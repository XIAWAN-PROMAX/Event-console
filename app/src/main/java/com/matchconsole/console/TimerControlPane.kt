package com.matchconsole.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matchconsole.scoreboard.ScoreboardViewModel
import com.matchconsole.scoreboard.formatMainClock
import com.matchconsole.scoreboard.formatSeconds
import com.matchconsole.scoreboard.formatShotClock

/**
 * 区域 3：计时控制区。
 *
 * - 比赛时间大字号显示 + 开始 / 暂停 / 重置
 * - 补时（+1 分钟）/ 扣时（-1 分钟）
 * - 进攻时限（篮球 24 秒体系：24 / 14）
 * - 暂停计时（60 秒倒计时）
 *
 * 性能说明：本组件只订阅低频道的数据（节次、运行标志），
 * 100ms 一跳的计时数字全部收敛到 [MainClockReadout] / [TimeoutClockReadout]
 * 两个叶子节点，避免这一整块面板（含十几个按钮）跟着计时器重组。
 */
@Composable
fun TimerControlPane(
    viewModel: ScoreboardViewModel,
    modifier: Modifier = Modifier
) {
    val scores by viewModel.scores.collectAsStateWithLifecycle()
    val flags by viewModel.clockFlags.collectAsStateWithLifecycle()

    SectionCard(title = "计时控制", modifier = modifier) {

        MainClockReadout(viewModel = viewModel, periodTitle = scores.periodTitle)

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConsoleButton(
                text = if (flags.mainRunning) "⏸ 暂停" else "▶ 开始",
                onClick = { viewModel.toggleMainClock() },
                modifier = Modifier.weight(1.4f),
                containerColor = if (flags.mainRunning) SideAccent.Away else SideAccent.Home,
                contentColor = Color.White,
                fontSize = 19.sp
            )
            ConsoleButton(
                text = "重置",
                onClick = { viewModel.resetMainClock() },
                modifier = Modifier.weight(1f),
                containerColor = SideAccent.Neutral,
                contentColor = Color.White,
                fontSize = 18.sp
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConsoleButton(
                text = "补时+1′",
                onClick = { viewModel.adjustMainClock(60_000L) },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            ConsoleButton(
                text = "扣时-1′",
                onClick = { viewModel.adjustMainClock(-60_000L) },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            ConsoleButton(
                text = "24秒",
                onClick = { viewModel.resetShotClock(24_000L) },
                modifier = Modifier.weight(1f),
                containerColor = SideAccent.Away,
                contentColor = Color.White,
                fontSize = 15.sp
            )
            ConsoleButton(
                text = "14秒",
                onClick = { viewModel.resetShotClock(14_000L) },
                modifier = Modifier.weight(1f),
                containerColor = SideAccent.AwayDeep,
                contentColor = Color.White,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConsoleButton(
                text = if (flags.shotEnabled) "关进攻时限" else "开进攻时限",
                onClick = { viewModel.toggleShotClockEnabled() },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            ConsoleButton(
                text = if (flags.shotRunning) "限时暂停" else "限时启动",
                onClick = { viewModel.toggleShotClock() },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        // 暂停计时（60 秒倒计时）
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                TimeoutClockReadout(viewModel = viewModel)
            }
            ConsoleButton(
                text = if (flags.timeoutRunning) "暂停" else "开始",
                onClick = { viewModel.toggleTimeoutClock() },
                modifier = Modifier.weight(0.85f),
                containerColor = SideAccent.Info,
                contentColor = Color.White,
                fontSize = 14.sp
            )
            ConsoleButton(
                text = "重置",
                onClick = { viewModel.resetTimeoutClock() },
                modifier = Modifier.weight(0.85f),
                containerColor = SideAccent.Neutral,
                contentColor = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

/** 比赛时间 + 进攻时限的大字号读数（唯一订阅 100ms 计时流的叶子）。 */
@Composable
private fun MainClockReadout(
    viewModel: ScoreboardViewModel,
    periodTitle: String
) {
    val clock by viewModel.clock.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B1220))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1.15f)) {
            Text(
                text = "比赛时间 · $periodTitle",
                fontSize = 10.sp,
                color = Color(0xFF9FB0C7),
                maxLines = 1
            )
            Text(
                text = formatMainClock(clock.mainClockMs),
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                color = if (clock.mainRunning) Color(0xFFFF4D4D) else Color(0xFFFFFFFF),
                maxLines = 1
            )
        }
        Column(Modifier.weight(0.85f), horizontalAlignment = Alignment.End) {
            Text(
                text = if (clock.shotEnabled) "进攻时限" else "进攻时限(关)",
                fontSize = 10.sp,
                color = Color(0xFF9FB0C7),
                maxLines = 1
            )
            Text(
                text = formatShotClock(clock.shotClockMs),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (clock.shotClockMs <= 5000 && clock.shotEnabled) Color(0xFFFF4D4D)
                else Color(0xFFFBBF24),
                maxLines = 1
            )
        }
    }
}

/** 暂停计时读数（叶子节点）。 */
@Composable
private fun TimeoutClockReadout(viewModel: ScoreboardViewModel) {
    val clock by viewModel.clock.collectAsStateWithLifecycle()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "暂停计时",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatSeconds(clock.timeoutClockMs) + "s",
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = if (clock.timeoutRunning) SideAccent.Danger
            else MaterialTheme.colorScheme.onSurface
        )
    }
}