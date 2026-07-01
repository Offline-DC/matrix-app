//! Core tests that run with no native deps (`cargo test`, default features).
//!
//! Two optional tests read real artifacts if you point env vars at them — these
//! are how the parser was validated against the genuine capture, but they never
//! commit secrets:
//!   ABSINTHE_DUMB_FILE   = path to the base64 OABS dumb file
//!   ABSINTHE_IMD_BINARY  = path to the fat IMDAppleServices binary

use absinthe::hardware::{HardwareConfig, MacOSConfig};
use absinthe::macho::MachOImage;

fn sample_config() -> MacOSConfig {
    MacOSConfig {
        inner: HardwareConfig {
            product_name: "Mac17,2".into(),
            rom: vec![0x5c, 0x9b, 0xa6, 0x5f, 0x86, 0x03],
            platform_serial_number: "EXAMPLE123".into(),
            platform_uuid: "78FDA2E2-BDA9-56C5-A26F-27523F758703".into(),
            root_disk_uuid: "096AAB72-CD9A-4CDB-A131-C29D3299649E".into(),
            board_id: "Mac-22000000".into(),
            os_build_num: "24D70".into(),
            gq3489ugfi: vec![1u8; 17],
            fyp98tpgj: vec![2u8; 17],
            kbjfrfpoju: vec![3u8; 17],
            mac_address: vec![0x00, 0x11, 0x22, 0x33, 0x44, 0x55],
            oycqazlotndm: vec![4u8; 17],
            mlb: "J33HKR00VPU0000VLK".into(),
            abkpld1ecmni: vec![5u8; 17],
        },
        version: "15.3.1".into(),
        protocol_version: 1640,
        device_id: "78FDA2E2-BDA9-56C5-A26F-27523F758703".into(),
        icloud_ua: "com.apple.iCloudHelper/282 CFNetwork/1408.0.4 Darwin/22.5.0".into(),
        aoskit_version: "com.apple.AOSKit/282 (com.apple.accountsd/113)".into(),
        udid: None,
    }
}

#[test]
fn oabs_round_trips_byte_exact() {
    let cfg = sample_config();
    let encoded = cfg.to_oabs();
    assert_eq!(&encoded[..5], b"OABS\0", "magic prefix");

    let decoded = MacOSConfig::from_oabs(&encoded).expect("decode");
    assert_eq!(decoded, cfg, "struct round-trip");

    // Re-encoding the decoded value must be byte-identical (stable wire form).
    assert_eq!(decoded.to_oabs(), encoded, "byte-exact re-encode");
}

#[test]
fn iokit_properties_cover_the_nac_reads() {
    let cfg = sample_config();
    let props = cfg.inner.iokit_properties();
    let keys: Vec<&str> = props.iter().map(|(k, _)| *k).collect();
    for required in [
        "IOPlatformSerialNumber",
        "IOPlatformUUID",
        "board-id",
        "product-name",
        "IOMACAddress",
        "Gq3489ugfi",
        "Fyp98tpgj",
        "kbjfrfpoJU",
        "oycqAZloTNDm",
        "abKPld1EcMni",
    ] {
        assert!(keys.contains(&required), "missing IOKit key {required}");
    }
}

#[test]
fn client_info_strings_match_rustpush_shape() {
    let cfg = sample_config();
    assert_eq!(
        cfg.mme_client_info("com.apple.private.alloy.sms"),
        "<Mac17,2> <macOS;15.3.1;24D70> <com.apple.private.alloy.sms>"
    );
    assert_eq!(cfg.version_ua(), "[macOS,15.3.1,24D70,Mac17,2]");
}

#[test]
fn rejects_missing_magic() {
    assert!(MacOSConfig::from_oabs(b"not-oabs").is_err());
}

#[test]
fn real_dumb_file_parses_if_present() {
    let Ok(path) = std::env::var("ABSINTHE_DUMB_FILE") else {
        eprintln!("skipping: set ABSINTHE_DUMB_FILE to run against the real capture");
        return;
    };
    let b64 = std::fs::read_to_string(&path).expect("read dumb file");
    // tiny base64 decode (no dep)
    let raw = base64_decode(b64.trim());
    let cfg = MacOSConfig::from_oabs(&raw).expect("parse real dumb file");
    assert!(!cfg.inner.platform_serial_number.is_empty());
    assert_eq!(cfg.protocol_version, 1640);
    assert_eq!(cfg.inner.rom.len(), 6);
    assert_eq!(cfg.inner.mac_address.len(), 6);
    // byte-exact round-trip against the genuine capture
    assert_eq!(cfg.to_oabs(), raw, "real capture must round-trip exactly");
    assert_eq!(cfg.inner.iokit_properties().len(), 12);
}

#[test]
fn real_binary_parses_if_present() {
    let Ok(path) = std::env::var("ABSINTHE_IMD_BINARY") else {
        eprintln!("skipping: set ABSINTHE_IMD_BINARY to run against the real binary");
        return;
    };
    let data = std::fs::read(&path).expect("read binary");
    let image = MachOImage::from_fat(&data).expect("parse fat binary");
    assert!(image.nac_prologues_ok(), "nac function prologues mismatch");
    // The bind streams should resolve the hardware-read import we hook.
    assert!(
        image
            .binds
            .iter()
            .any(|b| b.symbol == "_IORegistryEntryCreateCFProperty"),
        "missing the key IOKit import"
    );
}

fn base64_decode(s: &str) -> Vec<u8> {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut lut = [255u8; 256];
    for (i, &c) in T.iter().enumerate() {
        lut[c as usize] = i as u8;
    }
    let mut out = Vec::new();
    let mut buf = 0u32;
    let mut bits = 0u32;
    for &c in s.as_bytes() {
        if c == b'=' || c == b'\n' || c == b'\r' {
            continue;
        }
        let v = lut[c as usize];
        assert_ne!(v, 255, "bad base64 char");
        buf = (buf << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    out
}
