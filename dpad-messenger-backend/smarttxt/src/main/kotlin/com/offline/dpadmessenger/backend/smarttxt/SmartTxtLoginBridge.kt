package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An Apple ID and password typed on the paired iPhone (Dumb Down screen 6.3,
 * "sync ur iMessage") and sent to the flip as a sealed `smart_txt_login` on the
 * Type Sync line. The launcher decrypts it and hands it in through
 * [SmartTxtLoginBridge.deliverLogin].
 *
 * Not a data class: a data class's toString would print the password into
 * any log line it ends up in, and the Smart Txt log is emailed to the team.
 */
class SmartTxtLogin(val appleId: String, val password: String) {
    override fun toString(): String = "SmartTxtLogin(<redacted>)"
}

/**
 * The `status` values of `smart_txt_login_ack` (flip → iPhone). The relay
 * passes on any string of 64 characters or fewer; these names are what the
 * iPhone switches on, so change them only together with the iOS side.
 *
 * Success has no status of its own: `smart_txt_signed_in` on the onboarding
 * channel already moves the iPhone on. A security-key account needs none
 * either: the flip's own security-key screen says what to do, and the
 * iPhone waits on 6.5. Screen numbers are the v2 frames as of 2026-10-27.
 */
object SmartTxtLoginAck {
    /** The login was decrypted and the sign-in has started. iPhone: 6.5 "logging in". */
    const val SIGNING_IN = "signing_in"

    /** Apple wants a two-factor code, typed on the flip. iPhone: 6.6 "enter code on ur dumbphone". */
    const val NEEDS_2FA = "needs_2fa"

    /** The sign-in itself failed: a wrong password, Apple refused it, or the
     *  person backed out of the 2FA page on the flip. The only status that
     *  sends the iPhone back to its form (values kept). */
    const val FAILED = "failed"

    /** Smart Txt isn't on its setup screen yet (not open, or the flip's setup
     *  hasn't reached it). iPhone: keep waiting, and resend once the flip gets
     *  to the step. */
    const val NOT_READY = "not_ready"

    /** Smart Txt is already signed in, so there is nothing to do with the
     *  login. iPhone: move on (6.7), no error. */
    const val ALREADY_SIGNED_IN = "already_signed_in"

    /** The person chose "login manually" on the flip and is signing in there.
     *  iPhone: wait on 6.5 for smart_txt_signed_in. */
    const val MANUAL = "manual"

    /** The login could not be decrypted (e.g. a stale pairing secret). Sent by the
     *  launcher, which does the decrypting. iPhone: handled like auth_failed
     *  (re-link in device setup), since retrying with the same secret fails again. */
    const val UNREADABLE = "unreadable"
}

/**
 * Hands an iPhone login from the host app (which owns the Type Sync socket)
 * to the Smart Txt setup screen. Same shape as [SmartTxtFsaBridge], the
 * other thing that crosses this boundary.
 *
 * The setup screen collects [logins] for as long as it is composed and decides
 * what to do with each one (start the sign-in, or answer with where things
 * stand). When nothing is collecting, the login is dropped and the iPhone is
 * told why straight away, so it doesn't wait for an answer that will never
 * come: `already_signed_in` if Smart Txt is signed in, otherwise `not_ready`.
 */
object SmartTxtLoginBridge {
    private const val TAG = "SmartTxtLogin"

    private val _logins = MutableSharedFlow<SmartTxtLogin>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collected by the setup screen while it is shown. */
    val logins: SharedFlow<SmartTxtLogin> = _logins.asSharedFlow()

    /** Whether Smart Txt is signed in. Replaceable so tests don't need the repository. */
    internal var isSignedIn: () -> Boolean = { SmartTxtRepository.status.value == SmartTxtStatus.REGISTERED }

    private val _smsForwardingOn = MutableStateFlow(false)

    /**
     * Whether Text Message Forwarding is known to be on for this registration: set
     * by the native side's sms_activation events (Apple's toggle, a forwarded SMS
     * arriving, or the saved state on every start), cleared on sign-out. The host
     * re-sends `sms_forwarding_on` from it whenever the iPhone (re)connects, because
     * a one-off send is lost if the relay or the iPhone is away at that moment
     * (9 Nov review). The iPhone acts on it only on its 6.7, so repeats are harmless.
     */
    val smsForwardingOn: StateFlow<Boolean> = _smsForwardingOn.asStateFlow()

    internal fun setSmsForwardingOn(on: Boolean) {
        _smsForwardingOn.value = on
    }

    /**
     * Called by the host when a `smart_txt_login` arrives and decrypts. Safe
     * from any thread. Never logs the Apple ID or password.
     */
    fun deliverLogin(appleId: String, password: String) {
        if (_logins.subscriptionCount.value == 0) {
            val reply = if (isSignedIn()) {
                SmartTxtLoginAck.ALREADY_SIGNED_IN
            } else {
                SmartTxtLoginAck.NOT_READY
            }
            Log.i(TAG, "login arrived but Smart Txt isn't on its setup screen; replying $reply")
            SmartTxtConfig.companionSender?.sendLoginAck(reply)
            return
        }
        Log.i(TAG, "login arrived; handing it to the setup screen")
        _logins.tryEmit(SmartTxtLogin(appleId, password))
    }
}

/**
 * Host-provided sink for the two flip → iPhone messages that Smart Txt
 * originates on the Type Sync line. Declared here, in the host-agnostic
 * `:smarttxt` module, so [SmartTxtConfig] can hold one without depending on
 * the launcher; the launcher implements it and sends the frames on its relay
 * socket. Neither message is sealed: neither carries anything secret.
 */
interface SmartTxtCompanionSender {
    /** Send `{"type":"smart_txt_login_ack","status":<status>}`. [status] is one
     *  of [SmartTxtLoginAck]. */
    fun sendLoginAck(status: String)

    /** Send `{"type":"sms_forwarding_on"}`: Text Message Forwarding is on for
     *  this flip, so the iPhone can move past its forwarding screen (6.7). Sent
     *  on Apple's EnableSmsActivation toggle, when a forwarded SMS proves
     *  forwarding is live, and on every start while the saved state says on.
     *  Repeats are expected and harmless: the iPhone acts on it only on 6.7. */
    fun sendSmsForwardingOn()
}
