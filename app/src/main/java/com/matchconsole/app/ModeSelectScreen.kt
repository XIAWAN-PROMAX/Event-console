package com.matchconsole.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchconsole.AppScreen
import com.matchconsole.console.SideAccent

/**
 * 模式选择页：同一 App 内含「发送端」与「接收端」两种角色。
 *
 * 横屏两栏大卡片，戴手套也能点中。
 */
@Composable
fun ModeSelectScreen(
    onSelect: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 标题区：左侧标题 + 右上角「关于我们」
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "双机赛事监看与记分控制台",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "两台设备连同一个 Wi-Fi。第一台采集游戏画面并推流，第二台接收画面并完成记分与计时。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            AboutEntry(onClick = { showAbout = true })
        }

        if (showAbout) {
            AboutDialog(onDismiss = { showAbout = false })
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ModeCard(
                title = "发送端",
                badge = "第一台 · 游戏机",
                lines = listOf(
                    "申请屏幕采集权限（MediaProjection）",
                    "WebRTC 点对点推流到接收端",
                    "同时本地录制 MP4（MediaCodec + MediaMuxer）",
                    "720p / 1080p · 30fps · 4~8 Mbps 可调"
                ),
                accent = SideAccent.Home,
                onClick = { onSelect(AppScreen.SENDER) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            ModeCard(
                title = "接收端",
                badge = "第二台 · 赛事控制台",
                lines = listOf(
                    "局域网信令服务 + 二维码配对",
                    "主画面区：WebRTC 拉流渲染（16:9 黑边填充）",
                    "比分控制区 / 计时控制区 / 状态与设置区",
                    "记分牌叠加层实时同步，数据自动持久化"
                ),
                accent = SideAccent.Away,
                onClick = { onSelect(AppScreen.RECEIVER) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "不使用 Miracast / AirPlay / Cast 等系统投屏协议；媒体全程由 WebRTC 在局域网内直连传输。",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 首页右上角「关于我们」入口。 */
@Composable
private fun AboutEntry(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "关于我们",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 关于我们弹窗。四行内容按需求逐行展示。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "关于我们",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
        },
        text = {
            Column {
                ABOUT_LINES.forEachIndexed { index, line ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Text(
                        text = line,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", fontWeight = FontWeight.Bold)
            }
        }
    )
}

private val ABOUT_LINES = listOf(
    "xiawan开发",
    "感谢你来使用这款软件",
    "这款软件已在github上开源",
    "本作品采用 GNU GPLv3 许可协议。"
)

@Composable
private fun ModeCard(
    title: String,
    badge: String,
    lines: List<String>,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = badge,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        lines.forEach { line ->
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
                Text(
                    text = line,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "点击进入 →",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}