package com.aoede.twa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class ConnectionTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp
    private lateinit var activity: MainActivity
    private lateinit var lifecycle: org.robolectric.android.controller.ActivityController<MainActivity>

    @Before fun openServer() {
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(MainActivity.KEY_URL, "https://example.com:8443").commit()
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = lifecycle.get()
    }

    @After fun cleanup() {
        lifecycle.pause().stop().destroy()
        app.destroyPlayer()
    }

    private fun request(url: String, main: Boolean = true, gesture: Boolean = false) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
        override fun isRedirect() = !gesture
    }

    @Test fun internalEndpointsStayInAppAndRedirectsDoNotLaunchBrowsers() {
        val view = app.player!!
        val client = requireNotNull(shadowOf(view).webViewClient)
        fun navigate(url: String): Boolean = if (android.os.Build.VERSION.SDK_INT >= 24) {
            client.shouldOverrideUrlLoading(view, request(url))
        } else client.shouldOverrideUrlLoading(view, url)
        for (path in listOf("/admin/", "/admin/system", "/api/party/rooms/2/join", "/login")) {
            assertFalse(navigate("https://example.com:8443$path"))
        }
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://youtube.com", main = false)))
        }
        assertTrue(navigate("http://example.com/admin/"))
        assertNull(shadowOf(app).nextStartedActivity)
        assertNotNull(app.connectionFailure)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_settings).visibility)
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            assertTrue(client.shouldOverrideUrlLoading(view, request("https://elsewhere.test", gesture = true)))
            assertEquals(Intent.ACTION_VIEW, shadowOf(app).nextStartedActivity?.action)
        }
    }

    @Test fun subresourceErrorsDoNotHideThePageAndMainErrorsCanBeRetried() {
        val view = app.player!!
        val client = requireNotNull(shadowOf(view).webViewClient)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val error = WebResourceResponse("text/html", "UTF-8", 404, "Not Found", emptyMap(), null)
            client.onReceivedHttpError(view, request("https://example.com:8443/assets/missing.png", main = false), error)
            assertNull(app.connectionFailure)
            client.onReceivedHttpError(view, request("https://example.com:8443/admin/system"), error)
        } else {
            client.onReceivedError(view, android.webkit.WebViewClient.ERROR_CONNECT, "Cannot connect", "https://example.com:8443/admin/system")
        }
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.connection_error).visibility)
        activity.findViewById<View>(R.id.btn_retry).performClick()
        assertEquals("https://example.com:8443/admin/system", shadowOf(view).lastLoadedUrl)
        client.onPageStarted(view, "https://example.com:8443/admin/system", null)
        assertNull(app.connectionFailure)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.connection_error).visibility)
    }

    @Test fun fileUploadsDeliverSelectedContentToWebviewAndCancellationCompletes() {
        val view = app.player!!
        val chrome = requireNotNull(shadowOf(view).webChromeClient)
        val values = mutableListOf<Array<Uri>?>()
        val callback = ValueCallback<Array<Uri>> { values.add(it) }
        val params = object : WebChromeClient.FileChooserParams() {
            override fun getMode() = MODE_OPEN
            override fun getAcceptTypes() = arrayOf("image/*")
            override fun isCaptureEnabled() = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        }
        assertTrue(chrome.onShowFileChooser(view, callback, params))
        assertTrue(values.isEmpty())
        val picker = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        val chosen = Uri.parse("content://documents/image.png")
        shadowOf(activity).receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(chosen))
        assertArrayEquals(arrayOf(chosen), values.single())
        chrome.onShowFileChooser(view, callback, params)
        val cancel = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
        shadowOf(activity).receiveResult(cancel.intent, Activity.RESULT_CANCELED, null)
        assertNull(values.last())
    }
}
