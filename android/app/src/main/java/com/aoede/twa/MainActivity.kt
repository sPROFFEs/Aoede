package com.aoede.twa

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val app get() = application as AoedeApp
    private var failedUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val selected = if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else data.data?.let { arrayOf(it) }
        } else null
        val uris = selected?.filter { it.scheme == "content" }?.toTypedArray()
        fileCallback?.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
        fileCallback = null
    }

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
        app.connectionFailure?.let { (failed, detail) -> showConnectionError(failed, detail) }
        findViewById<View>(R.id.btn_retry).setOnClickListener {
            failedUrl?.let { view.loadUrl(it) }
        }
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
            if (path.startsWith("/login") || failedUrl != null) View.VISIBLE else View.GONE
    }

    fun clearConnectionError() {
        failedUrl = null
        findViewById<View>(R.id.connection_error)?.visibility = View.GONE
    }

    fun showConnectionError(url: String, detail: String) {
        if (ServerOrigin.from(Uri.parse(url)) != app.mediaController.serverOrigin) return
        failedUrl = url
        findViewById<TextView>(R.id.connection_error_detail).text = detail
        findViewById<View>(R.id.connection_error).visibility = View.VISIBLE
        updatePage(url)
    }

    fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        if (ServerOrigin.from(Uri.parse(app.player?.url.orEmpty())) != app.mediaController.serverOrigin) {
            callback.onReceiveValue(null)
            fileCallback = null
            return true
        }
        try {
            fileChooser.launch(params.createIntent())
        } catch (_: android.content.ActivityNotFoundException) {
            callback.onReceiveValue(null)
            fileCallback = null
            Toast.makeText(this, R.string.main_no_file_picker, Toast.LENGTH_LONG).show()
        }
        return true
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        app.detachPlayer(this)
        super.onDestroy()
    }
}
