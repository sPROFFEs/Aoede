# Aoede Android 1.2.0

Native Kotlin Views, Media3 playback and private offline storage connect to Aoede 1.3.1+. The normal client does not mount a WebView. Setup asks for a backend root URL, and the native login uses the existing protected session. Classic, Glass and Vitrine share account preferences with the web client; native layouts/effects are a separate implementation.

One native player supplies notification, widget and optional island controls. API requests are pinned to the configured scheme/host/port, reject credentialed redirects and retain normal TLS validation. Sessions use AndroidKeyStore-backed authenticated encryption and no-backup storage. Audio, metadata, queue and pending actions are isolated by backend/account. No microphone permission is required for native island sampling.

The previous installed web client remains an explicit compatibility option. Legacy IndexedDB import is explicit, temporarily requires WebView, verifies previous ownership and preserves its source database. Unassigned legacy downloads require manual review. See the repository's `docs/ANDROID.md`, `docs/ANDROID-NATIVE-MIGRATION.md` and `docs/SECURITY-REVIEW-1.3.1.md` (included in the source distribution).

## Build and checks

Requires JDK 17, Android SDK/build tools 35, Python 3 with Jinja2 3.1.6 and Node.js. Minimum Android is API 21. Gradle compiles native translations and the preserved compatibility frontend before building. A standalone source archive includes editable canonical frontend sources under `frontend-source/`.

```sh
python3 -m pip install Jinja2==3.1.6
cd android
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease
node test_frontend.mjs
node test_native_migration.mjs
cd ..
node --experimental-vm-modules scripts/test_android.mjs
python3 scripts/package_android.py
```

In the standalone archive, run Gradle/Node from its root; the packaging script is a monorepo release tool. Unsigned release builds require signing before installation. Build directories, local SDK configuration and signing keys are excluded from the ZIP.

## Distribution signing

Keep the existing distribution key. Configure `AOEDE_ANDROID_KEYSTORE`, `AOEDE_ANDROID_STORE_PASSWORD`, `AOEDE_ANDROID_KEY_ALIAS` and `AOEDE_ANDROID_KEY_PASSWORD` in the build environment, then build `assembleRelease`. Never commit keys or credentials. GitHub's Android workflow signs only with configured distribution secrets, verifies the package/certificate and publishes only a signed artifact. The source ZIP never contains those secrets.

The official updater independently checks the fixed GitHub release source, APK digest/size, package, increasing versionCode and installed signing certificate. Android installer confirmation is required. No backend-supplied APK URL, silent install, TLS bypass or signing-key fallback is added.

## Compatibility and device acceptance

Use **Appearance & settings → Installed web client · compatibility**, or disable **Native Android interface** in server configuration, to inspect the preserved client/library. Switching stops the other engine first. WebView is needed only there or for explicit IndexedDB import. Offline import cannot safely assign globally unowned legacy single downloads to an account.

Software checks and an isolated API 26 emulator are not physical Bluetooth/OEM validation. Verify upgrade/data ownership, focus/headphone loss, lock/unlock, widgets/island, offline cold start, rapid track changes and native federation on real Pixel/GrapheneOS and Samsung devices. The prior Vitreo/LibrePods/AirPods intermittent silence is not declared resolved. No automatic reconnection, process restart or pause/play recovery is used to conceal failures.
