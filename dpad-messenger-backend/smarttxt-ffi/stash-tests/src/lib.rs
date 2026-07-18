//! Host test harness: compile the exact source the FFI crate ships and run its tests.
//!
//! `attachment_stash.rs` is the persistence layer that lets a received attachment still
//! be downloaded after the app restarts. Its risky parts are all testable on the host:
//! the plist round-trip of `MMCSFile`'s raw byte fields (serde_json silently fails there),
//! the split of inline payloads into blob files, and the eviction/pruning rules.
//!
//! From this directory run: `cargo test`.
//!
//! The `#[path]` include means we test the SHIPPED file, not a copy, so the tests cannot
//! drift from the code the `.so` is built from.
#[path = "../../src/attachment_stash.rs"]
mod attachment_stash;
