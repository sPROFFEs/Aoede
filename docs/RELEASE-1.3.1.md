# Aoede 1.3.1

Aoede gains a native Android client with Kotlin screens, Media3 playback and account-scoped device downloads. The web and Android sign-in screens have also been refreshed with clearer typography, accessible controls and password visibility.

## Android 1.2.0 · build 30

- Native browsing, search, playlists, albums, favorites, discovery, profiles, lyrics, radio, Wrapped, parties, settings and administration connect to the existing authenticated backend. Normal native navigation and playback do not load a WebView.
- One Media3 player serves the application, notification, home-screen widgets and optional Dynamic Island. Audio focus, headphone disconnection and background playback use Android's media APIs. Island frequency sampling reads the same PCM pipeline without creating or closing an audio capture stream.
- The playback service posts its initial media notification before metadata work, avoids duplicate startup requests and defers a native widget resume until its foreground service exists. A joined live party retains its session while waiting for the host, without holding an audio wake lock while paused.
- The compact and fullscreen players support track gestures, seeking, volume, shuffle/repeat, sleep timer and optional normalization/fades. Final-track Next keeps the current player intact.
- Device downloads use app-private files, checksums, bounded artwork and account/node namespaces. Saved queues restore paused. Offline favorites and supported collection changes can synchronize after reconnection.
- Queue teardown, party restoration and queued requests retain their original account identity during a sign-in change; clearing a session safely stops its player and outstanding device-download work.
- A separate compatibility option retains the previous installed web interface. Original IndexedDB data is preserved. Explicit migration imports only collections attributable to a verified previous account session; unassigned old downloads remain available in compatibility mode.
- Provider verification is available to administrators through a private native screenshot/input viewer. The remote browser stays in the downloader worker; its ports and debugging protocol are not exposed to the phone.

Update the server to **1.3.1 before using the native interface**. Android remains **API 21+**. WebView is needed only for the optional compatibility client or an explicit legacy IndexedDB import. Native layouts share account theme colors; they are a separate implementation of the interface, not pixel-identical copies of the web themes or all their animations.

## Server and security

- New `/api/mobile/v2` routes reuse the signed session, revocation, account activity, administrator authorization and same-origin mutation checks. Settings responses never return stored provider secrets.
- A stable random account identity isolates device libraries across accounts and recreated usernames while preserving access after a password change. Newly issued sessions bind that identity; old sessions remain subject to existing expiry/version/revocation checks.
- Native collection creation supports atomic idempotent retries. Spotify previews can use the existing protected media relay without forwarding the native session to a CDN.
- Database migrations are additive. Back up the database before updating; deploy the concierge and downloader changes together.

## Downloads and compatibility

`aoede-android.apk` and `aoede-android-source.zip` contain Android 1.2.0. The release also preserves Android 1.1.22 as `aoede-android-webview-1.1.22.apk` and its complete source, the original 1.0.5 wrapper APK/source, and all six Desktop 1.2.10 archives unchanged. Desktop wrappers and the original Android wrapper do not need recompilation.

The current distribution signature and package identity are retained. Prefer the in-app compatibility option when checking old device data. Installing a lower-version backup normally requires uninstalling and removes app data; backups cannot be installed alongside the current client.

## Validation and limits

Backend authorization, CSRF, session recreation, migration, retry and private-response checks; Android JVM tests and release lint; and browser checks of login layouts, password visibility and submission accompany this release. An isolated API 26 emulator has opened the native client, authenticated and played a test recording with its WebView provider disabled.

Physical Pixel/GrapheneOS, Samsung/One UI, Bluetooth/AirPods, Vitreo and LibrePods behavior is not established by these software checks. The earlier intermittent Bluetooth silence report is not declared resolved. See the [native migration guide](ANDROID-NATIVE-MIGRATION.md) for data ownership limits and the remaining device acceptance matrix.
