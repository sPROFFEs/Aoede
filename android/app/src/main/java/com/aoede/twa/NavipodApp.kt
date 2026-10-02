package com.aoede.twa

import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.lang.ref.WeakReference

class AoedeApp : Application() {
    lateinit var mediaController: MediaController
        private set
    var player: WebView? = null
        private set
    private var playerUrl: String? = null
    private var activity = WeakReference<MainActivity>(null)

    override fun onCreate() {
        super.onCreate()
        mediaController = MediaController(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attachPlayer(owner: MainActivity, url: String): WebView {
        if (playerUrl != url) destroyPlayer()
        activity = WeakReference(owner)
        player?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            (it.context as MutableContextWrapper).baseContext = owner
            return it
        }
        val origin = ServerOrigin.from(Uri.parse(url)) ?: error("Invalid server URL")
        playerUrl = url
        mediaController.serverOrigin = origin
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val view = WebView(MutableContextWrapper(owner))
        player = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = false
            userAgentString += " AoedeAndroid/${BuildConfig.VERSION_NAME}"
        }
        val shim = assets.open("media-session.js").bufferedReader().use { it.readText() }
        val messages = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (messages) {
            WebViewCompat.addWebMessageListener(view, "AoedeMedia", setOf(origin.rule)) {
                _, message, source, mainFrame, _ ->
                if (mainFrame && ServerOrigin.from(source) == origin) {
                    mediaController.receive(message.data.orEmpty())
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(view, shim, setOf(origin.rule))
            }
        } else Toast.makeText(this, R.string.main_update_webview, Toast.LENGTH_LONG).show()
        mediaController.setActionInvoker { action, position ->
            player?.evaluateJavascript(
                "window.__aoedeMediaCommand && window.__aoedeMediaCommand(${JSONObject.quote(action)}, $position)", null
            )
        }
        view.webViewClient = object : WebViewClient() {
            private fun navigate(uri: Uri): Boolean {
                if (ServerOrigin.from(uri) == origin) return false
                if (uri.scheme in setOf("https", "http")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@AoedeApp, R.string.main_no_browser, Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (request.isForMainFrame) navigate(request.url) else false

            @Deprecated("Needed on Android 5 and 6")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = navigate(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                mediaController.reset()
                activity.get()?.updatePage(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (ServerOrigin.from(Uri.parse(url)) != origin) return
                if (messages && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(shim, null)
                }
                activity.get()?.updatePage(url)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                destroyPlayer()
                activity.get()?.recreate()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (ServerOrigin.from(request.origin) == origin && allowed.isNotEmpty()) {
                    request.grant(allowed.toTypedArray())
                } else request.deny()
            }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) android.util.Log.d("AoedeWebView", message.message())
                return true
            }
        }
        view.loadUrl(url)
        return view
    }

    fun detachPlayer(owner: MainActivity) {
        if (activity.get() !== owner) return
        (player?.parent as? ViewGroup)?.removeView(player)
        (player?.context as? MutableContextWrapper)?.baseContext = this
        activity.clear()
    }

    fun destroyPlayer() {
        mediaController.setActionInvoker(null)
        mediaController.reset()
        player?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        player = null
        playerUrl = null
    }

    fun ensurePlaybackService() {
        if (mediaController.needsForeground && !PlaybackService.running) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
            } catch (_: IllegalStateException) {
                mediaController.pauseLocal()
            } catch (_: SecurityException) {
                mediaController.pauseLocal()
            }
        } else if (mediaController.state == "none") {
            stopService(Intent(this, PlaybackService::class.java))
        }
    }
}

data class ServerOrigin(val scheme: String, val host: String, val port: Int) {
    val rule get() = "$scheme://${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:$port"
    companion object {
        fun from(uri: Uri): ServerOrigin? {
            val parsed = try { java.net.URI(uri.toString()) } catch (_: java.net.URISyntaxException) { return null }
            val scheme = parsed.scheme?.lowercase() ?: return null
            val host = parsed.host?.lowercase() ?: return null
            if (scheme !in setOf("https", "http") || host.isBlank() || parsed.userInfo != null) return null
            val port = if (parsed.port == -1) { if (scheme == "https") 443 else 80 } else parsed.port
            if (port !in 1..65535) return null
            return ServerOrigin(scheme, host, port)
        }
    }
}
