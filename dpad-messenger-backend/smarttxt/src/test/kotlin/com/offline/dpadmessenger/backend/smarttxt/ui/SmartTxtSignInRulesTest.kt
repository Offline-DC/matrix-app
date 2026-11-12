package com.offline.dpadmessenger.backend.smarttxt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartTxtSignInRulesTest {

    private fun action(
        signedIn: Boolean = false,
        manual: Boolean = false,
        running: Boolean = false,
        onTwoFactor: Boolean = false,
        onSecurityKey: Boolean = false,
    ) = iphoneLoginAction(signedIn, manual, running, onTwoFactor, onSecurityKey)

    // ---- iPhone login -------------------------------------------------------

    @Test fun `a login on the waiting page starts the sign-in`() {
        assertEquals(IphoneLoginAction.START, action())
    }

    @Test fun `a login once signed in is refused as already signed in`() {
        assertEquals(IphoneLoginAction.REFUSE_SIGNED_IN, action(signedIn = true))
        // Signed in wins over everything else.
        assertEquals(IphoneLoginAction.REFUSE_SIGNED_IN, action(signedIn = true, manual = true, running = true))
    }

    @Test fun `a login after login manually is refused as manual, even mid sign-in`() {
        assertEquals(IphoneLoginAction.REFUSE_MANUAL, action(manual = true))
        assertEquals(IphoneLoginAction.REFUSE_MANUAL, action(manual = true, running = true, onTwoFactor = true))
    }

    @Test fun `a resend while signing in never starts a second sign-in`() {
        assertEquals(IphoneLoginAction.RESEND_SIGNING_IN, action(running = true))
    }

    @Test fun `a resend on the 2FA page repeats needs_2fa`() {
        assertEquals(IphoneLoginAction.RESEND_NEEDS_2FA, action(running = true, onTwoFactor = true))
    }

    @Test fun `a resend on the security-key page says nothing`() {
        assertEquals(IphoneLoginAction.IGNORE, action(running = true, onSecurityKey = true))
    }

    @Test fun `a stale 2FA flag with nothing running still starts`() {
        // After a 2FA back-out the step can still read TWO_FACTOR for a moment
        // while the coroutine ends; only a running sign-in blocks a new one.
        assertEquals(IphoneLoginAction.START, action(onTwoFactor = true))
    }

    // ---- never sign in with an empty box ------------------------------------

    @Test fun `a sign-in needs both an Apple ID and a password`() {
        assertEquals(true, canSignIn("someone@example.com", "secret"))
        assertEquals(false, canSignIn("", "secret"))
        assertEquals(false, canSignIn("   ", "secret"))
        assertEquals(false, canSignIn("someone@example.com", ""))
        assertEquals(false, canSignIn("", ""))
    }

    // ---- setup's auto sign-in (the "password loop") ------------------------

    private fun auto(
        inSetup: Boolean = true,
        onForm: Boolean = true,
        signingIn: Boolean = false,
        showingError: Boolean = false,
        held: Boolean = false,
        appleIdBlank: Boolean = false,
        passwordEmpty: Boolean = false,
        appleIdWhole: Boolean = true,
        passwordWhole: Boolean = true,
    ) = autoSignInAction(inSetup, onForm, signingIn, showingError, held, appleIdBlank, passwordEmpty, appleIdWhole, passwordWhole)

    @Test fun `both values typed in setup signs in by itself`() {
        assertEquals(AutoSignInAction.SIGN_IN, auto())
    }

    @Test fun `only the Apple ID typed moves to the password box`() {
        assertEquals(AutoSignInAction.FOCUS_PASSWORD, auto(passwordEmpty = true))
        // Even after a failure: moving focus never sends anything.
        assertEquals(AutoSignInAction.FOCUS_PASSWORD, auto(passwordEmpty = true, held = true))
    }

    @Test fun `after a failed sign-in the same values are not sent again`() {
        // "retry" or Back clears the error page, or Back on the 2FA page puts the
        // form back; before the fix each of these signed in again.
        assertEquals(AutoSignInAction.NOTHING, auto(held = true))
    }

    @Test fun `stray key presses in the hidden form never move focus or sign in`() {
        // F35: one keypad press in the Apple ID box used to jump to the password
        // box, and a second press then sent a junk sign-in to Apple.
        assertEquals(AutoSignInAction.NOTHING, auto(passwordEmpty = true, appleIdWhole = false))
        assertEquals(AutoSignInAction.NOTHING, auto(passwordWhole = false))
        assertEquals(AutoSignInAction.NOTHING, auto(appleIdWhole = false, passwordWhole = false))
    }

    @Test fun `a value arrives whole only when more than one character comes at once`() {
        assertEquals(true, arrivedWhole("", "someone@example.com"))
        assertEquals(true, arrivedWhole("so", "someone@example.com"))
        assertEquals(false, arrivedWhole("", "s"))
        assertEquals(false, arrivedWhole("someone", "someone1"))
        assertEquals(false, arrivedWhole("someone", "someon"))
    }

    @Test fun `nothing happens outside setup, off the form, mid sign-in or on the error page`() {
        assertEquals(AutoSignInAction.NOTHING, auto(inSetup = false))
        assertEquals(AutoSignInAction.NOTHING, auto(onForm = false))
        assertEquals(AutoSignInAction.NOTHING, auto(signingIn = true))
        assertEquals(AutoSignInAction.NOTHING, auto(showingError = true))
        assertEquals(AutoSignInAction.NOTHING, auto(appleIdBlank = true))
    }

    // ---- the "send from" page's number --------------------------------------

    @Test fun `North American numbers are shown formatted, with the dash`() {
        assertEquals("+1 (404) 555-0123", setupHandleLabel("tel:+14045550123"))
        assertEquals("+1 (404) 555-0123", setupHandleLabel("tel:4045550123"))
    }

    @Test fun `emails and other numbers are left as they are`() {
        assertEquals("someone@example.com", setupHandleLabel("mailto:someone@example.com"))
        assertEquals("+447700900123", setupHandleLabel("tel:+447700900123"))
        assertEquals("+1404555", setupHandleLabel("tel:+1404555"))
    }

    // ---- the "send from" page with more addresses than fit, or none ---------

    private val tel = "tel:+14045550123"
    private fun emails(n: Int) = (1..n).map { "mailto:someone$it@example.com" }

    /** Down from the top card to the last, returning the scroll after each step. */
    private fun walkDown(handles: List<String>): List<Int> {
        var scroll = 0
        return handles.indices.map { i -> handlePickerScroll(handles, i, scroll).also { scroll = it } }
    }

    @Test fun `three addresses never scroll`() {
        assertEquals(listOf(0, 0, 0), walkDown(listOf(tel) + emails(2)))
        assertEquals(listOf(0, 0, 0), walkDown(emails(3)))
    }

    @Test fun `the 4th card moves the page up 15 px and the 5th 53 px`() {
        assertEquals(listOf(0, 0, 0, 15, 53), walkDown(listOf(tel) + emails(4)))
    }

    @Test fun `going back up keeps the scroll until a card would leave the top`() {
        val list = listOf(tel) + emails(4)
        assertEquals(53, handlePickerScroll(list, 3, 53))
        assertEquals(53, handlePickerScroll(list, 2, 53))
        assertEquals(38, handlePickerScroll(list, 1, 53))
        assertEquals(0, handlePickerScroll(list, 0, 38))
    }

    @Test fun `two phone numbers scroll the same way`() {
        val list = listOf(tel, "tel:+14045550199") + emails(2)
        assertEquals(0, handlePickerScroll(list, 1, 0))
        assertEquals(15, handlePickerScroll(list, 3, 0))
    }

    @Test fun `no addresses never scroll`() {
        for (f in 0..3) assertEquals(0, handlePickerScroll(emptyList(), f, 0))
    }

    @Test fun `the focused card always ends up fully between the frame's top and bottom card lines`() {
        for (n in 1..9) for (withTel in listOf(true, false)) {
            val list = (if (withTel) listOf(tel) else emptyList()) + emails(n)
            var scroll = 0
            val order = list.indices + list.indices.reversed()
            for (f in order) {
                scroll = handlePickerScroll(list, f, scroll)
                var top = SEND_FROM_TOP
                for (j in 0 until f) top += handleCardHeight(list[j], focused = false) + SEND_FROM_GAP
                val bottom = top + handleCardHeight(list[f], focused = true)
                val where = "n=$n tel=$withTel focus=$f scroll=$scroll"
                assertEquals(where, true, top - scroll >= SEND_FROM_TOP)
                assertEquals(where, true, bottom - scroll <= SEND_FROM_BOTTOM)
            }
        }
    }

    @Test fun `select with no addresses moves on with nothing chosen`() {
        assertEquals("", sendFromChoice(emptyList(), 0))
    }

    @Test fun `select saves the focused address`() {
        val list = listOf(tel) + emails(2)
        assertEquals(list[2], sendFromChoice(list, 2))
    }

    @Test fun `only the focused phone number's card is tall`() {
        assertEquals(57, handleCardHeight(tel, focused = true))
        assertEquals(34, handleCardHeight(tel, focused = false))
        assertEquals(34, handleCardHeight("mailto:someone@example.com", focused = true))
    }

    // ---- Back on the 2FA page -----------------------------------------------

    @Test fun `Back deletes the last digit while there is one`() {
        for (len in 1..6) assertEquals(BackKeyAction.DELETE_DIGIT, backKeyAction(len, repeatCount = 0))
    }

    @Test fun `holding Back keeps deleting digits`() {
        assertEquals(BackKeyAction.DELETE_DIGIT, backKeyAction(3, repeatCount = 5))
    }

    @Test fun `Back on an empty box leaves the page on a fresh press`() {
        assertEquals(BackKeyAction.LEAVE_PAGE, backKeyAction(0, repeatCount = 0))
    }

    @Test fun `holding Back past an empty box does not leave the page`() {
        assertEquals(BackKeyAction.NOTHING, backKeyAction(0, repeatCount = 1))
        assertEquals(BackKeyAction.NOTHING, backKeyAction(0, repeatCount = 12))
    }
}
