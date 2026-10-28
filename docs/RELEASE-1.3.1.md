# Aoede 1.3.1

Android now follows the mobile web layouts for Classic, Glass and Vitrine, using the web templates, final CSS values and original interface vectors as the source. This update also completes native Wrapped, collection dialogs and queue controls, improves private verification input, and fixes the desktop library sidebar alignment.

## Android 1.2.1 · build 31

- Reworked native Home, library, collection heroes, discovery, offline downloads, public profiles, parties and account settings. The three themes retain their own navigation order, spacing, controls and materials. The application icon is unchanged.
- Fullscreen playback uses the web player layouts, animated artwork transitions, horizontal track gestures and downward dismissal. Normal screens, audio and verification controls remain native; no WebView is mounted underneath them.
- Glass surfaces sample one scene with density-aware blur and correct coordinates across dialog windows. Rounded theme tabs, dialogs, track actions, touch bounds, keyboard handling and artwork sizing follow the web styles. Detached controls release their theme bindings.
- Downloads, import previews, playlist selection and collaborators open as dialogs. Downloads preserve the URL form during polling; closing, backgrounding or navigating away stops stale refreshes. Federated playlist permissions and leaving a collaboration use the existing protected controllers.
- Wrapped reads the server's actual Top Songs playlist, artists, monthly sprint and party rankings. Its native story has progress, pause/resume, previous/next, an Aoede intro, audio previews and a working final summary/save action. Temporary story controls preserve the personal queue paused and release their players on exit.
- Manual queue entries can be removed or reordered without replacing the playback engine. Queue synchronization uses the same manual/context fields as the web client and restores paused.
- Activity recreation restores the current page and fullscreen player for the validated account without saving credential drafts. Delayed requests and file selections cannot act on another account or replace a newer page.
- Party controls cannot change personal shuffle/repeat. Favorite controls respect the existing local-library API and stay disabled for radio and federated tracks.
- Server monitor backup and Wrapped forms retain their existing values, use native date/time pickers, and expose regeneration, reset and job status. Operational build fields remain administrator-only and exclude private paths and raw job output.
- Private provider verification includes Space, Enter, navigation and selection keys. These stay within the existing owner-bound, authenticated input allowlist.
- The collapsed desktop library keeps its covers centered and its scrollbar hidden. Existing desktop and remote wrappers remain available unchanged.

## Android 1.2.0 · build 30

- Native browsing, search, playlists, albums, favorites, discovery, profiles, lyrics, radio, Wrapped, parties, settings and administration connect to the existing authenticated backend. Normal native navigation and playback do not load a WebView.
- One Media3 player serves the application, notification, home-screen widgets and optional Dynamic Island. Audio focus, headphone disconnection and background playback use Android's media APIs. Island frequency sampling reads the same PCM pipeline without creating or closing an audio capture stream.
- The playback service posts its initial media notification before metadata work, avoids duplicate startup requests and defers a native widget resume until its foreground service exists. A joined live party retains its session while waiting for the host, without holding an audio wake lock while paused.
- The compact and fullscreen players support track gestures, seeking, volume, shuffle/repeat, sleep timer and optional normalization/fades. Final-track Next keeps the current player intact.
- Device downloads use app-private files, checksums, bounded artwork and account/node namespaces. Saved queues restore paused. Offline favorites and supported collection changes can synchronize after reconnection.
- Queue teardown, party restoration and queued requests retain their original account identity during a sign-in change; clearing a session safely stops its player and outstanding device-download work.
- Switching between native and compatibility clients resets remembered sessions for that backend. Compatibility requires a fresh sign-in, and artwork uses the active client's credentials.
- A separate compatibility option retains the previous installed web interface. Original IndexedDB data is preserved. Explicit migration imports only collections attributable to a verified previous account session; unassigned old downloads remain available in compatibility mode.
- Provider verification is available to administrators through a private native screenshot/input viewer. The remote browser stays in the downloader worker; its ports and debugging protocol are not exposed to the phone.

Update the server to **1.3.1 before using the native interface**. Android remains **API 21+**. WebView is needed only for the optional compatibility client or an explicit legacy IndexedDB import. The native interface translates the mobile web layouts into Android views and bundled design values. Physical font rendering, clipping and animation smoothness require device comparison; software captures alone do not establish pixel-identical rendering on every phone.

## Server and security

- New `/api/mobile/v2` routes reuse the signed session, revocation, account activity, administrator authorization and same-origin mutation checks. Settings responses never return stored provider secrets.
- A stable random account identity isolates device libraries across accounts and recreated usernames while preserving access after a password change. Newly issued sessions bind that identity; old sessions remain subject to existing expiry/version/revocation checks.
- Native collection creation supports atomic idempotent retries. Spotify previews can use the existing protected media relay without forwarding the native session to a CDN.
- Database migrations are additive. Back up the database before updating; deploy the concierge and downloader changes together.

## Downloads and compatibility

`aoede-android.apk` and `aoede-android-source.zip` contain Android 1.2.1/code 31. Android 1.2.0 is retained as `aoede-android-native-1.2.0.apk` and `aoede-android-native-1.2.0-source.zip`. The release also preserves Android 1.1.22 as `aoede-android-webview-1.1.22.apk` and its complete source, the original 1.0.5 wrapper APK/source, and all six Desktop 1.2.10 archives unchanged. Desktop wrappers and the original Android wrapper do not need recompilation.

The current distribution signature and package identity are retained. Prefer the in-app compatibility option when checking old device data. Installing a lower-version backup normally requires uninstalling and removes app data; backups cannot be installed alongside the current client.

## Validation and limits

The full repository check passed with **402 Python tests passed and 2 skipped**, alongside the JavaScript suites. Android passed **138 JVM tests**, release lint with zero errors, and release assembly. Browser checks cover three themes in light/dark mode, desktop sidebar alignment and private verification keys. Opt-in native captures exercise the same synthetic routes, dialogs and Wrapped completion. The complete source archive is checked for standalone frontend/icon generation. Local shell lint was unavailable; CI enforces it.

The earlier Android 1.2.0 native client opened, authenticated and played a test recording on an isolated API 26 emulator with its WebView provider disabled. That run is not evidence for every current layout or uninterrupted playback.

Physical Pixel/GrapheneOS, Samsung/One UI, Bluetooth/AirPods, Vitreo and LibrePods behavior is not established by these software checks. The earlier intermittent Bluetooth silence report is not declared resolved. See the [native migration guide](ANDROID-NATIVE-MIGRATION.md) for data ownership limits and the remaining device acceptance matrix.
