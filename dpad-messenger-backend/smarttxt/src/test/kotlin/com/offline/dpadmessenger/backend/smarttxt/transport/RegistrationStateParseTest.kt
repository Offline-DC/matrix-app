package com.offline.dpadmessenger.backend.smarttxt.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ONE registration-state decision with user-visible consequences: does this
 * payload mean "sign the user out and show the sign-in screen"?
 *
 * A terminal IDS 6005 cannot be provoked on demand - Apple decides when to invalidate
 * a registration. Shipping to more users does not exercise this path either, because a
 * user who signs in successfully never produces one. So this contract has to be pinned
 * by test rather than observed in the wild.
 *
 * The payload shapes are copied from regstate_payload() in smarttxt-ffi/src/lib.rs. If
 * that function changes shape these break, which is the point: the two halves of this
 * contract live in different languages, in different files, edited by different people.
 */
class RegistrationStateParseTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    // ---- states that must NOT sign anyone out -------------------------------

    @Test
    fun `registered emits nothing`() {
        assertNull(parseRegState(obj("""{"state":"registered","next_s":3884105}""")))
    }

    @Test
    fun `registering emits nothing`() {
        assertNull(parseRegState(obj("""{"state":"registering"}""")))
    }

    @Test
    fun `no_client emits nothing`() {
        assertNull(parseRegState(obj("""{"state":"no_client"}""")))
    }

    @Test
    fun `null state object emits nothing`() {
        assertNull(parseRegState(null))
    }

    // ---- transient failure: surface it, but do NOT sign out -----------------

    @Test
    fun `transient failure with retry_wait does not request relogin`() {
        val ev = parseRegState(
            obj("""{"state":"failed","retry_wait":300,"needs_relogin":false,"error":"boom"}"""),
        )
        assertNotNull("a failed state must still be surfaced", ev)
        assertFalse(
            "rustpush is still retrying this one - signing the user out would throw away " +
                "a registration that is about to recover",
            ev!!.needsRelogin,
        )
        assertEquals("boom", ev.error)
    }

    // ---- terminal failure: this is the 6005 --------------------------------

    @Test
    fun `terminal failure with no retry_wait requests relogin`() {
        val ev = parseRegState(obj("""{"state":"failed","needs_relogin":true,"error":"IDS 6005"}"""))
        assertNotNull(ev)
        assertTrue(
            "no retry_wait means rustpush marked it DoNotRetry - the user must sign in",
            ev!!.needsRelogin,
        )
        assertEquals("IDS 6005", ev.error)
    }

    @Test
    fun `closed identity requests relogin`() {
        val ev = parseRegState(
            obj(
                """{"state":"failed","needs_relogin":true,""" +
                    """"error":"identity closed by IDS (6005)"}""",
            ),
        )
        assertNotNull(ev)
        assertTrue(ev!!.needsRelogin)
    }

    // ---- malformed input must fail SAFE ------------------------------------

    @Test
    fun `failed with no needs_relogin key defaults to not signing out`() {
        // If the Rust side ever drops the key, the safe default is to log and keep the
        // session, not to sign a working user out.
        val ev = parseRegState(obj("""{"state":"failed","error":"???"}"""))
        assertNotNull(ev)
        assertFalse(ev!!.needsRelogin)
    }

    @Test
    fun `needs_relogin is read as a JSON boolean`() {
        assertTrue(parseRegState(obj("""{"state":"failed","needs_relogin":true}"""))!!.needsRelogin)
        assertFalse(parseRegState(obj("""{"state":"failed","needs_relogin":false}"""))!!.needsRelogin)
    }

    @Test
    fun `missing error field yields empty string not a crash`() {
        assertEquals("", parseRegState(obj("""{"state":"failed","needs_relogin":true}"""))!!.error)
    }
}
