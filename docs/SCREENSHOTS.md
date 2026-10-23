# README screenshots

The root README is a product overview. Its gallery uses local images so the page does not depend on temporary attachment URLs or a running public demo.

## Capture provenance

The current gallery was captured on 23 October 2026 from the actual Aoede templates, styles and JavaScript at revision `230a9a3`, rendered in Chromium. A local preview supplied fixture API responses, a fictional account named Alex, sample collections, abstract cover artwork and synthetic audio. No production deployment or private library was used.

Desktop images use a **1440 × 1000** viewport. Mobile images use **430 × 932** and show the responsive **web interface**, rather than screenshots of a physical phone or the Android host. Glass and Vitrine use the light color scheme; Classic retains its dark appearance. The library uses its grid option. Reduced Motion was enabled while capturing.

The screenshots preserve the rendered interface. They contain no added device frames, replacement UI or promotional overlays. They are encoded as WebP to keep the README lightweight.

## Gallery files

All paths below are relative to the repository root.

| File | Screen |
|---|---|
| `docs/images/screenshots/desktop-vitrine.webp` | Vitrine Home and compact player; README hero. |
| `docs/images/screenshots/desktop-classic.webp` | Classic Home and compact player. |
| `docs/images/screenshots/desktop-glass.webp` | Glass library and compact player. |
| `docs/images/screenshots/mobile-home.webp` | Vitrine Home with mobile navigation and compact player. |
| `docs/images/screenshots/mobile-library.webp` | Glass collection library and filters. |
| `docs/images/screenshots/mobile-player.webp` | Vitrine fullscreen music player. |

## Refresh the gallery

1. Open a local preview or a deployment you control, using a demo account and a populated library. Keep real usernames, credentials and private server addresses out of published captures.
2. Use the current frontend without altering its layout for the screenshots. Set the account theme and viewport to match the table above.
3. Load a track so the compact player is visible. For the fullscreen image, open the player with its normal artwork/expand control.
4. Wait for API requests, artwork, icons and fonts to finish loading. Check that no toast, dialog, loading placeholder or developer tool covers the intended screen.
5. Capture the viewport, export to WebP and replace the existing file. Keep text readable and inspect both the full image and its size in the README.
6. Update this guide's revision, date and provenance if the source or capture method changes. Check the README image links before publishing.

New screenshots should demonstrate a distinct feature or screen, rather than repeat the same view. Keep the main gallery short; operational walkthroughs belong in their corresponding guide.
