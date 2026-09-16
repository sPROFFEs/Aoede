/** Browsable library facets and smart-playlist controls. */

import * as api from './api.js';
import * as state from './state.js';
import * as ui from './ui.js';
import * as offlineStore from './offline_store.js';

let activeKind = 'playlists';
let smartEditingId = null;
let facetQuery = '';
let facetSort = 'name';
let facetLimit = 50;

function playlistCard(pl) {
  const tracks = Number(pl.track_count || 0);
  const type = pl.is_smart ? 'Smart playlist' : pl.source_playlist_id ? 'Synced playlist' : 'Playlist';
  const icon = pl.is_smart ? 'sparkles' : pl.source_playlist_id ? 'refresh-cw' : 'list-music';
  const thumb = pl.thumbnail || '/static/img/default_cover.png';
  const hasThumb = pl.thumbnail && !pl.thumbnail.includes('default');
  return `
    <div class="library-card" onclick="loadView('playlist', ${pl.id})">
      <div class="library-card-cover">
        ${hasThumb ? `<img src="${ui.escHtml(thumb)}" loading="lazy" onerror="this.src='/static/img/default_cover.png'">` : `<i data-lucide="${icon}"></i>`}
      </div>
      <div class="library-card-name" title="${ui.escHtml(pl.name || 'Playlist')}">${ui.escHtml(pl.name || 'Playlist')}</div>
      <div class="library-card-sub">${type} · ${tracks} ${tracks === 1 ? 'song' : 'songs'}</div>
    </div>`;
}

function facetCard(kind, facet) {
  const singular = kind === 'artists' ? 'artist' : kind === 'albums' ? 'album' : 'genre';
  const icon = kind === 'artists' ? 'user-round' : kind === 'albums' ? 'disc-3' : 'tags';
  const encoded = encodeURIComponent(facet.name).replace(/'/g, '%27');
  const encodedArtist = encodeURIComponent(facet.artist || '').replace(/'/g, '%27');
  return `
    <button class="library-card" onclick="openLibraryFacet('${singular}', decodeURIComponent('${encoded}'), decodeURIComponent('${encodedArtist}'))">
      <div class="library-card-cover ${kind === 'artists' ? 'artist-cover' : ''}">
        ${facet.thumbnail ? `<img src="${ui.escHtml(facet.thumbnail)}" loading="lazy" onerror="this.replaceWith(document.createTextNode(''))">` : `<i data-lucide="${icon}"></i>`}
      </div>
      <div class="library-card-name" title="${ui.escHtml(facet.name)}">${ui.escHtml(facet.name)}</div>
      <div class="library-card-sub">${facet.artist ? `${ui.escHtml(facet.artist)} · ` : ''}${facet.track_count} ${facet.track_count === 1 ? 'song' : 'songs'}</div>
    </button>`;
}

function shell(content, browsing = false, total = 0, hasMore = false) {
  return `
    <section class="library-shell">
      <section class="home-overview library-theme">
        <div class="library-head" style="display:flex; justify-content:space-between; align-items:flex-end; flex-wrap:wrap; gap:16px;">
          <div>
            <div class="hero-kicker">Your personal music collection</div>
            <h1 class="hero-greeting">Your Library</h1>
            <p class="hero-sub" style="color:var(--text-sub); margin:6px 0 0 0;">Browse your playlists, artists, albums, genres, and device downloads.</p>
          </div>
          <div class="library-actions-row" style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
            <button class="btn-secondary" onclick="showCreateSmartPlaylistModal()" title="New smart playlist">
              <i data-lucide="sparkles" width="16" height="16"></i> Smart Playlist
            </button>
            <button class="btn-primary" onclick="showCreatePlaylistModal()" title="New playlist">
              <i data-lucide="plus" width="16" height="16"></i> New Playlist
            </button>
          </div>
        </div>
      </section>

      <div class="library-filters" style="margin: 16px 0 16px 0;">
        <button class="library-filter ${activeKind === 'playlists' ? 'active' : ''}" onclick="switchLibraryKind('playlists')">Playlists</button>
        <button class="library-filter ${activeKind === 'artists' ? 'active' : ''}" onclick="switchLibraryKind('artists')">Artists</button>
        <button class="library-filter ${activeKind === 'albums' ? 'active' : ''}" onclick="switchLibraryKind('albums')">Albums</button>
        <button class="library-filter ${activeKind === 'genres' ? 'active' : ''}" onclick="switchLibraryKind('genres')">Genres</button>
        <button class="library-filter" onclick="loadView('offline')" title="Offline Downloads"><i data-lucide="cloud-off" width="14" height="14" style="margin-right:4px;"></i> Offline</button>
      </div>

      ${browsing ? `<div class="library-sort"><input id="library-facet-query" class="modal-input" value="${ui.escHtml(facetQuery)}" placeholder="Search ${activeKind}" onkeyup="if(event.key==='Enter') reloadLibraryFacets()"><select id="library-facet-sort" class="modal-input" onchange="reloadLibraryFacets()"><option value="name"${facetSort === 'name' ? ' selected' : ''}>Name</option><option value="count"${facetSort === 'count' ? ' selected' : ''}>Most songs</option></select><button class="library-icon-btn" onclick="reloadLibraryFacets()" title="Search"><i data-lucide="search"></i></button></div>` : ''}
      ${content}
      ${browsing ? `<p class="library-facet-count" style="margin-top:20px;">Showing ${Math.min(facetLimit, total)} of ${total}</p>${hasMore ? '<button class="btn-secondary" style="margin-top:10px;" onclick="loadMoreLibraryFacets()">Load more</button>' : ''}` : ''}
    </section>`;
}

export async function renderLibrary(container, kind = null) {
  const validKinds = ['playlists', 'artists', 'albums', 'genres'];
  if (kind === 'offline') {
    window.loadView('offline');
    return;
  }
  const selectedKind = validKinds.includes(kind) ? kind : validKinds.includes(activeKind) ? activeKind : 'playlists';
  if (selectedKind !== activeKind) {
    facetQuery = '';
    facetSort = 'name';
    facetLimit = 50;
  }
  activeKind = selectedKind;
  try {
    if (activeKind === 'playlists') {
      let playlists = [];
      if (navigator.onLine) {
        playlists = await api.fetchPlaylists();
        state.setUserPlaylists(playlists);
        offlineStore.saveLibrarySnapshot('playlists', playlists);
      } else {
        playlists = (await offlineStore.getLibrarySnapshot('playlists')) || [];
      }
      container.innerHTML = shell(
        playlists.length
          ? `<div class="library-cards-grid">${playlists.map(playlistCard).join('')}</div>`
          : '<div class="empty-state"><p>No playlists yet. Use + or create a smart playlist.</p></div>'
      );
    } else {
      let page = { items: [], total: 0, has_more: false };
      if (navigator.onLine) {
        page = await api.fetchLibraryFacets(activeKind, { q: facetQuery, sort: facetSort, limit: facetLimit });
        offlineStore.saveLibrarySnapshot(`facets_${activeKind}`, page);
      } else {
        page = (await offlineStore.getLibrarySnapshot(`facets_${activeKind}`)) || {
          items: [],
          total: 0,
          has_more: false
        };
      }
      const facets = page.items || [];
      container.innerHTML = shell(
        facets.length
          ? `<div class="library-cards-grid">${facets.map((facet) => facetCard(activeKind, facet)).join('')}</div>`
          : `<div class="empty-state"><p>No ${activeKind} with metadata found.</p></div>`,
        true,
        page.total || 0,
        Boolean(page.has_more)
      );
    }
    ui.refreshIcons(container);
  } catch (error) {
    const cached = await offlineStore.getLibrarySnapshot('playlists');
    if (cached && cached.length) {
      container.innerHTML = shell(`<div class="library-cards-grid">${cached.map(playlistCard).join('')}</div>`);
      ui.refreshIcons(container);
    } else {
      container.innerHTML = '<div class="empty-state glass-panel"><p>Failed to load your library.</p></div>';
    }
    console.error('[LIBRARY]', error);
  }
}

export async function switchLibraryKind(kind) {
  const container = document.getElementById('view-container');
  if (container) await renderLibrary(container, kind);
}

export async function reloadLibraryFacets() {
  facetQuery = document.getElementById('library-facet-query')?.value.trim() || '';
  facetSort = document.getElementById('library-facet-sort')?.value || 'name';
  facetLimit = 50;
  return switchLibraryKind(activeKind);
}

export async function loadMoreLibraryFacets() {
  facetLimit = Math.min(facetLimit + 50, 500);
  return switchLibraryKind(activeKind);
}

export async function openLibraryFacet(key, value, albumArtist = '') {
  const container = document.getElementById('view-container');
  if (!container) return;
  try {
    const filters = { [key]: value, limit: 500, sort: key === 'album' ? 'album' : 'artist' };
    if (key === 'album' && albumArtist) filters.artist = albumArtist;
    const page = await api.fetchLibraryTracks(filters);
    const tracks = page.items || [];
    state.setCurrentViewList(tracks);
    container.innerHTML = `
      <section class="library-shell">
        <button class="library-back" onclick="switchLibraryKind('${key === 'artist' ? 'artists' : key === 'album' ? 'albums' : 'genres'}')"><i data-lucide="arrow-left"></i> Library</button>
        <h1 class="library-title">${ui.escHtml(value)}</h1>
        ${albumArtist ? `<p class="library-facet-count">${ui.escHtml(albumArtist)}</p>` : ''}
        <p class="library-facet-count">${page.total || tracks.length} songs</p>
        <div class="track-list">${tracks
          .map(
            (track, index) => `
          <button class="library-track-row" onclick="playFromView(${index})">
            <img src="${track.thumbnail}" loading="lazy" onerror="this.src='/static/img/default_cover.png'">
            <span><strong>${ui.escHtml(track.title)}</strong><small>${ui.escHtml(track.artist)} · ${ui.escHtml(track.album || '')}${track.year ? ` · ${track.year}` : ''}</small></span>
            <i data-lucide="play"></i>
          </button>`
          )
          .join('')}</div>
      </section>`;
    ui.refreshIcons(container);
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}

export function showCreateSmartPlaylistModal() {
  smartEditingId = null;
  showSmartPlaylistModal();
}

function showSmartPlaylistModal(data = null) {
  const editing = Boolean(data);
  document.getElementById('modal-container').innerHTML = `
    <div class="modal-overlay" onclick="if(event.target===this) closeModal()">
      <div class="modal smart-playlist-modal">
        <h2>${editing ? 'Edit' : 'New'} smart playlist</h2>
        <p class="modal-subtitle">Preview the rules before saving. Matching songs also refresh when favorites change.</p>
        <input id="smart-name" class="modal-input" maxlength="100" placeholder="Playlist name">
        <div class="smart-rule-grid">
          <input id="smart-artist" class="modal-input" placeholder="Artist (optional)">
          <input id="smart-album" class="modal-input" placeholder="Album (optional)">
          <input id="smart-genre" class="modal-input" placeholder="Genre (optional)">
          <input id="smart-year-min" class="modal-input" type="number" min="1000" max="9999" placeholder="Released after year">
          <input id="smart-year-max" class="modal-input" type="number" min="1000" max="9999" placeholder="Released before year">
          <input id="smart-days" class="modal-input" type="number" min="0" placeholder="Added in last N days">
          <input id="smart-min-plays" class="modal-input" type="number" min="0" placeholder="Minimum play count">
          <input id="smart-unplayed" class="modal-input" type="number" min="0" placeholder="Not played for N days">
          <input id="smart-limit" class="modal-input" type="number" min="1" max="500" value="50" aria-label="Maximum songs">
          <select id="smart-sort" class="modal-input"><option value="newest">Newest added</option><option value="artist">Artist</option><option value="album">Album</option><option value="most_played">Most played</option><option value="least_played">Least played</option></select>
          <label class="smart-check"><input id="smart-favorites" type="checkbox"> Favorites only</label>
        </div>
        <div id="smart-preview" class="modal-subtitle" aria-live="polite"></div>
        <div class="modal-actions"><button class="btn-secondary" onclick="previewSmartPlaylist()">Preview</button><button class="btn-secondary" onclick="closeModal()">Cancel</button><button class="btn-primary" onclick="saveSmartPlaylist()">${editing ? 'Save' : 'Create'}</button></div>
      </div>
    </div>`;
  if (data) {
    const rules = data.smart_rules || {};
    const values = {
      'smart-name': data.name,
      'smart-artist': rules.artist,
      'smart-album': rules.album,
      'smart-genre': rules.genre,
      'smart-year-min': rules.year_min,
      'smart-year-max': rules.year_max,
      'smart-days': rules.added_within_days,
      'smart-min-plays': rules.min_plays,
      'smart-unplayed': rules.not_played_days,
      'smart-limit': rules.limit || 50,
      'smart-sort': rules.sort || 'newest'
    };
    Object.entries(values).forEach(([id, value]) => {
      const input = document.getElementById(id);
      if (input && value != null) input.value = value;
    });
    document.getElementById('smart-favorites').checked = Boolean(rules.favorite_only);
  }
}

function optionalNumber(id) {
  const value = document.getElementById(id)?.value;
  return value === '' || value == null ? null : Number(value);
}

function smartRulesFromForm() {
  return {
    artist: document.getElementById('smart-artist')?.value.trim() || '',
    album: document.getElementById('smart-album')?.value.trim() || '',
    genre: document.getElementById('smart-genre')?.value.trim() || '',
    year_min: optionalNumber('smart-year-min'),
    year_max: optionalNumber('smart-year-max'),
    added_within_days: optionalNumber('smart-days'),
    min_plays: optionalNumber('smart-min-plays'),
    not_played_days: optionalNumber('smart-unplayed'),
    favorite_only: Boolean(document.getElementById('smart-favorites')?.checked),
    limit: optionalNumber('smart-limit') || 50,
    sort: document.getElementById('smart-sort')?.value || 'newest'
  };
}

export async function showEditSmartPlaylistModal(playlistId) {
  const data = await api.fetchPlaylist(playlistId);
  if (!data?.is_smart) return ui.showToast('Smart playlist not found.', 'error');
  smartEditingId = playlistId;
  showSmartPlaylistModal(data);
}

export async function previewSmartPlaylist() {
  const target = document.getElementById('smart-preview');
  try {
    const result = await api.previewSmartPlaylistApi(smartRulesFromForm());
    if (target) target.textContent = `${result.track_count} matching songs · ${result.summary}`;
  } catch (error) {
    if (target) target.textContent = error.message;
  }
}

export async function saveSmartPlaylist() {
  const name = document.getElementById('smart-name')?.value.trim();
  if (!name) return ui.showToast('Enter a playlist name.', 'error');
  try {
    const payload = { name, rules: smartRulesFromForm() };
    const result = smartEditingId
      ? await api.updateSmartPlaylistApi(smartEditingId, payload)
      : await api.createSmartPlaylistApi(payload);
    ui.closeModal();
    ui.showToast(`${smartEditingId ? 'Updated' : 'Created'} with ${result.track_count} songs.`, 'success');
    smartEditingId = null;
    await switchLibraryKind('playlists');
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}

export const createSmartPlaylist = saveSmartPlaylist;

export async function refreshSmartPlaylist(playlistId) {
  try {
    const result = await api.refreshSmartPlaylistApi(playlistId);
    ui.showToast(`Updated: ${result.track_count} songs.`, 'success');
    await switchLibraryKind('playlists');
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}
