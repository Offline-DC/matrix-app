//! JNI wrapper over OpenBubbles' rustpush — the `libsmarttxt_ffi.so` that
//! `RustPushNative.kt` loads (SMARTTXT_NATIVE_BACKEND_PLAN.md §3).
//!
//! This is the REAL implementation (no longer a stub). It registers the phone
//! with iMessage on-device using `MacOSConfigRemote` — the migrated OpenBubbles Mac
//! identity (os_config.plist) whose validation data is produced by the self-hosted
//! NAC server (macos_remote::NAC_BASE_URL), driven by the device `dumb` file. The
//! hardware relay has been REMOVED: there is no relay fallback, and nativeInit
//! refuses to start without the migrated identity + dumb. There is no open-absinthe
//! either: the crate builds rustpush with `default-features = false`, so the x86
//! emulator (and its unicorn C dep, which won't cross-compile to Android) is not
//! compiled — nothing here needs it.
//!
//! The flow, split across JNI calls so the interactive 2FA is driven by the Kotlin
//! sign-in screen:
//!
//!   nativeInit(dir, …)           load MacOSConfigRemote (os_config.plist+dumb) + keystore
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
/// The bounded inbound event queue + its drain policy. Split out so the catch-up
/// pacing logic is unit-testable without JNI, rustpush or an Android device.
mod inbound;

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
    APSState, AppleAccount, Attachment, AttachmentType, ConversationData, IDSNGMIdentity, IDSUser, IMClient,
    IndexedMessagePart, LoginDelegate, LoginState, MMCSFile, Message, MessageInst, MessagePart,
    MessageParts, MessageType, NormalMessage, OSConfig, ReactMessage, ReactMessageType, Reaction,
    VerifyBody, AuthenticationFSAResponse, MADRID_SERVICE, ResourceState, IDSService, PushError
};
use rustpush::facetime::{FACETIME_SERVICE, VIDEO_SERVICE};
use rustpush::findmy::MULTIPLEX_SERVICE;

/// The IDS services this device registers for.
///
/// MUST stay identical to OpenBubbles (`rust/src/api/api.rs:863` and `:457`). This
/// slice is serialised one-to-one into the `services` array of the `id-register`
/// body (rustpush `ids/user.rs`: `("services", Value::Array(services))`), so this
/// list is literally what Apple receives.
///
/// Smart Txt previously registered ONLY madrid. That made a device declaring
/// `device_class: "MacOS"` register iMessage but never FaceTime or Find My - a
/// shape no real Mac produces. It repeated on every automatic re-registration
/// (the slice is stored on IdentityResource and reused by `generate`), and for a
/// user migrating from OpenBubbles the first re-registration visibly STRIPPED
/// three services off an identity Apple had already seen carrying four.
///
/// Known asymmetry, deliberate: we register these but do NOT subscribe to their
/// APNs topics - `IMClient` asks only for
/// `["com.apple.private.alloy.sms", "com.apple.madrid"]`
/// (rustpush `imessage/aps_client.rs:125`). So the device is advertised as a
/// FaceTime / Find My endpoint that never answers. That is indistinguishable from
/// a Mac which is asleep or offline, so it should be inert - but note OpenBubbles
/// registers the same four AND answers on them, because it builds FTClient and
/// FindMyClient. We deliberately do not.
const IDS_SERVICES: &[&IDSService] = &[
    &MADRID_SERVICE,    // com.apple.madrid - iMessage. The only one we service.
    &MULTIPLEX_SERVICE, // com.apple.private.alloy.multiplex1 - Find My
    &FACETIME_SERVICE,  // com.apple.private.alloy.facetime.multi
    &VIDEO_SERVICE,     // com.apple.ess - FaceTime video / lp / mw
];
// The remote NAC path. Lives HERE, not in rustpush: decoding the `dumb` hardware
// file and minting a client UDID are app concerns — OpenBubbles does both in its own
// FFI layer and never asks rustpush to parse a hardware file. Keeping it here also
// keeps the rustpush fork close to upstream.
mod macos_remote;
use macos_remote::MacOSConfigRemote;

// Anisette straight out of the box (do NOT reimplement). With the `remote-anisette-v3`
// feature, `DefaultAnisetteProvider` = RemoteAnisetteProviderV3 (a remote anisette
// server, default https://ani.sidestore.io) and `default_provider` builds it. We
// use v3 (not ClearADI) because the target is armeabi-v7a, which ClearADI can't build for.
use omnisette::{default_provider, DefaultAnisetteProvider};

static RUNTIME: OnceCell<Runtime> = OnceCell::new();
static STATE: OnceCell<Mutex<AppState>> = OnceCell::new();
/// Bumped every time a new receive loop starts; older loops see the mismatch and
/// exit, so a recover's fresh loop replaces the dead one instead of doubling up.
static RECV_GEN: AtomicU64 = AtomicU64::new(0);
/// True once the paired iPhone has enabled Text Message Forwarding for this device
/// (received as `Message::EnableSmsActivation`). Gates green (SMS) sends; persisted
/// to `<dir>/sms_active` so it survives restarts (the iPhone won't re-announce it).
static SMS_ACTIVE: AtomicBool = AtomicBool::new(false);

// Group-chat identity (gid-keying) lives in a dependency-free module so it can be
// unit-tested on the host (see group_identity.rs + identity-tests/). `canon` moved
// there too since the identity helpers need it and it belongs with handle logic.
mod attachment_stash;
use attachment_stash::{
    inline_bytes, load_attachments, remove_blob, save_attachments, stash_entry, write_blob,
    StashedAttachment, ATTACHMENT_INLINE_BUDGET, ATTACHMENT_STASH_CAP,
};

mod group_identity;
use group_identity::{
    canon, effective_send_guid, members_csv, resolve_group_guid, send_identity, updated_meta,
    GroupMeta,
};

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

    /// The DEFAULT handle for NEW conversations (raw rustpush form, e.g. "tel:+1…"
    /// or "mailto:…"). Empty = fall back to the first registered handle. Set from
    /// Kotlin via `nativeSetSendHandle` (handle-picker + the Settings "Start new
    /// messages from" default). Existing threads override this via `thread_handles`.
    send_handle: String,

    /// Per-conversation self-handle: chat_guid → the registered handle this thread
    /// transmits from. Learned from synced messages I sent (Apple tells us which
    /// handle a thread uses) and pinned on first send, so a REPLY to an existing
    /// thread goes out from the thread's own handle rather than `send_handle`.
    /// Replying under a different self-handle than a thread was started with is what
    /// Apple renders as an added participant — the "group chat with yourself" bug.
    /// Persisted to thread_handles.json so this survives a warm start.
    thread_handles: std::collections::HashMap<String, String>,

    /// Per-GROUP identity: gid-keyed chat_guid ("iMessage;+;<gid>") → the group's
    /// member set (canon addresses) + display name (cv_name). A group is identified by
    /// Apple's stable group id (the plist `gid`, which rustpush surfaces as
    /// `ConversationData.sender_guid`), NOT by its participants — two groups with the
    /// same people (a named "sibs & sav" and an unnamed thread) have different gids.
    /// Learned from inbound group messages and app-created groups (nativeRegisterGroup),
    /// then REPLAYED verbatim on every outbound (text/attachment/tapback/read) so a
    /// reply carries the group's real gid + name and threads into the SAME conversation
    /// instead of forking a new members-only one. Persisted to group_meta.json.
    group_meta: std::collections::HashMap<String, GroupMeta>,
    /// Reverse index: sorted-canon-members CSV → gid-keyed chat_guid. Lets a group
    /// message that arrives WITHOUT a gid (some group MMS/SMS) map onto the known gid
    /// thread for the same people instead of forking a members-keyed guid. Rebuilt from
    /// group_meta on load; not persisted separately.
    group_by_members: std::collections::HashMap<String, String>,

    connected: bool,
    receive_started: bool,
    /// Relay-wire-shaped event JSON objects waiting for `nativePollEvents`, behind a
    /// bounded queue that also tallies the burst (see [`mod@inbound`]).
    inbound: inbound::InboundQueue,
    /// Received attachments, keyed by the guid we hand Kotlin ("<msgid>:<idx>").
    /// `nativeDownloadAttachment` looks the rustpush `Attachment` back up here and
    /// streams it from MMCS (or returns the inline bytes) on demand.
    ///
    /// PERSISTED to attachments.plist. This map used to be memory-only, which made
    /// every attachment received before the current launch permanently undownloadable
    /// ("NO stashed attachment … stash has 0 entries") — the process dies often on a
    /// 916 MB phone, so that was most of them. An `Attachment` carries the whole MMCS
    /// coordinate set (url / object / signature / key / size), so keeping it is enough
    /// to fetch the bytes later. BlueBubbles never hits this because a Mac has already
    /// downloaded every attachment into ~/Library/Messages/Attachments and its server
    /// just serves those files; with rustpush there is no such local copy.
    attachments: std::collections::HashMap<String, Attachment>,
    /// Insertion order of the `attachments` keys, so the stash can be FIFO-capped at
    /// [`ATTACHMENT_STASH_CAP`] instead of growing without bound. Rebuilt from the
    /// persisted file's order on load; not stored separately.
    attachment_order: VecDeque<String>,

    /// Guids of messages/reactions the app ALREADY has, so an Apple replay is
    /// dropped instead of re-delivered (see `push_relay_event`).
    ///
    /// Apple re-sends its stored backlog on every APS connect — rustpush's
    /// `IMClient::setup_conn` explicitly asks it to (madrid command 160, "flush
    /// cache"), which is how messages that arrived while the app was closed get
    /// through. The cost is that a few days of history is re-delivered on EVERY
    /// launch, and without this set the whole pipeline re-runs for messages the
    /// app already stored.
    ///
    /// Seeded from Kotlin's on-disk cache at startup (`nativeSeedSeen`, called
    /// before connect) and grown as we deliver. Deliberately NOT persisted here:
    /// the Kotlin cache is the single source of truth, so we can only ever
    /// suppress a message Kotlin has proven it holds. If that cache is ever lost,
    /// nothing is seeded, nothing is suppressed, and the replay repopulates it.
    seen_guids: std::collections::HashSet<String>,
}

impl AppState {
    /// Queue a relay-wire event for `nativePollEvents`, enforcing [`inbound::INBOUND_CAP`].
    /// The policy (and its unit tests) live in [`mod@inbound`]; this is the AppState
    /// adapter so every producer goes through one bounded path instead of calling
    /// `inbound.push_back` directly.
    fn queue_event(&mut self, event: serde_json::Value) {
        self.inbound.queue(&mut self.seen_guids, event);
    }
}

fn rt() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("tokio runtime"))
}

/// Run `fut` on the tokio runtime, but never let the calling JNI thread block longer
/// than `secs`. On timeout, log + return `Err(msg)`. This is the catch-all bound for
/// authenticate/register: tokio's timer races the whole future, so ANY hung async
/// network await inside (anisette, GrandSlam login, IDS, NAC validation) is cancelled
/// and the UI gets an error instead of hanging forever — even if some inner HTTP
/// client lacked its own timeout. (A purely blocking/sync stall can't be cancelled
/// this way, but every network hop on these paths is async.)
fn block_on_timeout<T>(
    secs: u64,
    msg: &str,
    fut: impl std::future::Future<Output = Result<T, String>>,
) -> Result<T, String> {
    // catch_unwind so a PANIC inside the future (e.g. a keystore/plist `.expect`, like the
    // `Failed to decrypt!` at keystore/software.rs get_key) becomes a returned Err the JNI
    // caller can surface — instead of unwinding across the extern "system" boundary, which
    // is UB and in practice hangs the UI forever with the call never returning. The panic
    // hook (init_logger) has already logged "RUST PANIC …" with the real location.
    let driven = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        rt().block_on(async move {
            match tokio::time::timeout(std::time::Duration::from_secs(secs), fut).await {
                Ok(r) => r,
                Err(_) => {
                    log::error!("{msg} (timed out after {secs}s)");
                    Err(format!("{msg} (timed out after {secs}s)"))
                }
            }
        })
    }));
    match driven {
        Ok(r) => r,
        Err(_) => {
            log::error!("{msg}: panicked (see the RUST PANIC line above for the cause)");
            Err(format!("{msg}: internal error — please try again"))
        }
    }
}

/// Run [`build_client_and_receive`] on the runtime, turning BOTH an `Err` and a PANIC
/// (e.g. a keystore `get_key` on an unreadable entry) into an `Err(String)`. Both
/// `nativeConnect` (resume) and `nativeRegister` build the client OUTSIDE
/// `block_on_timeout`, so without this a panic there unwinds across JNI and hangs the UI.
fn build_client_guarded(users: Vec<IDSUser>, identity: IDSNGMIdentity) -> Result<(), String> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        rt().block_on(build_client_and_receive(users, identity))
    })) {
        Ok(r) => r,
        Err(_) => Err("client build panicked (internal error — see the RUST PANIC line in logs). Please try signing in again.".to_string()),
    }
}
fn st() -> MutexGuard<'static, AppState> {
    STATE.get_or_init(|| Mutex::new(AppState::default())).lock().unwrap()
}

fn init_logger() {
    // Per-target filter. `rustpush::util` (its mutex acquire/release tracing) and
    // `rustpush::aps` (per-frame APS chatter) log at INFO on every operation and are
    // ~95% of the Rust lines on a real capture — see SmartTxtLogRing.isNoise, which
    // already drops them at capture time on the Kotlin side. Emitting them at all is
    // what makes catch-up expensive: each line is a fresh String + a logcat write, and
    // a backlog replay produces tens of thousands of them inside a ~15MB heap, driving
    // GC churn on a device that is already under memory pressure. Silencing them at
    // the source (rather than filtering downstream) removes the allocation entirely.
    //
    // env_logger's Filter resolves the LONGEST matching directive prefix, so the
    // catch-all `None => Info` still applies to every other target while these two
    // are held down to Warn — real problems in them still surface.
    //
    // env_logger is already a mandatory (non-optional) dependency of android_logger
    // 0.13, so naming it directly in Cargo.toml pulls in nothing new.
    let filter = {
        let mut b = env_logger::filter::Builder::new();
        b.filter(None, log::LevelFilter::Info);
        b.filter(Some("rustpush::util"), log::LevelFilter::Warn);
        b.filter(Some("rustpush::aps"), log::LevelFilter::Warn);
        b.build()
    };
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_filter(filter)
            .with_tag("SmartTxtRust"),
    );
    // Install a panic hook ONCE that routes Rust panics through `log` (→ logcat).
    // The default hook writes to stderr, which Android discards — so a panic on a
    // native thread otherwise vanishes silently (and can leave the JNI caller hung,
    // exactly what happened with the anisette `panic!()`). Every JNI entry calls
    // init_logger, and nativeInit runs first, so the hook is live process-wide before
    // any later panic. It's global state, hence the `Once` guard against re-wrapping.
    static PANIC_HOOK: std::sync::Once = std::sync::Once::new();
    PANIC_HOOK.call_once(|| {
        let default_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            // Log the message + location FIRST (info's Display is
            // "panicked at <file>:<line>:<col>:\n<message>") so the essential line always
            // reaches logcat even if the backtrace step is slow. THEN force_capture a
            // backtrace for the call path INTO the panic (the `.so` is built panic=unwind,
            // so unwind info exists; strip=true just drops symbol NAMES, leaving addresses).
            // Message-first ordering means we never lose the location — unlike computing the
            // backtrace first. NB: a hook can't fully undo a panic that fires while a lock is
            // held (that stalls other threads), so panics on hot paths — e.g. the keystore
            // decrypt — are ALSO converted to errors at the source rather than relying on this.
            log::error!("RUST PANIC: {info}");
            log::error!("backtrace:\n{}", std::backtrace::Backtrace::force_capture());
            default_hook(info);
        }));
    });
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

/// Tag for the keystore key that wraps the persisted IDS identity. Must stay stable
/// — changing it makes every existing install's `config.plist` undecryptable.
const IDENTITY_TAG: &str = "smarttxt";

/// In-memory shape. NOT what goes on disk — see [`SavedStateDisk`].
#[derive(Clone)]
struct SavedState {
    push: APSState,
    users: Vec<IDSUser>,
    identity: IDSNGMIdentity,
}

/// On-disk shape. `identity` is the keystore-encrypted blob produced by
/// `IDSNGMIdentity::save` — rustpush's blessed path for exactly this.
///
/// It matters for this one type: `APSState.keypair` and `IDSRegistration.id_keypair`
/// are `KeyPairNew<RsaKey>`, which serialize as a keystore ALIAS (the private half
/// never leaves the keystore), but `IDSNGMIdentity`'s `device_key`/`pre_key` are raw
/// `CompactECKey<Private>` — serializing that struct directly writes real private-key
/// DER into the plist. Hence `save`/`restore`. OpenBubbles routes identity through
/// `identity.save("openbubbles")` at every site that persists it.
#[derive(serde::Serialize, serde::Deserialize)]
struct SavedStateDisk {
    push: APSState,
    users: Vec<IDSUser>,
    identity: plist::Data,
}

/// The pre-encryption on-disk shape, kept only so existing installs can be read once
/// and migrated. Remove once no shipped build writes it any more.
#[derive(serde::Deserialize)]
struct SavedStateLegacyPlaintext {
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
    let path = config_path(dir);
    // Preferred: identity stored as a keystore-encrypted blob.
    if let Ok(d) = plist::from_file::<_, SavedStateDisk>(&path) {
        let bytes: Vec<u8> = d.identity.into();
        match IDSNGMIdentity::restore(&bytes, IDENTITY_TAG) {
            Ok(identity) => {
                return Some(SavedState { push: d.push, users: d.users, identity });
            }
            Err(e) => log::error!(
                "config.plist: the identity blob would not decrypt ({e}). The keystore key \
                 'ids:identity-storage-key:{IDENTITY_TAG}' may have been cleared — a re-sign-in \
                 is required."
            ),
        }
    }
    // Legacy: identity written as a plaintext struct. Read it once and re-save encrypted.
    match plist::from_file::<_, SavedStateLegacyPlaintext>(&path) {
        Ok(l) => {
            let migrated = SavedState { push: l.push, users: l.users, identity: l.identity };
            log::warn!(
                "IDENTITY_MIGRATE: config.plist held the IDS identity in PLAINTEXT (raw EC \
                 private keys). Re-saving it keystore-encrypted via IDSNGMIdentity::save."
            );
            save_saved(dir, &migrated);
            Some(migrated)
        }
        Err(e) => {
            log::warn!("config.plist unreadable in both the encrypted and legacy shapes: {e}");
            None
        }
    }
}
fn save_saved(dir: &str, s: &SavedState) {
    let encrypted = match s.identity.save(IDENTITY_TAG) {
        Ok(bytes) => bytes,
        Err(e) => {
            log::error!("persist config.plist: identity encryption failed ({e}) — NOT writing the \
                         identity in the clear as a fallback. State is unchanged on disk.");
            return;
        }
    };
    let blob_len = encrypted.len();
    let disk = SavedStateDisk {
        push: s.push.clone(),
        users: s.users.clone(),
        identity: encrypted.into(),
    };
    match plist::to_file_xml(config_path(dir), &disk) {
        // Greppable positive confirmation: without this, "it worked" is only ever
        // provable by the ABSENCE of an error, which is not evidence.
        Ok(()) => log::info!(
            "IDENTITY_SEALED: config.plist written with the IDS identity keystore-encrypted \
             ({blob_len} B blob, tag '{IDENTITY_TAG}') — no private keys in the clear."
        ),
        Err(e) => log::warn!("persist config.plist failed: {e}"),
    }
}

/// Persist the Apple ID + SHA-256 password HASH (hex) so a recover after a 6005
/// identity-close can re-login without re-prompting. Never the plaintext.

/// Per-conversation self-handle map (chat_guid → registered send-from handle).
/// Persisted so replies to existing threads keep their handle across a warm start.
fn thread_handles_path(dir: &str) -> PathBuf {
    Path::new(dir).join("thread_handles.json")
}
fn load_thread_handles(dir: &str) -> std::collections::HashMap<String, String> {
    std::fs::read(thread_handles_path(dir))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}
fn save_thread_handles(dir: &str, map: &std::collections::HashMap<String, String>) {
    match serde_json::to_vec(map) {
        Ok(json) => {
            if let Err(e) = std::fs::write(thread_handles_path(dir), json) {
                log::warn!("persist thread_handles failed: {e}");
            }
        }
        Err(e) => log::warn!("serialize thread_handles failed: {e}"),
    }
}

// ---- group identity (gid-keyed) -------------------------------------------

/// Persisted map of gid-keyed chat_guid → GroupMeta (members + name), so a reply to a
/// group threads into the SAME Apple conversation across a warm start.
fn group_meta_path(dir: &str) -> PathBuf {
    Path::new(dir).join("group_meta.json")
}
fn load_group_meta(dir: &str) -> std::collections::HashMap<String, GroupMeta> {
    std::fs::read(group_meta_path(dir))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}
fn save_group_meta(dir: &str, map: &std::collections::HashMap<String, GroupMeta>) {
    match serde_json::to_vec(map) {
        Ok(json) => {
            if let Err(e) = std::fs::write(group_meta_path(dir), json) {
                log::warn!("persist group_meta failed: {e}");
            }
        }
        Err(e) => log::warn!("serialize group_meta failed: {e}"),
    }
}

/// Snapshot the stash in insertion order as a metadata-only index: inline payloads are
/// blanked here because they already live in their own blob files, which is what keeps
/// this cheap to rewrite.
fn ordered_attachments(s: &AppState) -> Vec<StashedAttachment> {
    s.attachment_order
        .iter()
        .filter_map(|k| s.attachments.get(k).map(|att| stash_entry(k, att)))
        .collect()
}

/// Record/refresh a group's identity for `chat_guid` (the pure decision lives in
/// [group_identity::updated_meta]). Persists only on a real change, mirroring
/// [remember_thread_handle].
fn remember_group_meta(chat_guid: &str, members: &[String], cv_name: &Option<String>) {
    if chat_guid.is_empty() {
        return;
    }
    let key = members_csv(members);
    let (dir, snapshot) = {
        let mut s = st();
        let new_meta = updated_meta(s.group_meta.get(chat_guid), members, cv_name);
        if s.group_meta.get(chat_guid) == Some(&new_meta) {
            return; // unchanged — skip the disk write
        }
        s.group_meta.insert(chat_guid.to_string(), new_meta);
        if !key.is_empty() {
            s.group_by_members.insert(key, chat_guid.to_string());
        }
        (s.files_dir.clone(), s.group_meta.clone())
    };
    save_group_meta(&dir, &snapshot);
}

/// The stable chat guid for an INBOUND group conversation ([resolve_group_guid]), plus
/// the side-effect of recording/refreshing its meta. `counterparts` are the canon
/// OTHER-party addresses (sorted + deduped by the caller).
fn group_chat_guid(gid: &Option<String>, counterparts: &[String], cv_name: &Option<String>) -> String {
    let guid = resolve_group_guid(gid.as_deref(), counterparts, &st().group_by_members);
    remember_group_meta(&guid, counterparts, cv_name);
    guid
}

/// The ConversationData for sending into `chat`. For a GROUP it replays the stored gid
/// + members + name ([send_identity]) so the message threads into the SAME Apple
/// conversation; a legacy members-keyed guid (no real gid) mints a random one. For a
/// 1:1 it targets the single recipient.
fn conv_data_for(chat: &str) -> ConversationData {
    if chat.contains(";+;") {
        // A reply from a not-yet-folded legacy member-keyed room redirects to its gid
        // (only when unambiguous) so it still threads into the real conversation.
        let chat = effective_send_guid(chat, &st().group_meta);
        let chat = chat.as_str();
        let meta = st().group_meta.get(chat).cloned();
        let id = send_identity(chat, meta.as_ref());
        let participants = if id.participant_addrs.is_empty() {
            participants_from_chat_guid(chat) // ultimate fallback: parse the guid
        } else {
            id.participant_addrs.iter().map(|a| to_handle(a)).collect()
        };
        ConversationData {
            participants,
            cv_name: id.cv_name,
            sender_guid: id.gid.or_else(|| Some(Uuid::new_v4().to_string())),
            after_guid: None,
        }
    } else {
        ConversationData {
            participants: participants_from_chat_guid(chat),
            cv_name: None,
            sender_guid: Some(Uuid::new_v4().to_string()),
            after_guid: None,
        }
    }
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

// ---- OpenBubbles migration --------------------------------------------------
// Reuse an existing OpenBubbles registration instead of re-logging-in. OB is also
// rustpush, so its persisted state maps 1:1 onto ours: we read OB's plists from a
// staging dir (root-copied by the Kotlin migrator) and write OUR files into the
// app's filesDir. Confirmed on-device: hw_info.plist{push,identity,os_config},
// id.plist=Vec<IDSUser>, keystore_s.plist=SoftwareKeystoreState.

/// A short description of a plist value's on-disk shape, for migration diagnostics.
fn ob_shape(v: &plist::Value) -> String {
    match v {
        plist::Value::Dictionary(d) => format!("dict{{{}}}", d.keys().cloned().collect::<Vec<_>>().join(",")),
        plist::Value::Array(a) => format!("array[{}]", a.len()),
        plist::Value::Data(b) => format!("data({}B)", b.len()),
        plist::Value::String(_) => "string".to_string(),
        plist::Value::Integer(_) => "integer".to_string(),
        plist::Value::Real(_) => "real".to_string(),
        plist::Value::Boolean(_) => "boolean".to_string(),
        _ => "other".to_string(),
    }
}

/// Deserialize one `hw_info.plist` field into `T`, tolerant of the two ways OpenBubbles
/// forks persist push/identity — inline (a `<dict>`) OR a nested serialized-plist
/// `<data>` blob. On a genuine rustpush serialization skew, report the field + its
/// on-disk shape so it's pinpointed instead of an opaque `UnexpectedEventType`.
fn ob_de_field<T: serde::de::DeserializeOwned>(d: &plist::Dictionary, key: &str) -> Result<T, String> {
    let v = d.get(key).ok_or_else(|| format!("hw_info.plist missing '{key}'"))?;
    // A nested serialized-plist blob: parse the bytes directly.
    if let plist::Value::Data(bytes) = v {
        return plist::from_bytes::<T>(bytes).map_err(|e| {
            format!("field '{key}' is a {}-byte data blob that didn't parse as its rustpush type: {e}", bytes.len())
        });
    }
    // Inline value: round-trip through a plist to deserialize into T.
    let mut buf = Vec::new();
    v.to_writer_xml(&mut buf).map_err(|e| format!("'{key}' reserialize: {e}"))?;
    plist::from_bytes::<T>(&buf).map_err(|e| {
        format!("field '{key}' (shape {}) is incompatible with our rustpush type: {e}", ob_shape(v))
    })
}

/// Recover OpenBubbles' `identity`. OB persists it via `IDSNGMIdentity::save(tag)` — an
/// AES-GCM-encrypted binary plist keyed by `ids:identity-storage-key:{tag}` in the
/// keystore (the caller must load that keystore BEFORE calling this). We recover `tag`
/// from the keystore file's key names and `restore()` (decrypt) it. Falls back to a
/// plain deserialize if OB ever stored it inline in our own dict format.
fn ob_restore_identity(hwd: &plist::Dictionary, keystore_plist: &Path) -> Result<IDSNGMIdentity, String> {
    let v = hwd.get("identity").ok_or_else(|| "hw_info.plist missing 'identity'".to_string())?;
    let blob = match v {
        plist::Value::Data(blob) => blob,
        // Already inline in our own dict format — deserialize directly.
        _ => return ob_de_field(hwd, "identity"),
    };
    // OB's identity blob is IDSNGMIdentity::save() output, decryptable with the keystore
    // key `ids:identity-storage-key:{tag}`. That only works if OB stored that key
    // UNENCRYPTED (our NoEncryptor format: a `bplist00` binary plist). OpenBubbles encrypts
    // its keystore, so the key isn't readable — and per the migration design we do NOT need
    // OB's keystore: only the login data (users/registration + push) transfers. So when the
    // key is unreadable, generate a FRESH device identity and let the transferred
    // registration + auth re-register against it (via hw.openbubbles.app).
    let tag = ob_identity_tag(keystore_plist);
    let key_is_plaintext = tag.as_deref()
        .and_then(|t| ob_keystore_key_format(keystore_plist, t))
        .map(|(_, _, is_plist)| is_plist)
        .unwrap_or(false);
    if let (Some(tag), true) = (tag.as_deref(), key_is_plaintext) {
        log::info!("import: decrypting OpenBubbles identity ({} B) with tag '{tag}'", blob.len());
        return IDSNGMIdentity::restore(blob, tag)
            .map_err(|e| format!("identity restore (decrypt, tag '{tag}') failed: {e:?}"));
    }
    log::warn!(
        "import: OpenBubbles identity is locked (encrypted keystore) — generating a FRESH device \
         identity; the transferred login (users + auth) re-registers against it."
    );
    IDSNGMIdentity::new().map_err(|e| format!("fresh identity generation failed: {e:?}"))
}

/// Report `(len, head_hex, starts_with_bplist00)` for the `ids:identity-storage-key:{tag}`
/// entry in OpenBubbles' keystore plist — used to tell an unencrypted key (our
/// NoEncryptor format, a `bplist00` binary plist) from an encrypted one.
fn ob_keystore_key_format(keystore_plist: &Path, tag: &str) -> Option<(usize, String, bool)> {
    let alias = format!("ids:identity-storage-key:{tag}");
    let v = plist::Value::from_file(keystore_plist).ok()?;
    let d = v.as_dictionary()?;
    ["keys", "secrets"].iter().find_map(|section| {
        match d.get(*section)?.as_dictionary()?.get(alias.as_str())? {
            plist::Value::Data(b) => {
                let head: String = b.iter().take(12).map(|x| format!("{x:02x}")).collect();
                Some((b.len(), head, b.starts_with(b"bplist00")))
            }
            _ => None,
        }
    })
}

/// The `{tag}` embedded in a `ids:identity-storage-key:{tag}` alias, scanned straight
/// from OpenBubbles' keystore plist (checking both key and secret sections, so it works
/// regardless of which section the AES key lives in).
fn ob_identity_tag(keystore_plist: &Path) -> Option<String> {
    const PREFIX: &str = "ids:identity-storage-key:";
    let v = plist::Value::from_file(keystore_plist).ok()?;
    let d = v.as_dictionary()?;
    ["keys", "secrets"].iter().find_map(|section| {
        d.get(*section)?.as_dictionary()?.keys()
            .find_map(|k| k.strip_prefix(PREFIX).map(str::to_string))
    })
}

/// Walk any plist value and collect iMessage handles (tel:/mailto:) — robust to
/// the exact IDSUser/registration nesting.
fn collect_handles(v: &plist::Value, acc: &mut Vec<String>) {
    match v {
        plist::Value::String(s) => {
            if (s.starts_with("tel:") || s.starts_with("mailto:")) && !acc.contains(s) {
                acc.push(s.clone());
            }
        }
        plist::Value::Array(a) => a.iter().for_each(|x| collect_handles(x, acc)),
        plist::Value::Dictionary(d) => d.values().for_each(|x| collect_handles(x, acc)),
        _ => {}
    }
}

fn read_gsa_username(obp: &Path) -> Option<String> {
    let v = plist::Value::from_file(obp.join("gsa.plist")).ok()?;
    v.as_dictionary()?.get("username")?.as_string().map(|s| s.to_string())
}

/// Repackage OB's state (in `ob`) into our files (`out`). Returns (handles, apple_id).
fn import_openbubbles(ob: &str, out: &str) -> Result<(Vec<String>, String), String> {
    let obp = Path::new(ob);
    let outp = Path::new(out);
    log::info!("import: reading OpenBubbles state from {ob}");
    // 0. Start from a CLEAN, EMPTY keystore and init the singleton FIRST (rustpush needs a
    //    keystore before any identity use). We deliberately DO NOT copy OpenBubbles'
    //    keystore_s.plist: its entries are AES-GCM ENCRYPTED with OB's device-bound key, so
    //    reading them back through our NoEncryptor hands ciphertext to plist parsing and
    //    PANICS (keystore/software.rs get_key, line 238). Per the migration design only the
    //    LOGIN DATA (users/registration + push) transfers; the device identity is
    //    regenerated fresh (below) and re-registers against hw.openbubbles.app, which
    //    repopulates THIS keystore with fresh plaintext keys. An empty keystore also makes a
    //    missing alias a graceful KeyNotFound error instead of a ciphertext panic. init is
    //    idempotent (OnceLock — a later nativeInit re-init is a harmless no-op).
    let ks_dst = outp.join("keystore.plist");
    // If the migrator staged a DECRYPTED keystore (plaintext NoEncryptor XML, produced
    // on-device by running OpenBubbles' OWN AndroidKeyStore key AS OpenBubbles' uid), adopt
    // it. Then `ob_restore_identity` finds `ids:identity-storage-key:{tag}` as plaintext and
    // decrypts OB's REAL device identity, the push keypair's `activation:{serial}` private key
    // resolves at connect, and the imported users keep their registration — so we RESTORE the
    // login exactly like OpenBubbles does, with NO re-registration. Absent a staged keystore we
    // fall back to the old behaviour (empty keystore → fresh identity → re-register on connect).
    let staged_ks = obp.join("keystore.plist");
    // Adopt ONLY a NON-EMPTY staged keystore. A 0-byte file means the on-device decrypt ran
    // but produced nothing (missing keystore_s.plist / no readable AndroidKeyStore alias for
    // OB's uid). Treating that as "adopted" made us copy an empty keystore, log a bogus
    // "restored OB identity", and skip the clean fresh-identity fallback. Size-gate it so
    // empty == not staged and we fall through to the fresh-identity/re-register path.
    let adopted_keystore = std::fs::metadata(&staged_ks).map(|m| m.len() > 0).unwrap_or(false);
    if adopted_keystore {
        std::fs::copy(&staged_ks, &ks_dst)
            .map_err(|e| format!("copy staged decrypted keystore.plist: {e}"))?;
        let sz = std::fs::metadata(&ks_dst).map(|m| m.len()).unwrap_or(0);
        log::info!("import: adopted staged DECRYPTED keystore ({sz} B) — restoring OB identity (no re-register)");
    } else {
        let _ = std::fs::remove_file(&ks_dst); // drop any stale / half-migrated keystore
        log::warn!("import: no decrypted keystore staged — starting empty (fresh identity, re-registers on connect)");
    }
    init_keystore_persisted(out);

    // 1. hw_info.plist → push (inline dict) + identity (encrypted blob → decrypt via
    //    IDSNGMIdentity::restore). Parse generically first (proven in stage_identity)
    //    and log the field shapes so any skew is visible.
    let hwv = plist::Value::from_file(obp.join("hw_info.plist"))
        .map_err(|e| format!("hw_info.plist parse: {e}"))?;
    let hwd = hwv.as_dictionary()
        .ok_or_else(|| format!("hw_info.plist is a {}, not a dictionary", ob_shape(&hwv)))?;
    log::info!("import: hw_info.plist keys=[{}]  push={}  identity={}  os_config={}",
        hwd.keys().cloned().collect::<Vec<_>>().join(", "),
        hwd.get("push").map(ob_shape).unwrap_or_else(|| "MISSING".to_string()),
        hwd.get("identity").map(ob_shape).unwrap_or_else(|| "MISSING".to_string()),
        hwd.get("os_config").map(ob_shape).unwrap_or_else(|| "MISSING".to_string()));
    // A hard `?` here is what dropped migrations to manual sign-in: when the OpenBubbles build
    // on the device bundled a rustpush that serialized the push state in a skewed format — e.g.
    // the keypair's private key as <data> where our rustpush wants a <string> — this returned
    // Serde("invalid value: byte array, expected a string") and aborted the ENTIRE login import.
    // The APNs token + keypair are re-established on connect regardless, so degrade to a FRESH
    // push state instead of failing. The migrated users + identity then re-register on connect
    // (the same fallback path taken when the keystore is missing), rather than forcing the user
    // back to a manual sign-in.
    let push: APSState = ob_de_field::<APSState>(hwd, "push").unwrap_or_else(|e| {
        log::warn!("import: push state from hw_info.plist is incompatible ({e}); starting FRESH push — migrated users/identity re-register on connect");
        APSState::default()
    });
    let identity: IDSNGMIdentity = ob_restore_identity(hwd, &ks_dst)?;
    log::info!("import: hw_info.plist processed (push ready, identity ready)");
    // 2. id.plist → Vec<IDSUser> (exact rustpush shape confirmed on device)
    let users: Vec<IDSUser> = plist::from_file(obp.join("id.plist"))
        .map_err(|e| format!("id.plist: {e}"))?;
    log::info!("import: id.plist → {} user(s)", users.len());
    // 3. write our config.plist (SavedState) — identity is re-serialized in OUR format
    //    (an inline dict, not re-encrypted), matching how nativeRegister persists it.
    let state = SavedState { push, users, identity };
    if !state.is_registered() {
        return Err("imported registration is empty (no users/registration)".into());
    }
    save_saved(out, &state);
    log::info!("import: wrote config.plist (registered={})", state.is_registered());

    // SMS forwarding survives the migration — seed it ON. We only reach here with a
    // REGISTERED OpenBubbles identity, and that device had Text Message Forwarding
    // enabled at Apple's side (that's how it sent/received green texts). We inherit the
    // SAME identity + registration, so forwarding is still on for us. But
    // `EnableSmsActivation` is a one-time APNs push the iPhone sends only when you TOGGLE
    // the setting — it already fired during OpenBubbles' lifetime and Apple will NOT
    // re-announce it to the migrated app. Without this seed, every migrated user starts
    // with sms_active=false and green sends are BLOCKED (decide_route) until they happen
    // to RECEIVE a green text (the inbound self-heal at from_raw) or manually toggle
    // forwarding off/on on the iPhone. That's the "green messages stopped sending after
    // migrating" regression. Seed true so sending works from the first launch; the iPhone
    // still turns it back OFF explicitly via EnableSmsActivation(false) if forwarding is
    // later disabled, and the inbound self-heal re-confirms it either way.
    SMS_ACTIVE.store(true, Ordering::SeqCst);
    save_sms_active(out, true);
    log::info!("import: seeded sms_active=true (migrated OpenBubbles identity had SMS forwarding)");
    // NB: os_config.plist (the MacOSConfigRemote device identity) is written SEPARATELY
    // and BEFORE this by `stage_identity`/nativeStageIdentity, so that a failure of the
    // login repackage here can fall back to a manual sign-in that still validates
    // through the NAC server. Nothing about os_config is required for this step.
    // 5. handles (walk id.plist) + apple id (gsa username, else first email handle)
    let mut handles = Vec::new();
    if let Ok(v) = plist::Value::from_file(obp.join("id.plist")) {
        collect_handles(&v, &mut handles);
    }
    let apple_id = read_gsa_username(obp).or_else(|| {
        handles.iter().find(|h| h.starts_with("mailto:"))
            .map(|h| h.trim_start_matches("mailto:").to_string())
    }).unwrap_or_default();
    log::info!(
        "import_openbubbles DONE: {} handle(s), apple_id={} — config.plist written ({})",
        handles.len(),
        if apple_id.is_empty() { "(none)" } else { apple_id.as_str() },
        if adopted_keystore {
            "RESTORED OB identity + keys from decrypted keystore — connects WITHOUT re-registration"
        } else {
            "fresh identity + empty keystore — re-registers on connect"
        },
    );
    Ok((handles, apple_id))
}

/// Stage ONLY the NAC device identity: extract `os_config` from OpenBubbles'
/// `hw_info.plist` and write it verbatim to `os_config.plist`. This is the hard
/// prerequisite for `MacOSConfigRemote` and runs BEFORE — and independently of —
/// the login import ([`import_openbubbles`]), so that even if the login repackage
/// fails the user can still sign in manually and validate through the NAC server.
/// (The raw `dumb` file is copied separately by the Kotlin migrator.)
fn stage_identity(ob: &str, out: &str) -> Result<(), String> {
    let obp = Path::new(ob);
    let outp = Path::new(out);
    let hwv = plist::Value::from_file(obp.join("hw_info.plist"))
        .map_err(|e| format!("hw_info.plist: {e}"))?;
    let oc = hwv.as_dictionary().and_then(|d| d.get("os_config"))
        .ok_or_else(|| "hw_info.plist has no os_config — cannot build the device identity".to_string())?;
    oc.to_file_xml(outp.join("os_config.plist"))
        .map_err(|e| format!("os_config.plist write: {e}"))?;
    log::info!("stage_identity: wrote os_config.plist (for MacOSConfigRemote)");
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeStageIdentity(
    mut env: JNIEnv,
    _class: JClass,
    ob_dir: JString,
    out_dir: JString,
) -> jstring {
    init_logger();
    let ob = jstr(&mut env, &ob_dir);
    let outd = jstr(&mut env, &out_dir);
    let json = match stage_identity(&ob, &outd) {
        Ok(()) => serde_json::json!({ "ok": true }).to_string(),
        Err(e) => {
            log::warn!("nativeStageIdentity failed: {e}");
            serde_json::json!({ "ok": false, "error": e }).to_string()
        }
    };
    out(&mut env, json)
}

/// Convert OpenBubbles' anisette machine-identity `state.plist` into the format
/// omnisette's `RemoteAnisetteProviderV3` reads, so the migrated login keeps the
/// already-provisioned ADI and never re-provisions anisette.
///
/// OpenBubbles stores the provisioned ADI SPLIT into a `provisioned` sub-dict:
///   { keychain_identifier:<data>,
///     provisioned:{ client_secret:<data>, mid:<data>, metadata:<data>,
///                   rinfo:<string>, flavor:<string "Mac"> } }
/// omnisette wants the SINGLE opaque blob the anisette server issued (base64-decoded
/// into `adi_pb`), which the server re-parses on every headers call:
///   { keychain_identifier:<data>,
///     adi_pb:<data = {"version":1,"flavor":1,"client_secret":"…","mid":"…",
///                     "metadata":"…","rinfo":"…"} > }
/// The sensitive values (client_secret/mid/metadata) are carried VERBATIM — their raw
/// bytes re-base64'd into the JSON strings — so only the JSON wrapper is rebuilt; the
/// `keychain_identifier` the ADI is bound to is preserved. A file already in the new
/// format (it has `adi_pb`) is re-emitted unchanged.
fn convert_anisette(ob_state: &str, out_state: &str) -> Result<usize, String> {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;

    let val = plist::Value::from_file(ob_state)
        .map_err(|e| format!("read anisette state.plist ({ob_state}): {e}"))?;
    let dict = val.as_dictionary()
        .ok_or_else(|| "anisette state.plist is not a dictionary".to_string())?;

    // The 16-byte machine id the adi_pb is bound to — must be carried as-is.
    let keychain = dict.get("keychain_identifier").and_then(|v| v.as_data())
        .ok_or_else(|| "anisette state.plist has no keychain_identifier".to_string())?
        .to_vec();
    if keychain.len() != 16 {
        return Err(format!("keychain_identifier is {} bytes, expected 16", keychain.len()));
    }

    // Already new-format (a single adi_pb blob)? Re-emit unchanged.
    if let Some(adi) = dict.get("adi_pb").and_then(|v| v.as_data()) {
        write_anisette(out_state, &keychain, adi)?;
        return Ok(adi.len());
    }

    // Old OpenBubbles split format: rebuild the adi_pb JSON from the sub-dict.
    let prov = dict.get("provisioned").and_then(|v| v.as_dictionary())
        .ok_or_else(|| "anisette state has neither adi_pb nor a 'provisioned' dict — \
                        OpenBubbles never finished provisioning".to_string())?;
    // A <data> field → base64 string for the JSON (tolerate an already-encoded string).
    let field_b64 = |k: &str| -> Result<String, String> {
        match prov.get(k) {
            Some(plist::Value::Data(d)) => Ok(b64.encode(d)),
            Some(plist::Value::String(s)) => Ok(s.clone()),
            _ => Err(format!("provisioned.{k} is missing or not <data>")),
        }
    };
    let client_secret = field_b64("client_secret")?;
    let mid = field_b64("mid")?;
    let metadata = field_b64("metadata")?;
    let rinfo = prov.get("rinfo")
        .and_then(|v| v.as_string().map(|s| s.to_string())
            .or_else(|| v.as_signed_integer().map(|i| i.to_string())))
        .ok_or_else(|| "provisioned.rinfo is missing".to_string())?;
    let flavor = match prov.get("flavor").and_then(|v| v.as_string()) {
        Some("Mac") | None => 1u32,
        Some(other) => { log::warn!("convert_anisette: unmapped flavor '{other}', using 1 (Mac)"); 1 }
    };

    // Struct (not a map) so serde_json emits the fields in the server's issued order.
    #[derive(serde::Serialize)]
    struct AdiPb {
        version: u32,
        flavor: u32,
        client_secret: String,
        mid: String,
        metadata: String,
        rinfo: String,
    }
    let adi = serde_json::to_vec(&AdiPb {
        version: 1, flavor, client_secret, mid, metadata, rinfo,
    }).map_err(|e| format!("serialize adi_pb json: {e}"))?;
    write_anisette(out_state, &keychain, &adi)?;
    Ok(adi.len())
}

/// Write an omnisette-format anisette `state.plist` (keychain_identifier + adi_pb),
/// creating the parent `anisette/` dir if needed.
fn adi_pb_shape(adi: &[u8]) -> String {
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD;
    match serde_json::from_slice::<serde_json::Value>(adi) {
        Ok(serde_json::Value::Object(map)) => {
            let mut keys: Vec<&String> = map.keys().collect();
            keys.sort();
            let parts: Vec<String> = keys.iter().map(|k| {
                let v = &map[k.as_str()];
                match k.as_str() {
                    "version" | "flavor" | "rinfo" => format!("{k}={v}"),
                    _ => match v.as_str() {
                        Some(s) => format!("{k}=<b64 {} chars / {} bytes>", s.len(), b64.decode(s).map(|d| d.len()).unwrap_or(0)),
                        None => format!("{k}=<non-string>"),
                    },
                }
            }).collect();
            format!("json_ok=true total={}B keys=[{}] {}", adi.len(),
                keys.iter().map(|k| k.as_str()).collect::<Vec<_>>().join(","), parts.join(" "))
        }
        Ok(_) => format!("json_ok=true(non-object) total={}B", adi.len()),
        Err(e) => format!("json_ok=false total={}B first_byte=0x{:02x} ({e})", adi.len(), adi.first().copied().unwrap_or(0)),
    }
}

fn write_anisette(out_state: &str, keychain: &[u8], adi_pb: &[u8]) -> Result<(), String> {
    if let Some(parent) = Path::new(out_state).parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("create {parent:?}: {e}"))?;
    }
    let mut d = plist::Dictionary::new();
    d.insert("keychain_identifier".into(), plist::Value::Data(keychain.to_vec()));
    d.insert("adi_pb".into(), plist::Value::Data(adi_pb.to_vec()));
    plist::Value::Dictionary(d).to_file_xml(out_state)
        .map_err(|e| format!("write anisette state.plist ({out_state}): {e}"))?;
    log::info!("ADI_STRUCT [source=migrated-adi] keychain_id={}B {}", keychain.len(), adi_pb_shape(adi_pb));
    log::info!("convert_anisette: wrote {out_state} (adi_pb {} bytes)", adi_pb.len());
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeConvertAnisette(
    mut env: JNIEnv,
    _class: JClass,
    ob_state: JString,
    out_state: JString,
) -> jstring {
    init_logger();
    let obs = jstr(&mut env, &ob_state);
    let outs = jstr(&mut env, &out_state);
    let json = match convert_anisette(&obs, &outs) {
        Ok(n) => serde_json::json!({ "ok": true, "adi_pb_len": n }).to_string(),
        Err(e) => {
            log::warn!("nativeConvertAnisette failed: {e}");
            serde_json::json!({ "ok": false, "error": e }).to_string()
        }
    };
    out(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeImportOpenBubbles(
    mut env: JNIEnv,
    _class: JClass,
    ob_dir: JString,
    out_dir: JString,
) -> jstring {
    init_logger();
    let ob = jstr(&mut env, &ob_dir);
    let outd = jstr(&mut env, &out_dir);
    // Run the import on a worker thread bounded to 30s, catching panics. A keystore
    // decrypt mismatch `expect(...)`s (panics), and a panic's backtrace capture can
    // stall on Android — so without this the whole migration could freeze. On a
    // timeout OR panic we return an error and the migrator falls back to manual sign-in.
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| import_openbubbles(&ob, &outd)));
        let _ = tx.send(r);
    });
    let json = match rx.recv_timeout(std::time::Duration::from_secs(30)) {
        Ok(Ok(Ok((handles, apple_id)))) =>
            serde_json::json!({ "ok": true, "handles": handles, "appleId": apple_id }).to_string(),
        Ok(Ok(Err(e))) => {
            log::warn!("nativeImportOpenBubbles failed: {e}");
            serde_json::json!({ "ok": false, "error": e }).to_string()
        }
        Ok(Err(_panic)) => {
            log::error!("nativeImportOpenBubbles PANICKED (see 'RUST PANIC' above for the cause)");
            serde_json::json!({ "ok": false, "error": "import crashed (panic) — see logcat (RUST PANIC)" }).to_string()
        }
        Err(_timeout) => {
            log::error!("nativeImportOpenBubbles timed out after 30s (import stalled)");
            serde_json::json!({ "ok": false, "error": "import timed out after 30s" }).to_string()
        }
    };
    out(&mut env, json)
}

/// Load the migrated native identity, if an OpenBubbles migration left one.
/// Requires BOTH `os_config.plist` (the serialized `MacOSConfigRemote`, written by
/// [`import_openbubbles`]) and `dumb` (the raw hardware-config body the NAC server
/// needs, copied over by the Kotlin migrator). Sets `SMARTTXT_DUMB_PATH` so
/// `MacOSConfigRemote::generate_validation_data` reads that exact dumb file.
/// Returns `None` — which makes `nativeInit` fail, since there is NO relay
/// fallback — when either file is missing or the plist doesn't parse.
fn load_remote_config(dir: &str) -> Option<MacOSConfigRemote> {
    use base64::Engine;
    let os_config = Path::new(dir).join("os_config.plist");
    let dumb = Path::new(dir).join("dumb");
    // The dumb is now the ONLY required file: the whole identity (hardware + software) is
    // rebuilt from it, so os_config.plist is optional — kept only for a verification compare.
    let raw = match std::fs::read(&dumb) {
        Ok(r) if !r.is_empty() => r,
        Ok(_) => {
            log::error!(
                "nativeInit: the dumb at {} is EMPTY (0 B) — re-run the OpenBubbles transfer",
                dumb.display()
            );
            return None;
        }
        Err(e) => {
            // Say WHICH failure. The old message claimed "missing/empty" for every error,
            // including EACCES — so a dumb that was dropped in with `su cp` (root-owned,
            // wrong SELinux label) shows up fine in `ls`/`cat` as root but is unreadable by
            // the app's uid, and the log sent people hunting for a file sitting right there.
            // Report the file's owner: uid 0 is the tell.
            use std::os::unix::fs::MetadataExt;
            let (exists, size, uid, gid, mode) = match std::fs::metadata(&dumb) {
                Ok(m) => (true, m.len(), m.uid(), m.gid(), m.mode() & 0o777),
                Err(_) => (false, 0, 0, 0, 0),
            };
            log::error!(
                "nativeInit: CANNOT READ the dumb at {} — {e} (kind={:?}). \
                 exists={exists} size={size}B owner={uid}:{gid} mode={mode:o}. \
                 If it exists but won't read, it was placed there by ROOT (uid 0) rather than \
                 by the app: chown it to the app's uid and restorecon it, or just re-run the \
                 OpenBubbles transfer, which copies it AS the app.",
                dumb.display(),
                e.kind(),
            );
            return None;
        }
    };
    // OpenBubbles stores the dumb base64-encoded; decode (fall back to raw if not base64).
    let body = match base64::engine::general_purpose::STANDARD.decode(String::from_utf8_lossy(&raw).trim()) {
        Ok(d) if !d.is_empty() => d,
        _ => raw.clone(),
    };
    match MacOSConfigRemote::from_dumb_body(&body) {
        Ok(mut cfg) => {
            std::env::set_var("SMARTTXT_DUMB_PATH", &dumb);
            // If os_config.plist is also present (older migration), verify the dumb-derived
            // config against it field-by-field. This does NOT change what we use — the
            // dumb-derived config is authoritative and self-consistent with the NAC
            // validation data (both come from the one dumb).
            //
            // Read it now, but DEFER the compare until after the UDID is pinned below.
            // `from_dumb_body` mints a fresh random UDID on every call, so comparing
            // here would report `udid MISMATCH` on every single launch even when the
            // persisted value is the one actually used — a permanently misleading log.
            let mut persisted: Option<MacOSConfigRemote> = None;
            if os_config.exists() {
                match plist::from_file::<_, MacOSConfigRemote>(&os_config) {
                    Ok(old) => persisted = Some(old),
                    Err(e) => log::warn!("config-verify: os_config.plist present but unreadable ({e}) — skipping compare"),
                }
            }
            let persisted_udid = persisted.as_ref().and_then(|p| p.udid.clone());
            // The client UDID is NOT carried in the dumb — it is this install's own
            // identifier, exactly like OpenBubbles' generate_udid(). `from_dumb_body`
            // mints a fresh one every call, so it MUST be pinned here: a UDID that
            // changed on every launch would look like a new device to Apple each
            // sign-in. Reuse the persisted value when it already has the OpenBubbles
            // shape (64 hex); otherwise mint one and persist it, which migrates the
            // old platform-UUID-derived value exactly once.
            match persisted_udid {
                Some(u) if macos_remote::is_openbubbles_shaped_udid(&u) => {
                    cfg.udid = Some(u);
                    log::info!("UDID_SHAPE: reusing persisted client UDID (64-hex, OpenBubbles-shaped) ✅");
                }
                other => {
                    let had = other.as_deref().unwrap_or("<none>");
                    match plist::to_file_xml(&os_config, &cfg) {
                        Ok(()) => log::info!(
                            "UDID_SHAPE: minted a NEW OpenBubbles-shaped client UDID (64 hex) and persisted it \
                             to os_config.plist — previous value was {} chars ({}). X-Client-UDID now matches \
                             OpenBubbles' shape.",
                            had.len(),
                            if had == "<none>" { "absent" } else { "legacy platform-UUID-derived" },
                        ),
                        Err(e) => log::error!(
                            "UDID_SHAPE: could NOT persist os_config.plist ({e}) — the client UDID would change \
                             on every launch, which looks like a new device to Apple each time. Fix storage/perms."
                        ),
                    }
                }
            }
            // NOW compare — `cfg` is final, so every field shown is the one that will
            // actually be presented to Apple.
            if let Some(old) = &persisted {
                compare_configs(&cfg, old);
            }
            // One greppable identity line for exported bundles. ROM is the field-11
            // value (NOT io_mac_address, field 2) — the whole point of the prost
            // rewrite in rustpush's macos_remote.rs.
            let rom_hex = cfg.get_gsa_hardware_headers().get("X-Apple-I-ROM").cloned().unwrap_or_default();
            // Self-check the field mapping that used to be wrong. `rom` is dumb field
            // 11; `io_mac_address` is field 2. An earlier hand-rolled protobuf reader
            // read field 2 and called it ROM, so every GSA request carried the MAC
            // address while the NAC-signed validation data attested to the real ROM.
            // Both values are re-decoded here with the real schema so the log PROVES
            // which one the header carries instead of just printing it.
            match cfg.rom_and_io_mac_hex() {
                Some((rom_f11, mac_f2)) if rom_hex == rom_f11 && rom_f11 != mac_f2 => log::info!(
                    "ROM_CHECK ✅ X-Apple-I-ROM={rom_hex} = dumb field 11 (rom). Field 2 \
                     (io_mac_address)={mac_f2} is a DIFFERENT value and is correctly NOT used. \
                     This matches OpenBubbles."
                ),
                Some((rom_f11, mac_f2)) if rom_hex == rom_f11 => log::info!(
                    "ROM_CHECK ✅ X-Apple-I-ROM={rom_hex} = dumb field 11 (rom). Field 2 is \
                     identical ({mac_f2}) on this device, so the old mapping was inert here — \
                     the header is right either way."
                ),
                Some((rom_f11, mac_f2)) => log::error!(
                    "ROM_CHECK ❌ X-Apple-I-ROM={rom_hex} does NOT match dumb field 11 \
                     ({rom_f11}); field 2 (io_mac_address) is {mac_f2}. The rom/io_mac mapping \
                     has REGRESSED — X-Apple-I-ROM will disagree with the NAC-signed \
                     validation data on every login."
                ),
                None => log::warn!(
                    "ROM_CHECK: couldn't re-decode the dumb for the rom/io_mac comparison \
                     (hw_config missing or too short) — mapping unverified this run."
                ),
            }
            log::info!(
                "IDENTITY: product={} build={} macos={} proto={} rom={} (dumb field 11) udid_len={}",
                cfg.inner.product_name, cfg.inner.os_build_num, cfg.version,
                cfg.protocol_version, rom_hex,
                cfg.udid.as_deref().unwrap_or("").len(),
            );
            log::info!("nativeInit: identity built FROM THE DUMB ({} B) — os_config.plist not required", body.len());
            Some(cfg)
        }
        Err(e) => {
            log::error!("nativeInit: couldn't build config from the dumb ({e})");
            // Fall back to os_config.plist so an existing install still starts if the dumb
            // parse ever regresses.
            match plist::from_file::<_, MacOSConfigRemote>(&os_config) {
                Ok(cfg) => {
                    std::env::set_var("SMARTTXT_DUMB_PATH", &dumb);
                    log::warn!("nativeInit: FELL BACK to os_config.plist (dumb parse failed)");
                    Some(cfg)
                }
                Err(e2) => {
                    log::error!("nativeInit: no usable identity — dumb parse failed AND os_config.plist unreadable ({e2})");
                    None
                }
            }
        }
    }
}

/// Log a field-by-field comparison of the dumb-derived config vs os_config.plist, so the
/// dumb parse can be confirmed correct before os_config.plist is dropped for good.
fn compare_configs(from_dumb: &MacOSConfigRemote, from_plist: &MacOSConfigRemote) {
    let cmp = |name: &str, a: String, b: String| {
        if a == b {
            log::info!("config-verify: {name} MATCH ({a})");
        } else {
            log::warn!("config-verify: {name} MISMATCH — dumb={a:?} os_config={b:?}");
        }
    };
    let rd: Vec<u8> = from_dumb.inner.rom.clone().into();
    let rp: Vec<u8> = from_plist.inner.rom.clone().into();
    cmp("product_name", from_dumb.inner.product_name.clone(), from_plist.inner.product_name.clone());
    cmp("serial", from_dumb.inner.platform_serial_number.clone(), from_plist.inner.platform_serial_number.clone());
    cmp("build", from_dumb.inner.os_build_num.clone(), from_plist.inner.os_build_num.clone());
    cmp("mlb", from_dumb.inner.mlb.clone(), from_plist.inner.mlb.clone());
    cmp("rom", dbg_hex(&rd), dbg_hex(&rp));
    cmp("version", from_dumb.version.clone(), from_plist.version.clone());
    cmp("protocol_version", from_dumb.protocol_version.to_string(), from_plist.protocol_version.to_string());
    cmp("device_id", from_dumb.device_id.clone(), from_plist.device_id.clone());
    cmp("icloud_ua", from_dumb.icloud_ua.clone(), from_plist.icloud_ua.clone());
    cmp("aoskit_version", from_dumb.aoskit_version.clone(), from_plist.aoskit_version.clone());
    cmp("udid", from_dumb.udid.clone().unwrap_or_default(), from_plist.udid.clone().unwrap_or_default());
}

// =============================================================================
// JNI: lifecycle
// =============================================================================

/// Lowercase hex of some bytes (diagnostic only).
fn dbg_hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Walk a protobuf wire-format buffer and log each top-level field: number, wire type,
/// and a value preview (UTF-8 string when printable, else hex). DIAGNOSTIC ONLY — used
/// to reveal the dumb file's `HwInfo` schema (there's no `.proto` for it in-tree) so its
/// hardware fields (serial / model / build / mlb / rom) can later be mapped to
/// `HardwareConfig` with confidence instead of guessing field numbers.
fn dbg_log_protobuf(tag: &str, mut buf: &[u8]) {
    fn read_varint(buf: &mut &[u8]) -> Option<u64> {
        let (mut result, mut shift) = (0u64, 0u32);
        loop {
            let (&byte, rest) = buf.split_first()?;
            *buf = rest;
            result |= u64::from(byte & 0x7f) << shift;
            if byte & 0x80 == 0 {
                return Some(result);
            }
            shift += 7;
            if shift >= 64 {
                return None;
            }
        }
    }
    let mut count = 0u32;
    while !buf.is_empty() {
        let Some(key) = read_varint(&mut buf) else { break };
        let (field, wire) = (key >> 3, key & 7);
        match wire {
            0 => match read_varint(&mut buf) {
                Some(v) => log::info!("{tag}: #{field} varint = {v}"),
                None => break,
            },
            1 => {
                if buf.len() < 8 {
                    break;
                }
                let (b, rest) = buf.split_at(8);
                buf = rest;
                log::info!("{tag}: #{field} i64 = 0x{}", dbg_hex(b));
            }
            2 => {
                let Some(len) = read_varint(&mut buf) else { break };
                let len = len as usize;
                if buf.len() < len {
                    break;
                }
                let (val, rest) = buf.split_at(len);
                buf = rest;
                match std::str::from_utf8(val) {
                    Ok(s) if !s.is_empty() && s.chars().all(|c| !c.is_control()) => {
                        log::info!("{tag}: #{field} str[{len}] = {s:?}")
                    }
                    _ => log::info!("{tag}: #{field} bytes[{len}] = {}", dbg_hex(val)),
                }
            }
            5 => {
                if buf.len() < 4 {
                    break;
                }
                let (b, rest) = buf.split_at(4);
                buf = rest;
                log::info!("{tag}: #{field} i32 = 0x{}", dbg_hex(b));
            }
            _ => {
                log::warn!("{tag}: #{field} unknown wire type {wire} — stopping");
                break;
            }
        }
        count += 1;
        if count > 200 {
            log::warn!("{tag}: >200 fields — stopping");
            break;
        }
    }
    log::info!("{tag}: decoded {count} top-level field(s)");
}

/// Diagnostic: read `<dir>/dumb`, base64-decode it (OpenBubbles stores it base64), skip
/// the 5-byte OABS header, and log every protobuf field of the `HwInfo` body. Purely
/// observational — nothing depends on the result yet; it exists so the dumb's fields can
/// be identified before deriving `HardwareConfig` from it (and dropping os_config.plist).
fn dbg_log_dumb(dir: &str) {
    use base64::Engine;
    let path = Path::new(dir).join("dumb");
    let raw = match std::fs::read(&path) {
        Ok(r) => r,
        Err(e) => {
            log::warn!("dumb-decode: can't read {}: {e}", path.display());
            return;
        }
    };
    let txt = String::from_utf8_lossy(&raw);
    let decoded = match base64::engine::general_purpose::STANDARD.decode(txt.trim()) {
        Ok(d) if !d.is_empty() => d,
        _ => raw.clone(),
    };
    log::info!("dumb-decode: {} raw byte(s) → {} decoded byte(s)", raw.len(), decoded.len());
    if decoded.len() <= 5 {
        log::warn!("dumb-decode: only {} decoded byte(s) — too short for a 5-byte header + HwInfo", decoded.len());
        return;
    }
    log::info!("dumb-decode: 5-byte header = 0x{}", dbg_hex(&decoded[..5]));
    dbg_log_protobuf("dumb-hwinfo", &decoded[5..]);
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    relay_host: JString,
    relay_code: JString,
) -> jboolean {
    init_logger();
    // ANISETTE-URL-CHECK: log the anisette v3 server this .so was compiled with, at
    // INFO so it shows in `adb logcat -s smarttxt_ffi` on every launch (no sign-in needed).
    log::info!("anisette: configured server = {}", omnisette::DEFAULT_ANISETTE_URL_V3);
    // The rustls stack (reqwest) needs a process crypto provider; ring is bundled.
    let _ = rustls::crypto::ring::default_provider().install_default();

    let dir = jstr(&mut env, &files_dir);
    // relay_host / relay_code are accepted only so the existing JNI signature keeps
    // working — they are IGNORED. The hardware relay has been removed: validation
    // goes EXCLUSIVELY through MacOSConfigRemote (the NAC server). There is no
    // relay fallback, ever.
    let _ = (&relay_host, &relay_code);

    // Diagnostic: dump the dumb file's HwInfo protobuf fields to logcat, so its hardware
    // identity (serial/model/build/mlb/rom) can be mapped and os_config.plist eventually
    // dropped. Observational only — nothing here consumes the result yet.
    dbg_log_dumb(&dir);

    init_keystore_persisted(&dir);

    // The one and only OSConfig is MacOSConfigRemote — the migrated OpenBubbles Mac
    // identity (os_config.plist) with validation offloaded to the NAC server, driven
    // by the device `dumb` file. If the identity OR the dumb is missing we FAIL hard
    // (never the relay). The OpenBubbles transfer must run first.
    let cfg = match load_remote_config(&dir) {
        Some(cfg) => cfg,
        None => {
            log::error!(
                "nativeInit: REFUSING to start — the migrated identity (os_config.plist) and/or \
                 the dumb file are missing from {dir}. There is NO relay fallback; run the \
                 OpenBubbles transfer so both files are present, then relaunch."
            );
            return JNI_FALSE;
        }
    };
    log::info!("nativeInit: OSConfig = MacOSConfigRemote (migrated identity → NAC {})",
        macos_remote::NAC_BASE_URL);

    let mut s = st();
    s.files_dir = dir.clone();
    s.thread_handles = load_thread_handles(&dir);
    // Group identities (gid → members + name), plus the members→gid reverse index
    // rebuilt from them, so replies to existing groups thread correctly after a restart.
    s.group_meta = load_group_meta(&dir);
    s.group_by_members = s
        .group_meta
        .iter()
        .map(|(guid, m)| (members_csv(&m.participants), guid.clone()))
        .filter(|(k, _)| !k.is_empty())
        .collect();
    // Attachment references, so "tap to download" still works for a message that
    // arrived in an earlier session instead of failing with an empty stash.
    let stashed = load_attachments(&dir);
    s.attachment_order = stashed.iter().map(|i| i.key.clone()).collect();
    s.attachments = stashed.into_iter().map(|i| (i.key, i.att)).collect();
    log::info!(
        "nativeInit: restored {} attachment ref(s)",
        s.attachments.len()
    );
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
    // Resume diagnostics. A MIGRATED session carries OpenBubbles' push keypair + user auth
    // keys as keystore-ALIAS references whose private halves lived in OB's un-transferable
    // keystore. If push.keypair shows present here but the keystore is empty, do_connect
    // SKIPS activate() and then fails signing with KeyNotFound — check the paired
    // "keystore get_key: alias '…' NOT FOUND — keystore holds 0 key(s)" line for the alias.
    log::info!(
        "nativeConnect: resume state — {} user(s), identity={}, push.keypair={}, push.token={}",
        resume_users.len(),
        resume_identity.is_some(),
        saved_push.as_ref().map(|p| p.keypair.is_some()).unwrap_or(false),
        saved_push.as_ref().map(|p| p.token.is_some()).unwrap_or(false),
    );

    // ONE attempt, deliberately. `APSConnectionResource::new` always returns a live
    // `ResourceManager` (rustpush aps.rs) wired with `max_times: usize::MAX` and a
    // 30s-capped exponential backoff — even when this first attempt failed, the
    // manager is ALREADY retrying in the background, and it also runs its own
    // keepalive and self-healing reconnect. Looping here and discarding the failed
    // connection therefore left an abandoned manager reconnecting with the SAME push
    // token while we opened another one — the exact "two sockets on one push token
    // makes Apple drop both" hazard noted above. OpenBubbles calls this once too
    // (`setup_push` in its api.rs) and trusts the resource. Do the same.
    let (connection, first_err) =
        rt().block_on(async move { APSConnectionResource::new(os_config.clone(), saved_push.clone()).await });
    if let Some(e) = first_err {
        // NOT fatal: the resource retries on its own from here.
        log::warn!(
            "nativeConnect: first APNs attempt failed ({e:?}) — the rustpush resource \
             manager is retrying in the background with backoff; not opening a second socket."
        );
    }
    {
        let mut s = st();
        s.connection = Some(connection);
        s.connected = true;
    }
    // If we resumed a registration, build the client + receive loop now. Panic-guarded:
    // a keystore/identity panic here must not unwind across JNI (hang) — we stay connected
    // and just log it; the UI can re-drive registration.
    if !resume_users.is_empty() {
        if let Some(identity) = resume_identity {
            if let Err(e) = build_client_guarded(resume_users, identity) {
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

/// Is the iMessage CLIENT actually built — i.e. can we send and are we receiving?
///
/// Not the same question as `nativeIsConnected`, and the difference is the whole
/// bug: `nativeConnect` sets `connected = true` as soon as the APNs socket is up,
/// BEFORE `build_client_guarded` runs. If that build fails (or was never reached
/// because APNs failed at boot) the app sits with `connected == true` and
/// `client == None` — every send hits the "not connected" guard and no receive loop
/// exists — while `nativeIsConnected` cheerfully reports healthy. That state
/// survived 24h+ in the field on two handsets.
///
/// `nativeIsRegistered` doesn't answer it either: it ORs in saved users, so it's
/// true from persisted state alone.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeHasClient(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if st().client.is_some() { JNI_TRUE } else { JNI_FALSE }
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

/// Full LOGOUT: wipe every trace of the current login so the NEXT sign-in is fresh —
/// no OpenBubbles/migrated resume. Resets the in-memory [`AppState`] to default
/// (keeping ONLY the device identity `os_config` + `files_dir`) and deletes the
/// persisted login files (config.plist, keystore.plist, creds.json, id_cache.plist,
/// anisette/). The device identity (`dumb` + `os_config.plist`) is intentionally KEPT
/// so a fresh registration still validates through the NAC server (no relay). Called on
/// explicit logout AND at the start of a fresh sign-in, so a stale/dangling session
/// (e.g. OpenBubbles' push keypair whose private half lived in its un-transferable
/// keystore) can never block `nativeConnect` with KeyNotFound.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeLogout(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    let dir = {
        let mut s = st();
        let files_dir = s.files_dir.clone();
        let os_config = s.os_config.clone();
        *s = AppState::default(); // drop connection/client/users/identity/account/creds/handles/…
        s.files_dir = files_dir.clone();
        s.os_config = os_config; // keep the device identity in memory
        files_dir
    };
    // Delete only LOGIN-level files. Do NOT touch anisette/: the anisette machine identity
    // (adi_pb + keychain_identifier) is DEVICE-level, not tied to the Apple ID login. Wiping
    // it forces a brand-new provisioning round-trip on every sign-in — which both wastes the
    // provisioning server (hw.openbubbles.app started returning HTTP 400 after the repeated
    // re-provisions) and makes login fail whenever provisioning has any hiccup. Keeping it
    // means a re-login reuses the already-provisioned identity and SKIPS provisioning
    // entirely (RemoteAnisetteProviderV3 only provisions when !is_provisioned()).
    for f in ["config.plist", "keystore.plist", "creds.json", "id_cache.plist"] {
        match std::fs::remove_file(Path::new(&dir).join(f)) {
            Ok(()) => log::info!("nativeLogout: deleted {f}"),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => log::warn!("nativeLogout: couldn't delete {f}: {e}"),
        }
    }
    log::info!("nativeLogout: login state cleared (device identity dumb + os_config + anisette KEPT for fresh sign-in)");
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
    log::info!("nativeAuthenticate: begin GSA login (fetches anisette; first run provisions over the LAN)");

    // Clone for the async closure; the originals are stored in state below.
    let apple_c = apple.clone();
    let pw_c = pw_hash.clone();
    let result = block_on_timeout(90, "Login timed out — the anisette/Apple server didn't respond", async move {
        let gsa = os_config.get_gsa_config(&*connection.state.read().await, false);
        let anisette = default_provider(gsa.clone(), Path::new(&dir).join("anisette"));
        let mut account = rustpush::AppleAccount::new_with_anisette(gsa, anisette)
            .map_err(|e| format!("new_with_anisette: {e}"))?;
        log::info!("nativeAuthenticate: calling login_email_pass… (anisette + GSA round-trip)");
        let state = account
            .login_email_pass(&apple_c, &pw_c)
            .await
            // Display, not Debug: icloud_auth::Error::AuthSrpWithMessage is
            // `#[error("{1} ({0})")]`, so `{e}` yields Apple's own message + code
            // (e.g. "Your Apple ID or password was entered incorrectly. (-20101)").
            // `{e:?}` would collapse that to `AuthSrpWithMessage(-20101, "…")`.
            .map_err(|e| format!("login_email_pass: {e}"))?;
        log::info!("login_email_pass → {state:?}");

        // Returns (status_json, sms_body, fsa_challenge_json).
        // status = "logged_in" | "needs_2fa" | "needs_fsa".
        // For an FSA (security-key) challenge we carry the challenge fields out as
        // JSON so the Kotlin layer can relay them to the companion phone.
        let mut fsa: Option<serde_json::Value> = None;
        let (status, sms) = match state {
            LoginState::LoggedIn => ("logged_in", None),
            LoginState::Needs2FAVerification => ("needs_2fa", None),
            LoginState::NeedsDevice2FA => {
                account.send_2fa_to_devices().await.map_err(|e| format!("send_2fa_to_devices: {e}"))?;
                ("needs_2fa", None)
            }
            LoginState::NeedsSMS2FA => {
                // get_auth_extras fetches the trusted-phone options and, on a 201,
                // has already dispatched the SMS — surfaced via extras.new_state.
                // It can also surface a security-key (FSA) challenge instead.
                let extras = account.get_auth_extras().await.map_err(|e| format!("get_auth_extras: {e}"))?;
                match extras.new_state {
                    // A security-key challenge — hand the fields up to Kotlin to
                    // relay to the companion; no SMS body in this branch.
                    Some(LoginState::NeedsFSAVerification(challenge)) => {
                        fsa = Some(serde_json::json!({
                            "challenge": challenge.challenge,
                            "keyHandles": challenge.key_handles,
                            "rpId": challenge.rp_id,
                            "allowedCredentials": challenge.allowed_credentials,
                        }));
                        ("needs_fsa", None)
                    }
                    // Already sent by get_auth_extras; reuse the body it built.
                    Some(LoginState::NeedsSMS2FAVerification(body)) => ("needs_2fa", Some(body)),
                    // Not sent yet — dispatch it ourselves against the first trusted number.
                    _ => {
                        let sms = match account.send_sms_2fa_to_devices(1).await.map_err(|e| format!("send_sms_2fa: {e}"))? {
                            LoginState::NeedsSMS2FAVerification(body) => Some(body),
                            other => {
                                log::warn!("send_sms_2fa unexpected: {other:?}");
                                None
                            }
                        };
                        ("needs_2fa", sms)
                    }
                }
            }
            LoginState::NeedsSMS2FAVerification(body) => ("needs_2fa", Some(body)),
            // Apple can also drive the FSA (security-key) challenge directly out of
            // login_email_pass; surface it the same way as the get_auth_extras path.
            LoginState::NeedsFSAVerification(challenge) => {
                fsa = Some(serde_json::json!({
                    "challenge": challenge.challenge,
                    "keyHandles": challenge.key_handles,
                    "rpId": challenge.rp_id,
                    "allowedCredentials": challenge.allowed_credentials,
                }));
                ("needs_fsa", None)
            }
            LoginState::NeedsExtraStep(s) => {
                if account.get_pet().is_some() { ("logged_in", None) } else {
                    return Err(format!("login needs extra step: {s}"));
                }
            }
            LoginState::NeedsLogin => return Err("bad credentials".to_string()),
        };
        Ok::<_, String>((account, status, sms, fsa))
    });

    match result {
        Ok((account, status, sms, fsa)) => {
            let mut s = st();
            s.apple_id = apple;
            s.pw_hash = pw_hash;
            s.sms_2fa_body = sms;
            s.account = Some(account);
            // Base response is {"status": ...}; on an FSA challenge, merge the
            // challenge fields (challenge/keyHandles/rpId/allowedCredentials) so
            // Kotlin can relay every field to the companion.
            let mut resp = serde_json::json!({ "status": status });
            if let (Some(fsa), Some(obj)) = (fsa, resp.as_object_mut()) {
                if let Some(fsa_obj) = fsa.as_object() {
                    for (k, v) in fsa_obj { obj.insert(k.clone(), v.clone()); }
                }
            }
            out(&mut env, resp.to_string())
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

    let result = block_on_timeout(90, "2FA verification timed out — Apple didn't respond", async move {
        let verified = if let Some(body) = sms_body {
            account.verify_sms_2fa(code, body).await.map_err(|e| format!("verify_sms_2fa: {e}"))?
        } else {
            account.verify_2fa(code).await.map_err(|e| format!("verify_2fa: {e}"))?
        };
        log::info!("2FA verify → {verified:?}");
        match verified {
            LoginState::LoggedIn | LoginState::NeedsExtraStep(_) => {}
            LoginState::NeedsLogin => {
                // Trusted-device path accepts the code (ec=0) but issues no PET;
                // re-run SRP to collect it (the harness' PET trick).
                account.login_email_pass(&apple, &pw_hash).await.map_err(|e| format!("post-2fa re-login: {e}"))?;
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

/// Submit a security-key (FSA) assertion produced by the companion phone. Mirrors
/// [nativeSubmit2fa]: verify with Apple, ensure we collected a PET, then the caller
/// proceeds to nativeRegister. All fields are the strings Apple's endpoint expects
/// (the companion base64-encodes the binary ones).
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSubmitFsa<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    challenge: JString<'l>,
    client_data: JString<'l>,
    signature_data: JString<'l>,
    authenticator_data: JString<'l>,
    credential_id: JString<'l>,
    user_handle: JString<'l>,
    rp_id: JString<'l>,
) -> jstring {
    let body = AuthenticationFSAResponse {
        challenge: jstr(&mut env, &challenge),
        client_data: jstr(&mut env, &client_data),
        signature_data: jstr(&mut env, &signature_data),
        authenticator_data: jstr(&mut env, &authenticator_data),
        credential_id: jstr(&mut env, &credential_id),
        user_handle: jstr(&mut env, &user_handle),
        rp_id: jstr(&mut env, &rp_id),
    };

    let (mut account, apple, pw_hash) = {
        let mut s = st();
        match s.account.take() {
            Some(a) => (a, s.apple_id.clone(), s.pw_hash.clone()),
            None => return out(&mut env, err_json("no login in progress")),
        }
    };

    let result = block_on_timeout(45, "FSA verification timed out — Apple didn't respond", async move {
        let verified = account.verify_security_key(body).await.map_err(|e| format!("verify_security_key: {e}"))?;
        log::info!("FSA verify → {verified:?}");
        match verified {
            LoginState::LoggedIn | LoginState::NeedsExtraStep(_) => {}
            LoginState::NeedsLogin => {
                // Same PET trick as the 2FA path: the verify can succeed without
                // issuing a PET, so re-run SRP to collect it.
                account.login_email_pass(&apple, &pw_hash).await.map_err(|e| format!("post-fsa re-login: {e}"))?;
            }
            other => return Err(format!("unexpected FSA result: {other:?}")),
        }
        if account.get_pet().is_none() {
            return Err("no PET after FSA — registration would fail".to_string());
        }
        Ok::<_, String>(account)
    });

    match result {
        Ok(account) => {
            st().account = Some(account);
            out(&mut env, serde_json::json!({ "status": "logged_in" }).to_string())
        }
        Err(e) => {
            log::error!("nativeSubmitFsa: {e}");
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
    log::info!("nativeRegister: begin (IDS auth + activation; validation data comes from the NAC server)");

    let result = block_on_timeout(120, "Registration timed out — anisette/Apple/NAC server didn't respond", async move {
        // OpenBubbles emits a liveness/postdata beacon after login and BEFORE asking
        // for delegates (api.rs:2178; again on restore at :677). Smart Txt never sent
        // it at all, so every Smart Txt account signed in without a beacon that every
        // OpenBubbles account emits. Non-fatal: OB ignores the failure on its restore
        // path (`let _ = ...`), and a missing beacon must never block registration.
        let mut account = account;
        match account
            .update_postdata("Apple Device", None, &["icloud", "imessage", "facetime"])
            .await
        {
            Ok(_) => log::info!(
                "OB_PARITY postdata: update_postdata sent (icloud, imessage, facetime) ✅ \
                 — matches OpenBubbles api.rs:2178"
            ),
            Err(e) => log::warn!(
                "OB_PARITY postdata: update_postdata FAILED ({e}) — continuing, not fatal"
            ),
        }

        // IDS only — a DELIBERATE divergence from OpenBubbles, not an oversight.
        //
        // OpenBubbles requests [IDS, MobileMe] (api.rs:2189/:2191) because it actually
        // uses the iCloud side: it builds CloudKitClient, KeychainClient, FindMyClient,
        // SharedStreamClient and the rest. Smart Txt builds none of those — we register
        // for iMessage and nothing else. Asking Apple for a MobileMe delegate we never
        // exercise means holding credentials for services this device does not touch,
        // which is worse than not asking, not better.
        //
        // (An older comment here claimed MobileMe triggers ICLOUD_UNSUPPORTED_DEVICE.
        // That was stale: once X-Apple-I-ROM was corrected to carry dumb field 11, Apple
        // accepted [IDS, MobileMe] on this device on 25-26 Jul. So the delegate is
        // available to us — we are choosing not to take it.)
        log::info!("nativeRegister: [1/3] login_apple_delegates (IDS only — we use no iCloud services)…");
        let delegates = login_apple_delegates(&account, None, os_config.as_ref(), &[LoginDelegate::IDS])
            .await
            .map_err(|e| format!("login_apple_delegates: {e}"))?;
        log::info!(
            "OB_PARITY delegates: [IDS] only — deliberate divergence from OpenBubbles' \
             [IDS, MobileMe]: we build no CloudKit/Keychain/FindMy/SharedStreams clients, \
             so the MobileMe delegate would be an unused credential."
        );
        let ids = delegates.ids.ok_or_else(|| "no IDS delegate".to_string())?;
        log::info!("nativeRegister: [2/3] authenticate_apple…");
        let user = authenticate_apple(ids, os_config.as_ref())
            .await
            .map_err(|e| format!("authenticate_apple: {e}"))?;
        let mut users = vec![user];
        let identity = IDSNGMIdentity::new().map_err(|e| format!("identity: {e}"))?;
        log::info!("nativeRegister: [3/3] register — activation runs NAC validation…");
        // Display, not Debug: PushError::RegisterFailed(IDSError) is
        // `#[error("Registration Error {0}")]` and IDSError's Display carries the
        // human 6001/6004/6005/6009 text; RateLimit / CustomerMessage likewise put
        // their message in Display. `{e:?}` would reduce all of that to
        // `RegisterFailed(IDSError(6005))`.
        log::info!(
            "OB_PARITY services: registering {} IDS service(s) = \
             [madrid, multiplex/findmy, facetime, ess/video] — matches OpenBubbles api.rs:863 \
             (this was madrid-only before, which no real Mac does)",
            IDS_SERVICES.len()
        );
        register(os_config.as_ref(), &*connection.state.read().await, IDS_SERVICES, &mut users, &identity)
            .await
            .map_err(|e| format!("register: {e}"))?;
        log::info!("nativeRegister: ✅ registered {} user(s)", users.len());
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
    if let Err(e) = build_client_guarded(users, identity) {
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

/// Force an IDS re-registration NOW, reusing the already-registered identity — no
/// login, no 2FA. This is the SAME operation rustpush runs on its own every couple
/// of months (schedule_rereg → IdentityResource::generate → `register(...)`); the
/// Settings "Re-register now" button drives it on demand so that path can be tested
/// without waiting for the cert to near expiry.
///
/// Goes through the live client's IdentityManager (`refresh_now`) so the running
/// client picks up the fresh registration, rather than re-registering a detached
/// copy underneath it. rustpush debounces this to once per 15s (MAX_RESOURCE_REGEN)
/// and bounds the wait to 30s (MAX_RESOURCE_WAIT); a call inside the debounce window
/// returns Ok without hitting Apple. Requires a live client — i.e. the user is
/// signed in and connected.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeReregister<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let client = st().client.clone();
    let Some(client) = client else {
        return out(&mut env, err_json("iMessage isn't connected right now. Open Smart Txt Settings and tap Re-register now to reconnect, then restart your phone."));
    };
    log::info!("nativeReregister: forcing IDS re-registration (reusing identity — no login)…");
    let result = rt().block_on(async move {
        // Display, not Debug, so an IDS 6004/6005/rate-limit surfaces its human text.
        client.identity.refresh_now().await.map_err(|e| format!("{e}"))?;
        Ok::<Vec<String>, String>(client.identity.get_handles().await)
    });
    match result {
        Ok(handles) => {
            log::info!("nativeReregister: ✅ re-registered handles={handles:?}");
            out(&mut env, serde_json::json!({ "ok": true, "handles": handles }).to_string())
        }
        Err(e) => {
            log::error!("nativeReregister: {e}");
            out(&mut env, err_json(e))
        }
    }
}

/// Build the registration-state JSON from rustpush's `ResourceState`. Shared by the
/// pull path (`nativeRegisterState`) and the push watcher (`spawn_regstate_watcher`)
/// so the two can never disagree.
async fn regstate_payload(client: &Arc<IMClient>) -> serde_json::Value {
    // Clone out of the watch guard BEFORE any await — the guard isn't Send.
    let state = { client.identity.resource_state.borrow().clone() };
    match &state {
        ResourceState::Generating => serde_json::json!({ "state": "registering" }),
        ResourceState::Generated => {
            let next_s = client.identity.calculate_rereg_time_s().await;
            serde_json::json!({ "state": "registered", "next_s": next_s })
        }
        // Display, not Debug, so an IDS code surfaces its human text.
        ResourceState::Failed(failure) => serde_json::json!({
            "state": "failed",
            "retry_wait": failure.retry_wait,
            "needs_relogin": failure.retry_wait.is_none(),
            "error": format!("{}", failure.error),
        }),
        ResourceState::Closed => serde_json::json!({
            "state": "failed",
            "needs_relogin": true,
            "error": "identity closed by IDS (6005) — re-sign-in required",
        }),
    }
}

/// Watch rustpush's `resource_state` and push every change into the same `inbound`
/// queue the app already drains every 500ms.
///
/// `resource_state` is a `watch` channel — a PUSH primitive. OpenBubbles subscribes
/// to it (`reg_state.changed()` inside its poll future) so Dart learns about a
/// terminal registration failure within one tick. Sampling it only at cold start left
/// a window where the UI still said "registered" while every send failed.
fn spawn_regstate_watcher(client: Arc<IMClient>, my_gen: u64) {
    rt().spawn(async move {
        let mut rx = client.identity.resource_state.subscribe();
        // Emit the CURRENT state once before waiting on changes. tokio's `watch`
        // marks the value as already seen at subscribe time, so `changed()` never
        // fires for a resource that is ALREADY `Generated` — or, more importantly,
        // already `Failed`. Without this, a client built on top of an
        // already-terminal identity would never tell Kotlin anything.
        {
            let payload = regstate_payload(&client).await;
            log::info!("REGSTATE(push, initial): {payload}");
            st().queue_event(serde_json::json!({
                "type": "registration_state",
                "state": payload,
            }));
        }
        loop {
            if rx.changed().await.is_err() {
                break; // sender dropped
            }
            if RECV_GEN.load(Ordering::SeqCst) != my_gen {
                break; // superseded by a newer client
            }
            let payload = regstate_payload(&client).await;
            log::info!("REGSTATE(push): {payload}");
            st().queue_event(serde_json::json!({
                "type": "registration_state",
                "state": payload,
            }));
        }
        log::info!("regstate watcher ended (gen {my_gen})");
    });
}

/// Watch the APS (APNs socket) resource state and log every transition.
///
/// DIAGNOSTIC ONLY. Unlike `spawn_regstate_watcher` this pushes nothing into
/// `inbound` and changes no behaviour — it exists purely so an exported log says
/// what the socket was doing.
///
/// WHY: when the APNs socket dies half-open (the AP or a NAT drops the flow with no
/// FIN/RST, so both ends still believe they are connected) the ONLY evidence in an
/// exported bundle is a handful of byte-identical "Send timed out, forcing reload!"
/// lines, and the minutes between the socket dying and the reconnect succeeding are
/// completely silent. Reconstructing it means diffing message timestamps by hand.
/// `resource_state` is the same `watch` channel the identity watcher above uses;
/// APS has one too and nothing was listening to it.
///
/// Expect, on a healthy reconnect: Failed(retry_wait=1s) → Generating → Generated.
/// A long silence after `Generating` with no `Generated` is a hung `open_socket`.
fn spawn_apsstate_watcher(conn: APSConnection, my_gen: u64) {
    fn describe(s: &ResourceState) -> String {
        match s {
            ResourceState::Generated => "Generated (socket up)".to_string(),
            ResourceState::Generating => "Generating (opening socket)".to_string(),
            ResourceState::Closed => "Closed (dead - no further retries)".to_string(),
            // Display, not Debug, so the underlying error surfaces its human text.
            ResourceState::Failed(f) => format!(
                "Failed (retry_wait={} err={})",
                f.retry_wait.map(|v| format!("{v}s")).unwrap_or_else(|| "none/terminal".to_string()),
                f.error,
            ),
        }
    }
    rt().spawn(async move {
        let mut rx = conn.resource_state.subscribe();
        // Emit the CURRENT state once: tokio's `watch` marks the value as already seen
        // at subscribe time, so `changed()` never fires for a resource that is already
        // Generated (or already Failed).
        {
            let now = rx.borrow().clone();
            log::info!("APSSTATE(initial): {}", describe(&now));
        }
        loop {
            if rx.changed().await.is_err() {
                break; // sender dropped
            }
            if RECV_GEN.load(Ordering::SeqCst) != my_gen {
                break; // superseded by a newer client
            }
            let now = rx.borrow_and_update().clone();
            log::info!("APSSTATE: {}", describe(&now));
        }
        log::info!("APSSTATE: watcher ended (gen {my_gen})");
    });
}

/// The live IDS registration state, read straight out of rustpush's own
/// `ResourceManager` — the mirror of OpenBubbles' `get_regstate`.
///
/// This is the ONLY thing Kotlin should use to judge registration health.
/// rustpush owns all retry: it re-registers on Apple's ~45-day cadence and
/// retries transient failures forever with a 5-minute-to-24-hour backoff.
/// Kotlin's job is to notice the one case rustpush refuses to retry — a 6005,
/// which arrives as `failed` with no `retry_wait` — and put the user on the
/// sign-in screen instead of re-presenting rejected credentials.
///
/// ```json
/// {"state":"registered","next_s":3887700}
/// {"state":"registering"}
/// {"state":"failed","retry_wait":300,"needs_relogin":false,"error":"..."}
/// {"state":"failed","needs_relogin":true,"error":"..."}   // 6005 / closed
/// {"state":"no_client"}                                   // not signed in yet
/// ```
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeRegisterState<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let client = st().client.clone();
    let Some(client) = client else {
        log::info!("REGSTATE: no_client (not signed in)");
        return out(&mut env, serde_json::json!({ "state": "no_client" }).to_string());
    };
    let payload = rt().block_on(regstate_payload(&client));
    // Greppable marker for exported log bundles.
    log::info!("REGSTATE: {payload}");
    out(&mut env, payload.to_string())
}

/// Push a synthetic registration state into the same `inbound` queue that
/// `spawn_regstate_watcher` writes to, so the Kotlin routing that follows a terminal
/// IDS 6005 can be exercised on demand.
///
/// WHY THIS SHIPS IN RELEASE. A 6005 cannot be provoked - Apple decides when to
/// invalidate a registration - and shipping to more users does not test it either,
/// since a user who signs in successfully never produces one. Reaching this requires
/// physically navigating the hidden Settings gesture (toggle 24-hour time 10x); there
/// is deliberately NO broadcast receiver, so adb alone cannot trigger it.
///
/// WHY IT TAKES A VARIANT NAME AND NOT JSON. `inbound` is a trusted channel -
/// everything else that writes to it is rustpush. Accepting caller-supplied JSON in a
/// shipped build would hand anyone who found this symbol a way to forge ANY event the
/// app understands: fake messages, fake read receipts, fake delivery status. Two fixed
/// variants can only reproduce states rustpush itself could already have published.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeDebugInjectRegState<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    kind: JString<'l>,
) {
    let kind = jstr(&mut env, &kind);
    let state = match kind.as_str() {
        // Mirrors ResourceState::Closed - the real 6005 shape.
        "terminal" => serde_json::json!({
            "state": "failed",
            "needs_relogin": true,
            "error": "SYNTHETIC terminal 6005 (injected from hidden diagnostics)",
        }),
        // Mirrors ResourceState::Failed WITH a retry_wait. Must NOT sign anyone out.
        "transient" => serde_json::json!({
            "state": "failed",
            "retry_wait": 300,
            "needs_relogin": false,
            "error": "SYNTHETIC transient failure (injected from hidden diagnostics)",
        }),
        other => {
            log::error!("nativeDebugInjectRegState: unknown variant {other:?} - ignoring");
            return;
        }
    };
    // warn!, and it says SYNTHETIC, so nobody reading an exported bundle six weeks from
    // now mistakes an injected state for something Apple actually sent.
    log::warn!("REGSTATE(SYNTHETIC - injected by hidden diagnostics, NOT from Apple): {state}");
    st().queue_event(serde_json::json!({
        "type": "registration_state",
        "state": state,
    }));
}

/// The handles IDS currently has registered for this device, read live from
/// rustpush (`IdentityResource::get_handles`).
///
/// Exposed because the app was otherwise reading a snapshot persisted at the last
/// register — the same stale-cache pattern that was already fixed once. OpenBubbles
/// exposes this as a one-line passthrough and calls it live everywhere.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeHandles<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let client = st().client.clone();
    let Some(client) = client else {
        return out(&mut env, err_json("iMessage isn't connected right now."));
    };
    let handles = rt().block_on(async move { client.identity.get_handles().await });
    out(&mut env, serde_json::json!({ "handles": handles }).to_string())
}

/// Proactively reconcile registered handles against what IDS vends and reregister if
/// they differ (see [reconcile_handles]). Exposed so the Kotlin side can run it on
/// connect / foreground / renewal — the "many triggers" that keep handles current the
/// way OpenBubbles does. Returns `{ok, handles}`.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeReconcileHandles<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let Some(client) = st().client.clone() else {
        return out(&mut env, err_json("iMessage isn't connected right now."));
    };
    let handles = rt().block_on(async move { reconcile_handles(&client).await });
    log::info!("nativeReconcileHandles: handles={handles:?}");
    out(&mut env, serde_json::json!({ "ok": true, "handles": handles }).to_string())
}

/// Send a read receipt (iMessage command 102, `Message::Read`) for `chat_guid`,
/// marking read up to `last_read_guid` — the guid of the newest message FROM THEM.
/// This is the command Apple devices act on to clear a conversation's notification;
/// it is NOT `Message::MessageReadOnDevice` (147), which rustpush uses only as the
/// SMS-activation confirmation. `tell_sender` gates delivery: false (the default)
/// targets ONLY my own handle, so my OTHER devices clear their notification and the
/// sender is never told "Read"; true also targets the chat's other party, so they
/// see "Read". Best-effort: returns false if not connected, no handle, or no
/// iMessage targets (e.g. an SMS thread).
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeMarkRead<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    last_read_guid: JString<'l>,
    tell_sender: jboolean,
) -> jboolean {
    let chat = jstr(&mut env, &chat_guid);
    let last_guid = jstr(&mut env, &last_read_guid);
    let tell_sender = tell_sender == JNI_TRUE;
    let client = st().client.clone();
    let Some(client) = client else {
        return JNI_FALSE;
    };
    let ok = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        // Send from the handle this thread is on (falls back to the default).
        let Some(handle) = thread_send_handle(&chat, &handles).or_else(|| pick_send_handle(&handles))
        else {
            return false;
        };
        // Send a REAL read receipt: command 102 (Message::Read). This is the message
        // Apple devices act on to clear a chat's notification — NOT MessageReadOnDevice
        // (147), which in rustpush is the SMS-activation confirmation (see aps_client.rs:
        // it's auto-sent in reply to command 145 EnableSmsActivation, Display = "confirmed
        // sms activation"). A read receipt references the message it read up to: its own
        // `id` is that message's guid, mirroring how rustpush parses an inbound 102.
        //
        // Who gets told is decided by the participant list:
        //  - tell_sender = false (default): ONLY my own handle. prepare_send resolves my
        //    handle's own devices and send_message drops MY CURRENT token, so the receipt
        //    reaches my OTHER devices (clearing their notification) and is NEVER delivered
        //    to the sender. (If a device turns out to key notification-clearing off the
        //    conversation participants rather than the read guid, the alternative is to
        //    keep the full participant list but restrict `inst.target` to my own device
        //    tokens — validate on-device / against a capture before relying on either.)
        //  - tell_sender = true: include the chat's other party, so they see "Read".
        // Start from the group's real identity (gid + members + name) so the receipt is
        // scoped to the exact conversation, then apply the tell_sender participant rule.
        let mut conv = conv_data_for(&chat);
        if !tell_sender {
            // self-only: target ONLY my own handle so the receipt reaches my OTHER
            // devices (clearing their notification) and never the sender.
            conv.participants = vec![handle.clone()];
        }
        // Grep-able trace of what this read actually does: which chat, which message it
        // marks read up to, whether the sender is told, and how many participants it
        // targets (self-only ⇒ 1, since only my own handle is listed). Pair this with the
        // "ID send message … command: 102" line to confirm the 147→102 switch is live.
        let participant_count = conv.participants.len();
        log::info!(
            "nativeMarkRead: chat={chat} up_to_guid={last_guid} tell_sender={tell_sender} \
             from={handle} participants={participant_count} → Message::Read (cmd 102)"
        );
        let mut inst = MessageInst::new(conv, &handle, Message::Read);
        // A read receipt's id IS the guid of the message being marked read (Apple reuses
        // the original message's uuid). Skip only if the caller had no inbound message.
        if !last_guid.is_empty() {
            inst.id = last_guid.to_uppercase();
        }
        match client.send(&mut inst).await {
            Ok(_) => {
                log::info!("nativeMarkRead: sent read (cmd 102) for chat={chat} up_to_guid={}", inst.id);
                true
            }
            Err(e) => {
                // Expected on a green/SMS thread: a read receipt is iMessage-only, and an
                // SMS contact has no iMessage targets, so this returns NoValidTargets.
                // Not a real failure — debug, not warn, so it doesn't look like the cause
                // when diagnosing send problems.
                log::debug!("nativeMarkRead: skipped ({e:?}) — no iMessage targets (SMS thread?)");
                false
            }
        }
    });
    if ok { JNI_TRUE } else { JNI_FALSE }
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

    // Same list as nativeRegister: IdentityResource stores this slice and reuses it
    // for every automatic re-registration, so a mismatch here would quietly undo the
    // parity fix ~45 days later.
    log::info!(
        "OB_PARITY services: IMClient carries {} IDS service(s) — automatic re-registration \
         will send the same list OpenBubbles sends",
        IDS_SERVICES.len()
    );
    let client = IMClient::new(
        connection.clone(),
        users,
        identity,
        IDS_SERVICES,
        Path::new(&dir).join("id_cache.plist"),
        os_config.clone(),
        Box::new(|_updated| {}),
    )
    .await;
    let client = Arc::new(client);
    // OB-style handle reconciliation: publish the client, then reregister if IDS is
    // vending handles we haven't registered (possible != registered). Handles are read
    // live from rustpush (get_handles) wherever needed — nothing is cached.
    st().client = Some(client.clone());
    let _ = reconcile_handles(&client).await;
    // Restore whether SMS forwarding was previously enabled by the iPhone.
    SMS_ACTIVE.store(load_sms_active(&dir), Ordering::SeqCst);

    // Bump the generation so any older receive loop (e.g. one left over from a
    // closed identity after a recover) exits when it sees the mismatch, and this
    // fresh loop takes over. VERIFY: the subscribe/handle surface moves between
    // rustpush revisions — mirrors the imessage-register engine receive pattern.
    let my_gen = RECV_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    spawn_regstate_watcher(client.clone(), my_gen);
    let connection = st().connection.clone().unwrap();
    // Log-only; see the fn doc. Same generation guard as the regstate watcher so a
    // recover's fresh watcher replaces this one instead of doubling up.
    spawn_apsstate_watcher(connection.clone(), my_gen);
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
                    // Read handles live from rustpush (get_handles = cheap local read)
                    // and pass them into the sync event handler — no cached self set.
                    let my_handles = client.identity.get_handles().await;
                    let client = client.clone();
                    let joined = rt().spawn(async move { client.handle(apns_msg).await }).await;
                    match joined {
                        Ok(Ok(Some(msg))) => push_relay_event(msg, &my_handles),
                        Ok(Ok(None)) => {}
                        Ok(Err(e)) => {
                            let dbg = format!("{e:?}");
                            log::warn!("receive handle error: {dbg}");
                            note_if_identity_closed(&dbg);
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

/// Log — and ONLY log — when IDS has closed the identity.
///
/// A 6005 means Apple invalidated this registration and wants a real re-login.
/// rustpush says so itself, then deliberately stops:
///
/// ```text
/// info!("Auth returns 6005, relog required!");
/// return Err(PushError::DoNotRetry(Box::new(err)))
/// ```
///
/// `ResourceManager` parks the resource in `ResourceState::Failed { retry_wait:
/// None }` and breaks out of its retry loop. Every OTHER failure it retries on
/// its own, forever, with a 5-minute-to-24-hour exponential backoff — so there
/// is nothing here for us to retry, and re-presenting rejected credentials in a
/// loop is what gets an Apple ID rate-limited.
///
/// Kotlin picks the state up via `nativeRegisterState` and routes the user to a
/// real sign-in — the same thing OpenBubbles does with `get_regstate` +
/// `markFailedToLogin`.
fn note_if_identity_closed(err_dbg: &str) {
    let closed = err_dbg.contains("ResourceClosed")
        || err_dbg.contains("Resource has been closed")
        || err_dbg.contains("6005");
    if closed {
        // Greppable marker for exported log bundles.
        log::warn!(
            "IDENTITY_CLOSED: IDS closed this identity (6005). rustpush marked it \
             DoNotRetry and will NOT retry — this is correct. No automatic re-login is \
             attempted (that behaviour was removed). A real re-sign-in is required; \
             Kotlin surfaces it via nativeRegisterState."
        );
    }
}

// `canon` (canonical handle key: scheme stripped, email lowercased, phone → "+<digits>"
// with a +1 for US 10-digit) now lives in group_identity.rs, imported above, so the
// identity helpers and their host tests share one definition. It MUST stay byte-for-byte
// equal to Kotlin `Handles.canon` so inbound/outbound/contact entries all key the same.

/// Only surface messages/reactions from the last few days. A big offline backlog
/// on catch-up is dropped here so it never reaches storage or the UI — the sweet
/// spot for a low-RAM dumb phone (older history still lives on the user's other
/// Apple devices).
const SYNC_WINDOW_MS: u64 = 3 * 24 * 60 * 60 * 1000; // 3 days

/// Map a received rustpush `Message` to a relay-wire event and queue it.
/// VERIFY: field access on the rustpush Message variants against your pinned rev.
fn push_relay_event(msg: MessageInst, my_handles: &[String]) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    // The paired iPhone toggling Text Message Forwarding for this device on/off.
    if let Message::EnableSmsActivation(enabled) = &msg.message {
        SMS_ACTIVE.store(*enabled, Ordering::SeqCst);
        let dir = st().files_dir.clone();
        save_sms_active(&dir, *enabled);
        st().queue_event(serde_json::json!({ "type": "sms_activation", "enabled": *enabled }));
        return;
    }
    // Cross-device read sync: Apple tells THIS device that I read a chat on another
    // of my devices, so we can clear its unread + notification here.
    //  - MessageReadOnDevice (type 147): the canonical "read on another of my devices".
    //  - A Read receipt whose sender is one of MY OWN handles: same meaning (I read it
    //    elsewhere). A Read receipt from the OTHER party is a "they read my message"
    //    delivery receipt — NOT this — so it's excluded by the self-handle check.
    let read_elsewhere = match &msg.message {
        Message::MessageReadOnDevice => true,
        Message::Read => {
            let sender = msg.sender.clone().unwrap_or_default();
            !sender.is_empty() && my_handles.iter().any(|h| canon(h) == canon(&sender))
        }
        _ => false,
    };
    // A Read whose sender ISN'T me is the PEER receipt — "they read MY message" —
    // which is what flips an outgoing bubble from Delivered to Read. It used to be
    // logged and dropped here. Note MessageReadOnDevice always sets read_elsewhere,
    // so this can only ever be a Message::Read.
    //
    // Both cases need exactly the same conversation → chat_guid derivation, so they
    // share the block below and differ only in the event pushed at the end.
    let peer_read = matches!(&msg.message, Message::Read) && !read_elsewhere;
    if read_elsewhere || peer_read {
        let sender = msg.sender.clone().unwrap_or_default();
        let my: Vec<String> = my_handles.iter().map(|h| canon(h)).collect();
        let participants: Vec<String> = msg
            .conversation
            .as_ref()
            .map(|c| c.participants.clone())
            .filter(|p| !p.is_empty())
            .unwrap_or_else(|| vec![sender.clone()]);
        let cv_name = msg
            .conversation
            .as_ref()
            .and_then(|c| c.cv_name.clone())
            .filter(|s| !s.is_empty());
        let mut counterparts: Vec<String> = participants
            .iter()
            .map(|h| canon(h))
            .filter(|c| !c.is_empty() && !my.contains(c))
            .collect();
        counterparts.sort();
        counterparts.dedup();
        // Match the chat_guid the text path computes (incl. cv_name grouping) so the
        // notification id (roomId.hashCode()) lines up on the Kotlin side.
        //
        // BUT a read that Apple self-syncs across MY OWN devices frequently arrives
        // with NO counterpart in its conversation (participants = just me, or no
        // conversation at all) — so we often CAN'T name the chat from participants and
        // used to silently drop it here ("read on my phone didn't clear the flip"). So
        // also ship the read's message guid (msg.id = the guid of the message read up
        // to): when chatGuid is empty the app resolves the room from that guid instead.
        let gid = msg.conversation.as_ref().and_then(|c| c.sender_guid.clone());
        let is_group = counterparts.len() > 1 || cv_name.is_some();
        let chat_guid = if is_group {
            group_chat_guid(&gid, &counterparts, &cv_name)
        } else if let Some(other) = counterparts.first() {
            format!("iMessage;-;{other}")
        } else {
            String::new()
        };
        let read_guid = msg.id.to_uppercase();
        log::info!(
            "recv {}: sender={sender} chat={chat_guid:?} up_to_guid={read_guid} \
             counterparts={counterparts:?}",
            if peer_read { "peer-read-receipt" } else { "read-on-device" }
        );
        if chat_guid.is_empty() && read_guid.is_empty() {
            return; // no chat AND no message guid — genuinely nothing to act on
        }
        if peer_read {
            // They read what I sent. `guid` is the message read UP TO — Apple reuses
            // the original message's uuid as the receipt's id — so the app flips every
            // outgoing message in the chat up to and including it, not just this one.
            st().queue_event(serde_json::json!({
                "type": "message_status",
                "chatGuid": chat_guid,
                "guid": read_guid,
                "status": "read",
            }));
        } else {
            st().queue_event(serde_json::json!({
                "type": "chat_read",
                "chatGuid": chat_guid,
                "messageGuid": read_guid,
            }));
        }
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
    // Replay guard: Apple re-sends its stored backlog on every APS connect (see
    // `AppState.seen_guids`), so on a relaunch the last few days of messages come
    // down the socket AGAIN — already-stored history, re-decrypted, re-marshalled,
    // re-folded into the room list. Drop anything the app already has.
    //
    // `insert` returns false when the guid was already present, so this both tests
    // and records in one lock. A tapback's add and its later removal are separate
    // messages with separate guids, so reactions dedupe correctly too.
    if matches!(&msg.message, Message::Message(_) | Message::React(_)) && !msg.id.is_empty() {
        // Bind first so the AppState guard is released before the block runs — the
        // rest of this function re-locks `st()` freely.
        let already_stored = !st().seen_guids.insert(msg.id.clone());
        if already_stored {
            log::debug!("skip replay: guid={} already stored", msg.id);
            return;
        }
    }
    // A reaction (tapback) from someone — emit a tapback event the repo folds into
    // the target message's reactions.
    if let Message::React(react) = &msg.message {
        let (emoji, remove) = match &react.reaction {
            ReactMessageType::React { reaction, enable } => (reaction_emoji(reaction), !*enable),
            _ => return, // extension/sticker reactions not handled here
        };
        let sender = msg.sender.clone().unwrap_or_default();
        let my: Vec<String> = my_handles.iter().map(|h| canon(h)).collect();
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
        // Same gid-keying as the message path, so a reaction lands on the exact group
        // conversation (and folds into its target message) instead of a members-only one.
        let gid = msg.conversation.as_ref().and_then(|c| c.sender_guid.clone());
        let cv_name = msg
            .conversation
            .as_ref()
            .and_then(|c| c.cv_name.clone())
            .filter(|s| !s.is_empty());
        let chat_guid = if counterparts.len() > 1 || cv_name.is_some() {
            group_chat_guid(&gid, &counterparts, &cv_name)
        } else {
            format!("iMessage;-;{}", counterparts.first().cloned().unwrap_or_else(|| canon(&sender)))
        };
        log::info!(
            "recv tapback: target={} emoji={emoji} remove={remove} from_me={is_from_me} chat={chat_guid}",
            react.to_uuid
        );
        st().queue_event(serde_json::json!({
            "type": "tapback",
            "chatGuid": chat_guid,
            "targetGuid": react.to_uuid,
            // The reaction message's OWN guid, so a later cross-device "read up to this
            // reaction" can resolve the room: we fold reactions into their target message
            // and never store one under this guid, so the app can't look it up otherwise.
            "guid": msg.id.clone(),
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
        let my: Vec<String> = my_handles.iter().map(|h| canon(h)).collect();
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
        // Group = more than one other party (or a named conversation). Key it by
        // Apple's stable group id (gid) — NOT the member set — so a named group and an
        // unnamed group with the same people stay distinct AND a reply threads into the
        // exact conversation (see group_chat_guid). A 1:1 stays keyed by the other party.
        let gid = msg.conversation.as_ref().and_then(|c| c.sender_guid.clone());
        let is_group = counterparts.len() > 1 || cv_name.is_some();
        let chat_guid = if is_group {
            group_chat_guid(&gid, &counterparts, &cv_name)
        } else {
            let other = counterparts.first().cloned().unwrap_or_else(|| canon(&sender));
            format!("iMessage;-;{other}")
        };
        // Group display names are user-authored, so they get the same
        // treatment as message bodies. chat_guid already identifies the
        // conversation uniquely, so the name adds nothing to diagnosis.
        let cv_name_redacted = cv_name
            .as_deref()
            .map(rustpush::redact_text)
            .unwrap_or_else(|| "none".to_string());
        log::info!(
            "recv msg: guid={} ts={} sender={sender} is_from_me={is_from_me} \
             is_group={is_group} counterparts={counterparts:?} cv_name={cv_name_redacted} \
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
                // A voice memo (the message's `voice` flag) is an audio attachment
                // whose mime often ISN'T "audio/*" — iMessage sends CAF as
                // application/octet-stream — so it would otherwise fall to "other" and
                // get routed to the photo viewer ("Can't preview this photo"). The
                // voice flag is the reliable signal, so honor it FIRST; also treat CAF
                // by uti/mime as audio for good measure.
                let is_audio_uti = att.uti_type.contains("coreaudio")
                    || att.uti_type.contains("m4a")
                    || att.mime.contains("caf")
                    || att.name.to_lowercase().ends_with(".caf");
                let kind = if normal.voice || is_audio_uti || att.mime.starts_with("audio/") { "audio" }
                    else if att.mime.starts_with("image/") { "image" }
                    else if att.mime.starts_with("video/") { "video" }
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

        // Self-heal SMS forwarding state: RECEIVING a forwarded green text is proof
        // the paired iPhone's Text Message Forwarding is live right now. Trust that
        // for SENDING too, even if we never caught the EnableSmsActivation announce
        // (forwarding turned on before this app existed, the persisted flag was lost,
        // etc.). Without this, "I can receive green but can't reply green" happens
        // because the SMS send is blocked on a stale SMS_ACTIVE=false. The iPhone
        // still turns it back OFF explicitly via EnableSmsActivation(false).
        if service == "SMS" && !SMS_ACTIVE.load(Ordering::SeqCst) {
            log::info!("inbound SMS observed — enabling SMS forwarding for outgoing sends");
            SMS_ACTIVE.store(true, Ordering::SeqCst);
            let dir = st().files_dir.clone();
            save_sms_active(&dir, true);
        }

        // Learn which of MY handles this 1:1 iMessage thread transmits from, so a
        // reply goes out from the SAME handle instead of the global default. A
        // message I sent (synced from any device) is ground truth: its sender IS the
        // thread's handle. A received message is a weaker signal — the handle they
        // addressed — used only when we don't already know. (Groups route over
        // iMessage to everyone regardless, so they don't need a pinned self-handle.)
        if service == "iMessage" && !is_group {
            let my_registered = my_handles.to_vec();
            if is_from_me {
                if my_registered.iter().any(|h| h == &sender) {
                    remember_thread_handle(&chat_guid, &sender);
                }
            } else if !st().thread_handles.contains_key(&chat_guid) {
                let mine = participants.iter().find_map(|p| {
                    let pc = canon(p.as_str());
                    my_registered.iter().find(|h| canon(h.as_str()) == pc).cloned()
                });
                if let Some(mine) = mine {
                    remember_thread_handle(&chat_guid, &mine);
                }
            }
        }

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
                // The group's OTHER-party addresses, so the repo can show members and
                // per-sender names without parsing them out of the (now gid-keyed) guid.
                "participants": counterparts,
                "replyToGuid": normal.reply_guid.clone().unwrap_or_default(),
                // A message I sent (incl. from another device that synced here) was
                // at least delivered — show the receipt, don't leave it plain "Sent".
                "status": if is_from_me { "delivered" } else { "" },
            }
        });
        // Stash the attachment refs and persist them, so the download still works after
        // the process restarts. Only touches the disk when this message actually carried
        // an attachment, which is rare enough that the write costs nothing in practice.
        // Inline payloads (MMS pictures) go to their own blob files FIRST — written once,
        // before the lock is taken — so the index rewritten below stays a few hundred KB
        // no matter how much attachment history is retained. Writing them into the index
        // instead would mean re-serialising every retained byte on every new attachment.
        if !stash.is_empty() {
            let dir = st().files_dir.clone();
            for (key, att) in &stash {
                if let AttachmentType::Inline(data) = &att.a_type {
                    if !data.is_empty() {
                        write_blob(&dir, key, data);
                    }
                }
            }
        }
        let persist = {
            let mut s = st();
            let changed = !stash.is_empty();
            for (guid, att) in stash {
                if s.attachments.insert(guid.clone(), att).is_none() {
                    s.attachment_order.push_back(guid);
                }
            }
            // Evict oldest-first until BOTH limits hold: the entry count, and the inline
            // bytes actually written to disk (MMCS refs are free, MMS payloads are not).
            let mut inline_total: usize = s
                .attachment_order
                .iter()
                .filter_map(|k| s.attachments.get(k))
                .map(inline_bytes)
                .sum();
            let mut evicted: Vec<String> = Vec::new();
            while s.attachment_order.len() > ATTACHMENT_STASH_CAP
                || inline_total > ATTACHMENT_INLINE_BUDGET
            {
                let Some(key) = s.attachment_order.pop_front() else { break };
                if let Some(att) = s.attachments.remove(&key) {
                    inline_total = inline_total.saturating_sub(inline_bytes(&att));
                }
                evicted.push(key);
            }
            s.queue_event(event);
            if changed {
                Some((s.files_dir.clone(), ordered_attachments(&s), evicted))
            } else {
                None
            }
        }; // guard dropped here — never hold the AppState lock across disk I/O
        if let Some((dir, items, evicted)) = persist {
            for key in &evicted {
                remove_blob(&dir, key);
            }
            save_attachments(&dir, &items);
        }
    }
}

// =============================================================================
// JNI: outbound + inbound drain
// =============================================================================

/// Shown on the bubble when a send is attempted with no live `IMClient`. In the field
/// that almost always means the phone has no working data connection — the client is
/// built on connect and torn down when the network goes away — so the copy points at
/// the network instead of at re-registering. The old text ("iMessage isn't connected…
/// tap Re-register now") sent a user whose data plan had simply run out into Settings
/// to fix something that wasn't broken.
const ERR_SEND_OFFLINE: &str =
    "ERR:Message not sent — network error. Check your connection and try again.";

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
        return out(&mut env, ERR_SEND_OFFLINE.to_string());
    };

    let guid = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        // Existing thread → the handle it's already on; new thread → the global
        // "start new messages from" default. Keeps a reply on the thread's own
        // self-handle so Apple doesn't see a new participant (group-chat-with-self).
        let Some(handle) = thread_send_handle(&chat, &handles).or_else(|| pick_send_handle(&handles)) else {
            return "ERR:No sending number or email is set up for this account (no registered handles).".to_string();
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
        // One line that says exactly how this send was routed — the missing piece
        // when a green reply fails: was it built as SMS (green) or iMessage (blue),
        // and from which of my handles. Pair it with the decide_route log above.
        log::info!(
            "nativeSendText: chat={chat} is_group={is_group} is_sms={is_sms} \
             from_handle={handle} has_reply={}",
            !reply_to.is_empty()
        );
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
            // Replay the group's real gid + members + name (or the 1:1 recipient) so the
            // message threads into the SAME Apple conversation instead of forking a new
            // members-only one. rustpush adds the sender (&handle) itself.
            conv_data_for(&chat),
            &handle,
            Message::Message(msg),
        );
        match client.send(&mut inst).await {
            Ok(_) => {
                // Pin this thread to the handle we just sent from, so every later
                // reply/attachment/tapback stays on it — and a brand-new thread's
                // first message fixes its self-handle here.
                remember_thread_handle(&chat, &handle);
                let guid = inst.id.clone(); // VERIFY: server guid field name on MessageInst
                // Optimistic "delivered" so the bubble shows a receipt. Apple's real
                // delivery receipt arrives later over APNs (via the receive loop).
                st().queue_event(serde_json::json!({
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
                // Surface the real reason to the UI (via the ERR: channel) instead of a
                // generic "couldn't send". Display is the human-readable thiserror message;
                // the full Debug is in logcat above.
                format!("ERR:Send failed: {e}")
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
    caption: JString<'l>,
) -> jstring {
    let chat = jstr(&mut env, &chat_guid);
    let mime_s = jstr(&mut env, &mime);
    let name_s = jstr(&mut env, &name);
    let caption_s = jstr(&mut env, &caption);
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return out(&mut env, String::new()),
    };
    let client = st().client.clone();
    let connection = st().connection.clone();
    let (Some(client), Some(connection)) = (client, connection) else {
        return out(&mut env, ERR_SEND_OFFLINE.to_string());
    };
    let voice = mime_s.starts_with("audio/");
    let uti = uti_for_mime(&mime_s);

    let guid = rt().block_on(async move {
        let handles = client.identity.get_handles().await;
        // Existing thread → its own handle; new thread → the global default.
        let Some(handle) = thread_send_handle(&chat, &handles).or_else(|| pick_send_handle(&handles)) else {
            return "ERR:No sending number or email is set up for this account (no registered handles).".to_string();
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
                return format!("ERR:Couldn't prepare the attachment upload: {e}");
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
                return format!("ERR:Attachment upload failed: {e}");
            }
        };

        // 2) The message. Its parts are the attachment, plus — when a caption was
        // typed — the caption text so it renders as ONE bubble (image + caption),
        // matching iMessage. NormalMessage::new builds the text part(s) from the
        // caption; we then append the attachment part. With no caption we send the
        // attachment alone (an empty text part would show as a blank line).
        let mut normal = if caption_s.is_empty() {
            let mut n = NormalMessage::new(String::new(), service);
            n.parts = MessageParts(vec![IndexedMessagePart {
                part: MessagePart::Attachment(attachment),
                idx: None,
                ext: None,
            }]);
            n
        } else {
            let mut n = NormalMessage::new(caption_s.clone(), service);
            n.parts.0.push(IndexedMessagePart {
                part: MessagePart::Attachment(attachment),
                idx: None,
                ext: None,
            });
            n
        };
        normal.voice = voice;
        let mut inst = MessageInst::new(
            // Replay the group's real gid + members + name (or the 1:1 recipient) so the
            // attachment threads into the SAME conversation instead of a members-only one.
            conv_data_for(&chat),
            &handle,
            Message::Message(normal),
        );
        match client.send(&mut inst).await {
            Ok(_) => {
                remember_thread_handle(&chat, &handle);
                let guid = inst.id.clone();
                st().queue_event(serde_json::json!({
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
                format!("ERR:Send failed: {e}")
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
        // The stash is persisted (attachments.plist) and reloaded at init, so a prior
        // session is no longer a reason to miss. A miss now means the reference was
        // evicted past ATTACHMENT_STASH_CAP, the message predates persistence, or its
        // parts were never stashed.
        log::warn!(
            "nativeDownloadAttachment: NO stashed attachment for key='{key}' \
             (stash has {stash_len} entries) — reference evicted, or the message \
             predates attachment persistence. Cannot download."
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

/// OpenBubbles-style handle reconciliation. rustpush only compares IDS-vended handles
/// against registered ones REACTIVELY (on an IDS command-66 push; see identity_manager).
/// This is the PROACTIVE form: if `get_possible_handles()` (what Apple says this account
/// can use) differs from `get_handles()` (what we've actually registered), force a
/// reregister to adopt the delta. Run on login and on the app's reconnect/foreground/
/// renewal triggers so a handle Apple vends without a push (e.g. a phone number missing
/// from the first id-get-handles) is still picked up. Handles are then read live from
/// rustpush (get_handles) wherever needed — nothing is cached. Mirrors OB's
/// `if real_handles != my_handles { refresh }`.
async fn reconcile_handles(client: &Arc<IMClient>) -> Vec<String> {
    let my: std::collections::HashSet<String> =
        client.identity.get_handles().await.into_iter().collect();
    match client.identity.get_possible_handles().await {
        Ok(possible) if possible != my => {
            log::info!(
                "reconcile: IDS vends handles we haven't registered (possible={possible:?} registered={my:?}) — reregistering"
            );
            if let Err(e) = client.identity.refresh_now().await {
                log::warn!("reconcile: reregister failed: {e}");
            }
        }
        Ok(_) => log::info!("reconcile: registered handles already match IDS"),
        Err(e) => log::warn!("reconcile: get_possible_handles failed ({e}) — keeping current handles"),
    }
    client.identity.get_handles().await
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

/// Record that conversation `chat_guid` transmits from `handle`. No-op if unchanged;
/// persists to disk on a change. Called from the receive loop (learning from synced
/// sent-messages) and after a successful send (pinning a thread to its handle).
fn remember_thread_handle(chat_guid: &str, handle: &str) {
    if chat_guid.is_empty() || handle.is_empty() {
        return;
    }
    let (dir, snapshot) = {
        let mut s = st();
        if s.thread_handles.get(chat_guid).map(String::as_str) == Some(handle) {
            return; // unchanged — skip the disk write
        }
        s.thread_handles.insert(chat_guid.to_string(), handle.to_string());
        (s.files_dir.clone(), s.thread_handles.clone())
    };
    save_thread_handles(&dir, &snapshot);
}

/// The handle an EXISTING conversation already transmits from, if we know it AND it
/// is still a currently-registered handle. `None` → the caller falls back to the
/// global "start new messages from" default (i.e. this is a brand-new thread).
fn thread_send_handle(chat_guid: &str, handles: &[String]) -> Option<String> {
    let h = st().thread_handles.get(chat_guid).cloned()?;
    handles.iter().any(|x| x == &h).then_some(h)
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

/// Build the green-SMS route from my own account's phone handle, or a user-facing
/// reason we can't. Shared by `decide_route`'s "confirmed not on iMessage" and
/// "couldn't check" paths.
fn sms_route(my_handles: &[String]) -> Route {
    // Forward from my own phone number (the tel: handle on my account).
    match my_handles.iter().find(|h| h.starts_with("tel:")).cloned() {
        Some(number) => Route::Sms { using_number: number },
        None => Route::Block("Your iCloud account has no phone number to text from.".to_string()),
    }
}

async fn decide_route(client: &IMClient, handle: &str, recipient: &str) -> Route {
    let is_phone = recipient.starts_with("tel:");
    let sms_active = SMS_ACTIVE.load(Ordering::SeqCst);
    let targets = vec![recipient.to_string()];
    let valid = match client
        .identity
        .validate_targets(&targets, "com.apple.madrid", handle)
        .await
    {
        Ok(v) => v,
        Err(e) => {
            // Couldn't check iMessage availability (network/IDS hiccup). For a phone
            // number whose SMS forwarding we KNOW is live, a green send WILL land —
            // so try SMS rather than a doomed iMessage encrypt that fails
            // NoValidTargets and surfaces as "Not Delivered". For an email, or with
            // forwarding off, keep the old "assume iMessage" default so a transient
            // blip doesn't misroute a real iMessage contact to a text.
            if is_phone && sms_active {
                log::warn!("decide_route[{recipient}]: validate failed ({e:?}) — forwarding on, routing SMS");
                return sms_route(&client.identity.get_handles().await);
            }
            log::warn!("decide_route[{recipient}]: validate failed ({e:?}); defaulting to iMessage");
            return Route::IMessage;
        }
    };
    let on_imessage = valid.iter().any(|t| t == recipient);
    log::info!(
        "decide_route[{recipient}]: on_imessage={on_imessage} is_phone={is_phone} \
         sms_active={sms_active} valid={valid:?}"
    );
    if on_imessage {
        return Route::IMessage;
    }
    // Not on iMessage. Only phone numbers can fall back to SMS.
    if !is_phone {
        return Route::Block("This address isn't on iMessage, and only phone numbers can receive a text.".to_string());
    }
    if !sms_active {
        return Route::Block(
            "SMS forwarding isn't on. On your iPhone (same Apple ID): Settings ▸ Messages ▸ Text Message Forwarding ▸ turn on all devices ▸ restart iPhone."
                .to_string(),
        );
    }
    sms_route(&client.identity.get_handles().await)
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

/// Seed the identity of an app-CREATED group so its sends thread correctly. The Kotlin
/// side generates a fresh gid, forms the guid ("iMessage;+;<gid>"), and calls this with
/// the comma-separated member addresses + optional name. Stored in group_meta so every
/// outbound (text/attachment/tapback/read) replays this gid + members + name — exactly
/// as if the group had been learned from an inbound message.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeRegisterGroup<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    participants_csv: JString<'l>,
    name: JString<'l>,
) {
    let chat = jstr(&mut env, &chat_guid);
    let csv = jstr(&mut env, &participants_csv);
    let name = jstr(&mut env, &name);
    let members: Vec<String> =
        csv.split(',').map(|s| canon(s)).filter(|s| !s.is_empty()).collect();
    let cv_name = if name.trim().is_empty() { None } else { Some(name) };
    log::info!("nativeRegisterGroup: chat={chat} members={members:?} name={cv_name:?}");
    remember_group_meta(&chat, &members, &cv_name);
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
        // React from the handle this thread is on (a tapback under a different
        // self-handle would fork the conversation just like a reply would).
        let Some(handle) = thread_send_handle(&chat, &handles).or_else(|| pick_send_handle(&handles)) else {
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
            // Replay the group's real gid + members + name (or the 1:1 recipient) so the
            // tapback lands on the SAME conversation as the message it reacts to.
            conv_data_for(&chat),
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
    // Bounded drain — take at most POLL_BATCH_MAX, leaving the rest queued for the
    // next poll. See POLL_BATCH_MAX for why draining everything at once was the main
    // cause of the catch-up freeze. The transport loops immediately while batches come
    // back full (see NativeRustPushTransport.startPolling), so this costs no throughput.
    check_push_cert_rejected();
    let drained: Vec<serde_json::Value> = st().inbound.drain_batch(inbound::POLL_BATCH_MAX);
    out(&mut env, serde_json::to_string(&drained).unwrap_or_else(|_| "[]".into()))
}

/// How long APS has to stay stuck on a REFUSED connect before we tell the user their
/// push certificate is dead. Wall-clock, not a retry count, because the retry cadence
/// is rustpush's business and changes with its backoff. Five minutes is far past any
/// tunnel, lift, or dead spot.
const PUSH_CERT_BAD_FOR_MS: u64 = 5 * 60 * 1000;

/// When APS first entered the refused state, 0 = it isn't in it.
static PUSH_CERT_BAD_SINCE_MS: AtomicU64 = AtomicU64::new(0);

/// True once we've raised the modal for the CURRENT episode, so the user is told once
/// rather than twice a second. Cleared when APS connects, so a device that breaks again
/// later is told again.
static PUSH_CERT_ANNOUNCED: AtomicBool = AtomicBool::new(false);

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Surface a refused APS connect to the user, the way OpenBubbles does.
///
/// ## Where this reads from, and why it isn't a rustpush change
///
/// rustpush already publishes exactly this: `ResourceManager::resource_state` is a
/// public `watch` channel carrying `ResourceState::Failed(ResourceFailure { error, .. })`,
/// and `error` is the `PushError` — here `APSConnectError(status)`, whose Display text
/// is the "You need to re-setup your device" message. `ResourceState` and
/// `ResourceFailure` are both re-exported from rustpush's crate root. That channel is
/// how OpenBubbles learns about this, and it is why OB can show the error with no
/// library patch at all. We were already subscribed to it for logging
/// (`spawn_apsstate_watcher`) and simply never acted on it.
///
/// ## Why a state READ rather than the existing watcher
///
/// `spawn_apsstate_watcher` consumes transitions. In the 2026-08-04 field bundle its
/// lines stopped entirely while rejections continued for 1h53m — the capture had 14
/// holes so that is not conclusive, but a missed edge is unrecoverable and this is the
/// signal that decides whether a customer is told their phone is broken. Reading the
/// CURRENT state each poll cannot miss an edge, and `nativePollEvents` is driven by
/// Kotlin every 500ms.
///
/// ## Why wall-clock, and why only APSConnectError
///
/// A refused connect is not a bad network — it means Apple accepted the socket and
/// rejected the credentials. Failures with any other error (DNS, socket, timeout) reset
/// the clock, so a tunnel never raises this. Only a sustained refusal does.
fn check_push_cert_rejected() {
    let Some(conn) = st().connection.clone() else {
        PUSH_CERT_BAD_SINCE_MS.store(0, Ordering::SeqCst);
        return;
    };
    let state = conn.resource_state.subscribe().borrow().clone();
    let refused = matches!(
        &state,
        ResourceState::Failed(f) if matches!(&*f.error, PushError::APSConnectError(_))
    );

    if refused {
        let now = now_ms();
        let since = PUSH_CERT_BAD_SINCE_MS.load(Ordering::SeqCst);
        if since == 0 {
            PUSH_CERT_BAD_SINCE_MS.store(now, Ordering::SeqCst);
        } else if now.saturating_sub(since) >= PUSH_CERT_BAD_FOR_MS
            && !PUSH_CERT_ANNOUNCED.swap(true, Ordering::SeqCst)
        {
            log::error!(
                "PUSH CERT REJECTED: Apple has refused this device's APS connect for \
                 {}s — the socket transmits but subscribed to nothing, so sends look \
                 fine and NOTHING is delivered. Surfacing the re-setup modal. state={}",
                now.saturating_sub(since) / 1000,
                match &state {
                    ResourceState::Failed(f) => f.error.to_string(),
                    _ => "?".to_string(),
                }
            );
            st().queue_event(serde_json::json!({ "type": "push_cert_rejected", "rejected": true }));
        }
        return;
    }

    // Anything that is not a refusal clears the clock: Generated (healthy), or a
    // Failed carrying a network error, which is a bad link and not a dead certificate.
    // Generating is the gap between retries and must NOT clear it, or a device that
    // alternates Failed → Generating → Failed never accumulates any elapsed time.
    if matches!(state, ResourceState::Generating) {
        return;
    }
    PUSH_CERT_BAD_SINCE_MS.store(0, Ordering::SeqCst);
    if PUSH_CERT_ANNOUNCED.swap(false, Ordering::SeqCst) {
        // warn!, not info!: the launcher's hourly snapshot filterspec ends in `*:W` and
        // does not allow-list SmartTxtRust at :I, so an info! here would be missing from
        // exactly the bundle where "did the logout fix it?" gets answered.
        log::warn!("PUSH CERT RECOVERED: APS connected — clearing the re-setup modal");
        st().queue_event(serde_json::json!({ "type": "push_cert_rejected", "rejected": false }));
    }
}

/// Tell the native side which message guids the app ALREADY has on disk, so the
/// backlog Apple replays on every APS connect is dropped instead of re-delivered
/// (see `AppState.seen_guids`). `guids_json` is a JSON array of strings.
///
/// Kotlin calls this from its cache restore, BEFORE connecting — anything not in
/// the seed is treated as new, so a missing/corrupt cache simply means nothing is
/// suppressed and the replay rebuilds the history.
#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_smarttxt_RustPushNative_nativeSeedSeen(
    mut env: JNIEnv,
    _class: JClass,
    guids_json: JString,
) {
    let raw = jstr(&mut env, &guids_json);
    let guids: Vec<String> = serde_json::from_str(&raw).unwrap_or_default();
    let mut s = st();
    for g in guids {
        if !g.is_empty() {
            s.seen_guids.insert(g);
        }
    }
    log::info!("nativeSeedSeen: {} guid(s) already stored by the app", s.seen_guids.len());
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
