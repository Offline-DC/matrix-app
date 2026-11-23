package com.offline.dpadmessenger.backend.signal

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Offline-DC/matrix-app#24: Signal photos and voice notes never sent because the
 * upload-form request had no `uploadLength`, and Signal's server answers 400 then.
 * This drives the real [SignalApi] over HTTP against a local server that behaves
 * like Signal's `/v4/attachments/form/upload`.
 */
class SignalAttachmentUploadFormTest {
    private lateinit var server: ServerSocket
    private val seenQueries = mutableListOf<String?>()

    /**
     * A tiny HTTP/1.1 server on a plain socket (the JDK's com.sun HttpServer isn't on
     * the Android unit-test classpath). Answers like Signal's upload-form endpoint:
     * 400 without a positive `uploadLength`, otherwise a form.
     */
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "fake-signal") {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
                    val target = requestLine.split(' ')[1]
                    val query = target.substringAfter('?', "").ifEmpty { null }
                    synchronized(seenQueries) { seenQueries += query }
                    val length = query?.split('&')?.firstOrNull { it.startsWith("uploadLength=") }
                        ?.substringAfter('=')?.toLongOrNull()
                    val (code, body) = if (!target.startsWith("/v4/attachments/form/upload")) {
                        404 to "{}"
                    } else if (length == null || length <= 0) {
                        400 to """{"code":400,"message":"uploadLength is required"}"""
                    } else {
                        200 to """{"cdn":3,"key":"abc123","headers":{"x":"y"},"signedUploadLocation":"https://cdn3.example/upload"}"""
                    }
                    val bytes = body.toByteArray()
                    val out = s.getOutputStream()
                    out.write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun api() = SignalApi(OkHttpClient(), "http://127.0.0.1:${server.localPort}")

    @Test
    fun `upload form request carries the encrypted size and the server accepts it`() = runBlocking {
        val form = api().getAttachmentUploadForm("login", "password", uploadLength = 48_213)
        assertEquals(listOf<String?>("uploadLength=48213"), seenQueries)
        assertEquals(3, form.cdn)
        assertEquals("abc123", form.key)
        assertEquals("https://cdn3.example/upload", form.signedUploadLocation)
    }

    @Test
    fun `upload form URL has uploadLength`() {
        assertEquals(
            "https://chat.signal.org/v4/attachments/form/upload?uploadLength=1024",
            SignalApi.uploadFormUrl("https://chat.signal.org", 1024),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-byte upload is refused before it reaches the server`() {
        SignalApi.uploadFormUrl("https://chat.signal.org", 0)
    }
}
