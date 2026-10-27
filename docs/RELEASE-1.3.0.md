# Aoede 1.3.0 — A cleaner music experience

Aoede 1.3 refreshes the interface across the web app and packaged Android client: Spotify inspired Classic, Apple Music inspired Vitrine, clearer navigation and collection controls, and smoother shared interactions. Glass remains available, and Aoede's application icon stays unchanged.

## Interface and interaction

- Refine Home, library, search, profiles, settings, administration and download management with consistent spacing, typography, artwork sizes, tabs and touch targets.
- Give Classic distinct charcoal panes and filled playback controls. Vitrine uses an open library canvas, red accent, quiet separators and restrained translucent chrome.
- Keep Play, Shuffle and offline download visible in collection details. Move rename, cover selection, sharing, collaboration, copy/sync and deletion into a named overflow menu, presented as a sheet on phones.
- Keep collection menus reachable in short desktop windows and touch landscape. Outside clicks, Escape and Android Back dismiss them.
- Apply the shared focus trap and Escape/return-focus behavior to older dialogs, including renaming and party creation.
- Honor scoped icon updates and retain existing SVGs. Frequent control updates no longer redraw icons across the entire page; switching themes preserves their geometry and filled favorite state.
- Remove universal transitions and repeated content entrance fades. Player and menu transitions remain short and respect reduced motion; Android Reduce effects remains available.
- Load interface fonts and compatibility icons locally. Updated PWA shell caching includes the refreshed interface.

## Android 1.1.21 (28)

The native APK includes the refreshed frontend, updated server setup styling and consistent Back behavior for dialogs. The existing distribution signature is retained for in-place updates. APK and complete editable source are provided below.

Playback continues to use the same retained WebView, queue and native media integration. Offline listening, home-screen widgets, the optional Dynamic Island, track swipes, background playback and the 1.1.20 analyser cleanup fixes are retained. This interface release adds no Bluetooth reconnection, process restart or automatic pause/play recovery loop.

## Versions and downloads

- **Aoede server/source:** 1.3.0; the release tag points to the matching server version.
- **Native Android:** 1.1.21, version code 28; new signed APK and source archive.
- **Desktop wrappers:** 1.2.10 for all existing macOS, Windows and Linux variants. Existing binaries are retained without recompilation and load the refreshed interface from your server.
- **Legacy Android wrapper backup:** 1.0.5; its original APK and source archive remain available unchanged.

Install the native APK to update its bundled interface. Update the server to 1.3.0 for browsers, PWAs and URL wrappers. Account theme preferences and the existing backend data model are preserved. The earlier authentication, media-relay, upload and proxy security fixes remain included; see [the security review](https://github.com/sPROFFEs/Aoede/blob/v1.3.0/docs/SECURITY-REVIEW-2026-10-24.md) for deployment settings.

## Validation and scope

Local validation passed 452 browser layout/interaction checks, 386 repository tests (2 skipped), 73 Android unit tests, release lint and compilation. The publishing workflows run the repository and Android checks again and verify signing compatibility.

Browser checks use the actual frontend with synthetic catalog/API fixtures across phone, tablet, landscape and desktop layouts. Physical Android/OEM rendering and the previously reported intermittent Vitreo/LibrePods/AirPods audio issue remain unverified on the affected hardware. The changes fix demonstrated UI and analyser lifecycle defects without claiming a confirmed hardware-level resolution.
