/** Browsable library facets and smart-playlist controls. */

import * as api from './api.js';
import * as state from './state.js';
import * as ui from './ui.js';
import * as offlineStore from './offline_store.js';

let activeKind = 'all';
let smartEditingId = null;
let facetQuery = '';
let facetSort = 'recent';
const facetLimit = 50;
let facetOffset = 0;
let showMetadataAlbums = false;
let libraryRequest = 0;
let libraryLayout = window.innerWidth <= 768 ? 'list' : 'grid';
try {
  const saved = localStorage.getItem('aoede_library_layout');
  if (['list', 'grid'].includes(saved)) libraryLayout = saved;
} catch (_) {
  /* Storage is optional. */
}

function trackList(tracks) {
  return `<div class="track-list playlist-track-list"><div class="track-row header playlist-row"><div class="track-num">#</div><div>${ui.t('search.col_title', 'Title')}</div><div class="track-album-col">${ui.t('library.album', 'Album')}</div><div class="track-source-col">${ui.t('search.col_source', 'Source')}</div><div class="track-duration-col"><i data-lucide="clock" width="16"></i></div></div>${tracks.map((track, index) => window.createPlaylistTrackRow(track, index)).join('')}</div>`;
}

function playlistCard(pl) {
  const tracks = Number(pl.track_count || 0);
  const type =
    pl.kind === 'album'
      ? ui.t('library.album', 'Album')
      : pl.is_smart
        ? ui.t('library.smart_playlist', 'Smart playlist')
        : pl.is_collaborative
          ? ui.t('library.collaborative_playlist', 'Collaborative playlist')
          : pl.source_playlist_id || pl.source_instance_id
            ? ui.t('library.synced_playlist', 'Synced playlist')
            : ui.t('library.playlist', 'Playlist');
  const icon = pl.is_liked
    ? 'heart'
    : pl.kind === 'album'
      ? 'disc-3'
      : pl.is_smart
        ? 'sparkles'
        : pl.source_playlist_id || pl.source_instance_id
          ? 'refresh-cw'
          : 'list-music';
  const thumb = pl.thumbnail || '/static/img/default_cover.png';
  const hasThumb = pl.thumbnail && !pl.thumbnail.includes('default');
  return `
    <button type="button" class="library-card${pl.is_liked ? ' library-liked' : ''}" onclick="${pl.is_liked ? "loadView('favorites')" : `loadView('playlist', '${ui.escJs(String(pl.id))}')`}">
      <div class="library-card-cover">
        ${hasThumb ? `<img src="${ui.escHtml(thumb)}" alt="" loading="lazy" onerror="this.src='/static/img/default_cover.png'">` : `<i data-lucide="${icon}"></i>`}
      </div>
      <div class="library-card-name" title="${ui.escHtml(pl.name || 'Playlist')}">${ui.escHtml(pl.name || 'Playlist')}</div>
      <div class="library-card-sub">${type} · ${ui.escHtml(pl.owner_username || window.USER_DATA?.username || '')}${pl.is_federated ? ` @ ${ui.escHtml(pl.fed_instance_name)}` : ''} · ${tracks} ${ui.t(tracks === 1 ? 'common.song' : 'common.songs', tracks === 1 ? 'song' : 'songs')}</div>
    </button>`;
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
      <div class="library-card-sub">${facet.artist ? `${ui.escHtml(facet.artist)} · ` : ''}${facet.track_count} ${ui.t(facet.track_count === 1 ? 'common.song' : 'common.songs', facet.track_count === 1 ? 'song' : 'songs')}</div>
    </button>`;
}

function shell(content, browsing = false, total = 0, hasMore = false) {
  const savedCollections =
    ['all', 'playlists'].includes(activeKind) || (activeKind === 'albums' && !showMetadataAlbums);
  const sorts = savedCollections
    ? [
        ['recent', ui.t('library.sort_recent', 'Recently added')],
        ['name', ui.t('library.sort_name', 'Name')],
        ['owner', ui.t('library.sort_owner', 'Creator')],
        ['count', ui.t('library.sort_count', 'Most songs')]
      ]
    : activeKind === 'songs'
      ? [
          ['title', ui.t('library.song_title', 'Song title')],
          ['artist', ui.t('library.artist', 'Artist')],
          ['newest', ui.t('library.sort_recent', 'Recently added')]
        ]
      : [
          ['name', ui.t('library.sort_name', 'Name')],
          ['count', ui.t('library.sort_count', 'Most songs')]
        ];
  return `
    <section class="library-shell" data-layout="${libraryLayout}">
        <header class="library-head">
          <div>
            <div class="hero-kicker">${ui.t('library.hero_kicker', 'Your personal music collection')}</div>
            <h1 class="hero-greeting">${ui.t('library.title', 'Your Library')}</h1>
            <p class="hero-sub">${ui.t('library.hero_sub', 'Browse your playlists, artists, albums, genres, and device downloads.')}</p>
          </div>
          <div class="library-actions-row">
            <button class="btn-secondary" onclick="showCreateSmartPlaylistModal()" title="${ui.escHtml(ui.t('library.smart_playlist', 'Smart Playlist'))}">
              <i data-lucide="sparkles" width="16" height="16"></i> ${ui.t('library.smart_playlist', 'Smart Playlist')}
            </button>
            <button class="btn-primary" onclick="showCreateMenu()" title="${ui.escHtml(ui.t('library.new_playlist', 'New Playlist'))}">
              <i data-lucide="plus" width="16" height="16"></i> ${ui.t('library.create', 'Create')}
            </button>
          </div>
        </header>

      <nav class="library-filters" aria-label="${ui.escHtml(ui.t('library.filter_type', 'Collection type'))}">
        ${['all', 'playlists', 'albums', 'songs', 'artists', 'genres'].map((kind) => `<button type="button" class="library-filter ${activeKind === kind ? 'active' : ''}" aria-pressed="${activeKind === kind}" onclick="switchLibraryKind('${kind}')">${ui.t(kind === 'all' ? 'tabs.all' : `library.${kind}`, kind[0].toUpperCase() + kind.slice(1))}</button>`).join('')}
        <button class="library-filter" onclick="loadView('offline')" title="${ui.escHtml(ui.t('offline.title', 'Offline Downloads'))}"><i data-lucide="cloud-off" width="14" height="14" style="margin-right:4px;"></i> ${ui.t('library.offline', 'Offline')}</button>
      </nav>

      ${activeKind === 'albums' ? `<label class="collection-search-toggle"><input type="checkbox" role="switch" onchange="toggleLibraryAlbumMetadata(this.checked)"${showMetadataAlbums ? ' checked' : ''}> ${ui.t('library.metadata_albums', 'Browse albums from song metadata')}</label>` : ''}
      ${
        browsing
          ? `<form class="library-sort library-toolbar" onsubmit="reloadLibraryFacets(); return false"><div class="library-query"><input type="search" id="library-facet-query" class="modal-input" value="${ui.escHtml(facetQuery)}" aria-label="${ui.escHtml(ui.t('library.search_saved', 'Search your library'))}" placeholder="${ui.escHtml(ui.t('library.search_saved', 'Search your library'))}"><button type="submit" class="library-icon-btn" title="${ui.escHtml(ui.t('menu.search', 'Search'))}" aria-label="${ui.escHtml(ui.t('menu.search', 'Search'))}"><i data-lucide="search"></i></button></div><select id="library-facet-sort" class="modal-input" aria-label="${ui.escHtml(ui.t('library.sort', 'Sort library'))}" onchange="reloadLibraryFacets()">${sorts
              .map(
                ([value, label]) =>
                  `<option value="${value}"${facetSort === value ? ' selected' : ''}>${label}</option>`
              )
              .join(
                ''
              )}</select>${activeKind !== 'songs' ? `<div class="library-layout" role="group" aria-label="${ui.escHtml(ui.t('library.layout', 'Library layout'))}">${['list', 'grid'].map((layout) => `<button type="button" class="library-icon-btn" data-library-layout="${layout}" aria-pressed="${libraryLayout === layout}" onclick="setLibraryLayout('${layout}')" title="${ui.escHtml(ui.t(`library.view_${layout}`, layout))}" aria-label="${ui.escHtml(ui.t(`library.view_${layout}`, layout))}"><i data-lucide="${layout === 'list' ? 'list' : 'layout-grid'}"></i></button>`).join('')}</div>` : ''}</form>`
          : ''
      }
      ${content}
      ${browsing ? `<div class="library-pagination"><p class="library-facet-count" role="status">${ui.t('common.pagination', 'Showing {start}–{end} of {total}', { start: total ? Math.min(facetOffset + 1, total) : 0, end: Math.min(facetOffset + facetLimit, total), total })}</p><button class="btn-secondary" onclick="previousLibraryPage()"${facetOffset ? '' : ' disabled'}>${ui.t('common.previous', 'Previous')}</button><button class="btn-secondary" onclick="loadMoreLibraryFacets()"${hasMore ? '' : ' disabled'}>${ui.t('common.next', 'Next')}</button></div>` : ''}
    </section>`;
}

function savedLibraryContent(playlists) {
  const collections = [...playlists];
  if (activeKind !== 'albums')
    collections.unshift({
      is_liked: true,
      name: ui.t('menu.liked', 'Liked Songs'),
      owner_username: window.USER_DATA?.username || '',
      track_count: state.userFavorites?.size || 0
    });
  const query = facetQuery.toLocaleLowerCase();
  const visible = collections.filter(
    (pl) =>
      (activeKind === 'all' || (activeKind === 'albums' ? pl.kind === 'album' : pl.kind !== 'album')) &&
      `${pl.name || ''} ${pl.owner_username || ''} ${pl.fed_instance_name || ''}`.toLocaleLowerCase().includes(query)
  );
  if (facetSort !== 'recent')
    visible.sort(
      (a, b) =>
        (facetSort === 'count'
          ? Number(b.track_count || 0) - Number(a.track_count || 0)
          : facetSort === 'owner'
            ? (a.owner_username || '').localeCompare(b.owner_username || '')
            : 0) || (a.name || '').localeCompare(b.name || '')
    );
  facetOffset = Math.min(facetOffset, Math.max(0, Math.ceil(visible.length / facetLimit) - 1) * facetLimit);
  return shell(
    `${activeKind === 'albums' ? `<h2 class="library-section-title">${ui.t('library.your_albums', 'Your albums')}</h2>` : ''}<div class="library-cards-grid">${visible
      .slice(facetOffset, facetOffset + facetLimit)
      .map(playlistCard)
      .join(
        ''
      )}</div>${visible.length ? '' : `<div class="empty-state"><i data-lucide="search-x"></i><p>${ui.t('library.no_collections', 'No saved collections match. Try another search or create a collection.')}</p></div>`}`,
    true,
    visible.length,
    visible.length > facetOffset + facetLimit
  );
}

export async function renderLibrary(container, kind = null) {
  const request = ++libraryRequest;
  const validKinds = ['all', 'playlists', 'songs', 'artists', 'albums', 'genres'];
  if (kind === 'offline') {
    window.loadView('offline');
    return;
  }
  const selectedKind = validKinds.includes(kind) ? kind : validKinds.includes(activeKind) ? activeKind : 'all';
  if (selectedKind !== activeKind) {
    facetQuery = '';
    facetSort = selectedKind === 'songs' ? 'title' : ['all', 'playlists'].includes(selectedKind) ? 'recent' : 'name';
    facetOffset = 0;
  }
  activeKind = selectedKind;
  state.setCurrentViewList([]);
  const browsingCollections =
    ['all', 'playlists'].includes(activeKind) || (activeKind === 'albums' && !showMetadataAlbums);
  const snapshot = `library-page:${JSON.stringify([activeKind, facetQuery, facetSort, facetOffset])}`;
  try {
    if (browsingCollections) {
      let playlists = [];
      if (navigator.onLine) {
        playlists = await api.fetchPlaylists();
        if (request !== libraryRequest) return;
        state.setUserPlaylists(playlists);
        await offlineStore.saveLibrarySnapshot('playlists', playlists);
      } else {
        playlists = (await offlineStore.getLibrarySnapshot('playlists')) || [];
      }
      if (request !== libraryRequest) return;
      container.innerHTML = savedLibraryContent(playlists);
      window.renderSidebarPlaylists?.();
    } else if (activeKind === 'songs') {
      const page = navigator.onLine
        ? await api.fetchLibraryTracks({ q: facetQuery, sort: facetSort, limit: facetLimit, offset: facetOffset })
        : (await offlineStore.getLibrarySnapshot(snapshot)) || { items: [], total: 0 };
      if (request !== libraryRequest) return;
      if (navigator.onLine) await offlineStore.saveLibrarySnapshot(snapshot, page);
      if (request !== libraryRequest) return;
      state.setCurrentViewList(page.items || []);
      container.innerHTML = shell(
        page.items?.length
          ? trackList(page.items)
          : `<div class="empty-state"><p>${ui.t('library.no_songs', 'No songs match this filter.')}</p></div>`,
        true,
        page.total || 0,
        Boolean(page.has_more)
      );
    } else {
      let page = { items: [], total: 0, has_more: false };
      if (navigator.onLine) {
        page = await api.fetchLibraryFacets(activeKind, {
          q: facetQuery,
          sort: facetSort,
          limit: facetLimit,
          offset: facetOffset
        });
        if (request !== libraryRequest) return;
        await offlineStore.saveLibrarySnapshot(snapshot, page);
      } else {
        page = (await offlineStore.getLibrarySnapshot(snapshot)) || {
          items: [],
          total: 0,
          has_more: false
        };
      }
      if (request !== libraryRequest) return;
      const facets = page.items || [];
      container.innerHTML = shell(
        (activeKind === 'albums'
          ? `<h2>${ui.t('library.metadata_title', 'Albums from song metadata')}</h2><p class="modal-subtitle">${ui.t('library.metadata_description', 'Groups based on song tags may be incomplete. Turn off metadata browsing to open your saved, editable albums.')}</p>`
          : '') +
          (facets.length
            ? `<div class="library-cards-grid">${facets.map((facet) => facetCard(activeKind, facet)).join('')}</div>`
            : `<div class="empty-state"><p>${ui.t('library.no_metadata', 'No matching metadata found.')}</p></div>`),
        true,
        page.total || 0,
        Boolean(page.has_more)
      );
    }
    ui.refreshIcons(container);
  } catch (error) {
    if (request !== libraryRequest) return;
    const cached = await offlineStore.getLibrarySnapshot('playlists');
    if (request !== libraryRequest) return;
    if (browsingCollections && cached?.length) {
      container.innerHTML =
        `<p role="status">${ui.t('library.cached', 'Showing saved collections. The server could not be reached.')}</p>` +
        savedLibraryContent(cached);
      ui.refreshIcons(container);
    } else {
      container.innerHTML = `<div class="empty-state glass-panel"><p>${ui.t('library.load_failed', 'Failed to load your library.')}</p></div>`;
    }
    console.error('[LIBRARY]', error);
  }
}

export function setLibraryLayout(layout) {
  if (!['list', 'grid'].includes(layout)) return;
  libraryLayout = layout;
  const shell = document.querySelector('.library-shell');
  if (shell) shell.dataset.layout = layout;
  document
    .querySelectorAll('[data-library-layout]')
    .forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.libraryLayout === layout)));
  try {
    localStorage.setItem('aoede_library_layout', layout);
  } catch (_) {
    /* Storage is optional. */
  }
}

export async function switchLibraryKind(kind) {
  const container = document.getElementById('view-container');
  if (container) await renderLibrary(container, kind);
}

export async function toggleLibraryAlbumMetadata(enabled) {
  showMetadataAlbums = Boolean(enabled);
  facetQuery = '';
  facetSort = 'name';
  facetOffset = 0;
  return switchLibraryKind('albums');
}

export async function reloadLibraryFacets() {
  facetQuery = document.getElementById('library-facet-query')?.value.trim() || '';
  facetSort = document.getElementById('library-facet-sort')?.value || 'name';
  facetOffset = 0;
  return switchLibraryKind(activeKind);
}

export async function loadMoreLibraryFacets() {
  facetOffset += facetLimit;
  return switchLibraryKind(activeKind);
}

export async function previousLibraryPage() {
  facetOffset = Math.max(0, facetOffset - facetLimit);
  return switchLibraryKind(activeKind);
}

export async function openLibraryFacet(key, value, albumArtist = '', offset = 0) {
  const container = document.getElementById('view-container');
  if (!container) return;
  const request = ++libraryRequest;
  try {
    const filters = { [key]: value, limit: facetLimit, offset, sort: key === 'album' ? 'album' : 'artist' };
    if (key === 'album' && albumArtist) filters.artist = albumArtist;
    const page = await api.fetchLibraryTracks(filters);
    if (request !== libraryRequest) return;
    const tracks = page.items || [];
    state.setCurrentViewList(tracks);
    const facetAction = `openLibraryFacet('${ui.escJs(key)}', '${ui.escJs(value)}', '${ui.escJs(albumArtist)}',`;
    container.innerHTML = `
      <section class="library-shell">
        <button class="library-back" onclick="switchLibraryKind('${key === 'artist' ? 'artists' : key === 'album' ? 'albums' : 'genres'}')"><i data-lucide="arrow-left"></i> ${ui.t('library.sidebar_title', 'Library')}</button>
        <h1 class="library-title">${ui.escHtml(value)}</h1>
        ${albumArtist ? `<p class="library-facet-count">${ui.escHtml(albumArtist)}</p>` : ''}
        <p class="library-facet-count">${page.total || tracks.length} ${ui.t('common.songs', 'songs')}</p>
        ${trackList(tracks)}
        <div class="library-pagination"><button class="btn-secondary" onclick="${facetAction} ${Math.max(0, offset - facetLimit)})"${offset ? '' : ' disabled'}>${ui.t('common.previous', 'Previous')}</button><span>${ui.t('common.pagination', 'Showing {start}–{end} of {total}', { start: page.total ? offset + 1 : 0, end: offset + tracks.length, total: page.total || 0 })}</span><button class="btn-secondary" onclick="${facetAction} ${offset + facetLimit})"${page.has_more ? '' : ' disabled'}>${ui.t('common.next', 'Next')}</button></div>
      </section>`;
    ui.refreshIcons(container);
  } catch (error) {
    if (request !== libraryRequest) return;
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
        <h2>${editing ? ui.t('common.edit', 'Edit') : ui.t('library.create_smart_playlist_title', 'New smart playlist')}</h2>
        <p class="modal-subtitle">${ui.t('library.smart_description', 'Preview the rules before saving. Matching songs also refresh when favorites change.')}</p>
        <input id="smart-name" class="modal-input" maxlength="100" placeholder="${ui.escHtml(ui.t('library.smart_playlist_name', 'Playlist name'))}">
        <div class="smart-rule-grid">
          <input id="smart-artist" class="modal-input" placeholder="${ui.escHtml(ui.t('library.smart_artist', 'Artist (optional)'))}">
          <input id="smart-album" class="modal-input" placeholder="${ui.escHtml(ui.t('library.smart_album', 'Album (optional)'))}">
          <input id="smart-genre" class="modal-input" placeholder="${ui.escHtml(ui.t('library.smart_genre', 'Genre (optional)'))}">
          <input id="smart-year-min" class="modal-input" type="number" min="1000" max="9999" placeholder="${ui.escHtml(ui.t('library.smart_year_min', 'Released after year'))}">
          <input id="smart-year-max" class="modal-input" type="number" min="1000" max="9999" placeholder="${ui.escHtml(ui.t('library.smart_year_max', 'Released before year'))}">
          <input id="smart-days" class="modal-input" type="number" min="0" placeholder="${ui.escHtml(ui.t('library.smart_days', 'Added in last N days'))}">
          <input id="smart-min-plays" class="modal-input" type="number" min="0" placeholder="${ui.escHtml(ui.t('library.smart_min_plays', 'Minimum play count'))}">
          <input id="smart-unplayed" class="modal-input" type="number" min="0" placeholder="${ui.escHtml(ui.t('library.smart_unplayed', 'Not played for N days'))}">
          <input id="smart-limit" class="modal-input" type="number" min="1" max="500" value="50" aria-label="${ui.escHtml(ui.t('library.maximum_songs', 'Maximum songs'))}">
          <select id="smart-sort" class="modal-input"><option value="newest">${ui.t('library.sort_recent', 'Recently added')}</option><option value="artist">${ui.t('library.artist', 'Artist')}</option><option value="album">${ui.t('library.album', 'Album')}</option><option value="most_played">${ui.t('library.most_played', 'Most played')}</option><option value="least_played">${ui.t('library.least_played', 'Least played')}</option></select>
          <label class="smart-check"><input id="smart-favorites" type="checkbox"> ${ui.t('library.favorites_only', 'Favorites only')}</label>
        </div>
        <div id="smart-preview" class="modal-subtitle" aria-live="polite"></div>
        <div class="modal-actions"><button class="btn-secondary" onclick="previewSmartPlaylist()">${ui.t('common.preview', 'Preview')}</button><button class="btn-secondary" onclick="closeModal()">${ui.t('common.cancel', 'Cancel')}</button><button class="btn-primary" onclick="saveSmartPlaylist()">${editing ? ui.t('common.save', 'Save') : ui.t('library.create', 'Create')}</button></div>
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
  if (!data?.is_smart) return ui.showToast(ui.t('library.smart_not_found', 'Smart playlist not found.'), 'error');
  smartEditingId = playlistId;
  showSmartPlaylistModal(data);
}

export async function previewSmartPlaylist() {
  const target = document.getElementById('smart-preview');
  try {
    const result = await api.previewSmartPlaylistApi(smartRulesFromForm());
    if (target)
      target.textContent = ui.t('library.smart_preview', '{count} matching songs · {summary}', {
        count: result.track_count,
        summary: result.summary
      });
  } catch (error) {
    if (target) target.textContent = error.message;
  }
}

export async function saveSmartPlaylist() {
  const name = document.getElementById('smart-name')?.value.trim();
  if (!name) return ui.showToast(ui.t('library.enter_name', 'Enter a playlist name.'), 'error');
  try {
    const payload = { name, rules: smartRulesFromForm() };
    const result = smartEditingId
      ? await api.updateSmartPlaylistApi(smartEditingId, payload)
      : await api.createSmartPlaylistApi(payload);
    ui.closeModal();
    ui.showToast(
      ui.t(
        smartEditingId ? 'library.smart_updated' : 'library.smart_created',
        smartEditingId ? 'Updated with {count} songs.' : 'Created with {count} songs.',
        { count: result.track_count }
      ),
      'success'
    );
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
    ui.showToast(
      ui.t('library.smart_updated', 'Updated with {count} songs.', { count: result.track_count }),
      'success'
    );
    await switchLibraryKind('playlists');
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}
