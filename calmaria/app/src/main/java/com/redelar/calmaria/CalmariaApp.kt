package com.redelar.calmaria

import android.app.Application
import android.content.Context
import com.redelar.calmaria.data.Store
import com.redelar.calmaria.playback.Notifications
import com.redelar.calmaria.playback.Player

class CalmariaApp : Application() {
    lateinit var store: Store
        private set
    lateinit var player: Player
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        player = Player(this, store)
        Notifications.createChannels(this)
    }
}

val Context.app: CalmariaApp get() = applicationContext as CalmariaApp
