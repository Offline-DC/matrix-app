package com.offline.dpadspotify

import android.app.Application
import com.offline.dpadspotify.spotify.SpotifyManager

class SpotifyApp : Application() {

    lateinit var spotify: SpotifyManager
        private set

    override fun onCreate() {
        super.onCreate()
        spotify = SpotifyManager(this)
        spotify.start()
    }
}
