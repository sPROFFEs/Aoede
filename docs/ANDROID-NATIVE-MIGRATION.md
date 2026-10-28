# Native Android implementation and migration

Status: Android **1.2.1/code 31**, server **1.3.1**. Native Kotlin Views and Media3 replace the normal WebView UI and JavaScript audio owner. The previous installed web client remains an explicit compatibility option; it is not silently run underneath native playback.

## Ownership map

| Responsibility | Native implementation |
|---|---|
| Setup/navigation/login/themes | `SetupActivity.kt`, `NativeUi.kt`, `NativeTools.kt`, `NativeIcon.kt`; Android views, insets, accessibility labels, font scaling and system file selection. |
| Authentication/HTTP | `NativeApi.kt`: configured-origin requests, existing signed session, private encrypted vault and bounded responses. `/api/mobile/v2/context` validates protocol/account/storage namespace. |
| Audio/queue | `NativePlayback.kt`: one normal music player, queue, focus/noisy handling, volume, fades/normalization and listening events. `NativeAudioProcessor.kt` samples the same PCM sink for the island without touching the audio route. Wrapped temporarily owns transport and scoped soundtrack/preview players, which are released on exit. |
| System controls | Existing `MediaController.kt`, `PlaybackService.kt`, `PlaybackWidget.kt`, `MusicIsland.kt` share native commands/metadata. The activity can detach without destroying playback. |
| Offline | `NativeLibrary.kt`: account/node-scoped SQLite records and hashed private audio paths. `NativeDownloadService.kt`: explicit user-initiated foreground download work. `NativeSync.kt`: known pending operations and authenticated retry. |
| Parties | `NativeParty.kt`: existing local/federated join/queue/control/stream APIs and bounded SSE, loading/ready synchronization, local pause and restoration of the personal queue paused. |
| Legacy import | `NativeMigration.kt`, `mobile/native-migration.js`: explicit temporary network-blocked WebView with verified owner, exact origin/main frame, nonce, bounded chunks and retained source database. |
| Updates | Existing `AppUpdater.kt`: fixed official source, version/package/hash/certificate checks and installer confirmation. |

Views reuse the existing AppCompat stack and preserve API 21 support. Media3 1.8.1 and Kotlin 2.0.21 are pinned in the build. The implementation does not need Compose or a second media session service. Android documents [background playback](https://developer.android.com/media/media3/session/background-playback); Aoede retains its existing foreground service/native MediaSessionCompat integration around the one native engine.

## Mobile UI reference and verification

The final mobile web CSS cascade is the reference for Classic, Glass and Vitrine. `NativeDesign.kt`, `NativeUi.kt`, `NativeTools.kt`, `NativePlayerView.kt` and the native Wrapped views translate those layouts into Android views. `android/build_icons.mjs` extracts the existing web vectors into `native/icons.json`; `--check` rejects stale geometry in the normal quality checks and Android workflow. The application icon is unchanged.

`android/visual_reference.py` serves the real web templates and assets on **127.0.0.1:8765** with synthetic accounts, tracks, covers and lyrics. It does not connect to a deployment. With the optional Playwright and Pillow development packages installed:

```sh
python android/visual_reference.py
# In another terminal:
python android/capture_web_reference.py --output /tmp/aoede-web-reference
AOEDE_NATIVE_PREVIEWS=/tmp/aoede-native-reference ./android/gradlew -p android testDebugUnitTest
```

The browser capture checks expanded/collapsed desktop sidebar alignment and scrolling, then captures the mobile themes in light and dark mode. `--sidebar-only` runs just the sidebar check. `--write-theme-tokens` explicitly updates `native/themes.json` from computed browser colors. Normal builds use the reviewed JSON and do not need a browser or the fixture server. The tokens include modal margins, borders and materials, panel typography and the theme-specific tab shapes from the final CSS cascade. `--tokens-only` reads these values without capturing images.

The opt-in Android capture renders the actual Android views against the same fixture. The regular tests also cover safe-area placement, short and narrow player layouts, navigation history, account-bound route restoration without credential drafts, text scaling, offline filters, image sampling, scoped dialogs, theme-binding cleanup, Wrapped response parsing, temporary transport ownership and manual/context queue round-trips. Party transport cannot change personal shuffle/repeat; favorites are available only for local library tracks, matching the existing server API. The optional fixture run exercises Wrapped through its final slide and return to summary, dialog loading, radio searches, background/closed polling and stale mutation responses. Screenshot rendering in Robolectric does not reproduce Android's outline clipping and shadows; rounded avatars, gestures, system bars and animations additionally need an emulator/device check. A screenshot capture alone does not establish complete visual or playback parity.

## Server contract and security

`/api/mobile/v2` adds sanitized context/settings/integration responses, administrator user/system/deletion-request data, and an owner-bound private verification viewer. Authentication, session revocation/account restrictions, CSRF and administrator checks remain centralized. There is no username-only mobile token or public administrative endpoint. Existing music, collection, download and federation controllers continue to enforce their permissions.

Database migrations 036/037 add atomic native collection-operation records and stable random account identities. The account identity is a namespace input, not a credential. Password changes preserve it; recreating an account changes it. New session tokens bind it, while legacy sessions still undergo version/expiry/revocation checks. [Security boundaries and checks](SECURITY-REVIEW-1.3.1.md).

Update both concierge and downloader code/images together. Take the normal server backup first. No external database or identity provider is introduced.

## Data transition

The old WebView database contains globally stored tracks/audio and username-scoped collection snapshots. It cannot safely be treated as a fully account-owned store. Native migration therefore requires a verified previous account session and imports tracks reachable through that account's collection snapshots. It does not assign all old audio to whichever user logs in next.

The original database remains intact on success or failure. Unassigned single downloads, expired/unverifiable previous ownership and unsupported pending actions need manual review through compatibility mode. Imported legacy mutations require review before replay. Large collection records and audio are chunked; oversized/invalid/incomplete records fail without marking the migration complete.

Native session storage is encrypted and excluded from backup; native audio/SQLite stays private and is also excluded from transfer. Remember-me governs persistence. Offline namespace mapping uses exact backend origin and stable account identity. Explicit logout, changing account or changing backend never replays another account's pending work.

## Acceptance evidence and remaining device work

| Area | Evidence / remaining acceptance |
|---|---|
| API/security | Tests for unauthenticated, inactive/revoked/wrong-identity and non-admin access; CSRF; cross-admin viewer ownership/expiry; DTO limits; private-secret exclusion; account-scoped idempotency and additive migrations. |
| Audio/storage | JVM checks cover exact-origin/range requests and redirect/HTML rejection; final-track Next; paused restoration; PCM sampling pass-through; bounded gain; scoped files; failed replacement. Node checks cover large Unicode imports, chunk limits, ownership and retention. |
| UI | Browser login checks cover Classic/Glass/Vitrine, light/dark, phone/landscape/desktop, touch bounds, password visibility and form submission. The initial 1.2.0 native login/catalog/playback ran on an isolated API 26 emulator with WebView disabled. Current layouts are exercised by the native fixture run; that older emulator run is not evidence for all current layouts. |
| Physical devices | Still required: Pixel/GrapheneOS and Samsung/One UI; real focus interruptions/headphone disconnection, Bluetooth/AirPods, native/Vitreo lock screens, LibrePods comparison, overlay restrictions and low-memory/battery policy. Record installed versions. |
| Migration/release | Distribution upgrade compatibility is checked by CI certificate comparison. Real installed libraries, very large collections, storage exhaustion, interrupted import/download and explicit compatibility switching need device acceptance. |
| Federation | Existing backend federation tests remain. Multi-node native-party latency/clock and collaborator workflows need real deployment comparison, including older-node errors. |
| Performance/appearance | Native views remove browser zoom/rendering, but no broad battery/startup/frame-time improvement is claimed without measurements. Native themes retain account colors and controls; web-derived material tokens and native backdrop sampling are used. The native Wrapped story has its own scoped clock and audio previews. Physical font rendering, clipping and animation smoothness still require device comparison. |

The previous native 1.2.0 APK/source, WebView 1.1.22 APK/source and original wrapper remain release backups. Prefer the in-place compatibility option for old device data; an Android version downgrade normally requires uninstalling. The intermittent Bluetooth silence previously reported with Vitreo/LibrePods is not considered reproduced or resolved by emulator/JVM results.

The unaccelerated headless API 26 emulator also exhibited System UI ANRs and slow/unresponsive system-service commands during later media/navigation checks. That run is evidence for native login/catalog/basic playback only; it does not establish uninterrupted playback, lock/unlock, island or Bluetooth acceptance. The origin of those emulator failures has not been attributed to Aoede or ruled out as emulator/OS behavior.
