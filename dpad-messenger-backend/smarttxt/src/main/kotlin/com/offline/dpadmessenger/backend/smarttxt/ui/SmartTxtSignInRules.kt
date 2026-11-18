package com.offline.dpadmessenger.backend.smarttxt.ui

/*
 * The decisions behind Smart Txt's sign-in screens, kept out of the Compose
 * code so they can be unit-tested without a device (SmartTxtSignInRulesTest).
 * The screens only map their state onto these inputs and act on the result.
 */

/** What the setup screen does with a login typed on the iPhone (smart_txt_login). */
internal enum class IphoneLoginAction {
    /** Fill in the Apple ID and password, start signing in, reply `signing_in`. */
    START,
    /** Already signed in: reply `already_signed_in`, start nothing. */
    REFUSE_SIGNED_IN,
    /** "login manually" was chosen on the flip: reply `manual`, start nothing. */
    REFUSE_MANUAL,
    /** The iPhone resending while its sign-in runs: reply `signing_in` again. */
    RESEND_SIGNING_IN,
    /** The iPhone resending while the flip waits for a 2FA code: reply `needs_2fa` again. */
    RESEND_NEEDS_2FA,
    /** On the security-key page: say nothing (fsa_challenge is already re-sent on reconnect). */
    IGNORE,
}

/**
 * Never starts a second native sign-in over a running one: a login that
 * arrives while one runs is the iPhone resending, and gets told where things
 * stand instead.
 */
internal fun iphoneLoginAction(
    signedIn: Boolean,
    manual: Boolean,
    signInRunning: Boolean,
    onTwoFactor: Boolean,
    onSecurityKey: Boolean,
): IphoneLoginAction = when {
    signedIn -> IphoneLoginAction.REFUSE_SIGNED_IN
    manual -> IphoneLoginAction.REFUSE_MANUAL
    signInRunning -> when {
        onTwoFactor -> IphoneLoginAction.RESEND_NEEDS_2FA
        onSecurityKey -> IphoneLoginAction.IGNORE
        else -> IphoneLoginAction.RESEND_SIGNING_IN
    }
    else -> IphoneLoginAction.START
}

/**
 * Whether a sign-in may go to Apple at all: only with both an Apple ID and a
 * password. A blank Apple ID used to be swapped for "demo@icloud.com" (a mock
 * leftover), so OK on setup's keyless page, or "sign in" with empty boxes,
 * sent a pointless failing sign-in to Apple (owner, 2026-11-06).
 */
internal fun canSignIn(appleId: String, password: String): Boolean =
    appleId.isNotBlank() && password.isNotEmpty()

/** What Back (the flip's delete key) does on a 2FA code page. */
internal enum class BackKeyAction { DELETE_DIGIT, LEAVE_PAGE, NOTHING }

/**
 * For a Back key-down on a 2FA page: delete the last digit while there is
 * one; on an empty box, leave the page, but only on a fresh press, so
 * holding Back to clear the box can't carry on and leave by accident.
 */
internal fun backKeyAction(codeLength: Int, repeatCount: Int): BackKeyAction = when {
    codeLength > 0 -> BackKeyAction.DELETE_DIGIT
    repeatCount == 0 -> BackKeyAction.LEAVE_PAGE
    else -> BackKeyAction.NOTHING
}
