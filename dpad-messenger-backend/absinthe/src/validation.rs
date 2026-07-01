//! The two Apple round-trips that bracket `nac_key_establishment`, plus the
//! high-level [`generate_validation_data`] driver.
//!
//! The transport is abstracted so the on-device path (rustpush already owns an
//! HTTP client + APNs connection) can implement it without pulling `reqwest`
//! into the Android build. A blocking `reqwest` implementation is provided
//! behind `--features net` for servers / the `nacserver` relay.

use crate::error::{AbsintheError, Result};
use crate::hardware::MacOSConfig;
use crate::nac::ValidationCtx;

/// Apple endpoints. `id-validation-cert` / `id-initialize-validation` are also
/// resolvable via the IDS bag, but the community clients hardcode these and
/// they've been stable for years.
pub const CERT_URL: &str = "http://static.ess.apple.com/identity/validation/cert-1.0.plist";
pub const INITIALIZE_VALIDATION_URL: &str =
    "https://identity.ess.apple.com/WebObjects/TDIdentityService.woa/wa/initializeValidation";

/// Abstracts the two network calls the nac handshake needs.
pub trait ValidationTransport {
    /// GET the validation certificate; return the raw DER bytes from the plist
    /// `cert` field (this is what `nac_init` consumes).
    fn fetch_cert(&self) -> Result<Vec<u8>>;

    /// POST the `nac_init` request as a plist `{"session-info-request": <req>}`
    /// to `id-initialize-validation`; return the `session-info` bytes from the
    /// response plist (this is what `nac_key_establishment` consumes).
    fn initialize_validation(&self, request: &[u8]) -> Result<Vec<u8>>;
}

/// Full pipeline: cert → nac_init → initializeValidation → key_establishment →
/// nac_sign → validation-data. `binary` is the fat `IMDAppleServices`.
///
/// Requires `--features emulate`; without it, [`ValidationCtx::new`] returns
/// [`AbsintheError::EmulatorNotBuilt`].
pub fn generate_validation_data(
    cfg: &MacOSConfig,
    binary: &[u8],
    transport: &dyn ValidationTransport,
) -> Result<Vec<u8>> {
    let cert = transport.fetch_cert()?;
    let mut request = Vec::new();
    let mut ctx = ValidationCtx::new(&cert, &mut request, &cfg.inner, binary)?;
    let session_info = transport.initialize_validation(&request)?;
    ctx.key_establishment(&session_info)?;
    let validation_data = ctx.sign()?;
    if validation_data.is_empty() {
        return Err(AbsintheError::Emulation(
            "nac_sign returned empty validation data".into(),
        ));
    }
    Ok(validation_data)
}

/// A blocking `reqwest` transport (feature `net`). Suitable for a server-side
/// nacserver / validation relay.
#[cfg(feature = "net")]
pub struct HttpTransport {
    client: reqwest::blocking::Client,
}

#[cfg(feature = "net")]
impl HttpTransport {
    pub fn new() -> Result<Self> {
        // Apple's `identity.ess.apple.com` presents a cert chain some stacks
        // dislike; community clients disable verification for THIS host only.
        // We keep verification on by default; callers that must relax it can
        // build their own client. (rustpush historically set verify=false.)
        let client = reqwest::blocking::Client::builder()
            .user_agent("com.apple.invitation-registration [macOS,15.3.1,24D70,Mac17,2]")
            .build()
            .map_err(|e| AbsintheError::Transport(e.to_string()))?;
        Ok(HttpTransport { client })
    }
}

#[cfg(feature = "net")]
impl Default for HttpTransport {
    fn default() -> Self {
        Self::new().expect("build reqwest client")
    }
}

#[cfg(feature = "net")]
impl ValidationTransport for HttpTransport {
    fn fetch_cert(&self) -> Result<Vec<u8>> {
        let bytes = self
            .client
            .get(CERT_URL)
            .send()
            .and_then(|r| r.error_for_status())
            .and_then(|r| r.bytes())
            .map_err(|e| AbsintheError::Transport(format!("cert fetch: {e}")))?;
        let dict: plist::Dictionary = plist::from_bytes(&bytes)
            .map_err(|e| AbsintheError::Transport(format!("cert plist: {e}")))?;
        let cert = dict
            .get("cert")
            .and_then(|v| v.as_data())
            .ok_or_else(|| AbsintheError::Transport("cert plist missing `cert` data".into()))?;
        Ok(cert.to_vec())
    }

    fn initialize_validation(&self, request: &[u8]) -> Result<Vec<u8>> {
        let mut body = plist::Dictionary::new();
        body.insert(
            "session-info-request".into(),
            plist::Value::Data(request.to_vec()),
        );
        let mut buf = Vec::new();
        plist::to_writer_xml(&mut buf, &plist::Value::Dictionary(body))
            .map_err(|e| AbsintheError::Transport(format!("encode init body: {e}")))?;

        let resp = self
            .client
            .post(INITIALIZE_VALIDATION_URL)
            .header("Content-Type", "application/x-apple-plist")
            .body(buf)
            .send()
            .and_then(|r| r.error_for_status())
            .and_then(|r| r.bytes())
            .map_err(|e| AbsintheError::Transport(format!("initializeValidation: {e}")))?;

        let dict: plist::Dictionary = plist::from_bytes(&resp)
            .map_err(|e| AbsintheError::Transport(format!("init response plist: {e}")))?;
        let session_info = dict
            .get("session-info")
            .and_then(|v| v.as_data())
            .ok_or_else(|| AbsintheError::Apple("response missing `session-info`".into()))?;
        Ok(session_info.to_vec())
    }
}
