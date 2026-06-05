package com.offline.dpadmessenger.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Threading the current user id through the composition saves passing it
 * down five levels into reaction chips and other small components.
 *
 * Set once at the top of [com.offline.dpadmessenger.ui.DpadMessengerApp].
 */
val LocalCurrentUserId = compositionLocalOf<String?> { null }
