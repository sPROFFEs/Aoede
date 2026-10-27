/**
 * views.js - View Rendering and Routing
 * Handles navigation, view switching, and UI component builders
 */

import * as state from './state.js';
import * as ui from './ui.js';
import * as api from './api.js';
import * as player from './player.js';
import * as audioEngine from './audio_engine.js';
import * as search from './search.js';
import * as radio from './radio.js';
import * as favorites from './favorites.js';
import * as playlists from './playlists.js';
import * as downloads from './downloads.js';
import * as sync from './sync.js';
import * as library from './library.js';
import * as party from './party.js';
import * as offlineStore from './offline_store.js';
import { initSystemMonitor } from './system_monitor.js';
import { initDownloadManager } from './admin_downloads.js';
import { syncThemeControls } from './appearance.js';

const SECRET_EYE_ICON = `
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8S1 12 1 12z"></path>
        <circle cx="12" cy="12" r="3"></circle>
    </svg>
`;

const SECRET_EYE_OFF_ICON = `
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M17.94 17.94A10.94 10.94 0 0 1 12 20c-5 0-9.27-3.11-11-8 1-2.68 2.87-4.9 5.31-6.34"></path>
        <path d="M9.9 4.24A10.94 10.94 0 0 1 12 4c5 0 9.27 3.11 11 8a11.05 11.05 0 0 1-4.08 5.36"></path>
        <path d="M14.12 14.12A3 3 0 1 1 9.88 9.88"></path>
        <path d="M3 3l18 18"></path>
    </svg>
`;

const MIX_META = {
  repeat: {
    summary: 'Your most replayed tracks, weighted by favorites and recent full listens.',
    short: 'Your most played local tracks'
  },
  deep_cuts: {
    summary: 'Tracks with solid history that are not the obvious top repeats.',
    short: 'Repeated tracks outside the obvious top'
  },
  favorites: {
    summary: 'Liked songs mixed with tracks from the same artists and albums already in your library.',
    short: 'Liked songs and their closest local neighbors'
  },
  rediscovery: {
    summary: 'Local tracks you liked before but have not played much lately.',
    short: 'Older local favorites worth bringing back'
  },
  top_pool_tracks: {
    summary: 'The most played local tracks across all users, refreshed from shared playback stats.',
    short: 'Top 50 across the whole pool'
  },
  latest_pool_additions: {
    summary: 'The 100 most recently added tracks in the shared pool, ordered from newest to oldest.',
    short: 'Latest 100 added to the server'
  }
};

function renderSecretToggleIcon(button, revealed) {
  const iconTarget = button.querySelector('.secret-toggle-icon');
  if (!iconTarget) return;
  iconTarget.innerHTML = revealed ? SECRET_EYE_OFF_ICON : SECRET_EYE_ICON;
}

function initUserSettingsView(container) {
  const userSettingsShell = container.querySelector('.user-settings-shell');
  if (!userSettingsShell) return;

  userSettingsShell.querySelectorAll('.toggle-secret-btn').forEach((button) => {
    if (button.dataset.bound === 'true') return;
    button.dataset.bound = 'true';

    const wrapper = button.closest('.user-settings-secret');
    const input = wrapper?.querySelector('.secret-input');
    if (!input) return;

    renderSecretToggleIcon(button, input.type !== 'password');
    button.addEventListener('click', () => {
      const reveal = input.type === 'password';
      input.type = reveal ? 'text' : 'password';
      renderSecretToggleIcon(button, reveal);
    });
  });

  // ── Playback prefs ────────────────────────────────────────────────
  // The settings page comes in via renderExternalView (DOMParser +
  // replaceChildren), which silently drops inline <script> elements.
  // So we wire the chip-group + Save logic from here, where the JS
  // module IS loaded. The HTML uses inline onclick="" attrs that call
  // window-exposed helpers — those work because the attribute is
  // evaluated at click-time rather than at parse-time.
  initPlaybackPrefs(userSettingsShell);
}

// Module-scoped state for the playback prefs editor. We keep the
// "saved" baseline alongside the "pending" edit so the Save button
// only enables when there's a real diff.
let _prefsState = null;

function initPlaybackPrefs(shell) {
  const RG_KEY = 'aoede.replaygain.enabled';
  const XF_KEY = 'aoede.crossfade.seconds';
  const ALLOWED_XF = [0, 2, 4, 6, 8];

  const rgEl = shell.querySelector('#pref-replaygain');
  const chipsEl = shell.querySelector('#pref-crossfade-chips');
  const saveBtn = shell.querySelector('#pref-save-btn');
  const hintEl = shell.querySelector('#pref-save-hint');
  if (!rgEl || !chipsEl || !saveBtn) return;

  let savedRg = false;
  let savedXf = 0;
  try {
    savedRg = localStorage.getItem(RG_KEY) === '1';
    const n = parseInt(localStorage.getItem(XF_KEY) || '0', 10) || 0;
    savedXf = ALLOWED_XF.indexOf(n) >= 0 ? n : 0;
  } catch (_) {}

  rgEl.checked = savedRg;
  let pendingXf = savedXf;

  _prefsState = {
    rgEl,
    chipsEl,
    saveBtn,
    hintEl,
    savedRg,
    savedXf,
    pendingXf,
    RG_KEY,
    XF_KEY
  };

  paintCrossfadeChips(pendingXf);
  refreshPlaybackPrefsDirty();
}

function paintCrossfadeChips(value) {
  if (!_prefsState) return;
  const { chipsEl } = _prefsState;
  chipsEl.querySelectorAll('.playback-chip').forEach((b) => {
    const v = parseInt(b.dataset.value, 10);
    b.classList.toggle('active', v === value);
    b.setAttribute('aria-checked', v === value ? 'true' : 'false');
  });
}

function refreshPlaybackPrefsDirty() {
  if (!_prefsState) return;
  const { rgEl, saveBtn, hintEl, savedRg, savedXf, pendingXf } = _prefsState;
  const dirty = rgEl.checked !== savedRg || pendingXf !== savedXf;
  saveBtn.disabled = !dirty;
  if (hintEl) hintEl.textContent = dirty ? 'Unsaved changes' : '';
}

// Window-exposed helpers (called from inline onclick="" in user_settings.html)
export function setCrossfadePending(value) {
  if (!_prefsState) return;
  _prefsState.pendingXf = parseInt(value, 10) || 0;
  paintCrossfadeChips(_prefsState.pendingXf);
  refreshPlaybackPrefsDirty();
}

export function markPlaybackPrefsDirty() {
  refreshPlaybackPrefsDirty();
}

export function savePlaybackPrefs() {
  if (!_prefsState) return;
  const { rgEl, hintEl, RG_KEY, XF_KEY, pendingXf } = _prefsState;
  try {
    localStorage.setItem(RG_KEY, rgEl.checked ? '1' : '0');
    localStorage.setItem(XF_KEY, String(pendingXf));
  } catch (_) {}
  if (window.audioEngineSetReplayGain) window.audioEngineSetReplayGain(rgEl.checked);
  if (window.audioEngineSetCrossfade) window.audioEngineSetCrossfade(pendingXf);
  _prefsState.savedRg = rgEl.checked;
  _prefsState.savedXf = pendingXf;
  refreshPlaybackPrefsDirty();
  if (hintEl) {
    hintEl.textContent = 'Saved';
    setTimeout(() => {
      if (hintEl.textContent === 'Saved') hintEl.textContent = '';
    }, 1800);
  }
}

// === VIEW ROUTING ===

function _canTrackSpaHistory(view) {
  return view !== 'settings_admin';
}

function _pushSpaHistory(view, param = null, replace = false) {
  if (!_canTrackSpaHistory(view)) return;
  const statePayload = { aoedeView: view, aoedeParam: param };
  if (replace) {
    window.history.replaceState(statePayload, '');
  } else {
    window.history.pushState(statePayload, '');
  }
}

let navigationVersion = 0;

export async function loadView(view, param = null, options = {}) {
  const version = ++navigationVersion;
  const isCurrent = () => version === navigationVersion;
  if (view === 'settings_admin') {
    window.location.assign('/admin/');
    return;
  }
  const container = document.getElementById('view-container');
  if (!container) return;
  container.removeAttribute('aria-busy');
  // Legacy aliases — both 'radio' and 'your_radios' now resolve to the
  // unified Discover Radios surface (which renders saved radios at the
  // bottom of the same view). Old bookmarks / sidebar entries keep
  // working without 404s.
  if (view === 'radio' || view === 'your_radios') view = 'discover_radios';
  if (view !== 'home' || !container.querySelector('[data-home-root]')) shelfResizeObserver?.disconnect();
  const { pushHistory = true, replaceHistory = false } = options;
  const refreshing =
    state.currentViewName === view &&
    String(state.currentViewParam ?? '') === String(param ?? '') &&
    container.children.length > 0;

  if (view !== 'search' && view !== 'home' && !refreshing) {
    container.innerHTML = `
      <div class="empty-state">
          <div class="music-loader">
              <div class="music-bar" style="animation-delay: 0.0s"></div>
              <div class="music-bar" style="animation-delay: 0.1s"></div>
              <div class="music-bar" style="animation-delay: 0.2s"></div>
              <div class="music-bar" style="animation-delay: 0.3s"></div>
              <div class="music-bar" style="animation-delay: 0.4s"></div>
          </div>
      </div>`;
    // Music-loader has no [data-lucide] elements — no icon refresh needed
    // here. Each view's renderer refreshes its own icons after innerHTML.
  }
  if (typeof window.markBottomNavActive === 'function') {
    window.markBottomNavActive(view, param);
  }

  try {
    state.setCurrentViewName(view);
    state.setCurrentViewParam(param);
    if (state.isSidebarOpen && window.innerWidth <= 768) state.toggleSidebar();
    if (pushHistory) {
      _pushSpaHistory(view, param, replaceHistory);
    }

    if (view === 'home') await renderHome(container, isCurrent);
    else if (view === 'offline' || view === 'downloads_offline') await renderOfflineDownloads(container);
    else if (view === 'profile') await renderProfile(container, param);
    else if (view === 'community' || view === 'users') await renderCommunity(container);
    else if (view === 'party') await party.renderPartyList(container);
    else if (view === 'party_room') await party.renderPartyRoom(container, param);
    else if (view === 'library') await library.renderLibrary(container, param);
    else if (view === 'mix') {
      await renderMix(container, param);
      await trackRecentMix(param);
    } else if (view === 'wrapped') await renderWrapped(container, param);
    else if (view === 'search') renderSearch(container);
    else if (view === 'public') await playlists.renderPublicPlaylists(container);
    else if (view === 'discover_radios') await radio.renderRadio(container);
    else if (view === 'discovery') await renderDiscovery(container);
    else if (view === 'artist') await renderArtist(container, param);
    else if (view === 'favorites') await favorites.renderFavorites(container);
    else if (view === 'playlist') {
      await playlists.renderPlaylist(container, param);
      await trackRecentPlaylist(param);
    } else if (view === 'system_monitor') await renderExternalView(container, '/admin/system', isCurrent);
    else if (view === 'admin_downloads') await renderExternalView(container, '/admin/downloads', isCurrent);
    else if (view === 'admin_delete_requests')
      await renderExternalView(container, '/admin/song-delete-requests', isCurrent);
    else if (view === 'settings_user') await renderExternalView(container, '/user/settings', isCurrent);
    else if (view === 'help') await renderExternalView(container, '/help', isCurrent);
  } catch (e) {
    console.error(e);
    if (isCurrent())
      container.innerHTML = `<div class="empty-state" style="color:#e74c3c;">Error: ${ui.escHtml(e.message)}</div>`;
  }
}

// === EXTERNAL VIEW (HTMX) ===

export async function renderExternalView(container, url, isCurrent = () => true) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const res = await fetch(url, { credentials: 'include', cache: 'no-store', signal: controller.signal });
    if (!isCurrent()) return;
    if (res.redirected && new URL(res.url).pathname !== url) {
      window.location.assign(res.url);
      return;
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    if (!isCurrent()) return;

    const parser = new DOMParser();
    const doc = parser.parseFromString(text, 'text/html');
    const newContent = doc.getElementById('view-container');

    if (newContent) {
      // Use replaceChildren with DOM nodes — avoids re-serialising/parsing HTML
      // and keeps the browser from re-executing inline scripts in the fragment.
      container.replaceChildren(...Array.from(newContent.childNodes));
    } else {
      const main = doc.querySelector('main');
      if (main) container.replaceChildren(...Array.from(main.childNodes));
      else container.innerHTML = text; // Last resort: raw unparsed text
    }

    ui.refreshIcons(container);
    ui.initSettingsTabs(container);
    syncThemeControls(container);

    if (typeof htmx !== 'undefined') {
      htmx.process(container);
    }

    if (url === '/user/settings') {
      initUserSettingsView(container);
    }
    if (url === '/admin/system') {
      initSystemMonitor(container);
    }
    if (url === '/admin/downloads') {
      initDownloadManager(container);
    }
  } catch (e) {
    console.error('External view load failed', url, e);
    if (!isCurrent()) return;
    const reason = controller.signal.aborted
      ? ui.t('common.request_timeout', 'The server took too long to respond.')
      : e.message;
    container.innerHTML = `<div class="empty-state" role="alert"><p>${ui.t('common.load_failed', 'Could not load this page.')}</p><p>${ui.escHtml(url)} · ${ui.escHtml(reason)}</p><button type="button" class="btn-secondary-lg">${ui.t('common.retry', 'Retry')}</button></div>`;
    container.querySelector('button').onclick = () => renderExternalView(container, url, isCurrent);
  } finally {
    clearTimeout(timer);
  }
}

export function initSpaHistory() {
  if (window.__aoedeSpaHistoryBound) return;
  window.__aoedeSpaHistoryBound = true;
  if (!window.history.state?.aoedeView) {
    const initialViews = {
      '/admin/system': 'system_monitor',
      '/admin/downloads': 'admin_downloads',
      '/admin/song-delete-requests': 'admin_delete_requests',
      '/user/settings': 'settings_user',
      '/help': 'help'
    };
    const initial = initialViews[window.location.pathname];
    if (initial) _pushSpaHistory(initial, null, true);
  }

  window.addEventListener('popstate', (event) => {
    const historyState = event.state;
    if (historyState?.aoedeView) {
      loadView(historyState.aoedeView, historyState.aoedeParam ?? null, { pushHistory: false });
      return;
    }
    if (
      window.location.pathname === '/portal' ||
      window.location.pathname === '/' ||
      window.location.pathname === '/index.html'
    ) {
      loadView('home', null, { pushHistory: false });
    }
  });
}

document.body.addEventListener('htmx:afterSwap', (event) => {
  if (event.target?.id !== 'view-container') return;
  ui.initSettingsTabs(event.target);
  syncThemeControls(event.target);
  initUserSettingsView(event.target);
  const username = window.USER_DATA?.username;
  if (!username) return;
  const timestamp = Date.now();
  const avatarImgs = document.querySelectorAll('.user-menu .avatar-circle img, .m-topbar-avatar img');
  avatarImgs.forEach((img) => {
    img.src = `/user/avatar/${encodeURIComponent(username)}?t=${timestamp}`;
  });
});

// === HOME VIEW ===
function _renderQuickPicksGrid() {
  const likedTile = `
    <button class="quick-pick" onclick="loadView('favorites')" title="Liked Songs">
        <div class="quick-pick-cover quick-pick-cover-favorites">
            <i data-lucide="heart"></i>
        </div>
        <div class="quick-pick-name">Liked Songs</div>
    </button>`;

  const items = [];
  state.recentPlaylists.forEach((pl) => items.push({ kind: 'playlist', data: pl }));
  state.recentMixes.forEach((m) => items.push({ kind: 'mix', data: m }));
  state.recentRadios.forEach((r) => items.push({ kind: 'radio', data: r }));

  // 8 total slots in the 4×2 grid; Liked occupies the first one.
  const slots = items.slice(0, 7);

  const tile = ({ kind, data }) => {
    if (kind === 'playlist') {
      const name = ui.escHtml(data.name || 'Playlist');
      const thumb = data.thumbnail && !data.thumbnail.includes('default') ? data.thumbnail : '';
      const fallback = data.source_playlist_id ? 'refresh-cw' : data.is_public ? 'globe' : 'list-music';
      return `
        <button class="quick-pick" onclick="loadView('playlist', '${ui.escJs(String(data.id))}')" title="${name}">
            <div class="quick-pick-cover">
                ${
                  thumb
                    ? `<img src="${ui.escHtml(thumb)}" alt="" loading="lazy" decoding="async" onerror="this.outerHTML='<i data-lucide=\\'${fallback}\\'></i>'">`
                    : `<i data-lucide="${fallback}"></i>`
                }
            </div>
            <div class="quick-pick-name">${name}</div>
        </button>`;
    }
    if (kind === 'mix') {
      const name = ui.escHtml(data.title || 'Mix');
      const keyJs = ui.escJs(data.key || '');
      const thumb = data.thumbnail && !data.thumbnail.includes('default') ? data.thumbnail : '';
      return `
        <button class="quick-pick" onclick="loadView('mix', '${keyJs}')" title="${name}">
            <div class="quick-pick-cover quick-pick-cover-mix">
                ${
                  thumb
                    ? `<img src="${ui.escHtml(thumb)}" alt="" loading="lazy" decoding="async" onerror="this.outerHTML='<i data-lucide=\\'sparkles\\'></i>'">`
                    : `<i data-lucide="sparkles"></i>`
                }
            </div>
            <div class="quick-pick-name">${name}</div>
        </button>`;
    }
    // radio
    const name = ui.escHtml(data.name || 'Saved Radio');
    const nameJs = ui.escJs(data.name || 'Saved Radio');
    const idJs = ui.escJs(data.id || '');
    const url = encodeURIComponent(data.streamUrl || '');
    return `
      <button class="quick-pick" onclick="playSavedRadio('${url}', '${nameJs}', '${idJs}')" title="${name}">
          <div class="quick-pick-cover quick-pick-cover-radio">
              <i data-lucide="radio"></i>
          </div>
          <div class="quick-pick-name">${name}</div>
      </button>`;
  };

  return `<div class="quick-picks-grid" role="region" aria-label="${ui.escHtml(ui.t('home.quick_access', 'Quick access'))}">${likedTile}${slots.map(tile).join('')}</div>`;
}

let shelfResizeObserver = null;

function syncShelfNavigation(rail) {
  const buttons = rail.closest('.home-shelf').querySelectorAll('.shelf-nav');
  if (buttons.length !== 2) return;
  buttons[0].disabled = rail.scrollLeft <= 1;
  buttons[1].disabled = rail.scrollLeft + rail.clientWidth >= rail.scrollWidth - 1;
}

function homeShelf(title, cards) {
  return `<section class="shelf-section home-shelf">
    <div class="shelf-header"><h2 class="shelf-title">${ui.escHtml(title)}</h2>
      <div class="shelf-navigation">
        <button type="button" class="shelf-nav" onclick="scrollHomeShelf(this, -1)" aria-label="${ui.escHtml(ui.t('nav.previous_items', 'Previous items'))}"><i data-lucide="chevron-left"></i></button>
        <button type="button" class="shelf-nav" onclick="scrollHomeShelf(this, 1)" aria-label="${ui.escHtml(ui.t('nav.next_items', 'Next items'))}"><i data-lucide="chevron-right"></i></button>
      </div>
    </div>
    <div class="grid-shelf home-rail" tabindex="0" aria-label="${ui.escHtml(title)}">${cards}</div>
  </section>`;
}

export function scrollHomeShelf(button, direction) {
  const rail = button.closest('.home-shelf')?.querySelector('.home-rail');
  if (!rail) return;
  rail.scrollBy({
    left: direction * rail.clientWidth * 0.8,
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
  });
}

const renderedMarkup = new WeakMap();

function updateHomeSection(container, name, html) {
  const section = container.querySelector(`[data-home-section="${name}"]`);
  if (!section || renderedMarkup.get(section) === html) return;
  section.querySelectorAll('.home-rail').forEach((rail) => shelfResizeObserver?.unobserve(rail));
  section.innerHTML = html;
  renderedMarkup.set(section, html);
  ui.refreshIcons(section);
  if (!shelfResizeObserver && typeof window.ResizeObserver === 'function') {
    shelfResizeObserver = new window.ResizeObserver((entries) =>
      entries.forEach(({ target }) => syncShelfNavigation(target))
    );
  }
  section.querySelectorAll('.home-rail').forEach((rail) => {
    shelfResizeObserver?.observe(rail);
    rail.addEventListener('scroll', () => syncShelfNavigation(rail), { passive: true });
  });
}

function refreshHomeQuickPicks() {
  const container = document.getElementById('view-container');
  if (container?.querySelector('[data-home-root]'))
    updateHomeSection(container, 'quick-picks', _renderQuickPicksGrid());
}

export async function renderHome(container, isCurrent = () => true) {
  if (!isCurrent()) return;
  let sections = [];
  let mixes = [];
  let wrapped = null;
  let partyRooms = [];
  state.setCurrentViewList([]);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  if (!container.querySelector('[data-home-root]')) {
    const username = ui.escHtml(window.USER_DATA?.username || 'User');
    container.innerHTML = `<section class="home-overview" data-home-root>
      ${ui.homeTabsBar('all')}
      <div class="hero-section">
          <div class="hero-kicker">${ui.t('home.hero_kicker', 'Your library, tuned for right now')}</div>
          <h1 class="hero-greeting"><span class="vitrine-home-title">${ui.t('menu.home', 'Home')}</span><span class="home-greeting-copy">${ui.getGreeting()}, <span class="hero-username">${username}</span></span></h1>
      </div>
      <div data-home-section="wrapped" style="display:contents"></div>
      <div data-home-section="quick-picks" style="display:contents"></div>
    </section>
    <div data-home-section="parties" style="display:contents"></div>
    <div data-home-section="mixes" style="display:contents"></div>
    <div data-home-section="recommendations" style="display:contents"></div>`;
    ui.refreshIcons(container);
  }
  updateHomeSection(container, 'quick-picks', _renderQuickPicksGrid());
  container.setAttribute('aria-busy', 'true');

  try {
    if (!window.AOEDE_NATIVE_OFFLINE && typeof navigator !== 'undefined' && navigator.onLine) {
      const currentYear = new Date().getFullYear();
      await Promise.allSettled([
        api.fetchRecommendations({ signal: controller.signal }).then((value) => {
          sections = value || [];
          paint('recommendations', sections.map((s) => homeShelf(s.title, s.items.map(createCard).join(''))).join(''));
        }),
        api.fetchMixes({ signal: controller.signal }).then((value) => {
          mixes = value || [];
          paint(
            'mixes',
            mixes.length ? homeShelf(ui.t('home.your_mixes', 'Your Mixes'), mixes.map(createMixCard).join('')) : ''
          );
        }),
        api.fetchWrapped(currentYear, { summary: false, signal: controller.signal }).then((value) => {
          wrapped = value && !value.error && value.visible !== false && value.enabled !== false ? value : null;
          paint('wrapped', wrapped ? createWrappedHomeCard(wrapped) : '');
        }),
        party.fetchRooms({ signal: controller.signal }).then((value) => {
          partyRooms = value || [];
          paint('parties', party.renderHomeShelf(partyRooms));
        })
      ]);
      if (isCurrent()) offlineStore.saveLibrarySnapshot('home_feed', { sections, mixes, wrapped, partyRooms });
    } else {
      const cached = await offlineStore.getLibrarySnapshot('home_feed');
      paintCached(cached);
    }
  } catch (e) {
    console.error('Recs error:', e);
    const cached = await offlineStore.getLibrarySnapshot('home_feed');
    paintCached(cached);
  } finally {
    clearTimeout(timer);
    if (isCurrent()) container.removeAttribute('aria-busy');
  }

  function paint(name, html) {
    if (!isCurrent()) return;
    updateHomeSection(container, name, html);
  }

  function paintCached(cached) {
    if (!cached) return;
    paint('wrapped', cached.wrapped ? createWrappedHomeCard(cached.wrapped) : '');
    paint(
      'mixes',
      cached.mixes?.length
        ? homeShelf(ui.t('home.your_mixes', 'Your Mixes'), cached.mixes.map(createMixCard).join(''))
        : ''
    );
    paint(
      'recommendations',
      (cached.sections || []).map((s) => homeShelf(s.title, s.items.map(createCard).join(''))).join('')
    );
    // Remote party membership must be revalidated online, not restored from an offline snapshot.
    paint('parties', '');
  }
}

function createWrappedHomeCard(wrapped) {
  const year = Number(wrapped.year || new Date().getFullYear());
  const listenedTime = formatListeningDuration(wrapped.minutes_listened);
  const topSong = wrapped.top_songs?.[0];
  const topArtist = wrapped.top_artists?.[0]?.artist || 'your library';
  return `
        <section class="wrapped-home-card">
            <div>
                <h2>Aoede Wrapped ${year}</h2>
                <p>${wrapped.minutes_listened != null ? `${listenedTime} listened · Top artist: ${ui.escHtml(topArtist)}` : 'Your year in music is ready. Explore your listening highlights.'}</p>
                ${topSong ? `<p class="wrapped-home-sub">#1 song: ${ui.escHtml(topSong.title)} — ${ui.escHtml(topSong.artist)}</p>` : ''}
            </div>
            <a class="btn-secondary-lg" href="/wrapped/story/${year}">
                <i data-lucide="sparkles"></i>
                <span>Watch Wrapped</span>
            </a>
        </section>`;
}

function formatUnit(value, singular, plural) {
  const formatted = Number.isInteger(value)
    ? value.toLocaleString()
    : value.toLocaleString(undefined, { maximumFractionDigits: 1 });
  return `${formatted} ${value === 1 ? singular : plural}`;
}

function formatListeningDuration(minutes) {
  const parsed = Number(minutes || 0);
  if (!Number.isFinite(parsed) || parsed <= 0) return '0 seconds';

  const seconds = parsed * 60;
  if (seconds < 60) return formatUnit(Math.round(seconds), 'second', 'seconds');
  if (parsed < 60) return formatUnit(Math.round(parsed), 'minute', 'minutes');

  const hours = parsed / 60;
  if (hours < 24) return formatUnit(Number(hours.toFixed(hours >= 10 ? 0 : 1)), 'hour', 'hours');

  const days = hours / 24;
  if (days < 365) return formatUnit(Number(days.toFixed(days >= 10 ? 0 : 1)), 'day', 'days');

  const years = days / 365;
  return formatUnit(Number(years.toFixed(years >= 10 ? 0 : 1)), 'year', 'years');
}

function renderWrappedSprint(sprint = []) {
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const activeMonths = sprint.filter((month) => month.artists?.length);
  if (!activeMonths.length) return '<p>No monthly artist movement yet.</p>';

  return `<div class="wrapped-sprint">${activeMonths
    .map((month) => {
      const leader = month.artists[0];
      return `<div class="wrapped-sprint-row">
                <span>${months[(Number(month.month) || 1) - 1] || month.month}</span>
                <strong>${ui.escHtml(leader.artist)}</strong>
                <em>${Number(leader.stream_count || 0)} plays</em>
            </div>`;
    })
    .join('')}</div>`;
}

function renderWrappedParty(party) {
  if (!party || party.visible === false || party.enabled === false)
    return '<p>Party rankings are not available yet.</p>';

  const minutes = party.most_minutes_listened || [];
  const repeaters = party.biggest_repeaters || [];
  return `
        <div class="wrapped-party-grid">
            <div>
                <h2>Most Listening Time</h2>
                ${
                  minutes.length
                    ? `<ol class="wrapped-rank-list">${minutes
                        .slice(0, 5)
                        .map(
                          (item) =>
                            `<li><span>#${item.rank} ${ui.escHtml(item.username)}</span><strong>${formatListeningDuration(item.minutes_listened)}</strong></li>`
                        )
                        .join('')}</ol>`
                    : '<p>No user ranking yet.</p>'
                }
            </div>
            <div>
                <h2>Biggest Repeaters</h2>
                ${
                  repeaters.length
                    ? `<ol class="wrapped-rank-list">${repeaters
                        .slice(0, 5)
                        .map(
                          (item, index) =>
                            `<li><span>#${index + 1} ${ui.escHtml(item.username)}</span><strong>${Number(item.stream_count || 0)} plays</strong></li>`
                        )
                        .join('')}</ol>`
                    : '<p>No repeat ranking yet.</p>'
                }
            </div>
        </div>`;
}

export async function renderWrapped(container, yearParam = null) {
  const year = Number(yearParam || new Date().getFullYear());
  const [wrapped, party] = await Promise.all([api.fetchWrapped(year), api.fetchWrappedParty(year).catch(() => null)]);
  if (!wrapped || wrapped.enabled === false || wrapped.visible === false || wrapped.error) {
    container.innerHTML = `<div class="empty-state glass-panel"><p>Wrapped is not available right now.</p></div>`;
    return;
  }

  const topSongs = wrapped.top_songs_playlist?.tracks || [];
  const topArtists = wrapped.top_artists || [];
  const tracks = topSongs.map((item) => ({
    ...item,
    id: item.db_id || item.id,
    db_id: item.db_id || item.id,
    is_local: true,
    source: 'local'
  }));
  state.setCurrentViewList(tracks);
  const listenedTime = formatListeningDuration(wrapped.minutes_listened);

  container.innerHTML = `
        <section class="wrapped-shell wrapped-enter">
            <div class="wrapped-summary">
                <div>
                    <h1>Aoede Wrapped ${ui.escHtml(String(wrapped.year))}</h1>
                    <p>${listenedTime} listened · ${wrapped.event_count || 0} tracked listens</p>
                </div>
                <div class="wrapped-actions">
                    <a class="btn-secondary-lg" href="/wrapped/story/${year}">
                        <i data-lucide="sparkles"></i>
                        <span>Watch Story</span>
                    </a>
                    <button class="btn-primary-lg" onclick="saveWrappedTopSongsPlaylist(${year}, this)" ${tracks.length ? '' : 'disabled'}>
                        <i data-lucide="save"></i>
                        <span>Save Top Songs</span>
                    </button>
                </div>
            </div>
            <div class="wrapped-stats">
                <div><span>Listened</span><strong>${listenedTime}</strong></div>
                <div><span>Top artist</span><strong>${ui.escHtml(topArtists[0]?.artist || 'No data')}</strong></div>
                <div><span>Top song</span><strong>${ui.escHtml(tracks[0]?.title || 'No data')}</strong></div>
            </div>
            <div class="wrapped-grid">
                <article class="wrapped-panel">
                    <h2>Top Songs</h2>
                    ${
                      tracks.length
                        ? `<div class="track-list">${tracks
                            .slice(0, 20)
                            .map((t, i) => createTrackRow(t, i, null))
                            .join('')}</div>`
                        : '<p>No listening data yet.</p>'
                    }
                </article>
                <article class="wrapped-panel">
                    <h2>Top Artists</h2>
                    ${
                      topArtists.length
                        ? `<ol class="wrapped-artist-list">${topArtists
                            .map(
                              (artist) =>
                                `<li><span>${ui.escHtml(artist.artist)}</span><strong>${Number(artist.stream_count || 0)} plays</strong></li>`
                            )
                            .join('')}</ol>`
                        : '<p>No artist data yet.</p>'
                    }
                    <div class="wrapped-message">
                        <h2>${ui.escHtml(wrapped.artist_clip?.title || 'A message from Aoede')}</h2>
                        <p>${ui.escHtml(wrapped.artist_clip?.message || '')}</p>
                    </div>
                </article>
            </div>
            <div class="wrapped-grid">
                <article class="wrapped-panel">
                    <h2>Top Artist Sprint</h2>
                    ${renderWrappedSprint(wrapped.top_artist_sprint || [])}
                </article>
                <article class="wrapped-panel">
                    <h2>Wrapped Party</h2>
                    ${renderWrappedParty(party)}
                </article>
            </div>
        </section>`;
  ui.refreshIcons(container);
}

// === SEARCH VIEW ===
// Both search inputs feed the same query and debounce pipeline.

export function renderSearch(container) {
  const seedQuery = (document.getElementById('topbar-search-input')?.value || '').trim();
  container.innerHTML = `
      <section class="search-shell">
        <header class="search-heading"><h1 class="hero-greeting">${ui.t('menu.search', 'Search')}</h1><p>${ui.t('search.browse_subtitle', 'Find music across your library and connected sources.')}</p></header>
        <div class="m-search-pill">
            <i data-lucide="search" class="m-search-pill-icon"></i>
            <input
                type="text"
                id="m-search-input"
                class="m-search-pill-input"
                placeholder="${ui.escHtml(ui.t('search.placeholder', 'Artists, songs, or playlists'))}"
                aria-label="${ui.escHtml(ui.t('menu.search', 'Search'))}"
                autocomplete="off"
                spellcheck="false"
                value="${ui.escHtml(seedQuery)}"
                oninput="handleTopbarSearch(this.value)">
        </div>
        <div class="source-chips">
            <button type="button" class="chip active" onclick="setSource(this, 'all')">${ui.t('search.chip_all', 'All')}</button>
            <button type="button" class="chip spotify" onclick="setSource(this, 'spotify')">${ui.t('search.chip_spotify', 'Spotify')}</button>
            <button type="button" class="chip youtube" onclick="setSource(this, 'youtube')">${ui.t('search.chip_youtube', 'YouTube')}</button>
            <button type="button" class="chip lastfm" onclick="setSource(this, 'lastfm')">${ui.t('search.chip_lastfm', 'Last.fm')}</button>
            <button type="button" class="chip musicbrainz" onclick="setSource(this, 'musicbrainz')">${ui.t('search.chip_musicbrainz', 'MusicBrainz')}</button>
            <button type="button" class="chip" onclick="setSource(this, 'local')">${ui.t('search.chip_local', 'Local')}</button>
            <button type="button" class="chip" onclick="setSource(this, 'users')"><i data-lucide="users" width="13" height="13" style="margin-right:4px;"></i> ${ui.t('search.chip_users', 'Users')}</button>
        </div>
        <label class="collection-search-toggle"><input type="checkbox" role="switch" id="collection-search-toggle" ${search.collectionSearchEnabled ? 'checked' : ''} onchange="setCollectionSearch(this.checked)"> ${ui.t('search.collections', 'Search albums and playlists (Spotify / YouTube)')}</label>
        <div id="search-results"></div>
      </section>`;
  container.querySelectorAll('.source-chips .chip').forEach((chip) => {
    const active = chip.getAttribute('onclick').includes(`'${state.currentSource}'`);
    chip.classList.toggle('active', active);
    chip.setAttribute('aria-pressed', String(active));
  });
  ui.refreshIcons(container);
  search.executeSearch(seedQuery);
}

export async function renderMix(container, mixKey) {
  const mix = await api.fetchMixDetail(mixKey);
  if (!mix || mix.error) {
    container.innerHTML = `<div class="empty-state glass-panel"><p>Mix not available.</p></div>`;
    return;
  }

  const tracks = (mix.items || []).map((item) => ({
    ...item,
    id: item.db_id || item.id,
    db_id: item.db_id || item.id,
    mix_key: mix.key,
    is_local: true,
    source: 'local'
  }));
  state.setCurrentViewList(tracks);
  const mixMeta = MIX_META[mix.key] || {};

  container.innerHTML = `
        <div class="playlist-header-section">
            <div class="playlist-cover-large">
                <img src="${mix.thumbnail || '/static/img/default_cover.png'}" class="mix-cover-img" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">
            </div>
            <div class="playlist-info">
                <p class="playlist-type">Personal Mix</p>
                <div class="playlist-title-row">
                    <h1 class="playlist-title">${ui.escHtml(mix.title || 'Mix')}</h1>
                </div>
                <p class="playlist-stats">${mix.track_count || tracks.length} ${ui.t('common.songs', 'songs')} · ${ui.escHtml(mixMeta.summary || 'Built from your local library')}</p>
                <div class="playlist-actions">
                    ${
                      tracks.length > 0
                        ? `
                    <button onclick="playPlaylistInOrder()" class="btn-primary-lg playlist-action-btn" title="${ui.escHtml(ui.t('player.play', 'Play'))}" aria-label="${ui.escHtml(ui.t('player.play', 'Play'))}">
                        <i data-lucide="play" width="20" height="20"></i>
                        <span class="playlist-btn-label">${ui.t('player.play', 'Play')}</span>
                    </button>
                    <button onclick="playPlaylistShuffle()" class="btn-secondary-lg playlist-action-btn" title="${ui.escHtml(ui.t('player.shuffle', 'Shuffle'))}" aria-label="${ui.escHtml(ui.t('player.shuffle', 'Shuffle'))}">
                        <i data-lucide="shuffle" width="20" height="20"></i>
                        <span class="playlist-btn-label">${ui.t('player.shuffle', 'Shuffle')}</span>
                    </button>`
                        : ''
                    }
                    <button onclick="showSaveMixModal('${ui.escJs(mix.key)}', '${ui.escJs(mix.title || 'Mix')}')" class="btn-secondary-lg playlist-action-btn" title="${ui.escHtml(ui.t('player.save_as_playlist', 'Save as playlist'))}" aria-label="${ui.escHtml(ui.t('player.save_as_playlist', 'Save as playlist'))}">
                        <i data-lucide="save" width="20" height="20"></i>
                        <span class="playlist-btn-label">${ui.t('player.save_as_playlist', 'Save as Playlist')}</span>
                    </button>
                </div>
            </div>
        </div>
        ${
          tracks.length > 0
            ? `<div class="track-list">${tracks.map((t, i) => createTrackRow(t, i, null)).join('')}</div>`
            : '<div class="empty-state glass-panel"><p>This mix is empty.</p></div>'
        }`;
  lucide.createIcons();
}

// === CARD COMPONENT ===

export function createCard(item) {
  const img = item.thumbnail || '/static/img/default_cover.png';
  const data = btoa(encodeURIComponent(JSON.stringify(item)));
  const sourceMap = {
    spotify: { icon: 'music', color: '#1DB954' },
    youtube: { icon: 'tv', color: '#ff0000' },
    lastfm: { icon: 'radio', color: '#d51007' },
    musicbrainz: { icon: 'disc-3', color: '#BA478F' },
    local: { icon: 'hard-drive', color: '#ffffff' }
  };
  const src = sourceMap[item.source] || sourceMap['local'];
  const sourceIcon = src.icon;
  const sourceColor = src.color;
  const canAddToPlaylist = (item.is_local && item.db_id) || item.fed_instance_id != null;

  return `<div class="card" onclick="handleCardClick('${data}', this)">
        <div class="card-img-container cover-loading">
            <img src="${ui.escHtml(img)}" loading="lazy" decoding="async"
                 onload="this.closest('.cover-loading')?.classList.remove('cover-loading')"
                 onerror="this.closest('.cover-loading')?.classList.remove('cover-loading'); this.src='/static/img/default_cover.png'">
            <div style="position:absolute;top:8px;right:8px;background:rgba(0,0,0,0.7);border-radius:50%;padding:4px;width:24px;height:24px;display:flex;align-items:center;justify-content:center;">
                <i data-lucide="${sourceIcon}" style="color:${sourceColor};width:14px;height:14px;"></i>
            </div>
            <div class="play-overlay">
                <div style="display:flex;gap:8px;align-items:center;">
                    ${!item.is_local ? `<button class="play-btn-card" onclick="event.stopPropagation(); playPreview('${data}')" title="Preview" style="background:#444; color:white;"><i data-lucide="eye"></i></button>` : ''}
                    <button class="play-btn-card" onclick="event.stopPropagation(); handleCardClick('${data}', this.closest('.card'))"><i data-lucide="${item.is_local || item.fed_instance_id != null ? 'play' : 'download'}"></i></button>
                    ${canAddToPlaylist ? `<button class="play-btn-card play-btn-add-pl" onclick="event.stopPropagation(); showAddToPlaylistModal('${ui.escJs(String(item.db_id || item.id))}')" title="Add to playlist"><i data-lucide="plus"></i></button>` : ''}
                </div>
            </div>
        </div>
        <div class="card-title">${ui.escHtml(item.title || 'Unknown')}</div>
        <div class="card-subtitle">${ui.escHtml(item.artist || 'Unknown')}</div>
    </div>`;
}

// === PLAYLIST CARD COMPONENT ===

export function createPlaylistCard(pl) {
  const thumb = pl.thumbnail || '/static/img/default_cover.png';
  const hasThumb = pl.thumbnail && !pl.thumbnail.includes('default');
  const ownerLine = pl.owner_username && !pl.is_owner ? `By ${ui.escHtml(pl.owner_username)} - ` : '';
  const visibilityLabel =
    pl.source_playlist_id || pl.source_instance_id ? 'Synced copy' : pl.is_public ? 'Public' : 'Private';

  return `<div class="card glass-hover" onclick="loadView('playlist', '${ui.escJs(String(pl.id))}')">
        <div class="card-img-container playlist-card-bg">
            ${hasThumb ? `<img src="${ui.escHtml(thumb)}" class="card-img" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">` : `<i data-lucide="list-music" class="playlist-icon-large"></i>`}
        </div>
        <div class="playlist-card-copy">
            <div class="card-title">${ui.escHtml(pl.name)}</div>
            <div class="card-subtitle">${pl.kind === 'album' ? 'Album · ' : ''}${ownerLine}${Number(pl.track_count) || 0} tracks - ${visibilityLabel}${pl.is_federated ? ` · ${ui.escHtml(pl.fed_instance_name)}` : ''}</div>
        </div>
    </div>`;
}

export function createMixCard(mix) {
  const thumb = mix.thumbnail || '/static/img/default_cover.png';
  const mixMeta = MIX_META[mix.key] || {};
  return `<div class="card glass-hover" onclick="loadView('mix', '${ui.escJs(mix.key)}')">
        <div class="card-img-container playlist-card-bg">
            <img src="${ui.escHtml(thumb)}" class="card-img mix-cover-img" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">
        </div>
        <div class="playlist-card-copy">
            <div class="card-title">${ui.escHtml(mix.title || 'Mix')}</div>
            <div class="card-subtitle">${ui.escHtml(mixMeta.short || `${mix.track_count || 0} tracks`)}</div>
        </div>
    </div>`;
}

export function showTrackDeleteRequestModal(trackId, title, artist = '') {
  const safeTitle = ui.escHtml(title || 'Unknown track');
  const safeArtist = ui.escHtml(artist || 'Unknown artist');
  const modal = document.createElement('div');
  modal.className = 'modal-overlay';
  modal.onclick = () => modal.remove();
  modal.innerHTML = `
        <div class="modal" onclick="event.stopPropagation()">
            <h2 style="margin-bottom: 10px;">Request Song Deletion</h2>
            <p style="color: var(--text-sub); margin-bottom: 14px;">
                Send a deletion request for <strong style="color:white;">${safeTitle}</strong> · ${safeArtist}.
            </p>
            <textarea id="track-delete-reason" class="monitor-input" rows="4" maxlength="1000" placeholder="${ui.escHtml(ui.t('delete_request.reason_placeholder', 'Why should this track be removed? (required)'))}"></textarea>
            <div class="modal-actions" style="margin-top: 16px;">
                <button class="modal-btn-cancel" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
                <button class="modal-btn-danger" onclick="submitTrackDeleteRequest(${Number(trackId)}, this)">Send Request</button>
            </div>
        </div>`;
  document.body.appendChild(modal);
}

export async function submitTrackDeleteRequest(trackId, buttonEl) {
  const modal = buttonEl?.closest('.modal-overlay') || document.querySelector('.modal-overlay');
  const reasonInput = modal?.querySelector('#track-delete-reason');
  const reason = (reasonInput?.value || '').trim();
  if (reason.length < 8) {
    ui.showToast('Please provide a clearer reason (min 8 chars).', 'error');
    reasonInput?.focus();
    return;
  }
  if (buttonEl) buttonEl.disabled = true;
  const result = await api.submitTrackDeleteRequest(trackId, reason);
  if (buttonEl) buttonEl.disabled = false;
  if (!result.ok) {
    ui.showToast(result.error || 'Could not send request', 'error');
    return;
  }
  ui.showToast(result.message || 'Request sent to admins', 'success');
  modal?.remove();
}

// === TRACK ROW COMPONENT ===

// Source icon mapping (same as createCard) — used by mobile track rows
// where the dedicated source column is hidden, so we need an inline
// indicator near the more-vertical button.
const TRACK_SOURCE_ICONS = {
  spotify: { icon: 'music', color: '#1DB954' },
  youtube: { icon: 'tv', color: '#ff4444' },
  lastfm: { icon: 'radio', color: '#d51007' },
  musicbrainz: { icon: 'disc-3', color: '#BA478F' },
  local: { icon: 'hard-drive', color: '#9aa3b2' }
};

export function createTrackRow(item, idx, playlistId = null) {
  const img = item.thumbnail || '/static/img/default_cover.png';
  const data = btoa(encodeURIComponent(JSON.stringify(item)));
  const src = item.source || 'local';
  const srcMeta = TRACK_SOURCE_ICONS[src] || TRACK_SOURCE_ICONS.local;
  const isLiked = state.userFavorites.has(item.db_id || item.id);
  const canLike = item.is_local && item.db_id;
  const canAddToPlaylist = (item.is_local && item.db_id) || item.fed_instance_id != null;
  const isActive = item.id === state.currentTrack?.id;
  const isOfflineAvailable = offlineStore.isTrackAvailableOfflineSync(item.db_id || item.id);
  const isDeviceOffline = typeof navigator !== 'undefined' && !navigator.onLine;
  const isUnavailableOffline = isDeviceOffline && !isOfflineAvailable && !item.is_offline;

  // Federation results are NOT local but ARE playable (we proxy them
  // through /api/federation/proxy). Route them to a dedicated handler
  // so we can short-circuit playback if the peer drops offline
  // between the search render and the click.
  const isFederated = item.source === 'federation' && item.fed_instance_id != null;
  const rowClickAction = isUnavailableOffline
    ? `showToast('This track is not downloaded for offline use.', 'error')`
    : item.is_local
      ? `playFromView(${idx})`
      : isFederated
        ? `playFromView(${idx})`
        : `playPreview('${data}')`;

  const _safeTitle = ui.escJs(item.title || 'Unknown');
  const _safeArtist = ui.escJs(item.artist || 'Unknown');

  return `<div class="track-row glass-hover ${isLiked ? 'liked-row' : ''} ${isActive ? 'active-track' : ''} ${isUnavailableOffline ? 'offline-disabled' : ''}" onclick="${rowClickAction}" data-idx="${idx}">
        <div class="track-num">
            <span class="num-text">${idx + 1}</span>
            <i data-lucide="play" class="hover-play-icon"></i>
            <i data-lucide="bar-chart-2" class="playing-icon"></i>
        </div>
        <div class="track-main">
            <img src="${ui.escHtml(img)}" class="track-cover-sm" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">
            <div class="track-titles">
                <div class="track-name-sm">
                    ${ui.escHtml(item.title || 'Unknown')}
                    ${isOfflineAvailable ? `<span class="track-offline-badge" title="Available offline"><i data-lucide="check-circle" width="13" height="13"></i></span>` : ''}
                    ${
                      isFederated
                        ? `
                        <span class="fed-source-badge ${item.fed_instance_status === 'degraded' ? 'degraded' : ''}"
                              title="From ${ui.escHtml(item.fed_instance_name || 'remote pool')} (${ui.escHtml(item.fed_instance_status || 'unknown')})">
                            <i data-lucide="globe"></i>${ui.escHtml(item.fed_instance_name || 'remote')}
                        </span>`
                        : ''
                    }
                </div>
                <div class="track-artist-sm">
                    <a class="artist-link"
                       onclick="event.stopPropagation(); loadView('artist', '${_safeArtist}')"
                    >${ui.escHtml(item.artist || 'Unknown')}</a>
                </div>
            </div>
        </div>
        <div class="track-source-col"><span class="source-badge ${src}">${src}</span></div>
        <div class="action-btns" onclick="event.stopPropagation()">
            ${!item.is_local ? `<button class="action-btn" onclick="playPreview('${data}')" title="Preview"><i data-lucide="eye"></i></button>` : ''}

            ${
              item.is_local
                ? `<button class="action-btn ${isLiked ? 'liked' : ''}" onclick="toggleFavorite(${item.db_id || item.id}, this)"><i data-lucide="heart"></i></button>`
                : item.fed_instance_id == null
                  ? `<button class="action-btn" onclick="triggerDownload('${data}')" title="Download to Library"><i data-lucide="download"></i></button>`
                  : ''
            }

            ${item.is_local || item.fed_instance_id != null ? `<button class="action-btn" onclick="addToQueue('${data}')" title="Add to Queue"><i data-lucide="list-plus"></i></button>` : ''}
            ${canAddToPlaylist ? `<button class="action-btn" onclick="showAddToPlaylistModal('${ui.escJs(String(item.db_id || item.id))}')"><i data-lucide="plus"></i></button>` : ''}
            ${
              item.is_local
                ? `<button class="action-btn warning" onclick="showTrackDeleteRequestModal(${item.db_id || item.id}, '${_safeTitle}', '${_safeArtist}')" title="Request deletion"><i data-lucide="flag"></i></button>`
                : ''
            }

            ${playlistId ? `<button class="action-btn-danger" onclick="showRemoveFromPlaylistModal('${ui.escJs(String(playlistId))}', '${ui.escJs(String(item.id))}', '${_safeTitle}', ${Number(item.playlist_item_id) || 'null'})"><i data-lucide="trash-2"></i></button>` : ''}

            <!-- Mobile-only source-of-origin glyph; shown via CSS only at
                 <=768 px because desktop still has the full .track-source-col
                 column with the proper text badge. -->
            <span class="track-source-mini" title="${ui.escHtml(src)}">
                <i data-lucide="${srcMeta.icon}" style="color: ${srcMeta.color};"></i>
            </span>

            <!-- Three-dots: context menu on desktop, bottom sheet on mobile -->
            <button class="action-btn action-btn-more" data-track="${data}" data-playlist="${ui.escHtml(playlistId || '')}"
                    onclick="openTrackMenu('${data}', ${playlistId == null ? 'null' : `'${ui.escJs(String(playlistId))}'`}, this)"
                    title="More options">
                <i data-lucide="more-vertical"></i>
            </button>
        </div>
    </div>`;
}

// === PLAYLIST-VIEW TRACK ROW ===
// Spotify-style five-column row used by the playlist detail page:
// number, title (cover + title + artist), album, source badge, duration.
// Lives alongside createTrackRow rather than replacing it because other
// surfaces (search, mix, library) use a narrower 3-column layout and
// would not benefit from the extra columns.
export function createPlaylistTrackRow(item, idx, playlistId = null) {
  const img = item.thumbnail || '/static/img/default_cover.png';
  const data = btoa(encodeURIComponent(JSON.stringify(item)));
  const src = item.source || 'local';
  const isLiked = state.userFavorites.has(item.db_id || item.id);
  const canAddToPlaylist = (item.is_local && item.db_id) || item.fed_instance_id != null;
  const isActive = item.id === state.currentTrack?.id;
  const isOfflineAvailable = offlineStore.isTrackAvailableOfflineSync(item.db_id || item.id);
  const isDeviceOffline = typeof navigator !== 'undefined' && !navigator.onLine;
  const isUnavailableOffline = isDeviceOffline && !isOfflineAvailable && !item.is_offline;

  const _safeTitle = ui.escJs(item.title || 'Unknown');
  const _safeArtist = ui.escJs(item.artist || 'Unknown');
  const duration = ui.fmtTime(item.duration);

  const rowClickAction = isUnavailableOffline
    ? `ui.showToast('This track is not downloaded for offline use.', 'error')`
    : `playFromView(${idx})`;

  return `<div class="track-row playlist-row glass-hover ${isLiked ? 'liked-row' : ''} ${isActive ? 'active-track' : ''} ${isUnavailableOffline ? 'offline-disabled' : ''}" onclick="${rowClickAction}" data-idx="${idx}">
        <div class="track-num">
            <span class="num-text">${idx + 1}</span>
            <i data-lucide="play" class="hover-play-icon"></i>
            <i data-lucide="bar-chart-2" class="playing-icon"></i>
        </div>
        <div class="track-main">
            <img src="${ui.escHtml(img)}" class="track-cover-sm" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">
            <div class="track-titles">
                <div class="track-name-sm">
                    ${ui.escHtml(item.title || 'Unknown')}
                    ${isOfflineAvailable ? `<span class="track-offline-badge" title="Available offline"><i data-lucide="check-circle" width="13" height="13"></i></span>` : ''}
                </div>
                <div class="track-artist-sm">
                    <a class="artist-link"
                       onclick="event.stopPropagation(); loadView('artist', '${_safeArtist}')"
                    >${ui.escHtml(item.artist || 'Unknown')}</a>
                </div>
            </div>
        </div>
        <div class="track-album-col" title="${ui.escHtml(item.album || '')}">${ui.escHtml(item.album || '')}</div>
        <div class="track-source-col"><span class="source-badge ${src}">${src}</span></div>
        <div class="track-duration-col" onclick="event.stopPropagation()">
            ${
              item.is_local
                ? `<button class="action-btn-inline ${isLiked ? 'liked' : ''}" onclick="event.stopPropagation(); toggleFavorite(${item.db_id || item.id}, this)" title="${isLiked ? 'Unlike' : 'Like'}">
                <i data-lucide="heart"></i>
            </button>`
                : ''
            }
            ${canAddToPlaylist ? `<button class="action-btn-inline" onclick="event.stopPropagation(); showAddToPlaylistModal('${ui.escJs(String(item.db_id || item.id))}')" title="Add to playlist"><i data-lucide="plus"></i></button>` : ''}
            ${playlistId ? `<button class="action-btn-inline danger" onclick="event.stopPropagation(); showRemoveFromPlaylistModal('${ui.escJs(String(playlistId))}', '${ui.escJs(String(item.id))}', '${_safeTitle}', ${Number(item.playlist_item_id) || 'null'})" title="Remove from playlist"><i data-lucide="trash-2"></i></button>` : ''}
            <span class="track-duration">${duration}</span>
            <button class="action-btn action-btn-more" data-track="${data}" data-playlist="${ui.escHtml(playlistId || '')}"
                    onclick="event.stopPropagation(); openTrackMenu('${data}', ${playlistId == null ? 'null' : `'${ui.escJs(String(playlistId))}'`}, this)"
                    title="More options">
                <i data-lucide="more-vertical"></i>
            </button>
        </div>
    </div>`;
}

// === MOBILE TRACK ACTIONS BOTTOM SHEET ===

export function closeTrackActionsSheet() {
  const sheet = document.getElementById('track-actions-sheet');
  if (!sheet) return;
  sheet.classList.remove('open');
  // Remove after transition; setTimeout as safety net
  sheet.addEventListener('transitionend', () => sheet.remove(), { once: true });
  setTimeout(() => {
    if (sheet.parentNode) sheet.remove();
  }, 400);
}

export function showTrackActionsSheet(encodedData, playlistId) {
  closeTrackActionsSheet();

  const item = JSON.parse(decodeURIComponent(atob(encodedData)));
  const isLiked = state.userFavorites.has(item.db_id || item.id);
  const canLike = item.is_local && item.db_id;
  const canAddToPlaylist = (item.is_local && item.db_id) || item.fed_instance_id != null;
  const img = item.thumbnail || '/static/img/default_cover.png';
  const safeTitle = ui.escJs(item.title || 'Unknown');
  const safeArtist = ui.escJs(item.artist || 'Unknown');

  const actions = [];

  if (!item.is_local) {
    actions.push(`
      <button class="tas-action-btn" onclick="playPreview('${encodedData}'); closeTrackActionsSheet()">
        <i data-lucide="eye"></i><span>Preview</span>
      </button>`);
  }
  if (canLike) {
    actions.push(`
      <button class="tas-action-btn ${isLiked ? 'liked' : ''}"
              onclick="toggleFavorite(${item.db_id}, this); closeTrackActionsSheet()">
        <i data-lucide="heart"></i><span>${isLiked ? 'Unlike' : 'Like'}</span>
      </button>`);
  }
  if (!item.is_local && item.fed_instance_id == null) {
    actions.push(`
      <button class="tas-action-btn" onclick="triggerDownload('${encodedData}'); closeTrackActionsSheet()">
        <i data-lucide="download"></i><span>Download to Library</span>
      </button>`);
  }
  if (item.is_local) {
    actions.push(`
      <button class="tas-action-btn" onclick="addToQueue('${encodedData}'); closeTrackActionsSheet()">
        <i data-lucide="list-plus"></i><span>${ui.t('player.add_to_queue', 'Add to Queue')}</span>
      </button>`);

    if (offlineStore.isTrackAvailableOfflineSync(item.db_id || item.id)) {
      actions.push(`
        <button class="tas-action-btn danger" onclick="deleteOfflineTrackAction(${item.db_id || item.id}); closeTrackActionsSheet()">
          <i data-lucide="trash-2"></i><span>${ui.t('player.remove_offline', 'Remove from Offline')}</span>
        </button>`);
    } else {
      actions.push(`
        <button class="tas-action-btn" onclick="downloadOfflineTrackAction('${encodedData}'); closeTrackActionsSheet()">
          <i data-lucide="download-cloud"></i><span>${ui.t('player.make_offline', 'Make Available Offline')}</span>
        </button>`);
    }
  }
  if (
    (item.is_local || item.fed_instance_id != null) &&
    window.partyController?.isActive() &&
    window.partyController?.canAddSongs()
  ) {
    actions.push(
      `<button class="tas-action-btn" onclick="addLibraryTrackToParty('${encodedData}'); closeTrackActionsSheet()"><i data-lucide="radio-tower"></i><span>Add to Party Queue</span></button>`
    );
  }
  if (item.fed_instance_id != null)
    actions.push(
      `<button class="tas-action-btn" onclick="addToQueue('${encodedData}'); closeTrackActionsSheet()"><i data-lucide="list-plus"></i><span>Add to Queue</span></button>`
    );
  if (canAddToPlaylist) {
    actions.push(`
      <button class="tas-action-btn" onclick="showAddToPlaylistModal('${ui.escJs(String(item.db_id || item.id))}'); closeTrackActionsSheet()">
        <i data-lucide="plus"></i><span>Add to Playlist</span>
      </button>`);
  }
  if (item.is_local) {
    actions.push(`
      <button class="tas-action-btn warning"
              onclick="showTrackDeleteRequestModal(${item.db_id || item.id}, '${safeTitle}', '${safeArtist}'); closeTrackActionsSheet()">
        <i data-lucide="flag"></i><span>Request Deletion</span>
      </button>`);
  }
  if (playlistId) {
    actions.push(`
      <button class="tas-action-btn danger"
              onclick="showRemoveFromPlaylistModal('${ui.escJs(String(playlistId))}', '${ui.escJs(String(item.id))}', '${safeTitle}', ${Number(item.playlist_item_id) || 'null'}); closeTrackActionsSheet()">
        <i data-lucide="trash-2"></i><span>Remove from Playlist</span>
      </button>`);
  }

  // Smart radio + artist view — available for any track regardless of source.
  actions.push(`
    <button class="tas-action-btn"
            onclick="startSmartRadio('${safeArtist}', '${safeTitle}'); closeTrackActionsSheet()">
      <i data-lucide="radio"></i><span>Start radio</span>
    </button>`);
  actions.push(`
    <button class="tas-action-btn"
            onclick="loadView('artist', '${safeArtist}'); closeTrackActionsSheet()">
      <i data-lucide="user"></i><span>Go to artist</span>
    </button>`);

  const sheet = document.createElement('div');
  sheet.id = 'track-actions-sheet';
  sheet.innerHTML = `
    <div class="tas-overlay" onclick="closeTrackActionsSheet()"></div>
    <div class="tas-sheet">
      <div class="tas-handle-bar"></div>
      <div class="tas-track-info">
        <img src="${img}" class="tas-cover" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">
        <div class="tas-track-meta">
          <div class="tas-title">${ui.escHtml(item.title || 'Unknown')}</div>
          <div class="tas-artist">${ui.escHtml(item.artist || 'Unknown')}</div>
        </div>
      </div>
      <div class="tas-actions">${actions.join('')}</div>
    </div>`;

  document.body.appendChild(sheet);
  // Trigger enter animation on next paint
  requestAnimationFrame(() => requestAnimationFrame(() => sheet.classList.add('open')));
  if (window.lucide) lucide.createIcons();
}

// === DESKTOP RIGHT-CLICK CONTEXT MENU ===
//
// Spotify desktop exposes track management (add to playlist, add to
// queue, like, go to artist, etc.) via a right-click context menu
// instead of inline icon buttons cluttering every row. This mirrors
// that: right-clicking any .track-row opens a floating dropdown at
// the cursor with the same action set as the mobile bottom sheet.

export function closeContextMenu() {
  const menu = document.getElementById('track-context-menu');
  if (!menu) return;
  menu.classList.remove('open');
  setTimeout(() => menu.remove(), 150);
  document.removeEventListener('click', _ctxMenuOutsideClick, true);
  window.removeEventListener('resize', closeContextMenu);
  window.removeEventListener('scroll', _ctxMenuScrollGuard, true);
}

function _ctxMenuOutsideClick(e) {
  const menu = document.getElementById('track-context-menu');
  if (menu && !menu.contains(e.target)) closeContextMenu();
}
// Close on scroll UNLESS the scroll originated from inside the menu
// itself (e.g. scrolling the menu's own playlist/action list).
function _ctxMenuScrollGuard(e) {
  const menu = document.getElementById('track-context-menu');
  if (menu && menu.contains(e.target)) return;
  closeContextMenu();
}

export function showContextMenu(encodedData, playlistId, x, y) {
  closeContextMenu();

  const item = JSON.parse(decodeURIComponent(atob(encodedData)));
  const isLiked = state.userFavorites.has(item.db_id || item.id);
  const canLike = item.is_local && item.db_id;
  const canAddToPlaylist = (item.is_local && item.db_id) || item.fed_instance_id != null;
  const safeTitle = ui.escJs(item.title || 'Unknown');
  const safeArtist = ui.escJs(item.artist || 'Unknown');
  const img = item.thumbnail || '/static/img/default_cover.png';

  const actions = [];

  if (!item.is_local) {
    actions.push(`<div class="ctx-item" onclick="playPreview('${encodedData}'); closeContextMenu()">
      <i data-lucide="eye"></i><span>Preview</span></div>`);
  }
  if (canLike) {
    actions.push(`<div class="ctx-item ${isLiked ? 'liked' : ''}" onclick="toggleFavorite(${item.db_id}, null); closeContextMenu()">
      <i data-lucide="heart"></i><span>${isLiked ? 'Remove from Liked' : 'Save to Liked'}</span></div>`);
  }
  if (!item.is_local && item.fed_instance_id == null) {
    actions.push(`<div class="ctx-item" onclick="triggerDownload('${encodedData}'); closeContextMenu()">
      <i data-lucide="download"></i><span>Download to Library</span></div>`);
  }
  if (item.is_local) {
    actions.push(`<div class="ctx-item" onclick="addToQueue('${encodedData}'); closeContextMenu()">
      <i data-lucide="list-plus"></i><span>${ui.t('player.add_to_queue', 'Add to Queue')}</span></div>`);

    if (offlineStore.isTrackAvailableOfflineSync(item.db_id || item.id)) {
      actions.push(`<div class="ctx-item danger" onclick="deleteOfflineTrackAction(${item.db_id || item.id}); closeContextMenu()">
        <i data-lucide="trash-2"></i><span>${ui.t('player.remove_offline', 'Remove from Offline')}</span></div>`);
    } else {
      actions.push(`<div class="ctx-item" onclick="downloadOfflineTrackAction('${encodedData}'); closeContextMenu()">
        <i data-lucide="download-cloud"></i><span>${ui.t('player.make_offline', 'Make Available Offline')}</span></div>`);
    }
  }
  if (
    (item.is_local || item.fed_instance_id != null) &&
    window.partyController?.isActive() &&
    window.partyController?.canAddSongs()
  ) {
    actions.push(
      `<div class="ctx-item" onclick="addLibraryTrackToParty('${encodedData}'); closeContextMenu()"><i data-lucide="radio-tower"></i><span>Add to Party Queue</span></div>`
    );
  }
  if (item.fed_instance_id != null)
    actions.push(
      `<div class="ctx-item" onclick="addToQueue('${encodedData}'); closeContextMenu()"><i data-lucide="list-plus"></i><span>Add to Queue</span></div>`
    );
  if (canAddToPlaylist) {
    actions.push(`<div class="ctx-item" onclick="openAddToPlaylistFlyout('${ui.escJs(String(item.db_id || item.id))}', this)">
      <i data-lucide="plus"></i><span>Add to Playlist</span></div>`);
  }
  // Smart radio + artist nav — available for any track
  actions.push(`<div class="ctx-item" onclick="startSmartRadio('${safeArtist}', '${safeTitle}'); closeContextMenu()">
    <i data-lucide="radio"></i><span>Start Radio</span></div>`);
  actions.push(`<div class="ctx-item" onclick="loadView('artist', '${safeArtist}'); closeContextMenu()">
    <i data-lucide="user"></i><span>Go to Artist</span></div>`);

  if (item.is_local) {
    actions.push(`<div class="ctx-item warning" onclick="showTrackDeleteRequestModal(${item.db_id || item.id}, '${safeTitle}', '${safeArtist}'); closeContextMenu()">
      <i data-lucide="flag"></i><span>Request Deletion</span></div>`);
  }
  if (playlistId) {
    actions.push(`<div class="ctx-sep"></div>`);
    actions.push(`<div class="ctx-item danger" onclick="showRemoveFromPlaylistModal('${ui.escJs(String(playlistId))}', '${ui.escJs(String(item.id))}', '${safeTitle}', ${Number(item.playlist_item_id) || 'null'}); closeContextMenu()">
      <i data-lucide="trash-2"></i><span>Remove from Playlist</span></div>`);
  }

  const menu = document.createElement('div');
  menu.id = 'track-context-menu';
  menu.className = 'track-context-menu';
  menu.innerHTML = `<div class="ctx-list">${actions.join('')}</div>`;
  document.body.appendChild(menu);

  // Position: clamp to viewport so the menu never overflows the edge.
  requestAnimationFrame(() => {
    const rect = menu.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    let left = x;
    let top = y;
    if (left + rect.width > vw - 8) left = Math.max(8, vw - rect.width - 8);
    if (top + rect.height > vh - 8) top = Math.max(8, vh - rect.height - 8);
    menu.style.left = left + 'px';
    menu.style.top = top + 'px';
    menu.classList.add('open');
  });

  if (window.lucide) lucide.createIcons();

  // Close on outside click, scroll, or resize.
  document.addEventListener('click', _ctxMenuOutsideClick, true);
  window.addEventListener('resize', closeContextMenu);
  window.addEventListener('scroll', _ctxMenuScrollGuard, true);
}

/** Attach right-click handler to track rows. Called once on app init. */
export function initContextMenu() {
  document.addEventListener('contextmenu', (e) => {
    const row = e.target.closest('.track-row');
    if (!row) return;
    e.preventDefault();
    const moreBtn = row.querySelector('.action-btn-more');
    if (moreBtn?.dataset.track)
      showContextMenu(moreBtn.dataset.track, moreBtn.dataset.playlist || null, e.clientX, e.clientY);
  });
}

/** Dispatcher: opens the context menu on desktop (≥769px) or the
 * bottom sheet on mobile. Called by the three-dots button onclick. */
export function openTrackMenu(encodedData, playlistId, anchorEl) {
  if (window.innerWidth <= 768) {
    showTrackActionsSheet(encodedData, playlistId);
  } else if (anchorEl) {
    const r = anchorEl.getBoundingClientRect();
    showContextMenu(encodedData, playlistId, r.left, r.bottom + 4);
  } else {
    showContextMenu(encodedData, playlistId, window.innerWidth / 2, window.innerHeight / 2);
  }
}

/** Opens the Add-to-Playlist flyout anchored to the context-menu item
 * that was clicked. Captures the anchor's rect before closing the
 * context menu (which detaches the element from the DOM). */
export function openAddToPlaylistFlyout(trackId, anchorEl) {
  const rect = anchorEl ? anchorEl.getBoundingClientRect() : null;
  // Use a synthetic anchor element so showAddToPlaylistModal can
  // position relative to where the user clicked.
  closeContextMenu();
  // Small delay so the context menu's removal animation doesn't
  // interfere with the flyout's positioning.
  setTimeout(() => {
    playlists.showAddToPlaylistModal(trackId, rect);
  }, 50);
}

// === CARD CLICK HANDLER ===

export function handleCardClick(data, cardElement) {
  if (cardElement && window.innerWidth <= 768) {
    document.querySelectorAll('.card.active-mobile').forEach((c) => {
      if (c !== cardElement) c.classList.remove('active-mobile');
    });

    if (!cardElement.classList.contains('active-mobile')) {
      cardElement.classList.add('active-mobile');
      return;
    }
  }

  const track = JSON.parse(decodeURIComponent(atob(data)));

  if (track.search_query) {
    const input = document.getElementById('search-input');
    if (input) {
      input.value = track.search_query;
      search.executeSearch(track.search_query);
      return;
    }
  }

  if (track.is_local || track.fed_instance_id != null) player.playTrack(track);
  else downloads.triggerDownload(track);
}

// === LIBRARY SIDEBAR ===

function _sidebarPlaylistRow(pl) {
  const name = ui.escHtml(pl.name || 'Playlist');
  const thumb = pl.thumbnail && !pl.thumbnail.includes('default') ? pl.thumbnail : '';
  const fallbackIcon =
    pl.kind === 'album' ? 'disc-3' : pl.source_playlist_id ? 'refresh-cw' : pl.is_public ? 'globe' : 'list-music';
  const sub =
    pl.kind === 'album'
      ? 'Album'
      : pl.source_playlist_id
        ? 'Synced playlist'
        : pl.is_public
          ? 'Public playlist'
          : 'Playlist';
  return `
    <button type="button" class="sidebar-item" data-sb-key="playlist-${ui.escHtml(pl.id)}" data-kind="${pl.kind === 'album' ? 'album' : 'playlist'}" data-view="playlist" data-param="${ui.escHtml(pl.id)}"
       onclick="loadView('playlist', '${ui.escJs(String(pl.id))}')" title="${name}">
        <div class="sidebar-cover">
            ${
              thumb
                ? `<img src="${ui.escHtml(thumb)}" alt="" loading="lazy" decoding="async" onerror="this.outerHTML='<i data-lucide=\\'${fallbackIcon}\\'></i>'">`
                : `<i data-lucide="${fallbackIcon}"></i>`
            }
        </div>
        <div class="sidebar-meta">
            <div class="sidebar-name">${name}</div>
            <div class="sidebar-sub">${sub}</div>
        </div>
    </button>`;
}

function _sidebarRadioRow(r) {
  const name = ui.escHtml(r.name || 'Saved Radio');
  const nameJs = ui.escJs(r.name || 'Saved Radio');
  const idJs = ui.escJs(r.id || '');
  const url = encodeURIComponent(r.streamUrl || '');
  return `
    <button type="button" class="sidebar-item" data-sb-key="radio-${idJs}" data-kind="radio"
       onclick="playSavedRadio('${url}', '${nameJs}', '${idJs}')" title="${name}">
        <div class="sidebar-cover sidebar-cover-radio">
            <i data-lucide="radio"></i>
        </div>
        <div class="sidebar-meta">
            <div class="sidebar-name">${name}</div>
            <div class="sidebar-sub">Radio</div>
        </div>
    </button>`;
}

function _sidebarMixRow(m) {
  const name = ui.escHtml(m.title || 'Mix');
  const keyJs = ui.escJs(m.key || '');
  const thumb = m.thumbnail && !m.thumbnail.includes('default') ? m.thumbnail : '';
  return `
    <button type="button" class="sidebar-item" data-sb-key="mix-${keyJs}" data-kind="mix" data-view="mix" data-param="${ui.escHtml(m.key || '')}"
       onclick="loadView('mix', '${keyJs}')" title="${name}">
        <div class="sidebar-cover sidebar-cover-mix">
            ${
              thumb
                ? `<img src="${ui.escHtml(thumb)}" alt="" loading="lazy" decoding="async" onerror="this.outerHTML='<i data-lucide=\\'sparkles\\'></i>'">`
                : `<i data-lucide="sparkles"></i>`
            }
        </div>
        <div class="sidebar-meta">
            <div class="sidebar-name">${name}</div>
            <div class="sidebar-sub">Mix</div>
        </div>
    </button>`;
}

function _sidebarCreateButton() {
  return `
    <button type="button" class="sidebar-item sidebar-item-create" data-sb-key="__create__"
            onclick="showCreatePlaylistModal()" title="Create playlist">
        <div class="sidebar-cover sidebar-cover-create">
            <i data-lucide="plus"></i>
        </div>
        <div class="sidebar-meta">
            <div class="sidebar-name">Create playlist</div>
            <div class="sidebar-sub">New</div>
        </div>
    </button>`;
}

export function renderSidebarRecents() {
  const container = document.getElementById('sidebar-items');
  if (!container) return;

  const rows = [];
  const collections = new Map([...state.recentPlaylists, ...state.userPlaylists].map((pl) => [String(pl.id), pl]));
  collections.forEach((pl) => rows.push(_sidebarPlaylistRow(pl)));
  state.recentMixes.forEach((m) => rows.push(_sidebarMixRow(m)));
  state.recentRadios.forEach((r) => rows.push(_sidebarRadioRow(r)));
  rows.push(_sidebarCreateButton());

  const html = rows.join('');
  if (renderedMarkup.get(container) !== html) {
    container.innerHTML = html;
    renderedMarkup.set(container, html);
    ui.refreshIcons(container);
  }
  filterSidebarLibrary();
  window.markBottomNavActive?.(state.currentViewName, state.currentViewParam);

  refreshHomeQuickPicks();
}

export function renderSidebarPlaylists() {
  renderSidebarRecents();
}

export function filterSidebarLibrary() {
  const query = (document.getElementById('sidebar-query')?.value || '').trim().toLocaleLowerCase();
  const kind = document.getElementById('sidebar-kind')?.value || 'all';
  document.querySelectorAll('#sidebar-list [data-kind]').forEach((row) => {
    row.hidden = (kind !== 'all' && row.dataset.kind !== kind) || !row.textContent.toLocaleLowerCase().includes(query);
  });
}

export async function refreshRecentActivity() {
  try {
    const res = await fetch(`${state.API}/recent-activity`);
    if (!res.ok) return;
    const data = await res.json();
    state.setRecentPlaylists(Array.isArray(data.playlists) ? data.playlists : []);
    state.setRecentRadios(Array.isArray(data.radios) ? data.radios : []);
    state.setRecentMixes(Array.isArray(data.mixes) ? data.mixes : []);
    renderSidebarRecents();
  } catch (e) {
    console.error('Failed to refresh recent activity:', e);
  }
}

export async function trackRecentPlaylist(playlistId) {
  if (!/^\d+$/.test(String(playlistId))) return;
  if (!playlistId) return;
  try {
    await fetch(`${state.API}/recent-activity/playlist`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ playlist_id: Number(playlistId) })
    });
    await refreshRecentActivity();
  } catch (e) {
    console.error('Failed to track recent playlist:', e);
  }
}

export async function trackRecentMix(mixKey) {
  if (!mixKey) return;
  // Snapshot the title and cover URL from the freshly-rendered mix view so
  // the sidebar can show the same artwork the user just saw, without
  // needing a follow-up /mixes round trip on the next page load.
  let title = '';
  let thumbnail = '';
  const titleEl = document.querySelector('.playlist-title');
  if (titleEl) title = titleEl.textContent.trim();
  const coverImg = document.querySelector('.playlist-cover-large img');
  if (coverImg && coverImg.src && !coverImg.src.includes('default_cover.png')) {
    thumbnail = coverImg.src;
  }
  try {
    await fetch(`${state.API}/recent-activity/mix`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mix_key: String(mixKey), title, thumbnail })
    });
    await refreshRecentActivity();
  } catch (e) {
    console.error('Failed to track recent mix:', e);
  }
}

// === LOAD USER DATA ===

export async function loadUserData() {
  try {
    if (typeof navigator !== 'undefined' && navigator.onLine) {
      // CRITICAL PATH: only favorites + playlists block first paint. The
      // sidebar's "Recent" section is not visible above the fold on mobile
      // and is harmless if it populates a few hundred ms later — defer it.
      const [favsRes, playlistsRes] = await Promise.all([
        fetch(`${state.API}/favorites`),
        fetch(`${state.API}/playlists`)
      ]);
      const favs = await favsRes.json();
      const pls = await playlistsRes.json();

      state.setUserFavorites(new Set(favs.map((f) => f.id)));
      state.setUserPlaylists(pls);

      offlineStore.saveLibrarySnapshot('favorites', favs);
      offlineStore.saveLibrarySnapshot('playlists', pls);

      sync.setSyncHandlers({
        renderSidebarPlaylists,
        refreshRecentActivity
      });
      sync.startHeartbeatSync();

      // Lazy: kick recent-activity off after the boot promise resolves so it
      // doesn't compete with the home view's own /api/* fan-out.
      setTimeout(() => {
        fetch(`${state.API}/recent-activity`)
          .then((r) => (r.ok ? r.json() : { playlists: [], radios: [] }))
          .then((recents) => {
            state.setRecentPlaylists(Array.isArray(recents.playlists) ? recents.playlists : []);
            state.setRecentRadios(Array.isArray(recents.radios) ? recents.radios : []);
            state.setRecentMixes(Array.isArray(recents.mixes) ? recents.mixes : []);
            offlineStore.saveLibrarySnapshot('recent_activity', recents);
            renderSidebarRecents();
          })
          .catch((e) => console.error('Recent activity (deferred) failed:', e));
      }, 0);
    } else {
      const [favs, pls, recents] = await Promise.all([
        offlineStore.getLibrarySnapshot('favorites'),
        offlineStore.getLibrarySnapshot('playlists'),
        offlineStore.getLibrarySnapshot('recent_activity')
      ]);

      if (favs && Array.isArray(favs)) state.setUserFavorites(new Set(favs.map((f) => f.id)));
      if (pls && Array.isArray(pls)) state.setUserPlaylists(pls);
      if (recents) {
        state.setRecentPlaylists(Array.isArray(recents.playlists) ? recents.playlists : []);
        state.setRecentRadios(Array.isArray(recents.radios) ? recents.radios : []);
        state.setRecentMixes(Array.isArray(recents.mixes) ? recents.mixes : []);
      }
      renderSidebarPlaylists();
      renderSidebarRecents();
    }
  } catch (e) {
    console.error('Failed to load user data:', e);
    const [favs, pls] = await Promise.all([
      offlineStore.getLibrarySnapshot('favorites'),
      offlineStore.getLibrarySnapshot('playlists')
    ]);
    if (favs && Array.isArray(favs)) state.setUserFavorites(new Set(favs.map((f) => f.id)));
    if (pls && Array.isArray(pls)) state.setUserPlaylists(pls);
    renderSidebarPlaylists();
  }
}

export function showSaveMixModal(mixKey, mixTitle) {
  const defaultName = ui.escHtml(mixTitle || 'New Mix').replace(/'/g, '&#39;');
  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal modal-playlist" onclick="event.stopPropagation()">
            <div class="modal-header">
                <h2><i data-lucide="save"></i> Save Mix as Playlist</h2>
                <button class="modal-close" onclick="closeModal()"><i data-lucide="x"></i></button>
            </div>
            <div class="modal-body">
                <label class="settings-label" for="mix-save-name">Playlist Name</label>
                <input id="mix-save-name" class="modal-input" type="text" value="${defaultName}" maxlength="120" placeholder="${ui.escHtml(ui.t('library.smart_playlist_name', 'Playlist name'))}">
                <div class="playlist-actions" style="margin-top:16px;">
                    <button class="btn-primary-lg playlist-action-btn" onclick="saveMixAsPlaylistAction('${mixKey}')">
                        <i data-lucide="save" width="18" height="18"></i>
                        <span class="playlist-btn-label">Save</span>
                    </button>
                </div>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  ui.refreshIcons(document.getElementById('modal-container'));
  document.getElementById('mix-save-name')?.focus();
}

export async function saveMixAsPlaylistAction(mixKey) {
  const input = document.getElementById('mix-save-name');
  const name = (input?.value || '').trim();
  if (!name) {
    ui.showToast('Playlist name is required', 'error');
    return;
  }

  const saved = await api.saveMixAsPlaylist(mixKey, name);
  if (!saved) {
    ui.showToast('Failed to save mix', 'error');
    return;
  }

  ui.closeModal();
  ui.showToast('Mix saved as playlist', 'success');
  await loadUserData();
  loadView('playlist', saved.id);
}

export async function saveWrappedTopSongsPlaylist(year, button = null) {
  if (button?.disabled) return;
  if (button) button.disabled = true;
  try {
    const saved = await api.saveWrappedTopSongsPlaylist(year);
    if (!saved) throw new Error('Could not save Wrapped playlist');
    await loadUserData();
    ui.showToast('Wrapped playlist saved', 'success');
    await loadView('playlist', saved.id);
  } catch (error) {
    ui.showToast(error.message || 'Could not save Wrapped playlist', 'error');
  } finally {
    if (button) button.disabled = false;
  }
}

export function initHeartbeatLifecycle() {
  sync.setSyncHandlers({
    renderSidebarPlaylists,
    refreshRecentActivity
  });
  sync.initHeartbeatLifecycle();
}

export const startHeartbeatSync = sync.startHeartbeatSync;
export const stopHeartbeatSync = sync.stopHeartbeatSync;
export const checkSyncState = sync.checkSyncState;

// ─── DISCOVERY FEED ─────────────────────────────────────────────────
//
// Vertical card stack of recommended remote tracks the user doesn't
// already own. Each card has a 30-second preview (HTML5 audio) and
// three action buttons:
//   ✕ Dismiss — fades the card out and remembers in localStorage so
//               it doesn't reappear in future feeds.
//   ⬇ Download — sends the track through the existing triggerDownload
//               pipeline so it lands in the local library.
//   ❤ Like — same as Download (the track has to be local first to
//             be properly favorited).
//
// On mobile this is a dedicated bottom-nav tab; on desktop reachable
// via loadView('discovery').

const DISCOVERY_DISMISSED_KEY = 'aoede_discovery_dismissed';
const DISCOVERY_DISMISSED_CAP = 200;

function _discoveryKey(item) {
  return `${(item.title || '').trim().toLowerCase()}|${(item.artist || '').trim().toLowerCase()}`;
}

function _loadDiscoveryDismissed() {
  try {
    return new Set(JSON.parse(localStorage.getItem(DISCOVERY_DISMISSED_KEY) || '[]'));
  } catch {
    return new Set();
  }
}

function _saveDiscoveryDismissed(set) {
  try {
    // Cap to prevent unbounded growth — keep the most recent N
    const arr = [...set].slice(-DISCOVERY_DISMISSED_CAP);
    localStorage.setItem(DISCOVERY_DISMISSED_KEY, JSON.stringify(arr));
  } catch {}
}

function _discoveryCard(item) {
  const data = btoa(encodeURIComponent(JSON.stringify(item)));
  const cover = item.thumbnail || '/static/img/default_cover.png';
  const title = ui.escHtml(item.title || 'Unknown');
  const artist = ui.escHtml(item.artist || 'Unknown');
  const album = ui.escHtml(item.album || '');
  const src = item.source || 'spotify';
  const srcMeta = TRACK_SOURCE_ICONS[src] || TRACK_SOURCE_ICONS.local;
  const preview = item.preview || '';
  const key = ui.escHtml(_discoveryKey(item));

  return `
    <article class="discovery-card" data-key="${key}">
        <div class="discovery-cover-wrap">
            <img src="${cover}" alt="${title}" class="discovery-cover"
                 loading="lazy" decoding="async"
                 onerror="this.src='/static/img/default_cover.png'">
            <!-- Play button is always shown — discoveryTogglePreview now
                 routes through /api/playback/preview which resolves any
                 source (Spotify/YouTube/Last.fm/MB) to a YouTube preview
                 stream, so the user can audition every card. -->
            <button class="discovery-play-btn"
                    onclick="discoveryTogglePreview(this, '${data}')"
                    title="${ui.escHtml(ui.t('player.preview', 'Preview'))}" aria-label="${ui.escHtml(ui.t('player.preview', 'Preview'))} ${title}">
                <i data-lucide="play"></i>
            </button>
            <span class="discovery-source-badge" style="color: ${srcMeta.color}">
                <i data-lucide="${srcMeta.icon}"></i>
            </span>
        </div>
        <div class="discovery-meta">
            <h2 class="discovery-title">${title}</h2>
            <p class="discovery-artist">${artist}${album ? ` · ${album}` : ''}</p>
        </div>
        <div class="discovery-actions">
            <button class="discovery-action discovery-dismiss" onclick="discoveryDismiss(this)" title="Pass">
                <i data-lucide="x"></i>
            </button>
            <button class="discovery-action discovery-download" onclick="discoveryDownload(this, '${data}')" title="Download to library">
                <i data-lucide="download"></i>
            </button>
            <button class="discovery-action discovery-like" onclick="discoveryDownload(this, '${data}', true)" title="Save to library">
                <i data-lucide="heart"></i>
            </button>
        </div>
    </article>`;
}

export async function renderDiscovery(container) {
  const data = await api.fetchDiscoveryFeed(20);
  const items = data.items || [];

  if (!items.length) {
    container.innerHTML = `
        <section class="home-overview discovery-theme">
            ${ui.homeTabsBar('discovery')}
            <div class="hero-section">
                <div class="hero-kicker">${ui.t('discover.hero_kicker', 'New Music Audition')}</div>
                <h1 class="hero-greeting">${ui.t('discover.title', 'Discover Feed')}</h1>
                <p class="hero-sub" style="color:var(--text-sub); margin:6px 0 0 0;">${ui.t('discover.subtitle', 'Tracks recommended for you with instant 30-second previews.')}</p>
            </div>
        </section>
        <div class="discovery-shell">
            <div class="empty-state glass-panel">
                <i data-lucide="compass" class="empty-icon"></i>
                <p>${ui.t('discover.empty', 'No discovery items right now. Explore music in Search to build recommendations, then come back.')}</p>
            </div>
        </div>`;
    ui.refreshIcons(container);
    return;
  }

  const dismissed = _loadDiscoveryDismissed();
  const visible = items.filter((it) => !dismissed.has(_discoveryKey(it)));

  container.innerHTML = `
        <section class="home-overview discovery-theme">
            ${ui.homeTabsBar('discovery')}
            <div class="hero-section">
                <div class="hero-kicker">${ui.t('discover.hero_kicker', 'New Music Audition')}</div>
                <h1 class="hero-greeting">${ui.t('discover.title', 'Discover Feed')}</h1>
                <p class="hero-sub" style="color:var(--text-sub); margin:6px 0 0 0;">${ui.t('discover.count_preview', '{count} tracks · tap a cover to preview', { count: visible.length })}</p>
            </div>
        </section>
        <div class="discovery-shell">
            <div class="discovery-feed">
                ${visible.map(_discoveryCard).join('')}
            </div>
        </div>`;
  ui.refreshIcons(container);
}

// ── Discovery handlers (window-exposed via main.js) ─────────────────

export function discoveryTogglePreview(btn, encodedData) {
  if (!player.allowPrivatePlayback()) return;
  // The card now passes the full encoded track payload (same shape as
  // createTrackRow) and we resolve to a playable URL via the existing
  // /api/playback/preview endpoint — same path the eye-icon uses, so
  // every card has a working ▶ regardless of source. Spotify previews
  // were unreliable since their late-2024 API change; YouTube fallback
  // works for everything.
  let track;
  try {
    track = JSON.parse(decodeURIComponent(atob(encodedData)));
  } catch {
    return;
  }
  if (!track || (!track.id && !track.title)) return;

  // Build the preview URL the same way playPreview does. We pass both
  // url and title so the backend can pick the cheapest path (Spotify
  // embed redirect for spotify ids, YouTube search fallback otherwise).
  const params = new URLSearchParams();
  if (track.id) params.set('url', track.id);
  if (track.title) params.set('title', `${track.artist || ''} ${track.title}`.trim());
  if (track.source === 'spotify' && track.id && track.id.includes('track/')) {
    params.set('spotify_id', track.id.split('track/').pop().split('?')[0]);
  }
  const previewUrl = `${state.API}/playback/preview?${params.toString()}`;

  // Single shared <audio> for previews — pause the previous if any.
  let audio = window.__discoveryPreviewAudio;
  if (!audio) {
    audio = new Audio();
    audio.preload = 'metadata';
    audio.crossOrigin = 'anonymous';
    audio.addEventListener('ended', () => _discoveryResetAllPlayIcons());
    window.__discoveryPreviewAudio = audio;
  }
  audioEngine.syncPreviewVolume();

  // Toggle off if this same card is already playing.
  const playingThis = audio.dataset.cardKey === btn.closest('.discovery-card')?.dataset.key && !audio.paused;
  if (playingThis) {
    audio.pause();
    _discoveryResetAllPlayIcons();
    return;
  }

  audio.pause();
  state.audio.pause();
  _discoveryResetAllPlayIcons();
  audio.src = previewUrl;
  audio.dataset.cardKey = btn.closest('.discovery-card')?.dataset.key || '';
  audio.play().catch((e) => {
    console.warn('[DISCOVERY] preview play failed', e);
    ui.showToast('Could not play preview', 'error');
    _discoveryResetAllPlayIcons();
  });

  const icon = btn.querySelector('[data-lucide]');
  if (icon) {
    icon.setAttribute('data-lucide', 'pause');
    ui.refreshIcons(btn);
  }
  btn.classList.add('playing');
}

function _discoveryResetAllPlayIcons() {
  document.querySelectorAll('.discovery-play-btn.playing').forEach((b) => {
    const i = b.querySelector('[data-lucide]');
    if (i) i.setAttribute('data-lucide', 'play');
    b.classList.remove('playing');
    ui.refreshIcons(b);
  });
}

export function discoveryDismiss(btn) {
  const card = btn.closest('.discovery-card');
  if (!card) return;
  const key = card.dataset.key;
  const dismissed = _loadDiscoveryDismissed();
  if (key) {
    dismissed.add(key);
    _saveDiscoveryDismissed(dismissed);
  }
  card.classList.add('dismissing');
  setTimeout(() => card.remove(), 220);
}

export function discoveryDownload(btn, encodedData, removeAfter = false) {
  if (typeof window.triggerDownload === 'function') {
    window.triggerDownload(encodedData);
  }
  btn.classList.add('liked');
  if (removeAfter) {
    const card = btn.closest('.discovery-card');
    if (card) {
      card.classList.add('dismissing');
      setTimeout(() => card.remove(), 220);
    }
  }
}

// === ARTIST VIEW ============================================================
//
// Surfaces every datum we have for an artist:
//   1. Hero — name, hero image (Spotify), listener count + tags (Last.fm)
//   2. Tracks in your library  (local DB)
//   3. Top tracks               (Last.fm — only if user has no local copy)
//   4. Discography              (Spotify albums + singles)
//                               (annotated with owned / missing track counts)
//   5. Fans also like           (Last.fm getSimilar)
//
// Each remote section degrades gracefully: if the relevant API key is
// missing or the call failed, the section is hidden rather than empty.

function _artistAlbumCard(alb) {
  const cover = alb.image || '/static/img/default_cover.png';
  const year = (alb.release_date || '').slice(0, 4);
  const subtitle = [year, alb.type === 'single' ? 'Single' : 'Album'].filter(Boolean).join(' · ');

  // "Complete this album" CTA: only for albums where the user has SOME
  // tracks but is missing others — full ownership shows a check, no
  // ownership shows a generic download link instead of a "complete" CTA.
  let ctaHtml = '';
  if (alb.fully_owned) {
    ctaHtml = `<span class="album-status owned"><i data-lucide="check"></i>In library</span>`;
  } else if (alb.owned_count > 0 && alb.missing_count > 0) {
    const url = ui.escJs(alb.url || '');
    ctaHtml = `
      <button class="album-status partial"
              onclick="event.stopPropagation(); previewCollectionImport('${url}')"
              title="Complete this album">
        <i data-lucide="package-plus"></i>
        Complete (${alb.missing_count} missing)
      </button>`;
  }

  const url = alb.url || '';
  return `
    <article class="artist-album-card">
      <button class="artist-album-cover-link" type="button" ${url ? '' : 'disabled'} onclick="previewCollectionImport('${ui.escJs(url)}')" aria-label="Import ${ui.escHtml(alb.name || 'album')}">
        <img src="${cover}" class="artist-album-cover" loading="lazy"
             onerror="this.src='/static/img/default_cover.png'">
      </button>
      <div class="artist-album-meta">
        <div class="artist-album-title">${ui.escHtml(alb.name || 'Untitled')}</div>
        <div class="artist-album-sub">${subtitle}</div>
        ${ctaHtml}
      </div>
    </article>`;
}

function _artistSimilarCard(sim) {
  const cover = sim.image || '/static/img/default_cover.png';
  const safe = ui.escJs(sim.name || '');
  return `
    <button class="artist-similar-card" onclick="loadView('artist', '${safe}')">
      <img src="${cover}" class="artist-similar-cover" loading="lazy"
           onerror="this.src='/static/img/default_cover.png'">
      <div class="artist-similar-name">${ui.escHtml(sim.name || '')}</div>
    </button>`;
}

export async function renderArtist(container, name) {
  if (!name) {
    container.innerHTML = `<div class="empty-state">No artist specified.</div>`;
    return;
  }

  const data = await api.fetchArtist(name);
  if (!data) {
    container.innerHTML = `
      <div class="empty-state">
        <p>Could not load <strong>${ui.escHtml(name)}</strong>.</p>
      </div>`;
    return;
  }

  const heroImg =
    (data.spotify && data.spotify.image) ||
    (data.local_tracks && data.local_tracks[0] && data.local_tracks[0].thumbnail) ||
    '/static/img/default_cover.png';

  const tags = (data.info && data.info.tags) || [];
  const listenerCount = data.info && data.info.listeners ? Number(data.info.listeners).toLocaleString() : '';
  const followerCount = data.spotify && data.spotify.followers ? Number(data.spotify.followers).toLocaleString() : '';
  const stats = [
    listenerCount ? `${listenerCount} Last.fm listeners` : '',
    followerCount ? `${followerCount} Spotify followers` : '',
    `${data.local_tracks.length} tracks in your library`
  ]
    .filter(Boolean)
    .join(' · ');

  const bio = (data.info && data.info.bio) || '';

  // Local tracks rail — empty state when the user has nothing of this
  // artist locally, with a CTA to play smart radio (since there is
  // nothing to play locally we route through Last.fm seeds instead).
  let localBlock = '';
  if (data.local_tracks.length) {
    state.setCurrentViewList(data.local_tracks);
    localBlock = `
      <section class="artist-section">
        <div class="artist-section-head">
          <h2>In your library</h2>
          <button class="artist-section-play" onclick="playFromView(0)" title="Play all">
            <i data-lucide="play"></i><span>Play</span>
          </button>
        </div>
        <div class="track-list">
          ${data.local_tracks.map((t, i) => createTrackRow(t, i, null)).join('')}
        </div>
      </section>`;
  } else {
    const safe = ui.escJs(name);
    localBlock = `
      <section class="artist-section">
        <div class="artist-empty">
          <p>You don't have any ${ui.escHtml(name)} tracks yet.</p>
          <button class="btn-primary"
                  onclick="startSmartRadio('${safe}', '${ui.escJs(((data.top_tracks || [])[0] || {}).title || '')}')">
            <i data-lucide="radio"></i> Start a radio
          </button>
        </div>
      </section>`;
  }

  // Top tracks — only show entries the user doesn't already have locally,
  // so we don't double up with "In your library" above.
  const localKeys = new Set(
    data.local_tracks.map((t) => `${(t.title || '').toLowerCase()}|${(t.artist || '').toLowerCase()}`)
  );
  const topTracks = (data.top_tracks || [])
    .filter((t) => !localKeys.has(`${(t.title || '').toLowerCase()}|${(t.artist || '').toLowerCase()}`))
    .slice(0, 8);

  let topBlock = '';
  if (topTracks.length) {
    topBlock = `
      <section class="artist-section">
        <div class="artist-section-head">
          <h2>Popular tracks you don't have</h2>
        </div>
        <ol class="artist-top-tracks">
          ${topTracks
            .map((t, i) => {
              const safeT = ui.escJs(t.title || '');
              const safeA = ui.escJs(t.artist || name);
              // Resolve cover via existing /api/cover/resolve endpoint —
              // it caches its own results so we don't add to remote API
              // pressure even if the user opens many artist views.
              const cover = `/api/cover/resolve?artist=${encodeURIComponent(t.artist || name)}&title=${encodeURIComponent(t.title || '')}`;
              return `
              <li>
                <span class="artist-top-num">${i + 1}</span>
                <img class="artist-top-cover" src="${cover}" loading="lazy" decoding="async"
                     onerror="this.src='/static/img/default_cover.png'">
                <span class="artist-top-title">${ui.escHtml(t.title || '')}</span>
                <span class="artist-top-actions">
                  <button onclick="window.startSearchAndDownload && window.startSearchAndDownload('${safeA} ${safeT}')"
                          title="Download to library">
                    <i data-lucide="download"></i>
                  </button>
                  <button onclick="startSmartRadio('${safeA}', '${safeT}')" title="Start radio from this track">
                    <i data-lucide="radio"></i>
                  </button>
                </span>
              </li>`;
            })
            .join('')}
        </ol>
      </section>`;
  }

  const albumsBlock =
    data.albums && data.albums.length
      ? `
      <section class="artist-section">
        <div class="artist-section-head"><h2>Discography</h2></div>
        <div class="artist-albums-grid">
          ${data.albums.map(_artistAlbumCard).join('')}
        </div>
      </section>`
      : '';

  const similarBlock =
    data.similar && data.similar.length
      ? `
      <section class="artist-section">
        <div class="artist-section-head"><h2>Fans also like</h2></div>
        <div class="artist-similar-grid">
          ${data.similar.map(_artistSimilarCard).join('')}
        </div>
      </section>`
      : '';

  const safeArtistAttr = ui.escJs(name);

  container.innerHTML = `
    <div class="artist-shell">
      <header class="artist-hero" style="background-image: linear-gradient(180deg, rgba(0,0,0,0.2), rgba(0,0,0,0.85)), url('${heroImg}')">
        <div class="artist-hero-inner">
          <div class="artist-hero-tags">${tags.map((t) => `<span class="artist-tag">${ui.escHtml(t)}</span>`).join('')}</div>
          <h1 class="artist-hero-name">${ui.escHtml(data.name || name)}</h1>
          <p class="artist-hero-stats">${stats}</p>
          <div class="artist-hero-actions">
            ${
              data.local_tracks.length
                ? `
              <button class="btn-primary" onclick="playFromView(0)">
                <i data-lucide="play"></i> Play
              </button>`
                : ''
            }
            <button class="btn-secondary"
                    onclick="startSmartRadio('${safeArtistAttr}', '${ui.escJs(((data.top_tracks || [])[0] || {}).title || '')}')">
              <i data-lucide="radio"></i> Radio
            </button>
          </div>
        </div>
      </header>

      ${localBlock}
      ${topBlock}
      ${albumsBlock}
      ${similarBlock}

      ${
        bio
          ? `
        <section class="artist-section artist-bio">
          <div class="artist-section-head"><h2>About</h2></div>
          <p>${ui.escHtml(bio)}</p>
        </section>`
          : ''
      }
    </div>`;

  if (window.lucide) lucide.createIcons();
}

// === SMART RADIO ============================================================
// Builds an endless queue from a (artist, title) seed. The backend
// returns a list of (title, artist) candidates from the cached
// Last.fm getSimilar pool; we resolve each through the existing search
// → preview pipeline so playback uses YouTube / Spotify previews
// without us re-implementing source resolution here.

export async function startSmartRadio(artist, title) {
  if (!player.allowPrivatePlayback()) return;
  if (!artist) {
    ui.showToast('Need an artist to start a radio', 'error');
    return;
  }
  ui.showToast('Starting radio…', 'info');

  // Backend returns LOCAL library tracks (real db_id) sorted by
  // Last.fm similarity to the seed. No external resolution at play
  // time — the existing /api/stream pipeline handles them like any
  // other library track.
  const data = await api.fetchSmartRadio(artist, title || '', 30);
  const queue = (data && data.tracks) || [];

  if (!queue.length) {
    ui.showToast('Need more tracks in your library to build a radio', 'error');
    return;
  }

  state.setCurrentViewList(queue);
  state.setContextQueue([...queue]);
  state.setOriginalContextQueue([...queue]);
  state.setContextIndex(0);
  player.playTrack(queue[0]);
  ui.showToast(`Radio: ${queue.length} tracks queued`, 'success');
}

// === OFFLINE DOWNLOADS VIEW =================================================

export async function renderOfflineDownloads(container, activeTab = 'all') {
  let allTracks = [];
  let offlinePlaylists = [];
  let offlineSingles = [];
  let storage = { bytesUsed: 0, trackCount: 0 };

  try {
    [allTracks, offlinePlaylists, offlineSingles, storage] = await Promise.all([
      offlineStore.listOfflineTracks(),
      offlineStore.listOfflinePlaylists(),
      offlineStore.listOfflineSingles(),
      offlineStore.getStorageUsage()
    ]);
  } catch (e) {
    console.error('[OFFLINE] Load error:', e);
  }

  let displayTracks = allTracks;
  if (activeTab === 'singles') displayTracks = offlineSingles;
  state.setCurrentViewList(displayTracks);

  const trackCount = allTracks.length;
  const mbUsed = (storage.bytesUsed / (1024 * 1024)).toFixed(1);
  const subtitle = ui.t('offline.summary', '{tracks} tracks · {collections} collections · {size} MB on device', {
    tracks: trackCount,
    collections: offlinePlaylists.length,
    size: mbUsed
  });

  let quotaBarHtml = '';
  if (storage.quotaBytes) {
    const quotaMB = (storage.quotaBytes / (1024 * 1024)).toFixed(0);
    const pct = storage.usagePercent || 0;
    quotaBarHtml = `
      <div class="offline-storage-bar-bg">
        <div class="offline-storage-bar-fill" style="width: ${Math.min(100, pct)}%"></div>
      </div>
      <div class="offline-storage-meta">${mbUsed} MB used of ~${quotaMB} MB browser allocated storage (${pct}%)</div>`;
  }

  // Playlists section HTML
  let playlistsHtml = '';
  if (offlinePlaylists.length > 0 && (activeTab === 'all' || activeTab === 'playlists')) {
    playlistsHtml = `<h2 class="offline-section-title">${ui.t('offline.collections', 'Playlists & albums')}</h2>
      <div class="offline-playlists-grid">${offlinePlaylists
        .map((pl) => {
          const name = ui.escHtml(pl.name || 'Playlist');
          const count = pl.tracks?.length || pl.track_count || 0;
          const target = ui.escJs(String(pl.id));
          return `<article class="offline-playlist-card">
          <button type="button" class="offline-playlist-open" onclick="loadView('playlist', '${target}')">
            <div class="offline-playlist-card-cover">${pl.thumbnail ? `<img src="${ui.escHtml(pl.thumbnail)}" alt="" loading="lazy" onerror="this.src='/static/img/default_cover.png'">` : '<i data-lucide="list-music"></i>'}</div>
            <div class="offline-playlist-card-info"><div class="offline-playlist-card-name">${name}</div><div class="offline-playlist-card-sub">${pl.kind === 'album' ? ui.t('library.album', 'Album') : ui.t('library.playlist', 'Playlist')} · ${count} ${ui.t('common.songs', 'songs')}</div></div>
          </button>
          <div class="offline-playlist-card-actions">
            <button type="button" class="btn-primary" onclick="loadView('playlist', '${target}').then(() => playPlaylistInOrder())" aria-label="${ui.escHtml(ui.t('player.play', 'Play'))} ${name}" title="${ui.escHtml(ui.t('player.play', 'Play'))}"><i data-lucide="play" width="20"></i></button>
            <button type="button" class="btn-danger-outline" onclick="deleteOfflinePlaylistAction(${Number(pl.id)})" aria-label="${ui.escHtml(ui.t('offline.remove_copy', 'Remove offline copy'))} ${name}" title="${ui.escHtml(ui.t('offline.remove_copy', 'Remove offline copy'))}"><i data-lucide="trash-2" width="18"></i></button>
          </div>
        </article>`;
        })
        .join('')}</div>`;
  } else if (activeTab === 'playlists') {
    playlistsHtml = `<div class="empty-state"><p>${ui.t('offline.no_collections', 'No collections downloaded. Open a playlist or album online and choose Download.')}</p></div>`;
  }

  // Tracks section HTML
  let tracksHtml = '';
  if (activeTab === 'all' || activeTab === 'singles') {
    const listToRender = activeTab === 'singles' ? offlineSingles : allTracks;
    const title =
      activeTab === 'singles'
        ? 'Individual / Standalone Songs'
        : offlinePlaylists.length > 0
          ? 'All Downloaded Tracks'
          : 'Downloaded Tracks';
    if (listToRender.length > 0) {
      tracksHtml = `
        <h2 class="offline-section-title"><i data-lucide="music"></i> ${title}</h2>
        <div class="track-list">${listToRender.map((t, i) => createTrackRow({ ...t, is_local: true, is_offline: true, db_id: t.id }, i)).join('')}</div>
      `;
    } else if (activeTab === 'singles') {
      tracksHtml = `
        <div class="empty-state glass-panel">
          <i data-lucide="music" class="empty-icon"></i>
          <p>No standalone single tracks downloaded yet.<br>Use "Make Available Offline" from any track's options menu.</p>
        </div>`;
    }
  }

  const storageTools = `<details class="offline-storage-tools">
    <summary><i data-lucide="hard-drive" width="18"></i><span>${ui.t('offline.device_storage', 'Device Storage')} · ${mbUsed} MB</span><i data-lucide="chevron-down"></i></summary>
    <div class="offline-storage-card">${quotaBarHtml}
      <p class="offline-disclaimer">${ui.t('help.offline.desc', 'Tracks are stored in your browser/app private database for offline playback.')}</p>
      <div class="offline-storage-actions">
        <button class="btn-secondary" onclick="checkOfflineReadinessAction()"><i data-lucide="activity" width="16"></i>${ui.t('offline.check_readiness', 'Check Readiness')}</button>
        ${trackCount > 0 ? `<button class="btn-danger-outline" onclick="showClearOfflineConfirmModal()"><i data-lucide="trash-2" width="16"></i>${ui.t('offline.clear_all', 'Clear All Downloads')}</button>` : ''}
      </div>
    </div>
  </details>`;
  container.innerHTML = `<section class="offline-shell">
    <header class="offline-header">
      <div><h1>${ui.t('offline.title', 'Offline Downloads')}</h1><p>${subtitle}</p></div>
      ${
        trackCount > 0
          ? `<div class="offline-header-actions">
        <button type="button" class="btn-primary" onclick="playPlaylistInOrder()"><i data-lucide="play" width="20"></i>${ui.t('player.play_all', 'Play All')}</button>
        <button type="button" class="btn-secondary" onclick="playPlaylistShuffle()"><i data-lucide="shuffle" width="20"></i>${ui.t('player.shuffle', 'Shuffle')}</button>
      </div>`
          : ''
      }
    </header>
    <div class="library-filters" style="margin-bottom: 16px;">
      <button class="library-filter ${activeTab === 'all' ? 'active' : ''}" onclick="renderOfflineDownloads(document.getElementById('view-container'), 'all')">
        ${ui.t('offline.all_tracks', 'All Tracks')} (${trackCount})
      </button>
      <button class="library-filter ${activeTab === 'playlists' ? 'active' : ''}" onclick="renderOfflineDownloads(document.getElementById('view-container'), 'playlists')">
        ${ui.t('offline.collections', 'Playlists & albums')} (${offlinePlaylists.length})
      </button>
      <button class="library-filter ${activeTab === 'singles' ? 'active' : ''}" onclick="renderOfflineDownloads(document.getElementById('view-container'), 'singles')">
        ${ui.t('offline.singles', 'Individual Songs')} (${offlineSingles.length})
      </button>
    </div>

    ${
      trackCount > 0 || offlinePlaylists.length > 0
        ? `<div class="offline-search-bar">
            <i data-lucide="search" width="16" height="16" style="color:var(--text-sub);"></i>
            <input id="offline-filter-input" type="search" aria-label="${ui.escHtml(ui.t('offline.search_placeholder', 'Search downloaded tracks or playlists...'))}" placeholder="${ui.escHtml(ui.t('offline.search_placeholder', 'Search downloaded tracks or playlists...'))}" oninput="
              const q = this.value.trim().toLowerCase();
              document.querySelectorAll('#offline-tracks-container .track-row').forEach(row => {
                const title = (row.querySelector('.track-name-sm')?.textContent || '').toLowerCase();
                const artist = (row.querySelector('.track-artist-sm')?.textContent || '').toLowerCase();
                row.style.display = (title.includes(q) || artist.includes(q)) ? '' : 'none';
              });
              document.querySelectorAll('.offline-playlist-card').forEach(card => {
                const name = (card.querySelector('.offline-playlist-card-name')?.textContent || '').toLowerCase();
                card.style.display = name.includes(q) ? '' : 'none';
              });
            ">
          </div>`
        : ''
    }

    <div id="offline-playlists-container">
      ${playlistsHtml}
    </div>
    <div id="offline-tracks-container">
      ${tracksHtml}
    </div>
    ${
      trackCount === 0 && offlinePlaylists.length === 0
        ? `<div class="empty-state glass-panel">
            <p>${ui.t('offline.empty', "No offline downloads yet.<br>Use the 'Make Available Offline' option on any track or 'Download for Offline' on playlists while online.")}</p>
         </div>`
        : ''
    }
    ${storageTools}
    </section>
  `;
  ui.refreshIcons(container);
}

export async function deleteOfflinePlaylistAction(playlistId) {
  try {
    await offlineStore.deleteOfflinePlaylist(playlistId);
    ui.showToast('Removed playlist from offline storage');
    if (state.currentViewName === 'offline' || state.currentViewName === 'downloads_offline') {
      const container = document.getElementById('view-container');
      if (container) await renderOfflineDownloads(container, 'playlists');
    }
  } catch (err) {
    ui.showToast(`Failed to remove playlist: ${err.message}`, 'error');
  }
}

export async function downloadOfflineTrackAction(encodedData) {
  try {
    const item = JSON.parse(decodeURIComponent(atob(encodedData)));
    ui.showToast(`Downloading "${item.title || 'track'}" for offline playback...`, 'info');
    await offlineStore.downloadTrack(item, null, { isSingle: true });
    ui.showToast(`Downloaded "${item.title || 'track'}"!`, 'success');

    // Update any offline badges in visible track rows
    document.querySelectorAll(`.track-row`).forEach((row) => {
      const idx = row.dataset.idx;
      if (idx !== undefined && state.currentViewList?.[idx]?.db_id === item.db_id) {
        const titleEl = row.querySelector('.track-name-sm');
        if (titleEl && !titleEl.querySelector('.track-offline-badge')) {
          titleEl.insertAdjacentHTML(
            'beforeend',
            '<span class="track-offline-badge" title="Available offline"><i data-lucide="check-circle" width="13" height="13"></i></span>'
          );
        }
      }
    });
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    console.error('[OFFLINE] Download error:', err);
    ui.showToast(`Failed to download: ${err.message}`, 'error');
  }
}

export async function deleteOfflineTrackAction(trackId) {
  try {
    await offlineStore.deleteOfflineTrack(trackId);
    ui.showToast('Removed from offline storage');
    if (state.currentViewName === 'offline' || state.currentViewName === 'downloads_offline') {
      const container = document.getElementById('view-container');
      if (container) await renderOfflineDownloads(container);
    }
  } catch (err) {
    ui.showToast(`Failed to remove: ${err.message}`, 'error');
  }
}

export function showClearOfflineConfirmModal() {
  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal" onclick="event.stopPropagation()">
            <h2 style="margin-bottom: 16px;"><i data-lucide="trash-2"></i> ${ui.t('offline.clear_all', 'Clear All Downloads')}</h2>
            <p style="color: var(--text-sub); margin-bottom: 24px;">
                ${ui.t('library.delete_folder_confirm', 'Are you sure you want to remove all offline downloaded tracks from this device? This will free up storage immediately.')}
            </p>
            <div class="modal-actions">
                <button class="modal-btn-cancel" onclick="closeModal()">${ui.t('common.cancel', 'Cancel')}</button>
                <button class="modal-btn-danger" onclick="clearAllOfflineData()">${ui.t('offline.clear_all', 'Clear All')}</button>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  if (window.lucide) lucide.createIcons();
}

export async function clearAllOfflineData() {
  ui.closeModal();
  try {
    await offlineStore.clearOfflineData();
    ui.showToast('All offline downloads cleared', 'success');
    if (state.currentViewName === 'offline' || state.currentViewName === 'downloads_offline') {
      const container = document.getElementById('view-container');
      if (container) await renderOfflineDownloads(container);
    }
  } catch (err) {
    ui.showToast(`Failed to clear offline storage: ${err.message}`, 'error');
  }
}

export async function checkOfflineReadinessAction() {
  const info = await offlineStore.checkOfflineReadiness();
  const mb = (info.storageInfo.bytesUsed / (1024 * 1024)).toFixed(1);
  const quota = info.storageInfo.quotaBytes
    ? `${(info.storageInfo.quotaBytes / (1024 * 1024)).toFixed(0)} MB`
    : 'Browser managed';
  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal modal-md" onclick="event.stopPropagation()">
            <div class="modal-header">
                <h2><i data-lucide="shield-check"></i> ${ui.t('offline.check_readiness', 'Offline Readiness')}</h2>
                <button class="modal-close" onclick="closeModal()"><i data-lucide="x"></i></button>
            </div>
            <div class="modal-body" style="display:flex; flex-direction:column; gap:12px; font-size:0.9rem;">
                <div style="display:flex; justify-content:space-between; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:8px;">
                    <span>IndexedDB Storage</span>
                    <strong style="color:${info.hasIndexedDB ? '#4ade80' : '#f87171'}">${info.hasIndexedDB ? 'Available' : 'Unsupported'}</strong>
                </div>
                <div style="display:flex; justify-content:space-between; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:8px;">
                    <span>Service Worker (App Shell)</span>
                    <strong style="color:${info.hasServiceWorker ? '#4ade80' : '#f87171'}">${info.hasServiceWorker ? 'Active' : 'Unsupported'}</strong>
                </div>
                <div style="display:flex; justify-content:space-between; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:8px;">
                    <span>Blob Audio Playback</span>
                    <strong style="color:${info.hasBlobPlayback ? '#4ade80' : '#f87171'}">${info.hasBlobPlayback ? 'Supported' : 'Unsupported'}</strong>
                </div>
                <div style="display:flex; justify-content:space-between; border-bottom:1px solid rgba(255,255,255,0.08); padding-bottom:8px;">
                    <span>${ui.t('offline.all_tracks', 'Downloaded Tracks')}</span>
                    <strong>${info.storageInfo.trackCount} ${ui.t('common.songs', 'tracks')} (${mb} MB)</strong>
                </div>
                <div style="display:flex; justify-content:space-between; padding-bottom:8px;">
                    <span>${ui.t('offline.device_storage', 'Browser Storage Quota')}</span>
                    <strong>${quota}</strong>
                </div>
                <p style="font-size:0.78rem; color:var(--text-sub); margin-top:4px;">
                    ${ui.t('help.offline.desc', 'Browser storage quota is allocated dynamically by your browser/device per origin.')}
                </p>
                <div class="modal-actions" style="margin-top:16px;">
                    <button class="btn-primary" onclick="closeModal()">${ui.t('common.close', 'OK')}</button>
                </div>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  if (window.lucide) lucide.createIcons();
}

// === PUBLIC USER PROFILE VIEW ===============================================

export async function renderProfile(container, username) {
  if (!username) {
    username = window.USER_DATA?.username || 'User';
  }

  const profile = await api.fetchUserProfile(username);
  if (!profile || profile.error) {
    container.innerHTML = `
      <div class="empty-state glass-panel">
        <i data-lucide="user-x" class="empty-icon"></i>
        <p>User profile not found for "<strong>${ui.escHtml(username)}</strong>".</p>
      </div>`;
    if (window.lucide) lucide.createIcons();
    return;
  }

  username = profile.username || username;
  const stats = profile.stats || {};
  const playlists = profile.public_playlists || [];
  const favorites = profile.favorites || [];
  const topArtists = stats.top_artists || [];
  const avatarUrl = profile.avatar_url
    ? `${profile.avatar_url}?t=${Date.now()}`
    : `/user/avatar/${encodeURIComponent(username)}`;

  let playlistsHtml = '';
  if (playlists.length > 0) {
    playlistsHtml = `
      <section class="shelf-section home-shelf">
        <div class="shelf-header">
          <h2 class="shelf-title">Public Playlists & Albums (${playlists.length})</h2>
        </div>
        <div class="library-cards-grid">
          ${playlists.map(window.createPlaylistCard).join('')}
        </div>
      </section>`;
  }

  let favoritesHtml = '';
  if (favorites.length > 0) {
    state.setCurrentViewList(favorites);
    favoritesHtml = `
      <section class="shelf-section home-shelf">
        <div class="shelf-header">
          <h2 class="shelf-title">Favorite Songs (${favorites.length})</h2>
          <button class="btn-secondary" onclick="playPlaylistInOrder()" title="Play favorites">
            <i data-lucide="play" width="16" height="16"></i> Play All
          </button>
        </div>
        <div class="track-list">
          ${favorites.map((t, i) => createTrackRow(t, i)).join('')}
        </div>
      </section>`;
  }

  let topArtistsHtml = '';
  if (topArtists.length > 0) {
    topArtistsHtml = `
      <section class="shelf-section home-shelf">
        <div class="shelf-header">
          <h2 class="shelf-title">Top Artists</h2>
        </div>
        <div class="artist-chips-row" style="display:flex; gap:10px; flex-wrap:wrap;">
          ${topArtists
            .map(
              (a) => `
            <button class="btn-secondary" onclick="loadView('artist', '${ui.escJs(a.artist)}')" style="display:inline-flex; align-items:center; gap:8px;">
              <i data-lucide="user-round" width="14" height="14"></i>
              <span>${ui.escHtml(a.artist)}</span>
              <small style="opacity:0.65; margin-left:4px;">${Number(a.count) || 0} plays</small>
            </button>
          `
            )
            .join('')}
        </div>
      </section>`;
  }

  container.innerHTML = `
    <section class="home-overview library-theme user-profile-shell" style="padding-bottom: 24px;">
      <button class="library-back" onclick="history.back()" style="margin-bottom:14px; display:inline-flex; align-items:center; gap:6px; background:none; border:none; color:var(--text-sub); cursor:pointer; font-size:0.88rem;">
        <i data-lucide="arrow-left" width="16" height="16"></i> ${ui.t('common.back', 'Back')}
      </button>
      <div class="profile-identity">
        <div class="profile-avatar-large" style="width:116px; height:116px; border-radius:50%; overflow:hidden; border:2px solid rgba(255,255,255,0.18); flex-shrink:0; box-shadow:0 8px 24px rgba(0,0,0,0.4);">
          <img src="${ui.escHtml(avatarUrl)}" alt="${ui.escHtml(username)}" style="width:100%; height:100%; object-fit:cover;" onerror="this.src='/static/img/default_cover.png'">
        </div>
        <div class="profile-details">
          <div style="display:flex; align-items:center; gap:10px; margin-bottom:4px;">
            <span class="hero-kicker" style="margin:0;">${ui.t('profile.profile', 'Profile')}</span>
            <span class="status-badge finished" style="font-size:0.75rem; text-transform:uppercase;">${profile.is_admin ? ui.t('common.admin', 'Admin') : ui.t('common.member', 'Member')}</span>
          </div>
          <h1 class="hero-greeting" style="font-size: clamp(2rem, 4vw, 3rem); margin: 4px 0 10px 0;">${ui.escHtml(username)}${profile.is_federated ? ` <small class="source-badge federation">${ui.escHtml(profile.fed_instance_name)}</small>` : ''}</h1>
          
          <div style="display:flex; gap:10px; flex-wrap:wrap; margin-top:8px;">
            <div class="user-stat-chip"><i data-lucide="list-music" width="14" height="14"></i> <strong>${playlists.length}</strong> ${ui.t('library.playlists', 'Playlists')}</div>
            <div class="user-stat-chip"><i data-lucide="heart" width="14" height="14"></i> <strong>${favorites.length}</strong> ${ui.t('menu.liked', 'Favorites')}</div>
            <div class="user-stat-chip"><i data-lucide="headphones" width="14" height="14"></i> <strong>${Number(stats.total_listens) || 0}</strong> ${ui.t('common.plays', 'Listens')}</div>
            <div class="user-stat-chip"><i data-lucide="clock" width="14" height="14"></i> <strong>${Number(stats.listening_minutes) || 0}</strong> Mins</div>
          </div>

          ${
            profile.is_self
              ? `<div style="margin-top:16px;"><button class="btn-secondary" onclick="loadView('settings_user')"><i data-lucide="settings" width="16" height="16"></i> ${ui.t('profile.edit_settings', 'Edit Profile & Settings')}</button></div>`
              : ''
          }
        </div>
      </div>
    </section>

    ${playlistsHtml}
    ${favoritesHtml}
    ${topArtistsHtml}

    ${
      !playlists.length && !favorites.length && !topArtists.length
        ? `<div class="empty-state glass-panel" style="margin-top:24px;">
            <i data-lucide="music" class="empty-icon"></i>
            <p>${ui.t('profile.empty', "This user hasn't shared any public playlists or favorites yet.")}</p>
           </div>`
        : ''
    }
  `;
  ui.refreshIcons(container);
}

// === SERVER COMMUNITY / USERS VIEW ==========================================

export async function renderCommunity(container) {
  let users = [];
  try {
    users = await api.fetchUsersList();
  } catch (e) {
    console.error('[COMMUNITY] Error fetching users:', e);
  }

  function renderUserCard(u) {
    return `
      <div class="user-profile-card" onclick="loadView('profile', '${ui.escJs(u.profile_key || u.username)}')">
        <div class="user-profile-card-avatar">
          <img src="${ui.escHtml(u.avatar_url)}?t=${Date.now()}" alt="${ui.escHtml(u.username)}" onerror="this.src='/static/img/default_cover.png'">
        </div>
        <div class="user-profile-card-name">
          <span>${ui.escHtml(u.username)}${u.is_federated ? ` @ ${ui.escHtml(u.fed_instance_name)}` : ''}</span>
          ${u.is_admin ? `<span class="status-badge finished" style="font-size:0.65rem; padding:2px 6px;">${ui.t('common.admin', 'Admin')}</span>` : ''}
        </div>
        <div class="user-profile-card-meta">
          ${u.public_playlists_count} ${ui.t('library.playlists', 'playlists')} · ${u.favorites_count} ${ui.t('menu.liked', 'favorites')} · ${u.total_listens} ${ui.t('common.plays', 'listens')}
        </div>
      </div>`;
  }

  container.innerHTML = `
    <section class="home-overview public-theme">
      ${ui.homeTabsBar('community')}
      <div class="hero-section">
        <div class="hero-kicker">${ui.t('community.hero_kicker', 'Server Network & Members')}</div>
        <h1 class="hero-greeting">${ui.t('community.title', 'Community Profiles')}</h1>
        <p class="hero-sub" style="color:var(--text-sub); margin:6px 0 0 0;">${ui.t('community.subtitle', 'Explore listener profiles on this Aoede server. Discover their shared playlists, favorites, and music taste.')}</p>
      </div>
    </section>

    <section class="shelf-section home-shelf">
      <div class="shelf-header community-header" style="margin-bottom:16px;">
        <h2 class="shelf-title">${ui.t('community.server_members', 'Server Members')} (${users.length})</h2>
        <div class="community-search">
          <input id="community-search-input" class="modal-input" placeholder="${ui.escHtml(ui.t('community.filter_placeholder', 'Filter by username...'))}" style="padding:7px 14px; font-size:0.85rem;" oninput="
            const query = this.value.trim().toLowerCase();
            const cards = document.querySelectorAll('.user-cards-grid .user-profile-card');
            cards.forEach(card => {
              const name = card.querySelector('.user-profile-card-name').textContent.toLowerCase();
              card.style.display = name.includes(query) ? '' : 'none';
            });
          ">
        </div>
      </div>
      <div class="user-cards-grid">
        ${users.map(renderUserCard).join('')}
      </div>
    </section>
  `;
  ui.refreshIcons(container);
}
