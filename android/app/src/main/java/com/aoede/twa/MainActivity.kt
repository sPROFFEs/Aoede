package com.aoede.twa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets

class MainActivity : AppCompatActivity() {
    private val app get() = application as AoedeApp
    private var failedUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val selected = if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.toTypedArray()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyAppearance(app.darkAppearance)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > bars.bottom
            // Resize for the keyboard ourselves, including older WebViews. Forward system bars
            // and zero only the handled IME type to avoid a second resize on WebView 139+.
            root.setPadding(0, 0, 0, if (keyboard) ime.bottom else 0)
            app.updateViewport(bars, keyboard)
            val gear = findViewById<ImageButton>(R.id.btn_settings)
            (gear.layoutParams as FrameLayout.LayoutParams).apply {
                val density = resources.displayMetrics.density
                marginEnd = (16 * density).toInt() + if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) bars.left else bars.right
                bottomMargin = (24 * density).toInt() + if (keyboard) 0 else bars.bottom
                gear.layoutParams = this
            }
            val errorPadding = (32 * resources.displayMetrics.density).toInt()
            findViewById<View>(R.id.connection_error).setPadding(bars.left + errorPadding, bars.top + errorPadding, bars.right + errorPadding, bars.bottom + errorPadding)
            WindowInsetsCompat.Builder(insets).setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE).build()
        }
        val view = app.attachPlayer(this, url)
        findViewById<FrameLayout>(R.id.webview_container).addView(view)
        ViewCompat.requestApplyInsets(findViewById(R.id.main_root))
        updatePage(view.url ?: url)
        app.connectionFailure?.let { (failed, detail) -> showConnectionError(failed, detail) }
        findViewById<View>(R.id.btn_retry).setOnClickListener {
            failedUrl?.let { view.loadUrl(it) }
        }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            openServerSettings()
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                view.evaluateJavascript("Boolean(window.__aoedeBack && window.__aoedeBack())") { handled ->
                    if (handled != "true" && !isFinishing && !isDestroyed) {
                        if (view.canGoBack()) view.goBack() else moveTaskToBack(true)
                    }
                }
            }
        })
    }

    fun applyAppearance(dark: Boolean) {
        // Older Android cannot draw dark bar icons; retain a scrim for light themes there.
        window.statusBarColor = if (!dark && android.os.Build.VERSION.SDK_INT < 23) 0x66000000 else Color.TRANSPARENT
        window.navigationBarColor = if (!dark && android.os.Build.VERSION.SDK_INT < 26) 0x66000000 else Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    fun openServerSettings() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FIRST_RUN, false))
    }

    fun updatePage(url: String) {
        val path = android.net.Uri.parse(url).path.orEmpty()
        findViewById<ImageButton>(R.id.btn_settings)?.visibility =
            if (path.startsWith("/login") || path == "/_aoede/start" || failedUrl != null) View.VISIBLE else View.GONE
    }

    fun clearConnectionError() {
        failedUrl = null
        findViewById<View>(R.id.connection_error)?.visibility = View.GONE
    }

    fun showConnectionError(url: String, detail: String) {
        if (ServerOrigin.from(Uri.parse(url)) != app.mediaController.serverOrigin) return
        failedUrl = url
        applyAppearance(true)
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
