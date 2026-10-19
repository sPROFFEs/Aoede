package com.aoede.twa

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class MobileFrontendTest {
    private val app get() = RuntimeEnvironment.getApplication() as AoedeApp
    private val origin = ServerOrigin.from(Uri.parse("https://example.com:8443"))!!

    @After fun cleanup() { app.destroyPlayer() }

    @Test fun bundledResourcesAreRestrictedToExactOriginAndNeverReplaceApisOrUploads() {
        val frontend = MobileFrontend(app, origin)
        val response = frontend.intercept(Uri.parse("${origin.rule}/assets/js/main.js?v=anything"))!!
        assertEquals("application/javascript", response.mimeType)
        assertTrue(response.data.bufferedReader().readText().contains("Aoede ES6 Modules"))
        for (url in listOf("https://example.com/assets/js/main.js", "http://example.com:8443/assets/js/main.js",
            "https://elsewhere.test:8443/assets/js/main.js", "${origin.rule}/api/party/remote/2/rooms/3/join",
            "${origin.rule}/user/avatar/alice", "${origin.rule}/assets/user-upload.png")) {
            assertNull(frontend.intercept(Uri.parse(url)))
        }
        assertNull(frontend.intercept(Uri.parse("${origin.rule}/login"), "POST", true))
        assertEquals(403, frontend.intercept(Uri.parse("${origin.rule}/assets/%2e%2e/mobile/boot.js"))!!.statusCode)
        val start = frontend.intercept(Uri.parse("${origin.rule}/_aoede/start"), mainFrame = true)!!
        assertTrue(start.data.bufferedReader().readText().contains("/_aoede/boot.js"))
        for (url in listOf("http://192.168.1.20:8080", "https://example.com", "https://[::1]:8443")) {
            val custom = MobileFrontend(app, ServerOrigin.from(Uri.parse(url))!!)
            assertNotNull(custom.intercept(Uri.parse("$url/assets/js/main.js")))
            assertNull(custom.intercept(Uri.parse("$url/api/party/rooms/3/join"), "POST", true))
        }
    }

    @Test fun validatedContextSelectsLocalThemeLanguageRoleAndReloadReauthenticates() {
        val frontend = MobileFrontend(app, origin)
        fun payload(username: String = "alice", theme: String = "vitrine") = JSONObject().put("type", "render")
            .put("language", "es").put("offline", true).put("data", JSONObject().put("protocol", 1)
                .put("username", username).put("is_admin", true).put("theme", theme)
                .put("pool", JSONObject().put("percent", 17).put("used", 2).put("limit", 10))).toString()
        assertNull(frontend.prepare(payload("</script>")))
        assertNull(frontend.prepare(payload(theme = "bad\"theme")))
        val target = frontend.prepare(payload())!!
        assertEquals("${origin.rule}/portal?native=1", target)
        val html = frontend.intercept(Uri.parse(target), mainFrame = true)!!.data.bufferedReader().readText()
        assertTrue(html.contains("data-theme=\"vitrine\""))
        assertTrue(html.contains("lang=\"es\""))
        assertTrue(html.contains("window.AOEDE_NATIVE_OFFLINE=true"))
        assertTrue(html.contains("admin_system.css"))
        assertFalse(html.contains("AOEDE_MOBILE_"))
        assertFalse(html.contains("https://fonts.googleapis.com"))
        val reload = frontend.intercept(Uri.parse(target), mainFrame = true)!!.data.bufferedReader().readText()
        assertTrue(reload.contains("/_aoede/boot.js"))
        assertFalse(reload.contains("username: \"alice\""))
    }

    @Test fun widgetsShowNativeMetadataAndColdActionsOpenAppWithoutStartingPlayback() {
        val provider = PlaybackWidget()
        provider.onReceive(app, Intent(app, PlaybackWidget::class.java).setAction(PlaybackWidget.ACTION_CONTROL).putExtra("control", "toggle"))
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
        assertFalse(PlaybackService.running)
        val manager = AppWidgetManager.getInstance(app)
        val id = shadowOf(manager).createWidget(PlaybackWidget::class.java, R.layout.widget_playback)
        app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putString(MainActivity.KEY_URL, origin.rule).commit()
        val lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        val calls = mutableListOf<String>()
        app.mediaController.setActionInvoker { action, _ -> calls.add(action) }
        app.mediaController.updateState("paused", 0, 9000)
        app.mediaController.receive("{\"type\":\"metadata\",\"title\":\"A song\",\"artist\":\"Artist\"}")
        shadowOf(Looper.getMainLooper()).idle()
        PlaybackWidget.update(app, intArrayOf(id))
        assertEquals("A song", shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_title).text.toString())
        provider.onReceive(app, Intent(app, PlaybackWidget::class.java).setAction(PlaybackWidget.ACTION_CONTROL).putExtra("control", "toggle"))
        assertEquals(listOf("play"), calls)
        provider.onReceive(app, Intent(app, PlaybackWidget::class.java).setAction(PlaybackWidget.ACTION_CONTROL).putExtra("control", "next"))
        assertEquals(listOf("play"), calls)
        lifecycle.pause().stop().destroy()
        val component = ComponentName(app, PlaybackWidget::class.java)
        assertEquals(app.packageName, component.packageName)
    }

    @Test fun failedLoginsKeepTheirErrorAndEscapeServerText() {
        val frontend = MobileFrontend(app, origin)
        val target = frontend.prepare(JSONObject().put("type", "render").put("login", true)
            .put("loginError", "Invalid <script>alert(1)</script>").toString())!!
        val html = frontend.intercept(Uri.parse(target), mainFrame = true)!!.data.bufferedReader().readText()
        assertTrue(html.contains("Invalid &lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("Invalid <script>"))
        val normal = frontend.prepare("{\"type\":\"render\",\"login\":true}")!!
        val clean = frontend.intercept(Uri.parse(normal), mainFrame = true)!!.data.bufferedReader().readText()
        assertFalse(clean.contains("<div class=\"error-pill-pro\">"))
        // A literal <head> inside the PWA script comment must not receive another script injection.
        assertEquals(1, Regex("<script>window.AOEDE_ANDROID=true;").findAll(clean).count())
        assertEquals(1, Regex("src=\"/_aoede/media-session.js\"").findAll(clean).count())
        assertTrue(clean.contains("is in <head>\n"))
        System.getenv("AOEDE_LOGIN_PREVIEW")?.let { java.io.File(it).writeText(clean) }
    }
}
