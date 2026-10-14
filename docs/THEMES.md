# Appearance themes

Choose **Settings → Appearance → Glass** to enable the additional theme, or **Classic** to return to the existing Aoede interface. Classic remains the default for existing and new accounts.

Glass follows the device's light/dark appearance. Its design takes inspiration from [Square](https://github.com/Lelonio/Square/tree/f9a75dac8affd3c1399db8d66751cb0063494d87), reviewed at commit `f9a75da`: paper and ink colors, translucent surfaces, rounded capsules, luminous edges, and a restrained accent derived from the playing track's artwork. Reference material: [theme colors](https://github.com/Lelonio/Square/blob/f9a75dac8affd3c1399db8d66751cb0063494d87/app/src/main/java/dev/lelonio/square/ui/theme/Theme.kt), [glass surface](https://github.com/Lelonio/Square/blob/f9a75dac8affd3c1399db8d66751cb0063494d87/app/src/main/java/dev/lelonio/square/ui/player/GlassSurface.kt), and [screenshots](https://github.com/Lelonio/Square/tree/f9a75dac8affd3c1399db8d66751cb0063494d87/docs/screenshots).

Aoede implements this appearance with original CSS and its existing UI components. Browser blur, film, highlights and shadows approximate the native material; Square's Android GPU refraction shader is not reproduced in the browser. No Square code, icons or artwork are bundled.

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

## Persistence and synchronization

The authenticated account owns its preference in `users.theme`. `GET /user/appearance` returns it; `PATCH /user/appearance` accepts only `{"theme":"classic"}` or `{"theme":"glass"}` and changes the current account. Theme responses and the sync heartbeat are private and not HTTP-cacheable. Schema migration `034_user_theme` supplies the Classic default without changing existing account data.

The server renders the saved theme before the page displays. Other signed-in devices reconcile it through the existing visible-page heartbeat (every 30 seconds and on returning to the app). The theme is separate from the library version, so a preference update does not replace the current playlist or interrupt editing. A stale heartbeat cannot overwrite a newer save.

Saving requires a connection: failed, timed-out or unauthenticated requests leave the previous theme selected. The browser caches the last **confirmed** choice per username for offline launches. Storage restrictions do not prevent online theme selection. Login can remember the last theme on that browser without storing the account identity in the login preference; the authenticated server choice takes precedence after signing in. The service worker precaches the additional CSS and module. When offline, versioned asset requests can fall back to the installed shell's canonical asset URL; online requests keep their exact version authoritative.

The theme preference belongs to an account on its home server. Viewing another node's public collections uses the viewer's theme; theme preferences are not published through federation.

## Browser and accessibility behavior

All web clients, including the Android WebView and desktop wrappers, receive the same stylesheet. No new native binary is required for this frontend feature. Light/dark appearance remains device-specific while the Classic/Glass choice follows the account.

CSS handles mobile safe areas and leaves space for the floating controls. Focus outlines, native radio controls, reduced motion, reduced transparency and forced colors are supported. Browsers without backdrop blur use solid surfaces. Artwork colors are sampled from the already loaded cover; blocked cross-origin canvas access keeps the default accent and does not affect playback.

Implementation: `Aoede/assets/css/theme_glass.css`, `Aoede/assets/js/modules/appearance.js`, the shared `appearance_head.html` template, and the authenticated user appearance endpoint. Backend checks live in `test_user_theme.py`; `scripts/test_appearance.mjs` exercises persistence, account isolation, offline behavior, failed saves and stale responses. Both run through `npm run check`.
