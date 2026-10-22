package com.offline.dpadmessenger.backend.smarttxt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [RustPushBridge.humanizeLoginError], the one function that turns a raw rustpush /
 * FFI error into the sentence the customer reads on the red "couldn't sign in" card.
 *
 * The branch this file exists for: in Oct 2026 four customers could not sign in to Smart
 * Txt because their handset clock was off by hours to a day. Apple refuses the APS
 * connect when the clock is wrong, rustpush reports it as `register: APS not ready!
 * token`, and that string matched no branch — so the card showed "Sign-in failed.
 * (Technical detail: register: APS not ready! token)" and every one of them emailed
 * support. The mapping now names the likely cause (the clock) and where in Settings to
 * fix it.
 *
 * The remaining tests pin the branches around it, so a future edit that re-orders the
 * `when` (the order is load-bearing, see the comment in the function) shows up here
 * rather than on a customer's handset. None of this needs the native library: the
 * function is pure string matching on the companion object.
 */
class RustPushBridgeLoginErrorTest {

    private val clockMessage =
        "This is usually because the phone's date or time is wrong. In Settings, " +
            "set the date & time to be correct, then restart your phone and try again."

    private fun clockMessageFor(raw: String) = "$clockMessage (Technical detail: $raw)"

    @Test
    fun `APS not ready maps to the clock message, with the raw detail kept`() {
        assertEquals(
            clockMessageFor("register: APS not ready! token"),
            RustPushBridge.humanizeLoginError("register: APS not ready! token"),
        )
    }

    @Test
    fun `APSConnectError maps to the clock message`() {
        assertEquals(clockMessageFor("APSConnectError(2)"), RustPushBridge.humanizeLoginError("APSConnectError(2)"))
    }

    @Test
    fun `the Display form of APSConnectError maps to the clock message too`() {
        // What the FFI's `{e}` formatting actually emits (rustpush error.rs:40) —
        // the Debug form above is what shows up in logs and exception messages.
        val raw = "register: Aps connection failed. You need to re-setup your device. " +
            "Go to Settings -> Change Apple Hardware. For self-hosted users, have " +
            "your hardware code ready. (2)"
        // The detail is capped at 120 characters, like the generic fallback's.
        assertEquals(clockMessageFor(raw.take(120)), RustPushBridge.humanizeLoginError(raw))
    }

    @Test
    fun `the clock message leads with the advice and keeps the raw text for support`() {
        // The complaint on the card is that the customer saw ONLY the raw rustpush text.
        // Jack's call (2026-10-21): say what to do first, handset-agnostic, and keep the raw
        // text after it so support can still recognise the refusal.
        val shown = RustPushBridge.humanizeLoginError("register: APS not ready! token")
        assertTrue(shown, shown.startsWith("This is usually because the phone's date or time is wrong."))
        assertTrue(shown, !shown.contains("Phone settings"))
        assertTrue(shown, shown.endsWith("(Technical detail: register: APS not ready! token)"))
    }

    @Test
    fun `a wrong 2fa code still maps to the code message`() {
        assertEquals(
            "That verification code didn't work. Request a new code and try again.",
            RustPushBridge.humanizeLoginError("bad 2fa code"),
        )
    }

    @Test
    fun `6009 still maps to the registration-paused message and does not say try again`() {
        val shown = RustPushBridge.humanizeLoginError("Registration Error: account temporarily disabled (6009)")
        assertTrue(shown, shown.startsWith("Apple has paused iMessage registration for this device."))
        assertTrue(shown, !shown.lowercase().contains("try again"))
    }

    @Test
    fun `apns connect failed still maps to the connection message`() {
        assertEquals(
            "Couldn't connect to Apple's servers. Check your internet connection and try again.",
            RustPushBridge.humanizeLoginError("apns connect failed"),
        )
    }

    @Test
    fun `an empty error maps to the plain retry message`() {
        assertEquals("Sign-in failed. Please try again.", RustPushBridge.humanizeLoginError(""))
        assertEquals("Sign-in failed. Please try again.", RustPushBridge.humanizeLoginError(null))
    }

    @Test
    fun `an unknown error still falls through to the technical-detail fallback`() {
        assertEquals(
            "Sign-in failed. (Technical detail: SomethingNobodyHasSeenBefore(42))",
            RustPushBridge.humanizeLoginError("SomethingNobodyHasSeenBefore(42)"),
        )
    }
}

/**
 * The second half of the same card: Settings -> Re-register only shows the humanised
 * message when [SmartTxtRepository.isTerminalFailure] says the failure needs the user;
 * everything else is toasted as "still reconnecting in the background". A wrong clock
 * needs the user, so the APS refusals must classify as terminal or the clock message
 * never reaches the Re-register path at all.
 */
class SmartTxtRepositoryTerminalFailureTest {

    @Test
    fun `APS not ready is a terminal failure`() {
        assertTrue(SmartTxtRepository.isTerminalFailure("register: APS not ready! token"))
    }

    @Test
    fun `Aps connection failed is a terminal failure`() {
        assertTrue(
            SmartTxtRepository.isTerminalFailure(
                "register: Aps connection failed. You need to re-setup your device. (2)",
            ),
        )
    }

    @Test
    fun `a 6009 is still terminal`() {
        assertTrue(SmartTxtRepository.isTerminalFailure("Registration Error: temporarily disabled (6009)"))
    }

    @Test
    fun `a timeout is still not terminal`() {
        assertTrue(!SmartTxtRepository.isTerminalFailure("register: request timed out"))
    }
}
