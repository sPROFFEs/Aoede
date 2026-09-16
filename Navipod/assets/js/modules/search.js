/**
 * search.js - Search Logic
 * Query handling, source selection, and result rendering
 */

import * as state from './state.js';
import * as ui from './ui.js';
import * as api from './api.js';

// === SEARCH INPUT HANDLER ===

export function handleSearch(val) {
  if (state.searchDebounce) clearTimeout(state.searchDebounce);
  state.setSearchDebounce(
    setTimeout(() => {
      // Previously `query.length > 1` silently skipped single-character
      // queries (U-08). Allow any length — empty shows a help state, any
      // non-empty value runs the unified search.
      executeSearch(val.trim());
    }, 400)
  );
}

// === SOURCE SELECTION ===

export function setSource(el, src) {
  // The chip click is the user's explicit signal — cancel any pending
  // debounced search from typing so we don't fire two redundant requests.
  if (state.searchDebounce) {
    clearTimeout(state.searchDebounce);
    state.setSearchDebounce(null);
  }
  state.setCurrentSource(src);
  document.querySelectorAll('.chip').forEach((c) => c.classList.remove('active'));
  el.classList.add('active');
  // The search query can come from any of (in priority order):
  //   - the desktop topbar pill        (#topbar-search-input)
  //   - the mobile in-view search pill (#m-search-input)
  //   - the legacy in-view input       (#search-input, no longer rendered)
  // We pick the first non-empty value so a chip click after typing on
  // mobile re-runs the search with the right query.
  const val =
    document.getElementById('topbar-search-input')?.value ||
    document.getElementById('m-search-input')?.value ||
    document.getElementById('search-input')?.value ||
    '';
  executeSearch(val.trim());
}

// === EXECUTE SEARCH ===

// Reusable music-bar loader strip. Sits above the results body in
// every render path; CSS shows/hides it based on .search-results-fetching
// on the parent, so the body never gets wiped on each keystroke.
const LOADER_STRIP_HTML = `
  <div class="music-loader search-loader-strip" aria-hidden="true">
    <div class="music-bar" style="animation-delay: 0.0s"></div>
    <div class="music-bar" style="animation-delay: 0.1s"></div>
    <div class="music-bar" style="animation-delay: 0.2s"></div>
    <div class="music-bar" style="animation-delay: 0.3s"></div>
    <div class="music-bar" style="animation-delay: 0.4s"></div>
  </div>`;

function renderResults(container, bodyHtml) {
  container.innerHTML = `${LOADER_STRIP_HTML}<div class="search-results-body">${bodyHtml}</div>`;
}

function userProfileCard(u) {
  return `
    <div class="user-profile-card" onclick="loadView('profile', '${ui.escHtml(u.username).replace(/'/g, "\\'")}')">
      <div class="user-profile-card-avatar">
        <img src="${u.avatar_url}?t=${Date.now()}" alt="${ui.escHtml(u.username)}" onerror="this.src='/static/img/default_cover.png'">
      </div>
      <div class="user-profile-card-name">
        <span>${ui.escHtml(u.username)}</span>
        ${u.is_admin ? '<span class="status-badge finished" style="font-size:0.65rem; padding:2px 6px;">Admin</span>' : ''}
      </div>
      <div class="user-profile-card-meta">
        ${u.public_playlists_count} playlists · ${u.favorites_count} favorites · ${u.total_listens} listens
      </div>
    </div>`;
}

export async function executeSearch(query) {
  const results = document.getElementById('search-results');
  if (!results) return;

  // Always abort the previous in-flight search before starting a new one.
  if (state.searchAbortController) {
    try {
      state.searchAbortController.abort();
    } catch (_) {
      /* noop */
    }
  }
  const controller = new AbortController();
  state.setSearchAbortController(controller);

  // If source is 'users', list all server users if empty query or filter by name
  if (state.currentSource === 'users') {
    results.classList.add('search-results-fetching');
    try {
      const allUsers = await api.fetchUsersList();
      if (controller.signal.aborted || state.searchAbortController !== controller) return;

      const filtered = query
        ? allUsers.filter((u) => u.username.toLowerCase().includes(query.toLowerCase()))
        : allUsers;

      if (!filtered.length) {
        renderResults(
          results,
          `<div class="empty-state glass-panel"><i data-lucide="user-x" class="empty-icon"></i><p>No server users found matching "${ui.escHtml(query)}".</p></div>`
        );
      } else {
        renderResults(
          results,
          `
          <div class="shelf-section" style="margin-top: 12px;">
            <div class="shelf-header">
              <h2 class="shelf-title">Server Community Profiles (${filtered.length})</h2>
            </div>
            <div class="user-cards-grid">
              ${filtered.map(userProfileCard).join('')}
            </div>
          </div>`
        );
      }
      if (window.lucide?.createIcons) lucide.createIcons();
    } catch (e) {
      if (e.name !== 'AbortError') {
        renderResults(results, `<div class="empty-state" style="color:#e74c3c;">Failed to load users.</div>`);
      }
    } finally {
      if (state.searchAbortController === controller) {
        state.setSearchAbortController(null);
        results.classList.remove('search-results-fetching');
      }
    }
    return;
  }

  // Empty query for audio search: render help state
  if (!query) {
    results.classList.remove('search-results-fetching');
    renderResults(
      results,
      '<div class="empty-state"><p>Type to search your library, federated peers, server users, and remote sources.</p></div>'
    );
    state.setCurrentViewList([]);
    return;
  }

  // Flag the panel as fetching
  results.classList.add('search-results-fetching');
  if (!results.querySelector('.search-results-body')) {
    renderResults(results, '');
  }

  try {
    const url = `${state.API}/search?q=${encodeURIComponent(query)}&source=${encodeURIComponent(state.currentSource)}`;

    // In 'all' mode, also search server users in parallel
    const [searchRes, usersList] = await Promise.all([
      fetch(url, { signal: controller.signal }),
      state.currentSource === 'all' ? api.fetchUsersList().catch(() => []) : Promise.resolve([])
    ]);

    if (!searchRes.ok) {
      const msg =
        searchRes.status === 401
          ? 'Your session expired. Reload the page to log in.'
          : searchRes.status === 429
            ? 'Too many searches in a row — slow down (limit: 30/min).'
            : `Search failed (HTTP ${searchRes.status}).`;
      renderResults(results, `<div class="empty-state" style="color:#e74c3c;"><p>${ui.escHtml(msg)}</p></div>`);
      return;
    }

    const data = await searchRes.json();
    if (controller.signal.aborted || state.searchAbortController !== controller) return;

    const matchingUsers = (usersList || []).filter((u) => u.username.toLowerCase().includes(query.toLowerCase()));

    let usersSectionHtml = '';
    if (matchingUsers.length > 0) {
      usersSectionHtml = `
        <div class="shelf-section" style="margin-bottom: 24px;">
          <div class="shelf-header">
            <h2 class="shelf-title">Matching Profiles</h2>
          </div>
          <div class="user-cards-grid" style="margin-top: 10px;">
            ${matchingUsers.slice(0, 4).map(userProfileCard).join('')}
          </div>
        </div>`;
    }

    if (!Array.isArray(data) || data.length === 0) {
      if (matchingUsers.length > 0) {
        renderResults(
          results,
          `${usersSectionHtml}<div class="empty-state"><p>No matching audio tracks found.</p></div>`
        );
      } else {
        renderResults(
          results,
          '<div class="empty-state"><p>No results found in your library or remote sources.</p></div>'
        );
      }
      state.setCurrentViewList([]);
      if (window.lucide?.createIcons) lucide.createIcons();
      return;
    }

    state.setCurrentViewList(data);
    renderResults(
      results,
      `${usersSectionHtml}<div class="track-list"><div class="track-row header"><div class="track-num">#</div><div>Title</div><div>Source</div><div></div><div></div></div>${data.map((item, i) => (window.createTrackRow ? window.createTrackRow(item, i) : '')).join('')}</div>`
    );
    if (window.lucide?.createIcons) lucide.createIcons();
  } catch (e) {
    if (e.name === 'AbortError') return;
    console.error('[SEARCH] Error:', e);
    renderResults(
      results,
      `<div class="empty-state" style="color:#e74c3c;">Error: ${ui.escHtml(e.message || 'Unknown error')}</div>`
    );
  } finally {
    if (state.searchAbortController === controller) {
      state.setSearchAbortController(null);
      results.classList.remove('search-results-fetching');
    }
  }
}

// === DOWNLOAD FROM URL ===

export async function downloadUrl() {
  const input = document.getElementById('url-input');
  const url = input?.value?.trim();

  if (!url) {
    ui.showToast('Please enter a URL', 'error');
    return;
  }
  // Accept all sources the backend actually supports (U-09)
  const SUPPORTED_DOMAINS = ['youtube.com', 'youtu.be', 'spotify.com', 'soundcloud.com', 'audius.co', 'jamendo.com'];
  const isSupported = SUPPORTED_DOMAINS.some((d) => url.includes(d));
  if (!isSupported) {
    ui.showToast('Invalid URL. Supported: YouTube, Spotify, SoundCloud, Audius, Jamendo.', 'error');
    return;
  }

  ui.showToast('Queuing download...');
  try {
    const res = await fetch(`${state.API}/download`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url, title: '', artist: '', album: '', source: '' })
    });
    if (res.ok) {
      const payload = await res.json();
      ui.showToast(payload.message || 'Download queued', 'success');
      if (input) input.value = '';
    } else {
      const err = await res.json();
      ui.showToast(err.error || 'Download failed', 'error');
    }
  } catch (e) {
    ui.showToast('Network error', 'error');
  }
}
