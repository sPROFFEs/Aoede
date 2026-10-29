package com.aoede.twa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import java.io.IOException

/** Decoded, account-bound covers shared by lists, heroes and the player. */
class NativeArtwork(private val api: NativeApi) {
    private val cache =
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

    private class Request(val current: () -> Boolean, val done: (Bitmap?) -> Unit)

    private val pending = mutableMapOf<String, java.util.concurrent.CopyOnWriteArrayList<Request>>()
    @Volatile private var epoch = 0

    private fun size(pixels: Int) =
        when {
            pixels <= 128 -> 128
            pixels <= 256 -> 256
            else -> 512
        }

    private fun key(path: String, pixels: Int) =
        "${api.origin?.rule}:${api.scope}:${size(pixels)}:$path"

    fun peek(path: String, pixels: Int): Bitmap? = cache.get(key(path, pixels))

    fun clear() {
        epoch++
        cache.evictAll()
        pending.clear()
    }

    fun load(
        path: String,
        pixels: Int,
        current: () -> Boolean = { true },
        done: (Bitmap?) -> Unit,
    ) {
        if (!current()) return
        if (
            path.isBlank() || path.length > if (path.startsWith("data:image/")) 192 * 1024 else 4096
        ) {
            done(null)
            return
        }
        val size = size(pixels)
        val key = key(path, pixels)
        cache.get(key)?.let {
            done(it)
            return
        }
        pending[key]
            ?.takeIf { it.any { request -> request.current() } }
            ?.let {
                it.add(Request(current, done))
                return
            }
        val waiting = java.util.concurrent.CopyOnWriteArrayList<Request>()
        waiting.add(Request(current, done))
        pending[key] = waiting
        val ticket = epoch
        api.image(
            {
                if (ticket != epoch || waiting.none { it.current() }) return@image null
                val bytes =
                    if (path.startsWith("data:image/")) {
                        Base64.decode(path.substringAfter(','), Base64.DEFAULT)
                    } else read(path)
                if (ticket == epoch && waiting.any { it.current() }) decode(bytes, size) else null
            },
            current = { ticket == epoch && waiting.any { it.current() } },
        ) { value, _ ->
            if (ticket != epoch || pending[key] !== waiting) return@image
            val bitmap = value as? Bitmap
            if (bitmap != null) cache.put(key, bitmap)
            pending.remove(key)
            waiting.filter { it.current() }.forEach { it.done(bitmap) }
        }
    }

    private fun read(path: String): ByteArray {
        val origin = api.origin ?: throw IOException("Configure your server first")
        var target =
            if (
                path.startsWith("https://") &&
                    runCatching { NativeApi.resolve(origin, path) }.isFailure
            ) {
                "/api/proxy-image?url=" + NativeApi.encode(path)
            } else path
        repeat(4) {
            val connection = api.connection(target)
            try {
                connection.setRequestProperty("Accept", "image/*")
                val status = connection.responseCode
                api.checkResponse(connection)
                if (status in setOf(301, 302, 303, 307, 308)) {
                    // Covers and avatars legitimately redirect to local fallback images.
                    // Resolve relative to this response and never forward a session off-origin.
                    val next =
                        java.net.URL(connection.url, connection.getHeaderField("Location") ?: "")
                    target = NativeApi.resolve(origin, next.toString()).toString()
                } else {
                    if (status != 200) throw IOException("Artwork unavailable (HTTP $status)")
                    val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
                    if (
                        mime.isNotBlank() &&
                            !mime.startsWith("image/") &&
                            mime != "application/octet-stream"
                    )
                        throw IOException("Artwork response is not an image")
                    val bytes = connection.inputStream.use { it.readBytesBounded(2 * 1024 * 1024) }
                    api.checkResponse(connection)
                    return bytes
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many artwork redirects")
    }

    companion object {
        fun decode(bytes: ByteArray, pixels: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample =
                MediaController.artworkSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= pixels) sample *= 2
            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }
}
