package com.aoede.twa

import android.content.pm.PackageInfo
import android.content.pm.SigningInfo
import org.robolectric.Shadows.shadowOf
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 34], application = AoedeApp::class)
class AppUpdaterTest {
    private fun release() = JSONObject("""{"tag_name":"v1.2.7","assets":[{"name":"aoede-android.apk","state":"uploaded","label":"Aoede Android 1.10.0 (99)","size":123,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"https://evil.test/malware.apk"}]}""")

    @Test fun releaseUsesBinaryVersionFixedRepositoryAndOnlyNewStableSignedMetadata() {
        val data = release()
        val update = AppUpdater.parseRelease(data, BuildConfig.VERSION_CODE)!!
        assertEquals(99, update.code)
        assertEquals("1.10.0", update.version)
        assertEquals("https://github.com/sPROFFEs/Aoede/releases/download/v1.2.7/aoede-android.apk", update.url)
        assertNull(AppUpdater.parseRelease(data, 99))
        assertNull(AppUpdater.parseRelease(data.put("prerelease", true), 1))
        for ((key, value) in listOf("digest" to "bad", "label" to "1.10.0", "size" to -1, "size" to 1000000000)) {
            val broken = release()
            broken.getJSONArray("assets").getJSONObject(0).put(key, value)
            assertNull(AppUpdater.parseRelease(broken, 1))
        }
        assertTrue(runCatching { AppUpdater.parseRelease(release().put("tag_name", "../evil"), 1) }.isFailure)
    }

    @Test fun redirectsRejectUntrustedHostsCleartextCredentialsAndUnexpectedPorts() {
        for (url in listOf("http://github.com/a", "https://github.com.evil.test/a", "https://evil.test/a", "https://user:pass@github.com/a", "https://github.com:8443/a")) assertFalse(AppUpdater.allowedUrl(URL(url)))
        for (host in listOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) assertTrue(AppUpdater.allowedUrl(URL("https://$host/a")))
    }

    @Suppress("DEPRECATION")
    @Test fun installerRequiresMatchingIdentityVersionAndCertificateAndProviderIsNarrow() {
        val context = RuntimeEnvironment.getApplication() as AoedeApp
        run {
            fun info(name: String = "com.aoede.twa", code: Int = 99, signature: String = "abcd") = PackageInfo().apply {
                packageName = name; versionCode = code; signatures = arrayOf(Signature(signature))
                if (Build.VERSION.SDK_INT >= 28) {
                    signingInfo = SigningInfo().also { shadowOf(it).setSignatures(signatures) }
                    longVersionCode = code.toLong()
                }
            }
            assertTrue(AppUpdater.compatible(info(), info(code = 1), 99))
            assertFalse(AppUpdater.compatible(info(signature = "beef"), info(), 99))
            assertFalse(AppUpdater.compatible(info(name = "other.app"), info(), 99))
            assertFalse(AppUpdater.compatible(info(code = 98), info(), 99))
            assertFalse(AppUpdater.compatible(info(code = BuildConfig.VERSION_CODE), info(), BuildConfig.VERSION_CODE))
        }
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val file = File(context.cacheDir, "updates/aoede-update.apk")
        assertEquals("content", FileProvider.getUriForFile(context, provider.authority, file).scheme)
        assertTrue(runCatching { FileProvider.getUriForFile(context, provider.authority, File(context.cacheDir, "other-secret")) }.isFailure)
        assertEquals(1, JSONObject(context.clientInfo).getInt("updater"))
        assertEquals(BuildConfig.VERSION_CODE, JSONObject(context.clientInfo).getInt("versionCode"))
    }

    @Test fun atomFeedParsesValidEntryAndExtractsVersionAndCode() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <link rel="alternate" type="text/html" href="https://github.com/sPROFFEs/Aoede/releases/tag/v1.3.1"/>
    <title>Aoede 1.3.1</title>
    <content type="html">&lt;h1&gt;Aoede 1.3.1 · Android 1.2.6 (36)&lt;/h1&gt;</content>
  </entry>
</feed>"""
        val update = AppUpdater.parseAtomFeed(xml, 30)!!
        assertEquals("1.2.6", update.version)
        assertEquals(36, update.code)
        assertEquals("https://github.com/sPROFFEs/Aoede/releases/download/v1.3.1/aoede-android.apk", update.url)

        assertNull(AppUpdater.parseAtomFeed(xml, 36))
        assertNull(AppUpdater.parseAtomFeed(xml, 40))
        assertNull(AppUpdater.parseAtomFeed("<feed></feed>", 1))
    }

    @Test fun expandedAssetsExtractsApkSha256Digest() {
        val html = """<div>
<a href="/sPROFFEs/Aoede/releases/download/v1.3.1/aoede-android.apk">aoede-android.apk</a>
<span class="Truncate-text">sha256:3e52c3b17ba451e2c0cc5d8b63352f6ddc024590e255fde1864a874a1c922dab</span>
</div>"""
        val digest = AppUpdater.parseExpandedAssetsDigest(html)
        assertEquals("3e52c3b17ba451e2c0cc5d8b63352f6ddc024590e255fde1864a874a1c922dab", digest)

        val empty = AppUpdater.parseExpandedAssetsDigest("<div>no assets</div>")
        assertEquals("", empty)
    }

}
