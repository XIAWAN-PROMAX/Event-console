package com.matchconsole.signaling

import com.matchconsole.common.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** 接收端信令服务对外暴露的状态。 */
data class SignalingServerState(
    val port: Int,
    val running: Boolean = false,
    val clientConnected: Boolean = false,
    val clientAddress: String? = null,
    val error: String? = null
)

/**
 * 接收端信令服务。
 *
 * 同一时刻只服务一个发送端；新连接接入时会踢掉旧连接，避免重连时残留半开连接。
 */
class SignalingServer(private val port: Int) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var clientJob: Job? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(SignalingServerState(port = port))
    val state: StateFlow<SignalingServerState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<SignalingMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val messages: SharedFlow<SignalingMessage> = _messages.asSharedFlow()

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port), 4)
                serverSocket = socket
                _state.update { it.copy(running = true, error = null) }
                Logx.i("信令服务已启动，端口 $port")
                while (isActive) {
                    val incoming = socket.accept()
                    incoming.tcpNoDelay = true
                    Logx.i("发送端接入: ${incoming.inetAddress?.hostAddress}")
                    bindClient(incoming)
                }
            } catch (t: Throwable) {
                if (isActive) {
                    Logx.e("信令服务异常", t)
                    _state.update { it.copy(running = false, error = t.message ?: "未知错误") }
                }
            }
        }
    }

    private fun bindClient(socket: Socket) {
        closeClient()

        clientSocket = socket
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        _state.update {
            it.copy(
                clientConnected = true,
                clientAddress = socket.inetAddress?.hostAddress,
                error = null
            )
        }

        clientJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = SignalingMessage.parse(line) ?: continue
                    if (msg.type == SignalingMessage.TYPE_BYE) break
                    _messages.tryEmit(msg)
                }
            } catch (t: Throwable) {
                Logx.w("信令读取结束: ${t.message}")
            } finally {
                closeClient()
            }
        }
    }

    /** 非阻塞发送，写失败会主动断开当前连接。 */
    fun send(message: SignalingMessage) {
        scope.launch {
            writeMutex.withLock {
                val w = writer ?: return@withLock
                try {
                    w.write(message.toJson())
                    w.write("\n")
                    w.flush()
                } catch (t: Throwable) {
                    Logx.e("信令发送失败", t)
                    closeClient()
                }
            }
        }
    }

    fun closeClient() {
        try {
            clientSocket?.close()
        } catch (_: Throwable) {
        }
        clientSocket = null
        writer = null
        if (_state.value.clientConnected) {
            _state.update { it.copy(clientConnected = false, clientAddress = null) }
            Logx.i("发送端已断开")
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        clientJob?.cancel()
        clientJob = null
        closeClient()
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        _state.update { it.copy(running = false, clientConnected = false) }
        Logx.i("信令服务已停止")
    }

    /** 彻底释放，切换模式时调用。 */
    fun dispose() {
        stop()
        scope.cancel()
    }
}