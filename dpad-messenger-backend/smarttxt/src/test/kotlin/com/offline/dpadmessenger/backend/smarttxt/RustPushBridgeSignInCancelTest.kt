package com.offline.dpadmessenger.backend.smarttxt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * Offline-DC/matrix-app#27: leaving the sign-in screen mid sign-in cancelled the
 * coroutine, and the CancellationException was shown and logged as
 * "Sign-in failed. (Technical detail: rememberCoroutineScope left the composition)".
 */
class RustPushBridgeSignInCancelTest {

    @Test
    fun `leaving the screen mid sign-in reads as cancelled, not as a technical error`() {
        val e = CancellationException("rememberCoroutineScope left the composition")
        val failure = RustPushBridge.signInFailureFor(e)
        assertEquals("Sign-in cancelled.", failure.message)
        assertSame(e, failure.cause)
    }

    @Test
    fun `a real cancellation from a cancelled scope, as the setup screen produces it`() = runBlocking {
        // Same shape as SmartTxtSetupScreen: sign-in runs in a scope the screen owns and
        // suspends waiting for the 2FA code; the screen goes away and the scope is cancelled.
        val screenScope = CoroutineScope(Dispatchers.Default)
        val waitingFor2fa = CompletableDeferred<String?>()
        val result = CompletableDeferred<RegistrationResult.Failure>()
        val started = CompletableDeferred<Unit>()
        screenScope.launch {
            try {
                started.complete(Unit)
                waitingFor2fa.await()
            } catch (t: Throwable) {
                result.complete(RustPushBridge.signInFailureFor(t))
            }
        }
        started.await()
        screenScope.cancel("rememberCoroutineScope left the composition")
        assertEquals("Sign-in cancelled.", result.await().message)
    }

    @Test
    fun `other sign-in errors still go through the humanizer`() {
        val failure = RustPushBridge.signInFailureFor(IOException("register: APS not ready! token"))
        assertEquals(RustPushBridge.humanizeLoginError("register: APS not ready! token"), failure.message)
    }
}
