package com.matchconsole.common

/**
 * 链路实时指标，发送端 / 接收端共用。
 */
data class LinkStats(
    val rttMs: Long = 0L,
    val kbps: Long = 0L,
    val packetsLost: Long = 0L,
    val jitterMs: Long = 0L,
    val fps: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "-"
) {
    val resolutionText: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else "-"

    val bitrateText: String
        get() = if (kbps > 0) "%.2f Mbps".format(kbps / 1000.0) else "-"

    val fpsText: String get() = if (fps > 0) "%.0f fps".format(fps) else "-"
}