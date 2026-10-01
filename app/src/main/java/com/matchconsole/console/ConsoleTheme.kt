package com.matchconsole.console

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.matchconsole.scoreboard.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF052E1B),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF0B1F3A),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFBFDBFE),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3B2600),
    background = Color(0xFF080E18),
    onBackground = Color(0xFFE5EAF2),
    surface = Color(0xFF111A28),
    onSurface = Color(0xFFE5EAF2),
    surfaceVariant = Color(0xFF1C2941),
    onSurfaceVariant = Color(0xFFAAB8CC),
    error = Color(0xFFEF4444),
    onError = Color(0xFF2A0505),
    outline = Color(0xFF31415C)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF15803D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBF7D0),
    onPrimaryContainer = Color(0xFF052E1B),
    secondary = Color(0xFF1D4ED8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF0B1F3A),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFEEF1F6),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF44506A),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFB9C3D4)
)

@Composable
fun MatchConsoleTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = themeMode == ThemeMode.DARK
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}

/** 记分牌叠加层固定配色：高对比、不随主题变化，保证任何画面下都可读。 */
object OverlayPalette {
    val Background = Color(0xD9000000)
    val HomeAccent = Color(0xFF4ADE80)
    val AwayAccent = Color(0xFFFBBF24)
    val Text = Color(0xFFFFFFFF)
    val SubText = Color(0xFFB9C6DA)
    val LedRed = Color(0xFFFF4D4D)
}

/** 主队 / 客队操作按钮配色，避免误触。 */
object SideAccent {
    val Home = Color(0xFF16A34A)
    val HomeDeep = Color(0xFF166534)
    val Away = Color(0xFFD97706)
    val AwayDeep = Color(0xFF92400E)
    val Danger = Color(0xFFDC2626)
    val Neutral = Color(0xFF475569)
    val Info = Color(0xFF2563EB)
}