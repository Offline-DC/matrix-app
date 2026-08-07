//! Host test harness: compile the exact source the FFI crate ships and run its tests.
//!
//! `send_policy.rs` holds the two decisions that produced the field bugs of 2026-08:
//! sending as green SMS when Apple never answered the IDS lookup, and re-registering on
//! every connect because the handle check fired on set inequality instead of on the one
//! actionable direction. Both are pure functions over small inputs, and neither can be
//! provoked reliably on a handset — an IDS timeout has to coincide with a send, and the
//! reconcile loop needs Apple to omit a URI it also vends. So they are proved here.
//!
//! From this directory run: `cargo test`.
//!
//! The `#[path]` include means we test the SHIPPED file, not a copy, so the tests can't
//! drift from the code the `.so` is built from.
#[path = "../../src/send_policy.rs"]
mod send_policy;
