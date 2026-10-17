# Appearance themes

Choose **Settings → Appearance → Classic, Glass or Vitrine**. Each is a separate account preference; switching themes keeps the current view and audio playing. Classic remains the default for existing and new accounts.

Glass follows the device's light/dark appearance. Its design takes inspiration from [Square](https://github.com/Lelonio/Square/tree/f9a75dac8affd3c1399db8d66751cb0063494d87), reviewed at commit `f9a75da`: paper and ink colors, translucent surfaces, rounded capsules, luminous edges, and a restrained accent derived from the playing track's artwork. Reference material: [theme colors](https://github.com/Lelonio/Square/blob/f9a75dac8affd3c1399db8d66751cb0063494d87/app/src/main/java/dev/lelonio/square/ui/theme/Theme.kt), [glass surface](https://github.com/Lelonio/Square/blob/f9a75dac8affd3c1399db8d66751cb0063494d87/app/src/main/java/dev/lelonio/square/ui/player/GlassSurface.kt), and [screenshots](https://github.com/Lelonio/Square/tree/f9a75dac8affd3c1399db8d66751cb0063494d87/docs/screenshots).

Aoede implements this appearance with original CSS and its existing UI components. Browser blur, film, highlights and shadows approximate the native material; Square's Android GPU refraction shader is not reproduced in the browser. No Square code, icons or artwork are bundled.

## Vitrine

Vitrine is an additional Apple Music inspired layout, based on [Vitrine](https://github.com/My-Name-Is-Jeff/vitrine/tree/914343731f66ccb2e639b8164a36be849a043276), reviewed at commit `9143437`. Its [Home](https://github.com/My-Name-Is-Jeff/vitrine/blob/914343731f66ccb2e639b8164a36be849a043276/docs/screenshots/home.webp) and [album](https://github.com/My-Name-Is-Jeff/vitrine/blob/914343731f66ccb2e639b8164a36be849a043276/docs/screenshots/album.webp) references inform the compact artwork tiles, open shelves, restrained separators, rose accent and floating mobile navigation. Desktop adds a translucent sidebar with Home, Search and Discover destinations; the existing sidebar collapse preference still works. Mobile retains all five destinations and the compact player's transport controls.

The fullscreen player keeps the complete artwork, places unboxed track information below it, and uses a glass play control with a lower capsule view selector. Wide/landscape screens place artwork beside controls. Lyrics, queue, volume, favorites and playlist actions reuse the same working components as the other themes. Album/playlist pages have a cover-led header with centered metadata and actions on mobile. Administration, download previews, notifications, login and Wrapped use the same neutral surfaces and rose accents.

The Liquid Glass material follows [Vitreo Launcher](https://github.com/sPROFFEs/vitreo-launcher/tree/dfa8ccda84a3db4b20e394d3d4615716b8f0023a), reviewed at `dfa8ccd`: [GlassRenderer](https://github.com/sPROFFEs/vitreo-launcher/blob/dfa8ccda84a3db4b20e394d3d4615716b8f0023a/app/src/main/java/dev/vitreo/launcher/graphics/GlassRenderer.kt) and [its shader](https://github.com/sPROFFEs/vitreo-launcher/blob/dfa8ccda84a3db4b20e394d3d4615716b8f0023a/app/src/main/assets/glass.agsl) inform the directional outer rim, inner reflection, diffuse centre and press feedback. Clear surfaces serve navigation and small controls; stronger regular surfaces keep the floating player, bottom navigation, dialogs and menus readable while retaining the illuminated rim. Artwork supplies a restrained background tint. Content shelves and album art stay sharp. The home heading and content lead the page, with secondary destinations below the quick picks.

Buttons have consistent circular/pill shapes and 44 px targets. Close glyphs are centred, labelled, and use `type="button"` so dismissal cannot accidentally submit a surrounding form. Native checkbox/radio semantics remain intact with custom checked, mixed, disabled and keyboard focus states; switches use a visible sliding thumb. Dark-mode checkmarks use a dark foreground over the rose accent. Download notices retain one material behind their text. High contrast, reduced motion/transparency, forced colors and browsers without blur retain readable solid controls. Reduced Motion removes press scaling as well as transitions.

The web finish uses CSS backdrop blur, layered highlights and inset shadows. It does not run Android's AGSL shader or reproduce native pixel refraction of page content. There is no canvas capture, per-frame rendering loop or new runtime dependency.

Vitrine follows the device's light/dark mode. Its accent remains rose while the fullscreen backdrop uses the playing cover. Aoede implements this with original CSS and existing components: no upstream native code, assets or proprietary fonts are bundled. Classic and Glass remain available with their previous palettes and layouts.

## Covered surfaces

The same theme applies to:

- Home, recommendations, discovery, search and album/playlist import previews;
- the library, albums, regular/smart/collaborative playlists and favorites;
- radio, party rooms, federation badges, community and user profiles;
- the desktop player, floating mobile player/navigation, fullscreen player, queue and lyrics;
- offline downloads, download jobs and deletion request responses;
- user settings, administration, library/duplicate tools, System Monitor, Download Manager and update progress;
- dialogs, context menus, playlist pickers, mobile action sheets and notifications;
- Help, login and the separate Wrapped story document.

Artwork remains artwork. Provider icons and semantic warning/error states remain recognizable. Switching appearance does not navigate, replace a view, reset a form or restart audio.

## Material details

Glass uses a quieter content layer beneath floating navigation, menus and notifications. Following the material hierarchy in [Apple's guidance](https://developer.apple.com/design/human-interface-guidelines/materials), cards and nested settings rows have subtle separators without repeated blur or heavy shadows. The [LiquidGlass.pro reference](https://www.liquid-glass.pro/) informed the soft edge lighting; the implementation uses original CSS gradients, inset highlights and browser backdrop blur, without a distortion filter on text.

Download notifications keep readable text on one translucent surface. Job metadata labels have transparent backgrounds; completed and failed jobs use the theme's semantic colors. Album/playlist search and URL preview controls are native checkboxes exposed as switches, with keyboard activation and visible focus. Other checkboxes and radio buttons retain their native behavior. Disabled controls, read-only fields, long metadata, mobile margins and reduced transparency/high contrast modes are handled by the same theme.

## Shared player layout

Classic and Glass share the fullscreen distribution inspired by Square's [player layout](https://github.com/Lelonio/Square/blob/f9a75dac8affd3c1399db8d66751cb0063494d87/app/src/main/java/dev/lelonio/square/ui/player/PlayerScreen.kt): an artwork/lyrics stage, song information with favorite and playlist actions, progress with time labels below, transport controls and a lower view selector. Cover, Lyrics and Queue use the same stage, keeping the playback controls visible. Wide screens and landscape place the stage beside the controls; short portrait screens shrink the artwork area rather than hiding controls. The mobile compact player provides Next and Play/Pause in both themes. Classic retains its opaque charcoal surfaces, outline icons and green active states; Glass adds translucent capsules, artwork backgrounds and glass discs.

The volume control opens from the upper corner and stays synchronized with the compact player's volume and mute state, including YouTube and discovery previews. Sliders support arrow keys (5% steps), Home and End. Escape collapses the fullscreen player and returns focus to its opener without pausing music. The existing audio element and queue/lyrics panels are reused; switching views or themes does not create another audio player or close the selected view. Closing fullscreen dismisses its panels and restores their normal sidebar hosts in both themes. At extreme zoom or very short viewports, the player scrolls to keep every control accessible.

Lyrics scroll inside their own stage without moving the transport. Late responses from a previous song are ignored. Unknown/live durations are not seekable, and track-dependent actions follow the existing local/federated/preview capabilities. The player uses solid surfaces when transparency is reduced, and text remains readable over light artwork.

## Persistence and synchronization

The authenticated account owns its preference in `users.theme`. `GET /user/appearance` returns it; `PATCH /user/appearance` accepts only `{"theme":"classic"}`, `{"theme":"glass"}` or `{"theme":"vitrine"}` and changes the current account. Theme responses and the sync heartbeat are private and not HTTP-cacheable. Schema migration `034_user_theme` supplies the Classic default. Migration `035_vitrine_theme` expands the existing column constraint to accept Vitrine, preserving saved themes, users, indexes and dependent records without rebuilding the users table. It requires SQLite 3.35 or newer (included in the supported Python container) for column removal, and can resume after an interrupted column replacement.

The server renders the saved theme before the page displays. Other signed-in devices reconcile it through the existing visible-page heartbeat (every 30 seconds and on returning to the app). The theme is separate from the library version, so a preference update does not replace the current playlist or interrupt editing. A stale heartbeat cannot overwrite a newer save.

Saving requires a connection: failed, timed-out or unauthenticated requests leave the previous theme selected. The browser caches the last **confirmed** choice per username for offline launches. Storage restrictions do not prevent online theme selection. Login can remember the last theme on that browser without storing the account identity in the login preference; the authenticated server choice takes precedence after signing in. The service worker precaches the additional CSS and module. When offline, versioned asset requests can fall back to the installed shell's canonical asset URL; online requests keep their exact version authoritative.

The theme preference belongs to an account on its home server. Viewing another node's public collections uses the viewer's theme; theme preferences are not published through federation.

## Browser and accessibility behavior

All web clients, including the Android WebView and desktop wrappers, receive the same stylesheet. No new native binary is required for this frontend feature. Light/dark appearance remains device-specific while the Classic/Glass/Vitrine choice follows the account.

The desktop header has its own stacking layer above animated view content so its user menu remains clickable in every theme. Context menus and playlist flyouts live under the document body, while modal backdrops remain above the app shell. Short windows scroll the user menu internally; Escape closes it and restores focus to its disclosure control.

Web installation icons use separate `any` and opaque `maskable` variants; the 1024 px maskable asset follows [WebKit's Add to Dock guidance](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/). Regenerate browser and installation assets with `python scripts/enlarge_brand_icons.py`; the master artwork stays unchanged and the service worker caches the installation icon for offline use.

CSS handles mobile safe areas and leaves space for the floating controls. Focus outlines, native radio controls, reduced motion, reduced transparency and forced colors are supported. Browsers without backdrop blur use solid surfaces. Artwork colors are sampled from the already loaded cover; blocked cross-origin canvas access keeps the default accent and does not affect playback.

Implementation: `Aoede/assets/css/theme_glass.css` (shared modern surfaces), `Aoede/assets/css/theme_vitrine.css` (Vitrine palette and layout), `Aoede/assets/js/modules/appearance.js`, the shared `appearance_head.html` template, and the authenticated user appearance endpoint. Player geometry lives in `Aoede/assets/css/ui_player.css`, with compact mobile controls in `mobile_shell.css`; Glass overrides their material; Vitrine additionally refines the cover, title and transport arrangement. Backend checks live in `test_user_theme.py`; `scripts/test_appearance.mjs` exercises persistence, account isolation, offline behavior, failed saves and stale responses. `scripts/test_player_ui.mjs` checks panel reuse in all three themes, theme changes with an open panel, stale lyrics, volume, supported actions and keyboard seeking. These checks run through `npm run check`.
