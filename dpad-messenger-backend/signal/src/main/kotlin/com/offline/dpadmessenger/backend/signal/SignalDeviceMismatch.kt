package com.offline.dpadmessenger.backend.signal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The server's answer when the device list in a `PUT /v1/messages/<id>` does
 * not match the recipient's account:
 *
 *  - **409** `{"missingDevices":[..],"extraDevices":[..]}`: we left out a
 *    device (new linked device) or included one that no longer exists.
 *  - **410** `{"staleDevices":[..]}`: the device id exists but its
 *    registration id differs from the one we sent, i.e. that device
 *    reinstalled / re-registered and our session with it is dead.
 *
 * Signal-Android handles both in `SignalServiceMessageSender`
 * (`handleMismatchedDevices` / `handleStaleDevices`): archive the session for
 * every extra or stale device, refetch keys, rebuild sessions, retry. Shapes
 * from Signal-Server `MismatchedDevicesResponse` / `StaleDevicesResponse`.
 */
internal data class DeviceMismatch(
    val missing: Set<Int>,
    val extra: Set<Int>,
    val stale: Set<Int>,
) {
    /** Devices whose current session must be archived before retrying. */
    val toArchive: Set<Int> get() = extra + stale

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class Mismatched(
            val missingDevices: List<Int> = emptyList(),
            val extraDevices: List<Int> = emptyList(),
        )

        @Serializable
        private data class Stale(val staleDevices: List<Int> = emptyList())

        /** Null when [httpStatus] is not 409/410. A 409/410 with an unreadable
         *  body still returns an (empty) mismatch so the caller refetches keys. */
        fun parse(httpStatus: Int, body: String): DeviceMismatch? = when (httpStatus) {
            409 -> runCatching { JSON.decodeFromString(Mismatched.serializer(), body) }
                .getOrDefault(Mismatched())
                .let { DeviceMismatch(it.missingDevices.toSet(), it.extraDevices.toSet(), emptySet()) }
            410 -> runCatching { JSON.decodeFromString(Stale.serializer(), body) }
                .getOrDefault(Stale())
                .let { DeviceMismatch(emptySet(), emptySet(), it.staleDevices.toSet()) }
            else -> null
        }
    }
}

/**
 * Caps how many retry receipts we send per sender, the way Signal-Android's
 * `MessageDecryptor` does (an error count per sender that resets after a quiet
 * period). Each failed message needs its OWN retry receipt for the sender to
 * resend it, so this is a per-window budget, not one-per-window: a burst of a
 * few failures all get receipts, a decrypt bug that fails forever doesn't
 * spam the contact.
 */
internal class RetryReceiptLimiter(
    private val maxPerWindow: Int = 10,
    private val windowMs: Long = 60L * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Count(var n: Int, var last: Long)

    private val counts = HashMap<String, Count>()

    /** Records one decrypt failure from [sender]; true if a receipt may go out. */
    @Synchronized
    fun tryAcquire(sender: String): Boolean {
        val now = clock()
        val c = counts.getOrPut(sender) { Count(0, 0L) }
        if (c.n > 0 && now - c.last > windowMs) c.n = 0
        c.n++
        c.last = now
        return c.n <= maxPerWindow
    }
}
