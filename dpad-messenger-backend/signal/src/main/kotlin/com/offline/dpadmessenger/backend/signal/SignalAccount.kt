package com.offline.dpadmessenger.backend.signal

import kotlinx.serialization.Serializable

/**
 * Credentials + state required to act as a Signal device after linking.
 *
 * Mirrors the fields Signal-Android's [TextSecurePreferences] persists
 * after linking, and the fields mautrix-signal's `pkg/signalmeow/types`
 * carries per-account.
 *
 * @param aci the Account UUID — Signal's primary account identifier.
 * @param pni the Phone Number Identity UUID — Signal's number-rotation
 *            companion to ACI introduced in late 2022.
 * @param phoneNumber E.164 phone number bound to this account.
 * @param deviceId integer assigned by the primary device during linking
 *                 (1 = primary, 2+ = secondary devices).
 * @param password random password chosen by us during provisioning;
 *                 used to authenticate to chat.signal.org with HTTP Basic.
 * @param identityKeyPairBase64 the long-term identity key (PRIVATE half
 *                 included). Encrypt-at-rest is mandatory.
 * @param profileKeyBase64 the user's Signal profile key (32 bytes).
 * @param registrationId per-device registration id used in prekey ops.
 */
@Serializable
data class SignalAccount(
    val aci: String,
    val pni: String?,
    val phoneNumber: String,
    val deviceId: Int,
    val password: String,
    val identityKeyPairBase64: String,
    val profileKeyBase64: String,
    val registrationId: Int,
    /** Separate registration id for the PNI (Phone Number Identity) keys.
     *  Signal's server requires both to be non-zero and unique. */
    val pniRegistrationId: Int = 0,
    /** Base64-encoded serialized [IdentityKeyPair] for the PNI side. */
    val pniIdentityKeyPairBase64: String = "",
)
