# Appearance themes

Choose **Settings → Appearance → Classic, Glass or Vitrine**. Classic is the default. The selected theme belongs to your account and synchronizes between devices; changing it keeps the current view, queue and playback session.

| Theme | Appearance |
|---|---|
| Classic | Spotify inspired charcoal panes, compact navigation, artwork shelves and filled transport controls. |
| Glass | Square inspired translucent surfaces, soft rounded controls and an accent sampled from the current artwork. |
| Vitrine | Apple Music inspired library and collection hierarchy, red accent, clean separators and restrained glass chrome. |

Glass and Vitrine follow the device's light/dark appearance. Their browser materials approximate translucency; native GPU refraction is not run over web content. The latest interface uses the supplied static music UI handoff as an additional reference; [implementation scope and provenance](UI-REFRESH.md) distinguishes recovered resources from Aoede adaptations.

## Browsing

Home, Search and Discover remain in the desktop sidebar. Library opens saved collections, with adjacent creation and collapse controls. Favorites, offline downloads, recent activity and saved collections scroll together. Search and type filters apply to the sidebar; new browsers start with it expanded while existing preferences are preserved.

Home contains browse destinations, recent shortcuts and artwork shelves. Touch scrolling, keyboard scrolling and desktop shelf arrows remain available. Search keeps source selection separate from the album/playlist switch. The profile menu contains administration, updates and other account actions.

Your Library includes playlists, albums, songs, artists, genres and device downloads. Search matches collection title, creator and federation-node name. Sorting, paging and the list/grid choice retain their existing behavior, including cached filtering offline. User-created albums remain separate from optional metadata album groups.

Collection details keep playback and offline download visible. Open **More options** for renaming, cover selection, sharing, collaborators, copy/sync and deletion, according to your permissions. On phones it opens as a sheet; click outside, press Escape or use Android Back to dismiss it. Mobile Library search and creation controls live with the library content rather than being duplicated in its header.

Content uses a 20 px phone gutter, reduced to 16 px on the narrowest displays; safe areas are applied separately. Desktop uses responsive gutters. Profiles have their own inner padding. Settings tabs and source filters scroll horizontally when their labels need more room.

## Player

All themes reuse the same compact and fullscreen player, queue, lyrics, volume and transport controllers. Vitrine has an open Apple Music style transport and inline volume; Classic uses a filled primary play button. Artwork expands gently during playback and contracts when paused. Reduced motion removes that decorative movement.

On mobile, swipe left on the compact player for the next track or right for the previous song. The fullscreen player accepts the same gestures on its artwork. Buttons and sliders keep their normal behavior, including the Previous button's restart-before-skip behavior. Vertical scrolling, short drags, long presses and multi-touch do not skip songs. Gestures use the existing queue, repeat and party controls.

Fullscreen lyrics and queue reuse the same panels as the normal player. Switching their views or changing themes does not recreate playback. Desktop supports side-by-side artwork and controls; short displays retain reachable transport controls. Seek and volume sliders support keyboard operation.

## Account and client behavior

The authenticated server preference wins online. Offline, the last confirmed choice is cached per account, preventing one account from inheriting another's settings. A failed save leaves the previous confirmed theme selected. Viewing another node's public collections uses the viewer's theme; appearance preferences are not published through federation.

The web app and URL wrappers load the server frontend. Native Android packages the same frontend and needs a new APK build to receive interface changes. Android Reduce effects disables costly materials. The application icon, favicons, adaptive icons and desktop installation artwork are independent of the theme and remain Aoede's own design.

English, Spanish and German share the same translated runtime labels and placeholders. Native form controls, focus outlines, named dismissal controls, reduced motion, reduced transparency and forced-color fallbacks remain available. No additional animation framework is needed.

Implementation: `ui_tokens.css`, `ui_experience.css`, the existing theme/player/mobile styles, `ui_icons.js`, `appearance.js` and `appearance_head.html`. [Interface refresh](UI-REFRESH.md) describes validation and the boundary between browser checks and physical-device testing.
