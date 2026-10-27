package com.aoede.twa

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account/node-scoped device library. Files are committed only after a complete, verified write.
 */
class NativeLibrary(private val app: AoedeApp) : SQLiteOpenHelper(app, "aoede-native.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records(scope TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(scope,kind,id))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported native library schema")
    }

    fun scope(): String {
        val api = app.nativeApi
        require(api.scope.isNotBlank() && api.origin != null) {
            "Sign in before using device downloads"
        }
        return NativeApi.digest(api.origin!!.rule + ":" + api.scope)
    }

    fun directory(scope: String = scope()): File {
        require(Regex("[a-f0-9]{64}").matches(scope))
        return File(app.filesDir, "native-library/$scope").apply { mkdirs() }
    }

    @Synchronized
    fun put(kind: String, id: String, data: JSONObject, scope: String = scope()) {
        require(
            kind in setOf("track", "collection", "snapshot", "pending", "migration", "download")
        )
        require(id.length in 1..256 && data.toString().length <= 4 * 1024 * 1024)
        val result =
            writableDatabase.insertWithOnConflict(
                "records",
                null,
                ContentValues().apply {
                    put("scope", scope)
                    put("kind", kind)
                    put("id", id)
                    put("data", data.toString())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        check(result >= 0) { "Could not save device library" }
    }

    fun get(kind: String, id: String, scope: String = scope()): JSONObject? =
        readableDatabase
            .query(
                "records",
                arrayOf("data"),
                "scope=? AND kind=? AND id=?",
                arrayOf(scope, kind, id),
                null,
                null,
                null,
            )
            .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    fun list(kind: String, scope: String = scope()): List<JSONObject> =
        readableDatabase
            .query(
                "records",
                arrayOf("id", "data"),
                "scope=? AND kind=?",
                arrayOf(scope, kind),
                null,
                null,
                "rowid DESC",
            )
            .use {
                buildList {
                    while (it.moveToNext()) add(
                        JSONObject(it.getString(1)).put("_native_record_id", it.getString(0))
                    )
                }
            }

    @Synchronized
    fun remove(kind: String, id: String, scope: String = scope()) {
        if (kind == "track") File(directory(scope), NativeApi.digest(id) + ".audio").delete()
        writableDatabase.delete("records", "scope=? AND kind=? AND id=?", arrayOf(scope, kind, id))
    }

    @Synchronized
    fun audio(id: String): File? =
        get("track", id)?.let { record ->
            val target = File(directory(), NativeApi.digest(id) + ".audio")
            val previous = File(directory(), NativeApi.digest(id) + ".previous")
            if (previous.isFile) {
                fun matches(file: File): Boolean {
                    if (!file.isFile || file.length() != record.optLong("offline_size"))
                        return false
                    val hash = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            hash.update(buffer, 0, n)
                        }
                    }
                    return hash.digest().joinToString("") { "%02x".format(it) } ==
                        record.optString("offline_sha256")
                }
                if (matches(target)) previous.delete()
                else if (matches(previous)) {
                    target.delete()
                    check(previous.renameTo(target))
                } else return@let null
            }
            target.takeIf { it.isFile && it.length() == record.optLong("offline_size") }
        }

    fun download(track: JSONObject, scope: String = scope()) {
        val id = NativeTrack.reference(track)
        require(NativeTrack.localId(track) > 0) { "Only local tracks support device downloads" }
        require(scope == scope()) { "Account changed" }
        val c = app.nativeApi.connection(NativeTrack.stream(track))
        val temporary = File(directory(scope), NativeApi.digest(id) + ".partial")
        try {
            val status = c.responseCode
            app.nativeApi.checkResponse(c)
            require(status == 200) { "Download failed (HTTP $status)" }
            val length = (c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L)
            // ponytail: 2GiB per track; use resumable segmented downloads if larger media becomes a
            // requirement.
            require(
                length <= 2L * 1024 * 1024 * 1024 &&
                    (length < 0 || directory(scope).usableSpace > length + 32L * 1024 * 1024)
            ) {
                "Insufficient device storage"
            }
            val mime = c.contentType.orEmpty().substringBefore(';')
            require(
                mime.startsWith("audio/") ||
                    mime in setOf("application/octet-stream", "video/mp4", "video/webm")
            ) {
                "Unsupported media response"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            c.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted || scope != scope())
                            error("Download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 2L * 1024 * 1024 * 1024)
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            require(size > 0 && (length < 0 || size == length)) { "Incomplete download" }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            val record =
                JSONObject(track.toString())
                    .put("offline_size", size)
                    .put("offline_sha256", checksum)
            require(scope == scope()) { "Account changed" }
            runCatching { cacheArtwork(track) }
                .getOrNull()
                ?.let { record.put("offline_artwork", it) }
            commitAudio(id, record, temporary, scope)
        } finally {
            c.disconnect()
            temporary.delete()
        }
    }

    private fun cacheArtwork(track: JSONObject): String? {
        val raw = track.optString("thumbnail")
        if (raw.startsWith("data:image/") && raw.length < 128 * 1024) return raw
        val path =
            if (raw.startsWith("/") && !raw.startsWith("//")) raw
            else if (raw.startsWith("https://")) "/api/proxy-image?url=" + NativeApi.encode(raw)
            else return null
        val c = app.nativeApi.connection(path)
        try {
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytesBounded(2 * 1024 * 1024) }
            app.nativeApi.checkResponse(c)
            val bounds =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample =
                MediaController.artworkSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
            val bitmap =
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return null
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, output)
            bitmap.recycle()
            if (output.size() > 96 * 1024) return null
            return "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(
                    output.toByteArray(),
                    android.util.Base64.NO_WRAP,
                )
        } finally {
            c.disconnect()
        }
    }

    @Synchronized
    fun commitAudio(id: String, data: JSONObject, temporary: File, scope: String = scope()) {
        require(temporary.canonicalFile.parentFile == directory(scope).canonicalFile)
        val checksum = MessageDigest.getInstance("SHA-256")
        temporary.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                checksum.update(buffer, 0, n)
            }
        }
        require(
            temporary.length() == data.getLong("offline_size") &&
                checksum.digest().joinToString("") { "%02x".format(it) } ==
                    data.getString("offline_sha256")
        ) {
            "Download checksum mismatch"
        }
        val target = File(directory(scope), NativeApi.digest(id) + ".audio")
        val previous = File(directory(scope), NativeApi.digest(id) + ".previous")
        if (target.exists()) check(target.renameTo(previous))
        try {
            check(temporary.renameTo(target))
            put("track", id, data, scope)
            previous.delete()
        } catch (error: Exception) {
            target.delete()
            if (previous.exists()) check(previous.renameTo(target))
            throw error
        }
    }
}

object NativeTrack {
    fun portable(t: JSONObject): JSONObject =
        JSONObject(t.toString()).apply {
            remove("offline_artwork")
            remove("offline_sha256")
            remove("offline_size")
            remove("_native_record_id")
        }

    fun localId(t: JSONObject): Long =
        if (t.optInt("fed_instance_id") > 0 || t.optBoolean("is_federated")) 0
        else
            t.optLong("db_id").takeIf { it > 0 }
                ?: if (t.optBoolean("is_local") || t.optString("source") == "local")
                    t.optLong("id").coerceAtLeast(0)
                else 0

    fun reference(t: JSONObject): String {
        val instance = t.optLong("fed_instance_id")
        val remote = t.optLong("fed_remote_id", t.optLong("remote_id"))
        return if (instance > 0 && remote > 0) "fed_${instance}_$remote"
        else localId(t).takeIf { it > 0 }?.toString() ?: t.optString("id").take(256)
    }

    fun collectionPath(id: String): String {
        val remote = Regex("fed_([1-9][0-9]*)_([1-9][0-9]*)").matchEntire(id)
        if (remote != null)
            return "/api/federation/proxy/${remote.groupValues[1]}/collections/${remote.groupValues[2]}"
        require(Regex("[1-9][0-9]*").matches(id))
        return "/api/playlists/$id"
    }

    fun stream(t: JSONObject): String {
        val explicit = t.optString("stream_url")
        if (explicit.startsWith("/api/") && !explicit.startsWith("//")) return explicit
        val id = reference(t)
        val remote = Regex("fed_([1-9][0-9]*)_([1-9][0-9]*)").matchEntire(id)
        if (remote != null)
            return "/api/federation/proxy/${remote.groupValues[1]}/stream/${remote.groupValues[2]}"
        if (localId(t) > 0) return "/api/stream/${localId(t)}"
        return "/api/playback/preview?relay=true&url=${NativeApi.encode(t.optString("url", t.optString("id")))}&title=${NativeApi.encode(t.optString("artist")+" "+t.optString("title"))}"
    }

    fun items(value: Any?): List<JSONObject> {
        val array =
            when (value) {
                is JSONArray -> value
                is JSONObject ->
                    value.optJSONArray("items") ?: value.optJSONArray("tracks") ?: JSONArray()
                else -> JSONArray()
            }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}
