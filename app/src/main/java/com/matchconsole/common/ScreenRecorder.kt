package com.matchconsole.common

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer

/**
 * 本地录屏：MediaCodec(H.264) + MediaMuxer(MP4)。
 *
 * 实现要点（重要）：
 * Android 14 起，同一个 MediaProjection 实例只允许调用一次 createVirtualDisplay()。
 * 因此**不能**用第二个 VirtualDisplay + MediaRecorder 来录屏，否则会与 WebRTC 的
 * ScreenCapturerAndroid 冲突。
 *
 * 这里改用「复用 WebRTC 的视频帧」方案：把本类作为 VideoSink 挂到 VideoTrack 上，
 * 直接吃编码前的 I420 帧，用 ByteBuffer 喂给 MediaCodec，再封装成 MP4。
 * 好处：零额外屏幕投影、录制内容与推流内容完全一致、无第二次授权弹窗。
 *
 * 该类被 sender（录制本机屏幕）与 receiver（录制接收到的画面）共用，故放在 common。
 */
class ScreenRecorder(
    private val outputFile: File,
    private val bitrateBps: Int,
    private val frameRate: Int,
    private val keyFrameIntervalSec: Int = 2
) : VideoSink {

    private val lock = Any()

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false

    private var encodeWidth = 0
    private var encodeHeight = 0

    private var firstPtsUs = -1L
    private var lastPtsUs = 0L
    private var encodedFrames = 0L
    private var droppedFrames = 0L

    @Volatile
    private var running = false

    val file: File get() = outputFile

    fun start() {
        running = true
        Logx.i("本地录制已开始 -> ${outputFile.absolutePath}")
    }

    /** 统计信息，供 UI 显示。 */
    fun stats(): Pair<Long, Long> = encodedFrames to droppedFrames

    override fun onFrame(frame: VideoFrame) {
        // 注意：这里**不能**调用 frame.release()。
        // org.webrtc.VideoSink#onFrame 传入的 VideoFrame 由调用方持有并自动释放，
        // 接收方只有在需要跨回调持有引用时才 retain()。自行 release() 会造成
        // 引用计数下溢（native 双重释放），表现为一开录就闪退 / 编码器崩溃。
        if (!running) return
        synchronized(lock) {
            if (running) {
                try {
                    encodeFrame(frame)
                } catch (t: Throwable) {
                    Logx.e("录制编码异常", t)
                }
            }
        }
    }

    private fun encodeFrame(frame: VideoFrame) {
        val buffer = frame.buffer
        val i420 = buffer.toI420() ?: return
        try {
            val width = i420.width
            val height = i420.height
            if (width <= 0 || height <= 0) return

            val codec = ensureEncoder(width, height) ?: return

            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) {
                droppedFrames++
                return
            }

            val ptsUs = resolvePtsUs(frame.timestampNs)

            // 尽量把这一帧写进编码器输入缓冲。无论成功与否，都必须把
            // dequeue 出来的 input index 归还给编码器；否则缓冲会被耗尽，
            // dequeueInputBuffer 之后一直返回 -1，编码器彻底停摆（录出来是空文件）。
            var filled = false
            val image = codec.getInputImage(inputIndex)
            if (image != null) {
                try {
                    fillImage(image, i420)
                    filled = true
                } catch (t: Throwable) {
                    Logx.w("填充编码器 Image 失败: ${t.message}")
                }
            } else {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    try {
                        fillNv12Fallback(inputBuffer, i420)
                        filled = true
                    } catch (t: Throwable) {
                        Logx.w("填充编码器 Buffer 失败: ${t.message}")
                    }
                }
            }

            if (filled) {
                codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0)
                encodedFrames++
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                droppedFrames++
            }
            drainEncoder(drainAll = false)
        } finally {
            if (i420 !== buffer) {
                i420.release()
            }
        }
    }

    private fun resolvePtsUs(timestampNs: Long): Long {
        if (firstPtsUs < 0) {
            firstPtsUs = timestampNs / 1000
            lastPtsUs = 0
            return 0
        }
        val pts = timestampNs / 1000 - firstPtsUs
        lastPtsUs = if (pts <= lastPtsUs) lastPtsUs + 1 else pts
        return lastPtsUs
    }

    /** 首帧到达时才按真实分辨率创建编码器，避免与推流分辨率不一致。 */
    private fun ensureEncoder(width: Int, height: Int): MediaCodec? {
        encoder?.let { if (encodeWidth == width && encodeHeight == height) return it }

        encoder?.let { releaseCodec() }

        return try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            encodeWidth = width
            encodeHeight = height
            firstPtsUs = -1L
            Logx.i("录制编码器就绪 ${width}x$height @ ${bitrateBps / 1000} kbps")
            codec
        } catch (t: Throwable) {
            Logx.e("创建录制编码器失败", t)
            null
        }
    }

    private fun startMuxerIfNeeded(format: MediaFormat) {
        if (muxerStarted) return
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = muxer.addTrack(format)
        muxer.start()
        this.muxer = muxer
        muxerStarted = true
        Logx.i("MP4 封装器已启动")
    }

    private fun drainEncoder(drainAll: Boolean) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        val timeout = if (drainAll) OUTPUT_TIMEOUT_US else 0L
        var guard = 0

        while (guard++ < MAX_DRAIN_LOOPS) {
            val outIndex = try {
                codec.dequeueOutputBuffer(info, timeout)
            } catch (t: Throwable) {
                Logx.w("dequeueOutputBuffer 异常: ${t.message}")
                return
            }

            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!drainAll) return
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    startMuxerIfNeeded(codec.outputFormat)
                }

                outIndex >= 0 -> {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                info.flags = info.flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            if (muxerStarted) {
                                try {
                                    muxer?.writeSampleData(videoTrackIndex, outBuffer, info)
                                } catch (t: Throwable) {
                                    Logx.e("写入 MP4 失败", t)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                else -> return
            }
        }
    }

    /** 停止录制并完成 MP4 收尾。返回输出文件；失败返回 null。 */
    fun stop(): File? {
        synchronized(lock) {
            if (!running && encoder == null) return null
            running = false

            val codec = encoder
            if (codec != null) {
                try {
                    val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, lastPtsUs + 1,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                } catch (t: Throwable) {
                    Logx.w("发送 EOS 失败: ${t.message}")
                }
                try {
                    drainEncoder(drainAll = true)
                } catch (t: Throwable) {
                    Logx.w("收尾 drain 异常: ${t.message}")
                }
            }

            releaseCodec()

            try {
                if (muxerStarted) muxer?.stop()
            } catch (t: Throwable) {
                Logx.e("MP4 stop 失败（文件可能不完整）", t)
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1

            Logx.i("本地录制已停止，编码帧数=$encodedFrames 丢弃=$droppedFrames")
            return if (outputFile.exists() && outputFile.length() > 0) outputFile else null
        }
    }

    private fun releaseCodec() {
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            encoder?.release()
        } catch (_: Throwable) {
        }
        encoder = null
        encodeWidth = 0
        encodeHeight = 0
    }

    // ------------------------------------------------------------------
    // YUV 填充
    // ------------------------------------------------------------------

    private fun fillImage(image: Image, i420: VideoFrame.I420Buffer) {
        if (image.format != ImageFormat.YUV_420_888) {
            Logx.w("编码器输入 Image 格式非 YUV_420_888: ${image.format}")
        }
        val planes = image.planes
        val w = i420.width
        val h = i420.height

        copyPlane(planes[0], i420.dataY, i420.strideY, w, h)

        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        copyPlane(planes[1], i420.dataU, i420.strideU, cw, ch)
        copyPlane(planes[2], i420.dataV, i420.strideV, cw, ch)
    }

    private fun copyPlane(
        plane: Image.Plane,
        source: ByteBuffer,
        sourceStride: Int,
        width: Int,
        height: Int
    ) {
        val dst = plane.buffer
        val dstRowStride = plane.rowStride
        val dstPixelStride = plane.pixelStride
        // 半平面(NV12/NV21)格式下，V 平面的起始位置会比 U 平面偏移 1 字节。
        // 必须以平面缓冲的起始位置为基准写入，否则 U/V 会互相覆盖、画面发花。
        val base = dst.position()

        val srcRow = ByteArray(sourceStride)
        val dstRow = ByteArray(dstRowStride)

        for (row in 0 until height) {
            val srcOffset = row * sourceStride
            if (srcOffset >= source.capacity()) break
            source.position(srcOffset)
            val readable = minOf(sourceStride, source.remaining())
            source.get(srcRow, 0, readable)

            java.util.Arrays.fill(dstRow, 0)
            if (dstPixelStride == 1) {
                System.arraycopy(srcRow, 0, dstRow, 0, minOf(width, dstRowStride, readable))
            } else {
                var i = 0
                while (i < width) {
                    val pos = i * dstPixelStride
                    if (pos >= dstRowStride || i >= readable) break
                    dstRow[pos] = srcRow[i]
                    i++
                }
            }

            val writePos = base + row * dstRowStride
            if (writePos >= dst.capacity()) break
            dst.position(writePos)
            val writable = minOf(dstRowStride, dst.remaining())
            if (writable <= 0) break
            dst.put(dstRow, 0, writable)
        }
    }

    /**
     * 极少数编码器不支持 getInputImage() 时的兜底路径：按 NV12 布局写入。
     */
    private fun fillNv12Fallback(dst: ByteBuffer, i420: VideoFrame.I420Buffer) {
        val w = i420.width
        val h = i420.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2

        dst.clear()
        val row = ByteArray(w)

        for (y in 0 until h) {
            i420.dataY.position(y * i420.strideY)
            val n = minOf(w, i420.dataY.remaining())
            if (n <= 0) break
            i420.dataY.get(row, 0, n)
            dst.put(row, 0, w)
        }

        val chromaRow = ByteArray(w)
        for (y in 0 until ch) {
            val uOffset = y * i420.strideU
            val vOffset = y * i420.strideV
            for (x in 0 until cw) {
                val up = uOffset + x
                val vp = vOffset + x
                chromaRow[x * 2] = if (up < i420.dataU.limit()) i420.dataU.get(up) else 0
                chromaRow[x * 2 + 1] = if (vp < i420.dataV.limit()) i420.dataV.get(vp) else 0
            }
            dst.put(chromaRow, 0, w)
        }
        dst.flip()
    }

    private companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val MAX_DRAIN_LOOPS = 200
    }
}