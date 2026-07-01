//! End-to-end smoke test of the Rust nac engine (feature `emulate` + `net`).
//!
//!   ABSINTHE_DUMB_FILE=./dumb ABSINTHE_IMD_BINARY=./IMDAppleServices \
//!     cargo run --example generate --features emulate,net
//!
//! Prints the length of the validation data it generates. Requires the real
//! IMDAppleServices binary and a dumb file (base64 OABS). Makes two live calls
//! to Apple (cert + initializeValidation).

use absinthe::validation::HttpTransport;
use absinthe::{generate_validation_data, MacOSConfig};

fn main() {
    let dumb_path = std::env::var("ABSINTHE_DUMB_FILE").expect("set ABSINTHE_DUMB_FILE");
    let bin_path = std::env::var("ABSINTHE_IMD_BINARY").expect("set ABSINTHE_IMD_BINARY");

    let b64 = std::fs::read_to_string(&dumb_path).expect("read dumb file");
    let oabs = base64_decode(b64.trim());
    let cfg = MacOSConfig::from_oabs(&oabs).expect("parse dumb file");
    println!(
        "identity: {} serial={} build={}",
        cfg.inner.product_name, cfg.inner.platform_serial_number, cfg.inner.os_build_num
    );

    let binary = std::fs::read(&bin_path).expect("read binary");
    let transport = HttpTransport::new().expect("http transport");

    match generate_validation_data(&cfg, &binary, &transport) {
        Ok(data) => println!("OK: generated {} bytes of validation data", data.len()),
        Err(e) => {
            eprintln!("FAILED: {e}");
            std::process::exit(1);
        }
    }
}

fn base64_decode(s: &str) -> Vec<u8> {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut lut = [255u8; 256];
    for (i, &c) in T.iter().enumerate() {
        lut[c as usize] = i as u8;
    }
    let (mut out, mut buf, mut bits) = (Vec::new(), 0u32, 0u32);
    for &c in s.as_bytes() {
        if c == b'=' || c == b'\n' || c == b'\r' {
            continue;
        }
        let v = lut[c as usize];
        assert_ne!(v, 255, "bad base64");
        buf = (buf << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    out
}
