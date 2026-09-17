/**
 * sw.js — Navipod Service Worker
 *
 * Full offline application shell and caching coordinator.
 *  - Precaches the versioned application shell (HTML, CSS, JS, icons, manifest).
 *  - Serves navigation fallback when offline so Navipod launches instantly in airplane mode.
 *  - Leaves audio streams and large downloads to IndexedDB storage.
 *  - Cleans up stale shell caches on version upgrades.
 */

const CACHE = 'navipod-shell-v3';
const REVALIDATE_AFTER_MS = 24 * 60 * 60 * 1000; // 24h
const EVICT_AFTER_MS = 7 * 24 * 60 * 60 * 1000; // 7d

const PRECACHE_ASSETS = [
  '/portal',
  '/assets/site.webmanifest',
  '/assets/favicon.ico',
  '/assets/favicon-16x16.png',
  '/assets/favicon-32x32.png',
  '/assets/apple-touch-icon.png',
  '/assets/android-chrome-192x192.png',
  '/assets/android-chrome-512x512.png',
  '/assets/vendor/lucide.min.js',
  '/assets/vendor/htmx.min.js',
  '/assets/img/default_cover.png',
  '/assets/img/navidrome.png',
  '/assets/img/fav.webp',
  '/assets/img/last.webp',
  '/assets/img/repeat.webp',
  '/assets/img/rediscovery.webp',
  '/assets/img/top.webp',
  '/assets/img/deep.webp',
  '/assets/css/style.css',
  '/assets/css/ui_tokens.css',
  '/assets/css/ui_sidebar.css',
  '/assets/css/ui_header.css',
  '/assets/css/ui_home.css',
  '/assets/css/ui_party.css',
  '/assets/css/ui_components.css',
  '/assets/css/ui_player.css',
  '/assets/css/ui_mobile.css',
  '/assets/css/ui_motion.css',
  '/assets/css/mobile_fixes.css',
  '/assets/css/mobile_shell.css',
  '/assets/css/admin_system.css',
  '/assets/css/admin_downloads.css',
  '/assets/js/main.js',
  '/assets/js/modules/admin.js',
  '/assets/js/modules/admin_downloads.js',
  '/assets/js/modules/admin_system.js',
  '/assets/js/modules/api.js',
  '/assets/js/modules/audio_engine.js',
  '/assets/js/modules/downloads.js',
  '/assets/js/modules/favorites.js',
  '/assets/js/modules/library.js',
  '/assets/js/modules/lyrics.js',
  '/assets/js/modules/offline_store.js',
  '/assets/js/modules/party.js',
  '/assets/js/modules/player.js',
  '/assets/js/modules/playlists.js',
  '/assets/js/modules/queue.js',
  '/assets/js/modules/radio.js',
  '/assets/js/modules/search.js',
  '/assets/js/modules/state.js',
  '/assets/js/modules/sync.js',
  '/assets/js/modules/system_monitor.js',
  '/assets/js/modules/ui.js',
  '/assets/js/modules/update_progress.js',
  '/assets/js/modules/views.js'
];

function _shouldRevalidate(response) {
  const dateHeader = response.headers.get('date');
  if (!dateHeader) return false;
  const parsed = Date.parse(dateHeader);
  if (Number.isNaN(parsed)) return false;
  return Date.now() - parsed >= REVALIDATE_AFTER_MS;
}

// ── Install ────────────────────────────────────────────────────────────────
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => {
        // Precache all assets safely without failing entire installation if a single asset 404s
        return Promise.allSettled(
          PRECACHE_ASSETS.map((url) =>
            fetch(url, { credentials: 'same-origin' }).then((res) => {
              if (res.ok) return cache.put(url, res);
            })
          )
        );
      })
      .then(() => self.skipWaiting())
  );
});

// ── Activate ───────────────────────────────────────────────────────────────
async function _evictStaleEntries() {
  const cache = await caches.open(CACHE);
  const requests = await cache.keys();
  await Promise.all(
    requests.map(async (request) => {
      const cached = await cache.match(request);
      const dateHeader = cached && cached.headers.get('date');
      const parsed = dateHeader ? Date.parse(dateHeader) : NaN;
      if (!Number.isNaN(parsed) && Date.now() - parsed >= EVICT_AFTER_MS) {
        await cache.delete(request);
      }
    })
  );
}

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => _evictStaleEntries())
      .catch(() => {})
      .then(() => self.clients.claim())
  );
});

// ── Fetch ──────────────────────────────────────────────────────────────────
self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // Cross-origin → browser handles it
  if (url.origin !== self.location.origin) return;

  // Streams and audio downloads should not pollute the shell cache
  if (url.pathname.startsWith('/api/stream/')) return;

  // 1. Navigation requests: Network-first with fallback to cached /portal shell
  if (request.mode === 'navigate' || request.headers.get('accept')?.includes('text/html')) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(async () => {
          const cachedNav = await caches.match(request);
          if (cachedNav) return cachedNav;
          const portalShell = await caches.match('/portal');
          if (portalShell) return portalShell;
          return new Response(
            '<!DOCTYPE html><html><head><meta charset="utf-8"><title>Navipod Offline</title></head><body style="background:#121212;color:#fff;font-family:sans-serif;text-align:center;padding:40px;"><h1>Navipod Offline</h1><p>You are offline. Open Navipod while connected to sync the offline app shell.</p></body></html>',
            { headers: { 'Content-Type': 'text/html' } }
          );
        })
    );
    return;
  }

  // 2. Static assets (/assets/) → Cache-first with background revalidation
  if (url.pathname.startsWith('/assets/') || url.hostname === 'unpkg.com') {
    event.respondWith(
      caches.match(request).then((cached) => {
        if (cached) {
          if (_shouldRevalidate(cached)) {
            fetch(request)
              .then((res) => {
                if (res.ok) caches.open(CACHE).then((c) => c.put(request, res));
              })
              .catch(() => {});
          }
          return cached;
        }

        // Not cached yet: fetch, cache, and return
        return fetch(request).then((res) => {
          if (!res.ok) return res;
          const cacheResponse = res.clone();
          return caches
            .open(CACHE)
            .then((cache) => cache.put(request, cacheResponse))
            .catch(() => {})
            .then(() => res);
        });
      })
    );
    return;
  }

  // 3. API endpoints → Network-first with structured offline JSON response
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(request).catch(async () => {
        return new Response(JSON.stringify({ offline: true, error: 'Network unavailable (offline mode)' }), {
          status: 503,
          headers: { 'Content-Type': 'application/json' }
        });
      })
    );
  }
});
