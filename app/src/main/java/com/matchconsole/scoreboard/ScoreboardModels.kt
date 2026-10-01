package com.matchconsole.scoreboard

import org.json.JSONObject
import java.util.Locale

/** 记分牌叠加层位置。 */
enum class OverlayPosition { TOP, BOTTOM }

/** 主题模式。 */
enum class ThemeMode { DARK, LIGHT }

/** 比赛分段方式：节 / 半场 / 局 / 回合。 */
enum class PeriodMode(val label: String, val defaultLengthMs: Long) {
    QUARTER("第 %d 节", 10 * 60 * 1000L),
    HALF("第 %d 半场", 20 * 60 * 1000L),
    SET("第 %d 局", 25 * 60 * 1000L),
    ROUND("第 %d 回合", 3 * 60 * 1000L);

    fun title(period: Int): String = label.format(period.toInt())
}

enum class TeamSide { HOME, AWAY }

data class TeamState(
    val name: String,
    val score: Int = 0,
    val fouls: Int = 0,
    val timeouts: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("score", score)
        put("fouls", fouls)
        put("timeouts", timeouts)
    }

    companion object {
        fun fromJson(json: JSONObject, fallbackName: String): TeamState = TeamState(
            name = json.optString("name", fallbackName).ifBlank { fallbackName },
            score = json.optInt("score", 0),
            fouls = json.optInt("fouls", 0),
            timeouts = json.optInt("timeouts", 0)
        )
    }
}

/**
 * 记分牌完整状态。既驱动主画面上的叠加层，也驱动右侧控制面板。
 * 该对象整体序列化到 DataStore，App 重启后恢复上一场。
 */
data class ScoreboardState(
    val home: TeamState = TeamState("主队"),
    val away: TeamState = TeamState("客队"),
    val period: Int = 1,
    val periodMode: PeriodMode = PeriodMode.QUARTER,

    /** 比赛钟剩余毫秒（倒计时） */
    val mainClockMs: Long = PeriodMode.QUARTER.defaultLengthMs,
    val mainClockRunning: Boolean = false,
    val periodLengthMs: Long = PeriodMode.QUARTER.defaultLengthMs,

    /** 进攻时限剩余毫秒 */
    val shotClockMs: Long = DEFAULT_SHOT_CLOCK_MS,
    val shotClockRunning: Boolean = false,
    val shotClockEnabled: Boolean = true,

    /** 暂停计时剩余毫秒 */
    val timeoutClockMs: Long = DEFAULT_TIMEOUT_MS,
    val timeoutClockRunning: Boolean = false,

    val overlayVisible: Boolean = true,
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM,

    /** 主画面角落的链路指标浮层开关（画面拥挤时可关掉）。 */
    val statsVisible: Boolean = true,

    /**
     * 纯净画面：把盖在推流画面上的东西全部屏蔽掉
     * （记分牌条、链路指标、连接角标、沉浸快捷条都隐藏），
     * 画面上只留一个很小的「显示浮层」按钮用于恢复。
     */
    val purePreview: Boolean = false,

    /**
     * 主画面缩放方式。
     * false = 适配（等比缩放到完整可见，可能留黑边）
     * true  = 裁切（等比放大铺满，画面左右或上下会被切掉）
     */
    val scalingFill: Boolean = false,

    val themeMode: ThemeMode = ThemeMode.DARK
) {

    fun team(side: TeamSide): TeamState = if (side == TeamSide.HOME) home else away

    fun withTeam(side: TeamSide, team: TeamState): ScoreboardState =
        if (side == TeamSide.HOME) copy(home = team) else copy(away = team)

    val periodTitle: String get() = periodMode.title(period)

    fun toJson(): JSONObject = JSONObject().apply {
        put("home", home.toJson())
        put("away", away.toJson())
        put("period", period)
        put("periodMode", periodMode.name)
        put("mainClockMs", mainClockMs)
        put("periodLengthMs", periodLengthMs)
        put("shotClockMs", shotClockMs)
        put("shotClockEnabled", shotClockEnabled)
        put("timeoutClockMs", timeoutClockMs)
        put("overlayVisible", overlayVisible)
        put("overlayPosition", overlayPosition.name)
        put("statsVisible", statsVisible)
        put("purePreview", purePreview)
        put("scalingFill", scalingFill)
        put("themeMode", themeMode.name)
    }

    companion object {
        const val DEFAULT_SHOT_CLOCK_MS = 24_000L
        const val DEFAULT_TIMEOUT_MS = 60_000L

        fun fromJson(json: JSONObject): ScoreboardState {
            val mode = runCatching {
                PeriodMode.valueOf(json.optString("periodMode", PeriodMode.QUARTER.name))
            }.getOrDefault(PeriodMode.QUARTER)

            return ScoreboardState(
                home = TeamState.fromJson(json.optJSONObject("home") ?: JSONObject(), "主队"),
                away = TeamState.fromJson(json.optJSONObject("away") ?: JSONObject(), "客队"),
                period = json.optInt("period", 1).coerceAtLeast(1),
                periodMode = mode,
                mainClockMs = json.optLong("mainClockMs", mode.defaultLengthMs),
                periodLengthMs = json.optLong("periodLengthMs", mode.defaultLengthMs),
                shotClockMs = json.optLong("shotClockMs", DEFAULT_SHOT_CLOCK_MS),
                shotClockEnabled = json.optBoolean("shotClockEnabled", true),
                timeoutClockMs = json.optLong("timeoutClockMs", DEFAULT_TIMEOUT_MS),
                overlayVisible = json.optBoolean("overlayVisible", true),
                overlayPosition = runCatching {
                    OverlayPosition.valueOf(json.optString("overlayPosition", OverlayPosition.BOTTOM.name))
                }.getOrDefault(OverlayPosition.BOTTOM),
                statsVisible = json.optBoolean("statsVisible", true),
                purePreview = json.optBoolean("purePreview", false),
                scalingFill = json.optBoolean("scalingFill", false),
                themeMode = runCatching {
                    ThemeMode.valueOf(json.optString("themeMode", ThemeMode.DARK.name))
                }.getOrDefault(ThemeMode.DARK)
            )
        }
    }
}

/** 主比赛钟格式化：MM:SS，超过 1 小时显示 H:MM:SS。 */
fun formatMainClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** 进攻时限格式化：显示到 0.1 秒，符合篮球读秒习惯。 */
fun formatShotClock(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    return String.format(Locale.US, "%.1f", clamped / 1000.0)
}

/** 面板上的短格式（秒）。 */
fun formatSeconds(ms: Long): String {
    return String.format(Locale.US, "%d", (ms.coerceAtLeast(0) / 1000))
}

// ----------------------------------------------------------------------
// 状态投影
//
// ScoreboardState 是一个大对象，计时器 100ms 改一次它。
// 如果 UI 直接订阅整个 state，整棵控制台树（含 WebRTC 的 AndroidView）
// 会跟着 10Hz 重组，主画面就会明显卡顿。
//
// 所以按「变化频率」把 state 投影成三条独立的流：
//   clock   —— 100ms 级，只有计时数字订阅
//   scores  —— 点击级，比分/犯规/暂停/节次
//   layout  —— 点击级，覆盖层与主题配置
// 每条都经过 distinctUntilChanged，互不干扰。
// ----------------------------------------------------------------------

/** 比分/节次投影（低频）。 */
data class ScoreUi(
    val home: TeamState = TeamState("主队"),
    val away: TeamState = TeamState("客队"),
    val periodTitle: String = PeriodMode.QUARTER.title(1)
)

/** 计时数据投影（高频，100ms 一跳）。 */
data class ClockUi(
    val mainClockMs: Long = PeriodMode.QUARTER.defaultLengthMs,
    val shotClockMs: Long = ScoreboardState.DEFAULT_SHOT_CLOCK_MS,
    val timeoutClockMs: Long = ScoreboardState.DEFAULT_TIMEOUT_MS,
    val mainRunning: Boolean = false,
    val shotRunning: Boolean = false,
    val shotEnabled: Boolean = true,
    val timeoutRunning: Boolean = false
)

/** 计时器的「运行标志」投影（低频）：只有点开始/暂停时才变，用于按钮文案。 */
data class ClockFlags(
    val mainRunning: Boolean = false,
    val shotRunning: Boolean = false,
    val shotEnabled: Boolean = true,
    val timeoutRunning: Boolean = false
)

/** 布局与显示配置投影（低频）。 */
data class LayoutUi(
    val overlayVisible: Boolean = true,
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM,
    val statsVisible: Boolean = true,
    val purePreview: Boolean = false,
    val scalingFill: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK
)

fun ScoreboardState.toScoreUi(): ScoreUi = ScoreUi(home = home, away = away, periodTitle = periodTitle)

fun ScoreboardState.toClockUi(): ClockUi = ClockUi(
    mainClockMs = mainClockMs,
    shotClockMs = shotClockMs,
    timeoutClockMs = timeoutClockMs,
    mainRunning = mainClockRunning,
    shotRunning = shotClockRunning,
    shotEnabled = shotClockEnabled,
    timeoutRunning = timeoutClockRunning
)

fun ScoreboardState.toClockFlags(): ClockFlags = ClockFlags(
    mainRunning = mainClockRunning,
    shotRunning = shotClockRunning,
    shotEnabled = shotClockEnabled,
    timeoutRunning = timeoutClockRunning
)

fun ScoreboardState.toLayoutUi(): LayoutUi = LayoutUi(
    overlayVisible = overlayVisible,
    overlayPosition = overlayPosition,
    statsVisible = statsVisible,
    purePreview = purePreview,
    scalingFill = scalingFill,
    themeMode = themeMode
)