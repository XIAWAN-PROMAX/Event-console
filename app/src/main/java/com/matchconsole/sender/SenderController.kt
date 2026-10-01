package com.matchconsole.sender

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.matchconsole.common.LinkStats
import com.matchconsole.common.Logx
import com.matchconsole.common.NetUtils
import com.matchconsole.common.ScreenRecorder
import com.matchconsole.common.WebRtcCore
import com.matchconsole.signaling.SignalingClient
import com.matchconsole.signaling.SignalingMessage
import com.matchconsole.signaling.SignalingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 发送端 UI 状态。 */
data class SenderUiState(
    val projectionActive: Boolean = false,
    val streaming: Boolean = false,
    val recording: Boolean = false,
    val signalingState: SignalingState = SignalingState.IDLE,
    val iceState: String = "未连接",
    val peerState: String = "-",
    val remoteEndpoint: String? = null,
    val localIp: String = "",
    val targetHeight: Int = 720,
    val fps: Int = 30,
    val bitrateMbps: Int = 6,
    val stats: LinkStats = LinkStats(),
    val recordedFrames: Long = 0L,
    val lastRecordingPath: String? = null,
    val statusText: String = "待启动",
    val error: String? = null
)

/**
 * 发送端总协调器（进程级单例）。
 *
 * 持有并串联：ScreenCaptureService(前台服务) + SenderPeer(WebRTC) +
 * SignalingClient(TCP 信令) + ScreenRecorder(本地 MP4)。
 *
 * 之所以用单例而不是 ViewModel：屏幕采集要在 Activity 重建 / 切后台时继续存活，
 * UI 只作为观察者订阅 [state]。
 */
object SenderController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(SenderUiState(localIp = NetUtils.primaryIpv4()))
    val state: StateFlow<SenderUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var appContext: Context? = null
    private var peer: SenderPeer? = null
    private var signaling: SignalingClient? = null
    private var recorder: ScreenRecorder? = null
    private var signalingJob: Job? = null
    private var statsJob: Job? = null

    // ------------------------------------------------------------------
    // 基础设置
    // ------------------------------------------------------------------

    fun bind(context: Context) {
        appContext = context.applicationContext
        refreshLocalIp()
    }

    fun refreshLocalIp() {
        val ip = NetUtils.primaryIpv4()
        _state.update { it.copy(localIp = ip) }
    }

    fun setTargetHeight(height: Int) {
        _state.update { it.copy(targetHeight = height) }
    }

    fun setFps(fps: Int) {
        _state.update { it.copy(fps = fps) }
    }

    fun setBitrateMbps(mbps: Int) {
        _state.update { it.copy(bitrateMbps = mbps) }
    }

    // ------------------------------------------------------------------
    // 屏幕采集（由前台服务回调）
    // ------------------------------------------------------------------

    /**
     * 前台服务已 startForeground(mediaProjection) 之后调用。
     * Android 14+ 必须满足这个时序才能成功获取 MediaProjection。
     */
    fun attachProjection(context: Context, permissionData: Intent) {
        appContext = context.applicationContext
        if (peer != null) {
            Logx.w("采集已在运行，忽略重复 attach")
            return
        }
        val current = _state.value
        val p = SenderPeer(context.applicationContext, peerListener)

        try {
            p.startCapture(
                permissionData = permissionData,
                targetHeight = current.targetHeight,
                fps = current.fps,
                bitrateMbps = current.bitrateMbps
            )
        } catch (t: Throwable) {
            Logx.e("启动屏幕采集失败", t)
            _state.update {
                it.copy(
                    projectionActive = false,
                    statusText = "采集启动失败",
                    error = t.message ?: "启动屏幕采集失败"
                )
            }
            _toasts.tryEmit("屏幕采集启动失败：${t.message}")
            ScreenCaptureService.stop(context.applicationContext)
            return
        }

        peer = p
        _state.update {
            it.copy(
                projectionActive = true,
                statusText = "采集中，等待连接接收端",
                error = null
            )
        }
        _toasts.tryEmit("屏幕采集已开始")
    }

    /** UI 主动停止采集：先停服务，服务 onDestroy 再回收资源。 */
    fun stopProjection() {
        val ctx = appContext ?: return
        ScreenCaptureService.stop(ctx)
        releaseAll()
    }

    /** 服务销毁时回调。幂等。 */
    fun onServiceDestroyed() {
        releaseAll()
    }

    // ------------------------------------------------------------------
    // 推流 / 信令
    // ------------------------------------------------------------------

    /**
     * 连接接收端并开始推流。
     * @param host 接收端 IP
     * @param port 接收端信令端口
     */
    fun startStream(host: String, port: Int) {
        val ctx = appContext ?: return
        val p = peer
        if (p == null) {
            _toasts.tryEmit("请先开始屏幕采集")
            return
        }
        if (_state.value.streaming) {
            _toasts.tryEmit("已在推流中")
            return
        }

        WebRtcCore.init(ctx)

        val client = SignalingClient()
        signaling = client

        // 必须先订阅再连接：SharedFlow 无订阅者时 tryEmit 的消息会被直接丢弃
        signalingJob = scope.launch {
            client.messages.collect { message -> handleSignalingMessage(message) }
        }
        scope.launch {
            client.state.collect { s ->
                _state.update { it.copy(signalingState = s) }
            }
        }

        scope.launch {
            _state.update { it.copy(statusText = "正在连接 $host:$port …", error = null) }
            val result = client.connect(host, port)
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            streaming = true,
                            remoteEndpoint = "$host:$port",
                            statusText = "信令已连接，正在协商媒体"
                        )
                    }
                    _toasts.tryEmit("已连接接收端，开始协商")
                    p.createOffer()
                    startStatsLoop()
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            streaming = false,
                            statusText = "连接接收端失败",
                            error = t.message ?: "连接失败"
                        )
                    }
                    _toasts.tryEmit("连接失败：${t.message}")
                }
            )
        }
    }

    private fun handleSignalingMessage(message: SignalingMessage) {
        val p = peer ?: return
        when (message.type) {
            SignalingMessage.TYPE_ANSWER -> {
                val sdp = message.sdp ?: return
                p.applyRemoteAnswer(sdp, message.sdpType ?: "answer")
            }

            SignalingMessage.TYPE_ICE -> {
                val candidate = message.candidate ?: return
                p.addRemoteIceCandidate(message.mid, message.mLineIndex, candidate)
            }

            SignalingMessage.TYPE_BYE -> {
                _toasts.tryEmit("接收端已断开")
                stopStream()
            }

            SignalingMessage.TYPE_HELLO -> {
                Logx.i("接收端握手: role=${message.role}")
            }

            else -> Logx.w("未知信令类型: ${message.type}")
        }
    }

    fun stopStream() {
        signalingJob?.cancel()
        signalingJob = null
        signaling?.send(SignalingMessage.bye("sender stopped"))
        signaling?.close()
        signaling = null
        statsJob?.cancel()
        statsJob = null

        _state.update {
            it.copy(
                streaming = false,
                remoteEndpoint = null,
                iceState = "未连接",
                peerState = "-",
                stats = LinkStats(),
                statusText = if (it.projectionActive) "采集中，等待连接接收端" else "待启动"
            )
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                peer?.pollStats { stats ->
                    _state.update { it.copy(stats = stats) }
                }
                recorder?.let { rec ->
                    val (encoded, dropped) = rec.stats()
                    _state.update { it.copy(recordedFrames = encoded - dropped) }
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------
    // 本地录制
    // ------------------------------------------------------------------

    fun startRecording() {
        val ctx = appContext ?: return
        val p = peer ?: run {
            _toasts.tryEmit("请先开始屏幕采集")
            return
        }
        if (recorder != null) return

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            _toasts.tryEmit("无法创建录制目录")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(dir, "match_$stamp.mp4")

        val current = _state.value
        val rec = ScreenRecorder(
            outputFile = output,
            bitrateBps = current.bitrateMbps * 1_000_000,
            frameRate = current.fps
        )
        rec.start()
        p.attachSink(rec)
        recorder = rec

        _state.update {
            it.copy(recording = true, lastRecordingPath = null, recordedFrames = 0L)
        }
        _toasts.tryEmit("开始本地录制\n${output.name}")
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        peer?.detachSink(rec)
        val finished = rec.stop()
        _state.update {
            it.copy(
                recording = false,
                lastRecordingPath = finished?.absolutePath
            )
        }
        if (finished != null) {
            _toasts.tryEmit("录制完成：${finished.name} (${finished.length() / 1024} KB)")
        } else {
            _toasts.tryEmit("录制结束，但文件为空（可能未采集到画面）")
        }
    }

    fun toggleRecording() {
        if (_state.value.recording) stopRecording() else startRecording()
    }

    // ------------------------------------------------------------------
    // 资源释放
    // ------------------------------------------------------------------

    private fun releaseAll() {
        stopRecording()
        stopStream()
        peer?.stopCapture()
        peer = null
        _state.update {
            it.copy(
                projectionActive = false,
                streaming = false,
                recording = false,
                iceState = "未连接",
                peerState = "-",
                stats = LinkStats(),
                statusText = "待启动"
            )
        }
        Logx.i("发送端资源已全部释放")
    }

    private val peerListener = object : SenderPeer.Listener {

        override fun onLocalSdp(sdp: SessionDescription) {
            signaling?.send(
                SignalingMessage.offer(sdp.description, sdp.type.canonicalForm())
            )
            Logx.i("已下发 Offer")
        }

        override fun onLocalIceCandidate(candidate: IceCandidate) {
            signaling?.send(
                SignalingMessage.ice(
                    mid = candidate.sdpMid,
                    mLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            )
        }

        override fun onIceState(state: PeerConnection.IceConnectionState) {
            val text = when (state) {
                PeerConnection.IceConnectionState.CHECKING -> "连接中"
                PeerConnection.IceConnectionState.CONNECTED -> "已连接"
                PeerConnection.IceConnectionState.COMPLETED -> "已连接"
                PeerConnection.IceConnectionState.DISCONNECTED -> "连接中断"
                PeerConnection.IceConnectionState.FAILED -> "连接失败"
                PeerConnection.IceConnectionState.CLOSED -> "已关闭"
                else -> "未连接"
            }
            _state.update { it.copy(iceState = text) }
        }

        override fun onPeerState(text: String) {
            _state.update { it.copy(peerState = text) }
        }

        override fun onError(message: String) {
            _state.update { it.copy(error = message, statusText = message) }
            _toasts.tryEmit(message)
            // 系统回收了投屏权限：整体停掉，避免留下半死状态
            if (message.contains("屏幕采集已停止")) {
                scope.launch { stopProjection() }
            }
        }
    }

    private const val STATS_INTERVAL_MS = 1_000L
}