//! The FaceTime JNI surface, minus the pipeline behind it.
//!
//! # Why this file exists
//!
//! `facetime_av.rs` is compiled only with the `facetime-av` feature, because its
//! AAudio / MediaCodec / ANativeWindow imports are API 28+ and Android resolves
//! every relocation at `dlopen` — one unresolvable symbol rejects the whole
//! library, so a FaceTime pipeline nobody asked for took iMessage down with it on
//! the API 27 HIT slider.
//!
//! But `facetime_av.rs` also owns five JNI entry points:
//!
//! ```text
//! Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_createAvc
//!                                                                   _destroyAvc
//!                                                                   _enableCamera
//!                                                                   _enableMicrophone
//!                                                                   _refreshSurfaces
//! ```
//!
//! Gating the module removed those too, which traded one failure for another: the
//! library loads on API 27, and then `FaceTimeInCallService` throws
//! `UnsatisfiedLinkError` the moment it is touched. Measured 2026-09-04 against the
//! shipped `v6.23.0-beta.10` build — its `.so` exports exactly these five symbols
//! more than a `FACETIME=0` build of the same tree, which is why
//! `setup-hit-phone.sh` refused to swap the clean library in.
//!
//! The JNI *signalling* surface and the AV *media* pipeline are separate things,
//! and only the second one needs API 28. So the entry points stay compiled in
//! unconditionally, as no-ops, and the feature gates only the pipeline. That is
//! what makes `FACETIME=0` usable for a unified APK that has to satisfy the lowest
//! handset in the fleet.
//!
//! # What a caller sees
//!
//! `createAvc` returns 0, which is the SAME value the real implementation returns
//! when there is no FaceTime client up (`crate::st().facetime == None`) — so the
//! Kotlin side already has to handle it, and this is not a new state for it to
//! learn. The other four take an `avc` handle they will never legitimately hold
//! and do nothing with it. Every one of them logs once at warn level, so a
//! FaceTime call attempted on a build without the pipeline says so in logcat
//! rather than failing silently.
//!
//! **Nothing here dereferences `avc`.** The real implementations reconstitute an
//! `Arc<FaceTimeNativeState>` from that `jlong`; doing that against a pointer this
//! build never handed out would be a straight use-after-free, and a crash is worse
//! than a call that does not connect.

use jni::{
    objects::{JObject, JString},
    sys::{jboolean, jlong},
    JNIEnv,
};
use log::warn;

const NO_PIPELINE: &str =
    "FaceTime AV pipeline is not in this build (built with FACETIME=0 for API 27); \
     in-call audio and video are unavailable";

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_createAvc(
    _env: JNIEnv,
    _object: JObject,
    _uuid: JString,
) -> jlong {
    warn!("createAvc: {NO_PIPELINE}");
    // 0 == "no session", exactly as the real one returns with no FaceTime client.
    0
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_destroyAvc(
    _env: JNIEnv,
    _object: JObject,
    _avc: jlong,
) {
    warn!("destroyAvc: {NO_PIPELINE}");
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_enableCamera(
    _env: JNIEnv,
    _object: JObject,
    _avc: jlong,
    _enabled: jboolean,
) {
    warn!("enableCamera: {NO_PIPELINE}");
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_enableMicrophone(
    _env: JNIEnv,
    _object: JObject,
    _avc: jlong,
    _enabled: jboolean,
) {
    warn!("enableMicrophone: {NO_PIPELINE}");
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_refreshSurfaces(
    _env: JNIEnv,
    _object: JObject,
    _avc: jlong,
) {
    warn!("refreshSurfaces: {NO_PIPELINE}");
}
