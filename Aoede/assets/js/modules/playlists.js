/**
 * playlists.js - Playlist Management
 * CRUD operations, modals, and view rendering
 */

import * as state from './state.js';
import * as ui from './ui.js';
import * as api from './api.js';
import * as player from './player.js';
import * as offlineStore from './offline_store.js';

let playlistRevision = null;
let creatingCollaborative = false;
document.addEventListener('aoede:modalclosed', () => {
  creatingCollaborative = false;
});

export function showCreateMenu() {
  creatingCollaborative = false;
  selectedImportTracks = [];
  document.getElementById('modal-container').innerHTML = `
    <div class="modal-overlay create-menu-overlay" onclick="if(event.target===this) closeModal()">
      <div class="modal create-menu" role="dialog" aria-modal="true" aria-label="Create">
        <button class="create-option" onclick="startPlaylistCreation(false)"><span class="create-option-icon"><i data-lucide="music"></i></span><span><strong>Playlist</strong><small>Create a playlist with your favorite songs</small></span></button>
        <button class="create-option" onclick="startPlaylistCreation(true)"><span class="create-option-icon"><i data-lucide="users"></i></span><span><strong>Collaborative playlist</strong><small>Create a playlist together with friends</small></span></button>
        <button class="create-option" onclick="showCreateSmartPlaylistModal()"><span class="create-option-icon"><i data-lucide="sparkles"></i></span><span><strong>Smart playlist</strong><small>Let your library build a mix from your rules</small></span></button>
        <button class="create-menu-close" onclick="closeModal()" aria-label="Close create menu"><i data-lucide="x"></i></button>
      </div>
    </div>`;
  ui.refreshIcons(document.getElementById('modal-container'));
  ui.focusModal();
}

export function startPlaylistCreation(collaborative) {
  creatingCollaborative = collaborative;
  selectedImportTracks = [];
  showCreatePlaylistModal();
}

async function collaboratorsRequest(playlistId, method = 'GET', userId = null) {
  if (!navigator.onLine) throw new Error('Connect to manage collaborators.');
  const response = await fetch(
    `${state.API}/playlists/${playlistId}/collaborators${userId === null ? '' : `/${userId}`}`,
    {
      method,
      headers: { 'Content-Type': 'application/json' },
      ...(method === 'POST'
        ? { body: JSON.stringify({ username: document.getElementById('collaborator-username')?.value.trim() || '' }) }
        : {})
    }
  );
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'Unable to update collaborators.');
  return data;
}

let cachedServerUsers = [];

export async function showCollaboratorsModal(playlistId) {
  try {
    const [data, usersList] = await Promise.all([
      collaboratorsRequest(playlistId),
      api.fetchUsersList().catch(() => [])
    ]);
    cachedServerUsers = usersList || [];

    const existingMemberNames = new Set(data.members.map((m) => m.username.toLowerCase()));
    const currentUser = (window.USER_DATA?.username || '').toLowerCase();
    const availableUsers = cachedServerUsers.filter(
      (u) => !existingMemberNames.has(u.username.toLowerCase()) && u.username.toLowerCase() !== currentUser
    );

    document.getElementById('modal-container').innerHTML = `
      <div class="modal-overlay" onclick="if(event.target===this) closeModal()">
        <div class="modal modal-create" role="dialog" aria-modal="true" aria-labelledby="collaborators-title" style="max-width:520px; max-height:85vh; display:flex; flex-direction:column;">
          <div class="modal-header"><h2 id="collaborators-title">Collaborators</h2><button class="modal-close" onclick="closeModal()" aria-label="Close"><i data-lucide="x"></i></button></div>
          <p class="modal-subtitle" style="margin-bottom:14px;">Invite someone on this Aoede server. They can add, remove, and reorder songs. You control sharing and deletion.</p>
          
          ${
            data.is_owner
              ? `
          <div style="margin-bottom: 16px;">
            <label class="modal-label" for="collaborator-username">Add Collaborator</label>
            <div class="collaborator-form" style="position:relative;">
              <input id="collaborator-username" class="modal-input" maxlength="100" autocomplete="off" placeholder="Search user to invite..." style="margin-bottom:0;" oninput="filterCollaboratorUsers(this.value, ${playlistId})">
              <button class="btn-primary" type="button" onclick="submitInviteCollaborator(${playlistId})">Invite</button>
            </div>
            <div id="collab-user-suggestions" class="glass-panel" style="margin-top:8px; max-height:160px; overflow-y:auto; border-radius:8px; padding:6px; display:${availableUsers.length ? 'flex' : 'none'}; flex-direction:column; gap:4px;">
              ${availableUsers
                .map(
                  (u) => `
                <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 8px; border-radius:6px; background:rgba(255,255,255,0.03); cursor:pointer; font-size:0.85rem;" onclick="selectCollabUser('${ui.escHtml(u.username).replace(/'/g, "\\'")}', ${playlistId})">
                  <div style="display:flex; align-items:center; gap:8px;">
                    <img src="${u.avatar_url}?t=${Date.now()}" style="width:24px; height:24px; border-radius:50%; object-fit:cover;" onerror="this.src='/static/img/default_cover.png'">
                    <span><strong>${ui.escHtml(u.username)}</strong></span>
                  </div>
                  <span class="btn-secondary" style="padding:2px 8px; font-size:0.75rem;">Select</span>
                </div>`
                )
                .join('')}
            </div>
          </div>`
              : ''
          }

          <div style="flex:1; overflow-y:auto;">
            <span class="modal-label">Current Members (${data.members.length})</span>
            <div class="collaborator-list" style="display:flex; flex-direction:column; gap:6px;">
              ${
                data.members.length
                  ? data.members
                      .map(
                        (member) => `
                <div class="collaborator-row" style="display:flex; align-items:center; justify-content:space-between; padding:8px; background:rgba(255,255,255,0.03); border-radius:6px;">
                  <span><strong>${ui.escHtml(member.username)}</strong></span>
                  ${data.is_owner ? `<button class="btn-secondary" onclick="updateCollaborator(${playlistId}, 'DELETE', ${Number(member.id)})" aria-label="Remove ${ui.escHtml(member.username)}">Remove</button>` : ''}
                </div>`
                      )
                      .join('')
                  : '<p class="modal-subtitle">No collaborators yet.</p>'
              }
            </div>
          </div>
        </div>
      </div>`;
    ui.refreshIcons(document.getElementById('modal-container'));
    ui.focusModal();
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}

window.filterCollaboratorUsers = function (query, playlistId) {
  const suggestions = document.getElementById('collab-user-suggestions');
  if (!suggestions) return;
  const q = (query || '').trim().toLowerCase();
  const filtered = cachedServerUsers.filter((u) => u.username.toLowerCase().includes(q));

  if (!filtered.length) {
    suggestions.style.display = 'none';
    suggestions.innerHTML = '';
    return;
  }

  suggestions.style.display = 'flex';
  suggestions.innerHTML = filtered
    .map(
      (u) => `
    <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 8px; border-radius:6px; background:rgba(255,255,255,0.03); cursor:pointer; font-size:0.85rem;" onclick="selectCollabUser('${ui.escHtml(u.username).replace(/'/g, "\\'")}', ${playlistId})">
      <div style="display:flex; align-items:center; gap:8px;">
        <img src="${u.avatar_url}?t=${Date.now()}" style="width:24px; height:24px; border-radius:50%; object-fit:cover;" onerror="this.src='/static/img/default_cover.png'">
        <span><strong>${ui.escHtml(u.username)}</strong></span>
      </div>
      <span class="btn-secondary" style="padding:2px 8px; font-size:0.75rem;">Select</span>
    </div>`
    )
    .join('');
};

window.selectCollabUser = function (username, playlistId) {
  const input = document.getElementById('collaborator-username');
  if (input) input.value = username;
  if (typeof window.submitInviteCollaborator === 'function') {
    window.submitInviteCollaborator(playlistId);
  }
};

window.submitInviteCollaborator = async function (playlistId) {
  const input = document.getElementById('collaborator-username');
  const val = input?.value?.trim();
  if (!val) return;
  await updateCollaborator(playlistId, 'POST');
};

export async function updateCollaborator(playlistId, method, userId = null) {
  try {
    await collaboratorsRequest(playlistId, method, userId);
    ui.showToast(method === 'POST' ? 'Collaborator invited.' : 'Collaborator removed.', 'success');
    await showCollaboratorsModal(playlistId);
    if (window.loadUserData) window.loadUserData();
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}

export async function leaveCollaborativePlaylist(playlistId) {
  try {
    const data = await collaboratorsRequest(playlistId);
    const self = data.members.find((member) => member.username === window.USER_DATA?.username);
    if (!self || !window.confirm('Leave this collaborative playlist?')) return;
    await collaboratorsRequest(playlistId, 'DELETE', self.id);
    await window.loadView('library');
    if (window.loadUserData) window.loadUserData();
  } catch (error) {
    ui.showToast(error.message, 'error');
  }
}

// === RENDER PLAYLIST VIEW ===

export async function renderPlaylist(container, playlistId) {
  let data = null;
  const numId = Number(playlistId);

  try {
    if (typeof navigator !== 'undefined' && navigator.onLine) {
      const res = await fetch(`${state.API}/playlists/${numId}`);
      if ([401, 403, 404].includes(res.status)) {
        container.innerHTML = '<div class="empty-state"><p>This playlist is no longer available to you.</p></div>';
        return;
      }
      if (res.ok) {
        const json = await res.json();
        if (json && !json.error && !json.offline) {
          data = json;
          offlineStore.saveLibrarySnapshot(`playlist_${numId}`, data);
        }
      }
    }
  } catch (e) {
    /* fallback to offline snapshot below */
  }

  // If online fetch did not return valid data or we are offline:
  if (!data || data.error || data.offline) {
    data = await offlineStore.getLibrarySnapshot(`playlist_${numId}`);

    // If no full snapshot, check offline playlists manifest and offline tracks store
    if (!data || !data.tracks || !data.tracks.length) {
      const offlinePlaylists = await offlineStore.listOfflinePlaylists();
      const matchedPl = offlinePlaylists.find((p) => Number(p.id) === numId);
      const allOfflineTracks = await offlineStore.listOfflineTracks();
      const plTracks = allOfflineTracks.filter((t) => t.playlist_ids && t.playlist_ids.includes(numId));

      if (matchedPl || plTracks.length > 0) {
        data = {
          id: numId,
          name: matchedPl?.name || plTracks[0]?.playlist_names?.[numId] || 'Offline Playlist',
          thumbnail: matchedPl?.thumbnail || '',
          tracks: plTracks,
          is_public: false,
          is_owner: true,
          is_editable: false
        };
      }
    }
  }

  if (!data || (data.error && (!data.tracks || !data.tracks.length))) {
    container.innerHTML = `<div class="empty-state glass-panel"><i data-lucide="list-music" class="empty-icon"></i><p>${ui.escHtml(data?.error || 'Playlist not available offline. Download it while connected to play offline.')}</p></div>`;
    ui.refreshIcons(container);
    return;
  }

  const tracks = (data.tracks || []).map((t) => ({
    ...t,
    db_id: t.track_id || t.id || t.db_id,
    id: t.track_id || t.id || t.db_id,
    is_local: true
  }));
  state.setCurrentViewList(tracks);

  const thumb = data.thumbnail || '/static/img/default_cover.png';
  const hasThumb = data.thumbnail && !data.thumbnail.includes('default');
  const trackCount = data.tracks?.length || 0;
  const isPublic = Boolean(data.is_public);
  const isOwner = Boolean(data.is_owner);
  const isEditable = Boolean(data.is_editable);
  const isSmart = Boolean(data.is_smart);
  playlistRevision = data.revision ?? null;
  const isSyncedCopy = Boolean(data.source_playlist_id);
  const sourcePlaylistAvailable = Boolean(data.source_playlist_exists && data.source_playlist_public);
  const ownerLabel = data.owner_username
    ? `By <a class="artist-link" onclick="loadView('profile', '${ui.escHtml(data.owner_username).replace(/'/g, "\\'")}')" style="cursor:pointer; font-weight:600;">${ui.escHtml(data.owner_username)}</a>`
    : '';
  const sourceBadge = isSyncedCopy
    ? '<span class="source-badge musicbrainz" style="margin-left:8px;">Synced copy</span>'
    : '';
  const smartBadge = isSmart
    ? '<span class="source-badge local" style="margin-left:8px;"><i data-lucide="sparkles" width="12"></i> Smart</span>'
    : '';
  const visibilityBadge = isPublic
    ? '<span class="source-badge spotify" style="margin-left:8px;">Public</span>'
    : '<span class="source-badge local" style="margin-left:8px;">Private</span>';
  // Total playlist duration. Tracks without a duration just don't
  // contribute; the worst case is a "n songs" stat with no time.
  const totalSeconds = (data.tracks || []).reduce((acc, t) => acc + (Number(t.duration) || 0), 0);
  const fmtTotal = (s) => {
    if (!s) return '';
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    if (h > 0) return `${h} h ${m} min`;
    if (m > 0) return `${m} min`;
    return '< 1 min';
  };
  const totalLabel = fmtTotal(totalSeconds);
  const ownerControls =
    isOwner && isEditable
      ? `
        <button class="icon-btn-sm" onclick="showEditPlaylistModal(${playlistId}, document.getElementById('playlist-title-${playlistId}').textContent)" title="Edit Name">
            <i data-lucide="pencil" width="16"></i>
        </button>`
      : '';
  const coverControls = isOwner
    ? `
        <div class="playlist-cover-actions">
            <button class="icon-btn-sm" onclick="openPlaylistCoverUpload(${playlistId})" title="Upload cover">
                <i data-lucide="image-plus" width="16"></i>
            </button>
            <button class="icon-btn-sm" onclick="showPlaylistCoverTrackModal(${playlistId})" title="Choose track cover">
                <i data-lucide="disc-3" width="16"></i>
            </button>
            <button class="icon-btn-sm" onclick="resetPlaylistCover(${playlistId})" title="Use automatic cover">
                <i data-lucide="rotate-ccw" width="16"></i>
            </button>
            <input type="file" id="playlist-cover-input-${playlistId}" accept="image/png,image/jpeg,image/webp,image/gif" style="display:none" onchange="handlePlaylistCoverUpload(${playlistId}, this)">
        </div>`
    : '';
  const publishButton =
    isOwner && !isSyncedCopy
      ? `
        <button onclick="togglePlaylistPublic(${playlistId}, ${isPublic ? 'false' : 'true'})" class="btn-secondary-lg playlist-action-btn" title="${isPublic ? 'Make Private' : 'Make Public'}" aria-label="${isPublic ? 'Make Private' : 'Make Public'}">
            <i data-lucide="${isPublic ? 'lock' : 'globe'}" width="20" height="20"></i>
            <span class="playlist-btn-label">${isPublic ? 'Make Private' : 'Make Public'}</span>
        </button>`
      : '';
  const copyButton =
    !isOwner && isPublic
      ? `
        <button onclick="copyPublicPlaylist(${playlistId})" class="btn-secondary-lg playlist-action-btn" title="Create / Sync Copy" aria-label="Create / Sync Copy">
            <i data-lucide="copy-plus" width="20" height="20"></i>
            <span class="playlist-btn-label">Create / Sync Copy</span>
        </button>`
      : '';
  const syncButton =
    isOwner && isSyncedCopy
      ? `
        ${
          sourcePlaylistAvailable
            ? `
        <button onclick="copyPublicPlaylist(${data.source_playlist_id})" class="btn-secondary-lg playlist-action-btn" title="Sync from Source" aria-label="Sync from Source">
            <i data-lucide="refresh-cw" width="20" height="20"></i>
            <span class="playlist-btn-label">Sync from Source</span>
        </button>`
            : `
        <button class="btn-secondary-lg playlist-action-btn" disabled style="opacity:0.55; cursor:not-allowed;" title="Source is private" aria-label="Source is private">
            <i data-lucide="lock" width="20" height="20"></i>
            <span class="playlist-btn-label">Source is private</span>
        </button>`
        }`
      : '';
  const smartRefreshButton =
    isOwner && isSmart
      ? `<button onclick="refreshSmartPlaylist(${playlistId}).then(() => loadView('playlist', ${playlistId}))" class="btn-secondary-lg playlist-action-btn" title="Refresh smart rules">
          <i data-lucide="refresh-cw" width="20" height="20"></i><span class="playlist-btn-label">Refresh</span>
        </button>`
      : '';
  const downloadedInPlaylist = (data.tracks || []).filter((t) =>
    offlineStore.isTrackAvailableOfflineSync(t.track_id || t.id || t.db_id)
  );
  const isFullyDownloaded = trackCount > 0 && downloadedInPlaylist.length === trackCount;
  const isPartiallyDownloaded = downloadedInPlaylist.length > 0 && !isFullyDownloaded;

  const offlineButton =
    trackCount > 0
      ? `
        <button id="pl-offline-btn-${playlistId}" onclick="togglePlaylistOfflineDownload(${playlistId})" class="btn-secondary playlist-offline-btn ${isFullyDownloaded ? 'downloaded' : isPartiallyDownloaded ? 'partial' : ''}" title="${isFullyDownloaded ? 'Downloaded offline · Tap to manage' : 'Download playlist for offline listening'}">
            <i data-lucide="${isFullyDownloaded ? 'check-circle' : 'arrow-down-circle'}" width="16" height="16"></i>
            <span>${isFullyDownloaded ? 'Downloaded' : isPartiallyDownloaded ? `Download (${downloadedInPlaylist.length}/${trackCount})` : 'Download'}</span>
        </button>`
      : '';

  const deleteButton = isOwner
    ? `
        <button onclick="event.stopPropagation(); showDeletePlaylistModal(${playlistId}, document.getElementById('playlist-title-${playlistId}').textContent)" class="btn-danger-outline playlist-action-btn" title="Delete" aria-label="Delete">
            <i data-lucide="trash-2" width="16"></i>
            <span class="playlist-btn-label">Delete</span>
        </button>`
    : '';

  container.innerHTML = `
        <div class="playlist-header-section">
            <div class="playlist-cover-large">
                ${hasThumb ? `<img src="${thumb}" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'">` : `<i data-lucide="list-music"></i>`}
                ${coverControls}
            </div>
            <div class="playlist-info">
                <p class="playlist-type">${isSmart ? 'Smart playlist' : data.is_collaborative ? 'Collaborative playlist' : 'Playlist'}</p>
                <div class="playlist-title-row">
                    <h1 class="playlist-title" id="playlist-title-${playlistId}">${ui.escHtml(data.name || 'Playlist')}</h1>
                    ${ownerControls}
                </div>
                <p class="playlist-stats">
                    ${ownerLabel ? `<span>${ownerLabel}</span><span class="playlist-stats-dot">·</span>` : ''}
                    <span>${trackCount} ${trackCount === 1 ? 'song' : 'songs'}</span>
                    ${totalLabel ? `<span class="playlist-stats-dot">·</span><span>${totalLabel}</span>` : ''}
                    ${visibilityBadge}${sourceBadge}${smartBadge}
                </p>
                <div class="playlist-actions">
                    ${
                      trackCount > 0
                        ? `
                    <button onclick="playPlaylistInOrder()" class="btn-play-circle" title="Play" aria-label="Play">
                        <i data-lucide="play" width="22" height="22"></i>
                    </button>
                    <button onclick="playPlaylistShuffle()" class="btn-icon-pill" title="Shuffle" aria-label="Shuffle">
                        <i data-lucide="shuffle" width="18" height="18"></i>
                    </button>
                    ${offlineButton}
                    `
                        : ''
                    }
                    ${publishButton}
                    ${copyButton}
                    ${syncButton}
                    ${smartRefreshButton}
                    ${isOwner && isEditable ? `<button class="btn-secondary-lg playlist-action-btn" onclick="showCollaboratorsModal(${numId})"><i data-lucide="users" width="20"></i><span class="playlist-btn-label">Collaborators</span></button>` : ''}
                    ${!isOwner && isEditable ? `<button class="btn-secondary-lg playlist-action-btn" onclick="leaveCollaborativePlaylist(${numId})"><i data-lucide="log-out" width="20"></i><span class="playlist-btn-label">Leave playlist</span></button>` : ''}
                    ${deleteButton}
                </div>
            </div>
        </div>
        ${
          trackCount > 0
            ? `<div class="track-list playlist-track-list">
                <div class="track-row header playlist-row">
                    <div class="track-num">#</div>
                    <div>Title</div>
                    <div class="track-album-col">Album</div>
                    <div class="track-source-col">Source</div>
                    <div class="track-duration-col"><i data-lucide="clock" width="16" height="16"></i></div>
                </div>
                ${state.currentViewList
                  .map((t, i) =>
                    window.createPlaylistTrackRow
                      ? window.createPlaylistTrackRow(
                          { ...t, is_local: true, source: 'local' },
                          i,
                          isEditable ? playlistId : null
                        )
                      : ''
                  )
                  .join('')}
              </div>`
            : '<div class="empty-state glass-panel"><p>This playlist is empty.</p></div>'
        }`;
  lucide.createIcons();
  if (isEditable && trackCount > 0) {
    initPlaylistDragDrop(container, playlistId);
  }
}

// === DRAG-AND-DROP REORDER ===

function initPlaylistDragDrop(container, playlistId) {
  const trackList = container.querySelector('.track-list');
  if (!trackList) return;

  const gripSvg = `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><circle cx="9" cy="5" r="1.5"/><circle cx="9" cy="12" r="1.5"/><circle cx="9" cy="19" r="1.5"/><circle cx="15" cy="5" r="1.5"/><circle cx="15" cy="12" r="1.5"/><circle cx="15" cy="19" r="1.5"/></svg>`;

  // Mark the list so the CSS can add the extra grip column without
  // breaking the base .track-row grid-template-columns.
  trackList.classList.add('playlist-draggable-list');

  Array.from(trackList.querySelectorAll('.track-row:not(.header)')).forEach((row, index) => {
    const handle = document.createElement('button');
    handle.type = 'button';
    handle.setAttribute('aria-label', `Reorder ${state.currentViewList[index]?.title || 'song'}`);
    handle.addEventListener('click', (event) => {
      event.stopPropagation();
      showReorderMenu(playlistId, index);
    });
    handle.className = 'track-drag-handle';
    handle.title = 'Drag to reorder';
    handle.innerHTML = gripSvg;
    // Only activate draggable on mousedown on the handle itself so normal
    // row clicks (play) still work.
    handle.addEventListener('mousedown', () => {
      row.draggable = true;
    });
    handle.addEventListener(
      'touchstart',
      () => {
        row.draggable = true;
      },
      { passive: true }
    );
    row.prepend(handle);
    row.dataset.dragIndex = index;
  });

  let dragSrc = null;

  trackList.addEventListener('dragstart', (e) => {
    const row = e.target.closest('.track-row');
    if (!row) return;
    dragSrc = row;
    row.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', row.dataset.dragIndex);
  });

  trackList.addEventListener('dragover', (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    const row = e.target.closest('.track-row');
    if (!row || row === dragSrc) return;
    trackList.querySelectorAll('.drag-over').forEach((r) => r.classList.remove('drag-over'));
    row.classList.add('drag-over');
  });

  trackList.addEventListener('dragleave', (e) => {
    if (!trackList.contains(e.relatedTarget)) {
      trackList.querySelectorAll('.drag-over').forEach((r) => r.classList.remove('drag-over'));
    }
  });

  trackList.addEventListener('dragend', () => {
    trackList.querySelectorAll('.track-row').forEach((r) => {
      r.classList.remove('dragging', 'drag-over');
      r.draggable = false;
    });
    dragSrc = null;
  });

  trackList.addEventListener('drop', async (e) => {
    e.preventDefault();
    const targetRow = e.target.closest('.track-row');
    if (!targetRow || !dragSrc || targetRow === dragSrc) return;

    const allRows = Array.from(trackList.querySelectorAll('.track-row:not(.header)'));
    const fromIdx = allRows.indexOf(dragSrc);
    const toIdx = allRows.indexOf(targetRow);
    if (fromIdx === toIdx) return;

    // Reorder DOM immediately for snappy UX
    if (fromIdx < toIdx) targetRow.after(dragSrc);
    else targetRow.before(dragSrc);

    // Reorder in-memory view list
    const newOrder = [...state.currentViewList];
    const [moved] = newOrder.splice(fromIdx, 1);
    newOrder.splice(toIdx, 0, moved);
    state.setCurrentViewList(newOrder);

    // Update drag indices
    trackList.querySelectorAll('.track-row').forEach((r, i) => {
      r.dataset.dragIndex = i;
    });

    // Persist to server
    const items = newOrder.map((t, i) => ({ track_id: t.db_id || t.id, position: i + 1 }));
    const result = await api.reorderPlaylistApi(playlistId, items, playlistRevision);
    if (result?.ok) {
      playlistRevision = result.revision;
      ui.showToast('Order saved', 'success');
    } else {
      ui.showToast('Playlist changed or order could not be saved. Reloading.', 'error');
    }
    await renderPlaylist(container, playlistId);
  });
}

function showReorderMenu(playlistId, index) {
  document.getElementById('modal-container').innerHTML =
    `<div class="modal-overlay" onclick="if(event.target===this) closeModal()"><div class="modal" role="dialog" aria-modal="true" aria-label="Reorder song"><h2>Move song</h2><div class="modal-actions"><button class="btn-secondary" ${index === 0 ? 'disabled' : ''} onclick="movePlaylistTrack(${playlistId}, ${index}, -1)">Move up</button><button class="btn-secondary" ${index === state.currentViewList.length - 1 ? 'disabled' : ''} onclick="movePlaylistTrack(${playlistId}, ${index}, 1)">Move down</button><button class="btn-secondary" onclick="closeModal()">Cancel</button></div></div></div>`;
  ui.focusModal();
}

export async function movePlaylistTrack(playlistId, index, direction) {
  const tracks = [...state.currentViewList];
  const target = index + direction;
  if (target < 0 || target >= tracks.length) return;
  [tracks[index], tracks[target]] = [tracks[target], tracks[index]];
  const result = await api.reorderPlaylistApi(
    playlistId,
    tracks.map((track, i) => ({ track_id: track.db_id || track.id, position: i + 1 })),
    playlistRevision
  );
  ui.closeModal();
  if (!result?.ok) ui.showToast('Playlist changed or order could not be saved. Reloading.', 'error');
  await window.loadView('playlist', playlistId, { pushHistory: false });
}

export function openPlaylistCoverUpload(playlistId) {
  document.getElementById(`playlist-cover-input-${playlistId}`)?.click();
}

// After a cover change, replace the cached thumbnail URL in
// state.userPlaylists and state.recentPlaylists with a freshly-versioned
// one so every subsequent render (sidebar quick-picks, library cards,
// home tiles) fetches the new image. The backend now returns a
// mtime-versioned URL via _playlist_cover_url, but its 1-second
// resolution means rapid updates can collide on the same ?v= value,
// and any intermediate cache (browser, CDN, proxy) can keep serving
// the old bytes for an unchanged URL. A client-side Date.now() bust
// is monotonic per-update and overrides whatever the server sent.
function _bustPlaylistCoverInState(playlistId) {
  const versionedThumb = (thumb) => {
    if (!thumb || thumb.includes('default')) return thumb;
    const base = thumb.split('?')[0];
    return `${base}?v=${Date.now()}`;
  };
  const bump = (list) => list.map((p) => (p.id !== playlistId ? p : { ...p, thumbnail: versionedThumb(p.thumbnail) }));
  state.setUserPlaylists(bump(state.userPlaylists));
  state.setRecentPlaylists(bump(state.recentPlaylists));
  // Push the new URL into the sidebar DOM immediately. loadUserData
  // also re-renders the sidebar, but doing it again here ensures any
  // path (cover upload / pick from track / reset to auto) that may
  // skip loadUserData still updates the sidebar.
  if (window.renderSidebarPlaylists) window.renderSidebarPlaylists();
}

export async function handlePlaylistCoverUpload(playlistId, input) {
  const file = input?.files?.[0];
  if (!file) return;

  const formData = new FormData();
  formData.append('cover_file', file);

  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/cover/upload`, {
      method: 'POST',
      body: formData
    });
    const data = await res.json();
    if (!res.ok) {
      ui.showToast(data.error || 'Failed to update cover', 'error');
      return;
    }
    ui.showToast('Playlist cover updated', 'success');
    if (window.loadUserData) await window.loadUserData();
    _bustPlaylistCoverInState(playlistId);
    if (window.loadView) window.loadView('playlist', playlistId);
  } catch (e) {
    ui.showToast('Failed to update cover', 'error');
  } finally {
    if (input) input.value = '';
  }
}

export function showPlaylistCoverTrackModal(playlistId) {
  const tracks = state.currentViewList || [];
  if (!tracks.length) {
    ui.showToast('Playlist is empty', 'error');
    return;
  }

  const items = tracks
    .map(
      (track) => `
        <button class="modal-playlist-item" onclick="setPlaylistCoverFromTrack(${playlistId}, ${track.id})">
            <div class="modal-playlist-thumb">
                <img src="${track.thumbnail || '/static/img/default_cover.png'}" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'" alt="">
            </div>
            <div class="modal-playlist-info">
                <span class="modal-playlist-name">${ui.escHtml(track.title || 'Unknown')}</span>
                <span class="modal-playlist-count">${ui.escHtml(track.artist || 'Unknown')}</span>
            </div>
            <i data-lucide="check-circle" class="modal-playlist-add-icon"></i>
        </button>`
    )
    .join('');

  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal modal-playlist" onclick="event.stopPropagation()">
            <div class="modal-header">
                <h2><i data-lucide="disc-3"></i> Choose Cover Track</h2>
                <button class="modal-close" onclick="closeModal()"><i data-lucide="x"></i></button>
            </div>
            <div class="modal-list">${items}</div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  lucide.createIcons();
}

export async function setPlaylistCoverFromTrack(playlistId, trackId) {
  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/cover/track`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ track_id: trackId })
    });
    const data = await res.json();
    if (!res.ok) {
      ui.showToast(data.error || 'Failed to update cover', 'error');
      return;
    }
    ui.closeModal();
    ui.showToast('Playlist cover updated', 'success');
    if (window.loadUserData) await window.loadUserData();
    _bustPlaylistCoverInState(playlistId);
    if (window.loadView) window.loadView('playlist', playlistId);
  } catch (e) {
    ui.showToast('Failed to update cover', 'error');
  }
}

export async function resetPlaylistCover(playlistId) {
  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/cover`, { method: 'DELETE' });
    const data = await res.json();
    if (!res.ok) {
      ui.showToast(data.error || 'Failed to reset cover', 'error');
      return;
    }
    ui.showToast('Playlist cover reset', 'success');
    if (window.loadUserData) await window.loadUserData();
    _bustPlaylistCoverInState(playlistId);
    if (window.loadView) window.loadView('playlist', playlistId);
  } catch (e) {
    ui.showToast('Failed to reset cover', 'error');
  }
}

export async function renderPublicPlaylists(container) {
  let publicPlaylists = [];
  let fetchError = false;
  try {
    const res = await fetch(`${state.API}/public/playlists`);
    publicPlaylists = await res.json();
    if (!res.ok) throw new Error(publicPlaylists.error || `HTTP ${res.status}`);
  } catch (e) {
    fetchError = true;
  }

  container.innerHTML = `
        <section class="home-overview public-theme">
            ${ui.homeTabsBar('public')}
            <div class="hero-section">
                <div class="hero-kicker">${ui.t('public.hero_kicker', 'Community & Sharing')}</div>
                <h1 class="hero-greeting">${ui.t('public.title', 'Public Playlists')}</h1>
                <p class="hero-sub" style="color:var(--text-sub); margin:6px 0 0 0;">${ui.t('public.subtitle', 'Browse read-only playlists shared by other users and create your own synced copy.')}</p>
            </div>
        </section>
        <section class="shelf-section home-shelf">
            <div class="shelf-header">
                <h2 class="shelf-title">${ui.t('public.shared_playlists', 'Shared Playlists')}</h2>
            </div>
            ${
              fetchError
                ? `<div class="empty-state glass-panel"><p>${ui.t('library.error', 'Failed to load public playlists.')}</p></div>`
                : publicPlaylists.length > 0
                  ? `<div class="grid-shelf playlist-mobile-list">${publicPlaylists.map(window.createPlaylistCard).join('')}</div>`
                  : `<div class="empty-state glass-panel"><i data-lucide="globe" class="empty-icon"></i><p>${ui.t('public.empty', 'No public playlists shared on this server yet.')}</p></div>`
            }
        </section>`;
  ui.refreshIcons(container);
}

// === PLAY PLAYLIST ===

export function playPlaylistInOrder() {
  if (!state.currentViewList || state.currentViewList.length === 0) {
    ui.showToast('Playlist is empty', 'error');
    return;
  }
  state.setContextQueue([...state.currentViewList]);
  state.setOriginalContextQueue([...state.currentViewList]);
  state.setContextIndex(0);
  player.playTrack(state.contextQueue[0]);
  if (window.renderQueue) window.renderQueue();
}

export function playPlaylistShuffle() {
  if (!state.currentViewList || state.currentViewList.length === 0) {
    ui.showToast('Playlist is empty', 'error');
    return;
  }
  const shuffled = [...state.currentViewList].sort(() => Math.random() - 0.5);
  state.setContextQueue(shuffled);
  state.setOriginalContextQueue([...state.currentViewList]);
  state.setContextIndex(0);
  player.playTrack(shuffled[0]);
  ui.showToast('Playing shuffled playlist 🔀');
  if (window.renderQueue) window.renderQueue();
}

// === ADD TO PLAYLIST FLYOUT ===
//
// Spotify-style floating panel with a search box. Opens as a flyout
// (not a full-screen modal) so the user can search/filter playlists
// inline. Falls back to the modal on mobile where screen space is
// too tight for a floating panel.

let _atpTrackId = null;
let _atpAlreadyIn = new Set();

export async function showAddToPlaylistModal(trackId, anchorEl = null) {
  _atpTrackId = trackId;
  const editablePlaylists = state.userPlaylists.filter((p) => p.is_editable !== false);

  // Fetch which of those playlists already contain this track so we
  // can render a green check (already added) instead of a "+" button
  // on those rows. Network call is local — ~10–50 ms — so we await
  // before rendering to avoid a flicker between the two states.
  _atpAlreadyIn = new Set();
  if (trackId && editablePlaylists.length) {
    try {
      const r = await fetch(`${state.API}/tracks/${trackId}/playlists`, { credentials: 'include' });
      if (r.ok) {
        const d = await r.json();
        _atpAlreadyIn = new Set(d.playlist_ids || []);
      }
    } catch {
      // On failure we just render everything as addable; the backend
      // /add endpoint will still reject duplicates with a clear toast.
    }
  }

  // On mobile, use the full modal (more vertical space, easier touch).
  if (window.innerWidth <= 768 || !anchorEl) {
    _renderAddToPlaylistModal(trackId, editablePlaylists);
    return;
  }

  _renderAddToPlaylistFlyout(trackId, editablePlaylists, anchorEl);
}

function _renderAddToPlaylistModal(trackId, editablePlaylists) {
  const renderItem = (p) => {
    const isMember = _atpAlreadyIn.has(p.id);
    const thumb = p.thumbnail || '/static/img/default_cover.png';
    const trackCount = p.track_count || 0;
    const trailing = isMember
      ? `<i data-lucide="check-circle-2" class="modal-playlist-check-icon" title="Already in this playlist"></i>`
      : `<i data-lucide="plus-circle" class="modal-playlist-add-icon"></i>`;
    // Member rows are NOT clickable — we communicate "already added"
    // visually and avoid the dup-add backend error entirely.
    const clickAttr = isMember ? '' : `onclick="addToPlaylist(${p.id}, ${trackId})"`;
    const stateClass = isMember ? 'modal-playlist-item--member' : '';
    const safeName = ui.escHtml(p.name).replace(/"/g, '&quot;');
    return `
        <div class="modal-playlist-item ${stateClass}" ${clickAttr} data-name="${safeName}">
            <div class="modal-playlist-thumb">
                <img src="${thumb}" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'" alt="">
            </div>
            <div class="modal-playlist-info">
                <span class="modal-playlist-name">${ui.escHtml(p.name)}</span>
                <span class="modal-playlist-count">${trackCount} track${trackCount !== 1 ? 's' : ''}</span>
            </div>
            ${trailing}
        </div>`;
  };

  const playlistItems =
    editablePlaylists.length > 0
      ? editablePlaylists.map(renderItem).join('')
      : '<p class="modal-empty">No editable playlists available. Create one below!</p>';

  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal modal-playlist" onclick="event.stopPropagation()">
            <div class="modal-header">
                <h2><i data-lucide="list-music"></i> Add to Playlist</h2>
                <button class="modal-close" onclick="closeModal()"><i data-lucide="x"></i></button>
            </div>
            <div class="modal-search">
                <i data-lucide="search"></i>
                <input type="text" id="modal-playlist-search" placeholder="Find a playlist" autocomplete="off">
            </div>
            <div class="modal-list" id="modal-playlist-list">${playlistItems}</div>
            <div class="modal-actions">
                <button class="modal-btn-primary" onclick="showCreatePlaylistModal(${trackId})">
                    <i data-lucide="plus"></i> New Playlist
                </button>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  lucide.createIcons();
  _wireModalPlaylistSearch();
}

/** Live search filter for the Add-to-Playlist modal. Mirrors the
 * flyout's _wireAtpSearch but operates on .modal-playlist-item rows
 * inside #modal-playlist-list. */
function _wireModalPlaylistSearch() {
  const input = document.getElementById('modal-playlist-search');
  if (!input) return;
  input.focus();
  input.addEventListener('input', (e) => {
    const q = e.target.value.toLowerCase().trim();
    const items = document.querySelectorAll('#modal-playlist-list .modal-playlist-item');
    let visible = 0;
    items.forEach((el) => {
      const name = el.dataset.name || '';
      const match = !q || name.toLowerCase().includes(q);
      el.style.display = match ? '' : 'none';
      if (match) visible++;
    });
    let empty = document.getElementById('modal-playlist-no-results');
    if (visible === 0 && !empty) {
      empty = document.createElement('p');
      empty.id = 'modal-playlist-no-results';
      empty.className = 'modal-empty';
      empty.textContent = 'No playlists found.';
      document.getElementById('modal-playlist-list')?.appendChild(empty);
    } else if (visible > 0 && empty) {
      empty.remove();
    }
  });
}

function _renderAddToPlaylistFlyout(trackId, editablePlaylists, anchorRect) {
  // Remove any existing flyout.
  document.getElementById('atp-flyout')?.remove();

  const flyout = document.createElement('div');
  flyout.id = 'atp-flyout';
  flyout.className = 'atp-flyout';

  const listHtml =
    editablePlaylists.length > 0
      ? editablePlaylists.map(_renderAtpItem).join('')
      : '<p class="atp-empty">No playlists yet. Create one below.</p>';

  flyout.innerHTML = `
    <div class="atp-header">
      <span class="atp-title">Add to playlist</span>
      <button class="atp-close" onclick="closeAddToPlaylistFlyout()"><i data-lucide="x"></i></button>
    </div>
    <div class="atp-body" id="atp-body">
      <div class="atp-search">
        <i data-lucide="search"></i>
        <input type="text" id="atp-search-input" placeholder="Find a playlist" autocomplete="off">
      </div>
      <div class="atp-list" id="atp-list">${listHtml}</div>
      <button class="atp-new-btn" onclick="showCreatePlaylistInline(${trackId})">
        <i data-lucide="plus"></i> New playlist
      </button>
    </div>`;

  document.body.appendChild(flyout);

  // Position: anchor to the right of the clicked element, fall back
  // to below if there's no room. anchorRect can be a DOMRect or an
  // element with getBoundingClientRect.
  requestAnimationFrame(() => {
    const ar = anchorRect && anchorRect.width !== undefined ? anchorRect : anchorRect.getBoundingClientRect();
    const fr = flyout.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    let left = ar.right + 8;
    let top = ar.top;
    // If no room on the right, open on the left side.
    if (left + fr.width > vw - 8) left = Math.max(8, ar.left - fr.width - 8);
    // Clamp vertically.
    if (top + fr.height > vh - 8) top = Math.max(8, vh - fr.height - 8);
    flyout.style.left = left + 'px';
    flyout.style.top = top + 'px';
    flyout.classList.add('open');
    document.getElementById('atp-search-input')?.focus();
  });

  // Wire up the live search filter.
  _wireAtpSearch();

  // Close on outside click.
  setTimeout(() => {
    document.addEventListener('click', _atpOutsideClick, true);
  }, 0);
  window.addEventListener('resize', closeAddToPlaylistFlyout);
  window.addEventListener('scroll', _atpScrollGuard, true);

  lucide.createIcons();
}

function _renderAtpItem(p) {
  const isMember = _atpAlreadyIn.has(p.id);
  const thumb = p.thumbnail || '/static/img/default_cover.png';
  const trackCount = p.track_count || 0;
  const safeName = ui.escHtml(p.name).replace(/"/g, '&quot;');
  const trailing = isMember
    ? `<i data-lucide="check" class="atp-item-check"></i>`
    : `<i data-lucide="plus" class="atp-item-add"></i>`;
  const clickAttr = isMember ? '' : `onclick="addToPlaylist(${p.id}, ${_atpTrackId})"`;
  const stateClass = isMember ? 'atp-item--member' : '';
  return `
    <div class="atp-item ${stateClass}" ${clickAttr} data-name="${safeName}">
      <div class="atp-item-thumb">
        <img src="${thumb}" loading="lazy" decoding="async" onerror="this.src='/static/img/default_cover.png'" alt="">
      </div>
      <div class="atp-item-info">
        <span class="atp-item-name">${ui.escHtml(p.name)}</span>
        <span class="atp-item-count">Playlist · ${trackCount} song${trackCount !== 1 ? 's' : ''}</span>
      </div>
      ${trailing}
    </div>`;
}

function _atpOutsideClick(e) {
  const flyout = document.getElementById('atp-flyout');
  if (flyout && !flyout.contains(e.target)) closeAddToPlaylistFlyout();
}
// Close on scroll UNLESS the scroll originated from inside the
// flyout itself (e.g. scrolling the playlist list).
function _atpScrollGuard(e) {
  const flyout = document.getElementById('atp-flyout');
  if (flyout && flyout.contains(e.target)) return;
  closeAddToPlaylistFlyout();
}

/** Swaps the flyout body from the playlist list to an inline
 * "Create playlist" name-input form — Spotify does the same: the
 * list fades out and a name field + Create button appears in the
 * same panel. No second modal. */
export function showCreatePlaylistInline(trackIdToAdd = null) {
  const body = document.getElementById('atp-body');
  if (!body) return;

  body.innerHTML = `
    <div class="atp-create-form">
      <input type="text" id="atp-create-input" class="atp-create-input" maxlength="120" placeholder="Playlist name" autocomplete="off">
      <div class="atp-create-actions">
        <button class="atp-create-cancel" onclick="_atpBackToList()">Back</button>
        <button class="atp-create-submit" onclick="createPlaylistInline(${trackIdToAdd ?? 'null'})">
          <i data-lucide="check"></i> Create
        </button>
      </div>
    </div>`;

  lucide.createIcons();
  const input = document.getElementById('atp-create-input');
  if (input) {
    input.focus();
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        createPlaylistInline(trackIdToAdd);
      } else if (e.key === 'Escape') {
        _atpBackToList();
      }
    });
  }
}

/** Creates a playlist from the inline flyout form, then if a trackId
 * was provided, adds the track to the new playlist and closes the
 * flyout with a success toast. */
export async function createPlaylistInline(trackIdToAdd = null) {
  const input = document.getElementById('atp-create-input');
  const name = input?.value?.trim();
  if (!name) {
    ui.showToast('Enter a name', 'error');
    input?.focus();
    return;
  }
  try {
    const res = await fetch(`${state.API}/playlists`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name })
    });
    const pl = await res.json();
    if (res.ok) {
      const playlists = [...state.userPlaylists, pl];
      state.setUserPlaylists(playlists);
      if (window.trackRecentPlaylist) await window.trackRecentPlaylist(pl.id);
      else if (window.renderSidebarPlaylists) window.renderSidebarPlaylists();
      if (trackIdToAdd) {
        await addToPlaylist(pl.id, trackIdToAdd);
        closeAddToPlaylistFlyout();
      } else {
        // No track context — just go back to the list showing the new entry.
        _atpBackToList();
      }
    } else {
      ui.showToast(pl.error || 'Failed to create playlist', 'error');
    }
  } catch (e) {
    ui.showToast('Failed', 'error');
  }
}

/** Restores the flyout body to the playlist list view (used as Back
 * button from the inline create form). Re-renders the list with
 * fresh state.userPlaylists so any just-created playlist shows up. */
function _atpBackToList() {
  const flyout = document.getElementById('atp-flyout');
  if (!flyout) return;
  const editablePlaylists = state.userPlaylists.filter((p) => p.is_editable !== false);
  const listHtml =
    editablePlaylists.length > 0
      ? editablePlaylists.map(_renderAtpItem).join('')
      : '<p class="atp-empty">No playlists yet. Create one below.</p>';
  const body = document.getElementById('atp-body');
  if (!body) return;
  body.innerHTML = `
    <div class="atp-search">
      <i data-lucide="search"></i>
      <input type="text" id="atp-search-input" placeholder="Find a playlist" autocomplete="off">
    </div>
    <div class="atp-list" id="atp-list">${listHtml}</div>
    <button class="atp-new-btn" onclick="showCreatePlaylistInline(${_atpTrackId ?? 'null'})">
      <i data-lucide="plus"></i> New playlist
    </button>`;
  lucide.createIcons();
  document.getElementById('atp-search-input')?.focus();
  // Re-wire search.
  _wireAtpSearch();
}

function _wireAtpSearch() {
  document.getElementById('atp-search-input')?.addEventListener('input', (e) => {
    const q = e.target.value.toLowerCase().trim();
    const items = document.querySelectorAll('#atp-list .atp-item');
    let visible = 0;
    items.forEach((el) => {
      const name = el.dataset.name || '';
      const match = !q || name.toLowerCase().includes(q);
      el.style.display = match ? '' : 'none';
      if (match) visible++;
    });
    const empty = document.getElementById('atp-no-results');
    if (visible === 0 && !empty) {
      const el = document.createElement('p');
      el.id = 'atp-no-results';
      el.className = 'atp-empty';
      el.textContent = 'No playlists found.';
      document.getElementById('atp-list')?.appendChild(el);
    } else if (visible > 0 && empty) {
      empty.remove();
    }
  });
}

export function closeAddToPlaylistFlyout() {
  const flyout = document.getElementById('atp-flyout');
  if (!flyout) return;
  flyout.classList.remove('open');
  setTimeout(() => flyout.remove(), 150);
  document.removeEventListener('click', _atpOutsideClick, true);
  window.removeEventListener('resize', closeAddToPlaylistFlyout);
  window.removeEventListener('scroll', _atpScrollGuard, true);
}

let selectedImportTracks = []; // [{ id, title, artist, thumbnail }]

export function showCreatePlaylistModal(trackIdToAdd = null, existingName = '') {
  if (trackIdToAdd) {
    selectedImportTracks = [];
  }
  const nameVal = existingName || document.getElementById('new-playlist-name')?.value || '';
  const count = selectedImportTracks.length;

  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal modal-create" onclick="event.stopPropagation()" style="max-width: 520px;">
            <div class="modal-header">
                <h2><i data-lucide="folder-plus"></i> ${creatingCollaborative ? 'Collaborative playlist' : ui.t('library.create_playlist_title', 'Create Playlist')}</h2>
                <button class="modal-close" onclick="closeModal()"><i data-lucide="x"></i></button>
            </div>
            <div class="modal-body">
                <label class="modal-label">${ui.t('library.smart_playlist_name', 'Playlist Name')}</label>
                <input type="text" id="new-playlist-name" class="modal-input" maxlength="120" value="${ui.escHtml(nameVal)}" placeholder="${ui.t('library.playlist_name_placeholder', 'My awesome playlist...')}" autofocus>

                ${
                  !trackIdToAdd
                    ? `
                <div style="margin-top: 10px;">
                    <button type="button" class="btn-secondary" style="width:100%; display:flex; align-items:center; justify-content:center; gap:8px; padding:10px 14px;" onclick="openPlaylistSourceModal()">
                        <i data-lucide="list-music"></i>
                        <span>${ui.t('library.import_songs_btn', 'Import songs from playlist/favorites')}</span>
                    </button>
                </div>

                ${
                  count > 0
                    ? `
                <div style="margin-top: 14px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 6px;">
                        <span class="modal-label" style="margin:0;">${ui.t('library.source_selected_summary', 'Selected songs to include:')} (<strong>${count}</strong>)</span>
                        <button type="button" class="btn-secondary" style="padding:2px 8px; font-size:0.75rem;" onclick="clearSelectedImportTracks()">${ui.t('common.delete', 'Clear')}</button>
                    </div>
                    <div class="glass-panel" style="max-height: 160px; overflow-y: auto; padding: 6px; border-radius: 8px; display: flex; flex-direction: column; gap: 4px;">
                        ${selectedImportTracks
                          .map(
                            (t) => `
                            <div style="display:flex; align-items:center; gap:8px; padding:4px 6px; background:rgba(255,255,255,0.03); border-radius:6px; font-size:0.82rem;">
                                <img src="${t.thumbnail || '/static/img/default_cover.png'}" style="width:24px; height:24px; border-radius:4px; object-fit:cover;" onerror="this.src='/static/img/default_cover.png'">
                                <span style="flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">
                                    <strong>${ui.escHtml(t.title || 'Unknown')}</strong>
                                    <span style="color:var(--text-sub);"> · ${ui.escHtml(t.artist || 'Unknown')}</span>
                                </span>
                                <button type="button" class="btn-icon-circle" style="width:20px; height:20px;" onclick="removeSingleImportTrack(${t.id})">
                                    <i data-lucide="x" style="width:12px; height:12px;"></i>
                                </button>
                            </div>`
                          )
                          .join('')}
                    </div>
                </div>`
                    : ''
                }`
                    : ''
                }
            </div>
            <div class="modal-actions" style="margin-top: 20px;">
                <button class="modal-btn-cancel" onclick="closeModal()">${ui.t('common.cancel', 'Cancel')}</button>
                <button class="modal-btn-primary" onclick="createPlaylist(${trackIdToAdd})">
                    <i data-lucide="check"></i> ${ui.t('library.add_button', 'Create')}
                </button>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  lucide.createIcons();
  document.getElementById('new-playlist-name')?.focus();
}

window.clearSelectedImportTracks = function () {
  selectedImportTracks = [];
  showCreatePlaylistModal(null);
};

window.removeSingleImportTrack = function (trackId) {
  selectedImportTracks = selectedImportTracks.filter((t) => Number(t.id) !== Number(trackId));
  showCreatePlaylistModal(null);
};

window.openPlaylistSourceModal = function () {
  const currentName = document.getElementById('new-playlist-name')?.value || '';
  const playlists = state.userPlaylists || [];

  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal modal-create" onclick="event.stopPropagation()" style="max-width: 580px; max-height: 85vh; display:flex; flex-direction:column;">
            <div class="modal-header">
                <h2><i data-lucide="list-music"></i> ${ui.t('library.select_source_title', 'Choose Songs to Import')}</h2>
                <button class="modal-close" onclick="showCreatePlaylistModal(null, '${ui.escHtml(currentName).replace(/'/g, "\\'")}')"><i data-lucide="x"></i></button>
            </div>
            <div class="modal-body" style="overflow-y:auto; flex:1; padding-right:4px;">
                <label class="modal-label">${ui.t('library.create_from_source', 'Source Collection')}</label>
                <select id="modal-import-source-select" class="modal-input" onchange="loadSourceCollectionTracks(this.value)">
                    <option value="">${ui.t('library.source_choose_btn', 'Choose source collection...')}</option>
                    <option value="favorites">${ui.t('library.source_favorites', 'Favorite Songs')} (${state.userFavorites.size})</option>
                    ${playlists.map((p) => `<option value="${p.id}">${ui.escHtml(p.name)} (${p.track_count || 0})</option>`).join('')}
                </select>

                <div id="modal-import-tracks-container" style="display:none; margin-top:12px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
                        <span class="modal-label" style="margin:0;">${ui.t('library.source_select_songs', 'Select songs to include')}</span>
                        <div style="display:flex; gap:8px;">
                            <button type="button" class="btn-secondary" style="padding:2px 8px; font-size:0.75rem;" onclick="toggleAllImportPicker(true)">${ui.t('library.select_all', 'Select All')}</button>
                            <button type="button" class="btn-secondary" style="padding:2px 8px; font-size:0.75rem;" onclick="toggleAllImportPicker(false)">${ui.t('library.deselect_all', 'Deselect All')}</button>
                        </div>
                    </div>
                    <div id="modal-import-tracks-list" class="glass-panel" style="max-height: 280px; overflow-y: auto; padding: 8px; border-radius: 8px; display: flex; flex-direction: column; gap: 6px;">
                    </div>
                </div>
            </div>
            <div class="modal-actions" style="margin-top: 16px;">
                <button class="modal-btn-cancel" onclick="showCreatePlaylistModal(null, '${ui.escHtml(currentName).replace(/'/g, "\\'")}')">${ui.t('common.cancel', 'Cancel')}</button>
                <button class="modal-btn-primary" onclick="confirmImportSelectedTracks('${ui.escHtml(currentName).replace(/'/g, "\\'")}')">
                    <i data-lucide="check"></i> ${ui.t('library.done', 'Done')}
                </button>
            </div>
        </div>
    </div>`;

  document.getElementById('modal-container').innerHTML = html;
  lucide.createIcons();
};

let loadedSourceTracks = [];

window.loadSourceCollectionTracks = async function (sourceVal) {
  const container = document.getElementById('modal-import-tracks-container');
  const list = document.getElementById('modal-import-tracks-list');
  if (!container || !list) return;

  if (!sourceVal) {
    container.style.display = 'none';
    list.innerHTML = '';
    loadedSourceTracks = [];
    return;
  }

  container.style.display = 'block';
  list.innerHTML = `<div style="text-align:center; padding:16px; color:var(--text-sub);">${ui.t('common.loading', 'Loading...')}</div>`;

  loadedSourceTracks = [];
  try {
    if (sourceVal === 'favorites') {
      const res = await fetch(`${state.API}/favorites`);
      if (res.ok) loadedSourceTracks = await res.json();
    } else {
      const res = await fetch(`${state.API}/playlists/${sourceVal}`);
      if (res.ok) {
        const data = await res.json();
        loadedSourceTracks = data.tracks || [];
      }
    }
  } catch (_) {
    loadedSourceTracks = [];
  }

  if (!loadedSourceTracks || loadedSourceTracks.length === 0) {
    list.innerHTML = `<div style="text-align:center; padding:16px; color:var(--text-sub);">${ui.t('library.no_results', 'No songs found in this collection.')}</div>`;
    return;
  }

  const existingIds = new Set(selectedImportTracks.map((t) => Number(t.id)));

  list.innerHTML = loadedSourceTracks
    .map((t) => {
      const id = t.db_id || t.id;
      const isChecked = existingIds.has(Number(id)) || existingIds.size === 0;
      const img = t.thumbnail || '/static/img/default_cover.png';
      return `
      <label style="display:flex; align-items:center; gap:10px; padding:8px; border-radius:6px; cursor:pointer; background:rgba(255,255,255,0.04); font-size:0.88rem; transition:background 0.15s;">
          <input type="checkbox" class="import-track-checkbox" value="${id}" ${isChecked ? 'checked' : ''} style="accent-color:var(--primary); width:16px; height:16px; cursor:pointer;">
          <img src="${img}" style="width:34px; height:34px; border-radius:4px; object-fit:cover;" onerror="this.src='/static/img/default_cover.png'">
          <div style="flex:1; min-width:0;">
              <div style="font-weight:600; color:var(--text-main); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${ui.escHtml(t.title || 'Unknown')}</div>
              <div style="color:var(--text-sub); font-size:0.78rem; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${ui.escHtml(t.artist || 'Unknown')} · ${ui.escHtml(t.album || '')}</div>
          </div>
      </label>`;
    })
    .join('');
};

window.toggleAllImportPicker = function (select) {
  document.querySelectorAll('.import-track-checkbox').forEach((cb) => {
    cb.checked = select;
  });
};

window.confirmImportSelectedTracks = function (playlistName) {
  const checkedBoxes = document.querySelectorAll('.import-track-checkbox:checked');
  const checkedIds = new Set(Array.from(checkedBoxes).map((cb) => Number(cb.value)));

  const selectedFromLoaded = loadedSourceTracks
    .filter((t) => checkedIds.has(Number(t.db_id || t.id)))
    .map((t) => ({
      id: t.db_id || t.id,
      title: t.title,
      artist: t.artist,
      thumbnail: t.thumbnail
    }));

  if (selectedFromLoaded.length > 0) {
    // Merge without duplicates
    const combined = [...selectedImportTracks];
    selectedFromLoaded.forEach((track) => {
      if (!combined.some((item) => Number(item.id) === Number(track.id))) {
        combined.push(track);
      }
    });
    selectedImportTracks = combined;
  }

  showCreatePlaylistModal(null, playlistName);
};

export function showDeletePlaylistModal(playlistId, playlistName) {
  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal" onclick="event.stopPropagation()">
            <h2 style="margin-bottom: 16px;">Delete Playlist</h2>
            <p style="color: var(--text-sub); margin-bottom: 24px;">Are you sure you want to permanently delete <strong style="color: white;">${ui.escHtml(playlistName)}</strong>? This action cannot be undone.</p>
            <div class="modal-actions">
                <button class="modal-btn-cancel" onclick="closeModal()">Cancel</button>
                <button class="modal-btn-danger" onclick="deletePlaylist(${playlistId})">Delete Permanently</button>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
}

export function showEditPlaylistModal(id, currentName) {
  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal" onclick="event.stopPropagation()">
            <h2 style="margin-bottom: 16px;">Rename Playlist</h2>
            <input type="text" id="edit-playlist-name-input" class="modal-input" value="${ui.escHtml(currentName)}" maxlength="120" placeholder="New playlist name" style="margin-bottom: 24px;">
            <div class="modal-actions">
                <button class="modal-btn-cancel" onclick="closeModal()">Cancel</button>
                <button class="modal-btn-primary" onclick="editPlaylistName('${id}', document.getElementById('edit-playlist-name-input').value)">Save</button>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
  setTimeout(() => document.getElementById('edit-playlist-name-input')?.focus(), 100);
}

// === CRUD ACTIONS ===

export async function createPlaylist(trackIdToAdd = null) {
  const collaborative = creatingCollaborative;
  const input = document.getElementById('new-playlist-name');
  const name = input?.value?.trim();
  if (!name) {
    ui.showToast('Enter a name', 'error');
    return;
  }

  // Gather selected track IDs
  let trackIds = [];
  if (trackIdToAdd) {
    trackIds = [trackIdToAdd];
  } else if (selectedImportTracks.length > 0) {
    trackIds = selectedImportTracks.map((t) => Number(t.id)).filter(Boolean);
  }

  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    if (creatingCollaborative) return ui.showToast('Connect to create a collaborative playlist.', 'error');
    const tempId = -Math.floor(Date.now() / 1000);
    const pl = { id: tempId, name, track_count: trackIds.length, is_owner: true, is_editable: true, is_public: false };
    const playlists = [...state.userPlaylists, pl];
    state.setUserPlaylists(playlists);
    await offlineStore.queueAction('create_playlist', { name, track_ids: trackIds });
    await offlineStore.saveLibrarySnapshot('playlists', playlists);
    if (window.renderSidebarPlaylists) window.renderSidebarPlaylists();
    selectedImportTracks = [];
    ui.closeModal();
    ui.showToast('Playlist created (offline)', 'success');
    return;
  }

  try {
    const res = await fetch(`${state.API}/playlists`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        track_ids: trackIds.length ? trackIds : undefined,
        is_collaborative: creatingCollaborative
      })
    });
    const pl = await res.json();
    if (res.ok) {
      const plWithCount = { ...pl, track_count: trackIds.length };
      const playlists = [...state.userPlaylists, plWithCount];
      state.setUserPlaylists(playlists);
      offlineStore.saveLibrarySnapshot('playlists', playlists);
      if (window.trackRecentPlaylist) await window.trackRecentPlaylist(pl.id);
      else if (window.renderSidebarPlaylists) window.renderSidebarPlaylists();
      selectedImportTracks = [];
      ui.closeModal();
      ui.showToast('Playlist created!', 'success');
      if (state.currentViewName === 'library' && window.loadView) window.loadView('library');
      if (collaborative) await showCollaboratorsModal(pl.id);
      creatingCollaborative = false;
    } else {
      ui.showToast(pl.error || 'Failed to create playlist', 'error');
    }
  } catch (e) {
    ui.showToast('Failed', 'error');
  }
}

export async function addToPlaylist(playlistId, trackId) {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    const next = state.userPlaylists.map((p) => {
      if (p.id !== playlistId) return p;
      return { ...p, track_count: (Number(p.track_count) || 0) + 1 };
    });
    state.setUserPlaylists(next);
    await offlineStore.queueAction('add_to_playlist', { playlistId, trackId });
    await offlineStore.saveLibrarySnapshot('playlists', next);
    ui.closeModal();
    closeAddToPlaylistFlyout();
    ui.showToast('Added to playlist (queued for sync)', 'info');
    return;
  }

  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/add`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ track_id: trackId })
    });
    if (res.ok) {
      const payload = await res.json().catch(() => ({}));
      // Update the cached state.userPlaylists track_count for this
      // playlist so a rapid second open of the Add-to-Playlist modal
      // shows the updated count immediately instead of the pre-add
      // value. Backend now returns the authoritative count in the
      // response; falling back to a +1 increment if it's missing
      // (older backend) keeps the UX consistent.
      const next = state.userPlaylists.map((p) => {
        if (p.id !== playlistId) return p;
        const newCount =
          typeof payload.track_count === 'number' ? payload.track_count : (Number(p.track_count) || 0) + 1;
        return { ...p, track_count: newCount };
      });
      state.setUserPlaylists(next);
      offlineStore.saveLibrarySnapshot('playlists', next);
      // Close whichever surface is open: the full modal (mobile) or
      // the floating flyout (desktop right-click). Both can't be open
      // at once, so calling both is safe and idempotent.
      ui.closeModal();
      closeAddToPlaylistFlyout();
      // Spotify-style "Added to {playlist}" toast with Undo.
      const pl = state.userPlaylists.find((p) => p.id === playlistId);
      const plName = pl ? pl.name : 'playlist';
      ui.showToast(`Added to ${plName}`, 'success', {
        label: 'Undo',
        callback: () => _undoAddToPlaylist(playlistId, trackId)
      });
    } else {
      const err = await res.json();
      ui.showToast(err.error || 'Failed', 'error');
    }
  } catch (e) {
    await offlineStore.queueAction('add_to_playlist', { playlistId, trackId });
    ui.showToast('Added to playlist (offline)', 'info');
  }
}

export async function togglePlaylistOfflineDownload(playlistId) {
  const numId = Number(playlistId);
  const btn = document.getElementById(`pl-offline-btn-${playlistId}`);

  if (btn && btn.classList.contains('downloaded')) {
    const shouldRemove = confirm('Remove this playlist and its downloaded songs from offline storage?');
    if (shouldRemove) {
      await offlineStore.deleteOfflinePlaylist(numId, true);
      ui.showToast('Removed offline playlist from device');
      btn.className = 'btn-secondary playlist-offline-btn';
      btn.innerHTML = `<i data-lucide="arrow-down-circle" width="16" height="16"></i><span>Download</span>`;
      ui.refreshIcons(btn);
    }
    return;
  }

  await downloadPlaylistOffline(playlistId);
}

export async function downloadPlaylistOffline(playlistId) {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    ui.showToast('Cannot download while offline', 'error');
    return;
  }

  const numId = Number(playlistId);
  let playlistData = null;
  let tracks = state.currentViewList || [];

  try {
    const res = await fetch(`${state.API}/playlists/${numId}`);
    if (res.ok) {
      playlistData = await res.json();
      if (playlistData && Array.isArray(playlistData.tracks) && playlistData.tracks.length > 0) {
        tracks = playlistData.tracks;
      }
    }
  } catch {
    /* fallback to currentViewList */
  }

  const plName =
    playlistData?.name || document.getElementById(`playlist-title-${playlistId}`)?.textContent?.trim() || 'Playlist';

  const localTracks = tracks
    .map((t) => ({
      ...t,
      db_id: t.track_id || t.id || t.db_id,
      id: t.track_id || t.id || t.db_id,
      is_local: true
    }))
    .filter((t) => t.db_id && t.source !== 'federation' && !t.is_radio);

  if (!localTracks.length) {
    ui.showToast('No downloadable tracks detected in this playlist', 'info');
    return;
  }

  const btn = document.getElementById(`pl-offline-btn-${playlistId}`);
  if (btn) {
    btn.className = 'btn-secondary playlist-offline-btn downloading';
    btn.innerHTML = `<i data-lucide="loader-2" class="spin" width="16" height="16"></i><span>Starting...</span>`;
    ui.refreshIcons(btn);
  }

  // Save playlist snapshot in offline store
  await offlineStore.saveOfflinePlaylist({
    id: numId,
    name: plName,
    thumbnail: playlistData?.thumbnail || '',
    tracks: localTracks,
    track_count: localTracks.length,
    is_owner: playlistData?.is_owner ?? true,
    is_smart: playlistData?.is_smart ?? false
  });

  let downloadedCount = 0;
  const total = localTracks.length;

  for (let i = 0; i < total; i++) {
    const t = localTracks[i];
    const isAlreadyOffline = offlineStore.isTrackAvailableOfflineSync(t.db_id);

    if (isAlreadyOffline) {
      downloadedCount++;
      if (btn) {
        const span = btn.querySelector('span');
        if (span) span.textContent = `Downloading (${downloadedCount}/${total})`;
      }
      continue;
    }

    try {
      if (btn) {
        const span = btn.querySelector('span');
        if (span) span.textContent = `Downloading (${downloadedCount + 1}/${total})`;
      }
      await offlineStore.downloadTrack(t, null, { playlistId: numId, playlistName: plName, isSingle: false });
      downloadedCount++;
    } catch (e) {
      console.warn('[OFFLINE] Interrupted during playlist download:', t.title, e);
      if (!navigator.onLine) {
        ui.showToast(`Download paused: connection lost (${downloadedCount}/${total} saved). Tap to resume.`, 'info');
        if (btn) {
          btn.className = 'btn-secondary playlist-offline-btn partial';
          btn.innerHTML = `<i data-lucide="arrow-down-circle" width="16" height="16"></i><span>Resume (${downloadedCount}/${total})</span>`;
          ui.refreshIcons(btn);
        }
        return;
      }
    }
  }

  if (btn) {
    btn.className = 'btn-secondary playlist-offline-btn downloaded';
    btn.innerHTML = `<i data-lucide="check-circle" width="16" height="16"></i><span>Downloaded</span>`;
    ui.refreshIcons(btn);
  }

  ui.showToast(`Downloaded all ${downloadedCount} tracks from "${plName}"!`, 'success');
  ui.refreshIcons();
}

/** Undo callback for the add-to-playlist action toast. Re-removes the
 * track and shows a confirming toast so the user knows it was undone. */
async function _undoAddToPlaylist(playlistId, trackId) {
  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/remove/${trackId}`, { method: 'DELETE' });
    if (res.ok) {
      const next = state.userPlaylists.map((p) => {
        if (p.id !== playlistId) return p;
        return { ...p, track_count: Math.max(0, (Number(p.track_count) || 1) - 1) };
      });
      state.setUserPlaylists(next);
      ui.showToast('Removed from playlist');
    }
  } catch (e) {
    ui.showToast('Could not undo', 'error');
  }
}

export function showRemoveFromPlaylistModal(playlistId, trackId, trackTitle) {
  const html = `<div class="modal-overlay" onclick="closeModal()">
        <div class="modal" onclick="event.stopPropagation()">
            <h2 style="margin-bottom: 16px;">Remove from Playlist</h2>
            <p style="color: var(--text-sub); margin-bottom: 24px;">Remove <strong style="color: white;">${ui.escHtml(trackTitle || 'this track')}</strong> from the playlist?</p>
            <div class="modal-actions">
                <button class="modal-btn-cancel" onclick="closeModal()">Cancel</button>
                <button class="modal-btn-danger" onclick="removeFromPlaylist(${playlistId}, ${trackId})">Remove</button>
            </div>
        </div>
    </div>`;
  document.getElementById('modal-container').innerHTML = html;
}

export async function removeFromPlaylist(playlistId, trackId) {
  ui.closeModal();
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    const next = state.userPlaylists.map((p) => {
      if (p.id !== playlistId) return p;
      return { ...p, track_count: Math.max(0, (Number(p.track_count) || 1) - 1) };
    });
    state.setUserPlaylists(next);
    await offlineStore.queueAction('remove_from_playlist', { playlistId, trackId });
    await offlineStore.saveLibrarySnapshot('playlists', next);
    if (window.loadView) window.loadView('playlist', playlistId);
    ui.showToast('Removed from playlist (queued for sync)', 'info');
    return;
  }

  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/remove/${trackId}`, { method: 'DELETE' });
    if (res.ok) {
      if (window.loadView) window.loadView('playlist', playlistId);
      ui.showToast('Removed from playlist', 'success');
    }
  } catch (e) {
    await offlineStore.queueAction('remove_from_playlist', { playlistId, trackId });
    ui.showToast('Removed from playlist (offline)', 'info');
  }
}

export async function deletePlaylist(playlistId) {
  ui.closeModal();
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    const playlists = state.userPlaylists.filter((p) => p.id !== playlistId);
    state.setUserPlaylists(playlists);
    await offlineStore.queueAction('delete_playlist', { playlistId });
    await offlineStore.saveLibrarySnapshot('playlists', playlists);
    if (window.renderSidebarPlaylists) window.renderSidebarPlaylists();
    if (window.loadView) window.loadView('home');
    ui.showToast('Playlist deleted (queued for sync)', 'info');
    return;
  }

  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}`, { method: 'DELETE' });
    if (res.ok) {
      await fetch(`${state.API}/recent-activity/playlist/${playlistId}`, { method: 'DELETE' }).catch(() => null);
      const playlists = state.userPlaylists.filter((p) => p.id !== playlistId);
      state.setUserPlaylists(playlists);
      offlineStore.saveLibrarySnapshot('playlists', playlists);
      if (window.renderSidebarPlaylists) window.renderSidebarPlaylists();
      if (window.refreshRecentActivity) window.refreshRecentActivity();
      if (window.loadView) window.loadView('home');
      ui.showToast('Playlist deleted', 'success');
    }
  } catch (e) {
    await offlineStore.queueAction('delete_playlist', { playlistId });
    const playlists = state.userPlaylists.filter((p) => p.id !== playlistId);
    state.setUserPlaylists(playlists);
    if (window.loadView) window.loadView('home');
    ui.showToast('Playlist deleted (offline)', 'info');
  }
}

export async function editPlaylistName(id, newName) {
  ui.closeModal();
  if (!newName || newName.trim() === '') return;

  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    const titleEl = document.getElementById(`playlist-title-${id}`);
    if (titleEl) titleEl.textContent = newName.trim();
    const next = state.userPlaylists.map((p) => (p.id === id ? { ...p, name: newName.trim() } : p));
    state.setUserPlaylists(next);
    await offlineStore.queueAction('edit_playlist', { playlistId: id, name: newName.trim() });
    await offlineStore.saveLibrarySnapshot('playlists', next);
    ui.showToast('Playlist renamed (queued for sync)', 'info');
    return;
  }

  try {
    const res = await fetch(`${state.API}/playlists/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: newName.trim() })
    });

    if (res.ok) {
      const data = await res.json();
      const titleEl = document.getElementById(`playlist-title-${id}`);
      if (titleEl) titleEl.textContent = data.name;

      if (window.loadUserData) window.loadUserData();
      ui.showToast('Playlist renamed', 'success');

      renderPlaylist(document.getElementById('view-container'), id);
    } else {
      const error = await res.json().catch(() => ({}));
      ui.showToast(error.error || 'Failed to rename playlist', 'error');
    }
  } catch (e) {
    await offlineStore.queueAction('edit_playlist', { playlistId: id, name: newName.trim() });
    ui.showToast('Playlist renamed (offline)', 'info');
  }
}

export async function togglePlaylistPublic(playlistId, shouldBePublic) {
  try {
    const res = await fetch(`${state.API}/playlists/${playlistId}/public`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ is_public: Boolean(shouldBePublic) })
    });
    const data = await res.json();
    if (!res.ok) {
      ui.showToast(data.error || 'Failed to update playlist visibility', 'error');
      return;
    }

    if (window.loadUserData) await window.loadUserData();
    if (window.loadView) window.loadView('playlist', playlistId);
    ui.showToast(data.is_public ? 'Playlist is now public' : 'Playlist is now private', 'success');
  } catch (e) {
    ui.showToast('Failed to update playlist visibility', 'error');
  }
}

export async function copyPublicPlaylist(sourcePlaylistId) {
  try {
    const res = await fetch(`${state.API}/playlists/${sourcePlaylistId}/copy`, {
      method: 'POST'
    });
    const data = await res.json();
    if (!res.ok) {
      ui.showToast(data.error || 'Failed to sync playlist copy', 'error');
      return;
    }

    if (window.loadUserData) await window.loadUserData();
    if (window.loadView) window.loadView('playlist', data.id);
    ui.showToast(data.status === 'copied' ? 'Playlist copied to your library' : 'Playlist copy synced', 'success');
  } catch (e) {
    ui.showToast('Failed to sync playlist copy', 'error');
  }
}

// === ADD FROM CURRENT TRACK ===

export function addToPlaylistCurrent() {
  if (!state.currentTrack || !state.currentTrack.db_id) {
    ui.showToast('Cannot add this track (not in library)', 'error');
    return;
  }
  showAddToPlaylistModal(state.currentTrack.db_id);
}

export function showAddToPlaylistFromPlayer() {
  if (!state.currentTrack) {
    ui.showToast('No track playing', 'error');
    return;
  }
  const id = state.currentTrack.db_id || state.currentTrack.id;
  if (!id) {
    ui.showToast('This track cannot be added to playlists', 'error');
    return;
  }
  showAddToPlaylistModal(id);
}
