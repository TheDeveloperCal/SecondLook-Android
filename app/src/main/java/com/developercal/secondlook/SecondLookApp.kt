package com.developercal.secondlook

import android.app.Application
import com.developercal.secondlook.data.LocalDatabase
import com.developercal.secondlook.data.LocalSettings
import com.developercal.secondlook.data.ThreatUpdateScheduler

class SecondLookApp : Application() {
    val database by lazy { LocalDatabase.get(this) }
    val settings by lazy { LocalSettings(this) }

    override fun onCreate() {
        super.onCreate()
        ThreatUpdateScheduler.schedule(this)
    }
}
