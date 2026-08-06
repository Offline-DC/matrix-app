# Fix: duplicate outgoing bubbles after re-link

**Repo:** `matrix-app` · **Module:** `dpad-messenger-backend/gmessages`
**Base:** HEAD `8bfbc6b` — note your tree has **uncommitted edits to this same file**
(`GoogleMessagesMessageRepository.kt`, the `unpairRemote()` work), so these are given as
exact before/after blocks rather than a line-numbered diff. Every anchor below was read from
the live working tree, not from HEAD.

All five changes are in **one file**:
`gmessages/src/main/kotlin/com/offline/dpadmessenger/backend/gmessages/GoogleMessagesMessageRepository.kt`

No new imports needed — `Log`, `Message`, `MessageStatus` are already imported.

---

## Ship now (Tier 1) — stops the symptom regardless of which cause is real

### 1. Constants

In the `companion object`, next to `AUTO_DELETE_AGE_MS` (~line 1028):

```kotlin
        private const val AUTO_DELETE_AGE_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

        /** Prefix for optimistic local rows, replaced when the phone echoes the id back. */
        private const val TMP_ID_PREFIX = "tmp_"

        /** How far apart an optimistic row and its echo may be and still reconcile by
         *  body, when the echo came back without the tmpID. Generous on purpose: the
         *  two timestamps come from different clocks (this handset vs. the paired
         *  phone), so a tight window would miss real matches. The (tmp_ id + outgoing
         *  + exact body) triple is already doing the discriminating work. */
        private const val TMP_RECONCILE_WINDOW_MS = 5L * 60 * 1000
```

Then use the prefix at both send sites (~line 559 text, ~line 826 media):

```kotlin
-        val tmpId = "tmp_" + System.nanoTime()
+        val tmpId = TMP_ID_PREFIX + System.nanoTime()
```

---

### 2. Fallback reconciliation — the actual fix

In `onMessages`, replace:

```kotlin
            // De-dup: replace an optimistic local copy (matched by tmpID) or an
            // earlier copy of the same server id.
            val idx = list.indexOfFirst {
                it.id == mapped.id || (gm.tmpId.isNotEmpty() && it.id == gm.tmpId)
            }
            val isNew = idx < 0
```

with:

```kotlin
            // De-dup: replace an optimistic local copy (matched by tmpID) or an
            // earlier copy of the same server id.
            var idx = list.indexOfFirst {
                it.id == mapped.id || (gm.tmpId.isNotEmpty() && it.id == gm.tmpId)
            }
            // Fallback for an echo that came back WITHOUT the tmpID it was sent
            // with — observed after a re-link, when the send went out carrying a
            // stale or blank defaultOutgoingID. With no tmpID on the echo, the id
            // check above can never match a "tmp_…" row, so the optimistic bubble
            // survives beside the server copy and the user sees the message twice.
            // Permanently: the two rows have distinct ids, so every later sync and
            // re-link preserves both. Match the orphan on (outgoing, exact body,
            // close in time) instead. FAILED rows are excluded — no echo is coming
            // for those and the user may still want to resend them.
            if (idx < 0 && gm.isOutgoing) {
                idx = list.indexOfFirst {
                    it.id.startsWith(TMP_ID_PREFIX) &&
                        it.isOutgoing &&
                        it.status != MessageStatus.FAILED &&
                        it.body == mapped.body &&
                        kotlin.math.abs(it.timestampMs - mapped.timestampMs) <= TMP_RECONCILE_WINDOW_MS
                }
                // Both branches log at W so they survive ROLLING_LOGCAT_FILTERSPEC
                // (GMRepo is deliberately not allowlisted — it carries contact
                // names — but the trailing *:W catches everything at W and above).
                // Ids only, no message body.
                Log.w(
                    TAG,
                    if (idx >= 0) {
                        "dedup-fallback: echo had no tmpID, reconciled by body+time " +
                            "msg=${gm.messageId} conv=${gm.conversationId} " +
                            "sentAs='${outgoingIdByRoom[gm.conversationId].orEmpty()}'"
                    } else {
                        "dedup-miss: outgoing echo matched nothing " +
                            "msg=${gm.messageId} conv=${gm.conversationId} " +
                            "tmpId='${gm.tmpId}' " +
                            "sentAs='${outgoingIdByRoom[gm.conversationId].orEmpty()}' " +
                            "tmpRows=${list.count { it.id.startsWith(TMP_ID_PREFIX) }}"
                    },
                )
            }
            val isNew = idx < 0
```

`val idx` → `var idx` is the only other edit; everything downstream (`isNew`, `preserved`,
`list[idx] = preserved`) works unchanged.

**Why this is safe against collapsing two genuine identical sends.** The fallback only
considers rows still carrying a `tmp_` id. A row that already reconciled no longer has one, so
sending "ok" twice reconciles tmp_A then tmp_B in order. Worst case two identical sends land
in the wrong order — indistinguishable to the user, since the text is identical.

---

### 3. Sweep orphaned `tmp_` rows on restore

This is what clears the duplicates already sitting on affected devices, so customers only need
to update — no logout, no history loss.

In `restoreFromCache`, replace:

```kotlin
            messagesByRoom.value = snap.messagesByRoom
                .mapValues { (_, list) -> list.filter { it.timestampMs >= cutoff } }
```

with:

```kotlin
            // Sweep orphaned optimistic rows. A "tmp_…" id means the phone's echo
            // never reconciled that bubble. On a fresh process there is no send in
            // flight, so anything still carrying one is a leftover duplicate — and
            // Google re-delivers the real copy on the next sync, so dropping it
            // loses nothing. FAILED rows are kept: those never reached Google.
            fun isOrphanedOptimistic(m: Message) =
                m.id.startsWith(TMP_ID_PREFIX) && m.status != MessageStatus.FAILED

            val aged = snap.messagesByRoom
                .mapValues { (_, list) -> list.filter { it.timestampMs >= cutoff } }
            val swept = aged.values.sumOf { list -> list.count(::isOrphanedOptimistic) }
            if (swept > 0) {
                Log.w(TAG, "restore: swept $swept orphaned optimistic row(s) — unreconciled tmp_ ids")
            }
            messagesByRoom.value = aged
                .mapValues { (_, list) -> list.filterNot(::isOrphanedOptimistic) }
```

That `swept` line is also your field confirmation: it appears once per affected device on the
first launch after the update, and never again.

---

### 4. Don't let a blank `defaultOutgoingID` clobber a good one

In `onConversations`, replace:

```kotlin
            outgoingIdByRoom[c.conversationId] = c.defaultOutgoingId
```

with:

```kotlin
            // Field 11 (defaultOutgoingID) is optional on the wire and decodes to
            // "" when absent (GMSessionProto: `var outgoingId = ""`). Writing that
            // over a known-good id makes the next send go out with a blank
            // self-participant, which is one route to an echo with no tmpID.
            if (c.defaultOutgoingId.isNotEmpty()) {
                outgoingIdByRoom[c.conversationId] = c.defaultOutgoingId
            }
```

---

### 5. Shutdown ordering — stop a queued save resurrecting what logout just deleted

Replace:

```kotlin
    fun shutdown(clearCache: Boolean = true) {
        session.shutdown()
        if (clearCache) cache.clear()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
```

with:

```kotlin
    fun shutdown(clearCache: Boolean = true) {
        session.shutdown()
        // Cancel BEFORE clearing. The debounced save loop lives in this scope
        // (CONFLATED channel, ~1.5s collapse), so a save already queued or in
        // flight would otherwise land *after* MessageStore's DELETE and
        // re-persist the snapshot we just wiped — bringing the old messages, and
        // any stale tmp_ rows, straight back after a logout.
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (clearCache) cache.clear()
    }
```

---

## Hold (Tier 2) — until the `dedup-miss` log tells you which cause is real

Tier 1 fixes the symptom either way. This one only matters if the logs confirm the stale-send-
identity theory, and it has a bandwidth cost, so don't ship it blind.

`GoogleMessagesSessionClient.kt:301` — `requestConversationList(count: Int = 25)`, single call
site at `:1049`. With 64–86 cached rooms, 39–61 threads never re-sync at session start and keep
their **pre-relink** `defaultOutgoingID` indefinitely (`outgoingIdByRoom.putAll(...)` merges,
never replaces). If `dedup-miss` shows `tmpId=''` together with a `sentAs` that looks stale,
the fix is to re-sync every cached room once after a re-link:

```kotlin
// at the REGISTER trigger, ~:1049
if (accepted && (trigger == ActiveSessionTrigger.REGISTER || quiet)) {
    requestConversationList(count = if (trigger == ActiveSessionTrigger.REGISTER) 200 else 25)
}
```

**Read the log first.** If `dedup-miss` instead shows a populated `tmpId` that simply matched
nothing, the cause is the other candidate — a re-link leaving two repository instances alive in
the same process (see the comment already at `MessageStore.save():244`) — and this change does
nothing for it. In that case the work is in `GoogleMessagesRepository.shutdown()` /
`instance` lifecycle, and Tier 1's fallback is what's actually saving you.

---

## Test plan

A clean login reproduces nothing on either build — the bug needs the post-relink state. To get
a meaningful before/after:

1. Device with an established link and **more than 25 conversations**.
2. On the current build: **Re-link phone** (not Log out) — the state-preserving path is the one
   that sets the bug up.
3. Send in a thread that is **not** in the 25 most recent. Recently-active threads may behave
   fine even on the broken build.
4. Confirm the signature: `MsgStore/gmessages: saved:` goes `X → X+1 → X+2` across one send.
5. Update to the fixed build. Expect on first launch:
   `GMRepo: restore: swept N orphaned optimistic row(s)`
   and on the next send `X → X+1 → X+1`, with either no `dedup-*` line or a
   `dedup-fallback` line.

Send one message at a time and note the last `saved:` before you start typing — the save
channel is conflated with a ~1.5 s collapse, so two writes can merge into one log line and hide
the increment.
