package com.matchconsole.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matchconsole.receiver.ReceiverSession
import com.matchconsole.receiver.ReceiverUiState
import com.matchconsole.scoreboard.ScoreboardViewModel

/**
 * 接收端「赛事控制台」主界面。
 *
 * 一台设备只显示一个 App，所以四个区域全部由本 Composable 在同一屏内排布：
 *
 *  常规模式
 *  ┌──────────────────────────────┬──────────────────┐
 *  │ 区域1 主画面（WebRTC 渲染）    │ 区域2 比分控制    │
 *  │  + 记分牌叠加层               │ 区域3 计时控制    │
 *  │  + 链路指标角标               │ 区域4 状态与设置  │
 *  └──────────────────────────────┴──────────────────┘
 *
 *  沉浸模式（[immersive]）
 *  ┌──────────────────────────────────────────────────┐
 *  │ 区域1 主画面铺满整屏                              │
 *  │  + 记分牌叠加层（置顶）                           │
 *  │  + 底部半透明快捷记分条                           │
 *  └──────────────────────────────────────────────────┘
 *
 * 性能说明（重要）：本层只订阅低频的 [ScoreboardViewModel.layout] / [scores]，
 * **不订阅** 100ms 一跳的计时流。否则整个控制台（含 WebRTC 的 AndroidView）
 * 会跟着 10Hz 重组，主画面必然卡顿。计时数据由具体显示它的叶子节点自行订阅。
 */
@Composable
fun ConsoleScreen(
    scoreboardViewModel: ScoreboardViewModel,
    receiver: ReceiverUiState,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val layout by scoreboardViewModel.layout.collectAsStateWithLifecycle()
    val scores by scoreboardViewModel.scores.collectAsStateWithLifecycle()

    MatchConsoleTheme(layout.themeMode) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val tablet = maxWidth >= 1000.dp
            val panelWidth = if (tablet) 420.dp else 336.dp

            if (immersive) {
                Box(modifier = Modifier.fillMaxSize()) {
                    VideoPane(
                        receiver = receiver,
                        layout = layout,
                        viewModel = scoreboardViewModel,
                        immersive = true,
                        onToggleImmersive = { onImmersiveChange(false) },
                        onToggleStats = scoreboardViewModel::toggleStats,
                        onTogglePure = scoreboardViewModel::togglePurePreview,
                        modifier = Modifier.fillMaxSize()
                    )

                    // 底部快捷记分条：看大画面的同时仍能立刻改比分。
                    // 纯净画面模式下连它一起屏蔽，做到画面上零遮挡。
                    if (!layout.purePreview) {
                        ImmersiveQuickBar(
                            viewModel = scoreboardViewModel,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    // ---------- 区域 1 ----------
                    VideoPane(
                        receiver = receiver,
                        layout = layout,
                        viewModel = scoreboardViewModel,
                        immersive = false,
                        onToggleImmersive = { onImmersiveChange(true) },
                        onToggleStats = scoreboardViewModel::toggleStats,
                        onTogglePure = scoreboardViewModel::togglePurePreview,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // ---------- 区域 2 / 3 / 4 ----------
                    Column(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScoreControlPane(
                            scores = scores,
                            onScore = scoreboardViewModel::changeScore,
                            onFouls = scoreboardViewModel::changeFouls,
                            onTimeouts = scoreboardViewModel::changeTimeouts,
                            onRename = scoreboardViewModel::renameTeam,
                            onPrevPeriod = scoreboardViewModel::previousPeriod,
                            onNextPeriod = scoreboardViewModel::nextPeriod
                        )

                        TimerControlPane(viewModel = scoreboardViewModel)

                        StatusPane(
                            receiver = receiver,
                            layout = layout,
                            onToggleOverlay = scoreboardViewModel::toggleOverlay,
                            onOverlayPosition = scoreboardViewModel::setOverlayPosition,
                            onToggleStats = scoreboardViewModel::toggleStats,
                            onTogglePure = scoreboardViewModel::togglePurePreview,
                            onScalingFill = scoreboardViewModel::setScalingFill,
                            onToggleTheme = scoreboardViewModel::toggleTheme,
                            onToggleRecording = { ReceiverSession.toggleRecording() },
                            onResetMatch = scoreboardViewModel::resetMatch,
                            onResetAll = scoreboardViewModel::resetAll
                        )

                        // 底部留白，保证最后一张卡片不被系统手势条遮挡
                        Box(modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }

            TransientMessageHost(
                messages = ReceiverSession.toasts,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 沉浸模式下底部有快捷记分条，提示条需要让位
                    .padding(bottom = if (immersive) 104.dp else 24.dp)
            )
        }
    }
}