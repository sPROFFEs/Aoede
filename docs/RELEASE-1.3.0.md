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

## Follow-up interface fixes

- Hide the sidebar playlist scrollbar while keeping touch, wheel and keyboard scrolling.
- Correct all collection overflow rows, including sharing, collaborators and deletion, across Classic, Glass and Vitrine. Use readable deletion confirmation and a compact, scrollable collaborator dialog with user/node suggestions.
- Make Discovery a denser audition list with smaller artwork, accessible preview buttons and correct play/pause icons.
- Put offline playlists, albums and downloaded songs first. Keep search and playback close at hand; collapse storage checks and cleanup below the content.
- Adjust Glass mobile cards, player spacing and long track titles. Add restrained dialog/press transitions with reduced-motion support.

## Android 1.1.22 (29)

The native APK includes the refreshed frontend, updated server setup styling and consistent Back behavior for dialogs. Fullscreen artwork follows horizontal swipes and transitions between tracks like a carousel. Swipe down on the artwork/header to minimize through the same action as the arrow. Controls, scrolling lyrics/queue, cancelled gestures and final-track Next retain their behavior. Reduced effects/motion skips decorative carousel movement. Browser zoom controls and pinch zoom are disabled inside the native client; browser/PWA zoom remains available. The existing distribution signature is retained for in-place updates. APK and complete editable source are provided below.

Playback continues to use the same retained WebView, queue and native media integration. Offline listening, home-screen widgets, the optional Dynamic Island, track swipes, background playback and the 1.1.20 analyser cleanup fixes are retained. This interface release adds no Bluetooth reconnection, process restart or automatic pause/play recovery loop.

## Versions and downloads

- **Aoede server/source:** 1.3.0; the release tag points to the matching server version.
- **Native Android:** 1.1.22, version code 29; new signed APK and source archive.
- **Desktop wrappers:** 1.2.10 for all existing macOS, Windows and Linux variants. Existing binaries are retained without recompilation and load the refreshed interface from your server.
- **Legacy Android wrapper backup:** 1.0.5; its original APK and source archive remain available unchanged.

Install the native APK to update its bundled interface. Update the server to the current 1.3.0 revision on `main` for browsers, PWAs and URL wrappers; these follow-up web fixes were added after the original 1.3.0 tag. Account theme preferences and the existing backend data model are preserved. The earlier authentication, media-relay, upload and proxy security fixes remain included; see [the security review](https://github.com/sPROFFEs/Aoede/blob/v1.3.0/docs/SECURITY-REVIEW-2026-10-24.md) for deployment settings.

A full replacement of WebView is proposed separately in the [Android native migration plan](https://github.com/sPROFFEs/Aoede/blob/main/docs/ANDROID-NATIVE-MIGRATION.md): typed API contracts, Kotlin/Compose screens, service-owned Media3 playback, offline data migration and physical-device acceptance before rollout. It is not part of this APK.

## Validation and scope

Local validation includes 1,063 browser layout/interaction checks, 388 repository tests (2 skipped), frontend regression checks, 75 Android unit tests, release lint and compilation. The publishing workflows run the repository and Android checks again and verify signing compatibility.

Browser checks use the actual frontend with synthetic catalog/API fixtures across phone, tablet, landscape and desktop layouts. Physical Android/OEM rendering and the previously reported intermittent Vitreo/LibrePods/AirPods audio issue remain unverified on the affected hardware. The changes fix demonstrated UI and analyser lifecycle defects without claiming a confirmed hardware-level resolution.
