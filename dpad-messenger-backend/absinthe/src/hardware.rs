//! The captured Mac hardware identity ("dumb file") and its OABS wire format.
//!
//! This is the input to the whole nac process. On a real Mac, Apple's
//! `IMDAppleServices` reads these identifiers out of the IORegistry / NVRAM /
//! DiskArbitration; off-device we capture them ONCE from a genuine Mac (with a
//! tool like OpenBubbles' hardware extractor / JJTech's `nacserver/extractor.m`)
//! and replay them into the emulated binary through the IOKit import hooks
//! (see `hooks.rs`). Every field here maps to exactly one IOKit read.
//!
//! Wire format (reverse-engineered, byte-exact — see `tests/oabs.rs`):
//! a protobuf message prefixed with the 5-byte magic `b"OABS\0"`. Field numbers
//! and semantics were recovered from a real Mac17,2 / macOS 15.3.1 capture and
//! cross-checked against rustpush's `MacOSConfig`/`HardwareConfig` and pypush's
//! `data.plist` IOKit key set.

use crate::error::{AbsintheError, Result};
use crate::protobuf::{Field, Reader, Writer};

pub const OABS_MAGIC: &[u8; 5] = b"OABS\0";

/// The hardware fingerprint. Field numbers match the OABS protobuf `inner`
/// message. The five oddly-named `Vec<u8>` fields are Apple's deliberately
/// obfuscated "derived iMessage keys" (17 bytes each) read from `IOPower:/`;
/// we carry them opaquely and hand them straight back to the binary.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HardwareConfig {
    /// #1 — model identifier, e.g. "Mac17,2". IOKit `product-name`.
    pub product_name: String,
    /// #2 — 6-byte NVRAM ROM (the primary interface's burned-in address).
    pub rom: Vec<u8>,
    /// #3 — `IOPlatformSerialNumber`, e.g. "HH9HTXD4C2".
    pub platform_serial_number: String,
    /// #4 — `IOPlatformUUID`.
    pub platform_uuid: String,
    /// #5 — root volume UUID (via DiskArbitration `DADiskCopyDescription`).
    pub root_disk_uuid: String,
    /// #6 — `board-id`, e.g. "Mac-22000000".
    pub board_id: String,
    /// #7 — OS build number, e.g. "24D70". Also used in client-info headers.
    pub os_build_num: String,
    /// #8 — obfuscated key `Gq3489ugfi` (17 bytes).
    pub gq3489ugfi: Vec<u8>,
    /// #9 — obfuscated key `Fyp98tpgj` (17 bytes).
    pub fyp98tpgj: Vec<u8>,
    /// #10 — obfuscated key `kbjfrfpoJU` (17 bytes).
    pub kbjfrfpoju: Vec<u8>,
    /// #11 — 6-byte primary `IOMACAddress` (read via the ethernet iterator).
    pub mac_address: Vec<u8>,
    /// #12 — obfuscated key `oycqAZloTNDm` (17 bytes).
    pub oycqazlotndm: Vec<u8>,
    /// #13 — main logic board serial (`MLB`), e.g. "J33HKR00VPU0000VLK".
    pub mlb: String,
    /// #14 — obfuscated key `abKPld1EcMni` (17 bytes).
    pub abkpld1ecmni: Vec<u8>,
}

/// The serialized `MacOSConfig` — the full dumb file. Wraps a [`HardwareConfig`]
/// plus the OS/client metadata Apple's GSA + IDS headers need.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MacOSConfig {
    /// #1 — the hardware fingerprint.
    pub inner: HardwareConfig,
    /// #2 — macOS marketing version, e.g. "15.3.1".
    pub version: String,
    /// #3 — IDS protocol version, e.g. 1640.
    pub protocol_version: u32,
    /// #4 — device id (GSA `X-Mme-Device-Id`); usually == platform_uuid.
    pub device_id: String,
    /// #5 — iCloud helper UA string.
    pub icloud_ua: String,
    /// #6 — AOSKit version string.
    pub aoskit_version: String,
    /// #7 — optional UDID (absent on many Macs).
    pub udid: Option<String>,
}

fn as_string(f: Field, name: &str) -> Result<String> {
    match f {
        Field::Bytes(b) => String::from_utf8(b)
            .map_err(|_| AbsintheError::BadHardwareConfig(format!("{name} not utf-8"))),
        Field::Varint(_) => Err(AbsintheError::BadHardwareConfig(format!(
            "{name}: expected bytes, got varint"
        ))),
    }
}

fn as_bytes(f: Field, name: &str) -> Result<Vec<u8>> {
    match f {
        Field::Bytes(b) => Ok(b),
        Field::Varint(_) => Err(AbsintheError::BadHardwareConfig(format!(
            "{name}: expected bytes, got varint"
        ))),
    }
}

fn as_u64(f: Field, name: &str) -> Result<u64> {
    match f {
        Field::Varint(v) => Ok(v),
        Field::Bytes(_) => Err(AbsintheError::BadHardwareConfig(format!(
            "{name}: expected varint, got bytes"
        ))),
    }
}

impl HardwareConfig {
    /// Decode the inner `HardwareConfig` protobuf message body.
    pub fn decode(buf: &[u8]) -> Result<HardwareConfig> {
        let mut hw = HardwareConfig::default();
        let mut r = Reader::new(buf);
        while let Some((fnum, field)) = r.next_field()? {
            match fnum {
                1 => hw.product_name = as_string(field, "product_name")?,
                2 => hw.rom = as_bytes(field, "rom")?,
                3 => hw.platform_serial_number = as_string(field, "platform_serial_number")?,
                4 => hw.platform_uuid = as_string(field, "platform_uuid")?,
                5 => hw.root_disk_uuid = as_string(field, "root_disk_uuid")?,
                6 => hw.board_id = as_string(field, "board_id")?,
                7 => hw.os_build_num = as_string(field, "os_build_num")?,
                8 => hw.gq3489ugfi = as_bytes(field, "gq3489ugfi")?,
                9 => hw.fyp98tpgj = as_bytes(field, "fyp98tpgj")?,
                10 => hw.kbjfrfpoju = as_bytes(field, "kbjfrfpoju")?,
                11 => hw.mac_address = as_bytes(field, "mac_address")?,
                12 => hw.oycqazlotndm = as_bytes(field, "oycqazlotndm")?,
                13 => hw.mlb = as_string(field, "mlb")?,
                14 => hw.abkpld1ecmni = as_bytes(field, "abkpld1ecmni")?,
                // Unknown fields are preserved-by-ignoring; future macOS builds
                // may add hardware entropy we don't need to interpret.
                _ => {}
            }
        }
        Ok(hw)
    }

    /// Encode the inner message body (field order matches the capture).
    pub fn encode(&self, w: &mut Writer) {
        w.string(1, &self.product_name);
        w.bytes(2, &self.rom);
        w.string(3, &self.platform_serial_number);
        w.string(4, &self.platform_uuid);
        w.string(5, &self.root_disk_uuid);
        w.string(6, &self.board_id);
        w.string(7, &self.os_build_num);
        w.bytes(8, &self.gq3489ugfi);
        w.bytes(9, &self.fyp98tpgj);
        w.bytes(10, &self.kbjfrfpoju);
        w.bytes(11, &self.mac_address);
        w.bytes(12, &self.oycqazlotndm);
        w.string(13, &self.mlb);
        w.bytes(14, &self.abkpld1ecmni);
    }

    /// The IOKit property table the emulator serves back to the binary, keyed
    /// by the exact registry property name `IORegistryEntryCreateCFProperty`
    /// asks for. String values are returned as CFString, byte values as CFData.
    pub fn iokit_properties(&self) -> Vec<(&'static str, IoKitValue<'_>)> {
        vec![
            ("IOPlatformSerialNumber", IoKitValue::Str(&self.platform_serial_number)),
            ("IOPlatformUUID", IoKitValue::Str(&self.platform_uuid)),
            ("board-id", IoKitValue::CStr(&self.board_id)),
            ("product-name", IoKitValue::CStr(&self.product_name)),
            ("IOMACAddress", IoKitValue::Data(&self.mac_address)),
            // NVRAM (`IODeviceTree:/options`) reads. Apple prefixes these with
            // the Fabrik GUID `4D1EDE05-38C7-4A6A-9CC6-4BCCA8B38C14:`.
            ("4D1EDE05-38C7-4A6A-9CC6-4BCCA8B38C14:ROM", IoKitValue::Data(&self.rom)),
            ("4D1EDE05-38C7-4A6A-9CC6-4BCCA8B38C14:MLB", IoKitValue::CStr(&self.mlb)),
            // The five obfuscated derived-key reads from `IOPower:/`.
            ("Gq3489ugfi", IoKitValue::Data(&self.gq3489ugfi)),
            ("Fyp98tpgj", IoKitValue::Data(&self.fyp98tpgj)),
            ("kbjfrfpoJU", IoKitValue::Data(&self.kbjfrfpoju)),
            ("oycqAZloTNDm", IoKitValue::Data(&self.oycqazlotndm)),
            ("abKPld1EcMni", IoKitValue::Data(&self.abkpld1ecmni)),
        ]
    }
}

/// A value the emulator hands back for an IOKit property read.
#[derive(Clone, Copy, Debug)]
pub enum IoKitValue<'a> {
    /// Returned as a CFString (no trailing NUL).
    Str(&'a str),
    /// Returned as a CFString; some board/product reads expect a C string with
    /// a trailing NUL byte inside the CFData — the hook layer adds it.
    CStr(&'a str),
    /// Returned as CFData (raw bytes).
    Data(&'a [u8]),
}

impl MacOSConfig {
    /// Parse a full OABS dumb file (magic + protobuf). Accepts the raw bytes;
    /// callers that have base64 should decode first.
    pub fn from_oabs(bytes: &[u8]) -> Result<MacOSConfig> {
        if bytes.len() < OABS_MAGIC.len() || &bytes[..OABS_MAGIC.len()] != OABS_MAGIC {
            return Err(AbsintheError::BadHardwareConfig(
                "missing OABS magic prefix".into(),
            ));
        }
        let body = &bytes[OABS_MAGIC.len()..];
        let mut cfg = MacOSConfig::default();
        let mut r = Reader::new(body);
        while let Some((fnum, field)) = r.next_field()? {
            match fnum {
                1 => cfg.inner = HardwareConfig::decode(&as_bytes(field, "inner")?)?,
                2 => cfg.version = as_string(field, "version")?,
                3 => cfg.protocol_version = as_u64(field, "protocol_version")? as u32,
                4 => cfg.device_id = as_string(field, "device_id")?,
                5 => cfg.icloud_ua = as_string(field, "icloud_ua")?,
                6 => cfg.aoskit_version = as_string(field, "aoskit_version")?,
                7 => cfg.udid = Some(as_string(field, "udid")?),
                _ => {}
            }
        }
        if cfg.inner.platform_serial_number.is_empty() {
            return Err(AbsintheError::BadHardwareConfig(
                "no platform serial number in dumb file".into(),
            ));
        }
        Ok(cfg)
    }

    /// Re-encode to the exact OABS wire form (round-trips byte-for-byte).
    pub fn to_oabs(&self) -> Vec<u8> {
        let mut inner_w = Writer::new();
        self.inner.encode(&mut inner_w);
        let inner_bytes = inner_w.finish();

        let mut w = Writer::new();
        w.bytes(1, &inner_bytes);
        w.string(2, &self.version);
        w.varint(3, self.protocol_version as u64);
        w.string(4, &self.device_id);
        w.string(5, &self.icloud_ua);
        w.string(6, &self.aoskit_version);
        if let Some(udid) = &self.udid {
            w.string(7, udid);
        }

        let mut out = Vec::with_capacity(OABS_MAGIC.len() + w.out_len());
        out.extend_from_slice(OABS_MAGIC);
        out.extend_from_slice(&w.finish());
        out
    }

    /// `X-Mme-Client-Info` / `User-Agent` client-info string, matching
    /// rustpush's `get_mme_clientinfo`: `<product> <macOS;ver;build> <item>`.
    pub fn mme_client_info(&self, for_item: &str) -> String {
        format!(
            "<{}> <macOS;{};{}> <{}>",
            self.inner.product_name, self.version, self.inner.os_build_num, for_item
        )
    }

    /// `[macOS,ver,build,product]` UA fragment (rustpush `get_version_ua`).
    pub fn version_ua(&self) -> String {
        format!(
            "[macOS,{},{},{}]",
            self.version, self.inner.os_build_num, self.inner.product_name
        )
    }
}
