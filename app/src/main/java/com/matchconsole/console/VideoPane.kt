package com.matchconsole.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.webrtc.RendererCommon
import com.matchconsole.common.WebRtcCore
import com.matchconsole.receiver.ReceiverSession
import com.matchconsole.receiver.ReceiverUiState
import com.matchconsole.scoreboard.ClockFlags
import com.matchconsole.scoreboard.ClockUi
import com.matchconsole.scoreboard.LayoutUi
import com.matchconsole.scoreboard.LinkStatsOverlay
import com.matchconsole.scoreboard.OverlayPosition
import com.matchconsole.scoreboard.ScoreUi
import com.matchconsole.scoreboard.ScoreboardOverlay
import com.matchconsole.scoreboard.ScoreboardViewModel
import com.matchconsole.scoreboard.TeamSide
import com.matchconsole.scoreboard.formatMainClock

/**
 * 区域 1：主画面区。
 *
 * - WebRTC 远端画面以 16:9 / 原比例等比适配，黑边填充（SCALE_ASPECT_FIT）
 * - 顶部或底部叠加半透明记分牌条（受开关控制）
 * - 角落显示链路指标（可整体关闭，避免画面拥挤）
 * - 支持沉浸式预览：本组件独占全屏，控制面板让位
 *
 * 性能说明（重要）：
 * 计时器 100ms 改一次数据。若在本层直接订阅记分牌状态，
 * 包含 SurfaceViewRenderer 的 AndroidView 会跟着 10Hz 重组，主画面必然卡顿。
 * 因此记分牌数据只在 [ScoreboardOverlayHost] / [OverlayStatsHost] 两个叶子节点订阅。
 */
@Composable
fun VideoPane(
    receiver: ReceiverUiState,
    layout: LayoutUi,
    viewModel: ScoreboardViewModel,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    onToggleStats: () -> Unit,
    onTogglePure: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebRtcCore.createRenderer(ctx).also { renderer ->
                    ReceiverSession.attachRenderer(renderer)
                }
            },
            // 每次布局变化时同步缩放方式：
            // 适配 = 画面完整可见（可能有黑边）；裁切 = 铺满（左右/上下会切掉）
            update = { renderer ->
                renderer.setScalingType(
                    if (layout.scalingFill) RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    else RendererCommon.ScalingType.SCALE_ASPECT_FIT
                )
            },
            onRelease = { renderer ->
                ReceiverSession.detachRenderer(renderer)
                WebRtcCore.releaseRenderer(renderer)
            }
        )

        // 未连接时的占位提示
        if (!receiver.connected) {
            WaitingPlaceholder(
                receiver = receiver,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 记分牌叠加层：沉浸模式下固定在顶部，给底部快捷记分条让位
        // 纯净画面模式下整体屏蔽
        if (layout.overlayVisible && !layout.purePreview) {
            val atTop = immersive || layout.overlayPosition == OverlayPosition.TOP
            ScoreboardOverlayHost(
                viewModel = viewModel,
                modifier = Modifier.align(
                    if (atTop) Alignment.TopCenter else Alignment.BottomCenter
                )
            )
        }

        // 链路指标：与记分牌相反的一侧，避免互相遮挡
        if (layout.statsVisible && !layout.purePreview) {
            OverlayStatsHost(
                receiver = receiver,
                modifier = Modifier
                    .align(
                        if (!immersive && layout.overlayPosition == OverlayPosition.TOP) {
                            Alignment.BottomStart
                        } else {
                            Alignment.TopStart
                        }
                    )
                    .padding(10.dp)
            )
        }

        // 右上角操作区。
        // 纯净画面模式下只留一个恢复按钮，其余全部屏蔽，保证画面完整无遮挡。
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(if (layout.purePreview) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (layout.purePreview) {
                GlassChip(
                    label = "显示浮层",
                    onClick = onTogglePure,
                    dim = true
                )
                if (immersive) {
                    GlassChip(
                        label = "退出沉浸",
                        onClick = onToggleImmersive,
                        dim = true
                    )
                }
            } else {
                ConnectionBadge(receiver)
                GlassChip(
                    label = if (layout.statsVisible) "指标" else "指标关",
                    onClick = onToggleStats
                )
                GlassChip(label = "纯净画面", onClick = onTogglePure)
                GlassChip(
                    label = if (immersive) "退出沉浸" else "沉浸预览",
                    onClick = onToggleImmersive
                )
            }
        }
    }
}

/** 只订阅计时/比分投影的叶子节点，把 10Hz 重组限制在这一层。 */
@Composable
private fun ScoreboardOverlayHost(
    viewModel: ScoreboardViewModel,
    modifier: Modifier = Modifier
) {
    val scores: ScoreUi by viewModel.scores.collectAsStateWithLifecycle()
    val clock: ClockUi by viewModel.clock.collectAsStateWithLifecycle()
    ScoreboardOverlay(scores = scores, clock = clock, modifier = modifier)
}

/** 链路指标叶子节点：按需生成紧凑文案。 */
@Composable
private fun OverlayStatsHost(receiver: ReceiverUiState, modifier: Modifier = Modifier) {
    LinkStatsOverlay(lines = buildStatLines(receiver), modifier = modifier)
}

/**
 * 指标只保留 3 行，避免在画面上堆成一整块。
 * 完整数据仍在右侧「状态与设置」区。
 */
private fun buildStatLines(receiver: ReceiverUiState): List<String> {
    val stats = receiver.stats
    return listOf(
        receiver.iceState,
        "${stats.resolutionText} · ${stats.fpsText} · ${stats.bitrateText}",
        "延迟 ${if (stats.rttMs > 0) "${stats.rttMs}ms" else "-"} · 丢包 ${stats.packetsLost}"
    )
}

/** 画面上方的半透明小胶囊按钮。 */
@Composable
fun GlassChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            // dim = 更低的不透明度，纯净模式下不干扰画面
            .background(Color(if (dim) 0x660B1220 else 0xCC0B1220))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (dim) 8.dp else 10.dp,
                vertical = if (dim) 4.dp else 6.dp
            )
    ) {
        Text(
            text = label,
            color = if (dim) Color(0x99E5EAF2) else Color(0xFFE5EAF2),
            fontSize = if (dim) 10.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ConnectionBadge(receiver: ReceiverUiState) {
    val color = when {
        receiver.connected -> Color(0xFF16A34A)
        receiver.senderConnected -> Color(0xFFD97706)
        else -> Color(0xFF475569)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.85f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = when {
                receiver.connected -> "已连接"
                receiver.senderConnected -> "建立通道中"
                else -> "等待连接"
            },
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun WaitingPlaceholder(receiver: ReceiverUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "等待比赛画面",
            color = Color(0xFF9FB0C7),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "让第一台手机选择「发送端」，\n扫描二维码或输入 ${receiver.localIp}:${receiver.port}",
            color = Color(0xFF7C8CA3),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        receiver.error?.let { error ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 沉浸模式下底部的半透明快捷记分条：只看大画面时也能立刻改比分与计时。 */
@Composable
fun ImmersiveQuickBar(
    viewModel: ScoreboardViewModel,
    modifier: Modifier = Modifier
) {
    val scores: ScoreUi by viewModel.scores.collectAsStateWithLifecycle()
    val flags: ClockFlags by viewModel.clockFlags.collectAsStateWithLifecycle()
    val clock: ClockUi by viewModel.clock.collectAsStateWithLifecycle()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE60B1220))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        QuickStepper(
            name = scores.home.name,
            accent = SideAccent.Home,
            onDelta = { viewModel.changeScore(TeamSide.HOME, it) },
            modifier = Modifier.weight(1f)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(120.dp)
        ) {
            Text(
                text = formatMainClock(clock.mainClockMs),
                color = if (clock.mainRunning) Color(0xFFFF4D4D) else Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                text = scores.periodTitle,
                color = Color(0xFF9FB0C7),
                fontSize = 9.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ConsoleButton(
                    text = if (flags.mainRunning) "⏸" else "▶",
                    onClick = { viewModel.toggleMainClock() },
                    modifier = Modifier.weight(1f),
                    containerColor = if (flags.mainRunning) SideAccent.Away else SideAccent.Home,
                    contentColor = Color.White,
                    height = 34.dp,
                    fontSize = 14.sp
                )
                ConsoleButton(
                    text = "重置",
                    onClick = { viewModel.resetMainClock() },
                    modifier = Modifier.weight(1f),
                    containerColor = SideAccent.Neutral,
                    contentColor = Color.White,
                    height = 34.dp,
                    fontSize = 11.sp
                )
            }
        }

        QuickStepper(
            name = scores.away.name,
            accent = SideAccent.Away,
            onDelta = { viewModel.changeScore(TeamSide.AWAY, it) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickStepper(
    name: String,
    accent: Color,
    onDelta: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = name,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(1, 2, 3).forEach { delta ->
                ConsoleButton(
                    text = "+$delta",
                    onClick = { onDelta(delta) },
                    modifier = Modifier.weight(1f),
                    containerColor = accent,
                    contentColor = Color.White,
                    height = 44.dp,
                    fontSize = 16.sp
                )
            }
            ConsoleButton(
                text = "-1",
                onClick = { onDelta(-1) },
                modifier = Modifier.weight(1f),
                containerColor = SideAccent.Danger,
                contentColor = Color.White,
                height = 44.dp,
                fontSize = 16.sp
            )
        }
    }
}