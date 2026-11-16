package com.offline.dpadmessenger.backend.smarttxt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartTxtLoginBridgeTest {

    private class RecordingSender : SmartTxtCompanionSender {
        val acks = mutableListOf<String>()
        var forwardingOn = 0
        override fun sendLoginAck(status: String) { acks += status }
        override fun sendSmsForwardingOn() { forwardingOn++ }
    }

    private val sender = RecordingSender()
    private val realIsSignedIn = SmartTxtLoginBridge.isSignedIn

    @Before fun setUp() {
        SmartTxtConfig.companionSender = sender
    }

    @After fun tearDown() {
        SmartTxtConfig.companionSender = null
        SmartTxtLoginBridge.isSignedIn = realIsSignedIn
        SmartTxtLoginBridge.setSmsForwardingOn(false)
    }

    @Test fun `forwarding is off until set, and follows what is set`() {
        assertFalse(SmartTxtLoginBridge.smsForwardingOn.value)
        SmartTxtLoginBridge.setSmsForwardingOn(true)
        assertTrue(SmartTxtLoginBridge.smsForwardingOn.value)
        SmartTxtLoginBridge.setSmsForwardingOn(false)
        assertFalse(SmartTxtLoginBridge.smsForwardingOn.value)
        // The bridge only holds the state; the repository and the host do the sending.
        assertEquals(0, sender.forwardingOn)
    }

    @Test fun `sign-out clears forwarding even without the native library`() {
        SmartTxtLoginBridge.setSmsForwardingOn(true)
        RustPushNative.runCatchingNativeLogout()
        assertFalse(SmartTxtLoginBridge.smsForwardingOn.value)
    }

    @Test fun `Apple's sign-out keeping history clears forwarding too, even without the native library`() {
        // 11 Nov review: the 6005 sign-out keeps the login but not the forwarding, which
        // belonged to the registration Apple ended. Left on, the launcher re-sent it to the
        // iPhone's 6.7 before the re-sign-in's own OFF.
        SmartTxtLoginBridge.setSmsForwardingOn(true)
        RustPushNative.runCatchingNativeClearSmsForwarding()
        assertFalse(SmartTxtLoginBridge.smsForwardingOn.value)
        assertEquals("nothing is sent to the iPhone for off", 0, sender.forwardingOn)
    }

    @Test fun `with no setup screen and not signed in, the iPhone is told not_ready`() {
        SmartTxtLoginBridge.isSignedIn = { false }
        SmartTxtLoginBridge.deliverLogin("someone@example.com", "secret")
        assertEquals(listOf(SmartTxtLoginAck.NOT_READY), sender.acks)
    }

    @Test fun `with no setup screen and signed in, the iPhone is told already_signed_in`() {
        SmartTxtLoginBridge.isSignedIn = { true }
        SmartTxtLoginBridge.deliverLogin("someone@example.com", "secret")
        assertEquals(listOf(SmartTxtLoginAck.ALREADY_SIGNED_IN), sender.acks)
    }

    @Test fun `with no host sender wired, nothing breaks`() {
        SmartTxtConfig.companionSender = null
        SmartTxtLoginBridge.isSignedIn = { false }
        SmartTxtLoginBridge.deliverLogin("someone@example.com", "secret")
        assertTrue(sender.acks.isEmpty())
    }

    @Test fun `with the setup screen listening, the login reaches it and the bridge says nothing`() = runTest {
        val received = mutableListOf<SmartTxtLogin>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SmartTxtLoginBridge.logins.collect { received += it }
        }
        SmartTxtLoginBridge.deliverLogin("someone@example.com", "secret")
        assertEquals(1, received.size)
        assertEquals("someone@example.com", received[0].appleId)
        assertEquals("secret", received[0].password)
        // The setup screen answers, not the bridge.
        assertTrue(sender.acks.isEmpty())
    }

    @Test fun `a login never prints its Apple ID or password`() {
        val text = SmartTxtLogin("someone@example.com", "secret").toString()
        assertFalse(text.contains("someone@example.com"))
        assertFalse(text.contains("secret"))
    }

    @Test fun `the status names match what the iPhone switches on`() {
        // Agreed with the iOS side on 2026-11-05; change only together with it.
        assertEquals("signing_in", SmartTxtLoginAck.SIGNING_IN)
        assertEquals("needs_2fa", SmartTxtLoginAck.NEEDS_2FA)
        assertEquals("failed", SmartTxtLoginAck.FAILED)
        assertEquals("not_ready", SmartTxtLoginAck.NOT_READY)
        assertEquals("already_signed_in", SmartTxtLoginAck.ALREADY_SIGNED_IN)
        assertEquals("manual", SmartTxtLoginAck.MANUAL)
        assertEquals("unreadable", SmartTxtLoginAck.UNREADABLE)
    }
}
