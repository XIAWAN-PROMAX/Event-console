package com.matchconsole.scoreboard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 记分牌状态机。
 *
 * 计时用「单一定时器 + 增量」实现：100ms 一跳，按真实流逝时间扣减，
 * 避免 delay 漂移导致 30 分钟后计时明显不准。
 * 只有存在运行中的计时器时才启动 ticker，空闲时零开销。
 */
class ScoreboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScoreboardRepository(application)

    private val _state = MutableStateFlow(ScoreboardState())
    val state: StateFlow<ScoreboardState> = _state.asStateFlow()

    /**
     * 高频计时流（100ms）。
     * 只有计时数字的叶子 Composable 订阅它，主画面与控制按钮不受影响。
     */
    val clock: StateFlow<ClockUi> = state
        .map { it.toClockUi() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toClockUi())

    /** 计时器运行标志（低频），用于按钮文案。 */
    val clockFlags: StateFlow<ClockFlags> = state
        .map { it.toClockFlags() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toClockFlags())

    /** 比分/犯规/暂停/节次（低频）。 */
    val scores: StateFlow<ScoreUi> = state
        .map { it.toScoreUi() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toScoreUi())

    /** 布局与显示配置（低频）。 */
    val layout: StateFlow<LayoutUi> = state
        .map { it.toLayoutUi() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.toLayoutUi())

    private var tickerJob: Job? = null
    private var restoreJob: Job? = null

    init {
        restoreJob = viewModelScope.launch {
            repository.load()?.let { restored ->
                _state.value = restored
            }
            startPersistLoop()
        }
    }

    // ------------------------------------------------------------------
    // 持久化：每秒检查一次，状态有变化才落盘
    // ------------------------------------------------------------------

    private fun startPersistLoop() {
        viewModelScope.launch {
            var lastSaved: ScoreboardState? = null
            while (isActive) {
                delay(PERSIST_INTERVAL_MS)
                val snapshot = _state.value
                if (snapshot != lastSaved) {
                    repository.save(snapshot)
                    lastSaved = snapshot
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 计时器内核
    // ------------------------------------------------------------------

    private fun onClockFlagsChanged() {
        val s = _state.value
        val anyRunning = s.mainClockRunning || s.shotClockRunning || s.timeoutClockRunning
        if (anyRunning) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTick
                lastTick = now
                tick(delta)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun tick(deltaMs: Long) {
        _state.update { s ->
            var main = s.mainClockMs
            var shot = s.shotClockMs
            var timeout = s.timeoutClockMs

            if (s.mainClockRunning) main = (main - deltaMs).coerceAtLeast(0L)
            if (s.shotClockRunning) shot = (shot - deltaMs).coerceAtLeast(0L)
            if (s.timeoutClockRunning) timeout = (timeout - deltaMs).coerceAtLeast(0L)

            val mainRunning = s.mainClockRunning && main > 0L
            val shotRunning = s.shotClockRunning && shot > 0L
            val timeoutRunning = s.timeoutClockRunning && timeout > 0L

            s.copy(
                mainClockMs = main,
                shotClockMs = shot,
                timeoutClockMs = timeout,
                mainClockRunning = mainRunning,
                shotClockRunning = shotRunning,
                timeoutClockRunning = timeoutRunning
            )
        }
        onClockFlagsChanged()
    }

    // ------------------------------------------------------------------
    // 比分 / 犯规 / 暂停
    // ------------------------------------------------------------------

    fun changeScore(side: TeamSide, delta: Int) {
        _state.update { s ->
            val team = s.team(side)
            s.withTeam(side, team.copy(score = (team.score + delta).coerceAtLeast(0)))
        }
    }

    fun setScore(side: TeamSide, value: Int) {
        _state.update { s ->
            s.withTeam(side, s.team(side).copy(score = value.coerceAtLeast(0)))
        }
    }

    fun renameTeam(side: TeamSide, name: String) {
        val trimmed = name.trim().ifBlank { if (side == TeamSide.HOME) "主队" else "客队" }
        _state.update { s -> s.withTeam(side, s.team(side).copy(name = trimmed)) }
    }

    fun changeFouls(side: TeamSide, delta: Int) {
        _state.update { s ->
            val team = s.team(side)
            s.withTeam(side, team.copy(fouls = (team.fouls + delta).coerceIn(0, MAX_FOULS)))
        }
    }

    fun changeTimeouts(side: TeamSide, delta: Int) {
        _state.update { s ->
            val team = s.team(side)
            s.withTeam(side, team.copy(timeouts = (team.timeouts + delta).coerceIn(0, MAX_TIMEOUTS)))
        }
    }

    // ------------------------------------------------------------------
    // 节次
    // ------------------------------------------------------------------

    fun nextPeriod() {
        _state.update { it.copy(period = it.period + 1) }
    }

    fun previousPeriod() {
        _state.update { it.copy(period = (it.period - 1).coerceAtLeast(1)) }
    }

    fun setPeriodMode(mode: PeriodMode) {
        _state.update {
            it.copy(
                periodMode = mode,
                period = 1,
                periodLengthMs = mode.defaultLengthMs,
                mainClockMs = mode.defaultLengthMs,
                mainClockRunning = false
            )
        }
        onClockFlagsChanged()
    }

    // ------------------------------------------------------------------
    // 比赛钟
    // ------------------------------------------------------------------

    fun startMainClock() {
        if (_state.value.mainClockMs <= 0L) return
        _state.update { it.copy(mainClockRunning = true) }
        onClockFlagsChanged()
    }

    fun pauseMainClock() {
        _state.update { it.copy(mainClockRunning = false) }
        onClockFlagsChanged()
    }

    fun toggleMainClock() {
        if (_state.value.mainClockRunning) pauseMainClock() else startMainClock()
    }

    fun resetMainClock() {
        _state.update {
            it.copy(
                mainClockMs = it.periodLengthMs,
                mainClockRunning = false,
                shotClockMs = ScoreboardState.DEFAULT_SHOT_CLOCK_MS,
                shotClockRunning = false,
                timeoutClockMs = ScoreboardState.DEFAULT_TIMEOUT_MS,
                timeoutClockRunning = false
            )
        }
        onClockFlagsChanged()
    }

    /** 补时 / 扣时（正数为补时）。 */
    fun adjustMainClock(deltaMs: Long) {
        _state.update { it.copy(mainClockMs = (it.mainClockMs + deltaMs).coerceAtLeast(0L)) }
        onClockFlagsChanged()
    }

    // ------------------------------------------------------------------
    // 进攻时限
    // ------------------------------------------------------------------

    fun resetShotClock(ms: Long = ScoreboardState.DEFAULT_SHOT_CLOCK_MS) {
        _state.update {
            it.copy(shotClockMs = ms, shotClockRunning = it.mainClockRunning && it.shotClockEnabled)
        }
        onClockFlagsChanged()
    }

    fun toggleShotClock() {
        _state.update { it.copy(shotClockRunning = !it.shotClockRunning) }
        onClockFlagsChanged()
    }

    fun toggleShotClockEnabled() {
        _state.update { it.copy(shotClockEnabled = !it.shotClockEnabled) }
        onClockFlagsChanged()
    }

    fun adjustShotClock(deltaMs: Long) {
        _state.update { it.copy(shotClockMs = (it.shotClockMs + deltaMs).coerceAtLeast(0L)) }
        onClockFlagsChanged()
    }

    // ------------------------------------------------------------------
    // 暂停计时
    // ------------------------------------------------------------------

    fun toggleTimeoutClock() {
        _state.update { it.copy(timeoutClockRunning = !it.timeoutClockRunning) }
        onClockFlagsChanged()
    }

    fun resetTimeoutClock() {
        _state.update {
            it.copy(
                timeoutClockMs = ScoreboardState.DEFAULT_TIMEOUT_MS,
                timeoutClockRunning = false
            )
        }
        onClockFlagsChanged()
    }

    // ------------------------------------------------------------------
    // 显示设置
    // ------------------------------------------------------------------

    fun toggleOverlay() {
        _state.update { it.copy(overlayVisible = !it.overlayVisible) }
    }

    fun setOverlayPosition(position: OverlayPosition) {
        _state.update { it.copy(overlayPosition = position) }
    }

    fun toggleStats() {
        _state.update { it.copy(statsVisible = !it.statsVisible) }
    }

    /** 纯净画面：一键屏蔽盖在推流画面上的所有浮层。 */
    fun togglePurePreview() {
        _state.update { it.copy(purePreview = !it.purePreview) }
    }

    fun setPurePreview(enabled: Boolean) {
        _state.update { it.copy(purePreview = enabled) }
    }

    /** 主画面缩放方式：false = 适配（不裁切），true = 裁切铺满。 */
    fun setScalingFill(fill: Boolean) {
        _state.update { it.copy(scalingFill = fill) }
    }

    fun toggleTheme() {
        _state.update {
            it.copy(
                themeMode = if (it.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
            )
        }
    }

    // ------------------------------------------------------------------
    // 一键重置
    // ------------------------------------------------------------------

    /** 重置比赛：保留队名与显示设置，清零比分 / 计时 / 犯规 / 暂停 / 节次。 */
    fun resetMatch() {
        stopTicker()
        _state.update { s ->
            s.copy(
                home = s.home.copy(score = 0, fouls = 0, timeouts = 0),
                away = s.away.copy(score = 0, fouls = 0, timeouts = 0),
                period = 1,
                mainClockMs = s.periodLengthMs,
                mainClockRunning = false,
                shotClockMs = ScoreboardState.DEFAULT_SHOT_CLOCK_MS,
                shotClockRunning = false,
                timeoutClockMs = ScoreboardState.DEFAULT_TIMEOUT_MS,
                timeoutClockRunning = false
            )
        }
    }

    /** 彻底清空（含队名与显示设置），用于换场次。 */
    fun resetAll() {
        stopTicker()
        val keepTheme = _state.value.themeMode
        _state.value = ScoreboardState(themeMode = keepTheme)
    }

    override fun onCleared() {
        stopTicker()
        super.onCleared()
    }

    private companion object {
        const val TICK_INTERVAL_MS = 100L
        const val PERSIST_INTERVAL_MS = 1_000L
        const val MAX_FOULS = 99
        const val MAX_TIMEOUTS = 9
    }
}