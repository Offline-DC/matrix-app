//! Persisted attachment stash: the references needed to fetch a received attachment
//! back from Apple after the app restarts.
//!
//! ## Why this exists
//!
//! `nativeDownloadAttachment` needs the rustpush [`Attachment`] for a message. That
//! used to live only in memory, so every attachment received before the current launch
//! was permanently undownloadable ("NO stashed attachment … stash has 0 entries") — and
//! on a 916 MB phone the process dies constantly. An `Attachment` carries the entire
//! MMCS coordinate set (url / object / signature / key / size), so persisting it is
//! sufficient to fetch the bytes later. There is no way to re-derive those coordinates
//! afterwards: if they are not captured when the message arrives, that attachment is
//! gone. OpenBubbles reaches the same conclusion and stores the same struct the same
//! way (an XML plist string in its `dbMetadata` column). BlueBubbles never faces this
//! because a Mac already downloaded every attachment to ~/Library/Messages/Attachments.
//!
//! ## Layout
//!
//! Two pieces, deliberately split:
//!
//! - `attachments.plist` — the INDEX. Metadata only, a few hundred KB at most.
//! - `attachments/<hex>.bin` — one blob per inline payload, written once.
//!
//! Keeping payloads out of the index is what makes this cheap: the index is rewritten
//! whenever the set changes, so co-locating megabytes of MMS pictures in it would mean
//! re-serialising every retained byte on every new attachment. Blobs are write-once.
//! This mirrors OpenBubbles, which keeps bytes as files and metadata in a DB column.
//!
//! Most entries cost nothing anyway: any iMessage attachment big enough to matter —
//! photo, video, voice memo — is an [`AttachmentType::MMCS`], a pure reference whose
//! payload stays on Apple's servers until tapped. Only two receive paths carry real
//! bytes: iMessage's `inline-attachment` (ia-0/ia-1, a few KB inside the push itself)
//! and an incoming SMS/MMS attachment, which has no such ceiling.

use rustpush::{Attachment, AttachmentType};
use std::path::{Path, PathBuf};

/// How many attachment references to keep. An MMCS entry is a few hundred bytes, so the
/// index stays well under a megabyte; this exists so the map cannot grow for the life of
/// the install. Oldest-first eviction — an evicted reference belongs to a message the
/// Kotlin cache has almost certainly trimmed already.
pub const ATTACHMENT_STASH_CAP: usize = 512;

/// Ceiling on inline payload bytes held on disk. MMCS references cost ZERO against it,
/// so blue-bubble history stays downloadable however long it gets; in practice this
/// governs how much MMS picture history survives a restart — roughly 100–300 of them at
/// carrier-downscaled sizes. A ceiling, not an allocation.
pub const ATTACHMENT_INLINE_BUDGET: usize = 32 * 1024 * 1024;

/// Disk cost of one entry. An MMCS attachment is a reference, so it costs nothing here —
/// [`Attachment::get_size`] would report the REMOTE file size, exactly the wrong number
/// for a storage budget (a 50 MB video reference costs a few hundred bytes locally).
pub fn inline_bytes(att: &Attachment) -> usize {
    match &att.a_type {
        AttachmentType::Inline(data) => data.len(),
        AttachmentType::MMCS(_) => 0,
    }
}

/// One persisted entry. `att` is METADATA ONLY — an inline payload is blanked here and
/// written to its own blob file instead.
#[derive(serde::Serialize, serde::Deserialize)]
pub struct StashedAttachment {
    pub key: String,
    pub att: Attachment,
    /// Payload lives in a blob file rather than in the index. MMCS entries never set it.
    #[serde(default)]
    pub inline_blob: bool,
}

/// Wrapper so the file's root is a dict — the safest plist shape.
#[derive(serde::Serialize, serde::Deserialize, Default)]
pub struct AttachmentStash {
    pub items: Vec<StashedAttachment>,
}

pub fn attachments_path(dir: &str) -> PathBuf {
    Path::new(dir).join("attachments.plist")
}
pub fn attachments_dir(dir: &str) -> PathBuf {
    Path::new(dir).join("attachments")
}

/// Blob file for one stash key. The key is hex-encoded rather than used directly: it
/// comes from Apple (`<msgid>:<idx>`), and hex is injective, fixed-alphabet, and cannot
/// contain a path separator or `..`, so a hostile id can never escape the directory.
pub fn blob_path(dir: &str, key: &str) -> PathBuf {
    let name: String = key.bytes().map(|b| format!("{b:02x}")).collect();
    attachments_dir(dir).join(format!("{name}.bin"))
}

/// Write via temp-file + rename. The whole point of this stash is to survive the process
/// being killed, so a torn write that loses the index would be self-defeating; rename
/// within a directory is atomic, so a reader sees either the old file or the new.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path)
}

pub fn write_blob(dir: &str, key: &str, data: &[u8]) {
    let path = blob_path(dir, key);
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Err(e) = write_atomic(&path, data) {
        log::warn!("persist attachment blob for '{key}' failed: {e}");
    }
}
pub fn read_blob(dir: &str, key: &str) -> Option<Vec<u8>> {
    std::fs::read(blob_path(dir, key)).ok()
}
pub fn remove_blob(dir: &str, key: &str) {
    let _ = std::fs::remove_file(blob_path(dir, key));
}

/// Drop blob files no live entry points at — e.g. the index was rewritten but the process
/// died before eviction deleted the payload. Keeps the directory inside its budget.
pub fn prune_orphan_blobs(dir: &str, live: &[StashedAttachment]) {
    let keep: std::collections::HashSet<PathBuf> = live
        .iter()
        .filter(|i| i.inline_blob)
        .map(|i| blob_path(dir, &i.key))
        .collect();
    let Ok(entries) = std::fs::read_dir(attachments_dir(dir)) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().is_some_and(|e| e == "bin") && !keep.contains(&path) {
            let _ = std::fs::remove_file(&path);
        }
    }
}

/// Build the index entry for one attachment, moving any inline payload out of the index.
pub fn stash_entry(key: &str, att: &Attachment) -> StashedAttachment {
    let has_inline = inline_bytes(att) > 0;
    let mut meta = att.clone();
    if has_inline {
        meta.a_type = AttachmentType::Inline(Vec::new());
    }
    StashedAttachment {
        key: key.to_string(),
        att: meta,
        inline_blob: has_inline,
    }
}

/// NOTE: plist, deliberately — NOT the serde_json used by thread_handles/group_meta.
/// `MMCSFile`'s `signature` and `key` are `Vec<u8>` tagged with rustpush's
/// `bin_serialize`/`bin_deserialize`, which call `serialize_bytes` and read back a
/// `plist::Data`. serde_json writes those as arrays of integers and then FAILS to
/// deserialize them, so the stash would silently come back empty — exactly the bug this
/// is meant to fix. plist round-trips them as `<data>`.
pub fn load_attachments(dir: &str) -> Vec<StashedAttachment> {
    let items = match plist::from_file::<_, AttachmentStash>(attachments_path(dir)) {
        Ok(stash) => stash.items,
        Err(e) => {
            // Absent on first run; only worth a line when a real file failed to parse.
            if attachments_path(dir).exists() {
                log::warn!("load attachments.plist failed: {e}");
            }
            Vec::new()
        }
    };
    // Rehydrate inline payloads from their blobs. An entry whose blob is missing is
    // DROPPED, not kept empty: an empty `Inline` would let get_attachment "succeed" and
    // hand the UI a zero-byte file, which is worse than a clean failure.
    let restored: Vec<StashedAttachment> = items
        .into_iter()
        .filter_map(|mut item| {
            if !item.inline_blob {
                return Some(item);
            }
            match read_blob(dir, &item.key) {
                Some(bytes) => {
                    item.att.a_type = AttachmentType::Inline(bytes);
                    Some(item)
                }
                None => {
                    log::warn!(
                        "attachment blob missing for '{}' — dropping the entry",
                        item.key
                    );
                    None
                }
            }
        })
        .collect();
    prune_orphan_blobs(dir, &restored);
    restored
}

/// Persist the INDEX only; blobs are written once, when the message arrives.
pub fn save_attachments(dir: &str, items: &[StashedAttachment]) {
    let stash = AttachmentStash {
        items: items
            .iter()
            .map(|i| StashedAttachment {
                key: i.key.clone(),
                att: i.att.clone(),
                inline_blob: i.inline_blob,
            })
            .collect(),
    };
    let mut buf: Vec<u8> = Vec::new();
    if let Err(e) = plist::to_writer_xml(&mut buf, &stash) {
        log::warn!("serialize attachments.plist failed: {e}");
        return;
    }
    if let Err(e) = write_atomic(&attachments_path(dir), &buf) {
        log::warn!("persist attachments.plist failed: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustpush::MMCSFile;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static N: AtomicUsize = AtomicUsize::new(0);

    /// Fresh scratch dir per test; no tempfile dependency.
    fn scratch() -> String {
        let n = N.fetch_add(1, Ordering::SeqCst);
        let dir = std::env::temp_dir().join(format!("stash-test-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.to_string_lossy().into_owned()
    }

    fn mmcs_att() -> Attachment {
        Attachment {
            a_type: AttachmentType::MMCS(MMCSFile {
                signature: (0u8..32).collect(),
                object: "6C4A9B2E-0000-4444-9999-AABBCCDDEEFF".into(),
                url: "https://content.icloud.com/mmcs/get/abc123".into(),
                key: (200u8..232).collect(),
                size: 4_812_003,
            }),
            part: 0,
            uti_type: "public.mpeg-4".into(),
            mime: "video/quicktime".into(),
            name: "IMG_0421.mov".into(),
            iris: false,
        }
    }

    fn inline_att(bytes: Vec<u8>) -> Attachment {
        Attachment {
            a_type: AttachmentType::Inline(bytes),
            part: 1,
            uti_type: "public.jpeg".into(),
            mime: "image/jpeg".into(),
            name: "mms.jpg".into(),
            iris: false,
        }
    }

    fn mmcs_of(att: &Attachment) -> &MMCSFile {
        match &att.a_type {
            AttachmentType::MMCS(m) => m,
            _ => panic!("expected MMCS"),
        }
    }
    fn inline_of(att: &Attachment) -> &[u8] {
        match &att.a_type {
            AttachmentType::Inline(d) => d,
            _ => panic!("expected inline"),
        }
    }

    /// The whole reason for the fix: an MMCS reference must survive a restart intact,
    /// because those coordinates cannot be re-derived afterwards.
    #[test]
    fn mmcs_reference_round_trips() {
        let dir = scratch();
        let att = mmcs_att();
        save_attachments(&dir, &[stash_entry("ABC:0", &att)]);

        let back = load_attachments(&dir);
        assert_eq!(back.len(), 1);
        assert_eq!(back[0].key, "ABC:0");
        assert!(!back[0].inline_blob, "an MMCS entry needs no blob");
        let (a, b) = (mmcs_of(&att), mmcs_of(&back[0].att));
        assert_eq!(a.signature, b.signature, "signature must survive plist");
        assert_eq!(a.key, b.key, "decryption key must survive plist");
        assert_eq!((&a.url, &a.object, a.size), (&b.url, &b.object, b.size));
        assert!(!blob_path(&dir, "ABC:0").exists());
    }

    #[test]
    fn inline_payload_round_trips_via_blob() {
        let dir = scratch();
        let bytes: Vec<u8> = (0..5000).map(|i| (i % 251) as u8).collect();
        write_blob(&dir, "MMS:0", &bytes);
        save_attachments(&dir, &[stash_entry("MMS:0", &inline_att(bytes.clone()))]);

        let back = load_attachments(&dir);
        assert_eq!(back.len(), 1);
        assert!(back[0].inline_blob);
        assert_eq!(inline_of(&back[0].att), &bytes[..], "payload must be identical");
    }

    /// The point of splitting blobs out: the index the app rewrites on every new
    /// attachment must NOT grow with retained payload size.
    #[test]
    fn index_stays_small_regardless_of_payload_size() {
        let dir = scratch();
        let big: Vec<u8> = vec![7u8; 1024 * 1024];
        let entries: Vec<StashedAttachment> = (0..5)
            .map(|i| {
                let key = format!("BIG:{i}");
                write_blob(&dir, &key, &big);
                stash_entry(&key, &inline_att(big.clone()))
            })
            .collect();
        save_attachments(&dir, &entries);

        let index = std::fs::metadata(attachments_path(&dir)).unwrap().len();
        assert!(
            index < 32 * 1024,
            "index must not carry payloads; 5 MB of blobs produced a {index}-byte index"
        );
        assert_eq!(load_attachments(&dir).len(), 5);
    }

    /// A missing blob must drop the entry, never resurface as a zero-byte "success".
    #[test]
    fn missing_blob_drops_entry_rather_than_returning_empty() {
        let dir = scratch();
        let bytes = vec![1u8, 2, 3, 4];
        write_blob(&dir, "GONE:0", &bytes);
        save_attachments(
            &dir,
            &[
                stash_entry("GONE:0", &inline_att(bytes)),
                stash_entry("KEEP:0", &mmcs_att()),
            ],
        );
        remove_blob(&dir, "GONE:0");

        let back = load_attachments(&dir);
        assert_eq!(back.len(), 1, "the entry with no payload must be dropped");
        assert_eq!(back[0].key, "KEEP:0");
    }

    #[test]
    fn orphan_blobs_are_pruned_live_ones_kept() {
        let dir = scratch();
        write_blob(&dir, "LIVE:0", &[9u8; 64]);
        write_blob(&dir, "ORPHAN:0", &[9u8; 64]);
        save_attachments(&dir, &[stash_entry("LIVE:0", &inline_att(vec![9u8; 64]))]);

        let back = load_attachments(&dir); // prunes as a side effect
        assert_eq!(back.len(), 1);
        assert!(blob_path(&dir, "LIVE:0").exists(), "live blob must survive");
        assert!(!blob_path(&dir, "ORPHAN:0").exists(), "orphan must be removed");
    }

    /// A hostile message id must not be able to write outside the attachments dir.
    #[test]
    fn blob_paths_are_contained_and_unique() {
        let dir = scratch();
        let evil = blob_path(&dir, "../../../etc/passwd");
        assert_eq!(evil.parent().unwrap(), attachments_dir(&dir));
        assert!(!evil.to_string_lossy().contains(".."));
        // Hex encoding is injective, so distinct keys cannot collide on one file.
        assert_ne!(blob_path(&dir, "A:1"), blob_path(&dir, "A_1"));
    }

    /// Rewriting must replace the index cleanly and leave no stray temp file behind.
    #[test]
    fn rewrite_replaces_index_and_leaves_no_temp() {
        let dir = scratch();
        save_attachments(&dir, &[stash_entry("ONE:0", &mmcs_att())]);
        save_attachments(
            &dir,
            &[
                stash_entry("TWO:0", &mmcs_att()),
                stash_entry("TWO:1", &mmcs_att()),
            ],
        );

        let back = load_attachments(&dir);
        assert_eq!(back.len(), 2, "index must be replaced, not appended to");
        assert_eq!(back[0].key, "TWO:0");
        assert!(
            !attachments_path(&dir).with_extension("tmp").exists(),
            "temp file must be renamed away, not left on disk"
        );
    }

    #[test]
    fn missing_index_is_not_an_error() {
        assert!(load_attachments(&scratch()).is_empty());
    }

    /// MMCS entries must cost nothing against the byte budget — otherwise a single video
    /// reference would report its remote size and evict everything behind it.
    #[test]
    fn only_inline_payloads_count_against_the_budget() {
        assert_eq!(inline_bytes(&mmcs_att()), 0);
        assert_eq!(inline_bytes(&inline_att(vec![0u8; 1234])), 1234);
    }
}
