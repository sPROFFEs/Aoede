# Interface refresh

The web frontend and native Android bundle share the same components. Classic remains the default and follows Spotify's compact navigation and player hierarchy. Vitrine follows Apple Music's library, collection details and player arrangement. Glass remains available with its own materials. The Aoede application icon and installation artwork are unchanged.

## Reference and scope

The local `music-ui-handoff.zip` contains a static resource analysis of Spotify Android **9.1.94.1929** and historical Apple Music iOS **3.1**. Its `analysis/implementation-blueprint.json`, decoded layouts, design tokens and glyph inventories informed this change. These resources are not live app screenshots or a runnable original frontend. Native rendering, runtime gestures and undocumented timing are not established by the archive.

The reference directory is excluded by `/NO-UPLOAD-TO-GITHUB/` in `.gitignore`. The archive, application packages, decompiled code, fonts, commercial-service branding and unrelated assets are not shipped. The Android source packager reads only the Android project, canonical frontend and Aoede branding, rather than traversing this directory.

The selected monochrome UI glyph geometry is in `Aoede/assets/js/ui_icons.js`: 26 Spotify icon mappings plus a filled-heart variant, and 11 Apple mappings. Spotify sources are the corresponding `analysis/spotify/icons/` vectors recorded by `analysis/spotify/icon-map.json`; Apple sources are `analysis/apple-music/font-glyphs/{videoplay,videopause,plus,modalcloseshort,check,chevrondown,chevronup,chevronleft,chevronright,share,download}.svg`. Existing Lucide glyphs cover unsupported actions and Glass. Application logos and proprietary fonts are not replaced or bundled.

## Changes

| Area | Behavior |
|---|---|
| Navigation | Distinct Classic panes; quieter Apple sidebar and separators; compact mobile header without duplicate Library actions; existing destinations remain available. |
| Home and library | Consistent gutters, type hierarchy, artwork ratios, shelves, quick access, filters, search and list/grid controls. |
| Collections | Play, Shuffle and offline download stay visible. Editing, covers, sharing, collaborators and deletion move into a named overflow menu. Mobile presents it as a sheet. Escape, outside clicks and Android Back dismiss it. |
| Players | Shared playback session; theme-specific transport, compact player and artwork presentation. Touch targets and short/landscape window constraints remain in effect. Existing artwork/compact-player track swipes stay available. Native Android adds artwork carousel motion and downward dismissal, without changing the audio owner. |
| Settings and tools | Shared spacing, text, fields, tabs and panel treatment across profile, administration, monitoring and downloads. |
| Motion | No universal transitions or entrance fade on every fetched section. Player expansion and menus use short spatial transitions; reduced motion and Android Reduce effects remain supported. |
| Android setup | Larger heading, readable URL hint, system typography, clear contrast and controls consistent with Classic. |

The floating-player measurements use the recovered 56 dp height, 40 dp artwork and 8 dp inset/radius as reference. Browser pixels and Android dp are not treated as an exact rendering equivalence. Menu motion adapts the recovered 300 ms slide/fade into CSS; it does not claim to reproduce undocumented native animation timing.

Discovery now uses small artwork beside track information and controls. Offline downloads prioritize collections and songs, with storage tools collapsed below them. The sidebar remains scrollable without a visible scrollbar. Collection menus override theme-specific action-button sizing; collaboration dialogs scroll within the screen and deletion uses the shared confirmation dialog. Preview buttons update their rendered SVG instead of looking for an already replaced icon placeholder.

## Performance and playback boundaries

The bundled Lucide version ignores the caller's `nodes` option. The adapter honors that option and retains SVGs that already match the theme and icon, so frequent control updates no longer replace icons throughout the page. Theme changes restore the correct SVG coordinate system and preserve filled favorite states. Fonts and compatibility icons load locally, without a font CDN request at startup.

The audio engine, WebView ownership, media service, audio focus, Dynamic Island analyzer and backend APIs are unchanged. No new player instance, playback recovery loop, transfer protocol, streaming service integration or animation library is introduced. Queue, lyrics, federation, collaborative collections, download and offline operations keep their existing controllers.

`appearance_head.html` loads the shared `ui_experience.css` after the theme materials. `base.html` installs the icon adapter after Lucide. The service worker's new shell revision precaches both. `android/build_frontend.py` includes the same files in the APK, so an already installed native APK needs a new build to receive them; the web app and URL wrappers receive them from their server.

## Checks

Run the existing frontend checks through `npm run check`, including `node scripts/test_ui_icons.mjs`. The icon regression check covers scoped refresh, repeated global refresh, theme changes, coordinate systems, accessibility attributes and filled favorites. Existing player, party, collection, appearance and Android frontend checks exercise the unchanged functional paths.

Build the native frontend and run Android unit tests, release lint and compilation with `gradle -p android testDebugUnitTest lintRelease assembleRelease` using the project's configured SDK/JDK.

Browser validation uses the actual templates, styles and JavaScript with synthetic catalog/API fixtures. Check Classic, Glass and Vitrine in light/dark appearance, narrow phones, tablets, short landscape windows and desktop, including open collection menus, settings tabs, profile gutters, lyrics/queue and full-player controls. This checks browser layout and interaction; it does not establish physical WebView/GPU rendering, Bluetooth continuity or real server integration on an attached Android device.

Validated locally: **1,063 browser layout/interaction checks** (452 existing, 539 follow-up and 72 Android-layout checks with safe areas), **388 repository tests passed (2 skipped)** through `npm run check`, and **75 Android unit tests**, release lint and APK compilation. The official release pairs server **1.3.0** with native Android **1.1.22 (29)**. Desktop **1.2.10** and the original Android wrapper **1.0.5** keep their existing binaries. The Android publishing workflow signs the APK and verifies its certificate against the previous release. Physical Android/WebView validation remains outstanding.

The follow-up browser checks use populated offline IndexedDB collections/tracks and synthetic discovery feeds, inspect every collection menu row in all themes, exercise invite/deletion cancellation and preview SVG updates, and send Chromium touch events through the actual player. They cover next/previous, cancellation, final-track no-op and downward minimization preserving the audio source/state. These are browser checks, not a physical Android audio test. Native zoom settings and bundled viewport restrictions are covered by Android tests. See the [fully native migration proposal](ANDROID-NATIVE-MIGRATION.md) for the separate rewrite.
