package com.aoede.twa

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val app get() = application as AoedeApp

    companion object {
        const val PREFS = "aoede_prefs"
        const val KEY_URL = "server_url"
        fun savedUrl(ctx: Context): String? =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, null)?.trimEnd('/')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val url = savedUrl(this)
        if (url.isNullOrBlank()) {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FIRST_RUN, true))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        val view = app.attachPlayer(this, url)
        findViewById<FrameLayout>(R.id.webview_container).addView(view)
        updatePage(view.url ?: url)
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FIRST_RUN, false))
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.canGoBack()) view.goBack() else moveTaskToBack(true)
            }
        })
    }

    fun updatePage(url: String) {
        val path = android.net.Uri.parse(url).path.orEmpty()
        findViewById<ImageButton>(R.id.btn_settings)?.visibility =
            if (path.startsWith("/login")) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        app.detachPlayer(this)
        super.onDestroy()
    }
}
