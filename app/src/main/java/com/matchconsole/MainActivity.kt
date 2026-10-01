package com.matchconsole

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.matchconsole.app.ModeSelectScreen
import com.matchconsole.console.MatchConsoleTheme
import com.matchconsole.receiver.ReceiverScreen
import com.matchconsole.scoreboard.ThemeMode
import com.matchconsole.sender.SenderScreen

/** 顶层路由。 */
enum class AppScreen { MODE_SELECT, SENDER, RECEIVER }

/**
 * 单 Activity 架构。横屏锁定 + 常亮，保证监看过程中屏幕不会熄灭。
 *
 * 发送端/接收端不是两个 App，而是同一个 App 的两种模式，
 * 由本 Activity 路由到各自的界面。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 赛事监看过程中不允许熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            var screen by rememberSaveable { mutableStateOf(AppScreen.MODE_SELECT) }

            Surface(modifier = Modifier.fillMaxSize()) {
                AppRoot(
                    screen = screen,
                    onNavigate = { screen = it }
                )
            }
        }
    }
}

@Composable
private fun AppRoot(screen: AppScreen, onNavigate: (AppScreen) -> Unit) {
    MatchConsoleTheme(ThemeMode.DARK) {
        when (screen) {
            AppScreen.MODE_SELECT -> ModeSelectScreen(
                onSelect = { onNavigate(it) }
            )

            AppScreen.SENDER -> SenderScreen(
                onBack = { onNavigate(AppScreen.MODE_SELECT) }
            )

            AppScreen.RECEIVER -> ReceiverScreen(
                onBack = { onNavigate(AppScreen.MODE_SELECT) }
            )
        }
    }
}