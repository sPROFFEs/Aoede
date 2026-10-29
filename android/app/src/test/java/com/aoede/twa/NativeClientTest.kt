package com.aoede.twa

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DataSpec
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sin
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeClientTest {
    private val app
        get() = RuntimeEnvironment.getApplication() as AoedeApp

    private fun context(
        name: String = "alice",
        scope: String = "a".repeat(64),
        token: String = "synthetic-session",
    ) {
        val value =
            JSONObject()
                .put("access_token", token)
                .put(
                    "context",
                    JSONObject()
                        .put("protocol", 2)
                        .put("storage_scope", scope)
                        .put("theme", "classic")
                        .put("language", "en")
                        .put("account", JSONObject().put("id", 1).put("username", name)),
                )
        NativeApi::class
            .java
            .getDeclaredField("session")
            .apply { isAccessible = true }
            .set(app.nativeApi, value)
    }

    @Before
    fun setup() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("use_native_player", true)
            .putString(MainActivity.KEY_URL, "https://music.test:8443")
            .commit()
        app.nativeApi.configure("https://music.test:8443")
    }

    @After
    fun cleanup() {
        app.destroyPlayer()
        PlaybackService.starting = false
    }

    @Test
    fun nativeLoginHasNoWebViewOrAudioOwner() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun count(view: View): Int =
            if (view is WebView) 1
            else if (view is ViewGroup)
                (0 until view.childCount).sumOf { count(view.getChildAt(it)) }
            else 0
        assertEquals(0, count(lifecycle.get().window.decorView))
        assertNull(app.player)
        assertFalse(app.nativePlayback.active)
        assertNotNull(lifecycle.get().findViewById<View>(R.id.main_root))
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun refreshingAnUnchangedPageKeepsItsViewsAndOnlyReplacesChangedData() {
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        context()
        val ui = NativeUi(lifecycle.get(), app)
        ui.screen("Starting")
        val workers =
            NativeApi::class
                .java
                .getDeclaredField("workers")
                .apply { isAccessible = true }
                .get(app.nativeApi) as java.util.concurrent.ThreadPoolExecutor
        fun refresh(value: String) {
            val before = workers.completedTaskCount
            ui.load(
                "Saved collection",
                "/api/playlists/7",
                read = { JSONObject().put("label", value) },
                stableKey = "fixture-layout",
            ) {
                ui.label((it as JSONObject).getString("label"))
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (workers.completedTaskCount <= before && System.nanoTime() < deadline) Thread
                .sleep(5)
            assertTrue(workers.completedTaskCount > before)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        refresh("Original content")
        val original = ui.content.getChildAt(0)
        refresh("Original content")
        assertSame(original, ui.content.getChildAt(0))
        refresh("Updated content")
        assertNotSame(original, ui.content.getChildAt(0))
        assertEquals(
            "Updated content",
            (ui.content.getChildAt(0) as android.widget.TextView).text.toString(),
        )
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun navigationSnapshotRestoresTheRouteWithoutSavingSecretsOrCrossingAccounts() {
        context()
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val ui = NativeUi(lifecycle.get(), app)
        ui.create()
        ui.search("Sample search")
        ui.labelledField("Private credential", password = true)
            .setText("fixture-only-private-draft")
        val state = ui.saveState()
        assertEquals("search", state.getString("route"))
        assertFalse(state.toString().contains("fixture-only-private-draft"))
        assertTrue(ui.restoreState(state))
        context("bob", "b".repeat(64))
        assertFalse(ui.restoreState(state))
        ui.close()
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun coverUploadSendsTheActualImageTypeToTheExistingValidatedController() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        app.nativeApi.configure("http://127.0.0.1:${server.localPort}")
        context()
        val folder = java.io.File(app.cacheDir, "updates").apply { mkdirs() }
        val image = java.io.File(folder, "native-upload-fixture.png")
        image.writeBytes(app.assets.open("web/assets/img/default_cover.png").use { it.readBytes() })
        val provider =
            object : android.content.ContentProvider() {
                override fun onCreate() = true

                override fun getType(uri: Uri) = "image/png"

                override fun openFile(uri: Uri, mode: String) =
                    android.os.ParcelFileDescriptor.open(
                        image,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )

                override fun query(
                    uri: Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?,
                ): android.database.Cursor? = null

                override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null

                override fun delete(
                    uri: Uri,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                ) = 0

                override fun update(
                    uri: Uri,
                    values: android.content.ContentValues?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                ) = 0
            }
        provider.attachInfo(
            app,
            android.content.pm.ProviderInfo().apply { authority = "native.upload.fixture" },
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
            "native.upload.fixture",
            provider,
        )
        val uri = Uri.parse("content://native.upload.fixture/cover.png")
        val part = java.util.concurrent.atomic.AtomicReference("")
        val worker =
            Thread {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        fun line(): String {
                            val text = StringBuilder()
                            while (true) {
                                val n = input.read()
                                if (n < 0 || n == 10) return text.toString().trimEnd('\r')
                                text.append(n.toChar())
                            }
                        }
                        line()
                        val headers =
                            generateSequence { line().takeIf { it.isNotEmpty() } }.toList()
                        val length =
                            headers
                                .first { it.startsWith("Content-Length:", true) }
                                .substringAfter(':')
                                .trim()
                                .toInt()
                        val bytes = ByteArray(length)
                        var count = 0
                        while (count < length) {
                            val n = input.read(bytes, count, length - count)
                            check(n > 0)
                            count += n
                        }
                        part.set(bytes.toString(Charsets.ISO_8859_1))
                        val body = "{\"ok\":true}"
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                                    .toByteArray()
                            )
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
        try {
            val result =
                app.nativeApi.upload("/api/playlists/1/cover/upload", "cover_file", uri)
                    as JSONObject
            assertTrue(result.getBoolean("ok"))
            assertTrue(part.get().contains("Content-Type: image/png\r\n"))
            assertTrue(part.get().contains("name=\"cover_file\""))
            assertFalse(part.get().contains("Content-Type: application/octet-stream"))
        } finally {
            server.close()
            worker.join(1000)
            image.delete()
        }
    }

    @Test
    fun fileSelectionCannotUploadIntoAnAccountSignedInAfterThePickerOpened() {
        context()
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = lifecycle.get()
        var uploads = 0
        activity.pickNativeFile("image/*") { uploads++ }
        @Suppress("UNCHECKED_CAST")
        val callback =
            MainActivity::class
                .java
                .getDeclaredField("nativeFileCallback")
                .apply { isAccessible = true }
                .get(activity) as (Uri) -> Unit
        context("bob", "b".repeat(64))
        callback(Uri.parse("content://fixture/image"))
        assertEquals(0, uploads)
        lifecycle.pause().stop().destroy()
    }

    @Test
    fun configuredOriginPinsSchemeHostPortAndRejectsCredentialedOrMalformedUrls() {
        val origin = ServerOrigin.from(Uri.parse("https://music.test:8443"))!!
        assertEquals("/api/playlists", NativeApi.resolve(origin, "/api/playlists").path)
        for (value in
            listOf(
                "https://other.test:8443/api",
                "https://music.test/api",
                "http://music.test:8443/api",
                "//other.test/api",
                "https://user:pass@music.test:8443/api",
                "/api#fragment",
                "/api\r\nX: injected",
                "\\\\other.test/api",
            )) {
            assertTrue(value, runCatching { NativeApi.resolve(origin, value) }.isFailure)
        }
        assertEquals(
            "https://music.test",
            NativeApi.originHeader(ServerOrigin("https", "music.test", 443)),
        )
        assertEquals(
            "http://music.test",
            NativeApi.originHeader(ServerOrigin("http", "music.test", 80)),
        )
        assertEquals("https://music.test:8443", NativeApi.originHeader(origin))
        context()
        assertNotNull(app.nativeApi.cookieHeader(origin))
        assertNull(app.nativeApi.cookieHeader(ServerOrigin("https", "music.test", 443)))
        val stale = app.nativeApi.connection("/api/playlists")
        app.nativeApi.clearSession()
        assertTrue(runCatching { app.nativeApi.checkResponse(stale) }.isFailure)
        stale.disconnect()
    }

    @Test
    fun audioIdsNeverConfuseProviderIdsWithLocalOrFederatedFiles() {
        assertEquals(
            0,
            NativeTrack.localId(JSONObject().put("id", "12345678901").put("source", "youtube")),
        )
        assertEquals("/api/stream/7", NativeTrack.stream(JSONObject().put("db_id", 7)))
        val remote =
            JSONObject().put("id", "fed_2_9").put("fed_instance_id", 2).put("fed_remote_id", 9)
        assertEquals("fed_2_9", NativeTrack.reference(remote))
        assertEquals("/api/federation/proxy/2/stream/9", NativeTrack.stream(remote))
        assertEquals("/api/federation/proxy/2/collections/9", NativeTrack.collectionPath("fed_2_9"))
        assertTrue(runCatching { NativeTrack.collectionPath("../../admin") }.isFailure)
    }

    @Test
    fun downloadedFilesAreScopedByAccountAndOriginAndFailedReplacementPreservesOriginal() {
        context()
        val library = app.nativeLibrary
        val scope = library.scope()
        val bytes = "original audio".toByteArray()
        fun record(data: ByteArray) =
            JSONObject()
                .put("id", 7)
                .put("db_id", 7)
                .put("title", "Device track")
                .put("offline_size", data.size)
                .put(
                    "offline_sha256",
                    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
                        "%02x".format(it)
                    },
                )
        val temporary =
            java.io.File(library.directory(), "test.partial").apply { writeBytes(bytes) }
        library.commitAudio("7", record(bytes), temporary)
        assertArrayEquals(bytes, library.audio("7")!!.readBytes())
        val audioFile = library.audio("7")!!
        val previous = java.io.File(library.directory(), NativeApi.digest("7") + ".previous")
        assertTrue(audioFile.renameTo(previous))
        audioFile.writeBytes(ByteArray(bytes.size) { 42 })
        assertArrayEquals(bytes, library.audio("7")!!.readBytes())
        assertFalse(previous.exists())
        val replacement = "replacement".toByteArray()
        temporary.writeBytes(replacement)
        library.writableDatabase.execSQL(
            "CREATE TRIGGER reject_native_write BEFORE INSERT ON records BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END"
        )
        assertTrue(
            runCatching { library.commitAudio("7", record(replacement), temporary) }.isFailure
        )
        library.writableDatabase.execSQL("DROP TRIGGER reject_native_write")
        assertArrayEquals(bytes, library.audio("7")!!.readBytes())
        context("bob", "b".repeat(64))
        assertTrue(library.list("track").isEmpty())
        assertNull(library.audio("7"))
        assertNotEquals(scope, library.scope())
        app.nativeApi.configure("https://music.test:9443")
        context()
        assertTrue(library.list("track").isEmpty())
        assertNotEquals(scope, library.scope())
    }

    @Test
    fun pcmSamplingDoesNotAlterOutputAndReplayGainCannotOverflowOrPropagateNan() {
        val processor = NativeAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.sampling = true
        val buffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder())
        repeat(512) {
            buffer.putShort((sin(2 * Math.PI * 250 * it / 48000) * 16000).toInt().toShort())
        }
        buffer.flip()
        val original = ByteArray(buffer.remaining())
        buffer.duplicate().get(original)
        processor.queueInput(buffer)
        val processed = processor.output
        val actual = ByteArray(processed.remaining())
        processed.get(actual)
        assertArrayEquals(original, actual)
        assertNotNull(processor.levels)
        assertTrue(processor.levels!![1] > processor.levels!![3])
        processor.sampling = false
        val second = ByteBuffer.wrap(original).order(ByteOrder.nativeOrder())
        processor.queueInput(second)
        val output = processor.output
        val unchanged = ByteArray(output.remaining())
        output.get(unchanged)
        assertArrayEquals(original, unchanged)
        assertEquals(1f, NativeAudioProcessor.replayGain(Double.NaN, 1.0))
        assertTrue(NativeAudioProcessor.replayGain(20.0, 2.0) <= .4951f)
    }

    @Test
    fun nativeFinalNextKeepsQueueAndPlaybackOwnerAndSettingsRestorePaused() {
        context()
        val player = app.nativePlayback
        player.play(
            listOf(
                JSONObject()
                    .put("id", 7)
                    .put("db_id", 7)
                    .put("title", "Last recording")
                    .put("duration", 90)
            ),
            autoplay = false,
        )
        assertTrue(player.active)
        assertEquals(0, player.index)
        player.command("nexttrack")
        assertTrue(player.active)
        assertEquals(0, player.index)
        assertEquals("7", NativeTrack.reference(player.current!!))
        player.command("shuffle")
        player.command("repeat")
        player.setVolume(.35f)
        player.setVolume(Float.NaN)
        player.persist()
        val saved = app.nativeLibrary.get("snapshot", "queue")!!
        assertTrue(saved.getBoolean("shuffle"))
        assertEquals(2, saved.getInt("repeat"))
        assertEquals(.35, saved.getDouble("volume"), .001)
        player.stop()
        player.restore()
        assertFalse(player.active)
        assertFalse(player.playing)
        assertEquals("7", NativeTrack.reference(player.current!!))
        assertEquals(.35f, player.volume, .001f)
        assertFalse(app.nativeApi.canImportLegacy)
        app.nativeLibrary.put("migration", "legacy-owner", JSONObject().put("verified", true))
        assertTrue(app.nativeApi.canImportLegacy)
        context("bob", "b".repeat(64))
        assertFalse(app.nativeApi.canImportLegacy)
    }

    @Test
    fun partyTransportCannotShuffleLocalQueueAndRemoteTracksCannotToggleLocalFavorites() {
        context()
        val player = app.nativePlayback
        player.play(listOf(JSONObject().put("id", 7).put("db_id", 7)), autoplay = false)
        val room = NativeParty(app, "1")
        player.party = room
        try {
            player.command("shuffle")
            player.command("repeat")
            val engine =
                NativePlayback::class
                    .java
                    .getDeclaredField("engine")
                    .apply { isAccessible = true }
                    .get(player) as androidx.media3.exoplayer.ExoPlayer
            assertFalse(engine.shuffleModeEnabled)
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, engine.repeatMode)
        } finally {
            room.close()
        }
        for (track in
            listOf(
                JSONObject().put("id", "radio-1"),
                JSONObject().put("id", "fed_2_9").put("fed_instance_id", 2).put("fed_remote_id", 9),
            )) {
            var error: Throwable? = null
            player.favorite(track) { error = it }
            assertTrue(error is IllegalArgumentException)
            assertFalse(track.optBoolean("is_favorite"))
        }
    }

    @Test
    fun wrappedTransportCannotReplaceThePersonalQueueOrAutomaticallyResumeIt() {
        context()
        val player = app.nativePlayback
        player.play(
            listOf(JSONObject().put("id", 7).put("title", "Personal recording")),
            autoplay = false,
        )
        val commands = mutableListOf<String>()
        val story: (String, Long) -> Unit = { action, _ -> commands.add(action) }
        assertTrue(player.present(story))
        assertFalse(player.present { _, _ -> fail("Another presentation must not steal controls") })
        player.command("play")
        player.command("nexttrack")
        assertEquals(listOf("play", "nexttrack"), commands)
        assertFalse(player.playing)
        assertEquals("7", NativeTrack.reference(player.current!!))
        player.endPresentation { _, _ -> }
        player.command("pause")
        assertEquals("pause", commands.last())
        player.endPresentation(story)
        val count = commands.size
        player.command("pause")
        assertEquals(count, commands.size)
        assertFalse(player.playing)
        assertEquals(1, player.queue.size)
    }

    @Test
    fun manualQueueEditsPreserveTheCurrentEngineAndContext() {
        context()
        fun song(id: Int) = JSONObject().put("id", id).put("db_id", id).put("duration", 90)
        val player = app.nativePlayback
        player.play(listOf(song(7), song(8)), autoplay = false)
        val field =
            NativePlayback::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val engine = field.get(player)
        val revision = player.queueRevision
        player.enqueue(song(9))
        player.enqueue(song(10))
        assertTrue(player.queueRevision > revision)
        assertEquals(listOf("7", "9", "10", "8"), player.queue.map(NativeTrack::reference))
        assertTrue(player.moveQueued(2, 1))
        assertEquals(listOf("7", "10", "9", "8"), player.queue.map(NativeTrack::reference))
        assertFalse(player.removeQueued(0))
        assertFalse(player.removeQueued(3))
        assertTrue(player.removeQueued(1))
        assertEquals(listOf("7", "9", "8"), player.queue.map(NativeTrack::reference))
        val remote = player.remoteQueueState()
        assertEquals(
            listOf("7", "8"),
            NativeTrack.items(remote.getJSONArray("context_queue")).map(NativeTrack::reference),
        )
        assertEquals(
            listOf("9"),
            NativeTrack.items(remote.getJSONArray("manual_queue")).map(NativeTrack::reference),
        )
        assertFalse(player.selectQueue(5))
        assertSame(engine, field.get(player))
        assertFalse(player.playing)
        assertTrue(player.selectQueue(0))
        assertSame("Queue selection must seek on the same player", engine, field.get(player))
        assertEquals("7", NativeTrack.reference(player.current!!))
        player.stop()
        player.restoreRemoteQueue(remote)
        assertEquals(listOf("7", "9", "8"), player.queue.map(NativeTrack::reference))
        assertTrue(player.queue[1].getBoolean("_manual"))
        assertFalse(player.active)
    }

    @Test
    fun backgroundResumeWaitsForForegroundServiceBeforeInvokingNativePlayer() {
        context()
        app.nativePlayback.play(
            listOf(JSONObject().put("id", 7).put("db_id", 7).put("title", "Test recording")),
            autoplay = false,
        )
        val calls = mutableListOf<String>()
        app.mediaController.setActionInvoker { action, _ -> calls.add(action) }
        app.mediaController.command("play")
        assertTrue(calls.isEmpty())
        var started = org.robolectric.Shadows.shadowOf(app).nextStartedService
        while (started != null && started.action != PlaybackService.NATIVE_PLAY) started =
            org.robolectric.Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            assertNotNull(
                org.robolectric.Shadows.shadowOf(service.get()).lastForegroundNotification
            )
            service.get().onStartCommand(started, 0, 1)
            assertEquals(listOf("play"), calls)
        } finally {
            service.destroy()
        }
    }

    @Test
    fun switchingAccountCannotMovePersonalOrPartyQueueIntoTheNewAccount() {
        context()
        val alice = app.nativeLibrary.scope()
        val player = app.nativePlayback
        player.play(
            listOf(JSONObject().put("id", 7).put("db_id", 7).put("title", "Alice's queue")),
            autoplay = false,
        )
        val room = NativeParty(app, "1")
        player.party = room
        context("bob", "b".repeat(64))
        val bob = app.nativeLibrary.scope()
        player.release()
        player.restore()
        assertNull(player.current)
        assertNull(app.nativeLibrary.get("snapshot", "queue", bob))
        assertEquals(
            "Alice's queue",
            NativeTrack.items(app.nativeLibrary.get("snapshot", "queue", alice))
                .single()
                .getString("title"),
        )
        assertFalse(player.active)
    }

    @Test
    fun queuedNativeRequestCannotPickUpAnotherAccountsCredentials() {
        context()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        app.nativeApi.execute({
            entered.countDown()
            check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            app.nativeApi.connection("/api/playlists", "POST")
        }) { _, error ->
            failure = error
            done.countDown()
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        context("bob", "b".repeat(64))
        proceed.countDown()
        repeat(100) {
            if (done.count > 0) {
                Thread.sleep(10)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
        }
        assertEquals(0L, done.count)
        assertTrue(failure is NativeApi.Failure)
        assertEquals(409, (failure as NativeApi.Failure).code)
        context()
        val source = NativeHttpSource(app.nativeApi)
        context("bob", "b".repeat(64))
        assertTrue(
            runCatching {
                    source.open(DataSpec.Builder().setUri("https://music.test:8443/audio").build())
                }
                .isFailure
        )
    }

    @Test
    fun mediaSourceHonorsRangesAndRefusesRedirectsAndHtmlWithoutLeakingCookies() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val bytes = ByteArray(256) { it.toByte() }
        var origin = ""
        val cookies = java.util.concurrent.atomic.AtomicReference("")
        val worker =
            Thread {
                    while (!server.isClosed) try {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val path = reader.readLine().split(' ')[1]
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] =
                                    line.substringAfter(':').trim()
                            }
                            origin =
                                headers["origin"] ?: headers["referer"].orEmpty().removeSuffix("/")
                            cookies.set(headers["cookie"].orEmpty())
                            val range = headers["range"]
                            val start =
                                range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                            val body =
                                if (path == "/html") "<html>Sign in</html>".toByteArray()
                                else bytes.copyOfRange(start, bytes.size)
                            val status =
                                if (path == "/redirect") 302 else if (range == null) 200 else 206
                            val extra =
                                if (status == 302) "Location: https://untrusted.test/audio\r\n"
                                else ""
                            val head =
                                "HTTP/1.1 $status OK\r\nContent-Type: ${if(path=="/html")"text/html"else"audio/wav"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n"
                            socket.getOutputStream().apply {
                                write(head.toByteArray())
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
            val base = "http://127.0.0.1:${server.localPort}"
            app.nativeApi.configure(base)
            context()
            val source = NativeHttpSource(app.nativeApi)
            assertEquals(
                246L,
                source.open(DataSpec.Builder().setUri("$base/audio").setPosition(10).build()),
            )
            val received = ByteArray(246)
            assertEquals(246, source.read(received, 0, received.size))
            assertArrayEquals(bytes.copyOfRange(10, 256), received)
            source.close()
            assertEquals(base, origin)
            for (path in listOf("redirect", "html")) assertTrue(
                runCatching { source.open(DataSpec.Builder().setUri("$base/$path").build()) }
                    .isFailure
            )
            source.close()
            app.mediaController.serverOrigin = ServerOrigin.from(Uri.parse(base))
            fun cover(path: String) {
                app.mediaController.receive(
                    JSONObject()
                        .put("type", "metadata")
                        .put("title", "Session check")
                        .put("artwork", base + path)
                        .toString()
                )
                org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.Future<*>>(
                        app.mediaController,
                        "artworkJob",
                    )
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            cover("/native-cover")
            assertTrue(cookies.get().contains("synthetic-session"))
            android.webkit.CookieManager.getInstance()
                .setCookie(base, "access_token=legacy-owner; Path=/")
            AoedeApp::class
                .java
                .getDeclaredField("player")
                .apply { isAccessible = true }
                .set(app, WebView(app))
            cover("/legacy-cover")
            assertTrue(cookies.get().contains("legacy-owner"))
            assertFalse(cookies.get().contains("synthetic-session"))
            app.nativeApi.changeClientMode(base, true)
            assertNull(app.nativeApi.origin)
            java.io.File(app.applicationInfo.dataDir, "app_webview").mkdirs()
            app.nativeApi.configure(base)
            assertTrue(
                app.nativeApi
                    .cookieHeader(ServerOrigin.from(Uri.parse(base))!!)
                    .orEmpty()
                    .contains("legacy-owner")
            )
            app.nativeApi.changeClientMode(base, false)
            assertFalse(
                android.webkit.CookieManager.getInstance()
                    .getCookie(base)
                    .orEmpty()
                    .contains("legacy-owner")
            )
        } finally {
            server.close()
            worker.join(1000)
        }
    }
}
