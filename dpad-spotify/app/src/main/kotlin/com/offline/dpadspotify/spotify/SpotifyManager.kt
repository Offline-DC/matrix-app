package com.offline.dpadspotify.spotify

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Application-scoped facade over the Rust librespot core ([LibrespotNative]).
 *
 * Login model (Spotify killed username/password auth in 2023, so a password
 * screen is off the table — convenient, because typing a password on a T9
 * keypad is misery):
 *
 *  1. First launch: the native core advertises this device on the LAN as a
 *     Spotify Connect speaker named [DEVICE_NAME]. The user opens the Spotify
 *     app on their REGULAR phone, taps Devices, picks it, and Spotify hands
 *     over an encrypted credential blob — zero typing here.
 *  2. librespot's cache stores reusable credentials in app-private storage,
 *     so every later launch logs straight in — no phone needed.
 *
 * This class owns three long-lived workers:
 *  - event pump thread: drains the native JSON event queue → state flows
 *  - audio pump thread: drains decoded PCM → AudioTrack
 *  - the native Tokio runtime itself (inside the .so)
 *
 * Next/previous: rust librespot's Player is a single-track engine (queueing
 * lives in Spotify Connect's spirc, which this prototype doesn't run), so the
 * play queue is app-side — the search result list the track was picked from.
 */
class SpotifyManager(private val appContext: Context) {

    sealed interface State {
        /** Working: native core booting / restoring stored credentials. */
        data object Starting : State

        /** Advertising on the LAN; waiting for the user to pick us in the Spotify app. */
        data class AwaitingHandoff(val deviceName: String) : State

        /** Credential blob received / stored credentials found; AP login in flight. */
        data object Authenticating : State

        data class Ready(val username: String) : State

        data class Error(val message: String) : State
    }

    data class NowPlaying(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Int,
        val paused: Boolean,
        val loading: Boolean,
    )

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    // App-side play queue (see class doc).
    @Volatile private var queue: List<TrackResult> = emptyList()
    @Volatile private var queueIndex: Int = -1

    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var started = false

    /** Idempotent. Call once from the Application. */
    fun start() {
        if (started) return
        started = true

        // Acquire BEFORE the native zeroconf can start advertising: Android
        // filters mDNS multicast in the Wi-Fi driver unless this is held.
        // Released once logged in (it's only needed while advertising).
        acquireMulticastLock()

        LibrespotNative.start(
            appContext.filesDir.absolutePath,
            appContext.cacheDir.absolutePath,
            DEVICE_NAME,
        )

        thread(name = "librespot-events", isDaemon = true) { eventPump() }
        thread(name = "librespot-audio", isDaemon = true) { audioPump() }
    }

    // ---- Playback controls (all non-blocking) ----

    /** Play [index] of [tracks], adopting the list as the next/prev queue. */
    fun play(tracks: List<TrackResult>, index: Int) {
        queue = tracks
        playIndex(index)
    }

    fun playPause() {
        val np = _nowPlaying.value ?: return
        if (np.paused) LibrespotNative.play() else LibrespotNative.pause()
    }

    fun next() {
        if (queueIndex + 1 < queue.size) playIndex(queueIndex + 1)
    }

    fun previous() {
        if (queueIndex > 0) playIndex(queueIndex - 1)
    }

    /** Current playback position in ms, or null when unknown. */
    fun positionMs(): Int? = LibrespotNative.positionMs().takeIf { it >= 0 }

    suspend fun search(query: String): List<TrackResult> = withContext(Dispatchers.IO) {
        val token = LibrespotNative.getToken()
            ?: throw IllegalStateException("Not logged in")
        WebApi.searchTracks(token, query)
    }

    fun logout() {
        queue = emptyList()
        queueIndex = -1
        _nowPlaying.value = null
        // Re-acquire BEFORE the native side loops back into zeroconf
        // advertising — otherwise the first mDNS queries from the phone get
        // filtered while the event pump catches up to AwaitingHandoff.
        acquireMulticastLock()
        // State transitions (→ AwaitingHandoff) arrive via the event pump.
        LibrespotNative.logout()
    }

    private fun playIndex(index: Int) {
        val track = queue.getOrNull(index) ?: return
        queueIndex = index
        // User picked a different track: cut buffered audio of the old one
        // immediately (the sink otherwise drains it out — right for pause,
        // wrong for an explicit switch).
        LibrespotNative.clearPcm()
        // Optimistic update from the search result so the UI flips instantly;
        // the native "track" event refines it (e.g. exact duration).
        _nowPlaying.value = NowPlaying(
            title = track.name,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            paused = false,
            loading = true,
        )
        LibrespotNative.playUri(track.uri)
    }

    // ---- Event pump: native JSON events → state flows ----

    private fun eventPump() {
        while (true) {
            val raw = LibrespotNative.pollEvent(1000) ?: continue
            try {
                handleEvent(JsonParser.parseString(raw).asJsonObject)
            } catch (e: Exception) {
                Log.e(TAG, "Bad native event: $raw", e)
            }
        }
    }

    private fun handleEvent(event: JsonObject) {
        when (event.get("type")?.asString) {
            "state" -> when (event.get("value")?.asString) {
                "starting" -> _state.value = State.Starting
                "awaitingHandoff" -> {
                    acquireMulticastLock()
                    _state.value = State.AwaitingHandoff(DEVICE_NAME)
                }
                "authenticating" -> _state.value = State.Authenticating
                "ready" -> {
                    releaseMulticastLock()
                    _state.value = State.Ready(event.get("username")?.asString ?: "")
                }
                "error" -> {
                    releaseMulticastLock()
                    _state.value = State.Error(event.get("message")?.asString ?: "Unknown error")
                }
            }

            "track" -> _nowPlaying.value = NowPlaying(
                title = event.get("title")?.asString ?: "Unknown",
                artist = event.get("artist")?.asString ?: "",
                album = event.get("album")?.asString ?: "",
                durationMs = event.get("durationMs")?.asInt ?: 0,
                paused = _nowPlaying.value?.paused ?: false,
                loading = false,
            )

            "playing" -> _nowPlaying.value =
                _nowPlaying.value?.copy(paused = false, loading = false)

            "paused" -> _nowPlaying.value = _nowPlaying.value?.copy(paused = true)

            "loading" -> _nowPlaying.value = _nowPlaying.value?.copy(loading = true)

            "endOfTrack" -> {
                // Auto-advance through the app-side queue, like any sane
                // music player. Stop at the end of the results list.
                if (queueIndex + 1 < queue.size) playIndex(queueIndex + 1)
                else _nowPlaying.value = _nowPlaying.value?.copy(paused = true)
            }

            "stopped" -> _nowPlaying.value =
                _nowPlaying.value?.copy(paused = true, loading = false)

            "unavailable" -> {
                Log.w(TAG, "Track unavailable, skipping")
                if (queueIndex + 1 < queue.size) playIndex(queueIndex + 1)
                else _nowPlaying.value = null
            }
        }
    }

    // ---- Audio pump: native PCM → AudioTrack ----

    private fun audioPump() = try {
        audioPumpLoop()
    } catch (e: Exception) {
        // An uncaught exception on this thread would kill the whole app.
        Log.e(TAG, "Audio pump died — no audio until restart", e)
    }

    private fun audioPumpLoop() {
        // librespot's output is fixed: 44.1kHz stereo s16.
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 32 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val buffer = ByteArray(8192)
        while (true) {
            val n = LibrespotNative.readPcm(buffer, 500)
            if (n > 0) {
                val written = track.write(buffer, 0, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write error $written — recreating is left to a restart")
                }
            }
        }
    }

    // ---- Multicast lock (needed only while zeroconf advertises) ----

    @Synchronized
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("dpad-spotify-zeroconf").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Synchronized
    private fun releaseMulticastLock() {
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    companion object {
        private const val TAG = "SpotifyManager"
        private const val SAMPLE_RATE = 44_100

        /** Name shown in the Spotify app's Devices list. */
        const val DEVICE_NAME = "Offline Dpad"
    }
}
