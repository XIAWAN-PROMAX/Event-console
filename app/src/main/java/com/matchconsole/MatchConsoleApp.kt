package com.matchconsole

import android.app.Application
import com.matchconsole.common.Logx
import com.matchconsole.common.Notifications
import com.matchconsole.common.WebRtcCore
import com.matchconsole.sender.SenderController

class MatchConsoleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WebRtcCore.init(this)
        Notifications.ensureChannels(this)
        SenderController.bind(this)
        Logx.i("MatchConsoleApp 启动完成")
    }
}