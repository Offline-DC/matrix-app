//! # absinthe
//!
//! An open reimplementation of Apple's iMessage **validation-data** generator
//! (the "nac" / "Absinthe" routines) for interoperability clients — a drop-in
//! for OpenBubbles' closed-source `open-absinthe::nac`.
//!
//! ## Why this exists
//!
//! iMessage registration (`IDS register`) requires a signed *validation data*
//! blob proving the request comes from genuine Apple hardware. Apple generates
//! it inside an **obfuscated** routine in `IMDAppleServices` (Apple ships this
//! through a custom VM obfuscator). Nobody has a clean-room re-implementation of
//! the algorithm; the proven, community-standard method — pypush, OpenBubbles,
//! Beeper — is to **emulate the untouched Apple binary** under a CPU emulator
//! and feed it a captured Mac hardware identity, faking every IOKit read.
//!
//! ## Architecture
//!
//! ```text
//!   MacOSConfig (dumb file, OABS) ──► hardware.rs   (this crate, always on)
//!            │
//!            ▼
//!   IMDAppleServices x86_64 slice ──► macho.rs      (load + dyld binds)
//!            │
//!            ▼
//!   Unicorn CPU + import hooks    ──► jelly.rs + hooks.rs   (feature = "emulate")
//!            │   nac_init / nac_key_establishment / nac_sign
//!            ▼
//!   IDS validation round-trips    ──► validation.rs (feature = "net")
//!            │   GET cert · POST id-initialize-validation
//!            ▼
//!            validation-data  ──►  rustpush `register(...)`
//! ```
//!
//! See `../ABSINTHE_RE_FINDINGS.md` for the full reverse-engineering writeup.

mod error;
mod protobuf;

pub mod hardware;
pub mod macho;
pub mod nac;
pub mod validation;

#[cfg(feature = "emulate")]
mod jelly;
#[cfg(feature = "emulate")]
mod hooks;

pub use error::{AbsintheError, Result};
pub use hardware::{HardwareConfig, MacOSConfig};
pub use nac::ValidationCtx;
pub use validation::{generate_validation_data, ValidationTransport};

/// The binary the emulator expects (see [`macho::EXPECTED_SHA1`]).
pub use macho::EXPECTED_SHA1;
