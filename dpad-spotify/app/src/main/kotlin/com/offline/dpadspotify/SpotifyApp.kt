package com.offline.dpadspotify

import android.app.Application
import com.offline.dpadspotify.spotify.SpotifyManager
import xyz.gianlu.librespot.audio.decoders.Decoders
import xyz.gianlu.librespot.audio.format.SuperAudioFormat
import xyz.gianlu.librespot.player.decoders.AndroidNativeDecoder

class SpotifyApp : Application() {

    lateinit var spotify: SpotifyManager
        private set

    companion object {
        init {
            // librespot's default decoders (jorbis/jlayer) are slow pure-Java;
            // replace them with the MediaCodec-backed decoder at priority 0.
            // Must happen before any Player is created.
            Decoders.registerDecoder(SuperAudioFormat.VORBIS, 0, AndroidNativeDecoder::class.java)
            Decoders.registerDecoder(SuperAudioFormat.MP3, 0, AndroidNativeDecoder::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        spotify = SpotifyManager(this)
        spotify.start()
    }
}
