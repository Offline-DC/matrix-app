package com.offline.dpadspotify.spotify

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.spotify.connectstate.Connect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.gianlu.librespot.ZeroconfServer
import xyz.gianlu.librespot.android.sink.AndroidSinkOutput
import xyz.gianlu.librespot.audio.MetadataWrapper
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.PlayableId
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.io.File

/**
 * Application-scoped owner of the librespot [Session] and [Player].
 *
 * Login model (Spotify killed username/password auth in 2023, so a password
 * screen is off the table — convenient, because typing a password on a T9
 * keypad is misery):
 *
 *  1. First launch: start a [ZeroconfServer]. The device advertises itself on
 *     the LAN as a Spotify Connect speaker named [DEVICE_NAME]. The user opens
 *     the Spotify app on their REGULAR phone, taps Devices, picks it, and
 *     Spotify hands us an encrypted credential blob — zero typing here.
 *  2. The session created from that blob stores reusable credentials
 *     (credentials.json in app-private storage), so every later launch logs
 *     straight in with [Session.Builder.stored] — no phone needed.
 *
 * Everything librespot does is blocking network I/O, so all calls funnel
 * through [Dispatchers.IO].
 */
class SpotifyManager(private val appContext: Context) {

    sealed interface State {
        /** Working: either restoring stored credentials or booting zeroconf. */
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    @Volatile private var session: Session? = null
    @Volatile private var player: Player? = null
    @Volatile private var zeroconf: ZeroconfServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val credentialsFile: File
        get() = File(appContext.filesDir, "credentials.json")

    private fun sessionConfiguration(): Session.Configuration =
        Session.Configuration.Builder()
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            // The audio cache makes track starts much snappier on flash
            // storage. Lives in cacheDir so the OS may reclaim it.
            .setCacheEnabled(true)
            .setCacheDir(File(appContext.cacheDir, "librespot"))
            .build()

    /** Idempotent. Call once from the Application / first composition. */
    fun start() {
        if (session != null || zeroconf != null) return
        _state.value = State.Starting
        scope.launch {
            if (credentialsFile.exists() && credentialsFile.length() > 0) {
                _state.value = State.Authenticating
                try {
                    onSessionReady(
                        Session.Builder(sessionConfiguration())
                            .setDeviceType(Connect.DeviceType.SPEAKER)
                            .setDeviceName(DEVICE_NAME)
                            .setDeviceId(null)
                            .stored(credentialsFile)
                            .create()
                    )
                    return@launch
                } catch (e: Session.SpotifyAuthenticationException) {
                    // Actually rejected — revoked/corrupt. Only THIS case may
                    // delete the file; a plain IOException is just "no Wi-Fi
                    // yet" and must not force a fresh phone handoff.
                    Log.w(TAG, "Stored credentials rejected, falling back to zeroconf", e)
                    credentialsFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Stored-credential login failed (transient?)", e)
                    _state.value = State.Error("Couldn't reach Spotify — check Wi-Fi and reopen the app")
                    return@launch
                }
            }
            startZeroconf()
        }
    }

    private fun startZeroconf() {
        try {
            // Android filters mDNS multicast in the Wi-Fi driver unless a
            // MulticastLock is held; without this the phone's Spotify app
            // never discovers us. Only held while advertising (battery).
            releaseMulticastLock()
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("dpad-spotify-zeroconf").apply {
                setReferenceCounted(false)
                acquire()
            }

            val server = ZeroconfServer.Builder(sessionConfiguration())
                .setDeviceType(Connect.DeviceType.SPEAKER)
                .setDeviceName(DEVICE_NAME)
                .setDeviceId(null)
                .setPreferredLocale("en")
                .setListenAll(true)
                .create()
            zeroconf = server

            server.addSessionListener(object : ZeroconfServer.SessionListener {
                override fun sessionClosing(session: Session) {
                    // The zeroconf server is about to close the old session
                    // (user re-handed-off as a different account). Drop our
                    // player first so it doesn't write to a dead session.
                    teardownPlayer()
                }

                override fun sessionChanged(session: Session) {
                    _state.value = State.Authenticating
                    scope.launch {
                        try {
                            onSessionReady(session)
                        } catch (e: Exception) {
                            // Without this, an exception here unwinds to the
                            // default handler and kills the process.
                            Log.e(TAG, "Player bring-up after handoff failed", e)
                            _state.value = State.Error("Login failed: ${e.message}")
                        }
                    }
                }
            })

            _state.value = State.AwaitingHandoff(DEVICE_NAME)
            Log.i(TAG, "Zeroconf advertising as '$DEVICE_NAME'")
        } catch (e: Exception) {
            Log.e(TAG, "Zeroconf failed to start", e)
            _state.value = State.Error("Couldn't start network discovery: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    private fun onSessionReady(s: Session) {
        session = s

        val conf = PlayerConfiguration.Builder()
            .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
            .setOutputClass(AndroidSinkOutput::class.java.name)
            // NORMAL (96kbps Vorbis) — kind to the flip phone's storage,
            // radio, and speaker. Bump to HIGH if it sounds rough.
            .setPreferredQuality(AudioQuality.NORMAL)
            .setAutoplayEnabled(false)
            .build()

        val p = Player(conf, s)
        p.addEventsListener(playerListener)
        player = p

        p.waitReady()
        // Advertising did its job; the Connect device registration now lives
        // on the session's dealer connection, not mDNS.
        releaseMulticastLock()
        _state.value = State.Ready(s.username())
        Log.i(TAG, "Session + player ready as ${s.username()}")
    }

    // ---- Playback controls (all safe to call from the UI thread) ----

    fun playUri(uri: String) {
        val p = player ?: return
        // Optimistic "loading" so the UI reacts instantly to the keypress.
        _nowPlaying.value = (_nowPlaying.value ?: NowPlaying("", "", "", 0, paused = false, loading = true))
            .copy(loading = true, paused = false)
        scope.launch {
            try {
                p.load(uri, true, false)
            } catch (e: Exception) {
                Log.e(TAG, "load($uri) failed", e)
                // Drop the optimistic placeholder so the UI doesn't show a
                // phantom "now playing" entry.
                _nowPlaying.value = null
            }
        }
    }

    fun playPause() = scope.launch { runCatching { player?.playPause() } }
    fun next() = scope.launch { runCatching { player?.next() } }
    fun previous() = scope.launch { runCatching { player?.previous() } }

    /** Current playback position in ms, or null when unknown. */
    fun positionMs(): Int? = try {
        player?.time()?.takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    suspend fun search(query: String): List<TrackResult> = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException("Not logged in")
        // Dev-branch librespot mints access tokens via Login5; they're valid
        // bearer tokens for the public Web API.
        WebApi.searchTracks(s.tokens().get(), query)
    }

    fun logout() {
        scope.launch {
            credentialsFile.delete()
            teardownPlayer()
            runCatching { session?.close() }
            session = null
            runCatching { zeroconf?.close() }
            zeroconf = null
            _nowPlaying.value = null
            startZeroconf()
        }
    }

    private fun teardownPlayer() {
        player?.let { p ->
            p.removeEventsListener(playerListener)
            runCatching { p.close() }
        }
        player = null
    }

    fun shutdown() {
        scope.launch {
            teardownPlayer()
            runCatching { session?.close() }
            runCatching { zeroconf?.close() }
            releaseMulticastLock()
        }
    }

    // ---- Player events → NowPlaying flow ----

    private fun publishMetadata(metadata: MetadataWrapper?, paused: Boolean, loading: Boolean) {
        if (metadata == null) {
            _nowPlaying.value = _nowPlaying.value?.copy(paused = paused, loading = loading)
            return
        }
        _nowPlaying.value = NowPlaying(
            title = metadata.name ?: "Unknown",
            artist = metadata.artist ?: "",
            album = metadata.albumName ?: "",
            durationMs = metadata.duration(),
            paused = paused,
            loading = loading,
        )
    }

    private val playerListener = object : Player.EventsListener {
        override fun onContextChanged(player: Player, newUri: String) {}

        override fun onTrackChanged(player: Player, id: PlayableId, metadata: MetadataWrapper?, userInitiated: Boolean) {
            publishMetadata(metadata, paused = false, loading = metadata == null)
        }

        override fun onPlaybackEnded(player: Player) {
            _nowPlaying.value = _nowPlaying.value?.copy(paused = true)
        }

        override fun onPlaybackPaused(player: Player, trackTime: Long) {
            _nowPlaying.value = _nowPlaying.value?.copy(paused = true)
        }

        override fun onPlaybackResumed(player: Player, trackTime: Long) {
            _nowPlaying.value = _nowPlaying.value?.copy(paused = false)
        }

        override fun onPlaybackFailed(player: Player, e: Exception) {
            Log.e(TAG, "Playback failed", e)
            _nowPlaying.value = _nowPlaying.value?.copy(paused = true, loading = false)
        }

        override fun onTrackSeeked(player: Player, trackTime: Long) {}

        override fun onMetadataAvailable(player: Player, metadata: MetadataWrapper) {
            publishMetadata(metadata, paused = _nowPlaying.value?.paused ?: false, loading = false)
        }

        override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) {}

        override fun onInactiveSession(player: Player, timeout: Boolean) {}

        override fun onVolumeChanged(player: Player, volume: Float) {}

        override fun onPanicState(player: Player) {
            _state.value = State.Error("Player entered panic state — restart the app")
        }

        override fun onStartedLoading(player: Player) {
            _nowPlaying.value = _nowPlaying.value?.copy(loading = true)
        }

        override fun onFinishedLoading(player: Player) {
            _nowPlaying.value = _nowPlaying.value?.copy(loading = false)
        }
    }

    companion object {
        private const val TAG = "SpotifyManager"

        /** Name shown in the Spotify app's Devices list. */
        const val DEVICE_NAME = "Offline Dpad"
    }
}
