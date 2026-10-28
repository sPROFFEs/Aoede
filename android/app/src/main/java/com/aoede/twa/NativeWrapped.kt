package com.aoede.twa

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** The summary in views.js, using the same private Wrapped responses and collection operation. */
class NativeWrapped(private val ui: NativeUi) {
    companion object {
        fun tracks(data: JSONObject) =
            NativeTrack.items(data.optJSONObject("top_songs_playlist")?.optJSONArray("tracks"))
                .map {
                    JSONObject(it.toString()).apply {
                        put("id", it.optLong("db_id").takeIf { value -> value > 0 } ?: it.opt("id"))
                        put("is_local", true)
                        put("source", "local")
                    }
                }

        fun duration(minutes: Double): String {
            if (!minutes.isFinite() || minutes <= 0) return "0 seconds"
            val (amount, unit) =
                when {
                    minutes < 1 -> minutes * 60 to "second"
                    minutes < 60 -> minutes to "minute"
                    minutes < 1440 -> minutes / 60 to "hour"
                    minutes < 525600 -> minutes / 1440 to "day"
                    else -> minutes / 525600 to "year"
                }
            val digits = if (unit in listOf("second", "minute") || amount >= 10) 0 else 1
            val factor = if (digits == 0) 1.0 else 10.0
            val rounded = kotlin.math.floor(amount * factor + .5) / factor
            val value =
                java.text.NumberFormat.getNumberInstance()
                    .apply { maximumFractionDigits = digits }
                    .format(rounded)
            return "$value $unit" + if (rounded == 1.0) "" else "s"
        }
    }

    fun open(
        year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
        story: Boolean = false,
        progress: android.os.Bundle? = null,
    ) {
        val path = "/api/wrapped/$year"
        ui.load(
            "Aoede Wrapped",
            path,
            read = {
                val data = ui.api.request(path) as JSONObject
                val resolved = data.optInt("year", year)
                data.put(
                    "_party",
                    runCatching { ui.api.request("/api/wrapped/$resolved/party") as JSONObject }
                        .getOrNull(),
                )
            },
            restore = { open(year) },
        ) { value ->
            val data = value as JSONObject
            if (!data.optBoolean("visible", true) || !data.optBoolean("enabled", true)) {
                ui.empty("Wrapped is not available right now.", "sparkles")
                return@load
            }
            val songs = tracks(data)
            val artists = NativeTrack.items(data.optJSONArray("top_artists"))
            val listened = duration(data.optDouble("minutes_listened", 0.0))
            val summary = panel(18, summary = true)
            copy("Aoede Wrapped ${data.optInt("year", year)}", 22.4f, summary, 700)
            copy(
                "$listened listened · ${data.optInt("event_count")} tracked listens",
                16f,
                summary,
                color = ui.muted,
                top = 6,
            )
            val actions = NativeFlowLayout(ui.activity, ui.dp(10))
            summary.addView(
                actions,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) },
            )
            val temporary = LinearLayout(ui.activity)
            fun action(
                label: String,
                glyph: String,
                primary: Boolean,
                click: (Button) -> Unit,
            ): Button {
                val button = ui.button(label, temporary, primary) {}
                temporary.removeView(button)
                actions.addView(button, android.view.ViewGroup.LayoutParams(-2, ui.dp(44)))
                button.setCompoundDrawablesRelative(
                    NativeIcon(glyph, if (primary) ui.design.onAccent else ui.ink, ui.design.theme)
                        .apply { setBounds(0, 0, ui.dp(18), ui.dp(18)) },
                    null,
                    null,
                    null,
                )
                button.compoundDrawablePadding = ui.dp(8)
                button.setOnClickListener { click(button) }
                return button
            }
            action("Watch Story", "sparkles", false) { ui.showWrapped(data) }
            action("Save Top Songs", "save", true) { save(data, it) }.isEnabled = songs.isNotEmpty()
            for ((caption, text) in
                listOf(
                    "Listened" to listened,
                    "Top artist" to (artists.firstOrNull()?.optString("artist") ?: "No data"),
                    "Top song" to (songs.firstOrNull()?.optString("title") ?: "No data"),
                )) {
                val stat = panel(14, gap = 12)
                copy(caption, 13.76f, stat, color = ui.muted)
                copy(text, 16.8f, stat, 650, top = 6).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            val top = panel()
            copy("Top Songs", 16f, top, 650)
            ui.space(14, top)
            if (songs.isEmpty()) copy("No listening data yet.", 16f, top, color = ui.muted)
            else ui.tracks(songs.take(20), parent = top)
            val artistPanel = panel()
            copy("Top Artists", 16f, artistPanel, 650)
            ui.space(14, artistPanel)
            if (artists.isEmpty()) copy("No artist data yet.", 16f, artistPanel, color = ui.muted)
            for (artist in artists) rank(
                artist.optString("artist"),
                "${artist.optInt("stream_count")} plays",
                artistPanel,
            )
            val message = data.optJSONObject("artist_clip")
            ui.space(18, artistPanel)
            divider(artistPanel)
            ui.space(16, artistPanel)
            copy(message?.optString("title") ?: "A message from Aoede", 16f, artistPanel, 650)
            copy(
                message?.optString("message").orEmpty(),
                16f,
                artistPanel,
                color = ui.muted,
                top = 6,
            )
            val sprint = panel()
            copy("Top Artist Sprint", 16f, sprint, 650)
            ui.space(14, sprint)
            val months = java.text.DateFormatSymbols(java.util.Locale.ENGLISH).shortMonths
            val movement =
                NativeTrack.items(data.optJSONArray("top_artist_sprint")).filter {
                    (it.optJSONArray("artists")?.length() ?: 0) > 0
                }
            if (movement.isEmpty())
                copy("No monthly artist movement yet.", 16f, sprint, color = ui.muted)
            for (month in movement) {
                val leader = month.getJSONArray("artists").getJSONObject(0)
                rank(
                    "${months.getOrNull(month.optInt("month", 1)-1).orEmpty()}   ${leader.optString("artist")}",
                    "${leader.optInt("stream_count")} plays",
                    sprint,
                )
            }
            val party = panel()
            copy("Wrapped Party", 16f, party, 650)
            val rankings = data.optJSONObject("_party")
            if (
                rankings == null ||
                    !rankings.optBoolean("visible", true) ||
                    !rankings.optBoolean("enabled", true)
            )
                copy(
                    "Party rankings are not available yet.",
                    16f,
                    party,
                    color = ui.muted,
                    top = 14,
                )
            else
                for ((field, title) in
                    listOf(
                        "most_minutes_listened" to "Most Listening Time",
                        "biggest_repeaters" to "Biggest Repeaters",
                    )) {
                    copy(title, 16f, party, 650, top = 16)
                    ui.space(14, party)
                    val entries = NativeTrack.items(rankings.optJSONArray(field)).take(5)
                    if (entries.isEmpty())
                        copy(
                            if (field == "most_minutes_listened") "No user ranking yet."
                            else "No repeat ranking yet.",
                            16f,
                            party,
                            color = ui.muted,
                        )
                    for ((i, entry) in entries.withIndex()) rank(
                        "#${entry.optInt("rank", i+1)} ${entry.optString("username")}",
                        if (field == "most_minutes_listened")
                            duration(entry.optDouble("minutes_listened", 0.0))
                        else "${entry.optInt("stream_count")} plays",
                        party,
                    )
                }
            if (!ui.reducedMotion()) {
                ui.content.alpha = 0f
                ui.content.translationY = ui.dp(10).toFloat()
                ui.content.animate().alpha(1f).translationY(0f).setDuration(260).start()
            }
            if (story) ui.showWrapped(data, progress)
        }
    }

    fun save(data: JSONObject, button: Button) {
        if (!button.isEnabled || tracks(data).isEmpty()) return
        button.isEnabled = false
        val owner = button.rootView
        ui.api.execute({
            ui.api.request("/api/wrapped/${data.getInt("year")}/top-songs/playlist", "POST")
        }) { value, error ->
            if (!button.isAttachedToWindow || button.rootView !== owner) return@execute
            button.isEnabled = true
            if (error != null) ui.toast(error.message ?: "Could not save Wrapped playlist")
            else {
                ui.hidePlayer()
                ui.toast("Wrapped playlist saved")
                (value as? JSONObject)?.opt("id")?.toString()?.let(ui::collection)
            }
        }
    }

    private fun panel(padding: Int = 16, summary: Boolean = false, gap: Int = 18) =
        LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(padding), ui.dp(padding), ui.dp(padding), ui.dp(padding))
            background =
                ui.design.shape(
                    if (ui.design.theme == "classic") {
                        if (summary) ui.surface else ui.design.strong
                    } else ui.design.content,
                    if (ui.design.theme == "classic") 6 else 24,
                    ui.design.theme != "classic",
                )
            ui.content.addView(
                this,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(gap) },
            )
        }

    private fun copy(
        value: String,
        size: Float,
        parent: LinearLayout,
        weight: Int = 400,
        color: Int = ui.ink,
        top: Int = 0,
    ) =
        TextView(ui.activity).apply {
            text = value
            ui.design.typography(this, size, weight)
            ui.design.lines(this, 1.45f)
            setTextColor(color)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(top) })
        }

    private fun divider(parent: LinearLayout) {
        parent.addView(
            View(ui.activity).apply { setBackgroundColor(ui.design.border) },
            LinearLayout.LayoutParams(-1, ui.dp(1)),
        )
    }

    private fun rank(title: String, detail: String, parent: LinearLayout) {
        val row =
            LinearLayout(ui.activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, ui.dp(10))
            }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        copy(title, 16f, row).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        copy(detail, 14.08f, row, 500, ui.muted).layoutParams =
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) }
        divider(parent)
    }
}
