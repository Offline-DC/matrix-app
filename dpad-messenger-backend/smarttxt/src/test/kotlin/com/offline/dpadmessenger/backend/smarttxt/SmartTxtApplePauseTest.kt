package com.offline.dpadmessenger.backend.smarttxt

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * matrix-app#32: after Apple paused iMessage registration for a phone (IDS 6009,
 * "Your iMessage access is temporarily disabled"), Settings -> "Re-register now"
 * still sent a real re-registration to Apple on the next press — the Nov 25 logs show
 * two `nativeReregister` calls 13 s apart — and every extra attempt makes Apple's
 * pause last longer.
 *
 * What these tests drive: [SmartTxtRepository.reregisterUnlessPaused], which is the
 * whole of PHASE 2 of [SmartTxtRepository.fixConnection] (fixConnection calls it with
 * the real store, the wall clock and `reregisterOnce`, which is the only thing that
 * calls `bridge().reregister()`). The fake `reregister` lambda below stands in for
 * that bridge call, so its call count is the number of times Apple would have been
 * contacted. The store is the real [SmartTxtAccountStore] over an in-memory
 * SharedPreferences (the production one is EncryptedSharedPreferences, which needs
 * the Android keystore). PHASE 1 (native init / client rebuild) is not driven here.
 */
class SmartTxtApplePauseTest {

    /** rustpush's Display text for the Nov 25 failure, and what the bridge shows for it. */
    private val raw6009 = "Failed to generate resource Registration Error Your iMessage access is " +
        "temporarily disabled. (6009)"
    private val shown6009 = RustPushBridge.humanizeLoginError(raw6009)

    private val t0 = 1_764_111_683_595L // 2026-11-25 23:01:23Z, the first press in the log

    /** Stand-in for `bridge().reregister()`: replays scripted (shown, raw) results and
     *  counts calls — each call is one re-registration sent to Apple. */
    private class FakeApple(vararg results: Pair<RegistrationResult, String>) {
        private val queue = ArrayDeque(results.toList())
        var calls = 0
            private set
        var lastRaw = ""
            private set

        fun reregister(): RegistrationResult {
            calls++
            val (r, raw) = queue.removeFirst()
            if (r is RegistrationResult.Failure) lastRaw = raw // as reregisterOnce stashes it
            return r
        }
    }

    private fun account(lastRegisteredMs: Long = t0 - 86_400_000L) =
        SmartTxtAccount(appleId = "test@example.com", identityTokenB64 = "", pushTokenB64 = "",
            lastRegisteredMs = lastRegisteredMs)

    private fun fail6009() = RegistrationResult.Failure(shown6009) to raw6009
    private fun failWith(raw: String) = RegistrationResult.Failure(RustPushBridge.humanizeLoginError(raw)) to raw
    private fun success() = RegistrationResult.Success(account(lastRegisteredMs = t0)) to ""

    private val paused = SmartTxtRepository.FixOutcome.NeedsUser(RustPushBridge.APPLE_PAUSED_MESSAGE)

    private fun press(store: SmartTxtAccountStore, apple: FakeApple, nowMs: Long) =
        SmartTxtRepository.reregisterUnlessPaused(
            store = store,
            nowMs = nowMs,
            reregister = apple::reregister,
            lastRaw = { apple.lastRaw },
        )

    private fun newStore() = SmartTxtAccountStore { InMemoryPrefs() }.also { it.saveAccount(account()) }

    @Test
    fun fixConnection_afterApplePause_doesNotCallAppleAgain() {
        val store = newStore()
        val apple = FakeApple(fail6009(), success())

        val first = press(store, apple, t0)
        val second = press(store, apple, t0 + 13_000L) // the second press in the log

        assertEquals("Apple must be contacted exactly once", 1, apple.calls)
        assertEquals(paused, first)
        assertEquals("the second press shows the same 'Apple has paused…' copy, not a raw error", paused, second)
    }

    @Test
    fun pausePersistsAcrossStoreInstances() {
        // A process restart builds a new SmartTxtAccountStore over the same prefs.
        val prefs = InMemoryPrefs()
        val apple = FakeApple(fail6009(), success())
        press(SmartTxtAccountStore { prefs }.also { it.saveAccount(account()) }, apple, t0)
        val second = press(SmartTxtAccountStore { prefs }, apple, t0 + 60_000L)
        assertEquals(1, apple.calls)
        assertEquals(paused, second)
    }

    @Test
    fun pauseExpires_allowsReregister() {
        val store = newStore()
        val apple = FakeApple(fail6009(), success())

        press(store, apple, t0)
        // Still paused just inside the window.
        assertEquals(paused, press(store, apple, t0 + SmartTxtRepository.APPLE_PAUSE_MS - 1))
        assertEquals(1, apple.calls)

        // Once the window has passed, Re-register now reaches Apple again and works.
        val after = press(store, apple, t0 + SmartTxtRepository.APPLE_PAUSE_MS)
        assertEquals(2, apple.calls)
        assertEquals(SmartTxtRepository.FixOutcome.Recovered, after)
        assertEquals(0L, store.applePausedUntilMs())
    }

    @Test
    fun successfulSignIn_clearsThePause() {
        val store = newStore()
        val apple = FakeApple(fail6009(), success())

        press(store, apple, t0)
        assertTrue(store.applePausedUntilMs() > t0)

        // Interactive sign-in (finishRegister) and REREGISTER_OK (reregisterOnce) both
        // record success through markRegistered.
        store.markRegistered(t0 + 60_000L)
        assertEquals(0L, store.applePausedUntilMs())

        assertEquals(SmartTxtRepository.FixOutcome.Recovered, press(store, apple, t0 + 61_000L))
        assertEquals(2, apple.calls)
    }

    @Test
    fun signOutWipe_clearsThePause() {
        val store = newStore()
        press(store, FakeApple(fail6009()), t0)
        assertTrue(store.applePausedUntilMs() > t0)

        store.clear() // what shutdown(wipe = true) does to this store
        assertEquals(0L, store.applePausedUntilMs())
    }

    @Test
    fun clockSetFarBackwards_doesNotStrandTheUser() {
        // A pause recorded under a wrong (future-dated) clock must not block
        // re-registering for longer than one window once the clock is corrected.
        val store = newStore()
        val apple = FakeApple(fail6009(), success())
        press(store, apple, t0)

        val next = press(store, apple, t0 - 30L * 86_400_000L)
        assertEquals(2, apple.calls)
        assertEquals(SmartTxtRepository.FixOutcome.Recovered, next)
    }

    @Test
    fun transientFailure_doesNotPause() {
        val store = newStore()
        val apple = FakeApple(failWith("request timed out"), success())

        assertEquals(SmartTxtRepository.FixOutcome.RetryingInBackground, press(store, apple, t0))
        assertEquals(0L, store.applePausedUntilMs())

        press(store, apple, t0 + 13_000L)
        assertEquals(2, apple.calls)
    }

    @Test
    fun a6009WhoseCopyCameOutDifferently_stillPauses() {
        // The raw text, not only our copy, starts the pause: if an earlier humaniser
        // branch ever claims a 6009 string, the second press must still stay local.
        val raw = "Registration Error Your iMessage access is temporarily disabled (6009) request timed out"
        val store = newStore()
        val apple = FakeApple(failWith(raw), success())
        // First press shows the paused copy too, not the "try again"-style copy the
        // humaniser picked, while the pause is being recorded.
        assertEquals(paused, press(store, apple, t0))
        assertEquals(paused, press(store, apple, t0 + 13_000L))
        assertEquals(1, apple.calls)
    }

    @Test
    fun relayRateLimit_doesNotPause() {
        // A 429 "rate limit" from our own anisette/validation relay is not Apple pausing
        // the device; it must not block Re-register now for 24 h.
        val store = newStore()
        val apple = FakeApple(failWith("validation relay returned HTTP 429: rate limit exceeded"), success())
        press(store, apple, t0)
        assertEquals(0L, store.applePausedUntilMs())
        assertEquals(SmartTxtRepository.FixOutcome.Recovered, press(store, apple, t0 + 13_000L))
        assertEquals(2, apple.calls)
    }

    @Test
    fun smallClockStepBack_staysPaused() {
        // An NTP sync that moves the clock back a few minutes must not end the pause.
        val store = newStore()
        val apple = FakeApple(fail6009(), success())
        press(store, apple, t0)
        assertEquals(paused, press(store, apple, t0 - 5L * 60_000L))
        assertEquals(1, apple.calls)
    }

    @Test
    fun pausedOutcome_isWhatFixConnectionChecksBeforePhase1() {
        // fixConnection returns this before the (slow, retried) local client rebuild,
        // so a paused press offline answers at once.
        val store = newStore()
        assertEquals(null, SmartTxtRepository.applePausedOutcome(store, t0))
        press(store, FakeApple(fail6009()), t0)
        assertEquals(paused, SmartTxtRepository.applePausedOutcome(store, t0 + 13_000L))
        assertEquals(null, SmartTxtRepository.applePausedOutcome(store, t0 + SmartTxtRepository.APPLE_PAUSE_MS))
    }

    @Test
    fun rustpushReportingRegistered_clearsThePause() {
        // rustpush's own background retry re-registered successfully: REGSTATE says
        // "registered", so the pause is over.
        val store = newStore()
        press(store, FakeApple(fail6009()), t0)
        for (state in listOf("failed", "registering", "no_client", "unknown")) {
            SmartTxtRepository.clearApplePauseIfRegistered(state, store)
            assertTrue(state, store.applePausedUntilMs() > t0)
        }
        SmartTxtRepository.clearApplePauseIfRegistered("registered", store)
        assertEquals(0L, store.applePausedUntilMs())
    }

    @Test
    fun rateLimited_alsoPauses() {
        val store = newStore()
        val apple = FakeApple(
            failWith("Registration Error: You have been rate-limited. Trying to reconfigure or " +
                "re-install to 'fix' the rate limit will result in being temporarily blocked from iMessage."),
            success(),
        )
        press(store, apple, t0)
        assertEquals(paused, press(store, apple, t0 + 13_000L))
        assertEquals(1, apple.calls)
    }
}

/** Minimal in-memory [SharedPreferences] for [SmartTxtAccountStore]; apply() == commit(). */
internal class InMemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        (map[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            map.putAll(puts)
            return true
        }
        override fun apply() { commit() }
    }
}
