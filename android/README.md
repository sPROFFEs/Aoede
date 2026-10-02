# Aoede Android

The Android app embeds the configured Aoede server in a WebView. The application owns one retained WebView, so its document, queue and offline Blob URLs survive activity recreation and returning from the background. A media playback foreground service runs during playback, publishes the MediaSession controls and holds a CPU wake lock; it does not keep the display awake.

Headphone/Bluetooth disconnection and Android media pause controls pause this device, including in party rooms. Playback remains locally paused until an explicit play action. Chromium manages audio focus; the wrapper does not request a competing audio focus owner. The bridge accepts messages only from the configured origin's main frame. HTTP servers remain supported when explicitly configured.

## Build and checks

Requires JDK 17, Android SDK 34 and an up-to-date Android System WebView on the device. Minimum Android version is 5.0 (API 21).

```sh
cd android
./gradlew testDebugUnitTest lintRelease assembleRelease
cd ..
node --experimental-vm-modules scripts/test_android.mjs
python3 scripts/package_android.py
```

Unsigned builds are named `aoede-android-unsigned.apk`. They cannot be installed until signed. The source ZIP includes the Gradle wrapper, app, media bridge and tests, and excludes SDK paths, build directories and signing keys.

## Distribution signing

Set `AOEDE_ANDROID_KEYSTORE` to the absolute path of the distribution PKCS12 keystore, plus `AOEDE_ANDROID_STORE_PASSWORD`, `AOEDE_ANDROID_KEY_ALIAS` and `AOEDE_ANDROID_KEY_PASSWORD`, then run `assembleRelease`. Keep this key private and backed up. Installing over an existing Aoede APK requires a compatible signing certificate; generating another Android Debug key does not preserve that compatibility.

Android 1.0.4 introduces the stable distribution key because the old debug key was not retained. Users of APK 1.0.3 must uninstall that APK before installing 1.0.4, which removes its local settings and offline downloads. Future releases use the same distribution key and can update 1.0.4 in place.

For GitHub Actions configure those three credentials and `AOEDE_ANDROID_KEYSTORE_BASE64` as repository secrets. The Android workflow tests, lints, builds and packages every Android change. Dispatch it with `release_tag` to update the APK and source ZIP on an existing release after validation. Unsigned builds are retained as workflow artifacts and never replace the published installable APK.

## Device acceptance checks

- Play several tracks with the screen locked; verify queue progression, notification progress and Bluetooth controls.
- Use Home, root Back, activity recreation and reopening from the notification without resetting playback.
- Disconnect wired and Bluetooth headphones; verify silence and no automatic resume on reconnect.
- Repeat as a party guest: local pause must survive room updates and must not pause the host.
- Interrupt with another music app and a phone call; verify focus loss and appropriate resumption.
- Check offline downloaded tracks, failed streams, long buffering and changing servers.
- Stop/dismiss playback and verify notification, CPU wake lock and noisy receiver cleanup.

Force-stopping the application or Android stopping its process ends playback. Extremely old WebView versions without origin-scoped web messages require a WebView update for native background controls; no unrestricted JavaScript interface is enabled as a fallback.
