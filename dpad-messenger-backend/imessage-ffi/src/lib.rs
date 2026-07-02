//! JNI wrapper over OpenBubbles' rustpush — the `libimessage_ffi.so` that
//! `RustPushNative.kt` loads (IMESSAGE_NATIVE_BACKEND_PLAN.md §3).
//!
//! The JNI plumbing here is complete and correct; the lines marked `RUSTPUSH:`
//! are the integration points where rustpush's API is called. They're written
//! against rustpush's documented shape (OSConfig/MacOSConfig, APNSConnection,
//! IDSUser/register, IMClient send/recv) and left as `todo!()`-style stubs so
//! the crate compiles standalone; fill them in against your pinned rustpush rev
//! and the `.so` is done.
//!
//! Design:
//!  - one global tokio runtime + a Mutex<Option<Client>> holding the live IDS
//!    client, mirroring how the gmessages session client is a process singleton;
//!  - inbound messages are pushed onto a queue that `nativePollEvents` drains as
//!    relay-wire-shaped JSON (so the Kotlin parser is identical to the relay's).

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::Mutex;
use tokio::runtime::Runtime;

static RUNTIME: OnceCell<Runtime> = OnceCell::new();
static STATE: OnceCell<Mutex<AppState>> = OnceCell::new();

#[derive(Default)]
struct AppState {
    files_dir: String,
    connected: bool,
    /// Relay-wire-shaped event JSON objects waiting to be drained by Kotlin.
    inbound: VecDeque<serde_json::Value>,
    // RUSTPUSH: hold the live client here, e.g.
    //   client: Option<rustpush::IMClient>,
    //   conn: Option<rustpush::APNSConnection>,
}

fn rt() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("tokio runtime"))
}
fn state() -> &'static Mutex<AppState> {
    STATE.get_or_init(|| Mutex::new(AppState::default()))
}

#[derive(Deserialize)]
#[allow(non_snake_case)]
struct HardwareConfig {
    platformSerialNumber: String,
    mlb: String,
    rom: String,
    productName: String,
    osBuildNum: String,
}
#[derive(Deserialize)]
#[allow(non_snake_case)]
struct MacOSConfig {
    inner: HardwareConfig,
    version: String,
    deviceId: String,
}

#[derive(Serialize)]
struct RegisterOk {
    handles: Vec<String>,
}
#[derive(Serialize)]
struct RegisterErr {
    error: String,
}

fn init_logger() {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
) -> jboolean {
    init_logger();
    let dir: String = match env.get_string(&files_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let mut st = state().lock().unwrap();
    st.files_dir = dir;
    // RUSTPUSH: restore persisted IDS identity / keys from files_dir here.
    log::info!("nativeInit dir={}", st.files_dir);
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeConnect(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let ok = rt().block_on(async {
        // RUSTPUSH: open the APNs courier socket, e.g.
        //   let conn = APNSConnection::connect(...).await?;
        // store it in AppState and start the receive loop that pushes inbound
        // messages onto AppState.inbound as relay-wire JSON.
        true
    });
    let mut st = state().lock().unwrap();
    st.connected = ok;
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeIsConnected(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if state().lock().unwrap().connected { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut st = state().lock().unwrap();
    st.connected = false;
    // RUSTPUSH: drop the APNs connection.
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeAuthenticate<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    apple_id: JString<'l>,
    _password: JString<'l>,
) -> jstring {
    let apple: String = env.get_string(&apple_id).map(Into::into).unwrap_or_default();
    let result = rt().block_on(async move {
        // RUSTPUSH: AppleAccount::login(appleid_closure, tfa_closure, gsa_config,
        // anisette). The 2FA closure should block on a channel whose sender is
        // stored in AppState and fired by nativeSubmit2fa; return "needs_2fa"
        // here and complete the login there. See the ../../imessage-register
        // harness for the exact call sequence. Stub: pretend success.
        log::info!("nativeAuthenticate apple={} (stub)", apple);
        serde_json::json!({ "status": "ok" }).to_string()
    });
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeSubmit2fa<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    code: JString<'l>,
) -> jstring {
    let _code: String = env.get_string(&code).map(Into::into).unwrap_or_default();
    // RUSTPUSH: send `code` into the 2FA channel opened by nativeAuthenticate,
    // then await login completion + collect the IDS delegate/auth cert.
    let result = serde_json::json!({ "status": "ok" }).to_string();
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeRegister<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    config_json: JString<'l>,
    apple_id: JString<'l>,
    validation_data: JByteArray<'l>,
) -> jstring {
    let cfg_s: String = env.get_string(&config_json).map(Into::into).unwrap_or_default();
    let apple: String = env.get_string(&apple_id).map(Into::into).unwrap_or_default();
    let validation: Vec<u8> = env.convert_byte_array(&validation_data).unwrap_or_default();

    let result_json = match serde_json::from_str::<MacOSConfig>(&cfg_s) {
        Err(e) => serde_json::to_string(&RegisterErr { error: format!("bad config: {e}") }).unwrap(),
        Ok(cfg) => rt().block_on(async move {
            // RUSTPUSH: the four-call sequence (§2.2) with relay-supplied
            // validation bytes instead of local absinthe:
            //   1. activate(&os_config)                        -> push cert
            //   2. authenticate_apple(&apple, password/2fa)    -> account tokens
            //   3. (validation already provided as `validation`)
            //   4. register(validation, cert, tokens)          -> IDSUser
            // then persist the identity and return its handles.
            let _ = (&cfg.version, &cfg.deviceId, &cfg.inner.rom, validation.len());
            log::info!("nativeRegister apple={} validation={}B (stub)", apple, validation.len());
            serde_json::to_string(&RegisterOk {
                handles: vec![format!("mailto:{apple}")],
            })
            .unwrap()
        }),
    };
    env.new_string(result_json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeSendText<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    text: JString<'l>,
    temp_guid: JString<'l>,
    reply_to: JString<'l>,
) -> jstring {
    let chat: String = env.get_string(&chat_guid).map(Into::into).unwrap_or_default();
    let body: String = env.get_string(&text).map(Into::into).unwrap_or_default();
    let _tmp: String = env.get_string(&temp_guid).map(Into::into).unwrap_or_default();
    let _reply: String = env.get_string(&reply_to).map(Into::into).unwrap_or_default();
    let guid = rt().block_on(async move {
        // RUSTPUSH: build an iMessage with parsed recipients from `chat` and
        // send it over the IDS identity; return the message guid.
        log::info!("nativeSendText chat={} len={} (stub)", chat, body.len());
        format!("native_{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0))
    });
    env.new_string(guid).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativeSendTapback<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    chat_guid: JString<'l>,
    target_guid: JString<'l>,
    associated_message_type: jni::sys::jint,
) -> jboolean {
    let _chat: String = env.get_string(&chat_guid).map(Into::into).unwrap_or_default();
    let _target: String = env.get_string(&target_guid).map(Into::into).unwrap_or_default();
    // RUSTPUSH: send a tapback with `associated_message_type` (BlueBubbles code).
    log::info!("nativeSendTapback type={} (stub)", associated_message_type);
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_offline_dpadmessenger_backend_imessage_RustPushNative_nativePollEvents(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let drained: Vec<serde_json::Value> = {
        let mut st = state().lock().unwrap();
        st.inbound.drain(..).collect()
    };
    let json = serde_json::to_string(&drained).unwrap_or_else(|_| "[]".into());
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
