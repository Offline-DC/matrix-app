//! JNI wrapper around rust librespot (v0.8) for the dpad-spotify Android app.
//!
//! Design: **pull model, no upcalls**. Rust never calls into the JVM, so no
//! thread-attachment bookkeeping and no GlobalRef lifetimes to manage. Kotlin
//! owns two daemon threads:
//!   - an event pump calling [`pollEvent`] (blocking pop of JSON event strings)
//!   - an audio pump calling [`readPcm`] (blocking pop of 44.1kHz stereo s16le
//!     PCM, fed straight into an AudioTrack)
//! Everything else is fire-and-forget commands onto the Tokio runtime.
//!
//! Login mirrors the librespot-java prototype this replaces: zeroconf
//! (Spotify Connect handoff) on first run, reusable credentials from the
//! librespot `Cache` afterwards.

use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{Condvar, Mutex, OnceLock},
    time::{Duration, Instant},
};

use futures_util::StreamExt;
use jni::{
    objects::{JByteArray, JObject, JString},
    sys::{jint, jstring},
    JNIEnv,
};
use librespot_core::{
    authentication::Credentials, cache::Cache, config::DeviceType, error::ErrorKind,
    session::Session, SessionConfig, SpotifyUri,
};
use librespot_metadata::audio::{item::AudioItem, UniqueFields};
use librespot_playback::{
    audio_backend::{Sink, SinkError, SinkResult},
    config::PlayerConfig,
    convert::Converter,
    decoder::AudioPacket,
    mixer::NoOpVolume,
    player::{Player, PlayerEvent},
};
use log::{error, info, warn};
use sha1::{Digest, Sha1};
use tokio::sync::Notify;

// ---------------------------------------------------------------------------
// Global state
// ---------------------------------------------------------------------------

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static EVENTS: OnceLock<EventQueue> = OnceLock::new();
static PCM: OnceLock<PcmBuffer> = OnceLock::new();
static LOGOUT: OnceLock<Notify> = OnceLock::new();

static SESSION: Mutex<Option<Session>> = Mutex::new(None);
static PLAYER: Mutex<Option<std::sync::Arc<Player>>> = Mutex::new(None);
static POSITION: Mutex<PositionState> = Mutex::new(PositionState {
    ms: 0,
    at: None,
    playing: false,
});
static STARTED: Mutex<bool> = Mutex::new(false);

struct PositionState {
    ms: u32,
    at: Option<Instant>,
    playing: bool,
}

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

fn events() -> &'static EventQueue {
    EVENTS.get_or_init(EventQueue::new)
}

fn pcm() -> &'static PcmBuffer {
    PCM.get_or_init(PcmBuffer::new)
}

fn logout_notify() -> &'static Notify {
    LOGOUT.get_or_init(Notify::new)
}

// ---------------------------------------------------------------------------
// Event queue (Rust → Kotlin, JSON strings)
// ---------------------------------------------------------------------------

struct EventQueue {
    queue: Mutex<VecDeque<String>>,
    cond: Condvar,
}

impl EventQueue {
    fn new() -> Self {
        Self {
            queue: Mutex::new(VecDeque::new()),
            cond: Condvar::new(),
        }
    }

    fn push(&self, event: String) {
        let mut q = self.queue.lock().unwrap();
        if q.len() > 256 {
            q.pop_front();
        }
        q.push_back(event);
        self.cond.notify_one();
    }

    fn pop(&self, timeout: Duration) -> Option<String> {
        let mut q = self.queue.lock().unwrap();
        if q.is_empty() {
            let (guard, _) = self.cond.wait_timeout(q, timeout).unwrap();
            q = guard;
        }
        q.pop_front()
    }
}

fn push_event(value: serde_json::Value) {
    events().push(value.to_string());
}

fn push_state(value: &str) {
    push_event(serde_json::json!({ "type": "state", "value": value }));
}

fn push_error(message: String) {
    push_event(serde_json::json!({ "type": "state", "value": "error", "message": message }));
}

// ---------------------------------------------------------------------------
// PCM ring buffer (Sink → Kotlin AudioTrack)
// ---------------------------------------------------------------------------

/// ~0.75s of 44.1kHz stereo s16. Small enough that pause feels instant,
/// big enough to ride out scheduler hiccups on a weak SoC.
const PCM_CAPACITY: usize = 132_300;

struct PcmBuffer {
    buf: Mutex<VecDeque<u8>>,
    not_empty: Condvar,
    not_full: Condvar,
}

impl PcmBuffer {
    fn new() -> Self {
        Self {
            buf: Mutex::new(VecDeque::with_capacity(PCM_CAPACITY)),
            not_empty: Condvar::new(),
            not_full: Condvar::new(),
        }
    }

    /// Blocks while full — this is what paces librespot's decode loop.
    fn push_blocking(&self, data: &[u8]) {
        let mut buf = self.buf.lock().unwrap();
        for chunk in data.chunks(PCM_CAPACITY / 2) {
            while buf.len() + chunk.len() > PCM_CAPACITY {
                // Timeout guards against a reader that died; we then drop
                // audio rather than wedge librespot's player thread forever.
                let (guard, timeout) = self
                    .not_full
                    .wait_timeout(buf, Duration::from_secs(2))
                    .unwrap();
                buf = guard;
                if timeout.timed_out() {
                    buf.clear();
                }
            }
            buf.extend(chunk.iter().copied());
            self.not_empty.notify_one();
        }
    }

    fn read(&self, out: &mut [u8], timeout: Duration) -> usize {
        let mut buf = self.buf.lock().unwrap();
        if buf.is_empty() {
            let (guard, _) = self.not_empty.wait_timeout(buf, timeout).unwrap();
            buf = guard;
        }
        let n = out.len().min(buf.len());
        for (slot, byte) in out.iter_mut().zip(buf.drain(..n)) {
            *slot = byte;
        }
        if n > 0 {
            self.not_full.notify_one();
        }
        n
    }

    /// Wait (bounded) for the reader to play out what's buffered. Used on
    /// pause/stop so we don't discard up to 0.75s of already-decoded audio —
    /// that would make every pause skip ahead and clip the tail off every
    /// naturally-ending track.
    fn drain(&self, timeout: Duration) {
        let deadline = Instant::now() + timeout;
        let mut buf = self.buf.lock().unwrap();
        while !buf.is_empty() {
            let now = Instant::now();
            if now >= deadline {
                break;
            }
            let (guard, _) = self.not_full.wait_timeout(buf, deadline - now).unwrap();
            buf = guard;
        }
    }

    fn clear(&self) {
        self.buf.lock().unwrap().clear();
        self.not_full.notify_one();
    }
}

// ---------------------------------------------------------------------------
// librespot Sink implementation
// ---------------------------------------------------------------------------

struct AndroidSink;

impl Sink for AndroidSink {
    fn stop(&mut self) -> SinkResult<()> {
        // Called on pause and at natural track end. Drain — don't clear —
        // so the listener hears everything that was decoded. User-initiated
        // track switches get an instant cut via the `clearPcm` JNI call
        // instead.
        pcm().drain(Duration::from_secs(2));
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let samples = packet
            .samples()
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;
        let s16 = converter.f64_to_s16(samples);
        let mut bytes = Vec::with_capacity(s16.len() * 2);
        for s in s16 {
            bytes.extend_from_slice(&s.to_le_bytes());
        }
        pcm().push_blocking(&bytes);
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Session lifecycle
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Paths {
    credentials_dir: PathBuf,
    volume_dir: PathBuf,
    audio_cache_dir: PathBuf,
    tmp_dir: PathBuf,
}

async fn supervisor(paths: Paths, device_name: String) {
    loop {
        if let Err(msg) = run_session(&paths, &device_name).await {
            push_error(msg);
            return; // fatal — user restarts the app
        }
        // Ok(()) means logout: loop back into a fresh handoff.
    }
}

/// One full session: login (stored creds or zeroconf handoff) → player →
/// event forwarding, until logout. `Ok(())` = logged out; `Err` = fatal.
async fn run_session(paths: &Paths, device_name: &str) -> Result<(), String> {
    push_state("starting");

    let cache = Cache::new(
        Some(&paths.credentials_dir),
        Some(&paths.volume_dir),
        Some(&paths.audio_cache_dir),
        Some(512 * 1024 * 1024),
    )
    .map_err(|e| format!("cache init failed: {e}"))?;

    // Stable device id: the Spotify app remembers devices by id, so don't
    // regenerate per launch.
    let device_id = hex::encode(Sha1::digest(device_name.as_bytes()));

    let session_config = SessionConfig {
        device_id: device_id.clone(),
        tmp_dir: paths.tmp_dir.clone(),
        ..SessionConfig::default()
    };

    let mut credentials = cache.credentials();
    let mut via_stored = credentials.is_some();

    let session = loop {
        let creds = match credentials.take() {
            Some(c) => c,
            None => {
                via_stored = false;
                push_state("awaitingHandoff");
                discover_credentials(&device_id, &session_config.client_id, device_name).await?
            }
        };

        push_state("authenticating");
        let session = Session::new(session_config.clone(), Some(cache.clone()));
        match session.connect(creds, true).await {
            Ok(()) => break session,
            Err(e) if via_stored && e.kind == ErrorKind::PermissionDenied => {
                // Stored credentials actually rejected — clear and fall back
                // to a fresh handoff. (Plain network errors are NOT this arm:
                // they must not force the user back through pairing.)
                warn!("stored credentials rejected: {e}");
                let _ = std::fs::remove_file(paths.credentials_dir.join("credentials.json"));
                via_stored = false;
                continue;
            }
            Err(e) => return Err(format!("Spotify login failed: {e}")),
        }
    };

    info!("logged in as {}", session.username());
    *SESSION.lock().unwrap() = Some(session.clone());

    let player_config = PlayerConfig {
        // Position events drive the Kotlin progress bar.
        position_update_interval: Some(Duration::from_millis(900)),
        ..PlayerConfig::default()
    };
    let player = Player::new(
        player_config,
        session.clone(),
        Box::new(NoOpVolume),
        move || Box::new(AndroidSink),
    );
    let mut event_rx = player.get_player_event_channel();
    *PLAYER.lock().unwrap() = Some(player);

    push_event(serde_json::json!({
        "type": "state",
        "value": "ready",
        "username": session.username(),
    }));

    // Forward player events until logout (channel closes when the Player is
    // dropped below).
    let forwarder = tokio::spawn(async move {
        while let Some(event) = event_rx.recv().await {
            forward_player_event(event);
        }
    });

    logout_notify().notified().await;

    // Teardown: stop and drop the player (closes the event channel), kill
    // the session, wipe credentials.
    if let Some(p) = PLAYER.lock().unwrap().take() {
        p.stop();
    }
    let _ = forwarder.await;
    SESSION.lock().unwrap().take();
    session.shutdown();
    pcm().clear();
    let _ = std::fs::remove_file(paths.credentials_dir.join("credentials.json"));
    info!("logged out");
    Ok(())
}

/// Advertise on the LAN and wait for the user to pick us in the Spotify app.
async fn discover_credentials(
    device_id: &str,
    client_id: &str,
    device_name: &str,
) -> Result<Credentials, String> {
    let mut discovery = librespot_discovery::Discovery::builder(device_id, client_id)
        .name(device_name.to_string())
        .device_type(DeviceType::Speaker)
        .launch()
        .map_err(|e| format!("zeroconf failed to start: {e}"))?;

    info!("zeroconf advertising as '{device_name}'");
    let credentials = discovery
        .next()
        .await
        .ok_or_else(|| "discovery stream ended unexpectedly".to_string())?;
    discovery.shutdown().await;
    Ok(credentials)
}

fn forward_player_event(event: PlayerEvent) {
    match event {
        PlayerEvent::TrackChanged { audio_item } => {
            push_event(track_json(&audio_item));
        }
        PlayerEvent::Playing { position_ms, .. } => {
            set_position(position_ms, true);
            push_event(serde_json::json!({ "type": "playing", "positionMs": position_ms }));
        }
        PlayerEvent::Paused { position_ms, .. } => {
            set_position(position_ms, false);
            push_event(serde_json::json!({ "type": "paused", "positionMs": position_ms }));
        }
        PlayerEvent::Loading { .. } => {
            push_event(serde_json::json!({ "type": "loading" }));
        }
        PlayerEvent::PositionChanged { position_ms, .. }
        | PlayerEvent::PositionCorrection { position_ms, .. }
        | PlayerEvent::Seeked { position_ms, .. } => {
            let playing = POSITION.lock().unwrap().playing;
            set_position(position_ms, playing);
        }
        PlayerEvent::EndOfTrack { .. } => {
            set_position(0, false);
            push_event(serde_json::json!({ "type": "endOfTrack" }));
        }
        PlayerEvent::Stopped { .. } => {
            set_position(0, false);
            push_event(serde_json::json!({ "type": "stopped" }));
        }
        PlayerEvent::Unavailable { .. } => {
            push_event(serde_json::json!({ "type": "unavailable" }));
        }
        _ => {}
    }
}

fn track_json(item: &AudioItem) -> serde_json::Value {
    let (artist, album) = match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => (
            artists
                .iter()
                .map(|a| a.name.as_str())
                .collect::<Vec<_>>()
                .join(", "),
            album.clone(),
        ),
        UniqueFields::Local { artists, album, .. } => (
            artists.clone().unwrap_or_default(),
            album.clone().unwrap_or_default(),
        ),
        _ => (String::new(), String::new()),
    };
    serde_json::json!({
        "type": "track",
        "title": item.name,
        "artist": artist,
        "album": album,
        "durationMs": item.duration_ms,
    })
}

fn set_position(ms: u32, playing: bool) {
    let mut pos = POSITION.lock().unwrap();
    pos.ms = ms;
    pos.at = Some(Instant::now());
    pos.playing = playing;
}

fn with_player(f: impl FnOnce(&Player)) {
    if let Some(p) = PLAYER.lock().unwrap().as_ref() {
        f(p);
    }
}

// ---------------------------------------------------------------------------
// JNI surface — com.offline.dpadspotify.spotify.LibrespotNative
// ---------------------------------------------------------------------------

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_start(
    mut env: JNIEnv,
    _this: JObject,
    files_dir: JString,
    cache_dir: JString,
    device_name: JString,
) {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("librespot"),
    );

    let mut started = STARTED.lock().unwrap();
    if *started {
        return;
    }
    *started = true;

    let files = PathBuf::from(jstring_to_string(&mut env, &files_dir));
    let cache = PathBuf::from(jstring_to_string(&mut env, &cache_dir));
    let device_name = jstring_to_string(&mut env, &device_name);

    let paths = Paths {
        credentials_dir: files.join("librespot"),
        volume_dir: files.join("librespot"),
        audio_cache_dir: cache.join("librespot-audio"),
        tmp_dir: cache.join("librespot-tmp"),
    };
    // librespot expects tmp_dir to exist (Android has no world-writable /tmp).
    let _ = std::fs::create_dir_all(&paths.tmp_dir);

    runtime().spawn(supervisor(paths, device_name));
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_pollEvent(
    env: JNIEnv,
    _this: JObject,
    timeout_ms: jint,
) -> jstring {
    match events().pop(Duration::from_millis(timeout_ms.max(0) as u64)) {
        Some(event) => env
            .new_string(event)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_readPcm(
    env: JNIEnv,
    _this: JObject,
    buffer: JByteArray,
    timeout_ms: jint,
) -> jint {
    let len = env.get_array_length(&buffer).unwrap_or(0) as usize;
    if len == 0 {
        return 0;
    }
    let mut tmp = vec![0u8; len];
    let n = pcm().read(&mut tmp, Duration::from_millis(timeout_ms.max(0) as u64));
    if n == 0 {
        return 0;
    }
    // u8 → i8 reinterpret for the JNI byte array.
    let signed: &[i8] = unsafe { std::slice::from_raw_parts(tmp.as_ptr() as *const i8, n) };
    match env.set_byte_array_region(&buffer, 0, signed) {
        Ok(()) => n as jint,
        Err(e) => {
            error!("set_byte_array_region failed: {e}");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_playUri(
    mut env: JNIEnv,
    _this: JObject,
    uri: JString,
) {
    let uri = jstring_to_string(&mut env, &uri);
    match SpotifyUri::from_uri(&uri) {
        Ok(id) => with_player(|p| p.load(id, true, 0)),
        Err(e) => {
            warn!("bad uri {uri}: {e}");
            push_event(serde_json::json!({ "type": "unavailable" }));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_play(
    _env: JNIEnv,
    _this: JObject,
) {
    with_player(|p| p.play());
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_pause(
    _env: JNIEnv,
    _this: JObject,
) {
    with_player(|p| p.pause());
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_seekMs(
    _env: JNIEnv,
    _this: JObject,
    position_ms: jint,
) {
    with_player(|p| p.seek(position_ms.max(0) as u32));
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_positionMs(
    _env: JNIEnv,
    _this: JObject,
) -> jint {
    let pos = POSITION.lock().unwrap();
    let mut ms = pos.ms as u64;
    if pos.playing {
        if let Some(at) = pos.at {
            ms += at.elapsed().as_millis() as u64;
        }
    }
    ms.min(jint::MAX as u64) as jint
}

/// Blocking — call from a background thread. Returns null on failure.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_getToken(
    env: JNIEnv,
    _this: JObject,
) -> jstring {
    let session = SESSION.lock().unwrap().clone();
    let Some(session) = session else {
        return std::ptr::null_mut();
    };
    let token = runtime().block_on(async move {
        session
            .token_provider()
            .get_token("user-read-private")
            .await
    });
    match token {
        Ok(token) => env
            .new_string(token.access_token)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            error!("token fetch failed: {e}");
            std::ptr::null_mut()
        }
    }
}

/// Instant-cut of buffered audio; call before loading a different track.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_clearPcm(
    _env: JNIEnv,
    _this: JObject,
) {
    pcm().clear();
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadspotify_spotify_LibrespotNative_logout(
    _env: JNIEnv,
    _this: JObject,
) {
    // notify_waiters (NOT notify_one): a stored permit from a press while
    // nobody was waiting (e.g. double-tap, or pressed during discovery)
    // would instantly log out the NEXT session right after it logs in.
    logout_notify().notify_waiters();
}
