package com.matchconsole.scoreboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchconsole.console.OverlayPalette

/**
 * 主画面上的半透明记分牌条。
 *
 * 该层只是「显示」，所有数据都来自 ScoreboardViewModel ——
 * 因此控制区任何一次点击都会立刻反映到这里。
 */
@Composable
fun ScoreboardOverlay(
    scores: ScoreUi,
    clock: ClockUi,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 按视频区高度自适应字号，保证在小窗和大屏上都清晰
        val videoHeight = maxHeight
        val compact = videoHeight < 160.dp
        val scoreSize = if (compact) 24.sp else 36.sp
        val clockSize = if (compact) 18.sp else 28.sp
        val nameSize = if (compact) 10.sp else 13.sp
        val subSize = if (compact) 9.sp else 11.sp
        val shotSize = if (compact) 12.sp else 14.sp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .background(OverlayPalette.Background)
                .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TeamBlock(
                team = scores.home,
                accent = OverlayPalette.HomeAccent,
                alignEnd = false,
                scoreSize = scoreSize,
                nameSize = nameSize,
                subSize = subSize,
                modifier = Modifier.weight(1f)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = if (compact) 8.dp else 16.dp)
            ) {
                Text(
                    text = scores.periodTitle,
                    color = OverlayPalette.SubText,
                    fontSize = subSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = formatMainClock(clock.mainClockMs),
                    color = if (clock.mainRunning) OverlayPalette.LedRed else OverlayPalette.Text,
                    fontSize = clockSize,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                if (clock.shotEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "时限",
                            color = OverlayPalette.SubText,
                            fontSize = subSize,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatShotClock(clock.shotClockMs),
                            color = if (clock.shotClockMs <= 5000) OverlayPalette.LedRed else OverlayPalette.AwayAccent,
                            fontSize = shotSize,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            TeamBlock(
                team = scores.away,
                accent = OverlayPalette.AwayAccent,
                alignEnd = true,
                scoreSize = scoreSize,
                nameSize = nameSize,
                subSize = subSize,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 视频**下方**那条独立记分牌。
 *
 * 与 [ScoreboardOverlay] 的区别：本组件是画面之外的一条实底横条，
 * 所以不做半透明、不占画面任何像素 —— 推流画面因此完全不被遮挡。
 * 高度固定，只做「显示」，所有操作仍在右侧控制区。
 */
@Composable
fun ScoreboardBand(
    scores: ScoreUi,
    clock: ClockUi,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BandHeight)
            .background(scheme.surfaceVariant)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BandTeam(
            team = scores.home,
            accent = OverlayPalette.HomeAccent,
            alignEnd = false,
            modifier = Modifier.weight(1f)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                text = scores.periodTitle,
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = formatMainClock(clock.mainClockMs),
                color = if (clock.mainRunning) OverlayPalette.LedRed else scheme.onSurface,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            if (clock.shotEnabled) {
                Text(
                    text = "进攻时限 ${formatShotClock(clock.shotClockMs)}",
                    color = if (clock.shotClockMs <= 5000) OverlayPalette.LedRed else scheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        BandTeam(
            team = scores.away,
            accent = OverlayPalette.AwayAccent,
            alignEnd = true,
            modifier = Modifier.weight(1f)
        )
    }
}

private val BandHeight = 72.dp

@Composable
private fun BandTeam(
    team: TeamState,
    accent: Color,
    alignEnd: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val horizontal = if (alignEnd) Alignment.End else Alignment.Start
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Column(
            horizontalAlignment = horizontal,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = team.name,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = team.score.toString(),
                color = scheme.onSurface,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                text = "犯规 ${team.fouls}  暂停 ${team.timeouts}",
                color = scheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
            )
        }
    }
}

@Composable
private fun TeamBlock(
    team: TeamState,
    accent: Color,
    alignEnd: Boolean,
    scoreSize: androidx.compose.ui.unit.TextUnit,
    nameSize: androidx.compose.ui.unit.TextUnit,
    subSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    val horizontal = if (alignEnd) Alignment.End else Alignment.Start
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = horizontal,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Text(
                text = team.name,
                color = accent,
                fontSize = nameSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = team.score.toString(),
                color = OverlayPalette.Text,
                fontSize = scoreSize,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                text = "犯规 ${team.fouls}  暂停 ${team.timeouts}",
                color = OverlayPalette.SubText,
                fontSize = subSize,
                maxLines = 1,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
            )
        }
    }
}

/** 视频区角落的链路指标浮层。 */
@Composable
fun LinkStatsOverlay(
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color(0xFFD7E0EE),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}