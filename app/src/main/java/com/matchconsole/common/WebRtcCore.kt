package com.matchconsole.common

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer

/**
 * WebRTC 全局基础设施。
 *
 * - 进程内只创建一次 [PeerConnectionFactory]
 * - 全 App 共用一个 [EglBase]，接收端渲染、发送端编码器都复用它
 * - 局域网直连：[buildRtcConfig] 不配置 STUN/TURN，仅收集 host candidate
 */
object WebRtcCore {

    @Volatile
    private var initialized = false

    @Volatile
    private var factoryRef: PeerConnectionFactory? = null

    private val eglBaseLazy: EglBase by lazy { EglBase.create() }

    /** 全局 EGL 上下文。渲染器与编码器共用，避免多份 GL 上下文带来的额外开销。 */
    val eglBase: EglBase get() = eglBaseLazy

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val options = PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            initialized = true
            Logx.i("WebRtcCore 初始化完成")
        }
    }

    fun factory(context: Context): PeerConnectionFactory {
        init(context)
        factoryRef?.let { return it }
        synchronized(this) {
            factoryRef?.let { return it }
            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase.eglBaseContext,
                /* enableIntelVp8Encoder = */ true,
                /* enableH264HighProfile = */ true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            val created = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            factoryRef = created
            Logx.i("PeerConnectionFactory 创建完成")
            return created
        }
    }

    /**
     * 局域网直连配置。
     * iceServers 传空列表 -> 不依赖公网 STUN/TURN，只交换本地 host candidate。
     */
    fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableCpuOveruseDetection = true
        }
    }

    fun createPeerConnection(
        context: Context,
        observer: PeerConnection.Observer
    ): PeerConnection {
        val pc = factory(context).createPeerConnection(buildRtcConfig(), observer)
        requireNotNull(pc) { "createPeerConnection 返回 null" }
        return pc
    }

    /**
     * 创建可直接挂到 Compose AndroidView 上的渲染器。
     * 必须在主线程调用（SurfaceView 限制）。
     */
    fun createRenderer(context: Context, mirror: Boolean = false): SurfaceViewRenderer {
        val renderer = SurfaceViewRenderer(context)
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(mirror)
        return renderer
    }

    /** 安全释放渲染器。 */
    fun releaseRenderer(renderer: SurfaceViewRenderer?) {
        try {
            renderer?.release()
        } catch (t: Throwable) {
            Logx.w("释放渲染器异常: ${t.message}")
        }
    }
}