package com.example.sunwakeup

import android.app.Application
import com.example.sunwakeup.data.Repository
import com.example.sunwakeup.service.Notifications

class SunWakeupApp : Application() {
    companion object {
        lateinit var instance: SunWakeupApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Repository.init(this)
        Notifications.createChannels(this)
    }
}
