package com.offline.dpadmessenger.backend.smarttxt

import kotlinx.coroutines.CompletableDeferred

/**
 * The companion's answer to an [FsaChallenge] — a CTAP2 assertion, in the exact
 * string shape Apple's `verify_security_key` endpoint expects (binary values are
 * base64-encoded by the companion). Delivered launcher → smarttxt via
 * [SmartTxtFsaBridge], then submitted through `nativeSubmitFsa`.
 */
data class FsaResponse(
    val challenge: String,
    val clientData: String,
    val signatureData: String,
    val authenticatorData: String,
    val credentialId: String,
    val userHandle: String,
    val rpId: String,
)

/**
 * Hands the companion's FSA response from the host app (which owns the typesync
 * receive path) back into the suspended sign-in flow. The sign-in coroutine calls
 * [awaitResponse] while the FSA screen is shown; the launcher calls
 * [deliverResponse] when an `fsa_response` arrives, or the UI calls [cancel] on
 * Back. Single in-flight request at a time (only one sign-in runs).
 */
object SmartTxtFsaBridge {
    @Volatile private var pending: CompletableDeferred<FsaResponse?>? = null

    /** Suspend until the companion's response arrives (or null on cancel). */
    suspend fun awaitResponse(): FsaResponse? {
        val deferred = CompletableDeferred<FsaResponse?>()
        pending = deferred
        return try {
            deferred.await()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    /** Called by the host when an `fsa_response` is received over typesync. */
    fun deliverResponse(response: FsaResponse) {
        pending?.complete(response)
    }

    /** Cancel the pending wait (FSA screen dismissed / Back pressed). */
    fun cancel() {
        pending?.complete(null)
    }
}

/**
 * An Apple "FSA" (security-key / WebAuthn) sign-in challenge, surfaced by the
 * native layer as `{"status":"needs_fsa", ...}` from `nativeAuthenticate`.
 *
 * The user answers this on a *different* device that holds the passkey/security
 * key — here, the paired companion smartphone. The dumbphone therefore relays
 * every field of the challenge to the companion over the typesync channel; see
 * [FsaChallengeSender].
 */
data class FsaChallenge(
    val challenge: String,
    val keyHandles: List<String>,
    val rpId: String,
    val allowedCredentials: String,
)

/**
 * Host-provided sink that relays an [FsaChallenge] to the paired companion app
 * over the launcher's typesync relay. Declared in the (host-agnostic) `:smarttxt`
 * module so [SmartTxtConfig] can hold one without a compile dependency on the
 * launcher's `:app` module; the launcher supplies the implementation.
 *
 * Lifecycle is driven by the FSA setup screen:
 *  - [start] when the screen appears: send the challenge now, and keep re-sending
 *    it on every relay/companion (re)connection (so it survives the companion app
 *    being closed or dropping the socket).
 *  - [stop] when the screen goes away: stop re-sending.
 *
 * This "re-send only while the screen is shown" contract is what bounds the
 * retransmission to the FSA step.
 */
interface FsaChallengeSender {
    /** Send [challenge] to the companion now and re-send on each (re)connection
     *  until [stop] is called. Safe to call again to replace the pending challenge. */
    fun start(challenge: FsaChallenge)

    /** Stop re-sending the pending challenge (call when leaving the FSA screen). */
    fun stop()

    /** Ask the relay for a short one-time code the user types into the web/desktop
     *  FSA client (dumb.co/fsa). The relay only mints it for the authenticated
     *  phone, so it's a real out-of-band code. [onCode] fires when it arrives. */
    fun requestWebCode(onCode: (String) -> Unit)

    /** Stop listening for the web code (call when leaving the FSA screen). */
    fun stopWebCode()

    /** Whether a companion smartphone is linked, so the relay path can actually
     *  work. The security-key relay (both the companion and the web/desktop code
     *  flow) rides on the flip↔companion pairing, so with nothing linked neither
     *  [start] nor [requestWebCode] can ever be answered. When false the FSA
     *  screen should prompt the user to set up their device instead of waiting.
     *  Defaults to true for hosts that don't implement it. */
    fun isCompanionLinked(): Boolean = true
}
