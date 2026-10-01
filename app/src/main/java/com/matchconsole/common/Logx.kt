package com.matchconsole.common

import android.util.Log

/**
 * 统一日志出口。release 包可通过 [enabled] 一键关闭。
 */
object Logx {

    private const val TAG = "MatchConsole"

    @Volatile
    var enabled: Boolean = true

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun i(msg: String) {
        if (enabled) Log.i(TAG, msg)
    }

    fun w(msg: String) {
        if (enabled) Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (enabled) {
            if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t)
        }
    }
}