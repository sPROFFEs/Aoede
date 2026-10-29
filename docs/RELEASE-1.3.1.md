# Aoede 1.3.1

Android 1.2.3 polishes headers, player gestures, volume and public profiles in the native and installed-web clients, restores saved library layout and improves Home refreshes. The island stays available while native playback is paused and has a smaller, precisely adjustable touch extension.

## Android 1.2.3 · build 33

- Vitrine headers have an opaque background. Native and web volume thumbs stay inside their tracks at both ends in all three themes.
- The native library remembers list/cover layout per account and backend. Public profiles use compact collection rows with 56 px/dp artwork, names and metadata. Collection links and federation references are retained.
- Home snapshots now use the supported account-scoped store. Cache writes run outside the UI thread; an already open Home retains its views, coalesces active refreshes and repaints only changed sections. Obsolete queued Home requests are skipped. A late web playback-session restore also preserves a newer user-selected view.
- Cover pickers show each local song's actual protected artwork. Native sheets use image rows; web pickers use canonical cover URLs with a valid fallback and escaped labels.
- A live native task keeps the optional island when playback pauses. Dismissing a paused task removes it, while the app still hides it in the foreground.
- Native spectrum bars use broad-band RMS across both stereo channels in the existing PCM processor. Sampling leaves unmodified playback bytes intact and does not rebuild the sink or change the audio route. Unavailable analysis retains the decorative fallback.
- The shared island editor allows an 8–160 dp touch extension, 1 dp arrow adjustments and live preview in both client modes. The capsule and visible handle remain touchable; the default extension is still 48 dp. Historical backup APKs are unchanged.
- Fullscreen gestures lock their direction, drag the complete player for dismissal and use coherent settling/carousel transitions across themes. Cancelled gestures return to their starting state; reduced-motion settings remain respected.

## Android 1.2.2 · build 32

- Covers share a bounded decoded-image cache within the active account/backend. Concurrent requests are coalesced, local fallback redirects are followed without forwarding credentials off-origin, and offline data images decode outside the UI thread. Failed or HTML responses retain a placeholder.
- Stationary translucent surfaces reuse their scene instead of scheduling continuous full-screen captures. Android 12+ uses a cropped GPU backdrop for hardware-rendered windows; older Android and software captures retain the sampled fallback. Position updates reuse unchanged vectors and text. Queue changes use a revision counter rather than serializing the entire queue on each player refresh.
- Classic and Vitrine navigation materials extend behind Android's gesture/button area with safe padding for controls. Glass keeps its floating offset. Keyboard resizing, fullscreen player bounds and navigation contrast no longer introduce a separate bottom band. Home quick-access rows no longer add an extra final gap.
- Classic, Glass and Vitrine player geometry follows the final mobile CSS: theme-specific text, artwork sizing/mask, materials, spacing, responsive transport controls and overflow behavior. Buttons discard Android's implicit elevation and retain the web themes' shapes.
- Playlist/album/mix titles keep their natural text width in both the web and native clients, including padding; short names no longer wrap their final character. Closing a replaced native interface cannot detach the current interface's playback callbacks.
- Refreshing retains content while fetching; unchanged-page reuse has an explicit layout key so changing a library or monitor tab cannot retain the wrong screen.
- Server check/apply operations open their actual job progress, including phases, percentage and completion/failure. The protected job response can hand native monitoring to the existing updater while Concierge restarts; its capability stays in memory and never enters navigation state. Library maintenance uses the same visible job flow.
- The administrative library keeps its search field and results together, plays a tapped recording and exposes deletion separately. Duplicate results retain match-type/text filtering, recommended copies, playback comparison and explicit deletion.
- Smart playlists include all web rule fields, preview, editing and refresh. Collection actions include choosing a local track as cover and syncing an existing copy from its source.
- Cover uploads send the actual validated image content type. Changing a cover or avatar invalidates the native artwork cache.
- Outbound federation peers render the actual array response and support issuing, copying, revoking and deleting tokens through the existing protected controllers. The one-time token sheet blocks screenshots and excludes its text from saved state.
- Login restriction information is available beside each account without leaving the user list.


## Android 1.2.1 · build 31

- Reworked native Home, library, collection heroes, discovery, offline downloads, public profiles, parties and account settings. The three themes retain their own navigation order, spacing, controls and materials. The application icon is unchanged.
- Fullscreen playback uses the web player layouts, animated artwork transitions, horizontal track gestures and downward dismissal. Normal screens, audio and verification controls remain native; no WebView is mounted underneath them.
- Glass surfaces sample one scene with density-aware blur and correct coordinates across dialog windows. Rounded theme tabs, dialogs, track actions, touch bounds, keyboard handling and artwork sizing follow the web styles. Detached controls release their theme bindings.
- Downloads, import previews, playlist selection and collaborators open as dialogs. Downloads preserve the URL form during polling; closing, backgrounding or navigating away stops stale refreshes. Federated playlist permissions and leaving a collaboration use the existing protected controllers.
- Wrapped reads the server's actual Top Songs playlist, artists, monthly sprint and party rankings. Its native story has progress, pause/resume, previous/next, an Aoede intro, audio previews and a working final summary/save action. Temporary story controls preserve the personal queue paused and release their players on exit.
- Manual queue entries can be removed or reordered without replacing the playback engine. Queue synchronization uses the same manual/context fields as the web client and restores paused.
- Activity recreation restores the current page and fullscreen player for the validated account without saving credential drafts. Delayed requests and file selections cannot act on another account or replace a newer page.
- Replacing button press animations finishes the previous platform animator first, avoiding stale animation frames on Android 5.0/5.1, including when Reduce effects is enabled.
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

`aoede-android.apk` and `aoede-android-source.zip` contain Android 1.2.3/code 33. Android 1.2.2 is retained as `aoede-android-native-1.2.2.apk` and its matching complete source archive. Android 1.2.1 is retained as `aoede-android-native-1.2.1.apk` and its matching source archive. Android 1.2.0 is retained as `aoede-android-native-1.2.0.apk` and `aoede-android-native-1.2.0-source.zip`. The release also preserves Android 1.1.22 as `aoede-android-webview-1.1.22.apk` and its complete source, the original 1.0.5 wrapper APK/source, and all six Desktop 1.2.10 archives unchanged. Desktop wrappers and the original Android wrapper do not need recompilation.

The current distribution signature and package identity are retained. Prefer the in-app compatibility option when checking old device data. Installing a lower-version backup normally requires uninstalling and removes app data; backups cannot be installed alongside the current client.

## Validation and limits

The full repository check passed with **403 Python tests passed and 2 skipped**, alongside the JavaScript suites. Android includes **168 JVM checks**, release lint and release assembly. Browser checks cover three themes in light/dark mode, desktop sidebar alignment, 108 collection-title cases, compact public-profile artwork, volume endpoints and private verification keys. Opt-in native captures assert the active theme and exercise the same synthetic routes, dialogs, updater restart and Wrapped completion using real inset handling. The complete source archive is checked for standalone frontend/icon generation. Local shell lint was unavailable; CI enforces it.

The Android 1.2.2 debug build also authenticated, loaded redirected covers and played a synthetic recording with advancing media position on an isolated API 26 emulator with WebView disabled. Media-session next/pause controls and fullscreen drawing behind three-button navigation were checked. The fixture audio is silent; this does not verify audible Bluetooth output, release-only behavior, modern GPU rendering or uninterrupted playback on a physical device.

Physical Pixel/GrapheneOS, Samsung/One UI, Bluetooth/AirPods, Vitreo and LibrePods behavior is not established by these software checks. The earlier intermittent Bluetooth silence report is not declared resolved. See the [native migration guide](ANDROID-NATIVE-MIGRATION.md) for data ownership limits and the remaining device acceptance matrix.
