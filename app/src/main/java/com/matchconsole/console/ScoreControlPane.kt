package com.matchconsole.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchconsole.scoreboard.ScoreUi
import com.matchconsole.scoreboard.TeamSide
import com.matchconsole.scoreboard.TeamState

/**
 * 区域 2：比分控制区。
 *
 * 每支球队一行主控（队名 / 比分 / +1 +2 +3 -1），
 * 下面一行是犯规数与暂停数的步进器；
 * 底部是节次切换。所有操作即时写入 ScoreboardViewModel。
 */
@Composable
fun ScoreControlPane(
    scores: ScoreUi,
    onScore: (TeamSide, Int) -> Unit,
    onFouls: (TeamSide, Int) -> Unit,
    onTimeouts: (TeamSide, Int) -> Unit,
    onRename: (TeamSide, String) -> Unit,
    onPrevPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renamingSide by remember { mutableStateOf<TeamSide?>(null) }

    SectionCard(
        title = "比分控制",
        modifier = modifier,
        trailing = {
            Text(
                text = "点队名可改名",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        TeamScoreRow(
            side = TeamSide.HOME,
            team = scores.home,
            accent = SideAccent.Home,
            onScore = { onScore(TeamSide.HOME, it) },
            onRenameClick = { renamingSide = TeamSide.HOME }
        )
        Spacer(Modifier.height(6.dp))
        TeamStatRow(
            team = scores.home,
            onFouls = { onFouls(TeamSide.HOME, it) },
            onTimeouts = { onTimeouts(TeamSide.HOME, it) }
        )

        Spacer(Modifier.height(12.dp))

        TeamScoreRow(
            side = TeamSide.AWAY,
            team = scores.away,
            accent = SideAccent.Away,
            onScore = { onScore(TeamSide.AWAY, it) },
            onRenameClick = { renamingSide = TeamSide.AWAY }
        )
        Spacer(Modifier.height(6.dp))
        TeamStatRow(
            team = scores.away,
            onFouls = { onFouls(TeamSide.AWAY, it) },
            onTimeouts = { onTimeouts(TeamSide.AWAY, it) }
        )

        Spacer(Modifier.height(12.dp))

        PeriodRow(
            title = scores.periodTitle,
            onPrev = onPrevPeriod,
            onNext = onNextPeriod
        )
    }

    renamingSide?.let { side ->
        val current = if (side == TeamSide.HOME) scores.home.name else scores.away.name
        RenameDialog(
            title = if (side == TeamSide.HOME) "修改主队名称" else "修改客队名称",
            initial = current,
            onDismiss = { renamingSide = null },
            onConfirm = { newName ->
                onRename(side, newName)
                renamingSide = null
            }
        )
    }
}

@Composable
private fun TeamScoreRow(
    side: TeamSide,
    team: TeamState,
    accent: Color,
    onScore: (Int) -> Unit,
    onRenameClick: () -> Unit
) {
    // 比分变化时底色闪一下，提供即时视觉反馈
    val scoreBg = rememberFlashColor(
        key = team.score,
        base = MaterialTheme.colorScheme.surfaceVariant,
        flash = accent
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onRenameClick)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = team.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (side == TeamSide.HOME) "主队 · 点击改名" else "客队 · 点击改名",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .width(54.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scoreBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = team.score.toString(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.width(6.dp))

        Row(
            modifier = Modifier.weight(1.95f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ConsoleButton(
                text = "-1",
                onClick = { onScore(-1) },
                modifier = Modifier.weight(1f),
                containerColor = SideAccent.Danger,
                contentColor = Color.White,
                fontSize = 17.sp
            )
            ConsoleButton(
                text = "+1",
                onClick = { onScore(1) },
                modifier = Modifier.weight(1f),
                containerColor = accent,
                contentColor = Color.White,
                fontSize = 17.sp
            )
            ConsoleButton(
                text = "+2",
                onClick = { onScore(2) },
                modifier = Modifier.weight(1f),
                containerColor = accent,
                contentColor = Color.White,
                fontSize = 17.sp
            )
            ConsoleButton(
                text = "+3",
                onClick = { onScore(3) },
                modifier = Modifier.weight(1f),
                containerColor = accent,
                contentColor = Color.White,
                fontSize = 17.sp
            )
        }
    }
}

/** 犯规 / 暂停 步进器行。 */
@Composable
private fun TeamStatRow(
    team: TeamState,
    onFouls: (Int) -> Unit,
    onTimeouts: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatStepper(
            label = "犯规",
            value = team.fouls,
            onDec = { onFouls(-1) },
            onInc = { onFouls(1) },
            modifier = Modifier.weight(1f)
        )
        StatStepper(
            label = "暂停",
            value = team.timeouts,
            onDec = { onTimeouts(-1) },
            onInc = { onTimeouts(1) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatStepper(
    label: String,
    value: Int,
    onDec: () -> Unit,
    onInc: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clickable(onClick = onDec),
            contentAlignment = Alignment.Center
        ) {
            Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(58.dp)
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value.toString(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clickable(onClick = onInc),
            contentAlignment = Alignment.Center
        ) {
            Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeriodRow(
    title: String,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConsoleButton(
            text = "◀ 上",
            onClick = onPrev,
            modifier = Modifier.weight(1f),
            containerColor = SideAccent.Neutral,
            contentColor = Color.White,
            fontSize = 15.sp
        )
        Box(
            modifier = Modifier
                .weight(1.5f)
                .padding(horizontal = 6.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        ConsoleButton(
            text = "下 ▶",
            onClick = onNext,
            modifier = Modifier.weight(1f),
            containerColor = SideAccent.Info,
            contentColor = Color.White,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 16) text = it },
                singleLine = true,
                label = { Text("队伍名称（最多 16 字）") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}