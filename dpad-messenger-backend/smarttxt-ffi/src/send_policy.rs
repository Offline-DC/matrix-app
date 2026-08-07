//! Send-routing and handle-reconciliation POLICY, isolated from every I/O type so it
//! can be exercised on the host (see ../policy-tests/). Same pattern as
//! `group_identity.rs` and `inbound.rs`: std only, no rustpush, no JNI.
//!
//! WHY THESE TWO DECISIONS LIVE TOGETHER IN A TESTABLE FILE. Both were bugs that could
//! not be provoked on demand on a device, and both were invisible in normal use:
//!
//!   * **Routing.** `decide_route` used to send a phone number as green SMS whenever the
//!     IDS lookup ERRORED and SMS forwarding was live. An error is the absence of an
//!     answer, not a negative one — rustpush only reports "not on iMessage" from a
//!     status-0 `id-query` (`ids/user.rs:993`). So a 15s APS timeout, a 6004 "please try
//!     again", or the identity resource being mid-re-registration silently downgraded a
//!     real iMessage contact to a text. Reproducing that on a handset means inducing an
//!     IDS failure at the exact moment of a send. Here it is three lines of test.
//!
//!   * **Reconciliation.** The handle check fired on set INEQUALITY, which is true in
//!     both directions — but only one direction is fixable by re-registering. The other
//!     (we hold a handle IDS no longer vends) survives the re-register, because
//!     rustpush's `register()` only ever INSERTS into `user.registration`
//!     (`ids/user.rs:1180`) and never clears. That state re-fires on every connect,
//!     forever. Proving the fix means proving a set relation, which is exactly what a
//!     unit test is for.
//!
//! Neither function performs I/O or logging. Callers map the returned decision onto real
//! routes/actions and own the user-facing strings.

use std::collections::BTreeSet;

// ---------------------------------------------------------------------------
// Send routing
// ---------------------------------------------------------------------------

/// What Apple actually told us about a recipient, as three distinct states.
///
/// The whole green-bubble bug was the absence of the third one: the old code had a
/// boolean (`valid` non-empty or not) plus an error path that guessed. "Apple says no"
/// and "Apple didn't answer" are not the same fact and must not produce the same route.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LookupOutcome {
    /// `id-query` returned status 0 and listed this handle. Definitely on iMessage.
    OnIMessage,
    /// `id-query` returned status 0 and the handle had no identities. Definitely NOT on
    /// iMessage. This is the ONLY state that may produce a green send.
    NotOnIMessage,
    /// No usable answer: transport timeout, non-zero IDS status (6004/6009/…), parse
    /// failure, or the identity resource being down or re-registering.
    NoAnswer,
}

/// Why a send can't go out at all, so the caller can attach the user-facing copy.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BlockReason {
    /// An email address that isn't on iMessage — SMS can't carry it.
    EmailNotOnIMessage,
    /// A phone number that isn't on iMessage, with Text Message Forwarding off.
    SmsForwardingOff,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RouteKind {
    IMessage,
    Sms,
    Block(BlockReason),
}

/// Decide where an outgoing 1:1 message goes.
///
/// The invariant, and the reason this function exists at all:
/// **`RouteKind::Sms` is reachable only from [`LookupOutcome::NotOnIMessage`].**
/// `NoAnswer` always routes iMessage, matching the composer-colour probe
/// (`nativeIsIMessage`), which has always failed blue. A doomed encrypt surfaces as a
/// visible, retryable "Not Delivered"; a wrong green is silent and irreversible.
pub fn route_for(outcome: LookupOutcome, is_phone: bool, sms_active: bool) -> RouteKind {
    match outcome {
        LookupOutcome::OnIMessage => RouteKind::IMessage,
        // Never a negative. Never green.
        LookupOutcome::NoAnswer => RouteKind::IMessage,
        LookupOutcome::NotOnIMessage => {
            if !is_phone {
                RouteKind::Block(BlockReason::EmailNotOnIMessage)
            } else if !sms_active {
                RouteKind::Block(BlockReason::SmsForwardingOff)
            } else {
                RouteKind::Sms
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Handle reconciliation
// ---------------------------------------------------------------------------

/// What to do about a difference between the handles IDS vends and the ones we
/// registered. `stale` is carried on every variant because it is diagnostic in all of
/// them — it is the signature of the state that could loop forever.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReconcileAction {
    /// Our registration covers everything IDS vends. `stale` may still be non-empty
    /// (we hold handles IDS no longer lists); that is NOT actionable — see the module
    /// doc — so it is reported and nothing else.
    UpToDate { stale: Vec<String> },
    /// IDS vends handles we never registered and the cooldown has elapsed. Ask rustpush
    /// to re-register with `refresh()` (never `refresh_now()`, which cancels its backoff).
    Reregister { missing: Vec<String>, stale: Vec<String> },
    /// Actionable, but we tried too recently. Defer to rustpush's command-66 path.
    Deferred { missing: Vec<String>, stale: Vec<String>, age_s: u64, cooldown_s: u64 },
}

/// Decide whether an IDS handle mismatch is worth a re-registration.
///
/// `last_attempt_age_s` is `None` when we have never attempted one (or the persisted
/// stamp is missing/corrupt/from a clock that moved backwards — all of which mean "no
/// evidence of a recent attempt"; the cooldown is a rate limit, not a correctness gate).
///
/// Two properties this encodes, both of which the previous set-inequality test violated:
///  1. A registration that is a SUPERSET of what IDS vends is up to date. Extra local
///     entries cannot be cleared by re-registering, so treating them as actionable is an
///     unbounded loop with no exit condition.
///  2. Even a genuinely actionable mismatch is rate-limited, so a handset stuck in the
///     "Apple omits a URI it also vends" state hits Apple once per window rather than
///     once per boot.
pub fn reconcile_action(
    possible: &BTreeSet<String>,
    registered: &BTreeSet<String>,
    last_attempt_age_s: Option<u64>,
    cooldown_s: u64,
) -> ReconcileAction {
    let missing: Vec<String> = possible.difference(registered).cloned().collect();
    let stale: Vec<String> = registered.difference(possible).cloned().collect();

    if missing.is_empty() {
        return ReconcileAction::UpToDate { stale };
    }
    if let Some(age_s) = last_attempt_age_s {
        if age_s < cooldown_s {
            return ReconcileAction::Deferred { missing, stale, age_s, cooldown_s };
        }
    }
    ReconcileAction::Reregister { missing, stale }
}

// ---------------------------------------------------------------------------
// Outbound receipts (IDS 101 delivered / IDS 120 decrypt failure)
// ---------------------------------------------------------------------------

/// What to do with a receipt Apple sent us about a message WE sent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReceiptAction {
    /// Do nothing. The `&'static str` is the reason, for the log — every ignore here is
    /// a deliberate policy choice, and a silent drop is what made this class of bug
    /// invisible in the first place.
    Ignore(&'static str),
    /// The message reached the other party. Advance the bubble.
    MarkDelivered,
    /// The message could not be decrypted by a device that should have been able to.
    /// Fail the bubble so the user can resend.
    MarkFailed,
}

/// IDS 101. `from_self` = the receipt's sender is one of MY OWN registered handles.
///
/// A fan-out to a 1:1 encrypts to every device on BOTH sides, so my iPad acknowledging
/// its own copy produces a 101 that says nothing about whether the recipient got it.
/// OpenBubbles drops those explicitly (`rustpush_service.dart`: `return; // delivered to
/// other devices is not`), and so do we.
///
/// `tracked` = we hold a send-time record for this guid. Without one there is no chat to
/// attribute the receipt to, and guessing from the sender would put a group message in a
/// 1:1 thread.
pub fn delivered_action(tracked: bool, from_self: bool) -> ReceiptAction {
    if from_self {
        return ReceiptAction::Ignore("receipt from one of my own devices, not the peer");
    }
    if !tracked {
        return ReceiptAction::Ignore("no send-time record for this guid");
    }
    ReceiptAction::MarkDelivered
}

/// IDS 120. A port of OpenBubbles' `Message_Error` handler, guard for guard.
///
/// The two guards are what stop this from being a false-alarm generator, and both are
/// load-bearing against real captures:
///
///  1. **`already_delivered`.** Straggler devices report `-6` long after the fact. In one
///     customer bundle a message was acked by three device tokens in 1.3 s and reacted to
///     with a tapback 14 s later, then drew a `-6` fifty-seven minutes afterwards from a
///     fourth device of the same person. OpenBubbles' comment on this line reads "if
///     we've been delivered, well :shrug: probably some stray device complaining."
///
///  2. **`from_self`.** Only a complaint from one of MY OWN handles is trusted. A peer
///     may own several devices and a single broken one proves nothing about the others —
///     whereas my own devices are guaranteed recipients of every message I send, so if
///     they cannot decrypt it, nobody could. This is the inverse of the 101 rule, and
///     deliberately so.
/// What an SMS send-confirmation from the paired iPhone means (IDS 146 = sent, 149 =
/// failed). This is the ONLY confirmation a green message ever gets: the recipient is
/// not on iMessage, so no Apple device can send a 101, and without this a green bubble
/// stays "Sending…" forever no matter how well the text landed.
///
/// The `from_self` rule is the opposite of [delivered_action]'s and that is not a
/// mistake. A 101 from my own handle is one of MY devices acking MY message, which says
/// nothing about the peer. A 146 from my own handle is my paired iPhone reporting that
/// it put the message on the cellular network — that IS the confirmation, and it can
/// only legitimately come from me. A 146 attributed to anyone else is not something we
/// should act on.
/// What to tell a user whose send failed because Apple has rate-limited the account.
///
/// Returns `None` when there is no wait to quote, so the caller keeps its existing
/// message. A rate limit we can put a number on is worth its own wording; a resource
/// failure with no retry (`retry_wait: None`) is a different, permanent condition and
/// has never been seen in the field, so this deliberately does not guess at it.
///
/// The "don't re-register" sentence is not padding. Apple's own text says retrying makes
/// the block longer, and the handset that produced this had just re-registered three
/// times in seventeen minutes — the advice the UI gave instead (toggle Text Message
/// Forwarding) sent the user to fix something that was not broken.
pub fn rate_limited_message(deferred: Option<(u64, bool)>) -> Option<String> {
    let (seconds, connectivity) = deferred?;
    Some(if connectivity {
        // Booting with no signal produces the SAME wrapper as a rate limit — a
        // ResourceFailure with a 300 s window — so the wait alone cannot tell them
        // apart. Blaming Apple here would hide the one thing the user can fix.
        format!(
            "Can't reach Apple right now - check your connection. \
             It will retry on its own in {}.",
            approx_wait(seconds)
        )
    } else {
        format!(
            "iMessage is temporarily rate-limited by Apple. Try again in {}. \
             Don't re-register or reinstall - that makes it last longer.",
            approx_wait(seconds)
        )
    })
}

/// A wait a person can act on. Deliberately vague — the number is Apple's estimate, and
/// rounding UP means the user who waits is never told to come back too early.
fn approx_wait(seconds: u64) -> String {
    if seconds <= 90 {
        return "about a minute".to_string();
    }
    let minutes = seconds.div_ceil(60);
    if minutes < 60 {
        return format!("about {minutes} minutes");
    }
    let hours = minutes.div_ceil(60);
    if hours == 1 {
        "about an hour".to_string()
    } else {
        format!("about {hours} hours")
    }
}

pub fn sms_confirm_action(
    tracked: bool,
    already_delivered: bool,
    from_self: bool,
    sent_ok: bool,
) -> ReceiptAction {
    if !tracked {
        return ReceiptAction::Ignore("no send-time record for this guid");
    }
    if !from_self {
        return ReceiptAction::Ignore("only my own paired iPhone can confirm an SMS send");
    }
    if already_delivered {
        // Apple replays receipts on every APS reconnect, so a second 146 is routine.
        // A 149 arriving after a 146 is a stray from a device that lost the race; either
        // way the message has already been confirmed and must not be re-marked.
        return ReceiptAction::Ignore("already confirmed");
    }
    if sent_ok {
        ReceiptAction::MarkDelivered
    } else {
        ReceiptAction::MarkFailed
    }
}

pub fn error_action(tracked: bool, already_delivered: bool, from_self: bool) -> ReceiptAction {
    if !tracked {
        return ReceiptAction::Ignore("no send-time record for this guid");
    }
    if already_delivered {
        return ReceiptAction::Ignore("already delivered — stray device complaining");
    }
    if !from_self {
        return ReceiptAction::Ignore("reported by a peer device, not one of mine");
    }
    ReceiptAction::MarkFailed
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set(items: &[&str]) -> BTreeSet<String> {
        items.iter().map(|s| s.to_string()).collect()
    }

    // -- routing ------------------------------------------------------------

    /// THE REGRESSION. A failed/timed-out/errored lookup must never produce a green
    /// send, for any combination of recipient type and forwarding state. Before the fix,
    /// the (phone, forwarding-on) case returned SMS — and forwarding is on for
    /// essentially the whole install base, since it latches true on any inbound
    /// forwarded text and is seeded true by the OpenBubbles migration.
    #[test]
    fn no_answer_never_routes_sms() {
        for is_phone in [true, false] {
            for sms_active in [true, false] {
                assert_eq!(
                    route_for(LookupOutcome::NoAnswer, is_phone, sms_active),
                    RouteKind::IMessage,
                    "NoAnswer must fail blue (is_phone={is_phone}, sms_active={sms_active})"
                );
            }
        }
    }

    /// The composer probe (`nativeIsIMessage`) fails blue on exactly the same error.
    /// They used to disagree, so the UI showed a blue composer while the send went out
    /// green — which is why this never reproduced in testing.
    #[test]
    fn no_answer_agrees_with_composer_probe() {
        let probe_says_imessage = true; // nativeIsIMessage returns JNI_TRUE on Err
        let route = route_for(LookupOutcome::NoAnswer, true, true);
        assert_eq!(probe_says_imessage, route == RouteKind::IMessage);
    }

    /// Green is reachable, but only from a status-0 answer that listed no identities.
    #[test]
    fn confirmed_negative_phone_with_forwarding_routes_sms() {
        assert_eq!(route_for(LookupOutcome::NotOnIMessage, true, true), RouteKind::Sms);
    }

    #[test]
    fn confirmed_negative_phone_without_forwarding_blocks() {
        assert_eq!(
            route_for(LookupOutcome::NotOnIMessage, true, false),
            RouteKind::Block(BlockReason::SmsForwardingOff)
        );
    }

    /// Email can never fall back to SMS regardless of forwarding state.
    #[test]
    fn confirmed_negative_email_always_blocks() {
        for sms_active in [true, false] {
            assert_eq!(
                route_for(LookupOutcome::NotOnIMessage, false, sms_active),
                RouteKind::Block(BlockReason::EmailNotOnIMessage)
            );
        }
    }

    #[test]
    fn on_imessage_always_routes_imessage() {
        for is_phone in [true, false] {
            for sms_active in [true, false] {
                assert_eq!(
                    route_for(LookupOutcome::OnIMessage, is_phone, sms_active),
                    RouteKind::IMessage
                );
            }
        }
    }

    /// Restated as the single invariant, over the whole input space.
    #[test]
    fn sms_is_reachable_only_from_a_confirmed_negative() {
        for outcome in [LookupOutcome::OnIMessage, LookupOutcome::NotOnIMessage, LookupOutcome::NoAnswer] {
            for is_phone in [true, false] {
                for sms_active in [true, false] {
                    if route_for(outcome, is_phone, sms_active) == RouteKind::Sms {
                        assert_eq!(outcome, LookupOutcome::NotOnIMessage);
                    }
                }
            }
        }
    }

    // -- reconciliation -----------------------------------------------------

    #[test]
    fn identical_sets_are_up_to_date() {
        let a = set(&["tel:+15551234567", "mailto:a@b.com"]);
        assert_eq!(
            reconcile_action(&a, &a, None, 3600),
            ReconcileAction::UpToDate { stale: vec![] }
        );
    }

    /// THE REGRESSION. Registration is a strict SUPERSET of what IDS vends — the old
    /// `possible != my` test fired here, but re-registering cannot clear a stale entry
    /// (rustpush's `register()` only inserts into `user.registration`, never clears), so
    /// the mismatch survived and re-fired on the next connect, forever.
    #[test]
    fn stale_local_handles_do_not_trigger_a_reregister() {
        let possible = set(&["tel:+15551234567"]);
        let registered = set(&["tel:+15551234567", "mailto:dropped@old.com"]);
        assert_eq!(
            reconcile_action(&possible, &registered, None, 3600),
            ReconcileAction::UpToDate { stale: vec!["mailto:dropped@old.com".to_string()] },
            "a superset registration is up to date; re-registering cannot shrink it"
        );
    }

    /// The one actionable direction: IDS vends something we never registered.
    #[test]
    fn unregistered_handle_triggers_a_reregister() {
        let possible = set(&["tel:+15551234567", "mailto:new@b.com"]);
        let registered = set(&["tel:+15551234567"]);
        assert_eq!(
            reconcile_action(&possible, &registered, None, 3600),
            ReconcileAction::Reregister {
                missing: vec!["mailto:new@b.com".to_string()],
                stale: vec![]
            }
        );
    }

    #[test]
    fn actionable_mismatch_is_rate_limited() {
        let possible = set(&["tel:+15551234567", "mailto:new@b.com"]);
        let registered = set(&["tel:+15551234567"]);
        assert_eq!(
            reconcile_action(&possible, &registered, Some(60), 3600),
            ReconcileAction::Deferred {
                missing: vec!["mailto:new@b.com".to_string()],
                stale: vec![],
                age_s: 60,
                cooldown_s: 3600,
            }
        );
    }

    #[test]
    fn cooldown_expiry_allows_another_attempt() {
        let possible = set(&["tel:+15551234567", "mailto:new@b.com"]);
        let registered = set(&["tel:+15551234567"]);
        match reconcile_action(&possible, &registered, Some(3600), 3600) {
            ReconcileAction::Reregister { .. } => {}
            other => panic!("expected Reregister at exactly the cooldown boundary, got {other:?}"),
        }
    }

    /// The permanent-mismatch shape: a handle IDS vends that register never echoes back,
    /// AND a stale local entry. Actionable (missing is non-empty) but the cooldown is the
    /// only thing preventing a register loop — so assert it still engages here.
    #[test]
    fn simultaneous_missing_and_stale_is_actionable_but_still_rate_limited() {
        let possible = set(&["tel:+15551234567", "mailto:new@b.com"]);
        let registered = set(&["tel:+15551234567", "mailto:dropped@old.com"]);
        assert_eq!(
            reconcile_action(&possible, &registered, None, 3600),
            ReconcileAction::Reregister {
                missing: vec!["mailto:new@b.com".to_string()],
                stale: vec!["mailto:dropped@old.com".to_string()],
            }
        );
        assert!(matches!(
            reconcile_action(&possible, &registered, Some(5), 3600),
            ReconcileAction::Deferred { .. }
        ));
    }

    /// An account with no handles at all must not be read as "IDS vends nothing new".
    /// It is up to date by definition; the failure there is upstream, not here.
    #[test]
    fn empty_sets_are_up_to_date() {
        assert_eq!(
            reconcile_action(&BTreeSet::new(), &BTreeSet::new(), None, 3600),
            ReconcileAction::UpToDate { stale: vec![] }
        );
    }

    /// A reboot loop must not become a register loop: repeated calls inside the cooldown
    /// stay deferred no matter how many times they happen.
    #[test]
    fn repeated_boots_inside_the_cooldown_stay_deferred() {
        let possible = set(&["tel:+15551234567", "mailto:new@b.com"]);
        let registered = set(&["tel:+15551234567"]);
        for age_s in [0u64, 1, 60, 3599] {
            assert!(
                matches!(
                    reconcile_action(&possible, &registered, Some(age_s), 3600),
                    ReconcileAction::Deferred { .. }
                ),
                "age {age_s}s is inside the cooldown and must defer"
            );
        }
    }

    // ---- receipts ---------------------------------------------------------

    /// The headline rule: only a receipt from the OTHER party means delivered. My own
    /// devices ack every message I send, so trusting those would mark a message
    /// delivered even when the recipient never got it — which is the bug this whole
    /// change exists to remove, just in the other direction.
    #[test]
    fn my_own_devices_acking_my_message_is_not_delivery() {
        assert_eq!(
            delivered_action(true, true),
            ReceiptAction::Ignore("receipt from one of my own devices, not the peer")
        );
        assert_eq!(delivered_action(true, false), ReceiptAction::MarkDelivered);
    }

    /// An untracked guid is unattributable — we cannot name its chat, so the only safe
    /// action is none. Covers receipts for messages sent from another of my devices and
    /// receipts that outlived the FIFO cap.
    #[test]
    fn untracked_receipts_do_nothing() {
        assert_eq!(
            delivered_action(false, false),
            ReceiptAction::Ignore("no send-time record for this guid")
        );
        assert_eq!(
            error_action(false, false, true),
            ReceiptAction::Ignore("no send-time record for this guid")
        );
    }

    /// The exact shape observed in the field: message acked by three of the recipient's
    /// device tokens within 1.3 s, tapback 14 s later, `-6` from a fourth device 57 min
    /// after that. It must not turn a delivered-and-reacted-to message red.
    #[test]
    fn a_straggler_complaint_after_delivery_is_ignored() {
        assert_eq!(
            error_action(true, true, true),
            ReceiptAction::Ignore("already delivered — stray device complaining")
        );
        assert_eq!(
            error_action(true, true, false),
            ReceiptAction::Ignore("already delivered — stray device complaining")
        );
    }

    /// A peer's device failing to decrypt proves nothing — people own several devices
    /// and a single stale one is routine. Only my own handles are trusted to fail a
    /// message, because they receive a copy of everything I send.
    #[test]
    fn only_my_own_handles_can_fail_a_message() {
        assert_eq!(
            error_action(true, false, false),
            ReceiptAction::Ignore("reported by a peer device, not one of mine")
        );
        assert_eq!(error_action(true, false, true), ReceiptAction::MarkFailed);
    }

    /// The two receipt kinds must never be able to produce each other's outcome — the
    /// call sites rely on this to keep their `unreachable!` arms honest.
    #[test]
    fn a_rate_limit_is_reported_as_a_wait_not_a_settings_change() {
        // The captured case: Apple returned retry_wait 300 and the UI told the user to
        // toggle iPhone settings, which did nothing.
        let msg = rate_limited_message(Some((300, false))).expect("a wait we can quote");
        assert!(msg.contains("about 5 minutes"), "{msg}");
        assert!(msg.contains("rate-limited"), "{msg}");
        // The counter-advice matters as much as the number.
        assert!(msg.contains("re-register"), "{msg}");
        // …and it must NOT send them to Text Message Forwarding.
        assert!(!msg.contains("Forwarding"), "{msg}");
    }

    #[test]
    fn no_signal_is_not_blamed_on_apple() {
        // The Wi-Fi-off boot: same 300 s wrapper, but the inner error was DNS. Telling
        // this user they are rate-limited is false AND hides the actual fix.
        let msg = rate_limited_message(Some((300, true))).expect("a wait we can quote");
        assert!(msg.contains("connection"), "{msg}");
        assert!(!msg.contains("rate-limited"), "{msg}");
        // The wait still appears — it is what stops them restarting the app.
        assert!(msg.contains("about 5 minutes"), "{msg}");
    }

    #[test]
    fn waits_round_up_so_nobody_is_told_to_come_back_early() {
        assert!(rate_limited_message(Some((30, false))).unwrap().contains("about a minute"));
        assert!(rate_limited_message(Some((90, false))).unwrap().contains("about a minute"));
        // 91s is more than a minute, and rounds UP to two rather than down to one.
        assert!(rate_limited_message(Some((91, false))).unwrap().contains("about 2 minutes"));
        assert!(rate_limited_message(Some((3600, false))).unwrap().contains("about an hour"));
        assert!(rate_limited_message(Some((5400, false))).unwrap().contains("about 2 hours"));
    }

    #[test]
    fn no_quotable_wait_leaves_the_existing_message_alone() {
        // retry_wait: None is a permanent resource failure, not a rate limit. The caller
        // must fall through to its own wording rather than invent a duration.
        assert_eq!(rate_limited_message(None), None);
    }

    #[test]
    fn a_green_message_is_confirmed_by_my_own_iphone() {
        // The bug: this is the only confirmation a green send ever gets, and ignoring it
        // left the bubble on "Sending…" even though the text had been transmitted.
        assert_eq!(
            sms_confirm_action(true, false, true, true),
            ReceiptAction::MarkDelivered
        );
        assert_eq!(
            sms_confirm_action(true, false, true, false),
            ReceiptAction::MarkFailed
        );
    }

    #[test]
    fn an_sms_confirmation_from_someone_else_is_not_mine_to_trust() {
        // Only the paired iPhone relays my texts. A 146 attributed to the peer is not a
        // report about my send.
        assert!(matches!(
            sms_confirm_action(true, false, false, true),
            ReceiptAction::Ignore(_)
        ));
        // …and a confirmation for a message we have no send record for is noise.
        assert!(matches!(
            sms_confirm_action(false, false, true, true),
            ReceiptAction::Ignore(_)
        ));
    }

    #[test]
    fn a_replayed_sms_confirmation_cannot_undo_a_confirmed_send() {
        // Apple replays receipts on every APS reconnect. Neither a duplicate 146 nor a
        // late 149 may touch a message that is already confirmed.
        assert!(matches!(
            sms_confirm_action(true, true, true, true),
            ReceiptAction::Ignore(_)
        ));
        assert!(matches!(
            sms_confirm_action(true, true, true, false),
            ReceiptAction::Ignore(_)
        ));
    }

    #[test]
    fn the_two_receipt_kinds_have_disjoint_outcomes() {
        for &tracked in &[true, false] {
            for &from_self in &[true, false] {
                assert_ne!(
                    delivered_action(tracked, from_self),
                    ReceiptAction::MarkFailed,
                    "a 101 must never fail a message (tracked={tracked} from_self={from_self})"
                );
                for &delivered in &[true, false] {
                    assert_ne!(
                        error_action(tracked, delivered, from_self),
                        ReceiptAction::MarkDelivered,
                        "a 120 must never deliver a message \
                         (tracked={tracked} delivered={delivered} from_self={from_self})"
                    );
                }
            }
        }
    }
}
