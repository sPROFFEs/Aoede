# Android App

**Android 1.2.3 (33)** connects to **Aoede 1.3.1+** with native Kotlin screens and Media3 playback. Enter the backend root URL on first launch, then sign in with your Aoede account. The Android version is independent of the server release number.

## Install and update

1. Update the Aoede server to 1.3.1, including the downloader image.
2. Download [aoede-android.apk](https://github.com/sPROFFEs/Aoede/releases/latest/download/aoede-android.apk).
3. Allow installation from your browser/file manager when Android asks; install the APK.
4. Open Aoede and enter an HTTPS backend address, for example `https://aoede.example.com`.
5. Sign in. Use **More → Appearance & settings** for theme, language, playback and device options.

Android **5.0/API 21+** is supported. Normal native UI and playback do not require WebView. Explicit HTTP/custom ports remain available for trusted local deployments; use HTTPS to protect credentials and music in transit. TLS certificate failures are not bypassed.

Future updates use the existing official updater: versionCode, SHA-256, size, package identity and installed signing certificate are verified before offering the Android installer. Installation requires your confirmation; Android 8+ may ask to allow installs from Aoede. Cancellation preserves the installed app/data. No backend-supplied APK URL or session cookie is sent to GitHub.

### Server is already 1.3.1 but native sign-in asks for an update

The native client needs `/api/mobile/v2/context`. Without a session this route returns **401**, not 404. A 404 means the running backend or an intermediate proxy is not serving the native API; a version label alone does not establish that the new Python process has started.

A failed Compose build can leave new bind-mounted source files with the previous server process still running. Reapply the server update and confirm that it finishes recreating Concierge. The updater recovers pending services from retained failed-job history, including when Git is already current or a later downloader-only update succeeded. Administrators can reload the processes from the deployment's Compose directory with `docker compose restart concierge updater`. Image/dependency build failures still need a successful rebuild; restarting does not install missing dependencies. This does not require reinstalling the APK or deleting its device library.

## Native interface and playback

Home, discovery, search, collections, profiles, parties, lyrics, radio, Wrapped, device downloads, settings and administration use native views and authenticated JSON APIs. Classic, Glass and Vitrine follow the mobile web templates, final CSS values and original interface vectors. Native rendering still requires comparison on physical devices for fonts, clipping and animations. The web/PWA and desktop interfaces remain available independently.

One ExoPlayer owns normal music playback. The compact and fullscreen players, notification, optional island and home-screen widget control that same player. Native audio focus and becoming-noisy handling pause on interruptions/headphone disconnection. A paused party listener stays locally paused until an explicit play action. Normal activity/background transitions do not create another audio player. Wrapped temporarily owns transport and its story soundtrack/preview players; closing it releases them and preserves the personal queue paused.

The library remembers list/cover layout per account and backend on this device. Public profiles use compact 56 dp collection covers. The Home uses the existing account-scoped snapshot store, retains its current views during refresh, and performs cache writes outside the UI thread. It skips obsolete queued reads and avoids repainting unchanged sections. Fullscreen gestures follow the finger, settle back on cancellation and use shared carousel/dismissal transitions; Reduce effects and the system animator setting remain respected.

Activity recreation restores the current route and fullscreen player after validating the same account. Saved navigation state excludes credential drafts. File selection and asynchronous results remain bound to the initiating account and screen.

Swipe the compact player or fullscreen artwork horizontally for adjacent tracks; swipe the fullscreen artwork downward to minimize. Buttons and seeking retain separate touch handling; cancelled/multi-touch gestures do not skip songs. Reduced effects/motion disables decorative movement. The native UI has no browser zoom gesture. Final-track Next with repeat/shuffle off leaves playback intact.

Playback settings include loudness normalization, short fade-out/fade-in transitions and sleep timer. This is not overlapping two-player crossfade. Queue snapshots restore paused; restoring a session does not start competing playback automatically. Online features, federation and remote provider previews require a reachable backend.

## Offline music and account isolation

Download local library tracks or collections to this device. Files and artwork are stored privately with length/checksum validation and account/backend namespaces. Partial or failed downloads never replace a verified existing recording. Device deletion and server-library deletion are separate actions.

After a successful remembered sign-in, an unavailable backend offers **Device downloads** using the previously validated account. An explicit 401 clears the rejected session; offline entry does not impersonate another account. Password changes preserve the account's device namespace. A deleted/recreated username gets a different namespace.

Offline favorites and supported collection creation/add/rename changes are retained for synchronization. **Storage & migration → Pending offline changes** shows outstanding work. Unknown legacy operations and failed authorization remain pending for review; they are not discarded or replayed into another account.

## Moving from the installed web client

The upgrade retains the old IndexedDB database. Native SQLite cannot directly read WebView's database files. Use **Appearance & settings → Storage & migration → Import previous installed library** while the original account can still be verified.

The importer temporarily opens a network-blocked WebView only to read the old database. It imports tracks referenced by that account's collection snapshots, artwork and supported pending changes. Large records/audio are transferred in bounded chunks. It never deletes the source database. Unassigned old single-track downloads and data without a verifiable previous account association remain available for manual review in compatibility mode.

To inspect the original interface, choose **Appearance & settings → Installed web client · compatibility**. From login, use **Configure server** and disable **Native Android interface**. Switching stops the current engine before opening the other client. Switch back through server configuration. A recent Android System WebView is needed only for compatibility mode or legacy import.

Changing client mode clears that backend's native remembered session. Entering compatibility mode asks for a fresh sign-in; returning to native mode can import only the browser's currently verified account. Artwork follows the active client's session rather than a session retained by the other client. Device libraries are preserved.

## Dynamic Island and widgets

The optional island remains under **Appearance & settings → Dynamic Island · this device**. Enable it and approve Android's **Display over other apps** permission. It is off by default and configured per device. The editor retains size/position controls, one-unit arrows, live preview and configurable lower handle/touch area. Android owns status-bar touches; the handle is the accessible alternative below it.

The island hides while Aoede is in the foreground and can appear while minimized/background playback is alive. Expanded controls share transport, favorite, shuffle, repeat and seek behavior with the player. Tap outside to collapse; opening Aoede preserves the same audio owner. Offline artwork comes from the saved device recording.

The common editor in Android 1.2.3 supports an 8–160 dp touch extension, with 1 dp arrows and live preview, in both native and installed-web compatibility modes. The capsule and visible handle remain part of the touch area; the previous backup APKs retain their original editor. A native task now keeps the island while paused in the background, until the task is dismissed or the process ends.

Native frequency bars read five broad PCM bands across every channel; toggling/locking the island never creates or closes an audio capture stream. Unsupported analysis uses the existing decorative fallback. No microphone, accessibility or notification-reading permission is requested. Android/OEM keyguard policy may hide application overlays; native media-session controls remain the supported lock-screen controls.

Add the Aoede widget through your launcher's widget picker. It uses the shared native media session and artwork. Android killing/force-stopping the process ends playback and overlays; the app does not restart playback or reconnect Bluetooth to hide a failure.

## Release artifacts

| File | Purpose |
|---|---|
| `aoede-android.apk` | Current signed native client, Android 1.2.3/code 33. |
| `aoede-android-source.zip` | Complete Android project, tests, editable compatibility frontend and compiler. |
| `aoede-android-native-1.2.2.apk` / `-source.zip` | Previous native client backup. |
| `aoede-android-native-1.2.1.apk` / `-source.zip` | Previous native client backup. |
| `aoede-android-native-1.2.0.apk` / `-source.zip` | Previous native client backup. |
| `aoede-android-webview-1.1.22.apk` / `-source.zip` | Previous installed web client backup. |
| `aoede-android-wrapper-1.0.5.apk` / `-source.zip` | Original remote-wrapper backup. |

Desktop 1.2.10 archives are unchanged. Android backups share the same package identity and cannot be installed alongside the current client. A version downgrade normally requires uninstalling, which removes local app data; prefer the in-app compatibility option when examining old downloads.

See [native architecture and migration limits](ANDROID-NATIVE-MIGRATION.md), [security review](SECURITY-REVIEW-1.3.1.md) and [release notes](RELEASE-1.3.1.md). Physical Bluetooth, OEM/GrapheneOS, Vitreo and LibrePods acceptance must still be checked on the affected devices; software tests do not establish that the earlier intermittent silence is resolved.
