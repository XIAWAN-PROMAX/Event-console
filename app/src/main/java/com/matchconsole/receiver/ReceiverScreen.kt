package com.matchconsole.receiver

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matchconsole.common.PermissionUtils
import com.matchconsole.console.ConsoleScreen
import com.matchconsole.console.ConsoleTopBar
import com.matchconsole.scoreboard.ScoreboardViewModel

/**
 * 接收端外壳（第二台设备）。
 *
 * 职责边界：
 *  - 本 Composable 负责权限、启动/停止局域网信令服务、顶部细条
 *  - 赛事控制台的四区域布局全部在 [ConsoleScreen] 内
 *  - 记分牌状态由 [ScoreboardViewModel] 持有，随 Activity 生命周期存活
 */
@Composable
fun ReceiverScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scoreboardViewModel: ScoreboardViewModel = viewModel()
    val receiver by ReceiverSession.state.collectAsStateWithLifecycle()

    // 沉浸式预览：铺满整屏，并隐藏顶部细条，把纵向空间全部让给主画面
    var immersive by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val missing = PermissionUtils.missing(context, PermissionUtils.requiredPermissions())
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
        ReceiverSession.startServer(context)
    }

    DisposableEffect(Unit) {
        onDispose { ReceiverSession.stopServer() }
    }

    // 主题由 ConsoleScreen 内部根据记分牌设置应用，这里只负责外壳布局
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!immersive) {
                ConsoleTopBar(
                    title = "赛事控制台",
                    subtitle = receiver.statusText,
                    onSwitchMode = onBack
                )
            }

            ConsoleScreen(
                scoreboardViewModel = scoreboardViewModel,
                receiver = receiver,
                immersive = immersive,
                onImmersiveChange = { immersive = it },
                modifier = Modifier.weight(1f)
            )
        }
    }
}