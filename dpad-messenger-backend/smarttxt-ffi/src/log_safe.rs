//! Log-safe text for everything Smart Txt's native side writes to the log.
//!
//! The Smart Txt log is emailed to the team on "report". The owner's rule
//! (2026-11-06, confirmed after the 9 Nov device test): it never holds an email
//! address in full — the Apple ID is never needed to debug, only the kind of
//! handle, its domain and which one is which — and never message text. Phone
//! numbers are kept.
//!
//! Std only, so its tests run on the host (log-safe-tests/), like the other
//! dependency-free modules of this crate.

use std::borrow::Cow;

/// Log-safe form of an iMessage handle. An email keeps its first character
/// and its domain ("mailto:s*****@dumb.co"); a phone number is unchanged.
/// Only for log text, never for anything sent.
pub(crate) fn log_handle(h: &str) -> String {
    let (prefix, rest) = match h.strip_prefix("mailto:") {
        Some(r) => ("mailto:", r),
        None => ("", h),
    };
    match rest.split_once('@') {
        Some((local, domain)) if !local.is_empty() => {
            let first: String = local.chars().take(1).collect();
            format!("{prefix}{first}*****@{domain}")
        }
        _ => h.to_string(),
    }
}

/// [log_handle] over a list.
pub(crate) fn log_handles<S: AsRef<str>>(handles: &[S]) -> Vec<String> {
    handles.iter().map(|h| log_handle(h.as_ref())).collect()
}

/// What every log line goes through before it is written (the android_logger
/// format closure in init_logger applies it to ours and rustpush's alike):
/// email addresses masked, then long encoded blobs collapsed to their length.
pub(crate) fn scrub_log_line(s: &str) -> Cow<'_, str> {
    match mask_emails(s) {
        Cow::Borrowed(b) => collapse_blobs(b),
        Cow::Owned(o) => Cow::Owned(collapse_blobs(&o).into_owned()),
    }
}

/// Every email address in a log line, masked the [log_handle] way
/// ("s*****@dumb.co"), wherever it sits: "mailto:…", "to=…", inside JSON or a
/// Debug dump. A URL-encoded '@' ("%40", as in Apple's auth responses) counts
/// too and is kept as written ("s*****%40dumb.co").
///
/// No regex crate. An address is a run of local-part characters, the '@', then
/// a dotted domain ending in a 2+ letter TLD. Already masked text is left alone
/// ('*' isn't a local-part character), so masking twice changes nothing. Lines
/// without an '@' are returned as they are, without copying.
pub(crate) fn mask_emails(s: &str) -> Cow<'_, str> {
    if !s.contains('@') && !s.contains("%40") {
        return Cow::Borrowed(s);
    }
    let b = s.as_bytes();
    let is_local = |c: u8| c.is_ascii_alphanumeric() || b"._%+-".contains(&c);
    let is_domain = |c: u8| c.is_ascii_alphanumeric() || c == b'.' || c == b'-';
    let mut out = String::with_capacity(s.len());
    let mut copied = 0; // bytes of `s` already in `out`
    let mut i = 0;
    while i < b.len() {
        let at_len = if b[i] == b'@' {
            1
        } else if b[i] == b'%' && b.get(i + 1) == Some(&b'4') && b.get(i + 2) == Some(&b'0') {
            3
        } else {
            0
        };
        if at_len > 0 {
            let mut start = i;
            while start > copied && is_local(b[start - 1]) {
                start -= 1;
            }
            let mut end = i + at_len;
            while end < b.len() && is_domain(b[end]) {
                end += 1;
            }
            while end > i + at_len && (b[end - 1] == b'.' || b[end - 1] == b'-') {
                end -= 1; // a sentence's full stop isn't part of the domain
            }
            let domain = &s[i + at_len..end];
            let tld_ok = domain.contains('.')
                && domain
                    .rsplit('.')
                    .next()
                    .map_or(false, |t| t.len() >= 2 && t.bytes().all(|c| c.is_ascii_alphabetic()));
            if start < i && tld_ok {
                // Keep the first character of the local part, drop the rest.
                out.push_str(&s[copied..start + 1]);
                out.push_str("*****");
                out.push_str(&s[i..end]);
                copied = end;
                i = end;
                continue;
            }
        }
        i += 1;
    }
    if copied == 0 {
        return Cow::Borrowed(s);
    }
    out.push_str(&s[copied..]);
    Cow::Owned(out)
}

/// Runs this long of hex / base64 characters are payloads, not diagnostics.
/// UUIDs (32), push tokens (64) and hashes up to SHA-256 (64) stay readable.
const BLOB_MIN: usize = 96;

/// Long hex or base64 runs collapsed to "<N chars redacted>". rustpush logs
/// every outgoing message as `raw: body <hex>` (identity_manager.rs, the
/// `Debug` of `Raw`), which is the message's plist — text, participants, all of
/// it, only gzipped — and Apple's auth replies as base64. An email inside them
/// can't be seen by [mask_emails], so the whole blob goes (9 Nov review).
pub(crate) fn collapse_blobs(s: &str) -> Cow<'_, str> {
    let b = s.as_bytes();
    let is_blob = |c: u8| c.is_ascii_alphanumeric() || c == b'+' || c == b'/' || c == b'=';
    let mut out: Option<String> = None;
    let mut copied = 0;
    let mut i = 0;
    while i < b.len() {
        if is_blob(b[i]) {
            let start = i;
            while i < b.len() && is_blob(b[i]) {
                i += 1;
            }
            if i - start >= BLOB_MIN {
                let o = out.get_or_insert_with(|| String::with_capacity(s.len()));
                o.push_str(&s[copied..start]);
                o.push_str(&format!("<{} chars redacted>", i - start));
                copied = i;
            }
        } else {
            i += 1;
        }
    }
    match out {
        None => Cow::Borrowed(s),
        Some(mut o) => {
            o.push_str(&s[copied..]);
            Cow::Owned(o)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emails_are_masked_phone_numbers_kept() {
        assert_eq!(log_handle("mailto:someone@dumb.co"), "mailto:s*****@dumb.co");
        assert_eq!(log_handle("someone@icloud.com"), "s*****@icloud.com");
        assert_eq!(log_handle("tel:+14045550123"), "tel:+14045550123");
        assert_eq!(log_handle("+14045550123"), "+14045550123");
    }

    #[test]
    fn odd_handles_pass_through() {
        assert_eq!(log_handle(""), "");
        assert_eq!(log_handle("mailto:@dumb.co"), "mailto:@dumb.co");
        assert_eq!(log_handle("mailto:é@x.io"), "mailto:é*****@x.io");
    }

    #[test]
    fn lists_are_masked_item_by_item() {
        let v = vec!["tel:+14045550123".to_string(), "mailto:someone@dumb.co".to_string()];
        assert_eq!(log_handles(&v), vec!["tel:+14045550123", "mailto:s*****@dumb.co"]);
    }

    /// The lines that leaked the Apple ID in the 9 Nov device test (rustpush's own
    /// logging and our nativeSendText), with made-up values.
    #[test]
    fn the_leaking_lines_from_the_device_test_come_out_masked() {
        assert_eq!(
            mask_emails("nativeSendText: chat=iMessage;-;+14045550123 is_sms=false from_handle=mailto:someone@dumb.co has_reply=false"),
            "nativeSendText: chat=iMessage;-;+14045550123 is_sms=false from_handle=mailto:s*****@dumb.co has_reply=false"
        );
        assert_eq!(
            mask_emails("IDS TARGETS mailto:someone@dumb.co → [tel:+14045550123:7 mailto:someone@dumb.co:3]"),
            "IDS TARGETS mailto:s*****@dumb.co → [tel:+14045550123:7 mailto:s*****@dumb.co:3]"
        );
        assert_eq!(
            mask_emails("IDS RX cmd=101 from=tel:+14045550123 to=mailto:someone@dumb.co uuid=d7c7"),
            "IDS RX cmd=101 from=tel:+14045550123 to=mailto:s*****@dumb.co uuid=d7c7"
        );
    }

    #[test]
    fn a_url_encoded_at_sign_is_masked_too() {
        assert_eq!(mask_emails("accountName=someone%40dumb.co&x=1"), "accountName=s*****%40dumb.co&x=1");
        assert_eq!(mask_emails("50%40 off"), "50%40 off");
    }

    /// A plus address URL-encoded ('+' as %2B, as in the recovery URLs of Apple's
    /// /auth reply) is masked from its first character: '%' counts as a local-part
    /// character, so the mailbox before the %2B isn't left showing. The Kotlin twin
    /// (LogSafe.kt) has the same case in its tests (10 Nov review).
    #[test]
    fn a_url_encoded_plus_address_is_masked_whole() {
        assert_eq!(mask_emails("x=foo%2Bbar%40dumb.co"), "x=f*****%40dumb.co");
        assert_eq!(mask_emails("foo%2Bbar@dumb.co"), "f*****@dumb.co");
        assert_eq!(mask_emails("prs_account_nm=someone%2Btag%40dumb.co&x=1"), "prs_account_nm=s*****%40dumb.co&x=1");
    }

    #[test]
    fn several_addresses_and_odd_edges() {
        assert_eq!(mask_emails("a@x.io,b.c+tag@sub.example.com."), "a*****@x.io,b*****@sub.example.com.");
        assert_eq!(mask_emails("(first.last@icloud.com)"), "(f*****@icloud.com)");
        assert_eq!(mask_emails("\"x@me.com\":1"), "\"x*****@me.com\":1");
        assert_eq!(mask_emails("iMessage;-;someone@x.com"), "iMessage;-;s*****@x.com");
    }

    #[test]
    fn masking_twice_changes_nothing() {
        let once = mask_emails("to=mailto:someone@dumb.co").into_owned();
        assert_eq!(mask_emails(&once), once);
        assert_eq!(mask_emails("handles=[\"mailto:s*****@dumb.co\"]"), "handles=[\"mailto:s*****@dumb.co\"]");
    }

    #[test]
    fn things_that_are_not_addresses_are_left_alone() {
        for s in [
            "no at sign here",
            "scale@2x",
            "user@localhost",
            "v@1.2.3",
            "@dumb.co",
            "trailing@",
            "tel:+14045550123",
            "émoji 😀 and ünïcode, no address",
        ] {
            assert_eq!(mask_emails(s), s, "{s}");
        }
    }

    #[test]
    fn non_ascii_around_an_address_is_kept_intact() {
        assert_eq!(mask_emails("ünï someone@dumb.co 😀"), "ünï s*****@dumb.co 😀");
    }

    /// rustpush's "ID send message" line: the message body as hex (here a made-up
    /// one of realistic length) collapses to its length; the sender is masked.
    #[test]
    fn a_message_body_dumped_as_hex_is_collapsed() {
        let hex = "1f8b0800000000000003".repeat(10); // 200 hex chars
        let line = format!(
            "ID send message IDSSendMessage {{ sender: \"mailto:someone@dumb.co\", raw: body {hex}, send_delivered: false }}"
        );
        assert_eq!(
            scrub_log_line(&line),
            "ID send message IDSSendMessage { sender: \"mailto:s*****@dumb.co\", raw: body <200 chars redacted>, send_delivered: false }"
        );
    }

    #[test]
    fn a_base64_reply_is_collapsed() {
        let b64 = "QmFzZTY0IGVuY29kZWQgcGxpc3Qgd2l0aCBhY2NvdW50TmFtZT1zb21lb25lQGR1bWIuY28+/=".repeat(2);
        let n = b64.len();
        assert_eq!(scrub_log_line(&format!("Got auth response {b64}")), format!("Got auth response <{n} chars redacted>"));
    }

    #[test]
    fn ids_tokens_and_hashes_stay_readable() {
        for s in [
            "uuid=d7c7d74bede24e5ea8fa5e502906a500",
            "token=15b2a216c0ffee15b2a216c0ffee15b2a216c0ffee15b2a216c0ffee1234", // 64
            "rom=7cd4b8aba940 udid_len=64",
            "a normal sentence with words",
        ] {
            assert_eq!(scrub_log_line(s), s, "{s}");
        }
    }
}
