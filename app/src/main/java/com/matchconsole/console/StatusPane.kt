package com.matchconsole.console

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchconsole.common.QrCodeUtils
import com.matchconsole.receiver.ReceiverUiState
import com.matchconsole.scoreboard.LayoutUi
import com.matchconsole.scoreboard.ThemeMode

/**
 * 区域 4：状态与设置区。
 *
 * 连接状态 / 本机 IP+端口+二维码 / 画面参数 /
 * 记分牌与快捷记分条开关 / 主题切换 / 接收端本地录制 / 一键重置。
 */
@Composable
fun StatusPane(
    receiver: ReceiverUiState,
    layout: LayoutUi,
    onToggleOverlay: () -> Unit,
    onToggleStats: () -> Unit,
    onTogglePure: () -> Unit,
    onToggleQuickBar: () -> Unit,
    onScalingFill: (Boolean) -> Unit,
    onToggleTheme: () -> Unit,
    onToggleRecording: () -> Unit,
    onResetMatch: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = "状态与设置", modifier = modifier) {

        InfoRow("连接状态", receiver.statusText)
        InfoRow("ICE / Peer", "${receiver.iceState} · ${receiver.peerState}")
        InfoRow("发送端", receiver.senderAddress ?: "未连接")
        InfoRow("本机地址", "${receiver.localIp}:${receiver.port}")

        Spacer(Modifier.height(8.dp))

        // 未连接时才展示二维码，连接后腾出空间给比赛操作
        if (!receiver.connected) {
            QrBlock(uri = receiver.connectUri)
            Spacer(Modifier.height(8.dp))
        }

        InfoRow("画面", "${receiver.stats.resolutionText} · ${receiver.stats.fpsText}")
        InfoRow("码率 / 编码", "${receiver.stats.bitrateText} · ${receiver.stats.codec}")
        InfoRow("延迟 / 抖动", "${receiver.stats.rttMs} ms · ${receiver.stats.jitterMs} ms")
        InfoRow("累计丢包", receiver.stats.packetsLost.toString())

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToggleChip(
                label = if (layout.overlayVisible) "记分牌：显示" else "记分牌：隐藏",
                selected = layout.overlayVisible,
                onClick = onToggleOverlay,
                modifier = Modifier.weight(1.3f)
            )
            ToggleChip(
                label = if (layout.quickBarVisible) "快捷记分条：开" else "快捷记分条：关",
                selected = layout.quickBarVisible,
                onClick = onToggleQuickBar,
                modifier = Modifier.weight(2f)
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToggleChip(
                label = if (layout.statsVisible) "画面指标：显示" else "画面指标：隐藏",
                selected = layout.statsVisible,
                onClick = onToggleStats,
                modifier = Modifier.weight(1f)
            )
            ToggleChip(
                label = if (layout.purePreview) "纯净画面：开" else "纯净画面：关",
                selected = layout.purePreview,
                onClick = onTogglePure,
                modifier = Modifier.weight(1f)
            )
        }

        if (layout.purePreview) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "纯净画面已开启：记分牌、指标、角标与快捷条全部屏蔽，画面零遮挡。" +
                    "点画面上方的「显示浮层」可恢复。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 14.sp
            )
        }

        Spacer(Modifier.height(6.dp))

        // 画面缩放方式：适配 = 完整可见；裁切 = 铺满但会切边
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToggleChip(
                label = "画面：适配",
                selected = !layout.scalingFill,
                onClick = { onScalingFill(false) },
                modifier = Modifier.weight(1f)
            )
            ToggleChip(
                label = "画面：裁切",
                selected = layout.scalingFill,
                onClick = { onScalingFill(true) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToggleChip(
                label = if (layout.themeMode == ThemeMode.DARK) "主题：深色" else "主题：浅色",
                selected = layout.themeMode == ThemeMode.DARK,
                onClick = onToggleTheme,
                modifier = Modifier.weight(1.3f)
            )
            ToggleChip(
                label = if (receiver.recording) "停止录制" else "录制接收画面",
                selected = receiver.recording,
                onClick = onToggleRecording,
                modifier = Modifier.weight(1.4f)
            )
        }

        receiver.lastRecordingPath?.let { path ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已保存：${path.substringAfterLast('/')}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConsoleButton(
                text = "一键重置比赛",
                onClick = onResetMatch,
                modifier = Modifier.weight(1.3f),
                containerColor = SideAccent.Danger,
                contentColor = Color.White,
                fontSize = 14.sp,
                height = 46.dp
            )
            ConsoleButton(
                text = "清空全部",
                onClick = onResetAll,
                modifier = Modifier.weight(1f),
                containerColor = SideAccent.Neutral,
                contentColor = Color.White,
                fontSize = 14.sp,
                height = 46.dp
            )
        }
    }
}

@Composable
private fun QrBlock(uri: String) {
    val bitmap = remember(uri) {
        QrCodeUtils.encode(uri, 512)?.asImageBitmap()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "发送端扫码连接二维码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(96.dp)
            )
        } else {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Text("二维码生成失败", fontSize = 10.sp, color = Color.Black)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "发送端扫码连接",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = uri,
                fontSize = 10.sp,
                color = Color(0xFF475569),
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "或在发送端手动输入 IP:端口",
                fontSize = 9.sp,
                color = Color(0xFF6B7280)
            )
        }
    }
}