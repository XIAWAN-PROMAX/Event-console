package com.matchconsole.sender

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import com.matchconsole.common.LinkStats
import com.matchconsole.common.Logx
import com.matchconsole.common.WebRtcCore
import org.webrtc.*

/**
 * 发送端 WebRTC 推流核心。
 *
 * 职责：
 *  - MediaProjection -> ScreenCapturerAndroid 采集屏幕
 *  - 建立 PeerConnection / VideoSource / VideoTrack
 *  - 生成 Offer、应用 Answer、交换 ICE Candidate
 *  - 通过 SDP 改写控制码率上限（局域网 4~8 Mbps）
 *  - 输出链路统计（码率 / 帧率 / RTT / 丢包）
 */
class SenderPeer(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLocalSdp(sdp: SessionDescription)
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceState(state: PeerConnection.IceConnectionState)
        fun onPeerState(text: String)
        fun onError(message: String)
    }

    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var rtpSender: RtpSender? = null

    private var captureWidth = 1280
    private var captureHeight = 720
    private var captureFps = 30
    private var maxBitrateKbps = 6000

    private var lastBytesSent = 0L
    private var lastPollNs = 0L

    private val candidateLock = Any()
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private val factory: PeerConnectionFactory
        get() = WebRtcCore.factory(context)

    val isCapturing: Boolean get() = capturer != null

    // ------------------------------------------------------------------
    // 采集 + 推流启动
    // ------------------------------------------------------------------

    /**
     * 启动屏幕采集与推流轨道。
     *
     * @param permissionData MediaProjection 授权返回的 Intent（Activity#onActivityResult 的 data）
     */
    fun startCapture(
        permissionData: Intent,
        targetHeight: Int,
        fps: Int,
        bitrateMbps: Int
    ) {
        captureFps = fps
        maxBitrateKbps = bitrateMbps * 1000

        val size = computeCaptureSize(targetHeight)
        captureWidth = size.first
        captureHeight = size.second

        val projectionCallback = object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                Logx.w("MediaProjection 被系统终止（用户停止录屏或权限回收）")
                listener.onError("屏幕采集已停止：用户或系统结束了投屏授权")
            }
        }

        // ScreenCapturerAndroid 内部在 startCapture 时调用 getMediaProjection()。
        // Android 14 要求此时已存在 mediaProjection 类型的前台服务，调用方已保证。
        val screenCapturer = ScreenCapturerAndroid(permissionData, projectionCallback)

        val helper = SurfaceTextureHelper.create("mc-capture", WebRtcCore.eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        // isScreencast = true：告诉编码器这是屏幕内容，允许更长的关键帧间隔与更高 QP
        val source = factory.createVideoSource(true)
        videoSource = source

        screenCapturer.initialize(helper, context, source.capturerObserver)
        screenCapturer.startCapture(captureWidth, captureHeight, captureFps)
        capturer = screenCapturer

        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        track.setEnabled(true)
        videoTrack = track

        Logx.i("屏幕采集已启动 ${captureWidth}x$captureHeight @ $captureFps fps，码率上限 ${bitrateMbps}Mbps")

        ensurePeerConnection()
        rtpSender = peerConnection?.addTrack(track, listOf(STREAM_ID))
    }

    private fun ensurePeerConnection(): PeerConnection {
        peerConnection?.let { return it }
        val pc = WebRtcCore.createPeerConnection(context, observer)
        peerConnection = pc
        return pc
    }

    /** 屏幕与编码分辨率按屏幕真实宽高比计算，避免画面被拉伸变形。 */
    private fun computeCaptureSize(targetHeight: Int): Pair<Int, Int> {
        val fallback = 1280 to 720
        val dm = context.getSystemService(DisplayManager::class.java) ?: return fallback
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return fallback
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        if (sw <= 0 || sh <= 0) return fallback

        val longSide = maxOf(sw, sh).toDouble()
        val shortSide = minOf(sw, sh).toDouble()

        // 同时受「目标高度」和「最大 1920 宽」约束
        val scale = minOf(targetHeight / shortSide, MAX_WIDTH / longSide)
        val h = (shortSide * scale).toInt() and 0xFFFFFFFE.toInt()
        val w = (longSide * scale).toInt() and 0xFFFFFFFE.toInt()
        if (w <= 0 || h <= 0) return fallback
        return w to h
    }

    // ------------------------------------------------------------------
    // SDP / ICE
    // ------------------------------------------------------------------

    fun createOffer() {
        val pc = ensurePeerConnection()
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    listener.onError("createOffer 返回空 SDP")
                    return
                }
                // 改写 SDP 带宽行，把码率上限压到配置值（WebRTC 默认会低估局域网带宽）
                val munged = mungeBandwidth(sdp.description, maxBitrateKbps)
                val local = SessionDescription(sdp.type, munged)
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Logx.i("本地 Offer 已就绪")
                        listener.onLocalSdp(local)
                    }

                    override fun onSetFailure(error: String?) {
                        listener.onError("setLocalDescription 失败: $error")
                    }
                }, local)
            }

            override fun onCreateFailure(error: String?) {
                listener.onError("createOffer 失败: $error")
            }
        }, MediaConstraints())
    }

    fun applyRemoteAnswer(sdp: String, type: String) {
        val pc = peerConnection ?: return
        val description = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type),
            sdp
        )
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Logx.i("远端 Answer 已应用")
                flushPendingCandidates()
            }

            override fun onSetFailure(error: String?) {
                listener.onError("setRemoteDescription 失败: $error")
            }
        }, description)
    }

    /**
     * ICE Candidate 可能早于 Answer 到达。
     * 远端描述未设置时先缓存，否则 addIceCandidate 会直接失败并丢掉候选。
     */
    fun addRemoteIceCandidate(mid: String?, mLineIndex: Int, candidate: String) {
        val ice = IceCandidate(mid, mLineIndex, candidate)
        synchronized(candidateLock) {
            if (!remoteDescriptionSet) {
                pendingRemoteCandidates += ice
                Logx.d("缓存远端 ICE（Answer 未到）")
                return
            }
        }
        val ok = peerConnection?.addIceCandidate(ice) ?: false
        if (!ok) {
            Logx.w("添加远端 ICE Candidate 失败：${candidate.take(48)}")
        }
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

    /**
     * 在视频 m= 行后插入 b=AS / b=TIAS 带宽行。
     * 这是控制 WebRTC 出流码率最稳妥、且不依赖具体版本 SDK 内部 API 的做法。
     */
    private fun mungeBandwidth(sdp: String, maxKbps: Int): String {
        val lines = sdp.split("\r\n")
        val out = ArrayList<String>(lines.size + 4)
        var done = false
        for (line in lines) {
            out += line
            if (!done && line.startsWith("m=video")) {
                out += "b=AS:$maxKbps"
                out += "b=TIAS:${maxKbps * 1000}"
                done = true
            }
        }
        return out.joinToString("\r\n")
    }

    // ------------------------------------------------------------------
    // 录制挂载
    // ------------------------------------------------------------------

    /** 把本地录制器挂到视频轨上，录制内容与推流内容完全一致。 */
    fun attachSink(sink: VideoSink) {
        videoTrack?.addSink(sink)
    }

    fun detachSink(sink: VideoSink) {
        videoTrack?.removeSink(sink)
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    fun pollStats(onResult: (LinkStats) -> Unit) {
        val pc = peerConnection ?: return
        pc.getStats { report ->
            var bytesSent = 0L
            var fps = 0.0
            var width = 0
            var height = 0
            var rttMs = 0.0
            var packetsLost = 0L
            var jitter = 0.0
            var codec = "-"

            for (stat in report.statsMap.values) {
                when (stat.type) {
                    "outbound-rtp" -> {
                        val m = stat.members
                        val isVideo = m["kind"] == "video" ||
                            m.containsKey("framesEncoded") ||
                            m.containsKey("frameWidth")
                        if (isVideo) {
                            bytesSent = asLong(m["bytesSent"])
                            fps = asDouble(m["framesPerSecond"])
                            width = asInt(m["frameWidth"])
                            height = asInt(m["frameHeight"])
                        }
                    }

                    "remote-inbound-rtp" -> {
                        val m = stat.members
                        rttMs = asDouble(m["roundTripTime"]) * 1000.0
                        packetsLost = asLong(m["packetsLost"])
                        jitter = asDouble(m["jitter"]) * 1000.0
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
                val deltaBytes = (bytesSent - lastBytesSent).coerceAtLeast(0)
                kbps = (deltaBytes * 8.0 / seconds / 1000.0).toLong()
            }
            lastPollNs = now
            lastBytesSent = bytesSent

            onResult(
                LinkStats(
                    rttMs = rttMs.toLong(),
                    kbps = kbps,
                    packetsLost = packetsLost,
                    jitterMs = jitter.toLong(),
                    fps = fps,
                    width = width,
                    height = height,
                    codec = codec
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // 释放
    // ------------------------------------------------------------------

    fun stopCapture() {
        try {
            capturer?.stopCapture()
        } catch (t: Throwable) {
            Logx.w("stopCapture 异常: ${t.message}")
        }
        try {
            capturer?.dispose()
        } catch (_: Throwable) {
        }
        capturer = null

        try {
            surfaceTextureHelper?.dispose()
        } catch (_: Throwable) {
        }
        surfaceTextureHelper = null

        videoTrack?.let { track ->
            rtpSender?.let { sender ->
                try {
                    peerConnection?.removeTrack(sender)
                } catch (_: Throwable) {
                }
            }
            track.dispose()
        }
        rtpSender = null
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        try {
            peerConnection?.close()
        } catch (_: Throwable) {
        }
        peerConnection?.dispose()
        peerConnection = null

        lastBytesSent = 0
        lastPollNs = 0
        synchronized(candidateLock) {
            remoteDescriptionSet = false
            pendingRemoteCandidates.clear()
        }
        Logx.i("推流资源已释放")
    }

    private val observer = object : PeerConnection.Observer {

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Logx.d("SignalingState=$newState")
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            listener.onIceState(newState ?: PeerConnection.IceConnectionState.FAILED)
        }

        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            listener.onIceState(newState ?: PeerConnection.IceConnectionState.FAILED)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            listener.onPeerState(newState?.name ?: "UNKNOWN")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Logx.d("ICE receiving=$receiving")
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Logx.d("IceGatheringState=$newState")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            listener.onLocalIceCandidate(candidate)
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
        }
    }

    companion object {
        private const val VIDEO_TRACK_ID = "mc-screen-video"
        private const val STREAM_ID = "mc-stream"
        private const val MAX_WIDTH = 1920.0

        fun asLong(value: Any?): Long = when (value) {
            null -> 0L
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

        fun asInt(value: Any?): Int = asLong(value).toInt()

        fun asDouble(value: Any?): Double = when (value) {
            null -> 0.0
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}

/** 简化 SdpObserver 的样板代码。 */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}