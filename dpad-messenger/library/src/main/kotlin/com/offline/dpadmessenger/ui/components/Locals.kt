package com.offline.dpadmessenger.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Threading the current user id through the composition saves passing it
 * down five levels into reaction chips and other small components.
 *
 * Set once at the top of [com.offline.dpadmessenger.ui.DpadMessengerApp].
 */
val LocalCurrentUserId = compositionLocalOf<String?> { null }

/**
 * Set by a host that opened the messenger as the last step of its onboarding
 * (the launcher's Smart Txt during device setup). While set, the room list —
 * the "welcome to smart txt!" page for a new user — swaps its "settings" /
 * "new" soft keys for a single centre "continue", and OK ends onboarding by
 * calling [onContinue] (the launcher closes Smart Txt, and its own resume
 * check shows the "ur dumbphone is ready to use" page).
 *
 * Why the host handles OK rather than the room list: on an empty room list
 * nothing may hold focus, and Compose only delivers keys along the focused
 * path, so a key handler on the screen could simply never see the press. The
 * host's Activity sees every key; it asks [showing] whether the room list is
 * the screen on top before treating OK as "continue". [showing] is maintained
 * by the room list itself (true while composed — the NavHost disposes it when
 * a thread or settings opens), so OK keeps its normal meaning everywhere else.
 */
class OnboardingContinue(val onContinue: () -> Unit) {
    @Volatile
    var showing: Boolean = false
}

/** Null outside onboarding — the room list then keeps its normal soft keys. */
val LocalOnboardingContinue = staticCompositionLocalOf<OnboardingContinue?> { null }
