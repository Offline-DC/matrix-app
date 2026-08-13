//! Byte accounting for the inbound iMessage path.
//!
//! ## Why this exists
//!
//! The Aug-2026 "launcher used 799 MB" investigation had every event logged and
//! not a single byte counted. The bundle proved that replays arrive and that the
//! `seen_guids` guard drops them — but the guard is what makes them CHEAP, not
//! what makes them FREE, and nothing on the device measured the difference.
//!
//! That difference is the whole problem. `seen_guids` is checked in
//! `push_relay_event`, which runs AFTER rustpush has already pulled the payload
//! off the APNs socket and decrypted it. For a blue-bubble photo that costs a
//! few KB (the payload is an MMCS reference; the pixels stay on Apple's servers
//! until tapped). For an SMS/MMS photo it costs the WHOLE FILE, because
//! `parse_sms` produces an `AttachmentType::Inline` whose bytes ride inside the
//! push itself. Same log line, same "skip replay", three orders of magnitude
//! apart on the bill. Counting inline bytes separately is the only way to tell
//! those two apart from a bundle.
//!
//! ## Design notes
//!
//! Plain relaxed atomics, no lock: these are monotonic counters read once a
//! minute by a logger, so ordering between them does not matter and contention
//! must be zero on the receive path.
//!
//! Everything logs at INFO deliberately. The existing `skip replay` line is
//! `log::debug!`, and both `env_logger` and `android_logger` are pinned to
//! `LevelFilter::Info` in `init_logging` — so on every build a customer actually
//! runs, the one line that proves the replay guard fired is compiled in and
//! then thrown away. A diagnostic that is invisible in production is not a
//! diagnostic.

use rustpush::{Message, MessagePart};
use std::sync::atomic::{AtomicU64, Ordering};

/// Relaxed because these are independent monotonic counters, never used to
/// establish happens-before between threads.
const O: Ordering = Ordering::Relaxed;

macro_rules! counters {
    ($($name:ident),* $(,)?) => {
        $(pub static $name: AtomicU64 = AtomicU64::new(0);)*
    };
}

counters! {
    /// Messages/reactions delivered to the app (passed every guard).
    DELIVERED,
    /// Replays dropped by the `seen_guids` guard.
    REPLAY_DROPPED,
    /// Inline attachment bytes carried by those dropped replays. These crossed
    /// the radio and were then discarded — pure waste, and the number that
    /// turns "replays happen" into "replays cost N MB".
    REPLAY_INLINE_BYTES,
    /// Messages dropped for being older than `SYNC_WINDOW_MS`.
    SYNC_WINDOW_DROPPED,
    /// Inline attachment bytes carried by those. Non-zero here means the sync
    /// window is being paid for in full and only saving downstream CPU.
    SYNC_WINDOW_INLINE_BYTES,
    /// Inline attachment bytes on messages we actually kept.
    DELIVERED_INLINE_BYTES,
    /// MMCS references received (cheap — bytes fetched later, on tap).
    MMCS_REFS,
    /// Attachment bytes fetched on demand via `nativeDownloadAttachment`.
    ATTACHMENT_FETCHED_BYTES,
    /// Fetches that ran even though the message was already known — the
    /// signature of a `cacheDir` eviction forcing a re-download.
    ATTACHMENT_REFETCHED,
}

/// Inline (on-the-wire) attachment bytes carried by a message.
///
/// MMCS attachments deliberately count zero: the payload is a URL plus a
/// decryption key, so a 50 MB video reference costs a few hundred bytes here.
/// Mirrors `attachment_stash::inline_bytes`, but walks a whole `Message` so it
/// can be called at the guard sites, before parts are unpacked.
pub fn inline_bytes_of(message: &Message) -> u64 {
    let Message::Message(normal) = message else { return 0 };
    normal
        .parts
        .0
        .iter()
        .filter_map(|part| match &part.part {
            MessagePart::Attachment(att) => Some(crate::attachment_stash::inline_bytes(att) as u64),
            _ => None,
        })
        .sum()
}

/// Record a delivered message and return its inline byte count.
pub fn note_delivered(message: &Message) -> u64 {
    let bytes = inline_bytes_of(message);
    DELIVERED.fetch_add(1, O);
    DELIVERED_INLINE_BYTES.fetch_add(bytes, O);
    bytes
}

/// Record a replay drop. Returns inline bytes wasted, for the call-site log.
pub fn note_replay(message: &Message) -> u64 {
    let bytes = inline_bytes_of(message);
    REPLAY_DROPPED.fetch_add(1, O);
    REPLAY_INLINE_BYTES.fetch_add(bytes, O);
    bytes
}

/// Record a sync-window drop. Returns inline bytes wasted.
pub fn note_sync_window(message: &Message) -> u64 {
    let bytes = inline_bytes_of(message);
    SYNC_WINDOW_DROPPED.fetch_add(1, O);
    SYNC_WINDOW_INLINE_BYTES.fetch_add(bytes, O);
    bytes
}

/// Record an on-demand attachment fetch. `refetch` is true when the message
/// was already in `seen_guids`, i.e. we have had these bytes before and lost
/// them — almost always `cacheDir` eviction, which is invisible otherwise.
pub fn note_attachment_fetch(bytes: u64, refetch: bool) {
    ATTACHMENT_FETCHED_BYTES.fetch_add(bytes, O);
    if refetch {
        ATTACHMENT_REFETCHED.fetch_add(1, O);
    }
}

/// One-line cumulative summary.
///
/// Emitted on a timer and at every connect/disconnect, so a bundle whose
/// rolling buffer covers six minutes still carries totals for the whole
/// process lifetime. The Aug-2026 capture failed precisely here: the buffer was
/// shorter than the phenomenon, and nothing in it was cumulative.
///
/// Read it as: everything after `wasted=` is data the customer paid for and
/// did not receive any value from.
pub fn log_summary(reason: &str) {
    let replay_bytes = REPLAY_INLINE_BYTES.load(O);
    let window_bytes = SYNC_WINDOW_INLINE_BYTES.load(O);
    log::info!(
        "NET ACCT [{reason}] delivered={} ({} KiB inline) | replay_dropped={} | \
         window_dropped={} | mmcs_refs={} | fetched={} KiB (refetch={}) | \
         wasted={} KiB (replay={} KiB window={} KiB)",
        DELIVERED.load(O),
        DELIVERED_INLINE_BYTES.load(O) / 1024,
        REPLAY_DROPPED.load(O),
        SYNC_WINDOW_DROPPED.load(O),
        MMCS_REFS.load(O),
        ATTACHMENT_FETCHED_BYTES.load(O) / 1024,
        ATTACHMENT_REFETCHED.load(O),
        (replay_bytes + window_bytes) / 1024,
        replay_bytes / 1024,
        window_bytes / 1024,
    );
}
