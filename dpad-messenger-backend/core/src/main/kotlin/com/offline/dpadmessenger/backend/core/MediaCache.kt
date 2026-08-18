package com.offline.dpadmessenger.backend.core

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Writes attachment bytes into an app-cache directory, re-creating that directory
 * every time rather than trusting that it still exists.
 *
 * ## Why this exists
 *
 * `Context.cacheDir` is *system-evictable*: Android deletes app cache contents —
 * including subdirectories, not just the files inside them — whenever the device runs
 * low on storage, and it does not tell the app. On a 4 GB flip phone that is a routine
 * event, not an edge case.
 *
 * The previous implementation was:
 *
 * ```kotlin
 * private val mediaDir by lazy { File(appContext.cacheDir, "smarttxt_media").apply { mkdirs() } }
 * ```
 *
 * `by lazy` runs `mkdirs()` exactly once per process and then memoises the `File`. So
 * the sequence was:
 *
 *  1. First attachment of the process: `mkdirs()` runs, directory exists, write succeeds.
 *  2. Android evicts `cache/`. The directory is gone. The memoised `File` still points at it.
 *  3. Every subsequent write fails with `ENOENT (No such file or directory)` — for the
 *     rest of the process lifetime, because nothing ever calls `mkdirs()` again.
 *
 * `FileOutputStream` opens with `O_CREAT`, so `ENOENT` never means "the file is missing";
 * it means a *parent path component* is missing. Restarting the app made the symptom
 * disappear (new process → `by lazy` re-runs) until the next eviction, which is what made
 * it look intermittent and environment-specific in support reports.
 *
 * The rule this class encodes: **never assume a cache directory that existed a moment ago
 * still exists.** Assert it immediately before each write, and treat a failed write as
 * possibly-evicted and worth one retry.
 */
object MediaCache {
    private const val TAG = "MediaCache"

    /**
     * Ensures [dir] exists as a directory. Returns true if it does when this returns.
     *
     * Safe to call on every write: when the directory is already present this is a single
     * `stat`, which is far cheaper than the multi-hundred-KB write that follows it.
     */
    fun ensureDir(dir: File): Boolean {
        if (dir.isDirectory) return true

        // A stale *file* sitting at the directory path would make mkdirs() fail forever,
        // so clear it rather than looping on an unfixable error.
        if (dir.exists()) {
            Log.w(TAG, "mediaCache: ${dir.path} exists but is not a directory — removing it")
            if (!dir.delete()) {
                Log.e(TAG, "mediaCache: could not remove non-directory ${dir.path}")
                return false
            }
        }

        val created = dir.mkdirs()
        // mkdirs() returns false if a concurrent caller won the race, which is fine —
        // "does it exist now" is the only question that matters.
        if (!dir.isDirectory) {
            Log.e(TAG, "mediaCache: mkdirs failed for ${dir.path} (usableSpace=${usableSpace(dir)})")
            return false
        }
        if (created) {
            Log.i(TAG, "mediaCache: created ${dir.path} — it was absent (first use of this process, or cache eviction)")
        }
        return true
    }

    /**
     * Writes [bytes] to [name] inside [dir], creating [dir] first.
     *
     * Retries once, because an eviction can land between the `ensureDir` check and the
     * write itself. Returns the written file, or null on failure (already logged).
     */
    fun write(dir: File, name: String, bytes: ByteArray): File? {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (!ensureDir(dir)) continue
            val file = File(dir, name)
            try {
                file.writeBytes(bytes)
                return file
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "mediaCache: write attempt $attempt/$MAX_ATTEMPTS failed for ${file.path} " +
                        "(${bytes.size}B, dirExists=${dir.isDirectory})", e)
            }
        }
        // Include usableSpace so the next support bundle distinguishes "directory missing"
        // (ENOENT) from "storage actually full" (ENOSPC) without needing the stack trace.
        Log.e(TAG, "mediaCache: gave up writing $name into ${dir.path} " +
                "(${bytes.size}B, dirExists=${dir.isDirectory}, usableSpace=${usableSpace(dir)})", lastError)
        return null
    }

    private fun usableSpace(dir: File): Long =
        runCatching { dir.usableSpace }.getOrDefault(-1L)

    private const val MAX_ATTEMPTS = 2
}
