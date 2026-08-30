//! Group-chat identity logic, kept dependency-free (std + serde only) so it can be
//! unit-tested on the host without the Android/rustpush toolchain. `lib.rs` uses these
//! functions for the real FFI; `identity-tests/` compiles THIS SAME FILE and runs the
//! `#[cfg(test)]` suite below (`cargo test` there), so the tests exercise the shipped
//! source, not a copy.
//!
//! The core idea (see ABSINTHE/README + the sibs & sav bug): an iMessage group is
//! identified by Apple's stable group id — the plist `gid`, which rustpush surfaces as
//! `ConversationData.sender_guid` — NOT by its member set. We key group chats as
//! "iMessage;+;<gid>" and replay that gid (plus members + name) on every outbound so a
//! reply threads into the SAME conversation instead of forking a members-only one.

use std::collections::HashMap;

/// A group's replayable identity, stored per gid-keyed chat_guid and sent on every
/// outbound. Persisted (by `lib.rs`) to group_meta.json.
#[derive(Clone, Default, PartialEq, Debug, serde::Serialize, serde::Deserialize)]
pub struct GroupMeta {
    /// The OTHER participants as canon addresses ("+1…"/"email"), sorted + deduped.
    /// (My own handle is added by rustpush at send.) Mapped to rustpush handles via
    /// `to_handle` in `lib.rs` when building ConversationData.
    pub participants: Vec<String>,
    /// The group's display name (iMessage cv_name), if it has one.
    pub cv_name: Option<String>,
    /// Which service this conversation actually runs on, learned from inbound traffic.
    /// `Some(true)` = MMS/SMS relayed by the paired iPhone, `Some(false)` = iMessage,
    /// `None` = never observed (nothing has arrived here yet).
    ///
    /// A group is NOT automatically an iMessage group. A group thread carried over Text
    /// Message Forwarding arrives as IDS cmd 140 on topic `com.apple.private.alloy.sms`
    /// and its members may not be on iMessage at all — replying to it over iMessage
    /// reaches only the members who happen to have it, and the rest get silence.
    ///
    /// This is OpenBubbles' `Chat.isRpSms` (`lib/database/io/chat.dart`), which it sets
    /// at chat creation from the inbound service and reads back in
    /// `RustPushService.getService`. `Option` rather than `bool` so an existing
    /// group_meta.json — written before this field existed — reads back as "unknown"
    /// and gets corrected by the next inbound message, instead of silently claiming
    /// every pre-existing group is iMessage.
    #[serde(default)]
    pub is_sms: Option<bool>,
}

/// Does a reply into this group have to go out as MMS/SMS? Only a conversation OBSERVED
/// to be SMS says yes: unknown means iMessage, which is both the old behaviour and the
/// right default for a group the user creates from the composer.
pub fn group_sends_sms(meta: Option<&GroupMeta>) -> bool {
    matches!(meta.and_then(|m| m.is_sms), Some(true))
}

/// The gid decision + members for a send, computed without touching global state.
/// `gid = Some(..)` means "use this real Apple group id"; `None` means the caller
/// should mint a random one (a legacy members-keyed guid that predates gid-keying).
#[derive(Clone, Default, PartialEq, Debug)]
pub struct SendIdentity {
    /// Canon member addresses to send to; empty when unknown (caller falls back).
    pub participant_addrs: Vec<String>,
    pub cv_name: Option<String>,
    pub gid: Option<String>,
}

/// Canonical handle key. MUST match the `canon` used everywhere else so a person's
/// inbound thread, outbound thread, and contact entry all key identically: scheme
/// stripped, email lowercased, phone reduced to "+<digits>" (a US 10-digit gets a +1).
pub fn canon(handle: &str) -> String {
    let s = handle.trim();
    let s = s
        .strip_prefix("tel:")
        .or_else(|| s.strip_prefix("mailto:"))
        .unwrap_or(s)
        .trim();
    if s.contains('@') {
        return s.to_lowercase();
    }
    let digits: String = s.chars().filter(|c| c.is_ascii_digit()).collect();
    if s.starts_with('+') {
        format!("+{digits}")
    } else if digits.len() == 10 {
        format!("+1{digits}")
    } else if digits.len() == 11 && digits.starts_with('1') {
        format!("+{digits}")
    } else if digits.is_empty() {
        s.to_lowercase()
    } else {
        format!("+{digits}")
    }
}

/// A group chat guid from Apple's stable group id: "iMessage;+;<gid>" (gid trimmed +
/// lowercased so it keys identically however it's cased on the wire).
pub fn group_guid_for(gid: &str) -> String {
    format!("iMessage;+;{}", gid.trim().to_lowercase())
}

/// The identifier embedded in a group chat guid ("iMessage;+;<id>" → "<id>").
pub fn group_ident(chat_guid: &str) -> String {
    chat_guid.rsplit(';').next().unwrap_or(chat_guid).to_string()
}

/// True when a group guid is keyed by an Apple group id (a UUID — no comma) rather
/// than a legacy member set ("a,b,c"). Groups always have ≥2 members, so a members-
/// keyed identifier always contains a comma; a gid never does.
pub fn is_gid_keyed(chat_guid: &str) -> bool {
    let id = group_ident(chat_guid);
    !id.is_empty() && !id.contains(',')
}

/// Sorted-canon-members CSV — the reverse-index key for the members→gid map.
pub fn members_csv(members: &[String]) -> String {
    let mut v: Vec<String> = members.iter().map(|m| canon(m)).filter(|c| !c.is_empty()).collect();
    v.sort();
    v.dedup();
    v.join(",")
}

/// The stable chat guid for an INBOUND group conversation (pure; the caller records
/// meta separately). Prefers Apple's gid; falls back to the known gid thread for the
/// same members (a gid-less group MMS), then to a legacy members-keyed guid.
pub fn resolve_group_guid(
    gid: Option<&str>,
    counterparts: &[String],
    by_members: &HashMap<String, String>,
) -> String {
    match gid.map(str::trim).filter(|g| !g.is_empty()) {
        Some(g) => group_guid_for(g),
        None => {
            let key = members_csv(counterparts);
            by_members
                .get(&key)
                .cloned()
                .unwrap_or_else(|| format!("iMessage;+;{key}"))
        }
    }
}

/// The new GroupMeta after seeing a message (pure core of `remember_group_meta`).
/// Adopts a name when present and never clobbers a known name with a nameless message;
/// keeps known members when the message carries none (a sparse control/read sync).
pub fn updated_meta(prev: Option<&GroupMeta>, members: &[String], cv_name: &Option<String>) -> GroupMeta {
    let mut participants: Vec<String> =
        members.iter().map(|m| canon(m)).filter(|c| !c.is_empty()).collect();
    participants.sort();
    participants.dedup();
    GroupMeta {
        participants: if participants.is_empty() {
            prev.map(|e| e.participants.clone()).unwrap_or_default()
        } else {
            participants
        },
        cv_name: cv_name.clone().or_else(|| prev.and_then(|e| e.cv_name.clone())),
        // Carried, never derived here: the service is learned from the inbound
        // message's own type, which this function does not see. `lib.rs`'s
        // `remember_group_service` is the only writer.
        is_sms: prev.and_then(|e| e.is_sms),
    }
}

/// What to put in an outbound ConversationData for a GROUP `chat` (pure core of
/// `conv_data_for`). Replays the stored gid + members + name; a legacy members-keyed
/// guid yields `gid = None` (caller mints a random one) and members parsed from the guid.
pub fn send_identity(chat: &str, meta: Option<&GroupMeta>) -> SendIdentity {
    let participant_addrs = match meta {
        Some(m) if !m.participants.is_empty() => m.participants.clone(),
        _ => {
            if is_gid_keyed(chat) {
                Vec::new() // gid-keyed but no stored members — caller falls back
            } else {
                // legacy "iMessage;+;a,b,c" — members are in the guid itself
                group_ident(chat)
                    .split(',')
                    .filter(|s| !s.is_empty())
                    .map(|s| s.to_string())
                    .collect()
            }
        }
    };
    SendIdentity {
        participant_addrs,
        cv_name: meta.and_then(|m| m.cv_name.clone()),
        gid: if is_gid_keyed(chat) { Some(group_ident(chat)) } else { None },
    }
}

/// The guid to actually SEND on for `chat`. A legacy member-keyed group guid
/// ("iMessage;+;a,b,c") — e.g. a reply typed in an old room that hasn't folded yet —
/// redirects to its gid-keyed guid so the message threads into the real conversation.
/// The redirect fires ONLY when exactly one known group has that member set; if two
/// groups share the same people (the very ambiguity that caused the original bug) we do
/// NOT guess and leave the guid as-is. 1:1 and already-gid guids pass straight through.
pub fn effective_send_guid(chat: &str, groups: &HashMap<String, GroupMeta>) -> String {
    if !chat.contains(";+;") || is_gid_keyed(chat) {
        return chat.to_string();
    }
    let key = members_csv(
        &group_ident(chat)
            .split(',')
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect::<Vec<_>>(),
    );
    let mut hits = groups
        .iter()
        .filter(|(guid, m)| is_gid_keyed(guid) && members_csv(&m.participants) == key)
        .map(|(guid, _)| guid);
    match (hits.next(), hits.next()) {
        (Some(guid), None) => guid.clone(), // exactly one match → safe to redirect
        _ => chat.to_string(),              // none, or ambiguous → don't guess
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // The five members from the real "sibs & sav" log.
    fn sibs() -> Vec<String> {
        vec![
            "+18048334449".into(),
            "+18048336158".into(),
            "+18048336200".into(),
            "+18048339340".into(),
            "+18048698183".into(),
        ]
    }

    #[test]
    fn canon_normalizes_handles() {
        assert_eq!(canon("tel:+18048334449"), "+18048334449");
        assert_eq!(canon("8048334449"), "+18048334449"); // 10-digit US → +1
        assert_eq!(canon("+1 (804) 833-4449"), "+18048334449"); // punctuation stripped
        assert_eq!(canon("18048334449"), "+18048334449"); // 11-digit leading 1
        assert_eq!(canon("mailto:Sav@ICLOUD.com"), "sav@icloud.com"); // email lowercased
    }

    #[test]
    fn group_guid_is_gid_keyed_and_lowercased() {
        let g = group_guid_for("C71A3485-5E0D-4974-8B3D-C55BF2EDEA8A");
        assert_eq!(g, "iMessage;+;c71a3485-5e0d-4974-8b3d-c55bf2edea8a");
        assert_eq!(group_ident(&g), "c71a3485-5e0d-4974-8b3d-c55bf2edea8a");
        assert!(is_gid_keyed(&g));
    }

    #[test]
    fn legacy_member_set_guid_is_not_gid_keyed() {
        let legacy = format!("iMessage;+;{}", members_csv(&sibs()));
        assert!(!is_gid_keyed(&legacy)); // contains commas → members, not a gid
        assert!(is_gid_keyed("iMessage;+;chat123")); // no comma → treated as a gid
    }

    #[test]
    fn members_csv_canon_sorts_and_dedups() {
        // Same people, different formats/order/dupes → one stable key.
        let a = members_csv(&sibs());
        let b = members_csv(&vec![
            "tel:+18048339340".into(),
            "(804) 833-6200".into(),
            "8048334449".into(),
            "+18048336158".into(),
            "+18048698183".into(),
            "8048334449".into(), // dupe
        ]);
        assert_eq!(a, b);
        assert_eq!(a, "+18048334449,+18048336158,+18048336200,+18048339340,+18048698183");
    }

    #[test]
    fn resolve_prefers_gid() {
        let guid = resolve_group_guid(Some("C71A3485"), &sibs(), &HashMap::new());
        assert_eq!(guid, "iMessage;+;c71a3485");
    }

    #[test]
    fn resolve_folds_gidless_message_onto_known_gid_thread() {
        // A gid-less group MMS for the same people maps onto the known gid thread
        // instead of forking a members-keyed guid.
        let mut idx = HashMap::new();
        idx.insert(members_csv(&sibs()), "iMessage;+;c71a3485".to_string());
        let guid = resolve_group_guid(None, &sibs(), &idx);
        assert_eq!(guid, "iMessage;+;c71a3485");
    }

    #[test]
    fn resolve_falls_back_to_members_when_unknown() {
        let guid = resolve_group_guid(None, &sibs(), &HashMap::new());
        assert_eq!(guid, format!("iMessage;+;{}", members_csv(&sibs())));
    }

    #[test]
    fn updated_meta_learns_then_preserves_name() {
        // First a named message establishes "sibs & sav".
        let m1 = updated_meta(None, &sibs(), &Some("sibs & sav".into()));
        assert_eq!(m1.cv_name.as_deref(), Some("sibs & sav"));
        assert_eq!(m1.participants.len(), 5);
        // A LATER nameless message must NOT wipe the known name (the routing anchor).
        let m2 = updated_meta(Some(&m1), &sibs(), &None);
        assert_eq!(m2.cv_name.as_deref(), Some("sibs & sav"));
        // A participant-less control message keeps the known members.
        let m3 = updated_meta(Some(&m2), &[], &None);
        assert_eq!(m3.participants.len(), 5);
        // A genuine rename is adopted.
        let m4 = updated_meta(Some(&m3), &sibs(), &Some("sibs & sav 2".into()));
        assert_eq!(m4.cv_name.as_deref(), Some("sibs & sav 2"));
    }

    #[test]
    fn an_unobserved_group_still_sends_imessage() {
        // The default has to stay blue: a group the user just created from the composer
        // has no inbound history, and must not be downgraded to a text.
        assert!(!group_sends_sms(None));
        assert!(!group_sends_sms(Some(&GroupMeta::default())));
    }

    #[test]
    fn a_group_observed_as_sms_replies_as_sms() {
        // The bug: this group's members are not on iMessage, so a blue reply reaches
        // nobody. Only an explicit Some(true) flips it.
        let mms = GroupMeta { is_sms: Some(true), ..Default::default() };
        assert!(group_sends_sms(Some(&mms)));
        let blue = GroupMeta { is_sms: Some(false), ..Default::default() };
        assert!(!group_sends_sms(Some(&blue)));
    }

    #[test]
    fn learning_a_name_or_members_never_forgets_the_service() {
        // updated_meta runs on every inbound message. If it dropped is_sms, the group
        // would revert to blue the moment anyone renamed it or a member list arrived.
        let learned = GroupMeta { is_sms: Some(true), ..Default::default() };
        let after = updated_meta(Some(&learned), &sibs(), &Some("sibs & sav".into()));
        assert_eq!(after.is_sms, Some(true));
        assert_eq!(after.cv_name.as_deref(), Some("sibs & sav"));
        // …and a group that has never been observed stays unknown, not false.
        let fresh = updated_meta(None, &sibs(), &None);
        assert_eq!(fresh.is_sms, None);
    }

    #[test]
    fn send_identity_replays_real_gid_and_name() {
        // THE FIX: sending to the gid-keyed "sibs & sav" replays the real gid + name +
        // members — NOT cv_name:None + a fresh random gid (the bug that forked the thread).
        let guid = "iMessage;+;c71a3485-5e0d-4974-8b3d-c55bf2edea8a";
        let meta = GroupMeta { participants: sibs(), cv_name: Some("sibs & sav".into()), ..Default::default() };
        let id = send_identity(guid, Some(&meta));
        assert_eq!(id.gid.as_deref(), Some("c71a3485-5e0d-4974-8b3d-c55bf2edea8a"));
        assert_eq!(id.cv_name.as_deref(), Some("sibs & sav"));
        assert_eq!(id.participant_addrs, sibs());
    }

    #[test]
    fn send_identity_legacy_guid_has_no_real_gid() {
        // A legacy members-keyed guid can't supply a real gid → caller mints a random
        // one, and members come from the guid itself.
        let legacy = format!("iMessage;+;{}", members_csv(&sibs()));
        let id = send_identity(&legacy, None);
        assert_eq!(id.gid, None);
        assert_eq!(id.participant_addrs, sibs());
    }

    #[test]
    fn two_same_member_groups_stay_distinct() {
        // The whole point of gid-keying: a named group and an unnamed group with the
        // SAME people are different conversations, so a reply can't cross over.
        let named = resolve_group_guid(Some("gid-aaaa"), &sibs(), &HashMap::new());
        let other = resolve_group_guid(Some("gid-bbbb"), &sibs(), &HashMap::new());
        assert_ne!(named, other);
    }

    #[test]
    fn effective_send_guid_redirects_legacy_to_gid_when_unambiguous() {
        // A reply typed in an old member-keyed room redirects to the one known gid.
        let mut groups: HashMap<String, GroupMeta> = HashMap::new();
        let gid = "iMessage;+;c71a3485-5e0d-4974-8b3d-c55bf2edea8a";
        groups.insert(gid.to_string(), GroupMeta { participants: sibs(), cv_name: Some("sibs & sav".into()), ..Default::default() });
        let legacy = format!("iMessage;+;{}", members_csv(&sibs()));
        assert_eq!(effective_send_guid(&legacy, &groups), gid);
    }

    #[test]
    fn effective_send_guid_passthrough_when_unknown() {
        // No gid learned for these people yet → leave the legacy guid alone.
        let groups: HashMap<String, GroupMeta> = HashMap::new();
        let legacy = format!("iMessage;+;{}", members_csv(&sibs()));
        assert_eq!(effective_send_guid(&legacy, &groups), legacy);
    }

    #[test]
    fn effective_send_guid_no_guess_when_ambiguous() {
        // Two groups with the SAME members → never guess; keep the legacy guid.
        let mut groups: HashMap<String, GroupMeta> = HashMap::new();
        groups.insert("iMessage;+;gid-aaaa".into(), GroupMeta { participants: sibs(), cv_name: Some("sibs & sav".into()), ..Default::default() });
        groups.insert("iMessage;+;gid-bbbb".into(), GroupMeta { participants: sibs(), cv_name: None, ..Default::default() });
        let legacy = format!("iMessage;+;{}", members_csv(&sibs()));
        assert_eq!(effective_send_guid(&legacy, &groups), legacy);
    }

    #[test]
    fn effective_send_guid_passes_through_gid_and_dm() {
        let mut groups: HashMap<String, GroupMeta> = HashMap::new();
        groups.insert("iMessage;+;c71a3485".into(), GroupMeta { participants: sibs(), cv_name: None, ..Default::default() });
        assert_eq!(effective_send_guid("iMessage;+;c71a3485", &groups), "iMessage;+;c71a3485"); // already gid
        assert_eq!(effective_send_guid("iMessage;-;+18048334449", &groups), "iMessage;-;+18048334449"); // 1:1
    }

    #[test]
    fn end_to_end_receive_then_reply_threads_into_same_group() {
        // Mirrors the log: receive a sibs & sav message (gid + name), then reply.
        let gid = "C71A3485-5E0D-4974-8B3D-C55BF2EDEA8A";
        let mut by_members: HashMap<String, String> = HashMap::new();

        // INBOUND
        let guid = resolve_group_guid(Some(gid), &sibs(), &by_members);
        let meta = updated_meta(None, &sibs(), &Some("sibs & sav".into()));
        by_members.insert(members_csv(&meta.participants), guid.clone());

        // OUTBOUND (reply)
        let id = send_identity(&guid, Some(&meta));
        // The reply carries the SAME gid it was received on, and the group's name —
        // so Apple threads it into "sibs & sav", not a new members-only chat.
        assert_eq!(id.gid.as_deref(), Some(&group_ident(&guid)[..]));
        assert_eq!(group_guid_for(&id.gid.unwrap()), guid);
        assert_eq!(id.cv_name.as_deref(), Some("sibs & sav"));
    }
}
