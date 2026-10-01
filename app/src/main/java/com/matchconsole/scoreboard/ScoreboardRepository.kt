package com.matchconsole.scoreboard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.matchconsole.common.Logx
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.scoreboardDataStore by preferencesDataStore(name = "scoreboard_store")

/**
 * 记分牌持久化。整场状态序列化为一段 JSON 存入 DataStore Preferences。
 * 选 DataStore 而不是 SharedPreferences：写入不阻塞主线程，且不会出现 apply 竞态。
 */
class ScoreboardRepository(private val context: Context) {

    private val jsonKey = stringPreferencesKey(KEY_SCOREBOARD)

    suspend fun load(): ScoreboardState? {
        return try {
            val prefs = context.scoreboardDataStore.data.first()
            val raw = prefs[jsonKey] ?: return null
            if (raw.isBlank()) return null
            val state = ScoreboardState.fromJson(JSONObject(raw))
            // 恢复出来的计时器必须是暂停态，否则重启后会瞬间跑完
            state.copy(
                mainClockRunning = false,
                shotClockRunning = false,
                timeoutClockRunning = false
            )
        } catch (t: Throwable) {
            Logx.e("读取记分牌失败", t)
            null
        }
    }

    suspend fun save(state: ScoreboardState) {
        try {
            context.scoreboardDataStore.edit { prefs ->
                prefs[jsonKey] = state.toJson().toString()
            }
        } catch (t: Throwable) {
            Logx.e("保存记分牌失败", t)
        }
    }

    suspend fun clear() {
        try {
            context.scoreboardDataStore.edit { it.remove(jsonKey) }
        } catch (t: Throwable) {
            Logx.e("清空记分牌失败", t)
        }
    }

    private companion object {
        const val KEY_SCOREBOARD = "scoreboard_json"
    }
}