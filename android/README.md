# Aoede Android

Android 1.1.4 installs the Aoede frontend in the APK: the login and player shells, JavaScript modules, Classic/Glass/Vitrine themes, translations, icons and icon fonts. First launch asks for a backend root URL (HTTPS by default; explicit HTTP and custom ports also work). Change it from user settings or the connection screen. The UI renders in a retained WebView; Android owns playback integration, widgets, file selection, window insets and configuration. This is a packaged hybrid client, not a Kotlin rewrite of every screen.

Login HTML preparation inserts native scripts only into the actual document head; literal `<head>` text inside script comments stays untouched.

Bundled resources are served only at the configured origin, including its exact scheme and port. Music streams, federation, uploads and API requests continue to reach that backend with its normal session cookie; no CORS proxy or copied token is involved. Server-rendered settings, help and admin forms still require the backend. The installed frontend updates with the APK. Backend support for `/api/mobile/bootstrap` (protocol 1, introduced with this client) is required; older servers display an update instruction instead of a JSON parsing error.

The application owns one retained WebView, so its document, queue and offline Blob URLs survive activity recreation and returning from the background. A media playback foreground service runs during playback, publishes the MediaSession controls and holds a CPU wake lock; it does not keep the display awake.

**More → About & updates** shows the running server version, release highlights and current Android/desktop downloads. Update the backend for the authenticated `/api/about` endpoint.

Add **Aoede player** from the launcher's widget picker. Its cover and metadata follow the native media session; play/pause and previous/next control the existing player without opening the app. If Android has stopped the process, tapping the widget opens the app without pretending playback survived. Updates follow media events, with no periodic polling.

The widget has three resize-aware layouts: a compact row, a player with previous/play/next controls, and a larger artwork card. Its dark colour gradient and primary button adapt to the existing cover; no extra artwork requests are made. Artwork rendering is cached and widget updates follow media changes rather than a polling timer. Large text falls back to a layout that fits, unavailable skip actions are disabled, and an empty player opens Aoede. Android 12+ uses [responsive RemoteViews](https://developer.android.com/develop/ui/views/appwidgets/layouts); older launchers get separate portrait and landscape layouts. Existing widget instances update in place.


Phones, landscape and tablets use the same responsive themes with touch targets, keyboard/cutout/system-bar insets and viewport-sized fullscreen playback. **Reduce visual effects** in server configuration disables expensive glass blur; low-memory devices default to reduced effects. Account theme preferences continue to sync through the backend.

Version 1.1.1 draws backgrounds behind Android's system bars and forwards safe areas to the frontend, including older WebViews. Android resizes the viewport for the keyboard and zeroes only the handled IME insets, preventing a second resize in newer WebViews, following [Android's window inset guidance](https://developer.android.com/develop/ui/views/layout/webapps/understand-window-insets). The mobile dock hides while typing, with focus preserved; Back dismisses dialogs, player sheets and menus before navigating. The same mobile shell is used in touch landscape, and Vitrine uses tighter spacing on phones and desktop. Secondary actions (Create, Downloads, deletion responses and Admin) are in the mobile header's More menu; Library retains its search and create shortcuts.


After a successful login, the last validated account context and installed shell can open downloaded music without the server. A 401/403 response or entering login clears that context; explicit logout also clears it before submission. A server outage can use the cached context, but HTTP errors and incompatible protocols never impersonate a valid login. The old wrapper's service worker is unregistered during migration; existing cookies and IndexedDB downloads are preserved. Force stop/process death still ends playback, and online features require a reachable backend.

Headphone/Bluetooth disconnection and Android media pause controls pause this device, including in party rooms. Playback remains locally paused until an explicit play action. Chromium manages audio focus; the wrapper does not request a competing audio focus owner. The bridge accepts messages only from the configured origin's main frame. HTTP servers remain supported when explicitly configured.

Internal navigation stays inside the configured origin, including its port. Automatic redirects to another origin show a connection error instead of launching a browser; external links tapped by the user still open in the browser. Connection errors offer Retry and server configuration, while errors in subresources do not replace the page. HTTPS certificate errors remain blocked. Avatar, playlist-cover and cookie-file uploads use the system file picker, and login cookies are flushed after navigation.

## Build and checks

Requires Python 3 with Jinja2 3.1.6, JDK 17, Android SDK/build-tools 35 and an up-to-date Android System WebView on the device. Minimum Android version is 5.0 (API 21).

```sh
python3 -m pip install Jinja2==3.1.6
cd android
./gradlew testDebugUnitTest lintRelease assembleRelease
node test_frontend.mjs
cd ..
node --experimental-vm-modules scripts/test_android.mjs
python3 scripts/package_android.py
```

Gradle runs `build_frontend.py` before building; it compiles the repository's canonical Jinja templates for EN/ES/DE without starting Python services. The source ZIP includes those editable templates, assets and catalogues under `frontend-source/`, the generated bundle, compiler, Gradle wrapper, native app and tests, so it also builds outside the monorepo. Unsigned builds are named `aoede-android-unsigned.apk` and need signing before installation. SDK paths, build directories and signing keys are excluded.

## Distribution signing

Set `AOEDE_ANDROID_KEYSTORE` to the absolute path of the distribution PKCS12 keystore, plus `AOEDE_ANDROID_STORE_PASSWORD`, `AOEDE_ANDROID_KEY_ALIAS` and `AOEDE_ANDROID_KEY_PASSWORD`, then run `assembleRelease`. Keep this key private and backed up. Installing over an existing Aoede APK requires a compatible signing certificate; generating another Android Debug key does not preserve that compatibility.

Android 1.0.4 introduces the stable distribution key because the old debug key was not retained. Users of APK 1.0.3 must uninstall that APK before installing 1.0.4, which removes its local settings and offline downloads. Future releases use the same distribution key and can update 1.0.4 in place.

For GitHub Actions configure those three credentials and `AOEDE_ANDROID_KEYSTORE_BASE64` as repository secrets. The Android workflow tests, lints, builds and packages every Android change. Dispatch it with `release_tag` to update the APK and source ZIP on an existing release after validation. Unsigned builds are retained as workflow artifacts and never replace the published installable APK.

Publishing also preserves the original 1.0.5 remote wrapper and its sources as `aoede-android-wrapper-1.0.5.apk` and `aoede-android-wrapper-1.0.5-source.zip`. The workflow retrieves the archived originals from v1.2.7 and verifies their pinned SHA-256 hashes before publishing any APK. Use this backup with older backends. Both clients have the same package ID; reverting to an older version normally requires uninstalling the newer app and removes its local data.

## Device acceptance checks

- Play several tracks with the screen locked; verify queue progression, notification progress and Bluetooth controls.
- Use Home, root Back, activity recreation and reopening from the notification without resetting playback.
- Disconnect wired and Bluetooth headphones; verify silence and no automatic resume on reconnect.
- Repeat as a party guest: local pause must survive room updates and must not pause the host.
- Interrupt with another music app and a phone call; verify focus loss and appropriate resumption.
- Check offline downloaded tracks, failed streams, long buffering and changing servers.
- Add multiple player widgets; resize each between compact, standard and artwork layouts in both orientations. Verify metadata, cover colours, 48dp or larger controls, large system fonts, disabled skip actions, local pause as a party guest and cold-process taps.
- Check each theme at 320/360/412dp, landscape and tablet widths, including gesture/three-button navigation, keyboard, cutouts, user menus, modals, fullscreen lyrics and queue. Check bar icon contrast after changing theme and after opening/closing the fullscreen player.
- Upgrade an installed 1.0.4/1.0.5 without uninstalling; verify cookies/downloads remain and old service-worker caches cannot replace installed modules.
- Open Admin from System Monitor behind an HTTPS tunnel and on a server with a custom port; verify navigation, form submission and Back stay in the app.
- Upload an avatar, a playlist cover and a cookies file; cancel the file picker and try again.
- Open an unreachable server, retry, then change its URL from the error screen. Check that an individual missing image does not trigger that screen.
- Stop/dismiss playback and verify notification, CPU wake lock and noisy receiver cleanup.

Force-stopping the application or Android stopping its process ends playback. Extremely old WebView versions without origin-scoped web messages require a WebView update for native background controls; no unrestricted JavaScript interface is enabled as a fallback.
