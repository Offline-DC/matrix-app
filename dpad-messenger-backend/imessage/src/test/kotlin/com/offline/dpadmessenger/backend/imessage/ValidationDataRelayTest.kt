package com.offline.dpadmessenger.backend.imessage

import com.offline.dpadmessenger.backend.imessage.absinthe.AbsintheStub
import com.offline.dpadmessenger.backend.imessage.absinthe.AbsintheUnavailableException
import com.offline.dpadmessenger.backend.imessage.relay.RelayHealth
import com.offline.dpadmessenger.backend.imessage.relay.StubValidationDataRelay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the closed-source seam (§2.5/§2.6): the local absinthe engine is
 * unavailable, so validation data MUST come from the relay, and the bridge's
 * registration sequence must complete on top of relay-supplied bytes.
 */
class ValidationDataRelayTest {

    @Test
    fun `absinthe stub is not a real implementation`() {
        assertFalse(AbsintheStub.IS_REAL_IMPLEMENTATION)
    }

    @Test
    fun `local absinthe throws AbsintheUnavailable`() {
        val hw = HardwareConfig.placeholder()
        var threw = false
        try {
            AbsintheStub.ValidationCtx.new(hw)
        } catch (_: AbsintheUnavailableException) {
            threw = true
        }
        assertTrue("ValidationCtx.new must throw AbsintheUnavailable in the stub", threw)
    }

    @Test
    fun `stub relay is reachable and issues non-empty validation data`() = runTest {
        val relay = StubValidationDataRelay()
        assertEquals(RelayHealth.REACHABLE, relay.health())
        val data = relay.fetchValidationData(HardwareConfig.placeholder())
        assertTrue("validation data must not be empty", data.bytes.isNotEmpty())
    }

    @Test
    fun `bridge falls back to relay for validation data`() = runTest {
        val bridge = RustPushBridge(StubValidationDataRelay())
        // generateValidationData tries the (throwing) local engine, then the relay.
        val bytes = bridge.generateValidationData(MacOSConfig.placeholder())
        assertTrue("relay-supplied validation data must be non-empty", bytes.isNotEmpty())
    }

    @Test
    fun `full registration succeeds on relay-supplied validation data`() = runTest {
        val bridge = RustPushBridge(StubValidationDataRelay())
        val result = bridge.register(MacOSConfig.placeholder(), "demo@icloud.com")
        assertTrue(
            "registration should succeed in stub mode, was $result",
            result is RegistrationResult.Success,
        )
        val account = (result as RegistrationResult.Success).account
        assertEquals("demo@icloud.com", account.appleId)
        assertTrue("registration should be stamped", account.lastRegisteredMs > 0L)
    }
}
