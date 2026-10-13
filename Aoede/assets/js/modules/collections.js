/** Collection discovery and import preview; playback/editing reuse playlists. */
import * as state from './state.js';
import * as ui from './ui.js';

export async function readResponse(response) {
  const fallback =
    response.status === 401 || (response.redirected && /\/login(?:[/?#]|$)/.test(response.url))
      ? 'Your session expired. Reload Aoede to log in.'
      : response.status === 404
        ? 'Collection API unavailable. Update the Aoede server and reload.'
        : [502, 503, 504, 524].includes(response.status)
          ? `Collection request failed (HTTP ${response.status}). The server or proxy could not complete it. Please try again.`
          : `The server returned an unexpected collection response (HTTP ${response.status}). Reload Aoede and check the server or proxy.`;
  let data;
  try {
    data = await response.json();
  } catch (_) {
    throw new Error(fallback);
  }
  if (!response.ok) throw new Error(data?.error || (typeof data?.detail === 'string' && data.detail) || fallback);
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error(fallback);
  return data;
}

export function isCollectionUrl(value) {
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return false;
    return (
      (url.hostname === 'open.spotify.com' && /\/(album|playlist)\//.test(url.pathname)) ||
      (['youtube.com', 'www.youtube.com', 'music.youtube.com', 'm.youtube.com', 'youtu.be'].includes(url.hostname) &&
        (url.searchParams.has('list') || /^\/browse\/MPRE/.test(url.pathname)))
    );
  } catch (_) {
    return false;
  }
}

export function resultsHtml(data) {
  const warnings = (data.warnings || [])
    .map((message) => `<p role="status" class="modal-subtitle">${ui.escHtml(message)}</p>`)
    .join('');
  return `${warnings}<div class="library-cards-grid">${(data.items || [])
    .map(
      (item, index) => `
    <button class="library-card" data-collection-index="${index}" type="button">
      <div class="library-card-cover"><img src="${ui.escHtml(item.thumbnail || '/static/img/default_cover.png')}" loading="lazy" alt="" onerror="this.src='/static/img/default_cover.png'"></div>
      <div class="library-card-name">${ui.escHtml(item.name)}</div>
      <div class="library-card-sub">${item.kind === 'album' ? 'Album' : 'Playlist'} · ${ui.escHtml(item.source)} · ${ui.escHtml(item.artist || '')}</div>
      <span class="library-card-sub">Preview and import</span>
    </button>`
    )
    .join('')}</div>${data.items?.length ? '' : '<div class="empty-state"><p>No albums or playlists found.</p></div>'}`;
}

export function bindResults(container, items) {
  container.querySelectorAll('[data-collection-index]').forEach((button) => {
    button.addEventListener('click', () => previewImport(items[Number(button.dataset.collectionIndex)].url));
  });
}

export async function previewImport(url) {
  if (!navigator.onLine) return ui.showToast('Connect to import an album or playlist.', 'error');
  const container = document.getElementById('modal-container');
  if (!container) return;
  container.innerHTML = `<div class="modal-overlay" onclick="if(event.target===this) closeModal()"><div class="modal" role="dialog" aria-modal="true" aria-label="Collection preview"><p role="status">Loading collection…</p><button class="btn-secondary" onclick="closeModal()">Cancel</button></div></div>`;
  const overlay = container.firstElementChild;
  ui.focusModal();
  try {
    const response = await fetch(`${state.API}/collections/resolve?url=${encodeURIComponent(url)}`);
    const data = await readResponse(response);
    if (container.firstElementChild !== overlay) return;
    container.innerHTML = `<div class="modal-overlay" onclick="if(event.target===this) closeModal()">
      <div class="modal modal-create" role="dialog" aria-modal="true" aria-labelledby="collection-title">
        <div class="modal-header"><h2 id="collection-title">Import collection</h2><button class="modal-close" onclick="closeModal()" aria-label="Close"><i data-lucide="x"></i></button></div>
        <div class="modal-body">
          <p class="modal-subtitle">${data.track_count} songs from ${ui.escHtml(data.source)}. Saved under ${ui.escHtml(window.USER_DATA?.username || 'your account')} in source order.</p>
          <p class="modal-subtitle">Matching songs already in the pool are reused. Only missing songs are downloaded; repeated entries in the source keep their positions.</p>
          ${data.skipped ? `<p role="status" class="modal-subtitle">${data.skipped} unavailable entries will be skipped.</p>` : ''}
          <label class="modal-label" for="collection-name">Name</label><input id="collection-name" class="modal-input" maxlength="120" value="${ui.escHtml(data.name || 'Imported collection')}">
          <label class="modal-label" for="collection-kind">Save as</label><select id="collection-kind" class="modal-input"><option value="playlist"${data.kind === 'playlist' ? ' selected' : ''}>Playlist</option><option value="album"${data.kind === 'album' ? ' selected' : ''}>Album</option></select>
          <ol class="collection-preview-tracks">${data.tracks.map((track) => `<li value="${Number(track.position)}">${ui.escHtml(track.title)} — ${ui.escHtml(track.artist)}</li>`).join('')}</ol>
        </div>
        <div class="modal-actions"><button class="modal-btn-cancel" onclick="closeModal()">Cancel</button><button class="modal-btn-primary" id="collection-import">Import ${data.track_count} songs</button></div>
      </div></div>`;
    const button = document.getElementById('collection-import');
    const importOverlay = container.firstElementChild;
    button.addEventListener('click', async () => {
      if (button.disabled) return;
      const name = document.getElementById('collection-name').value.trim();
      const kind = document.getElementById('collection-kind').value;
      if (!name) return ui.showToast('Enter a collection name.', 'error');
      button.disabled = true;
      button.textContent = 'Queuing songs…';
      try {
        const result = await fetch(`${state.API}/download/collection`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: data.url, name, kind })
        });
        const queued = await readResponse(result);
        ui.showToast(queued.message, 'success');
        window.ensureDownloadPolling?.();
        if (container.firstElementChild === importOverlay) {
          ui.closeModal();
          await window.loadView('playlist', queued.playlist_id);
        }
      } catch (error) {
        ui.showToast(error.message, 'error');
        button.disabled = false;
        button.textContent = `Import ${data.track_count} songs`;
      }
    });
    ui.refreshIcons(container);
    ui.focusModal();
  } catch (error) {
    if (container.firstElementChild !== overlay) return;
    ui.closeModal();
    ui.showToast(error.message || 'Could not load collection.', 'error');
  }
}
