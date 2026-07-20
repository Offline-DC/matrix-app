//! Host test harness: compile the exact source the FFI crate ships and run its tests.
//!
//! `group_identity.rs` is dependency-free (std + serde), so the group-chat identity
//! logic — the fix for the "sibs & sav" wrong-thread bug — is verifiable on the host
//! without the Android/rustpush toolchain. This crate exists only to run those tests;
//! from this directory run: `cargo test`.
//!
//! The `#[path]` include means we test the SHIPPED file, not a copy, so the tests can't
//! drift from the code the `.so` is built from.
#[path = "../../src/group_identity.rs"]
mod group_identity;
