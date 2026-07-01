package com.offline.dpadmessenger.backend.signal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.net.CdsiLookupRequest
import org.signal.libsignal.net.Network
import java.util.Collections
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-number → ACI resolution via Signal's Contact Discovery Service (CDSI).
 *
 * Signal addresses recipients by ACI, never by phone number, and there is no
 * plain "look up this number" endpoint — discovery goes through the attested
 * CDSI enclave (`cdsi.signal.org`). We let **libsignal's `Network`** do the
 * heavy lifting (the Noise handshake + SGX remote-attestation are implemented
 * inside libsignal-android); all we supply is:
 *   1. short-lived CDSI credentials from `GET /v2/directory/auth` (via [api]),
 *   2. the E.164 number(s) to resolve.
 *
 * Used by [SignalMessageRepository.startConversation] so tapping a contact the
 * device hasn't already learned about (no inbound message / contact sync) can
 * still open a chat.
 *
 * ⚠️ **Version-sensitive.** The `org.signal.libsignal.net` CDSI API
 * (`Network`, `CdsiLookupRequest`, `CdsiLookupResponse`) has moved between
 * libsignal-android releases. This is written against **0.86.5** — verify the
 * constructor/method shapes on any bump (see the call in [lookup]). Everything
 * runs under `runCatching`, so an API mismatch degrades to "number not
 * resolvable" rather than crashing.
 *
 * CDSI is **aggressively rate-limited** account-wide, so results are cached
 * (both hits and misses) and lookups are single-number on-demand. A future
 * optimization is to batch the whole address book behind one token.
 */
class SignalContactDiscovery(
    private val account: SignalAccount,
    private val api: SignalApi,
    /** libsignal network handle. Defaults to the production environment. */
    private val network: Network = Network(
        Network.Environment.PRODUCTION,
        USER_AGENT,
        emptyMap<String, String>(),
        Network.BuildVariant.PRODUCTION,
    ),
) {
    private val login = "${account.aci}.${account.deviceId}"
    private val password = account.password

    /** norm E.164 → ACI (uuid string). */
    private val cache = ConcurrentHashMap<String, String>()
    /** Numbers we looked up and that are NOT on Signal — don't ask CDSI again. */
    private val notFound = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Resolve [rawNumber] to a Signal ACI, or null if the number isn't on
     * Signal (or discovery failed). Cached.
     */
    suspend fun resolveAci(rawNumber: String): String? {
        val e164 = toE164(rawNumber) ?: run {
            Log.d(TAG, "can't normalize '$rawNumber' to E.164 — skipping discovery")
            return null
        }
        cache[e164]?.let { return it }
        if (e164 in notFound) return null

        val serviceId = lookup(e164)
        if (serviceId != null) {
            cache[e164] = serviceId
            // NB: a "PNI:<uuid>" here is NOT an ACI — it's a phone-number
            // identity for an account that restricts number-discoverability, so
            // CDSI withheld the ACI. We can still address it (same account), but
            // the reply will arrive under the real ACI and must be merged onto
            // this thread (see SignalMessageRepository.mergeRecipient). Log it
            // honestly rather than calling a PNI an ACI.
            if (serviceId.startsWith("PNI:")) {
                Log.d(TAG, "discovered $e164 → pni-only=$serviceId (ACI withheld; provisional)")
            } else {
                Log.d(TAG, "discovered $e164 → aci=$serviceId")
            }
        } else {
            notFound.add(e164)
            Log.d(TAG, "no Signal account for $e164")
        }
        return serviceId
    }

    /**
     * The single CDSI round-trip. Isolated so the version-sensitive libsignal
     * surface is in one place. Returns the ACI uuid string, or null.
     */
    private suspend fun lookup(e164: String): String? {
        val auth = api.getCdsiAuth(login, password)
        if (auth == null) {
            Log.w(TAG, "CDSI auth fetch failed")
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                // One-shot lookup: no previous E.164s, no prior token, and no
                // known service-id/profile-key pairs (we're discovering an
                // unknown contact). libsignal-android 0.86.5 constructor order
                // is (previousE164s, newE164s, serviceIds, token).
                val request = CdsiLookupRequest(
                    /* previousE164s = */ emptySet(),
                    /* newE164s = */ setOf(e164),
                    /* serviceIds = */ emptyMap(),
                    /* token = */ Optional.empty(),
                )
                // cdsiLookup wants a token consumer (used for incremental
                // lookups); we don't persist a token, so ignore it. The
                // returned future's .get() blocks — fine on Dispatchers.IO.
                val response = network
                    .cdsiLookup(auth.username, auth.password, request) { /* token: ignored */ }
                    .get()
                val entries = response.entries()
                Log.d(TAG, "CDSI $e164 → ${entries.size} entries; keys=${entries.keys}")

                // The response is keyed by "+<e164>", but be tolerant: also
                // match an entry whose digits equal ours, in case the key
                // formatting differs by version.
                val wantDigits = e164.filter { it.isDigit() }
                val entry = entries[e164]
                    ?: entries.entries
                        .firstOrNull { it.key.filter { c -> c.isDigit() } == wantDigits }
                        ?.value
                if (entry == null) {
                    Log.d(TAG, "CDSI $e164 → registered? NO (no entry)")
                    return@runCatching null
                }

                // Prefer the ACI (bare UUID, matching how we key rooms). If CDSI
                // only returned a PNI (common when we have no profile key for
                // the contact), fall back to the PNI service-id string
                // ("PNI:<uuid>") — Signal can address a recipient by PNI.
                val aci = entry.aci?.getRawUUID()?.toString()
                val pni = entry.pni?.toServiceIdString()
                Log.d(TAG, "CDSI $e164 → aci=${aci ?: "null"} pni=${pni ?: "null"}")
                aci ?: pni
            }.getOrElse {
                Log.w(TAG, "CDSI lookup for $e164 failed", it)
                null
            }
        }
    }

    /**
     * Best-effort E.164 normalization. CDSI requires a true `+<cc><number>`.
     * Heuristics (no libphonenumber dependency):
     *  - already starts with '+': keep its digits.
     *  - 11 digits starting with '1' (NANP): prefix '+'.
     *  - 10 digits: assume NANP, prefix '+1'.
     * Anything else is rejected (returns null) rather than guessing wrong.
     */
    private fun toE164(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith("+") && digits.length in 8..15 -> "+$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            else -> null
        }
    }

    companion object {
        private const val TAG = "SignalCDSI"
        private const val USER_AGENT = "DPADMessenger/0.1"
    }
}
