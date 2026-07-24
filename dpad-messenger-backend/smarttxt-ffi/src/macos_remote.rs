//! Lives in the APP, not in rustpush.
//!
//! Decoding the OpenBubbles `dumb` file and minting a client UDID are application
//! concerns: OpenBubbles does both in its own FFI layer (`mac_hw_info.proto` plus
//! `config_from_encoded` / `generate_udid` in its `api.rs`) and never asks rustpush
//! to parse a hardware file. Rust's orphan rule lets us implement rustpush's
//! `OSConfig` trait on our own local type, so the whole thing sits here and the
//! rustpush fork stays close to upstream.
//!
//! `MacOSConfigRemote` — a copy of rustpush's `MacOSConfig` whose ONLY
//! difference is how validation data is produced: instead of local absinthe
//! (`ValidationCtx`), it drives a self-hosted **NAC validation server**. Every
//! other field/method is identical to `MacOSConfig`, so the device it describes
//! stays perfectly consistent with its dumb file (same identity → Apple accepts
//! it, exactly like OpenBubbles).
//!
//! `generate_validation_data()` flow (see the NAC Validation API):
//!   1. Fetch Apple's validation certificate chain.
//!   2. POST the raw hardware-config body (the OpenBubbles **dumb file**) to
//!      `POST {NAC_BASE_URL}/nac/create`, with the cert chain in the
//!      `X-Absinthe-Cert-Chain` header. → `{ id, sign_url, request }`.
//!   3. base64-decode `request` (an OpenAbsinthe session-info-request).
//!   4. Send it to Apple's validation-initialize endpoint → `session-info`.
//!   5. POST that `session-info` to the returned `sign_url`.
//!   6. The sign response body IS the validation data.
//!
//! The NAC server and the anisette-v3 server are the same host (`NAC_BASE_URL`,
//! http on the LAN).

use std::{collections::HashMap, time::{Duration, SystemTime}};

use async_trait::async_trait;
use base64::Engine;
use plist::{Data, Dictionary, Value};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// Everything here comes from rustpush's PUBLIC surface — this file deliberately
// reaches into none of its internals. `REQWEST` in particular is rustpush's shared
// client with Apple's pinned root certs; re-creating it here would duplicate the
// pinning, which is exactly the kind of divergence this move exists to prevent.
use rustpush::{
    base64_decode, base64_encode, encode_hex, get_bag, plist_to_buf, ActivationInfo, DebugMeta,
    OSConfig, PushError, RegisterMeta, IDS_BAG, REQWEST,
};
use prost::Message;
use rand::Rng;

/// Generated from `mac_hw_info.proto` — OpenBubbles' authoritative schema for the
/// `dumb` file. Decoding against the real schema (rather than reading field numbers
/// off a hexdump) is what keeps `rom` = field 11 and `io_mac_address` = field 2
/// straight; they are DIFFERENT bytes on genuine hardware.
pub mod bbhwinfo {
    include!(concat!(env!("OUT_DIR"), "/bbhwinfo.rs"));
}

/// The subset of the Mac hardware identity the **remote** NAC path needs.
///
/// Deliberately self-contained and NOT `open_absinthe::nac::HardwareConfig`:
/// the remote path must not pull in open-absinthe (or unicorn) at all. It
/// deserializes from the very same on-disk plist OpenBubbles wrote — open-absinthe
/// serialized its byte fields with serde's `serialize_bytes` (a plist `<data>`
/// element), which `plist::Data` reads back identically. Extra keys present in
/// that dictionary (`io_mac_address`, `platform_uuid`, the `*_enc` variants, …)
/// are the ones the local emulator needed and the remote server doesn't, so
/// serde simply ignores them here.
#[derive(Serialize, Deserialize, Clone)]
pub struct HardwareConfig {
    pub product_name: String,
    pub platform_serial_number: String,
    pub os_build_num: String,
    pub mlb: String,
    /// ROM bytes, stored as a plist `<data>` element (matches open-absinthe).
    pub rom: Data,
}

/// NAC validation server (also serves anisette v3). Public host, https.
pub const NAC_BASE_URL: &str = "https://hw.openbubbles.app";

/// Where the raw hardware-config body is read from when not supplied inline —
/// the OpenBubbles dumb file. Overridable at runtime with `SMARTTXT_DUMB_PATH`.
pub const DEFAULT_DUMB_PATH: &str = "/data/data/com.openbubbles.messaging/files/dumb";

#[derive(Serialize, Deserialize, Clone)]
pub struct MacOSConfigRemote {
    pub inner: HardwareConfig,

    // software
    pub version: String,
    pub protocol_version: u32,
    pub device_id: String,
    pub icloud_ua: String,
    pub aoskit_version: String,
    pub udid: Option<String>,

    /// Raw hardware-config body for `POST /nac/create` (first 5 bytes ignored +
    /// protobuf `HwInfo`). When `None`, it's read from the dumb file
    /// ([`DEFAULT_DUMB_PATH`] / `SMARTTXT_DUMB_PATH`).
    #[serde(default)]
    pub hw_config: Option<Data>,
}

#[derive(Serialize)]
#[serde(rename_all = "kebab-case")]
struct SessionInfoRequest {
    session_info_request: Data,
}

#[derive(Deserialize)]
#[serde(rename_all = "kebab-case")]
struct SessionInfoResponse {
    session_info: Data,
}

#[derive(Deserialize)]
struct CertsResponse {
    cert: Data,
}

/// `POST /nac/create` response (extra fields like `id`/`expires_at` are ignored).
#[derive(Deserialize)]
struct NacCreateResponse {
    /// e.g. `/nac/<session id>/sign`
    sign_url: String,
    /// base64 OpenAbsinthe session-info-request bytes.
    request: String,
}

/// A fresh client UDID, the same shape OpenBubbles generates
/// (`openbubbles-app/rust/src/api/api.rs::generate_udid`): 32 random bytes, hex,
/// uppercase.
///
/// Deliberately NOT derived from the Mac's platform UUID. On real hardware the
/// client UDID and `IOPlatformUUID` are independent values; reusing one for both
/// asserts an equality genuine hardware never asserts, and makes the resulting
/// `X-Client-UDID` (see rustpush `auth.rs`, sent on every GSA request) a
/// dashed UUID where every OpenBubbles client sends 64 hex chars — a one-regex
/// fleet classifier. This keeps the two indistinguishable.
pub fn generate_udid() -> String {
    let udid: [u8; 32] = rand::thread_rng().gen();
    encode_hex(&udid).to_uppercase()
}

/// True when `udid` already has the OpenBubbles shape (64 hex chars). Used to
/// migrate installs that persisted the old platform-UUID-derived value exactly
/// once, then leave it alone.
pub fn is_openbubbles_shaped_udid(udid: &str) -> bool {
    udid.len() == 64 && udid.chars().all(|c| c.is_ascii_hexdigit())
}

impl MacOSConfigRemote {
    /// Build a full config from the OpenBubbles `dumb` body: a 5-byte "OABS\0"
    /// header followed by a `bbhwinfo.HwInfo` protobuf. Field numbers come from
    /// `mac_hw_info.proto` — OpenBubbles' authoritative schema — so `rom` is
    /// field 11 and is never confused with `io_mac_address` (field 2).
    ///
    /// `hw_config` keeps the full body so `/nac/create` still receives the exact
    /// bytes it expects.
    pub fn from_dumb_body(body: &[u8]) -> Result<MacOSConfigRemote, PushError> {
        fn bad(m: String) -> PushError {
            PushError::IoError(std::io::Error::new(std::io::ErrorKind::InvalidData, m))
        }
        if body.len() <= 5 {
            return Err(bad(format!(
                "dumb body is {} B — too short for the 5-byte header + HwInfo",
                body.len()
            )));
        }
        let hw_info = bbhwinfo::HwInfo::decode(&body[5..])
            .map_err(|e| bad(format!("dumb: protobuf decode failed: {e}")))?;
        let inner = hw_info
            .inner
            .ok_or_else(|| bad("dumb: no nested HwInfo (field #1)".into()))?;

        // proto3 yields empty defaults rather than errors, so the fields we
        // genuinely require are checked explicitly.
        let require = |v: String, what: &str| -> Result<String, PushError> {
            if v.is_empty() { Err(bad(format!("dumb: missing {what}"))) } else { Ok(v) }
        };
        if inner.rom.is_empty() {
            return Err(bad("dumb: missing rom (field #11)".into()));
        }

        let device_id = require(hw_info.device_id, "device UUID")?;
        Ok(MacOSConfigRemote {
            inner: HardwareConfig {
                product_name: require(inner.product_name, "product_name")?,
                platform_serial_number: require(inner.platform_serial_number, "serial")?,
                os_build_num: require(inner.os_build_num, "build number")?,
                mlb: require(inner.mlb, "mlb")?,
                // Field 11. NOT io_mac_address (field 2) — verified distinct on a
                // real MacBookPro16,2 dump (f2 147dda3f5864 vs f11 8e04522f5881).
                rom: inner.rom.into(),
            },
            version: require(hw_info.version, "macOS version")?,
            protocol_version: if hw_info.protocol_version == 0 {
                1640
            } else {
                hw_info.protocol_version as u32
            },
            icloud_ua: require(hw_info.icloud_ua, "iCloud UA")?,
            aoskit_version: require(hw_info.aoskit_version, "AOSKit version")?,
            // NOT device_id — see generate_udid(). Callers persist this so it is
            // stable across launches; a UDID that changes every start would look
            // like a brand-new device to Apple on every sign-in.
            udid: Some(generate_udid()),
            device_id,
            hw_config: Some(body.to_vec().into()),
        })
    }

    /// Diagnostic: the two 6-byte fields that were historically confused —
    /// `rom` (field 11, the one that belongs in `X-Apple-I-ROM`) and
    /// `io_mac_address` (field 2). Both are decoded straight from the stored dumb
    /// body with the real schema, so a log line can PROVE which one the GSA
    /// header is carrying rather than just printing a value.
    ///
    /// Returns `(rom_hex, io_mac_hex)`, lowercase, matching
    /// [`OSConfig::get_gsa_hardware_headers`]'s encoding.
    pub fn rom_and_io_mac_hex(&self) -> Option<(String, String)> {
        let body: Vec<u8> = self.hw_config.clone()?.into();
        if body.len() <= 5 {
            return None;
        }
        let inner = bbhwinfo::HwInfo::decode(&body[5..]).ok()?.inner?;
        Some((encode_hex(&inner.rom), encode_hex(&inner.io_mac_address)))
    }

    /// The raw hardware-config bytes for `/nac/create`: the inline `hw_config` if
    /// set, else the OpenBubbles dumb file. OpenBubbles stores it base64-encoded
    /// (like OABS); we decode it, falling back to the raw bytes if it isn't base64.
    fn hw_config_body(&self) -> Result<Vec<u8>, PushError> {
        if let Some(d) = &self.hw_config {
            let v: Vec<u8> = d.clone().into();
            if !v.is_empty() {
                return Ok(v);
            }
        }
        let path = std::env::var("SMARTTXT_DUMB_PATH").unwrap_or_else(|_| DEFAULT_DUMB_PATH.to_string());
        let raw = std::fs::read(&path).map_err(|e| {
            PushError::IoError(std::io::Error::new(std::io::ErrorKind::Other, format!("read dumb file {path}: {e}")))
        })?;
        let txt = String::from_utf8_lossy(&raw);
        match base64::engine::general_purpose::STANDARD.decode(txt.trim()) {
            Ok(decoded) if !decoded.is_empty() => Ok(decoded),
            _ => Ok(raw),
        }
    }
}

#[async_trait]
impl OSConfig for MacOSConfigRemote {
    fn build_activation_info(&self, csr: Vec<u8>) -> ActivationInfo {
        ActivationInfo {
            activation_randomness: Uuid::new_v4().to_string().to_uppercase(),
            activation_state: "Unactivated",
            build_version: self.inner.os_build_num.clone(),
            device_cert_request: csr.into(),
            device_class: "MacOS".to_string(),
            product_type: self.inner.product_name.clone(),
            product_version: self.version.clone(),
            serial_number: self.inner.platform_serial_number.clone(),
            unique_device_id: self.device_id.clone().to_uppercase(),
        }
    }

    fn get_udid(&self) -> String {
        self.udid.clone().expect("missing udid!")
    }

    fn get_normal_ua(&self, item: &str) -> String {
        let part = self.icloud_ua.split_once(char::is_whitespace).unwrap().0;
        format!("{item} {part}")
    }

    fn get_aoskit_version(&self) -> String {
        self.aoskit_version.clone()
    }

    fn get_mme_clientinfo(&self, for_item: &str) -> String {
        format!("<{}> <macOS;{};{}> <{}>", self.inner.product_name, self.version, self.inner.os_build_num, for_item)
    }

    fn get_version_ua(&self) -> String {
        format!("[macOS,{},{},{}]", self.version, self.inner.os_build_num, self.inner.product_name)
    }

    fn get_activation_device(&self) -> String {
        "MacOS".to_string()
    }

    fn get_device_uuid(&self) -> String {
        self.device_id.clone()
    }

    fn get_device_name(&self) -> String {
        format!("Mac-{}", self.inner.platform_serial_number)
    }

    async fn generate_validation_data(&self) -> Result<Vec<u8>, PushError> {
        // Every network hop below is bounded (NET_TIMEOUT) so a stuck NAC LAN server
        // or Apple endpoint fails with an error instead of hanging the whole
        // registration forever. Each step is logged with elapsed time so a hang is
        // pinpointed in logcat (tag `smarttxt_ffi`/`rustpush`).
        const NET_TIMEOUT: Duration = Duration::from_secs(20);
        let t0 = std::time::Instant::now();
        log::info!("nac: generating validation data (server {NAC_BASE_URL})");

        // 1. Apple's validation certificate chain.
        let url = get_bag(IDS_BAG, "id-validation-cert").await?.into_string().unwrap();
        log::info!("nac: [1/5] fetching Apple validation cert chain…");
        let key = REQWEST.get(url).timeout(NET_TIMEOUT).send().await
            .map_err(|e| { log::error!("nac: [1/5] cert-chain fetch failed: {e}"); e })?;
        let response: CertsResponse = plist::from_bytes(&key.bytes().await?)?;
        let certs: Vec<u8> = response.cert.into();
        log::info!("nac: [1/5] cert chain ok ({} bytes, {:?})", certs.len(), t0.elapsed());

        // 2. Create a NAC session: POST the raw hardware-config body (dumb file)
        //    with the cert chain in X-Absinthe-Cert-Chain.
        let body = self.hw_config_body()?;
        log::info!("nac: [2/5] POST {NAC_BASE_URL}/nac/create (hw body {} bytes)…", body.len());
        let create = REQWEST.post(format!("{NAC_BASE_URL}/nac/create"))
            .header("X-Absinthe-Cert-Chain", base64_encode(&certs))
            .header("Content-Type", "application/octet-stream")
            .timeout(NET_TIMEOUT)
            .body(body)
            .send().await
            .map_err(|e| { log::error!("nac: [2/5] /nac/create failed (is {NAC_BASE_URL} reachable?): {e}"); e })?;
        if !create.status().is_success() {
            let code = create.status().as_u16();
            let text = create.text().await.unwrap_or_default();
            log::error!("nac: [2/5] /nac/create HTTP {code}: {text}");
            return Err(PushError::IoError(std::io::Error::new(
                std::io::ErrorKind::Other, format!("nac/create HTTP {code}: {text}"))));
        }
        let create: NacCreateResponse = create.json().await?;
        log::info!("nac: [2/5] session created (sign_url={}, request {} chars, {:?})",
            create.sign_url, create.request.len(), t0.elapsed());

        // 3. base64-decode the session-info-request the server produced.
        let request_bytes = base64_decode(&create.request);

        // 4. Hand it to Apple's validation-initialize endpoint → session-info.
        let init = SessionInfoRequest { session_info_request: request_bytes.into() };
        let info = plist_to_buf(&init)?;
        let url = get_bag(IDS_BAG, "id-initialize-validation").await?.into_string().unwrap();
        log::info!("nac: [3/5] POST Apple id-initialize-validation…");
        let activation = REQWEST.post(url).timeout(NET_TIMEOUT).body(info).send().await
            .map_err(|e| { log::error!("nac: [3/5] Apple initialize-validation failed: {e}"); e })?;
        let response: SessionInfoResponse = plist::from_bytes(&activation.bytes().await?)?;
        let session_info: Vec<u8> = response.session_info.into();
        log::info!("nac: [4/5] Apple returned session-info ({} bytes, {:?})", session_info.len(), t0.elapsed());

        // 5. Sign: POST Apple's session-info to the session's sign_url (single-use).
        //    The response body IS the validation data.
        log::info!("nac: [5/5] POST {NAC_BASE_URL}{} (sign)…", create.sign_url);
        let signed = REQWEST.post(format!("{NAC_BASE_URL}{}", create.sign_url))
            .header("Content-Type", "application/octet-stream")
            .timeout(NET_TIMEOUT)
            .body(session_info)
            .send().await
            .map_err(|e| { log::error!("nac: [5/5] sign request failed: {e}"); e })?;
        if !signed.status().is_success() {
            let code = signed.status().as_u16();
            let text = signed.text().await.unwrap_or_default();
            log::error!("nac: [5/5] sign HTTP {code}: {text}");
            return Err(PushError::IoError(std::io::Error::new(
                std::io::ErrorKind::Other, format!("nac sign HTTP {code}: {text}"))));
        }
        let data = signed.bytes().await?.to_vec();
        log::info!("nac: ✅ validation data ready ({} bytes, total {:?})", data.len(), t0.elapsed());
        Ok(data)
    }

    fn get_protocol_version(&self) -> u32 {
        self.protocol_version
    }

    fn get_register_meta(&self) -> RegisterMeta {
        RegisterMeta {
            hardware_version: self.inner.product_name.clone(),
            os_version: format!("macOS,{},{}", self.version, self.inner.os_build_num),
            software_version: self.inner.os_build_num.clone(),
        }
    }

    fn get_debug_meta(&self) -> DebugMeta {
        DebugMeta {
            user_version: self.version.clone(),
            hardware_version: self.inner.product_name.clone(),
            serial_number: self.inner.platform_serial_number.clone(),
        }
    }

    fn get_gsa_hardware_headers(&self) -> HashMap<String, String> {
        let rom: Vec<u8> = self.inner.rom.clone().into();
        [
            ("X-Apple-I-MLB".to_string(), self.inner.mlb.clone()),
            ("X-Apple-I-ROM".to_string(), encode_hex(&rom)), // intentional lowercase
            ("X-Apple-I-SRL-NO".to_string(), self.inner.platform_serial_number.clone()),
        ].into_iter().collect()
    }

    fn get_serial_number(&self) -> String {
        self.inner.platform_serial_number.clone()
    }

    fn get_login_url(&self) -> &'static str {
        "https://setup.icloud.com/setup/signin/v2/login"
    }

    fn get_private_data(&self) -> Dictionary {
        let apple_epoch = SystemTime::UNIX_EPOCH + Duration::from_secs(978307200);
        Dictionary::from_iter([
            ("ap", Value::String("0".to_string())), // 1 for ios

            ("d", Value::String(format!("{:.6}", apple_epoch.elapsed().unwrap().as_secs_f64()))),
            ("dt", Value::Integer(1.into())),
            ("gt", Value::String("0".to_string())),
            ("h", Value::String("1".to_string())),
            ("m", Value::String("0".to_string())),
            ("p", Value::String("0".to_string())),

            ("pb", Value::String(self.inner.os_build_num.clone())),
            ("pn", Value::String("macOS".to_string())),
            ("pv", Value::String(self.version.clone())),

            ("s", Value::String("0".to_string())),
            ("t", Value::String("0".to_string())),
            ("u", Value::String(self.device_id.clone().to_uppercase())),
            ("v", Value::String("1".to_string())),
        ])
    }
}


#[cfg(test)]
mod tests {
    use super::*;

const IO_MAC: [u8; 6] = [0x14, 0x7d, 0xda, 0x3f, 0x58, 0x64];
    const ROM: [u8; 6] = [0x8e, 0x04, 0x52, 0x2f, 0x58, 0x81];
    
    fn dumb_body() -> Vec<u8> {
        let hw = bbhwinfo::HwInfo {
            inner: Some(bbhwinfo::hw_info::InnerHwInfo {
                product_name: "MacBookPro16,2".into(),
                io_mac_address: IO_MAC.to_vec(),
                platform_serial_number: "SERIALNUM123".into(),
                platform_uuid: "00000000-0000-0000-0000-000000000000".into(),
                os_build_num: "24D70".into(),
                rom: ROM.to_vec(),
                mlb: "MLB00000000000000".into(),
                ..Default::default()
            }),
            version: "15.3.1".into(),
            protocol_version: 1640,
            device_id: "00000000-0000-0000-0000-000000000000".into(),
            icloud_ua: "com.apple.iCloudHelper/282 CFNetwork/1494.0.7 Darwin/23.4.0".into(),
            aoskit_version: "com.apple.AOSKit/282".into(),
        };
        let mut body = b"OABS\0".to_vec();
        body.extend_from_slice(&hw.encode_to_vec());
        body
    }
    
    #[test]
    fn rom_comes_from_field_11_not_io_mac_address() {
        let cfg = MacOSConfigRemote::from_dumb_body(&dumb_body()).expect("parse dumb");
        let rom: Vec<u8> = cfg.inner.rom.clone().into();
        assert_eq!(rom, ROM.to_vec(), "rom must be field 11");
        assert_ne!(rom, IO_MAC.to_vec(), "rom must NOT be io_mac_address (field 2)");
    }
    
    #[test]
    fn gsa_rom_header_matches_openbubbles() {
        let cfg = MacOSConfigRemote::from_dumb_body(&dumb_body()).expect("parse dumb");
        let headers = cfg.get_gsa_hardware_headers();
        // OpenBubbles sends hex(field 11), lowercase.
        assert_eq!(headers.get("X-Apple-I-ROM").map(String::as_str), Some("8e04522f5881"));
    }
    
    #[test]
    fn other_identity_fields_round_trip() {
        let cfg = MacOSConfigRemote::from_dumb_body(&dumb_body()).expect("parse dumb");
        assert_eq!(cfg.inner.product_name, "MacBookPro16,2");
        assert_eq!(cfg.inner.os_build_num, "24D70");
        assert_eq!(cfg.version, "15.3.1");
        assert_eq!(cfg.protocol_version, 1640);
        assert_eq!(cfg.get_serial_number(), "SERIALNUM123");
    }
    
    #[test]
    fn truncated_body_is_rejected() {
        assert!(MacOSConfigRemote::from_dumb_body(b"OABS").is_err());
    }
    
    #[test]
    fn missing_rom_is_rejected() {
        let mut hw = bbhwinfo::HwInfo::decode(&dumb_body()[5..]).unwrap();
        hw.inner.as_mut().unwrap().rom.clear();
        let mut body = b"OABS\0".to_vec();
        body.extend_from_slice(&hw.encode_to_vec());
        assert!(MacOSConfigRemote::from_dumb_body(&body).is_err());
    }
    
    /// The client UDID must have OpenBubbles' shape (32 random bytes, hex, uppercase
    /// -> 64 hex chars) and must NOT be derived from the Mac's platform UUID. It
    /// leaves as `X-Client-UDID` on every GSA request; a dashed UUID there is a
    /// one-regex classifier separating this client from OpenBubbles.
    #[test]
    fn udid_is_openbubbles_shaped_and_not_the_platform_uuid() {
        let cfg = MacOSConfigRemote::from_dumb_body(&dumb_body()).expect("parse dumb");
        let udid = cfg.udid.clone().expect("udid");
        assert_eq!(udid.len(), 64, "OpenBubbles emits 32 bytes of hex");
        assert!(udid.chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(udid, udid.to_uppercase(), "OpenBubbles uppercases it");
        assert!(is_openbubbles_shaped_udid(&udid));
        assert_ne!(udid, cfg.device_id, "udid must not reuse the platform UUID");
    }
    
    #[test]
    fn generated_udids_are_distinct() {
        assert_ne!(generate_udid(), generate_udid());
    }
    
    /// The legacy value (a dashed platform UUID) must be recognised as NOT
    /// OpenBubbles-shaped, so existing installs migrate exactly once.
    #[test]
    fn legacy_dashed_uuid_is_not_openbubbles_shaped() {
        assert!(!is_openbubbles_shaped_udid("00000000-0000-0000-0000-000000000000"));
        assert!(is_openbubbles_shaped_udid(&"AB".repeat(32)));
    }
    
    /// The runtime self-check must be able to see BOTH fields, and must report them
    /// the right way round — that is what makes the ROM_CHECK log line proof rather
    /// than an assertion.
    #[test]
    fn rom_and_io_mac_diagnostic_reports_both_fields() {
        let cfg = MacOSConfigRemote::from_dumb_body(&dumb_body()).expect("parse dumb");
        let (rom_hex, mac_hex) = cfg.rom_and_io_mac_hex().expect("diagnostic");
        assert_eq!(rom_hex, "8e04522f5881", "field 11 = rom");
        assert_eq!(mac_hex, "147dda3f5864", "field 2 = io_mac_address");
        assert_ne!(rom_hex, mac_hex);
        // And the header must carry the ROM, not the MAC.
        let headers = cfg.get_gsa_hardware_headers();
        assert_eq!(headers.get("X-Apple-I-ROM").map(String::as_str), Some(rom_hex.as_str()));
        assert_ne!(headers.get("X-Apple-I-ROM").map(String::as_str), Some(mac_hex.as_str()));
    }
}
