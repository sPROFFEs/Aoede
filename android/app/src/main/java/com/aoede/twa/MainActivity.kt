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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private val app
        get() = application as AoedeApp

    private var nativeUi: NativeUi? = null
    private var nativeFileCallback: ((Uri) -> Unit)? = null
    private val nativeFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = nativeFileCallback
            nativeFileCallback = null
            if (uri?.scheme == "content") callback?.invoke(uri)
        }

    fun pickNativeFile(type: String, callback: (Uri) -> Unit) {
        val scope = app.nativeApi.scope
        nativeFileCallback = { uri -> if (scope == app.nativeApi.scope) callback(uri) }
        nativeFile.launch(type)
    }

    private var failedUrl: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val selected =
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    val clip = data.clipData
                    if (clip != null)
                        (0 until clip.itemCount)
                            .mapNotNull { clip.getItemAt(it).uri }
                            .toTypedArray()
                    else data.data?.let { arrayOf(it) }
                } else null
            val uris = selected?.filter { it.scheme == "content" }?.toTypedArray()
            fileCallback?.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
            fileCallback = null
        }

    private val updatePermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())
                app.updater.install(this)
            else Toast.makeText(this, R.string.update_permission_denied, Toast.LENGTH_LONG).show()
        }
    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val granted = app.musicIsland.permissionGranted
            app.musicIsland.setEnabled(granted)
            if (!granted)
                Toast.makeText(this, R.string.island_permission_denied, Toast.LENGTH_LONG).show()
        }

    fun openIslandSettings() =
        app.musicIsland.showSettings(this) {
            overlayPermission.launch(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }

    fun requestUpdatePermission() {
        updatePermission.launch(
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
        )
    }

    companion object {
        const val PREFS = "aoede_prefs"
        const val KEY_URL = "server_url"
        const val OPEN_PLAYER = "com.aoede.twa.OPEN_PLAYER"

        fun playerIntent(ctx: Context, openPlayer: Boolean = true) =
            Intent(ctx, MainActivity::class.java).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (openPlayer) putExtra(OPEN_PLAYER, true)
            }

        fun savedUrl(ctx: Context): String? =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, null)
                ?.trimEnd('/')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val url = savedUrl(this)
        if (url.isNullOrBlank()) {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .putExtra(SetupActivity.EXTRA_FIRST_RUN, true)
            )
            finish()
            return
        }
        if (
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("use_native_player", true)
        ) {
            app.attachNative(this, url)
            nativeUi =
                NativeUi(this, app).also {
                    it.create(savedInstanceState?.getBundle("native_navigation"))
                }
            onBackPressedDispatcher.addCallback(
                this,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (nativeUi?.back() != true) moveTaskToBack(true)
                    }
                },
            )
            openRequestedPlayer(intent)
            return
        }
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyAppearance(app.darkAppearance)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { root, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            app.musicIsland.updateSafeInsets(
                insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.tappableElement()
                )
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboard =
                insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > bars.bottom
            // Resize for the keyboard ourselves, including older WebViews. Forward system bars
            // and zero only the handled IME type to avoid a second resize on WebView 139+.
            root.setPadding(0, 0, 0, if (keyboard) ime.bottom else 0)
            app.updateViewport(bars, keyboard)
            val gear = findViewById<ImageButton>(R.id.btn_settings)
            (gear.layoutParams as FrameLayout.LayoutParams).apply {
                val density = resources.displayMetrics.density
                marginEnd =
                    (16 * density).toInt() +
                        if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) bars.left
                        else bars.right
                bottomMargin = (24 * density).toInt() + if (keyboard) 0 else bars.bottom
                gear.layoutParams = this
            }
            val errorPadding = (32 * resources.displayMetrics.density).toInt()
            findViewById<View>(R.id.connection_error)
                .setPadding(
                    bars.left + errorPadding,
                    bars.top + errorPadding,
                    bars.right + errorPadding,
                    bars.bottom + errorPadding,
                )
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }
        val view = app.attachPlayer(this, url)
        findViewById<FrameLayout>(R.id.webview_container).addView(view)
        ViewCompat.requestApplyInsets(findViewById(R.id.main_root))
        updatePage(view.url ?: url)
        app.connectionFailure?.let { (failed, detail) -> showConnectionError(failed, detail) }
        findViewById<View>(R.id.btn_retry).setOnClickListener {
            failedUrl?.let { view.loadUrl(it) }
        }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { openServerSettings() }
        openRequestedPlayer(intent)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    view.evaluateJavascript(
                        "Boolean(window.__aoedeBack && window.__aoedeBack())"
                    ) { handled ->
                        if (handled != "true" && !isFinishing && !isDestroyed) {
                            if (view.canGoBack()) view.goBack() else moveTaskToBack(true)
                        }
                    }
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedPlayer(intent)
    }

    private fun openRequestedPlayer(intent: Intent) {
        if (intent.getBooleanExtra(OPEN_PLAYER, false)) {
            intent.removeExtra(OPEN_PLAYER)
            app.mediaController.command("openplayer")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        nativeUi?.let { outState.putBundle("native_navigation", it.saveState()) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        nativeUi?.resume()
        app.musicIsland.refresh()
    }

    override fun onStop() {
        nativeUi?.pause()
        app.musicIsland.collapse()
        app.musicIsland.closeSettings(this)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        app.musicIsland.collapse()
        super.onUserLeaveHint()
    }

    fun applyAppearance(dark: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (android.os.Build.VERSION.SDK_INT >= 28)
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        // Older Android cannot draw dark bar icons; retain a scrim for light themes there.
        window.statusBarColor =
            if (!dark && android.os.Build.VERSION.SDK_INT < 23) 0x66000000 else Color.TRANSPARENT
        window.navigationBarColor =
            if (!dark && android.os.Build.VERSION.SDK_INT < 26) 0x66000000 else Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    fun openServerSettings() {
        startActivity(
            Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FIRST_RUN, false)
        )
    }

    fun updatePage(url: String) {
        val path = android.net.Uri.parse(url).path.orEmpty()
        findViewById<ImageButton>(R.id.btn_settings)?.visibility =
            if (path.startsWith("/login") || path == "/_aoede/start" || failedUrl != null)
                View.VISIBLE
            else View.GONE
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

    fun chooseFile(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        if (
            ServerOrigin.from(Uri.parse(app.player?.url.orEmpty())) !=
                app.mediaController.serverOrigin
        ) {
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

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val currentFocus = currentFocus
        if (currentFocus is android.widget.EditText) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_SPACE -> {
                if (app.nativePlayback.playing) app.mediaController.pauseLocal()
                else app.mediaController.command("play")
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (app.nativePlayback.playing) app.mediaController.pauseLocal()
                else app.mediaController.command("play")
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                app.mediaController.command("nexttrack")
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                app.mediaController.command("previousgesture")
                return true
            }
            android.view.KeyEvent.KEYCODE_L -> {
                val playerView = nativeUi?.fullscreen as? NativePlayerView
                if (playerView != null) {
                    playerView.setMode(if (playerView.mode == "lyrics") "cover" else "lyrics")
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_Q -> {
                val playerView = nativeUi?.fullscreen as? NativePlayerView
                if (playerView != null) {
                    playerView.setMode(if (playerView.mode == "queue") "cover" else "queue")
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_M -> {
                val player = app.nativePlayback
                player.setVolume(if (player.volume > 0) 0f else .7f, true)
                nativeUi?.toast(if (player.volume > 0) "Volume restored" else "Muted")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        val native = nativeUi
        native?.close()
        nativeUi = null
        nativeFileCallback = null
        val detached = if (native != null) app.detachNative(this) else app.detachPlayer(this)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (detached && isFinishing && !isChangingConfigurations) app.musicIsland.taskRemoved()
        app.musicIsland.closeSettings(this)
        super.onDestroy()
    }
}
