# Android App

Aoede Android 1.1.1 installs the player interface, JavaScript modules, themes, translations and icons inside the APK. A native Android host adds media controls, home-screen widgets, system file selection and device insets. The interface uses WebView; music and account data come from your chosen Aoede backend. Server-rendered settings/admin forms still need a connection.

## Release artifacts

The project documents two Android artifacts alongside releases:

- `aoede-android.apk` — installable APK.
- `aoede-android-source.zip` — complete Android Studio project, frontend compiler and editable frontend sources.
- `aoede-android-wrapper-1.0.5.apk` and `aoede-android-wrapper-1.0.5-source.zip` — preserved original remote-wrapper backup, for servers without the new bootstrap API.

The backup and new client use the same Android package identity. They cannot be installed side by side; Android normally requires uninstalling the newer version before reinstalling an older APK, removing local app data. Download any needed backup before switching.

Release page:

```text
https://github.com/sPROFFEs/Aoede/releases
```

## Requirements

Android **5.0+**, a current Android System WebView and a backend exposing `/api/mobile/bootstrap` protocol 1 are required. Update the Aoede backend alongside this APK. An older backend displays an update instruction; a connection failure offers Retry and server configuration.

The bootstrap endpoint uses the existing signed session cookie and checks expiry, revocation, account activity, service-account exclusion and session version. It returns only the authenticated account's display context and the same shared quota shown in the portal, never passwords, tokens, provider credentials or another user's profile. Responses are private and cannot be cached. Invalid sessions return 401; a session-verification outage returns a generic 503 without logging the user out. Browser origin policy/CORS still governs reads, and the Android bridge accepts only the configured origin's main frame, including its exact port.

## Install

1. Download `aoede-android.apk` to the device.
2. If Android asks, allow **Install unknown apps** for the browser or file manager you used.
3. Open the APK and install it.
4. Launch Aoede.
5. Enter your Aoede server URL, for example:

   ```text
   https://aoede.example.com
   ```

6. Connect and sign in with the same credentials as the web application.

## Features

The application provides:

- an installed frontend with Classic, Glass and Vitrine themes, without startup CDN requests;
- edge-to-edge phone and touch-landscape layouts with safe controls around cutouts and system bars;
- a dock that adapts to playback and the keyboard, compact Vitrine spacing and a mobile More menu;
- Android Back dismissing dialogs, fullscreen playback and menus before navigating;
- an **Aoede player** home-screen widget with cover, title, artist and transport controls;
- native system media notification;
- album artwork, title and artist in the notification;
- play / pause / next / previous media actions;
- background playback that continues when the screen is off or the app is not foregrounded;
- headphone/Bluetooth disconnection pause, including a local pause in party rooms;
- offline startup with the last validated account and existing downloaded tracks;
- configurable server URL from login, connection errors and user settings;
- a device-local **Reduce visual effects** option, enabled by default on low-memory devices.

Add the widget from your launcher's widget picker. During playback, controls operate in the background; when the player process is absent, a tap opens Aoede. Force-stopping the application ends playback. Music that has not been downloaded and online/federation/admin functions require the backend.

The APK keeps the backend's original origin, scheme and port for authentication, API calls, streaming and uploads. HTTP can be explicitly configured, while invalid HTTPS certificates remain blocked. Upgrading 1.0.4/1.0.5 preserves the package identity and distribution signature, login cookies and downloaded files. APK 1.0.3 used a lost debug signing key and still needs uninstalling first.

## Changing server URL

Use **Configure server** in user settings or the gear on the login/connection screen. Enter the root URL, including a custom port if needed; do not include `/portal` or another path. Saving reloads the player. Theme preferences remain attached to the backend account, while effects preferences stay on this device.

Build instructions, signing configuration and device acceptance checks are in [the Android project guide](../android/README.md).

## README screenshot recommendation

Use a portrait capture that shows the actual Aoede player, not the Android launcher. Avoid notification content with personal names, private domains or sensitive album/library metadata you do not want public.

See [Screenshot Guide](SCREENSHOTS.md).
