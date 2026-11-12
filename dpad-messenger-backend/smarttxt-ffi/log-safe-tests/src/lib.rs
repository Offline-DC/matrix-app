//! Host test harness: compile the exact log_safe.rs the FFI crate ships and run its
//! tests (`cargo test` from this directory). The `#[path]` include means the tests
//! can't drift from the code the `.so` is built from.
#![allow(dead_code)]
#[path = "../../src/log_safe.rs"]
mod log_safe;
