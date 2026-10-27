# Android without WebView: migration proposal

Status: proposed, not implemented. Aoede server remains **1.3.0**; Android **1.1.22 (29)** still packages the shared web frontend. This delivery finishes UI fixes and gestures on that client. It does not turn HTML into native screens.

A fully native client is feasible. Its main benefit would be direct control over Android layout, gestures, accessibility, playback and downloads. It requires replacing both the interface and the JavaScript player. Putting the current WebView inside Compose would retain the current engine and its constraints.

## Current boundaries

| Responsibility | Current implementation | Native destination |
|---|---|---|
| Setup and backend origin | `SetupActivity.kt`, `MobileFrontend.kt`, bootstrap protocol 1 | Retain setup and exact scheme/host/port validation; use a native authenticated session repository. |
| Screens and themes | Bundled templates, CSS and JavaScript; some settings/admin forms fetched from the server | Kotlin/Compose screens and navigation, with Classic, Glass and Vitrine visual specifications. |
| Playback and queue | `player.js`, `audio_engine.js`, retained WebView in `NavipodApp.kt` | One Media3 player owned by a media session service; one authoritative queue and playback state. |
| System integration | `PlaybackService.kt`, `MediaController.kt`, `PlaybackWidget.kt`, `MusicIsland.kt` | Reuse relevant Kotlin UI, geometry and permission handling; replace the JavaScript bridge with the native media controller. |
| Offline | IndexedDB metadata, snapshots, audio/artwork blobs and pending mutations | Room metadata and app-private files, with Media3 download infrastructure and account-scoped synchronization. |
| Updates | `AppUpdater.kt`, distribution signature and package identity | Retain certificate/hash checks, installer confirmation and independent Android versioning. |

These destinations are an Aoede design proposal. Android documents [incremental Compose migration](https://developer.android.com/develop/ui/compose/migrate/strategy), [service-owned background playback](https://developer.android.com/media/media3/session/background-playback) and [persistent media downloads](https://developer.android.com/media/media3/exoplayer/downloading-media). The documentation supplies platform building blocks, not automatic parity with Aoede's current features.

## Delivery stages and gates

1. **Inventory and stabilize the API contract.** Map every current screen/action to an authenticated endpoint and its permissions. Document pagination, errors, capabilities, retries, local/federated identifiers and offline behavior. Identify settings, admin, downloader sessions and Wrapped pages that still depend on server HTML. Add typed JSON contracts for these before promising a client with no WebView. Keep old clients supported through capability negotiation.
2. **Build one complete native path.** Backend selection, login/logout, Home, library, collection details, queue, streaming and fullscreen controls. Use Compose and Media3 with screen state separate from playback state. Validate authenticated streaming, byte ranges, seeks, redirects and supported codecs against the actual backend. Only one engine may own playback at a time; do not keep a silent WebView player and ExoPlayer running together.
3. **Move playback integrations and offline.** Connect notification, widgets, island, headset buttons, audio focus, becoming-noisy pause and foreground playback to the same media session. Preserve explicit user pauses and party rules. Implement downloads, interrupted transfers, quota, storage eviction and pending mutations. Native spectrum analysis can use player PCM processing where available, with the existing animation fallback for unsupported sources; no microphone permission is needed for that design.
4. **Complete product parity.** Search and imports, local/federated tracks and collections, public profiles, favorites, cross-node collaborators and parties, lyrics/timing, radio/previews, playback modes and volume, account/admin settings, downloader, updates and Wrapped. Implement native rendering/navigation for remaining server pages, or explicitly defer native release until they are covered. Apply theme-specific typography, materials, insets, density, accessibility and motion; do not translate CSS pixel values mechanically into dp.
5. **Migrate and release gradually.** Preserve `com.aoede.twa`, signing certificate and increasing version codes. Transfer the existing backend choice, authenticated session and preferences through a narrowly scoped migration. Export IndexedDB metadata/audio/artwork/pending actions using the old client before removing WebView, then import and verify file counts and checksums. Keep the old database until a successful migration and rollback decision; an APK upgrade does not automatically convert IndexedDB into Room. Test storage pressure and cancellation. Retain the current wrapper APK/source as a documented manual backup, not an automatic downgrade that can lose app data.

The first release gate is the complete path in stage 2. It proves the API and playback model before rebuilding every screen. A schedule needs that inventory and a device-tested prototype; this is a client rewrite, not a small wrapper change.

## Security and compatibility requirements

- Preserve exact backend origin/port checks, TLS certificate validation, session expiry/revocation, CSRF protections where cookie authentication is used, and server authorization on every mutation. Store session material in Android-protected storage; do not place it in logs or download links.
- Keep one account/node's downloads, queue, cached profiles and pending actions separate from another's. Switching backend or account must not replay another user's queued mutations. Model federated IDs explicitly instead of assuming every ID is a local integer.
- Authenticate media and images without leaking cookies to redirected third-party hosts. Keep offline deletion distinct from server deletion. Review any new API separately from UI work.
- Retain supported Android/device policy deliberately. Check Compose/Media3 minimum SDKs at implementation time and decide how Android 5/6 users retain access; do not silently remove current support.

## Acceptance matrix

| Area | Required evidence before replacing the current APK |
|---|---|
| Playback | Continuous background listening, rapid lock/unlock, real Bluetooth/headphone route changes, interruptions, final-track Next, repeat/shuffle, seeks and track changes. No restart/reconnection or pause/play loop hiding failures. |
| Devices | Physical Pixel/GrapheneOS and Samsung/One UI, relevant WebView-era migration installs, native and Vitreo lock screens, LibrePods/AirPods comparison, low memory and battery restrictions. Record installed OS/client/accessory versions. |
| Island and widgets | Correct track/artwork offline and online, expansion/dismissal, foreground hiding, task-preserving opening, precise editor controls and no second audio owner. |
| Offline and federation | Cold launch without network, partial/cancelled downloads, migration interruption and storage shortage; remote parties, collaborators, authorization failures and older-node capability errors. |
| UI | Three themes, font scaling, screen reader labels/focus, reduced motion/effects, cutouts, landscape, compact/full players and gesture conflicts. |
| Performance | Compare measured cold start, time to first track, track transition gaps, dropped frames, RAM and battery against the current client on the same devices/catalog/network. Native architecture alone is not evidence of improvement. |
| Release | Upgrade installed APK in place, retain downloads/session where valid, verify signature, complete source archive, update notice, staged rollback and unchanged wrapper backups. |

Browser and JVM tests support the current UI delivery. They do not establish the physical-device or Bluetooth results above, and no complete native migration is claimed by release 1.3.0.
