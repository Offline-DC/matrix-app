//! JNI wrapper over OpenBubbles' rustpush — the `libsmarttxt_ffi.so` that
//! `RustPushNative.kt` loads (SMARTTXT_NATIVE_BACKEND_PLAN.md §3).
//!
//! This is the REAL implementation (no longer a stub). It registers the phone
//! with iMessage on-device using rustpush's built-in `RelayConfig` pointed at the
//! OpenBubbles relay (https://hw.openbubbles.app) for BOTH the device identity
//! (get-version-info) and the validation data (get-validation-data). There is no
//! imessage-relay daemon and no open-absinthe: the crate builds rustpush with
//! `default-features = false`, so the x86 emulator (and its unicorn C dep, which
//! won't cross-compile to Android) is not compiled — nothing here needs it.
//!
//! The flow mirrors the compiler-checked `imessage-register` Mac harness
//! (main.rs / relay/apple.rs), split across JNI calls so the interactive 2FA is
//! driven by the Kotlin sign-in screen:
//!
//!   nativeInit(dir, host, code)  build RelayConfig (get_versions) + keystore
//!   nativeConnect()              APNs activate/connect (resume if registered)
//!   nativeAuthenticate(id, pw)   GrandSlam login (out-of-the-box anisette)
//!                                → "logged_in" | "needs_2fa"
//!   nativeSubmit2fa(code)        verify 2FA, collect the PET
//!   nativeRegister(id)           IDS delegates → authenticate → register → IMClient
//!   nativeSendText / nativePollEvents
//!
//! Anisette is used straight from omnisette (`default_provider`, remote-anisette-v3
//! → a remote anisette server, default https://ani.sidestore.io); we do NOT
//! reimplement or "fix" it.
//!
//! NOTE: this file could not be compiled in the authoring sandbox (no Rust/NDK
//! there). Every rustpush call is ported from the harness, but exact spellings on
//! the receive/handle surface move between revisions — `// VERIFY:` marks the
//! spots most likely to need a tweak against your pinned rustpush rev. Build with
//! `scripts/build-rustpush-so.sh` and iterate against the compiler.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::io::Cursor;
use once_cell::sync::OnceCell;
use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, RwLock};
use tokio::runtime::Runtime;
use uuid::Uuid;

use rustpush::{
    authenticate_apple, login_apple_delegates, register, APSConnection, APSConnectionResource,
    APSState, AppleAccount, Attachment, ConversationData, IDSNGMIdentity, IDSUser, IMClient,
    IndexedMessagePart, LoginDelegate, LoginState, MMCSFile, Message, MessageInst, MessagePart,
    MessageParts, MessageType, NormalMessage, OSConfig, ReactMessage, ReactMessageType, Reaction,
    RelayConfig, VerifyBody, MADRID_SERVICE,
};

// Anisette straight out of the box (do NOT reimplement). With the `remote-anisette-v3`
// feature, `DefaultAnisetteProvider` = RemoteAnisetteProviderV3 (a remote anisette
// server, default https://ani.sidestore.io) and `default_provider` builds it. We
// use v3 (not ClearADI) because the target is armeabi-v7a, which ClearADI can't build for.
use omnisette::{default_provider, DefaultAnisetteProvider};

static RUNTIME: OnceCell<Runtime> = OnceCell::new();
static STATE: OnceCell<Mutex<AppState>> = OnceCell::new();
/// True while a recover (re-login + re-register) is in flight, so a burst of
/// "Resource has been closed" errors only kicks off ONE recover.
static RECOVERING: AtomicBool = AtomicBool::new(false);
/// Bumped every time a new receive loop starts; older loops see the mismatch and
/// exit, so a recover's fresh loop replaces the dead one instead of doubling up.
static RECV_GEN: AtomicU64 = AtomicU64::new(0);
/// True once the paired iPhone has enabled Text Message Forwarding for this device
/// (received as `Message::EnableSmsActivation`). Gates green (SMS) sends; persisted
/// to `<dir>/sms_active` so it survives restarts (the iPhone won't re-announce it).
static SMS_ACTIVE: AtomicBool = AtomicBool::new(false);

#[derive(Default)]
struct AppState {
    files_dir: String,
    apple_id: String,
    pw_hash: Vec<u8>,
    /// Set when the current 2FA challenge is SMS (needs the body echoed back).
    sms_2fa_body: Option<VerifyBody>,

    os_config: Option<Arc<dyn OSConfig>>,
    connection: Option<APSConnection>,
    account: Option<AppleAccount<DefaultAnisetteProvider>>,
    users: Vec<IDSUser>,
    identity: Option<IDSNGMIdentity>,
    client: Option<Arc<IMClient>>,
    /// My own registered handles (raw form), used for SENDING (pick a from-handle).
    my_handles: Vec<String>,
    /// Broader "who am I" set — registered handles PLUS every address the account
    /// could use (aliases, e.g. a gmail on the Apple ID) PLUS the login Apple ID.
    /// Used to detect self-sends (incl. from another device under an alias) and to
    /// exclude myself when deciding a thread's other participant(s).
    self_handles: Vec<String>,

    /// The handle the user chose to send FROM (raw rustpush form, e.g. "tel:+1…"
    /// or "mailto:…"). Empty = fall back to the first registered handle. Set from
    /// Kotlin via `nativeSetSendHandle` (handle-picker + Settings default).
    send_handle: String,

    connected: bool,
    receive_started: bool,
    /// Relay-wire-shaped event JSON objects waiting for `nativePollEvents`.
    inbound: VecDeque<serde_json::Value>,
    /// Received attachments, keyed by the guid we hand Kotlin ("<msgid>:<idx>").
    /// `nativeDownloadAttachment` looks the rustpush `Attachment` back up here and
    /// streams it from MMCS (or returns the inline bytes) on demand.
    attachments: std::collections::HashMap<String, Attachment>,
}

fn rt() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("tokio runtime"))
}
fn st() -> MutexGuard<'static, AppState> {
    STATE.get_or_init(|| Mutex::new(AppState::default())).lock().unwrap()
}

fn init_logger() {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}
fn out(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
fn err_json(msg: impl std::fmt::Display) -> String {
    serde_json::json!({ "error": msg.to_string() }).to_string()
}

// ---- persistence (mirrors imessage-register relay/apple.rs) -----------------

#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct SavedState {
    push: APSState,
    users: Vec<IDSUser>,
    identity: IDSNGMIdentity,
}
impl SavedState {
    fn is_registered(&self) -> bool {
        self.users.first().map(|u| !u.registration.is_empty()).unwrap_or(false)
    }
}
fn config_path(dir: &str) -> PathBuf {
    Path::new(dir).join("config.plist")
}
fn load_saved(dir: &str) -> Option<SavedState> {
    plist::from_file::<_, SavedState>(config_path(dir)).ok()
}
fn save_saved(dir: &str, s: &SavedState) {
    if let Err(e) = plist::to_file_xml(config_path(dir), s) {
        log::warn!("persist config.plist failed: {e}");
    }
}

/// Persist the Apple ID + SHA-256 password HASH (hex) so a recover after a 6005
/// identity-close can re-login without re-prompting. Never the plaintext.
fn save_creds(dir: &str, apple_id: &str, pw_hash: &[u8]) {
    let hex: String = pw_hash.iter().map(|b| format!("{b:02x}")).collect();
    let json = serde_json::json!({ "apple_id": apple_id, "pw_hash": hex }).to_string();
    if let Err(e) = std::fs::write(Path::new(dir).join("creds.json"), json) {
        log::warn!("persist creds failed: {e}");
    }
}
fn load_creds(dir: &str) -> Option<(String, Vec<u8>)> {
    let bytes = std::fs::read(Path::new(dir).join("creds.json")).ok()?;
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    let apple_id = v.get("apple_id")?.as_str()?.to_string();
    let hex = v.get("pw_hash")?.as_str()?;
    let pw_hash = (0..hex.len())
        .step_by(2)
        .filter_map(|i| hex.get(i..i + 2).and_then(|b| u8::from_str_radix(b, 16).ok()))
        .collect();
    Some((apple_id, pw_hash))
}

/// Install the process-global software keystore, backed by `<dir>/keystore.plist`.
/// MUST run before APNs connect / any signing (rustpush requirement).
fn init_keystore_persisted(dir: &str) {
    let ks_path = Path::new(dir).join("keystore.plist");
    let initial: keystore::software::SoftwareKeystoreState =
        plist::from_file(&ks_path).unwrap_or_default();
    let write_path = ks_path.clone();
    keystore::init_keystore(keystore::software::SoftwareKeystore {
        state: RwLock::new(initial),
        update_state: Box::new(move |s| {
            if let Err(e) = plist::to_file_xml(&write_path, s) {
                log::error!("keystore persist failed: {e}");
            }
        }),
        encryptor: keystore::software::NoEncryptor,
    });
}

/// Stable per-install dev_uuid + 64-hex udid for the RelayConfig (persisted so a
/// re-registration describes the same device). Same approach as
/// imessage-register/src/config.rs.
fn relay_identity(dir: &str) -> (String, String) {
    #[derive(serde::Serialize, serde::Deserialize)]
    struct Id {
        dev_uuid: String,
        udid: String,
    }
    let path = Path::new(dir).join("relay_identity.json");
    if let Ok(bytes) = std::fs::read(&path) {
        if let Ok(id) = serde_json::from_slice::<Id>(&bytes) {
            return (id.dev_uuid, id.udid);
        }
    }
    let id = Id {
        dev_uuid: Uuid::new_v4().to_string(),
        udid: format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple()).to_uppercase(),
    };
    let _ = std::fs::write(&path, serde_json::to_vec_pretty(&id).unwrap_or_default());
    (id.dev_uuid, id.udid)
}

// =============================================================================
// JNI: lifecycle
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    relay_host: JString,
    relay_code: JString,
) -> jboolean {
    init_logger();
    // The rustls stack (reqwest) needs a process crypto provider; ring is bundled.
    let _ = rustls::crypto::ring::default_provider().install_default();

    let dir = jstr(&mut env, &files_dir);
    let host = {
        let h = jstr(&mut env, &relay_host);
        if h.is_empty() { "https://hw.openbubbles.app".to_string() } else { h.trim_end_matches('/').to_string() }
    };
    let code = jstr(&mut env, &relay_code);
    if code.is_empty() {
        log::error!("nativeInit: empty relay code");
        return JNI_FALSE;
    }

    init_keystore_persisted(&dir);
    let (dev_uuid, udid) = relay_identity(&dir);

    // Build RelayConfig: fetch the device version info from the relay up front.
    let built = rt().block_on(async {
        let version = RelayConfig::get_versions(&host, &code, &None).await?;
        Ok::<_, rustpush::PushError>(RelayConfig {
            version,
            icloud_ua: "com.apple.iCloudHelper/282 CFNetwork/1408.0.4 Darwin/22.5.0".to_string(),
            aoskit_version: "com.apple.AOSKit/282 (com.apple.accountsd/113)".to_string(),
            dev_uuid,
            protocol_version: 1640,
            host,
            code,
            beeper_token: None,
            udid: Some(udid),
        })
    });
    let cfg = match built {
        Ok(c) => c,
        Err(e) => {
            log::error!("nativeInit: relay get-version-info failed: {e:?}");
            return JNI_FALSE;
        }
    };

    let mut s = st();
    s.files_dir = dir.clone();
    s.os_config = Some(Arc::new(cfg));
    // Restore a prior registration so a warm start skips login/2FA.
    if let Some(saved) = load_saved(&dir) {
        if saved.is_registered() {
            s.users = saved.users;
            s.identity = Some(saved.identity);
            log::info!("nativeInit: restored existing iMessage registration");
        }
    }
    log::info!("nativeInit ok (dir={dir})");
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeConnect(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // Idempotent: opening a SECOND APNs connection with the same push token makes
    // Apple drop both sockets — the "early eof → Send timed out, forcing reload!"
    // loop. The sign-in path connects, then the REGISTERED screen builds the repo
    // and would connect again; guard against that by reusing the live connection.
    if {
        let s = st();
        s.connected && s.connection.is_some()
    } {
        log::info!("nativeConnect: already connected — reusing");
        return JNI_TRUE;
    }
    let (os_config, dir, resume_users, resume_identity) = {
        let s = st();
        (
            s.os_config.clone(),
            s.files_dir.clone(),
            s.users.clone(),
            s.identity.clone(),
        )
    };
    let Some(os_config) = os_config else {
        log::error!("nativeConnect before nativeInit");
        return JNI_FALSE;
    };
    let saved_push = load_saved(&dir).map(|s| s.push);

    let conn = rt().block_on(async move {
        let (connection, err) = APSConnectionResource::new(os_config, saved_push).await;
        match err {
            Some(e) => Err(e),
            None => Ok(connection),
        }
    });
    let connection = match conn {
        Ok(c) => c,
        Err(e) => {
            log::error!("nativeConnect: APNs failed: {e:?}");
            return JNI_FALSE;
        }
    };
    {
        let mut s = st();
        s.connection = Some(connection);
        s.connected = true;
    }
    // If we resumed a registration, build the client + receive loop now.
    if !resume_users.is_empty() {
        if let Some(identity) = resume_identity {
            if let Err(e) = rt().block_on(build_client_and_receive(resume_users, identity)) {
                log::error!("nativeConnect: client build (resume) failed: {e}");
            }
        }
    }
    log::info!("nativeConnect ok");
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeIsConnected(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if st().connected { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeIsRegistered(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let s = st();
    if s.client.is_some() || s.users.iter().any(|u| !u.registration.is_empty()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut s = st();
    s.connected = false;
    s.connection = None;
    s.client = None;
    s.receive_started = false;
}

// =============================================================================
// JNI: login (split so the Kotlin sign-in screen drives the interactive 2FA)
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeAuthenticate<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    apple_id: JString<'l>,
    password: JString<'l>,
) -> jstring {
    let apple = jstr(&mut env, &apple_id);
    let pw_hash = Sha256::digest(jstr(&mut env, &password).as_bytes()).to_vec();

    let (os_config, connection, dir) = {
        let s = st();
        (s.os_config.clone(), s.connection.clone(), s.files_dir.clone())
    };
    let (Some(os_config), Some(connection)) = (os_config, connection) else {
        return out(&mut env, err_json("not connected — call nativeConnect first"));
    };

    // Clone for the async closure; the originals are stored in state below.
    let apple_c = apple.clone();
    let pw_c = pw_hash.clone();
    let result = rt().block_on(async move {
        let gsa = os_config.get_gsa_config(&*connection.state.read().await, false);
        let anisette = default_provider(gsa.clone(), Path::new(&dir).join("anisette"));
        let mut account = rustpush::AppleAccount::new_with_anisette(gsa, anisette)
            .map_err(|e| format!("new_with_anisette: {e:?}"))?;
        let state = account
            .login_email_pass(&apple_c, &pw_c)
            .await
            .map_err(|e| format!("login_email_pass: {e:?}"))?;
        log::info!("login_email_pass → {state:?}");

        // Returns (status_json, sms_body). status = "logged_in" | "needs_2fa".
        let (status, sms) = match state {
            LoginState::LoggedIn => ("logged_in", None),
            LoginState::Needs2FAVerification => ("needs_2fa", None),
            LoginState::NeedsDevice2FA => {
                account.send_2fa_to_devices().await.map_err(|e| format!("send_2fa_to_devices: {e:?}"))?;
                ("needs_2fa", None)
            }
            LoginState::NeedsSMS2FA => {
                match account.send_sms_2fa_to_devices(1).await.map_err(|e| format!("send_sms_2fa: {e:?}"))? {
                    LoginState::NeedsSMS2FAVerification(body) => ("needs_2fa", Some(body)),
                    other => {
                        log::warn!("send_sms_2fa unexpected: {other:?}");
                        ("needs_2fa", None)
                    }
                }
            }
            LoginState::NeedsSMS2FAVerification(body) => ("needs_2fa", Some(body)),
            LoginState::NeedsExtraStep(s) => {
                if account.get_pet().is_some() { ("logged_in", None) } else {
                    return Err(format!("login needs extra step: {s}"));
                }
            }
            LoginState::NeedsLogin => return Err("bad credentials".to_string()),
        };
        Ok::<_, String>((account, status, sms))
    });

    match result {
        Ok((account, status, sms)) => {
            let mut s = st();
            s.apple_id = apple;
            s.pw_hash = pw_hash;
            s.sms_2fa_body = sms;
            s.account = Some(account);
            // Persist for recover-on-close (hashed only).
            save_creds(&s.files_dir, &s.apple_id, &s.pw_hash);
            out(&mut env, serde_json::json!({ "status": status }).to_string())
        }
        Err(e) => {
            log::error!("nativeAuthenticate: {e}");
            out(&mut env, err_json(e))
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSubmit2fa<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    code: JString<'l>,
) -> jstring {
    let code = jstr(&mut env, &code);
    let (mut account, apple, pw_hash, sms_body) = {
        let mut s = st();
        match s.account.take() {
            Some(a) => (a, s.apple_id.clone(), s.pw_hash.clone(), s.sms_2fa_body.take()),
            None => return out(&mut env, err_json("no login in progress")),
        }
    };

    let result = rt().block_on(async move {
        let verified = if let Some(body) = sms_body {
            account.verify_sms_2fa(code, body).await.map_err(|e| format!("verify_sms_2fa: {e:?}"))?
        } else {
            account.verify_2fa(code).await.map_err(|e| format!("verify_2fa: {e:?}"))?
        };
        log::info!("2FA verify → {verified:?}");
        match verified {
            LoginState::LoggedIn | LoginState::NeedsExtraStep(_) => {}
            LoginState::NeedsLogin => {
                // Trusted-device path accepts the code (ec=0) but issues no PET;
                // re-run SRP to collect it (the harness' PET trick).
                account.login_email_pass(&apple, &pw_hash).await.map_err(|e| format!("post-2fa re-login: {e:?}"))?;
            }
            other => return Err(format!("unexpected 2FA result: {other:?}")),
        }
        if account.get_pet().is_none() {
            return Err("no PET after 2FA — registration would fail".to_string());
        }
        Ok::<_, String>(account)
    });

    match result {
        Ok(account) => {
            st().account = Some(account);
            out(&mut env, serde_json::json!({ "status": "logged_in" }).to_string())
        }
        Err(e) => {
            log::error!("nativeSubmit2fa: {e}");
            out(&mut env, err_json(e))
        }
    }
}

// =============================================================================
// JNI: register (IDS delegates → authenticate → register → IMClient)
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeRegister<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    _apple_id: JString<'l>,
) -> jstring {
    let (os_config, connection, account, dir) = {
        let mut s = st();
        (s.os_config.clone(), s.connection.clone(), s.account.take(), s.files_dir.clone())
    };
    let (Some(os_config), Some(connection), Some(account)) = (os_config, connection, account) else {
        return out(&mut env, err_json("not logged in — call nativeAuthenticate first"));
    };

    let result = rt().block_on(async move {
        // iMessage is IDS-only. Requesting MobileMe triggers ICLOUD_UNSUPPORTED_DEVICE.
        let delegates = login_apple_delegates(&account, None, os_config.as_ref(), &[LoginDelegate::IDS])
            .await
            .map_err(|e| format!("login_apple_delegates: {e:?}"))?;
        let ids = delegates.ids.ok_or_else(|| "no IDS delegate".to_string())?;
        let user = authenticate_apple(ids, os_config.as_ref())
            .await
            .map_err(|e| format!("authenticate_apple: {e:?}"))?;
        let mut users = vec![user];
        let identity = IDSNGMIdentity::new().map_err(|e| format!("identity: {e:?}"))?;
        register(os_config.as_ref(), &*connection.state.read().await, &[&MADRID_SERVICE], &mut users, &identity)
            .await
            .map_err(|e| format!("register: {e:?}"))?;
        Ok::<_, String>((users, identity))
    });

    let (users, identity) = match result {
        Ok(v) => v,
        Err(e) => {
            log::error!("nativeRegister: {e}");
            return out(&mut env, err_json(e));
        }
    };

    // Persist for warm-start resume, then build the client + receive loop.
    {
        let push = rt().block_on(async {
            st().connection.as_ref().unwrap().state.read().await.clone()
        });
        save_saved(&dir, &SavedState { push, users: users.clone(), identity: identity.clone() });
    }
    if let Err(e) = rt().block_on(build_client_and_receive(users, identity)) {
        log::error!("nativeRegister: client build failed: {e}");
        return out(&mut env, err_json(e));
    }

    let handles = rt().block_on(async {
        let client = st().client.clone();
        match client {
            Some(c) => c.identity.get_handles().await,
            None => vec![],
        }
    });
    log::info!("REGISTERED with iMessage ✅ handles={handles:?}");
    out(&mut env, serde_json::json!({ "handles": handles }).to_string())
}

/// Build the IMClient from registered users + identity and spawn the APNs receive
/// loop that drains inbound messages onto `AppState.inbound` as relay-wire JSON.
async fn build_client_and_receive(users: Vec<IDSUser>, identity: IDSNGMIdentity) -> Result<(), String> {
    let (os_config, connection, dir) = {
        let s = st();
        (s.os_config.clone(), s.connection.clone(), s.files_dir.clone())
    };
    let os_config = os_config.ok_or("no os_config")?;
    let connection = connection.ok_or("no connection")?;

    let client = IMClient::new(
        connection.clone(),
        users,
        identity,
        &[&MADRID_SERVICE],
        Path::new(&dir).join("id_cache.plist"),
        os_config.clone(),
        Box::new(|_updated| {}),
    )
    .await;
    let client = Arc::new(client);
    let handles = client.identity.get_handles().await;
    // Broader self set for self-send detection: aliases on the account (a gmail,
    // etc.) that get_handles() may omit. get_possible_handles hits IDS, so tolerate
    // failure and fall back to the registered handles.
    let possible = client
        .identity
        .get_possible_handles()
        .await
        .map(|s| s.into_iter().collect::<Vec<_>>())
        .unwrap_or_default();
    {
        let mut s = st();
        let mut self_handles = handles.clone();
        self_handles.extend(possible);
        if !s.apple_id.is_empty() {
            self_handles.push(s.apple_id.clone());
        }
        self_handles.sort();
        self_handles.dedup();
        s.client = Some(client.clone());
        s.my_handles = handles;
        s.self_handles = self_handles;
        log::info!("handles: my={:?} self={:?}", s.my_handles, s.self_handles);
    }
    // Restore whether SMS forwarding was previously enabled by the iPhone.
    SMS_ACTIVE.store(load_sms_active(&dir), Ordering::SeqCst);

    // Bump the generation so any older receive loop (e.g. one left over from a
    // closed identity after a recover) exits when it sees the mismatch, and this
    // fresh loop takes over. VERIFY: the subscribe/handle surface moves between
    // rustpush revisions — mirrors the imessage-register engine receive pattern.
    let my_gen = RECV_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    let connection = st().connection.clone().unwrap();
    rt().spawn(async move {
        let mut sub = connection.messages_cont.subscribe();
        loop {
            if RECV_GEN.load(Ordering::SeqCst) != my_gen {
                break; // superseded by a newer loop (a recover)
            }
            match sub.recv().await {
                Ok(apns_msg) => {
                    // Handle each message in its OWN task so a panic inside rustpush
                    // is contained (surfaced as a JoinError, not a process abort) —
                    // but AWAIT it before taking the next message, so messages are
                    // decrypted and queued in strict receive order (no concurrent-task
                    // reordering).
                    let client = client.clone();
                    let joined = rt().spawn(async move { client.handle(apns_msg).await }).await;
                    match joined {
                        Ok(Ok(Some(msg))) => push_relay_event(msg),
                        Ok(Ok(None)) => {}
                        Ok(Err(e)) => {
                            let dbg = format!("{e:?}");
                            log::warn!("receive handle error: {dbg}");
                            spawn_recover_if_closed(&dbg);
                        }
                        Err(join_err) => log::error!("receive handle panicked: {join_err}"),
                    }
                }
                Err(_) => break,
            }
        }
        log::warn!("receive loop ended (gen {my_gen})");
    });
    Ok(())
}

/// If `err_dbg` is the 6005 identity-closed error, kick off ONE background recover
/// (fresh login + re-register + new IMClient). rustpush marks a 6005 resource
/// `DoNotRetry`/`Closed` and never revives it, so a brand-new client is the only
/// path back to receiving.
fn spawn_recover_if_closed(err_dbg: &str) {
    let closed = err_dbg.contains("ResourceClosed")
        || err_dbg.contains("Resource has been closed")
        || err_dbg.contains("6005");
    if !closed {
        return;
    }
    if RECOVERING.swap(true, Ordering::SeqCst) {
        return; // a recover is already in flight
    }
    rt().spawn(async {
        log::warn!("identity closed (6005) — recovering: fresh login + re-register");
        match recover().await {
            Ok(()) => log::info!("recover ok — receiving again"),
            Err(e) => log::error!("recover failed: {e}"),
        }
        RECOVERING.store(false, Ordering::SeqCst);
    });
}

/// Rebuild a working identity after a 6005-close: a FRESH login (new anisette —
/// the point, since the stale anisette/token is what 6005'd) → IDS delegate →
/// authenticate → register → new IMClient + receive loop. Needs the persisted
/// hashed creds; if Apple now demands interactive 2FA we can't recover silently.
async fn recover() -> Result<(), String> {
    let (os_config, connection, dir) = {
        let s = st();
        (s.os_config.clone(), s.connection.clone(), s.files_dir.clone())
    };
    let os_config = os_config.ok_or("no os_config")?;
    let connection = connection.ok_or("no connection")?;
    let (apple_id, pw_hash) = load_creds(&dir).ok_or("no saved credentials to recover with")?;

    let gsa = os_config.get_gsa_config(&*connection.state.read().await, false);
    let anisette = default_provider(gsa.clone(), Path::new(&dir).join("anisette"));
    let mut account = rustpush::AppleAccount::new_with_anisette(gsa, anisette)
        .map_err(|e| format!("new_with_anisette: {e:?}"))?;
    match account.login_email_pass(&apple_id, &pw_hash).await.map_err(|e| format!("login: {e:?}"))? {
        LoginState::LoggedIn | LoginState::NeedsExtraStep(_) => {}
        other => return Err(format!("recover needs interactive 2FA ({other:?}); re-sign-in required")),
    }
    if account.get_pet().is_none() {
        return Err("no PET after recover login".to_string());
    }

    let delegates = login_apple_delegates(&account, None, os_config.as_ref(), &[LoginDelegate::IDS])
        .await
        .map_err(|e| format!("delegates: {e:?}"))?;
    let ids = delegates.ids.ok_or("no IDS delegate")?;
    let user = authenticate_apple(ids, os_config.as_ref())
        .await
        .map_err(|e| format!("authenticate: {e:?}"))?;
    let mut users = vec![user];
    let identity = IDSNGMIdentity::new().map_err(|e| format!("identity: {e:?}"))?;
    register(os_config.as_ref(), &*connection.state.read().await, &[&MADRID_SERVICE], &mut users, &identity)
        .await
        .map_err(|e| format!("register: {e:?}"))?;

    let push = connection.state.read().await.clone();
    save_saved(&dir, &SavedState { push, users: users.clone(), identity: identity.clone() });
    build_client_and_receive(users, identity).await?; // bumps RECV_GEN → dead loop exits
    Ok(())
}

/// Canonical handle key. MUST match Kotlin `Handles.canon` byte-for-byte so a
/// person's inbound thread, outbound thread, and contact entry all key the same:
/// scheme stripped, email lowercased, phone reduced to "+<digits>" (US 10-digit
/// gets a +1). This is what merges the phone/email/format-variant splits.
fn canon(handle: &str) -> String {
    let s = handle.trim();
    let s = s
        .strip_prefix("tel:")
        .or_else(|| s.strip_prefix("mailto:"))
        .unwrap_or(s)
        .trim();
    if s.contains('@') {
        return s.to_lowercase();
    }
    let digits: String = s.chars().filter(|c| c.is_ascii_digit()).collect();
    if s.starts_with('+') {
        format!("+{digits}")
    } else if digits.len() == 10 {
        format!("+1{digits}")
    } else if digits.len() == 11 && digits.starts_with('1') {
        format!("+{digits}")
    } else if digits.is_empty() {
        s.to_lowercase()
    } else {
        format!("+{digits}")
    }
}

/// Only surface messages/reactions from the last few days. A big offline backlog
/// on catch-up is dropped here so it never reaches storage or the UI — the sweet
/// spot for a low-RAM dumb phone (older history still lives on the user's other
/// Apple devices).
const SYNC_WINDOW_MS: u64 = 3 * 24 * 60 * 60 * 1000; // 3 days

/// Map a received rustpush `Message` to a relay-wire event and queue it.
/// VERIFY: field access on the rustpush Message variants against your pinned rev.
fn push_relay_event(msg: MessageInst) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    // The paired iPhone toggling Text Message Forwarding for this device on/off.
    if let Message::EnableSmsActivation(enabled) = &msg.message {
        SMS_ACTIVE.store(*enabled, Ordering::SeqCst);
        let dir = st().files_dir.clone();
        save_sms_active(&dir, *enabled);
        st().inbound.push_back(serde_json::json!({ "type": "sms_activation", "enabled": *enabled }));
        return;
    }
    // Sync window: drop messages/reactions older than SYNC_WINDOW_MS so a big
    // offline backlog on catch-up never enters the pipeline (no marshal, no queue,
    // no storage, no UI work). Control messages (SMS-activation, above) are exempt.
    // NOTE: rustpush has already DECRYPTED the message to get here — the sent time
    // lives in the encrypted payload, so Apple can't pre-filter by date; this trims
    // everything downstream of the decrypt.
    if matches!(&msg.message, Message::Message(_) | Message::React(_))
        && msg.sent_timestamp > 0
        && msg.sent_timestamp + SYNC_WINDOW_MS < now
    {
        return;
    }
    // A reaction (tapback) from someone — emit a tapback event the repo folds into
    // the target message's reactions.
    if let Message::React(react) = &msg.message {
        let (emoji, remove) = match &react.reaction {
            ReactMessageType::React { reaction, enable } => (reaction_emoji(reaction), !*enable),
            _ => return, // extension/sticker reactions not handled here
        };
        let sender = msg.sender.clone().unwrap_or_default();
        let my: Vec<String> = st().self_handles.iter().map(|h| canon(h)).collect();
        let is_from_me = my.contains(&canon(&sender));
        let participants: Vec<String> = msg
            .conversation
            .as_ref()
            .map(|c| c.participants.clone())
            .filter(|p| !p.is_empty())
            .unwrap_or_else(|| vec![sender.clone()]);
        let mut counterparts: Vec<String> = participants
            .iter()
            .map(|h| canon(h))
            .filter(|c| !c.is_empty() && !my.contains(c))
            .collect();
        counterparts.sort();
        counterparts.dedup();
        let chat_guid = if counterparts.len() > 1 {
            format!("iMessage;+;{}", counterparts.join(","))
        } else {
            format!("iMessage;-;{}", counterparts.first().cloned().unwrap_or_else(|| canon(&sender)))
        };
        log::info!(
            "recv tapback: target={} emoji={emoji} remove={remove} from_me={is_from_me} chat={chat_guid}",
            react.to_uuid
        );
        st().inbound.push_back(serde_json::json!({
            "type": "tapback",
            "chatGuid": chat_guid,
            "targetGuid": react.to_uuid,
            "emoji": emoji,
            "senderAddress": sender,
            "isFromMe": is_from_me,
            "remove": remove,
            // The reaction's real send time so the app bumps the chat by WHEN the
            // reaction happened, not when it was received — otherwise a backlog of
            // old reactions synced on launch reorders old chats to the top.
            "timestampMs": if msg.sent_timestamp > 0 { msg.sent_timestamp } else { now },
        }));
        return;
    }
    if let Message::Message(normal) = &msg.message {
        // Thread by the OTHER participant(s), not the raw sender. rustpush gives
        // conversation.participants (both parties for a 1:1, everyone for a group).
        // Canonicalise + drop my own handles so outbound/inbound AND cross-device
        // self-sends collapse into one thread, and phone/email/format variants merge.
        let my: Vec<String> = st().self_handles.iter().map(|h| canon(h)).collect();
        // A forwarded SMS arrives with the APNs sender set to MY OWN forwarding
        // number (the iPhone gateway); the real other party is the SMS from_handle.
        // Use it as the effective sender so the text reads as received FROM them
        // (not sent by me) and resolves to their contact — not my own number.
        let sender = match &normal.service {
            MessageType::SMS { from_handle: Some(fh), .. } if !fh.trim().is_empty() => to_handle(fh),
            _ => msg.sender.clone().unwrap_or_default(),
        };
        let is_from_me = my.contains(&canon(&sender));

        let participants: Vec<String> = msg
            .conversation
            .as_ref()
            .map(|c| c.participants.clone())
            .filter(|p| !p.is_empty())
            .unwrap_or_else(|| vec![sender.clone()]);
        let mut counterparts: Vec<String> = participants
            .iter()
            .map(|h| canon(h))
            .filter(|c| !c.is_empty() && !my.contains(c))
            .collect();
        counterparts.sort();
        counterparts.dedup();

        let cv_name = msg
            .conversation
            .as_ref()
            .and_then(|c| c.cv_name.clone())
            .filter(|s| !s.is_empty());
        // Group = more than one other party (or a named conversation). Give it a
        // stable guid from the sorted other-participant set so it doesn't collapse
        // into a single-person thread.
        let is_group = counterparts.len() > 1 || cv_name.is_some();
        let chat_guid = if is_group {
            format!("iMessage;+;{}", counterparts.join(","))
        } else {
            let other = counterparts.first().cloned().unwrap_or_else(|| canon(&sender));
            format!("iMessage;-;{other}")
        };
        log::info!(
            "recv msg: guid={} ts={} sender={sender} is_from_me={is_from_me} \
             is_group={is_group} counterparts={counterparts:?} cv_name={cv_name:?} \
             chat_guid={chat_guid}",
            msg.id, msg.sent_timestamp
        );

        // Pull attachment parts out: stash the rustpush `Attachment` so
        // `nativeDownloadAttachment` can stream it later, and describe each one on
        // the wire (guid + mime + name + kind) for the RelayMessage.attachments list.
        let mut att_json: Vec<serde_json::Value> = Vec::new();
        let mut stash: Vec<(String, Attachment)> = Vec::new();
        for (idx, part) in normal.parts.0.iter().enumerate() {
            if let MessagePart::Attachment(att) = &part.part {
                let guid = format!("{}:{}", msg.id, idx);
                let kind = if att.mime.starts_with("image/") { "image" }
                    else if att.mime.starts_with("video/") { "video" }
                    else if att.mime.starts_with("audio/") { "audio" }
                    else { "other" };
                att_json.push(serde_json::json!({
                    "guid": guid, "mimeType": att.mime, "name": att.name, "kind": kind,
                }));
                stash.push((guid, att.clone()));
            }
        }

        // The attachment placeholder (U+FFFC) renders as a stray "OBJ" glyph — drop
        // it so an image/voice message shows clean (usually empty) text.
        let clean_text: String = normal
            .parts
            .raw_text()
            .chars()
            .filter(|c| *c != '\u{fffc}')
            .collect::<String>()
            .trim()
            .to_string();

        // Green (SMS, forwarded by the iPhone) vs blue (iMessage).
        let service = if matches!(normal.service, MessageType::SMS { .. }) { "SMS" } else { "iMessage" };

        let event = serde_json::json!({
            "type": "new_message",
            "message": {
                "guid": msg.id,
                "chatGuid": chat_guid,
                "senderAddress": sender,
                "isFromMe": is_from_me,
                "text": clean_text,
                "timestampMs": if msg.sent_timestamp > 0 { msg.sent_timestamp } else { now },
                "service": service,
                "attachments": att_json,
                "chatName": cv_name.clone().unwrap_or_default(),
                "replyToGuid": normal.reply_guid.clone().unwrap_or_default(),
                // A message I sent (incl. from another device that synced here) was
                // at least delivered — show the receipt, don't leave it plain "Sent".
                "status": if is_from_me { "delivered" } else { "" },
            }
        });
        let mut s = st();
        for (guid, att) in stash {
            s.attachments.insert(guid, att);
        }
        s.inbound.push_back(event);
    }
}

// =============================================================================
// JNI: outbound + inbound drain
// =============================================================================

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSendText<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    text: JString<'l>,
    _temp_guid: JString<'l>,
    reply_to: JString<'l>,
) -> jstring {
    let chat = jstr(&mut env, &chat_guid);
    let body = jstr(&mut env, &text);
    let reply_to = jstr(&mut env, &reply_to);
    let client = st().client.clone();
    let Some(client) = client else {
        return out(&mut env, String::new());
    };

    let guid = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        let Some(handle) = pick_send_handle(&handles) else {
            return String::new();
        };
        // A group ("iMessage;+;a,b,c") always goes over iMessage to every member;
        // only a 1:1 gets per-number SMS-forwarding routing.
        let is_group = chat.contains(";+;");
        let service = if is_group {
            MessageType::IMessage
        } else {
            let recipient = recipient_from_chat_guid(&chat);
            match decide_route(&client, &handle, &recipient).await {
                Route::Block(reason) => return format!("ERR:{reason}"),
                Route::IMessage => MessageType::IMessage,
                Route::Sms { using_number } => MessageType::SMS {
                    is_phone: false,
                    using_number,
                    from_handle: None,
                },
            }
        };
        let is_sms = matches!(service, MessageType::SMS { .. });
        let mut msg = NormalMessage::new(body, service);
        // Thread as a reply to a specific message so it shows quoted on all devices.
        if !reply_to.is_empty() {
            // iMessage threads a reply by the originator's guid PLUS a part range
            // ("<part>:<start>:<end>"), not a bare part index — a real client doesn't
            // recognize "r:0:<guid>" as a reply (it shows as a plain message). "0:0:0"
            // = reply to (the whole of) part 0.
            log::info!("nativeSendText: reply to {reply_to}");
            msg.reply_guid = Some(reply_to.clone());
            msg.reply_part = Some("0:0:0".to_string());
        }
        let mut inst = MessageInst::new(
            ConversationData {
                // All members for a group ("iMessage;+;a,b,c"); the single
                // recipient for a 1:1. rustpush adds the sender (&handle) itself.
                participants: participants_from_chat_guid(&chat),
                cv_name: None,
                sender_guid: Some(Uuid::new_v4().to_string()),
                after_guid: None,
            },
            &handle,
            Message::Message(msg),
        );
        match client.send(&mut inst).await {
            Ok(_) => {
                let guid = inst.id.clone(); // VERIFY: server guid field name on MessageInst
                // Optimistic "delivered" so the bubble shows a receipt. Apple's real
                // delivery receipt arrives later over APNs (via the receive loop).
                st().inbound.push_back(serde_json::json!({
                    "type": "message_status",
                    "chatGuid": chat,
                    "guid": guid,
                    "status": "delivered",
                    "service": if is_sms { "SMS" } else { "iMessage" },
                }));
                guid
            }
            Err(e) => {
                log::error!("nativeSendText: send failed: {e:?}");
                String::new()
            }
        }
    });
    out(&mut env, guid)
}

/// Upload `data` to MMCS and send it as an attachment message. An audio mime
/// is flagged `voice = true` so it renders as a voice-message bubble (this is how
/// the voice memo recorder's output reaches iMessage). Returns the server guid.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSendAttachment<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    _temp_guid: JString<'l>,
    data: JByteArray<'l>,
    mime: JString<'l>,
    name: JString<'l>,
) -> jstring {
    let chat = jstr(&mut env, &chat_guid);
    let mime_s = jstr(&mut env, &mime);
    let name_s = jstr(&mut env, &name);
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return out(&mut env, String::new()),
    };
    let client = st().client.clone();
    let connection = st().connection.clone();
    let (Some(client), Some(connection)) = (client, connection) else {
        return out(&mut env, String::new());
    };
    let voice = mime_s.starts_with("audio/");
    let uti = uti_for_mime(&mime_s);

    let guid = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        let Some(handle) = pick_send_handle(&handles) else {
            return String::new();
        };
        // Group attachments go over iMessage to every member; only a 1:1 gets
        // per-number SMS-forwarding routing.
        let is_group = chat.contains(";+;");
        let service = if is_group {
            MessageType::IMessage
        } else {
            let recipient = recipient_from_chat_guid(&chat);
            match decide_route(&client, &handle, &recipient).await {
                Route::Block(reason) => return format!("ERR:{reason}"),
                Route::IMessage => MessageType::IMessage,
                Route::Sms { using_number } => MessageType::SMS {
                    is_phone: false,
                    using_number,
                    from_handle: None,
                },
            }
        };
        let is_sms = matches!(service, MessageType::SMS { .. });

        // 1) Upload to MMCS: prepare_put computes the signature, new_mmcs uploads.
        // Both consume a reader over the bytes, so give each its own cursor.
        let prepared = match MMCSFile::prepare_put(Cursor::new(bytes.clone())).await {
            Ok(p) => p,
            Err(e) => {
                log::error!("nativeSendAttachment: prepare_put: {e:?}");
                return String::new();
            }
        };
        let attachment = match Attachment::new_mmcs(
            &*connection,
            &prepared,
            Cursor::new(bytes.clone()),
            &mime_s,
            &uti,
            &name_s,
            |_, _| {},
        )
        .await
        {
            Ok(a) => a,
            Err(e) => {
                log::error!("nativeSendAttachment: mmcs upload: {e:?}");
                return String::new();
            }
        };

        // 2) A message whose single part is that attachment.
        let mut normal = NormalMessage::new(String::new(), service);
        normal.parts = MessageParts(vec![IndexedMessagePart {
            part: MessagePart::Attachment(attachment),
            idx: None,
            ext: None,
        }]);
        normal.voice = voice;
        let mut inst = MessageInst::new(
            ConversationData {
                // All members for a group; the single recipient for a 1:1.
                participants: participants_from_chat_guid(&chat),
                cv_name: None,
                sender_guid: Some(Uuid::new_v4().to_string()),
                after_guid: None,
            },
            &handle,
            Message::Message(normal),
        );
        match client.send(&mut inst).await {
            Ok(_) => {
                let guid = inst.id.clone();
                st().inbound.push_back(serde_json::json!({
                    "type": "message_status",
                    "chatGuid": chat,
                    "guid": guid,
                    "status": "delivered",
                    "service": if is_sms { "SMS" } else { "iMessage" },
                }));
                guid
            }
            Err(e) => {
                log::error!("nativeSendAttachment: send failed: {e:?}");
                String::new()
            }
        }
    });
    out(&mut env, guid)
}

/// Download a received attachment (by the guid emitted in `push_relay_event`),
/// streaming it back from MMCS (or returning inline bytes). Null on any failure.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeDownloadAttachment<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    guid: JString<'l>,
) -> jbyteArray {
    let key = jstr(&mut env, &guid);
    let connection = st().connection.clone();
    let attachment = st().attachments.get(&key).cloned();
    let stash_len = st().attachments.len();
    if attachment.is_none() {
        // The stash is IN-MEMORY only, populated when a message is received; it is
        // lost on process restart. A guid received in a previous session (or a
        // self-synced send whose parts were never stashed) will miss here — which
        // is the usual reason a "tap to retry" never succeeds.
        log::warn!(
            "nativeDownloadAttachment: NO stashed attachment for key='{key}' \
             (stash has {stash_len} entries) — attachment reference not in memory \
             (received in a prior session, or never stashed). Cannot download."
        );
    }
    if connection.is_none() {
        log::warn!("nativeDownloadAttachment: APNs connection not ready for key='{key}'");
    }
    let (Some(connection), Some(attachment)) = (connection, attachment) else {
        return std::ptr::null_mut();
    };
    log::info!("nativeDownloadAttachment: downloading key='{key}' (stash={stash_len})");
    let key_log = key.clone();
    let bytes = rt().block_on(async move {
        let mut buf: Vec<u8> = Vec::new();
        match attachment.get_attachment(&*connection, &mut buf, |_, _| {}).await {
            Ok(()) => Some(buf),
            Err(e) => {
                log::error!("nativeDownloadAttachment: get_attachment failed for '{key_log}': {e:?}");
                None
            }
        }
    });
    log::info!(
        "nativeDownloadAttachment: key='{key}' -> {} bytes",
        bytes.as_ref().map(|b| b.len()).unwrap_or(0)
    );
    match bytes {
        Some(b) => env
            .byte_array_from_slice(&b)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// The user's chosen send-from handle if it's still one of their registered
/// handles, else the first registered handle.
fn pick_send_handle(handles: &[String]) -> Option<String> {
    let preferred = st().send_handle.clone();
    if !preferred.is_empty() && handles.iter().any(|h| h == &preferred) {
        return Some(preferred);
    }
    handles.first().cloned()
}

fn sms_active_path(dir: &str) -> std::path::PathBuf {
    Path::new(dir).join("sms_active")
}
fn load_sms_active(dir: &str) -> bool {
    std::fs::read_to_string(sms_active_path(dir)).map(|s| s.trim() == "1").unwrap_or(false)
}
fn save_sms_active(dir: &str, on: bool) {
    let _ = std::fs::write(sms_active_path(dir), if on { "1" } else { "0" });
}

/// Where an outgoing message goes, decided per-recipient (auto-detect): iMessage
/// if the number/email is registered on iMessage, else green SMS through the
/// paired iPhone — or a user-facing reason to block the send.
enum Route {
    IMessage,
    Sms { using_number: String },
    Block(String),
}

async fn decide_route(client: &IMClient, handle: &str, recipient: &str) -> Route {
    let targets = vec![recipient.to_string()];
    // On a lookup FAILURE default to iMessage — don't silently misroute a real
    // iMessage contact to SMS just because a network check hiccupped. Only an
    // explicit empty result means "definitely not on iMessage".
    let valid = match client
        .identity
        .validate_targets(&targets, "com.apple.madrid", handle)
        .await
    {
        Ok(v) => v,
        Err(e) => {
            log::warn!("validate_targets failed ({e:?}); defaulting to iMessage");
            return Route::IMessage;
        }
    };
    if valid.iter().any(|t| t == recipient) {
        return Route::IMessage;
    }
    // Not on iMessage. Only phone numbers can fall back to SMS.
    if !recipient.starts_with("tel:") {
        return Route::Block("This address isn't on iMessage, and only phone numbers can receive a text.".to_string());
    }
    if !SMS_ACTIVE.load(Ordering::SeqCst) {
        return Route::Block(
            "SMS forwarding isn't on. On your iPhone (same Apple ID): Settings ▸ Messages ▸ Text Message Forwarding ▸ turn on this device."
                .to_string(),
        );
    }
    // Forward from my own phone number (the tel: handle on my account).
    match st().my_handles.iter().find(|h| h.starts_with("tel:")).cloned() {
        Some(number) => Route::Sms { using_number: number },
        None => Route::Block("Your iCloud account has no phone number to text from.".to_string()),
    }
}

/// Set the handle outgoing messages are sent FROM (the Settings "default send"
/// picker + the post-login handle picker). Empty string clears it.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSetSendHandle<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: JString<'l>,
) {
    let h = jstr(&mut env, &handle);
    log::info!("nativeSetSendHandle: {h}");
    st().send_handle = h;
}

/// Is `handle` reachable on iMessage? Drives the composer color (blue = iMessage,
/// green = SMS) BEFORE anything is sent. Defaults to true (iMessage/blue) when the
/// client isn't up or the lookup fails, so we never wrongly show green.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeIsIMessage<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: JString<'l>,
) -> jboolean {
    let recipient = to_handle(&jstr(&mut env, &handle));
    let client = st().client.clone();
    let Some(client) = client else {
        return JNI_TRUE;
    };
    let is_imessage = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        let Some(my) = pick_send_handle(&handles) else {
            return true;
        };
        match client
            .identity
            .validate_targets(&[recipient.clone()], "com.apple.madrid", &my)
            .await
        {
            Ok(valid) => valid.iter().any(|t| t == &recipient),
            Err(_) => true,
        }
    });
    if is_imessage {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Best-effort UTI for a mime type (iMessage attachments carry a `uti-type`).
fn uti_for_mime(mime: &str) -> String {
    match mime {
        "image/jpeg" => "public.jpeg",
        "image/png" => "public.png",
        "image/gif" => "com.compuserve.gif",
        "image/heic" | "image/heif" => "public.heic",
        "video/mp4" => "public.mpeg-4",
        "video/quicktime" => "com.apple.quicktime-movie",
        "audio/mp4" | "audio/m4a" | "audio/x-m4a" => "com.apple.m4a-audio",
        "audio/aac" => "public.aac-audio",
        "audio/amr" => "org.3gpp.adaptive-multi-rate-audio",
        "audio/caf" | "audio/x-caf" => "com.apple.coreaudio-format",
        "application/pdf" => "com.adobe.pdf",
        _ => "public.data",
    }
    .to_string()
}

/// BlueBubbles reaction index (0-5) → rustpush `Reaction`. 6+ (arbitrary emoji)
/// isn't produced by the Kotlin Tapback enum, so it's unsupported here.
fn reaction_from_idx(idx: u64) -> Option<Reaction> {
    Some(match idx {
        0 => Reaction::Heart,
        1 => Reaction::Like,
        2 => Reaction::Dislike,
        3 => Reaction::Laugh,
        4 => Reaction::Emphasize,
        5 => Reaction::Question,
        _ => return None,
    })
}

/// rustpush `Reaction` → the emoji the UI keys reactions by (matches Kotlin Tapback).
fn reaction_emoji(r: &Reaction) -> String {
    match r {
        Reaction::Heart => "❤️",
        Reaction::Like => "👍",
        Reaction::Dislike => "👎",
        Reaction::Laugh => "😂",
        Reaction::Emphasize => "‼️",
        Reaction::Question => "❓",
        Reaction::Emoji(e) => return e.clone(),
        Reaction::Sticker { .. } => "🩷",
    }
    .to_string()
}

/// A bare address → a rustpush handle ("tel:"/"mailto:").
fn to_handle(addr: &str) -> String {
    let a = addr.trim();
    if a.starts_with("tel:") || a.starts_with("mailto:") {
        a.to_string()
    } else if a.contains('@') {
        format!("mailto:{a}")
    } else {
        format!("tel:{a}")
    }
}

/// Recipient handles for a chat guid: the single other party for a 1:1
/// ("iMessage;-;<addr>"), or the whole member set for a group ("iMessage;+;a,b,c").
fn participants_from_chat_guid(g: &str) -> Vec<String> {
    let ident = g.rsplit(';').next().unwrap_or(g);
    if g.contains(";+;") {
        ident.split(',').filter(|s| !s.is_empty()).map(to_handle).collect()
    } else {
        vec![recipient_from_chat_guid(g)]
    }
}

/// Send an iMessage reaction (tapback). `associated_message_type` is the
/// BlueBubbles code: 2000-2005 add a Heart/Like/Dislike/Laugh/Emphasize/Question,
/// 3000-3005 remove it. Returns true on a successful send.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSendTapback<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    target_guid: JString<'l>,
    associated_message_type: jint,
) -> jboolean {
    let chat = jstr(&mut env, &chat_guid);
    let target = jstr(&mut env, &target_guid);
    let code = associated_message_type as i64;
    let enable = (2000..3000).contains(&code);
    let Some(reaction) = reaction_from_idx(code.rem_euclid(1000) as u64) else {
        log::warn!("nativeSendTapback: unsupported reaction code {code}");
        return JNI_FALSE;
    };
    let client = st().client.clone();
    let Some(client) = client else {
        return JNI_FALSE;
    };

    let ok = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        let Some(handle) = pick_send_handle(&handles) else {
            return false;
        };
        let react = ReactMessage {
            to_uuid: target.clone(),
            to_part: Some(0),
            reaction: ReactMessageType::React { reaction, enable },
            to_text: String::new(),
            embedded_profile: None,
        };
        let mut inst = MessageInst::new(
            ConversationData {
                participants: participants_from_chat_guid(&chat),
                cv_name: None,
                sender_guid: Some(Uuid::new_v4().to_string()),
                after_guid: None,
            },
            &handle,
            Message::React(react),
        );
        match client.send(&mut inst).await {
            Ok(_) => true,
            Err(e) => {
                log::error!("nativeSendTapback: send failed: {e:?}");
                false
            }
        }
    });
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativePollEvents(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let drained: Vec<serde_json::Value> = st().inbound.drain(..).collect();
    out(&mut env, serde_json::to_string(&drained).unwrap_or_else(|_| "[]".into()))
}

/// `iMessage;-;<addr>` (or a bare/scheme'd address) → a rustpush handle.
fn recipient_from_chat_guid(g: &str) -> String {
    let addr = g.rsplit(';').next().unwrap_or(g).trim();
    if addr.starts_with("tel:") || addr.starts_with("mailto:") {
        addr.to_string()
    } else if addr.contains('@') {
        format!("mailto:{addr}")
    } else {
        format!("tel:{addr}")
    }
}
