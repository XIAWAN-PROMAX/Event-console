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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 发送端信令客户端。连接到接收端的 [SignalingServer]。
 */
class SignalingClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var readerJob: Job? = null

    @Volatile
    private var writer: BufferedWriter? = null

    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(SignalingState.IDLE)
    val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<SignalingMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val messages: SharedFlow<SignalingMessage> = _messages.asSharedFlow()

    fun currentState(): SignalingState = _state.value

    /** 连接接收端信令服务。超时 5s。 */
    suspend fun connect(host: String, port: Int): Result<Unit> {
        close()
        _state.value = SignalingState.CONNECTING
        return try {
            val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS.toInt())
                s
            }
            if (result == null) {
                _state.value = SignalingState.ERROR
                return Result.failure(IllegalStateException("连接超时：$host:$port"))
            }
            socket = result
            writer = BufferedWriter(OutputStreamWriter(result.getOutputStream(), Charsets.UTF_8))
            _state.value = SignalingState.CONNECTED
            Logx.i("已连接接收端信令服务 $host:$port")
            startReader(result)
            send(SignalingMessage.hello(SignalingMessage.ROLE_SENDER))
            Result.success(Unit)
        } catch (t: Throwable) {
            Logx.e("连接信令服务失败", t)
            _state.value = SignalingState.ERROR
            Result.failure(t)
        }
    }

    private fun startReader(s: Socket) {
        readerJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = SignalingMessage.parse(line) ?: continue
                    if (msg.type == SignalingMessage.TYPE_BYE) break
                    _messages.tryEmit(msg)
                }
            } catch (t: Throwable) {
                Logx.w("信令连接中断: ${t.message}")
            } finally {
                _state.value = SignalingState.DISCONNECTED
            }
        }
    }

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
                }
            }
        }
    }

    fun close() {
        readerJob?.cancel()
        readerJob = null
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        writer = null
        if (_state.value != SignalingState.IDLE) {
            _state.value = SignalingState.IDLE
        }
    }

    fun dispose() {
        close()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}