package com.aoede.twa

import java.util.UUID
import org.json.JSONObject

/** Replays only known operations in the authenticated account's private namespace. */
class NativeSync(private val app: AoedeApp) {
    @Volatile private var running = false

    fun flush(only: String? = null, done: (String?) -> Unit = {}) {
        if (running) {
            done("Synchronization already running")
            return
        }
        val scope = app.nativeLibrary.scope()
        val origin = app.nativeApi.origin
        running = true
        app.nativeApi.execute({
            val actions = app.nativeLibrary.list("pending", scope).reversed()
            for (action in actions) {
                if (app.nativeLibrary.scope() != scope || app.nativeApi.origin != origin)
                    error("Account changed")
                val key = action.getString("_native_record_id")
                if (only != null && key != only) continue
                if (only == null && action.optBoolean("review_required")) continue
                if (
                    action.has("username") && action.optString("username") != app.nativeApi.username
                )
                    continue
                val payload = action.optJSONObject("payload") ?: JSONObject()
                fun collection(): String {
                    var id = payload.optString("playlistId")
                    val map =
                        app.nativeLibrary.get("snapshot", "collection-map", scope) ?: JSONObject()
                    if (map.has(id)) id = map.getString(id)
                    return NativeTrack.collectionPath(id)
                }
                when (action.optString("type")) {
                    "listen_progress",
                    "track_completed" ->
                        app.nativeApi.request("/api/activity/listen", "POST", payload)
                    "toggle_favorite" ->
                        app.nativeApi.request(
                            "/api/favorites/${NativeApi.encode(payload.get("trackId").toString())}",
                            if (payload.optBoolean("liked")) "POST" else "DELETE",
                        )
                    "create_playlist" -> {
                        val body =
                            JSONObject(payload.toString())
                                .put(
                                    "request_id",
                                    UUID.nameUUIDFromBytes((scope + ":" + key).toByteArray())
                                        .toString(),
                                )
                        val result =
                            app.nativeApi.request("/api/playlists", "POST", body) as JSONObject
                        val temporary = payload.optString("temp_id")
                        if (temporary.isNotBlank()) {
                            val id = result.get("id").toString()
                            val map =
                                app.nativeLibrary.get("snapshot", "collection-map", scope)
                                    ?: JSONObject()
                            map.put(temporary, id)
                            app.nativeLibrary.put("snapshot", "collection-map", map, scope)
                            app.nativeLibrary.get("collection", temporary, scope)?.let {
                                app.nativeLibrary.put("collection", id, it.put("id", id), scope)
                                app.nativeLibrary.remove("collection", temporary, scope)
                            }
                        }
                    }
                    "add_to_playlist" ->
                        app.nativeApi.request(
                            collection() + "/add",
                            "POST",
                            JSONObject().put("track_id", payload.get("trackId")),
                        )
                    "remove_from_playlist" ->
                        app.nativeApi.request(
                            collection() +
                                "/remove/" +
                                NativeApi.encode(payload.get("trackId").toString()) +
                                if (payload.has("itemId")) "?item_id=" + payload.optLong("itemId")
                                else "",
                            "DELETE",
                        )
                    "edit_playlist" ->
                        app.nativeApi.request(
                            collection(),
                            "PUT",
                            JSONObject().put("name", payload.getString("name")),
                        )
                    "delete_playlist" -> app.nativeApi.request(collection(), "DELETE")
                    else -> error("Unknown legacy change retained for review")
                }
                app.nativeLibrary.remove("pending", key, scope)
            }
            null
        }) { _, error ->
            running = false
            done(error?.message)
        }
    }

    fun enqueue(type: String, payload: JSONObject) {
        require(
            type in
                setOf(
                    "toggle_favorite",
                    "create_playlist",
                    "add_to_playlist",
                    "remove_from_playlist",
                    "edit_playlist",
                    "delete_playlist",
                    "listen_progress",
                )
        )
        val key = UUID.randomUUID().toString()
        app.nativeLibrary.put(
            "pending",
            key,
            JSONObject()
                .put("type", type)
                .put("username", app.nativeApi.username)
                .put("payload", payload)
                .put("created_at", System.currentTimeMillis()),
        )
    }
}
