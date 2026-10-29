package com.aoede.twa

import android.graphics.Bitmap
import android.os.Looper
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class NativeArtworkTest {
    private val app
        get() = RuntimeEnvironment.getApplication() as AoedeApp

    private fun session(scope: String) {
        NativeApi::class
            .java
            .getDeclaredField("session")
            .apply { isAccessible = true }
            .set(
                app.nativeApi,
                JSONObject()
                    .put("access_token", "fixture-only")
                    .put("context", JSONObject().put("storage_scope", scope)),
            )
    }

    private fun settle(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Artwork callbacks must complete, including failures", done())
    }

    @Test
    fun coversShareRequestsFollowOnlyLocalRedirectsAndNeverLeakAcrossAccounts() {
        val hits = AtomicInteger()
        val wrongAccept = AtomicInteger()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val png = app.assets.open("web/assets/img/default_cover.png").use { it.readBytes() }
        val thread =
            Thread {
                    while (!server.isClosed) try {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val path = reader.readLine().split(' ')[1]
                            val headers =
                                generateSequence { reader.readLine().takeIf { it.isNotEmpty() } }
                                    .toList()
                            if (headers.none { it.equals("Accept: image/*", true) })
                                wrongAccept.incrementAndGet()
                            hits.incrementAndGet()
                            val redirect =
                                when (path) {
                                    "/cover" -> "/image"
                                    "/escape" -> "http://127.0.0.1:1/private"
                                    else -> null
                                }
                            val status = if (redirect == null) 200 else 307
                            val body =
                                if (path == "/html") "<html>Login</html>".toByteArray()
                                else if (redirect == null) png else byteArrayOf()
                            val response =
                                "HTTP/1.1 $status OK\r\nContent-Type: ${if(path == "/html") "text/html" else "image/png"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                                    (redirect?.let { "Location: $it\r\n" } ?: "") +
                                    "\r\n"
                            socket.getOutputStream().apply {
                                write(response.toByteArray())
                                write(body)
                                flush()
                            }
                        }
                    } catch (_: java.io.IOException) {
                        if (!server.isClosed) throw AssertionError("Fixture socket failed")
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
        try {
            app.nativeApi.configure("http://127.0.0.1:${server.localPort}")
            session("a".repeat(64))
            val results = mutableListOf<Bitmap?>()
            repeat(2) {
                app.nativeApi.artwork.load("http://127.0.0.1:${server.localPort}/cover", 100) {
                    results.add(it)
                }
            }
            settle { results.size == 2 }
            assertNotNull(results[0])
            assertSame(results[0], results[1])
            assertEquals(2, hits.get())
            app.nativeApi.artwork.load("http://127.0.0.1:${server.localPort}/cover", 100) {
                results.add(it)
            }
            assertEquals(3, results.size)
            assertEquals(2, hits.get())
            app.nativeApi.clearSession()
            session("b".repeat(64))
            app.nativeApi.artwork.load("http://127.0.0.1:${server.localPort}/cover", 100) {
                results.add(it)
            }
            settle { results.size == 4 }
            assertNotSame(results[0], results[3])
            assertEquals(4, hits.get())
            for (path in listOf("/escape", "/html")) {
                val count = results.size
                app.nativeApi.artwork.load(path, 100) { results.add(it) }
                settle { results.size == count + 1 }
                assertNull(path, results.last())
            }
            assertEquals(6, hits.get())
            assertEquals(0, wrongAccept.get())
        } finally {
            server.close()
            thread.join(1000)
        }
    }
}
