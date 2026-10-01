package com.matchconsole.receiver

import android.content.Context
import android.os.Environment
import com.matchconsole.common.LinkStats
import com.matchconsole.common.Logx
import com.matchconsole.common.NetUtils
import com.matchconsole.common.ScreenRecorder
import com.matchconsole.common.WebRtcCore
import com.matchconsole.sender.SdpObserverAdapter
import com.matchconsole.signaling.SignalingMessage
import com.matchconsole.signaling.SignalingServer
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
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 接收端 UI 状态。 */
data class ReceiverUiState(
    val localIp: String = "",
    val port: Int = DEFAULT_SIGNALING_PORT,
    val serverRunning: Boolean = false,
    val senderConnected: Boolean = false,
    val senderAddress: String? = null,
    val iceState: String = "未连接",
    val peerState: String = "-",
    val connected: Boolean = false,
    val stats: LinkStats = LinkStats(),
    val recording: Boolean = false,
    val lastRecordingPath: String? = null,
    val statusText: String = "等待发送端连接",
    val error: String? = null
) {
    val connectUri: String get() = NetUtils.buildConnectUri(localIp, port)
}

const val DEFAULT_SIGNALING_PORT = 8770

/**
 * 接收端会话总协调器（进程级单例）。
 *
 * 职责：
 *  - 拉起局域网信令服务（SignalingServer）并展示 IP/端口/二维码
 *  - 收到 Offer -> 建 PeerConnection -> 回 Answer
 *  - onTrack 后把远端视频轨挂到 Compose 里的 SurfaceViewRenderer
 *  - 轮询接收侧统计（码率/帧率/丢包/RTT）
 *  - 可选：把接收到的画面本地录成 MP4
 */
object ReceiverSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ReceiverUiState(localIp = NetUtils.primaryIpv4()))
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var appContext: Context? = null
    private var server: SignalingServer? = null
    private var serverMessagesJob: Job? = null
    private var serverStateJob: Job? = null

    private var peerConnection: PeerConnection? = null
    private var remoteTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var recorder: ScreenRecorder? = null
    private var statsJob: Job? = null

    private val candidateLock = Any()
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private var lastBytesReceived = 0L
    private var lastPollNs = 0L

    // ------------------------------------------------------------------
    // 信令服务
    // ------------------------------------------------------------------

    fun startServer(context: Context, port: Int = DEFAULT_SIGNALING_PORT) {
        appContext = context.applicationContext
        if (server != null) {
            Logx.w("信令服务已在运行")
            return
        }
        WebRtcCore.init(context.applicationContext)

        val s = SignalingServer(port)
        server = s

        // 先订阅再启动，避免 SharedFlow 无订阅者时丢消息
        serverMessagesJob = scope.launch {
            s.messages.collect { handleSignalingMessage(it) }
        }
        serverStateJob = scope.launch {
            s.state.collect { st ->
                _state.update {
                    it.copy(
                        port = st.port,
                        serverRunning = st.running,
                        senderConnected = st.clientConnected,
                        senderAddress = st.clientAddress,
                        statusText = when {
                            st.error != null -> "信令服务异常：${st.error}"
                            st.clientConnected -> "发送端已连接：${st.clientAddress ?: "-"}"
                            st.running -> "等待发送端连接"
                            else -> "信令服务未启动"
                        },
                        error = st.error
                    )
                }
            }
        }

        s.start()
        refreshLocalIp()
        _state.update { it.copy(port = port) }
    }

    fun stopServer() {
        serverMessagesJob?.cancel()
        serverMessagesJob = null
        serverStateJob?.cancel()
        serverStateJob = null
        server?.dispose()
        server = null
        closePeer()
        _state.update {
            it.copy(
                serverRunning = false,
                senderConnected = false,
                senderAddress = null,
                connected = false,
                iceState = "未连接",
                statusText = "信令服务已停止"
            )
        }
    }

    fun refreshLocalIp() {
        _state.update { it.copy(localIp = NetUtils.primaryIpv4()) }
    }

    // ------------------------------------------------------------------
    // 信令处理
    // ------------------------------------------------------------------

    private fun handleSignalingMessage(message: SignalingMessage) {
        when (message.type) {
            SignalingMessage.TYPE_OFFER -> {
                val sdp = message.sdp ?: return
                onRemoteOffer(sdp, message.sdpType ?: "offer")
            }

            SignalingMessage.TYPE_ICE -> {
                val candidate = message.candidate ?: return
                addRemoteIceCandidate(message.mid, message.mLineIndex, candidate)
            }

            SignalingMessage.TYPE_BYE -> {
                _toasts.tryEmit("发送端已停止推流")
                closePeer()
            }

            SignalingMessage.TYPE_HELLO -> {
                Logx.i("发送端握手: $message")
                _toasts.tryEmit("发送端已连接，正在建立媒体通道")
            }

            else -> Logx.w("未知信令类型: ${message.type}")
        }
    }

    private fun onRemoteOffer(sdp: String, type: String) {
        val ctx = appContext ?: return

        // 发送端重连时会重新发 Offer：先拆旧连接，避免复用已关闭的 PeerConnection
        closePeer()

        val pc = WebRtcCore.createPeerConnection(ctx, observer)
        peerConnection = pc

        val init = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        )
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)

        val description = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type),
            sdp
        )

        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Logx.i("已应用远端 Offer，开始生成 Answer")
                flushPendingCandidates()
                createAnswer(pc)
            }

            override fun onSetFailure(error: String?) {
                Logx.e("setRemoteDescription(offer) 失败: $error")
                _state.update { it.copy(error = "应用 Offer 失败：$error") }
            }
        }, description)
    }

    private fun createAnswer(pc: PeerConnection) {
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    _state.update { it.copy(error = "createAnswer 返回空 SDP") }
                    return
                }
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Logx.i("Answer 已就绪")
                        server?.send(
                            SignalingMessage.answer(sdp.description, sdp.type.canonicalForm())
                        )
                        startStatsLoop()
                    }

                    override fun onSetFailure(error: String?) {
                        _state.update { it.copy(error = "设置本地 Answer 失败：$error") }
                    }
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                Logx.e("createAnswer 失败: $error")
                _state.update { it.copy(error = "createAnswer 失败：$error") }
            }
        }, MediaConstraints())
    }

    private fun addRemoteIceCandidate(mid: String?, mLineIndex: Int, candidate: String) {
        val ice = IceCandidate(mid, mLineIndex, candidate)
        synchronized(candidateLock) {
            if (!remoteDescriptionSet) {
                pendingRemoteCandidates += ice
                return
            }
        }
        val ok = peerConnection?.addIceCandidate(ice) ?: false
        if (!ok) Logx.w("添加远端 ICE 失败：${candidate.take(48)}")
    }

    private fun flushPendingCandidates() {
        val pending: List<IceCandidate>
        synchronized(candidateLock) {
            remoteDescriptionSet = true
            if (pendingRemoteCandidates.isEmpty()) return
            pending = pendingRemoteCandidates.toList()
            pendingRemoteCandidates.clear()
        }
        val pc = peerConnection ?: return
        Logx.i("补齐缓存 ICE 候选 ${pending.size} 条")
        pending.forEach { pc.addIceCandidate(it) }
    }

    // ------------------------------------------------------------------
    // 渲染器挂载
    // ------------------------------------------------------------------

    /**
     * Compose 侧创建好 SurfaceViewRenderer 后调用。
     * 若远端轨道尚未到达，这里先记住引用，onTrack 时再自动挂上。
     */
    fun attachRenderer(view: SurfaceViewRenderer) {
        if (renderer === view) return
        renderer?.let { old -> remoteTrack?.removeSink(old) }
        renderer = view
        remoteTrack?.addSink(view)
        Logx.d("渲染器已挂载")
    }

    fun detachRenderer(view: SurfaceViewRenderer) {
        try {
            remoteTrack?.removeSink(view)
        } catch (_: Throwable) {
        }
        if (renderer === view) renderer = null
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                pollStats()
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun pollStats() {
        val pc = peerConnection ?: return
        pc.getStats { report ->
            var bytesReceived = 0L
            var fps = 0.0
            var width = 0
            var height = 0
            var packetsLost = 0L
            var jitterMs = 0.0
            var rttMs = 0.0
            var codec = "-"

            for (stat in report.statsMap.values) {
                when (stat.type) {
                    "inbound-rtp" -> {
                        val m = stat.members
                        val isVideo = m["kind"] == "video" ||
                            m.containsKey("framesDecoded") ||
                            m.containsKey("frameWidth")
                        if (isVideo) {
                            bytesReceived = asLong(m["bytesReceived"])
                            fps = asDouble(m["framesPerSecond"])
                            width = asInt(m["frameWidth"])
                            height = asInt(m["frameHeight"])
                            packetsLost = asLong(m["packetsLost"])
                            jitterMs = asDouble(m["jitter"]) * 1000.0
                        }
                    }

                    "candidate-pair" -> {
                        val m = stat.members
                        val succeeded = m["state"]?.toString() == "succeeded"
                        val nominated = m["nominated"] as? Boolean ?: false
                        if (succeeded && (nominated || rttMs == 0.0)) {
                            val candidateRtt = asDouble(m["currentRoundTripTime"]) * 1000.0
                            if (candidateRtt > 0) rttMs = candidateRtt
                        }
                    }

                    "codec" -> {
                        val mime = stat.members["mimeType"]?.toString() ?: ""
                        if (mime.startsWith("video", ignoreCase = true)) {
                            codec = mime.substringAfter('/').uppercase()
                        }
                    }
                }
            }

            val now = System.nanoTime()
            var kbps = 0L
            if (lastPollNs > 0 && now > lastPollNs) {
                val seconds = (now - lastPollNs) / 1_000_000_000.0
                val delta = (bytesReceived - lastBytesReceived).coerceAtLeast(0)
                kbps = (delta * 8.0 / seconds / 1000.0).toLong()
            }
            lastPollNs = now
            lastBytesReceived = bytesReceived

            _state.update {
                it.copy(
                    stats = LinkStats(
                        rttMs = rttMs.toLong(),
                        kbps = kbps,
                        packetsLost = packetsLost,
                        jitterMs = jitterMs.toLong(),
                        fps = fps,
                        width = width,
                        height = height,
                        codec = codec
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 本地录制（可选）
    // ------------------------------------------------------------------

    fun toggleRecording() {
        if (_state.value.recording) stopRecording() else startRecording()
    }

    fun startRecording() {
        val ctx = appContext ?: return
        val track = remoteTrack ?: run {
            _toasts.tryEmit("尚未收到画面，无法录制")
            return
        }
        if (recorder != null) return

        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            _toasts.tryEmit("无法创建录制目录")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(dir, "console_$stamp.mp4")

        val stats = _state.value.stats
        val rec = ScreenRecorder(
            outputFile = output,
            bitrateBps = if (stats.kbps > 0) (stats.kbps * 1000).toInt().coerceAtLeast(4_000_000)
            else 6_000_000,
            frameRate = 30
        )
        rec.start()
        track.addSink(rec)
        recorder = rec
        _state.update { it.copy(recording = true, lastRecordingPath = null) }
        _toasts.tryEmit("开始录制接收画面\n${output.name}")
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        remoteTrack?.removeSink(rec)
        val finished = rec.stop()
        _state.update { it.copy(recording = false, lastRecordingPath = finished?.absolutePath) }
        if (finished != null) {
            _toasts.tryEmit("录制完成：${finished.name} (${finished.length() / 1024} KB)")
        } else {
            _toasts.tryEmit("录制结束，但文件为空")
        }
    }

    // ------------------------------------------------------------------
    // 释放
    // ------------------------------------------------------------------

    private fun closePeer() {
        stopRecording()
        statsJob?.cancel()
        statsJob = null

        renderer?.let { r ->
            try {
                remoteTrack?.removeSink(r)
            } catch (_: Throwable) {
            }
        }
        remoteTrack?.let { track ->
            try {
                track.dispose()
            } catch (_: Throwable) {
            }
        }
        remoteTrack = null

        try {
            peerConnection?.close()
        } catch (_: Throwable) {
        }
        peerConnection?.dispose()
        peerConnection = null

        synchronized(candidateLock) {
            remoteDescriptionSet = false
            pendingRemoteCandidates.clear()
        }
        lastBytesReceived = 0
        lastPollNs = 0

        _state.update {
            it.copy(
                connected = false,
                iceState = "未连接",
                peerState = "-",
                stats = LinkStats()
            )
        }
    }

    // ------------------------------------------------------------------
    // PeerConnection 回调
    // ------------------------------------------------------------------

    private val observer = object : PeerConnection.Observer {

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Logx.d("接收端 SignalingState=$newState")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            applyIceState(newState)
        }

        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            applyIceState(newState)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            _state.update { it.copy(peerState = newState?.name ?: "UNKNOWN") }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            // 接收端把自己的候选回传给发送端
            server?.send(
                SignalingMessage.ice(
                    mid = candidate.sdpMid,
                    mLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        }

        override fun onAddStream(stream: MediaStream?) {
        }

        override fun onRemoveStream(stream: MediaStream?) {
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
        }

        override fun onRenegotiationNeeded() {
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            if (track !is VideoTrack) {
                Logx.w("收到非视频轨道: ${track.kind()}")
                return
            }
            remoteTrack?.removeSink(renderer)
            remoteTrack = track
            renderer?.let { track.addSink(it) }
            _state.update {
                it.copy(connected = true, statusText = "正在接收比赛画面")
            }
            _toasts.tryEmit("已收到比赛画面")
            Logx.i("远端视频轨已绑定渲染器")
        }
    }

    private fun applyIceState(newState: PeerConnection.IceConnectionState?) {
        val text = when (newState) {
            PeerConnection.IceConnectionState.CHECKING -> "连接中"
            PeerConnection.IceConnectionState.CONNECTED -> "已连接"
            PeerConnection.IceConnectionState.COMPLETED -> "已连接"
            PeerConnection.IceConnectionState.DISCONNECTED -> "连接中断"
            PeerConnection.IceConnectionState.FAILED -> "连接失败"
            PeerConnection.IceConnectionState.CLOSED -> "已关闭"
            else -> "未连接"
        }
        _state.update {
            it.copy(
                iceState = text,
                connected = text == "已连接",
                statusText = when (text) {
                    "已连接" -> "正在接收比赛画面"
                    "连接中" -> "正在建立点对点通道"
                    "连接中断", "连接失败" -> "与发送端的连接已断开"
                    else -> it.statusText
                }
            )
        }
    }

    private fun asLong(value: Any?): Long = when (value) {
        null -> 0L
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun asInt(value: Any?): Int = asLong(value).toInt()

    private fun asDouble(value: Any?): Double = when (value) {
        null -> 0.0
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private const val STATS_INTERVAL_MS = 1_000L
}