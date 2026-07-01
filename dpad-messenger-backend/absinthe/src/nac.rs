//! `ValidationCtx` — the three-call nac surface, API-compatible with
//! OpenBubbles' `open-absinthe::nac::ValidationCtx`.
//!
//! ```text
//!   let mut request = Vec::new();
//!   let mut ctx = ValidationCtx::new(&cert, &mut request, &hw, &binary)?; // nac_init
//!   // POST `request` to id-initialize-validation, get `session_info`
//!   ctx.key_establishment(&session_info)?;                               // nac_key_establishment
//!   let validation_data = ctx.sign()?;                                    // nac_sign
//! ```
//!
//! The three calls correspond to the unexported functions at slice offsets
//! `0xB1DB0` / `0xB1DD0` / `0xB1DF0` inside `IMDAppleServices`. With
//! `--features emulate` this drives a real Unicorn session (`jelly`); without
//! it, the type still exists (so downstream code compiles) but every call
//! returns [`AbsintheError::EmulatorNotBuilt`].

use crate::error::{AbsintheError, Result};
use crate::hardware::HardwareConfig;

pub struct ValidationCtx {
    #[cfg(feature = "emulate")]
    session: crate::jelly::NacSession,
    #[cfg(not(feature = "emulate"))]
    _unbuilt: (),
}

impl ValidationCtx {
    /// `nac_init`: map+emulate the binary, feed it the Apple validation `cert`,
    /// and capture the request blob into `out_request`. `hw` supplies the
    /// spoofed hardware identity the emulated IOKit reads will return; `binary`
    /// is the raw fat `IMDAppleServices` (see [`crate::macho::EXPECTED_SHA1`]).
    #[cfg(feature = "emulate")]
    pub fn new(
        cert: &[u8],
        out_request: &mut Vec<u8>,
        hw: &HardwareConfig,
        binary: &[u8],
    ) -> Result<ValidationCtx> {
        let mut session = crate::jelly::NacSession::load(binary, hw)?;
        let request = session.nac_init(cert)?;
        *out_request = request;
        Ok(ValidationCtx { session })
    }

    #[cfg(not(feature = "emulate"))]
    pub fn new(
        _cert: &[u8],
        _out_request: &mut Vec<u8>,
        _hw: &HardwareConfig,
        _binary: &[u8],
    ) -> Result<ValidationCtx> {
        Err(AbsintheError::EmulatorNotBuilt)
    }

    /// `nac_key_establishment`: feed Apple's `session-info` response back in.
    #[cfg(feature = "emulate")]
    pub fn key_establishment(&mut self, response: &[u8]) -> Result<()> {
        self.session.nac_key_establishment(response)
    }

    #[cfg(not(feature = "emulate"))]
    pub fn key_establishment(&mut self, _response: &[u8]) -> Result<()> {
        Err(AbsintheError::EmulatorNotBuilt)
    }

    /// `nac_sign`: emit the final validation-data blob.
    #[cfg(feature = "emulate")]
    pub fn sign(&mut self) -> Result<Vec<u8>> {
        self.session.sign_data()
    }

    #[cfg(not(feature = "emulate"))]
    pub fn sign(&mut self) -> Result<Vec<u8>> {
        Err(AbsintheError::EmulatorNotBuilt)
    }
}

/// Mirror of `open-absinthe`'s `HardwareConfig::from_validation_data`, which is
/// `panic!("Not supported with binary!")` in the stub and unnecessary for our
/// registration flow (we build the config from a captured dumb file instead).
/// Kept for API parity; always errors.
pub fn hardware_config_from_validation_data(_data: &[u8]) -> Result<HardwareConfig> {
    Err(AbsintheError::Emulation(
        "from_validation_data is not supported; capture a dumb file instead".into(),
    ))
}
