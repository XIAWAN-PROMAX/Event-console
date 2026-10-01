package com.matchconsole.sender

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matchconsole.common.NetUtils
import com.matchconsole.common.PermissionUtils
import com.matchconsole.console.ConsoleButton
import com.matchconsole.console.ConsoleTopBar
import com.matchconsole.console.InfoRow
import com.matchconsole.console.SectionCard
import com.matchconsole.console.SideAccent
import com.matchconsole.console.ToggleChip
import com.matchconsole.console.TransientMessageHost

/**
 * 发送端界面（第一台设备）。
 *
 * 左栏：采集参数 + 推流目标（扫码 / 手输 IP）
 * 右栏：运行状态与链路指标 + 本地录制
 */
@Composable
fun SenderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by SenderController.state.collectAsStateWithLifecycle()

    var endpoint by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var endpointError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        } else {
            Toast.makeText(context, "已取消屏幕采集授权", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        SenderController.bind(context)
        SenderController.refreshLocalIp()
        val missing = PermissionUtils.missing(context, PermissionUtils.requiredPermissions())
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    if (scanning) {
        QrScanScreen(
            onResult = { raw ->
                scanning = false
                val parsed = NetUtils.parseConnectUri(raw)
                if (parsed == null) {
                    endpointError = "二维码内容无法识别：$raw"
                } else {
                    endpointError = null
                    endpoint = "${parsed.first}:${parsed.second}"
                    SenderController.startStream(parsed.first, parsed.second)
                }
            },
            onCancel = { scanning = false }
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConsoleTopBar(
            title = "发送端",
            subtitle = "本机 ${state.localIp}  ·  ${state.statusText}",
            onSwitchMode = onBack
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---------------- 左栏 ----------------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionCard(title = "采集参数（下次开始采集生效）") {
                    Text("画面高度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ConsoleButton(
                            text = "720p",
                            onClick = { SenderController.setTargetHeight(720) },
                            modifier = Modifier.weight(1f),
                            containerColor = if (state.targetHeight == 720) SideAccent.Home else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (state.targetHeight == 720) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp
                        )
                        ConsoleButton(
                            text = "1080p",
                            onClick = { SenderController.setTargetHeight(1080) },
                            modifier = Modifier.weight(1f),
                            containerColor = if (state.targetHeight == 1080) SideAccent.Home else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (state.targetHeight == 1080) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("帧率", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(30, 60).forEach { fps ->
                            ConsoleButton(
                                text = "${fps}fps",
                                onClick = { SenderController.setFps(fps) },
                                modifier = Modifier.weight(1f),
                                containerColor = if (state.fps == fps) SideAccent.Info else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (state.fps == fps) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("码率上限", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(4, 6, 8).forEach { mbps ->
                            ConsoleButton(
                                text = "$mbps Mbps",
                                onClick = { SenderController.setBitrateMbps(mbps) },
                                modifier = Modifier.weight(1f),
                                containerColor = if (state.bitrateMbps == mbps) SideAccent.Away else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (state.bitrateMbps == mbps) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                SectionCard(title = "推流目标（接收端）") {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {
                            endpoint = it
                            endpointError = null
                        },
                        label = { Text("接收端 IP:端口，例如 192.168.1.10:8770") },
                        singleLine = true,
                        isError = endpointError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    endpointError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ConsoleButton(
                            text = "扫码连接",
                            onClick = { scanning = true },
                            modifier = Modifier.weight(1f),
                            containerColor = SideAccent.Info,
                            contentColor = Color.White,
                            fontSize = 15.sp
                        )
                        ConsoleButton(
                            text = "手动连接",
                            onClick = {
                                val parsed = NetUtils.parseConnectUri(endpoint)
                                if (parsed == null) {
                                    endpointError = "格式应为 IP:端口，例如 192.168.1.10:8770"
                                } else {
                                    SenderController.startStream(parsed.first, parsed.second)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            containerColor = SideAccent.Home,
                            contentColor = Color.White,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // ---------------- 右栏 ----------------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionCard(title = "屏幕采集与推流") {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ConsoleButton(
                            text = if (state.projectionActive) "停止采集" else "开始屏幕采集",
                            onClick = {
                                if (state.projectionActive) {
                                    SenderController.stopProjection()
                                } else {
                                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                                    if (mpm == null) {
                                        Toast.makeText(context, "设备不支持屏幕采集", Toast.LENGTH_SHORT).show()
                                    } else {
                                        consentLauncher.launch(mpm.createScreenCaptureIntent())
                                    }
                                }
                            },
                            modifier = Modifier.weight(1.3f),
                            containerColor = if (state.projectionActive) SideAccent.Danger else SideAccent.Home,
                            contentColor = Color.White,
                            height = 58.dp,
                            fontSize = 16.sp
                        )
                        ConsoleButton(
                            text = if (state.streaming) "停止推流" else "开始推流",
                            onClick = {
                                if (state.streaming) {
                                    SenderController.stopStream()
                                } else {
                                    val parsed = NetUtils.parseConnectUri(endpoint)
                                    if (parsed == null) {
                                        endpointError = "请先填写或扫码获取接收端地址"
                                        Toast.makeText(context, "请先填写接收端 IP:端口", Toast.LENGTH_SHORT).show()
                                    } else {
                                        SenderController.startStream(parsed.first, parsed.second)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            containerColor = if (state.streaming) SideAccent.Away else SideAccent.Info,
                            contentColor = Color.White,
                            height = 58.dp,
                            fontSize = 16.sp
                        )
                    }
                }

                SectionCard(title = "运行状态") {
                    InfoRow("采集状态", if (state.projectionActive) "采集中" else "未开始")
                    InfoRow("信令状态", state.signalingState.name)
                    InfoRow("ICE 状态", state.iceState)
                    InfoRow("Peer 状态", state.peerState)
                    InfoRow("接收端", state.remoteEndpoint ?: "未连接")
                    InfoRow("分辨率", state.stats.resolutionText)
                    InfoRow("帧率", state.stats.fpsText)
                    InfoRow("码率", state.stats.bitrateText)
                    InfoRow("RTT", if (state.stats.rttMs > 0) "${state.stats.rttMs} ms" else "-")
                    InfoRow("丢包", state.stats.packetsLost.toString())
                    state.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }

                SectionCard(title = "本地录制") {
                    ConsoleButton(
                        text = if (state.recording) "停止录制" else "开始录制到 MP4",
                        onClick = { SenderController.toggleRecording() },
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = if (state.recording) SideAccent.Danger else SideAccent.HomeDeep,
                        contentColor = Color.White,
                        height = 54.dp,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    InfoRow("已编码帧", state.recordedFrames.toString())
                    InfoRow(
                        "保存位置",
                        state.lastRecordingPath?.substringAfterLast('/') ?: "Android/data/com.matchconsole/files/Movies"
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "录制复用 WebRTC 编码前的视频帧，与推流画面完全一致；不需要第二次投屏授权。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(title = "连接提示") {
                    Text(
                        text = "1. 第二台手机选择「接收端」，屏幕上会显示二维码与本机 IP:端口\n" +
                            "2. 本机点「扫码连接」扫二维码，或手输 IP:端口\n" +
                            "3. 先点「开始屏幕采集」完成系统授权，再点「开始推流」",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleChip(
                            label = "刷新本机 IP：${state.localIp}",
                            selected = false,
                            onClick = { SenderController.refreshLocalIp() }
                        )
                    }
                }
            }
        }
    }

    TransientMessageHost(
            messages = SenderController.toasts,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}