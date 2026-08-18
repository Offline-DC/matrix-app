package com.offline.dpadmessenger.backend.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the attachment-cache write path.
 *
 * The bug these pin down: `cacheDir` is system-evictable, the media directory was created
 * once in a `by lazy` initialiser, and after Android evicted `cache/` every attachment
 * write failed with ENOENT for the rest of the process lifetime — photos and audio
 * messages showed "Couldn't load — tap to retry" forever, while the download itself had
 * succeeded.
 *
 * `evictionMidProcess` is the exact field failure. It fails against the old
 * `File(...).apply { mkdirs() }`-once implementation and passes against MediaCache.
 */
class MediaCacheTest {

    private val roots = mutableListOf<File>()

    private fun newRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "mediacache-${System.nanoTime()}")
        roots += root
        return root
    }

    /** Stands in for `File(cacheDir, "smarttxt_media")`. */
    private fun mediaDir(root: File) = File(root, "smarttxt_media")

    @After fun cleanup() = roots.forEach { it.deleteRecursively() }

    @Test
    fun `writes when the directory does not exist yet`() {
        val dir = mediaDir(newRoot())
        assertTrue("precondition: directory absent", !dir.exists())

        val out = MediaCache.write(dir, "a.heic", byteArrayOf(1, 2, 3))

        assertNotNull("first write must create the directory", out)
        assertTrue(out!!.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), out.readBytes())
    }

    /**
     * The field failure, reproduced: a successful write, then the OS clears cache/,
     * then another write. The second write must still succeed.
     */
    @Test
    fun `writes after the cache directory is evicted mid-process`() {
        val dir = mediaDir(newRoot())

        val first = MediaCache.write(dir, "first.heic", ByteArray(16) { 1 })
        assertNotNull("baseline write should succeed", first)

        // What Android does under storage pressure: the whole subtree, silently.
        dir.deleteRecursively()
        assertTrue("precondition: directory evicted", !dir.exists())

        val second = MediaCache.write(dir, "second.heic", ByteArray(32) { 2 })

        assertNotNull("write after eviction must recover, not fail with ENOENT", second)
        assertEquals(32, second!!.readBytes().size)
    }

    /** Eviction is not a one-shot event; the recovery must keep working. */
    @Test
    fun `survives repeated evictions`() {
        val dir = mediaDir(newRoot())
        repeat(5) { i ->
            val out = MediaCache.write(dir, "round$i.jpeg", ByteArray(8) { i.toByte() })
            assertNotNull("write $i must succeed", out)
            dir.deleteRecursively()
        }
    }

    /** A stale plain file squatting on the directory path would otherwise wedge mkdirs forever. */
    @Test
    fun `recovers when a plain file occupies the directory path`() {
        val root = newRoot()
        val dir = mediaDir(root)
        root.mkdirs()
        dir.writeBytes(byteArrayOf(9))
        assertTrue("precondition: path is a file", dir.isFile)

        val out = MediaCache.write(dir, "x.opus", byteArrayOf(4, 5))

        assertNotNull("must replace the squatting file", out)
        assertTrue(dir.isDirectory)
        assertArrayEquals(byteArrayOf(4, 5), out!!.readBytes())
    }

    /** Unwritable parent: must return null and log, never throw into the caller. */
    @Test
    fun `returns null instead of throwing when the directory cannot be created`() {
        val root = newRoot()
        root.mkdirs()
        val blocked = File(root, "blocked")
        blocked.mkdirs()
        // If the platform ignores the permission bit (some CI filesystems, root), the
        // write legitimately succeeds; only assert the contract when we really blocked it.
        val readOnly = blocked.setWritable(false)

        val out = MediaCache.write(File(blocked, "smarttxt_media"), "y.bin", byteArrayOf(1))

        if (readOnly && !File(blocked, "smarttxt_media").isDirectory) {
            assertNull("must fail soft, not throw", out)
        }
        blocked.setWritable(true)
    }

    @Test
    fun `ensureDir is idempotent and cheap to call repeatedly`() {
        val dir = mediaDir(newRoot())
        assertTrue(MediaCache.ensureDir(dir))
        assertTrue(MediaCache.ensureDir(dir))
        assertTrue(dir.isDirectory)
    }

    /**
     * Guards this suite against going vacuous.
     *
     * Reproduces the OLD implementation inline — `mkdirs()` once, `File` memoised, exactly
     * what `by lazy { File(...).apply { mkdirs() } }` compiles to — and asserts that it
     * really does fail after an eviction, with the same errno seen in the field bundle.
     *
     * If this test ever stops throwing, then `writes after the cache directory is evicted
     * mid-process` has stopped proving anything and this file needs revisiting. That is
     * cheaper to catch here than by hand-reverting production code.
     */
    @Test
    fun `the old lazy-mkdirs implementation really does fail after eviction`() {
        val dir = mediaDir(newRoot())

        // The `by lazy` initialiser: runs mkdirs() once, then the File is reused forever.
        val memoised = dir.apply { mkdirs() }
        memoised.resolve("first.heic").writeBytes(ByteArray(4))   // baseline: succeeds

        dir.deleteRecursively()                                   // Android evicts cache/

        val thrown: java.io.FileNotFoundException? = try {
            File(memoised, "second.heic").writeBytes(ByteArray(4))
            null
        } catch (e: java.io.FileNotFoundException) {
            e
        }

        assertNotNull(
            "the old implementation must fail after eviction — otherwise the eviction " +
                "tests in this file are vacuously green",
            thrown,
        )
        assertTrue(
            "must be the same ENOENT seen in the field bundle, got: ${thrown!!.message}",
            thrown.message!!.contains("No such file or directory"),
        )

        // ...and MediaCache recovers on that very same evicted directory.
        assertNotNull(
            "MediaCache must succeed where the old implementation failed",
            MediaCache.write(dir, "third.heic", ByteArray(4)),
        )
    }

    @Test
    fun `writes empty and large payloads`() {
        val dir = mediaDir(newRoot())
        assertNotNull(MediaCache.write(dir, "empty.bin", ByteArray(0)))
        // Roughly the size of the HEIC that failed in the field bundle.
        val big = ByteArray(746_982) { (it % 251).toByte() }
        val out = MediaCache.write(dir, "big.heic", big)
        assertNotNull(out)
        assertArrayEquals(big, out!!.readBytes())
    }
}
