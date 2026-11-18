package com.offline.dpadmessenger.backend.smarttxt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SmartTxtFieldBridge]: the iPhone's prompt for typing into Smart Txt's
 * sign-in boxes (owner, 17 Nov). The form's side (which box has focus, setting
 * the box) is Compose and is checked on the phone; what's pinned here is the
 * bridge's rules: the iPhone hears every change of box and only a change, a
 * value reaches a form only while one is collecting, and nothing prints a value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartTxtFieldBridgeTest {

    private class RecordingSender : SmartTxtCompanionSender {
        val focus = mutableListOf<SmartTxtTextField?>()
        override fun sendLoginAck(status: String) {}
        override fun sendSmsForwardingOn() {}
        override fun sendTextFieldFocus(field: SmartTxtTextField?) { focus += field }
    }

    private val sender = RecordingSender()

    @Before fun setUp() {
        // The bridge is a process-wide object: start every test from "no box".
        SmartTxtConfig.companionSender = null
        SmartTxtFieldBridge.setFocused(null)
        SmartTxtConfig.companionSender = sender
    }

    @After fun tearDown() {
        SmartTxtConfig.companionSender = null
        SmartTxtFieldBridge.setFocused(null)
    }

    @Test fun `the wire names are the ones the iPhone uses`() {
        assertEquals("apple_id", SmartTxtTextField.APPLE_ID.wire)
        assertEquals("password", SmartTxtTextField.PASSWORD.wire)
        assertEquals(SmartTxtTextField.APPLE_ID, SmartTxtTextField.fromWire("apple_id"))
        assertEquals(SmartTxtTextField.PASSWORD, SmartTxtTextField.fromWire("password"))
        assertNull(SmartTxtTextField.fromWire("code"))
        assertNull(SmartTxtTextField.fromWire(""))
    }

    @Test fun `the iPhone hears each change of box, and only a change`() {
        SmartTxtFieldBridge.setFocused(SmartTxtTextField.APPLE_ID)
        SmartTxtFieldBridge.setFocused(SmartTxtTextField.APPLE_ID)
        SmartTxtFieldBridge.setFocused(SmartTxtTextField.PASSWORD)
        SmartTxtFieldBridge.setFocused(null)
        SmartTxtFieldBridge.setFocused(null)
        assertEquals(listOf(SmartTxtTextField.APPLE_ID, SmartTxtTextField.PASSWORD, null), sender.focus)
        assertNull("the bridge no longer holds the box for the host to re-send", SmartTxtFieldBridge.focused.value)
    }

    @Test fun `the focused box is kept for the host to send again when the iPhone joins`() {
        SmartTxtFieldBridge.setFocused(SmartTxtTextField.PASSWORD)
        assertEquals(SmartTxtTextField.PASSWORD, SmartTxtFieldBridge.focused.value)
    }

    @Test fun `a value with no sign-in form up is dropped, not kept for a later form`() {
        assertFalse(SmartTxtFieldBridge.deliverValue("apple_id", "someone@example.com"))
    }

    @Test fun `a value for a box the form doesn't have is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val got = mutableListOf<SmartTxtFieldValue>()
        val job = launch { SmartTxtFieldBridge.values.collect { got += it } }
        assertFalse(SmartTxtFieldBridge.deliverValue("code", "123456"))
        assertTrue(got.isEmpty())
        job.cancel()
    }

    @Test fun `a value reaches the form that's up, for the box it names`() = runTest(UnconfinedTestDispatcher()) {
        val got = mutableListOf<SmartTxtFieldValue>()
        val job = launch { SmartTxtFieldBridge.values.collect { got += it } }
        assertTrue(SmartTxtFieldBridge.deliverValue("apple_id", "someone@example.com"))
        assertTrue(SmartTxtFieldBridge.deliverValue("password", "hunter2"))
        assertEquals(listOf(SmartTxtTextField.APPLE_ID, SmartTxtTextField.PASSWORD), got.map { it.field })
        assertEquals(listOf("someone@example.com", "hunter2"), got.map { it.value })
        job.cancel()
    }

    @Test fun `a value never prints itself`() {
        val v = SmartTxtFieldValue(SmartTxtTextField.PASSWORD, "hunter2")
        assertFalse("the password reached toString", "hunter2" in v.toString())
        assertTrue("password" in v.toString())
    }
}
