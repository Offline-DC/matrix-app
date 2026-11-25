package com.offline.dpadmessenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * matrix-app#30: pressing a room row whose id was blank navigated to "chat/",
 * which Navigation rejects with IllegalArgumentException and the messenger
 * crashed. Every chat navigation goes through [Routes.chatOrNull].
 */
class RoutesTest {
    @Test
    fun blankRoomIdHasNoChatRoute() {
        assertNull(Routes.chatOrNull(""))
        assertNull(Routes.chatOrNull("   "))
        assertNull(Routes.chatOrNull(null))
    }

    @Test
    fun realRoomIdKeepsTheExistingRoute() {
        assertEquals("chat/abc123", Routes.chatOrNull("abc123"))
        assertEquals(Routes.chat("!room:server"), Routes.chatOrNull("!room:server"))
    }
}
