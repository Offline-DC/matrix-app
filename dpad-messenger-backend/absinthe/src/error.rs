//! Crate error type.

use std::fmt;

#[derive(Debug)]
pub enum AbsintheError {
    /// The OABS blob was malformed (bad magic, truncated protobuf, missing
    /// required field).
    BadHardwareConfig(String),
    /// Mach-O / fat parsing failed, or the expected x86_64 slice / section was
    /// not found.
    Macho(String),
    /// The loaded binary is not the exact build this engine's fixed offsets
    /// were derived for. Carries (expected_sha1, got_sha1).
    BinaryMismatch { expected: String, got: String },
    /// The emulator faulted or a nac function returned a non-zero Apple error
    /// code. Carries the raw signed code where applicable.
    Emulation(String),
    /// A hooked import was called that we don't service — means the binary or
    /// its dependencies drifted and a new hook is required.
    UnhookedImport(String),
    /// This build was compiled without `--features emulate`, so the real nac
    /// engine is unavailable. The hardware/macho/transport layers still work.
    EmulatorNotBuilt,
    /// A validation network round-trip (cert fetch or initializeValidation)
    /// failed.
    Transport(String),
    /// Apple rejected the request (e.g. status 6004 = validation data expired).
    Apple(String),
}

impl fmt::Display for AbsintheError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        use AbsintheError::*;
        match self {
            BadHardwareConfig(m) => write!(f, "bad hardware config (dumb file): {m}"),
            Macho(m) => write!(f, "mach-o parse error: {m}"),
            BinaryMismatch { expected, got } => write!(
                f,
                "IMDAppleServices binary mismatch: fixed nac offsets are only \
                 valid for sha1 {expected}, but the supplied binary is {got}. \
                 Supply the matching build or update the offset table."
            ),
            Emulation(m) => write!(f, "nac emulation failed: {m}"),
            UnhookedImport(s) => write!(
                f,
                "emulated nac called an unhooked import `{s}` — the binary \
                 drifted; add a hook for it in hooks.rs"
            ),
            EmulatorNotBuilt => write!(
                f,
                "absinthe was built without `--features emulate`; the nac engine \
                 is unavailable. Rebuild with the feature (needs libunicorn)."
            ),
            Transport(m) => write!(f, "validation transport error: {m}"),
            Apple(m) => write!(f, "apple rejected validation: {m}"),
        }
    }
}

impl std::error::Error for AbsintheError {}

pub type Result<T> = std::result::Result<T, AbsintheError>;
