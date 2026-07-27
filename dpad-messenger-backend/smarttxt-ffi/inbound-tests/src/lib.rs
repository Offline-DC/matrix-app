//! Host test harness: compile the exact source the FFI crate ships and run its tests.
//!
//! `inbound.rs` holds the bounded queue + capped drain that keep an offline backlog from
//! spiking the heap hard enough to get the foreground process low-memory-killed — the
//! "the phone froze after being off for a day" bug. It is dependency-free (std +
//! serde_json) so those rules are verifiable on the host without the Android/rustpush
//! toolchain. This crate exists only to run them; from this directory run: `cargo test`.
//!
//! The `#[path]` include means we test the SHIPPED file, not a copy, so the tests can't
//! drift from the code the `.so` is built from.
#[path = "../../src/inbound.rs"]
mod inbound;
