// Run: node --experimental-vm-modules scripts/test_collections.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

async function load(file, globals, dependencies) {
  const context = vm.createContext(globals);
  const imports = {};
  const module = new vm.SourceTextModule(await readFile(new URL(`../Aoede/assets/js/modules/${file}`, import.meta.url), 'utf8'), { context });
  await module.link(name => {
    const values = dependencies[name];
    imports[name] = new vm.SyntheticModule(Object.keys(values), function () {
      for (const [key, value] of Object.entries(values)) this.setExport(key, value);
    }, { context });
    return imports[name];
  });
  await module.evaluate();
  return { api: module.namespace, imports };
}
const ui = (await load('ui.js', { document: { addEventListener() {} }, window: {} }, { './state.js': {}, './audio_engine.js': {} })).api;
const decodeHtml = value => value.replaceAll('&quot;', '"').replaceAll('&gt;', '>').replaceAll('&lt;', '<').replaceAll('&amp;', '&');
for (const value of ["Song's name", 'slash\\\' and "quotes"', '<img onerror="bad">\n\r\u2028', 0]) {
  const encoded = ui.escJs(value);
  assert.equal(vm.runInNewContext(`'${decodeHtml(encoded)}'`), String(value));
  assert.equal(encoded.includes('"'), false);
  assert.equal(encoded.includes('<'), false);
}

const cards = [];
const results = { innerHTML: '', classList: { add() {}, remove() {} }, querySelector: () => null, querySelectorAll: () => cards };
const query = { value: 'Album' };
const toggle = { checked: false };
const document = { getElementById: id => id === 'search-results' ? results : id === 'collection-search-toggle' ? toggle : query, querySelectorAll: () => [] };
const collections = (await load('collections.js', { URL, document, window: {} }, { './state.js': { API: '/api' }, './ui.js': ui })).api;
assert.equal(collections.isCollectionUrl('https://music.youtube.com/playlist?list=PL1234567890'), true);
assert.equal(collections.isCollectionUrl('https://open.spotify.com/album/' + 'a'.repeat(22)), true);
assert.equal(collections.isCollectionUrl('https://youtube.com.evil.test/playlist?list=PL1234567890'), false);
assert.equal(collections.isCollectionUrl('https://evil.test/?url=https://open.spotify.com/album/id'), false);
const html = collections.resultsHtml({ items: [{ name: '<Unsafe>', kind: 'album', source: 'youtube', thumbnail: '"bad', artist: "Artist's name" }], warnings: [] });
assert.match(html, /&lt;Unsafe&gt;/);
assert.match(html, /data-collection-index="0"/);
assert.equal(html.includes('onclick='), false);
for (const status of [200, 404, 502, 504, 524]) {
  await assert.rejects(collections.readResponse(new Response('<!DOCTYPE html><title>Proxy error</title>', { status })), error => {
    assert.equal(error.message.includes('Unexpected token'), false);
    assert.match(error.message, status === 404 ? /Update the Aoede server/ : new RegExp(`HTTP ${status}`));
    return true;
  });
}
await assert.rejects(collections.readResponse({ ok: true, status: 200, redirected: true, url: 'https://aoede.test/login', json: async () => { throw new SyntaxError('HTML'); } }), /session expired/);
await assert.rejects(collections.readResponse(Response.json({ error: 'YouTube took too long' }, { status: 504 })), /YouTube took too long/);
await assert.rejects(collections.readResponse(Response.json(null)), /unexpected collection response/);

let source;
const values = { API: '/api', currentSource: 'all', searchAbortController: null, searchDebounce: null,
  setCurrentSource(value) { values.currentSource = value; source.setExport('currentSource', value); },
  setSearchAbortController(value) { source.setExport('searchAbortController', value); },
  setSearchDebounce(value) { source.setExport('searchDebounce', value); }, setCurrentViewList() {} };
const calls = [];
let profiles = 0;
let slow;
const search = await load('search.js', { document, window: {}, console, URL, AbortController, clearTimeout, setTimeout,
  fetch: async url => {
    calls.push(url);
    if (url.includes('q=slow')) return new Promise(resolve => { slow = resolve; });
    if (url.includes('q=html-error')) return new Response('<!DOCTYPE html><title>Challenge</title>');
    return { ok: true, json: async () => url.includes('/collections/') ? { items: [], warnings: [] } : [] };
  }
}, { './state.js': values, './ui.js': { ...ui, refreshIcons() {} }, './api.js': { fetchUsersList: async () => { profiles++; return []; } }, './collections.js': collections });
source = search.imports['./state.js'];
await search.api.executeSearch('Album');
assert.deepEqual(calls, ['/api/search?q=Album&source=all']); // No collection requests by default.
await search.api.setCollectionSearch(true);
assert.equal(calls.at(-1), '/api/collections/search?q=Album&source=all');
assert.equal(profiles, 1); // Collection mode doesn't make user/profile requests either.
await search.api.executeSearch('html-error');
assert.match(results.innerHTML, /unexpected collection response/);
assert.equal(results.innerHTML.includes('Unexpected token'), false);
const stale = search.api.executeSearch('slow');
await Promise.resolve();
await search.api.executeSearch('new');
const fresh = results.innerHTML;
slow({ ok: false, status: 500 });
await stale;
assert.equal(results.innerHTML, fresh); // A stale failure must not overwrite fresh results.
search.api.setSource({ classList: { add() {} } }, 'local');
await new Promise(setImmediate);
assert.equal(search.api.collectionSearchEnabled, false);
assert.equal(toggle.checked, false);
await search.api.setCollectionSearch(true);
assert.equal(values.currentSource, 'all'); // Toggle after a local-only chip uses supported providers.

class Button {
  disabled = false;
  textContent = '';
  addEventListener(_name, callback) { this.callback = callback; }
  click() { return this.callback(); }
}
const importButton = new Button();
const modal = { set innerHTML(value) { this.html = value; this.firstElementChild = {}; }, get innerHTML() { return this.html; } };
const fields = { 'modal-container': modal, 'collection-import': importButton, 'collection-name': { value: "Album's name" }, 'collection-kind': { value: 'album' } };
let queued;
let finishImport;
let navigated;
let polling = 0;
const previewData = { name: '<Album>', url: 'https://www.youtube.com/playlist?list=PL1234567890', kind: 'album', source: 'youtube', track_count: 2, skipped: 1,
  tracks: [{ title: "Song's name", artist: 'Artist', position: 1 }, { title: 'Last', artist: 'Artist', position: 3 }] };
let deferredPreview;
const importNotices = [];
const importer = (await load('collections.js', {
  document: { getElementById: id => fields[id] }, window: { USER_DATA: { username: 'collector' }, ensureDownloadPolling() { polling++; }, loadView: async (view, id) => { navigated = [view, id]; } }, navigator: { onLine: true }, URL,
  fetch: async (url, options) => {
    if (options?.method === 'POST') {
      queued = JSON.parse(options.body);
      return new Promise(resolve => { finishImport = resolve; });
    }
    if (url.includes('cancel')) return new Promise(resolve => { deferredPreview = resolve; });
    if (url.includes('html-error')) return new Response('<!DOCTYPE html><title>Gateway error</title>', { status: 502 });
    return { ok: true, json: async () => previewData };
  }
}, { './state.js': { API: '/api' }, './ui.js': { ...ui, focusModal() {}, refreshIcons() {}, showToast: message => importNotices.push(message), closeModal() { modal.innerHTML = ''; } } })).api;
await importer.previewImport('html-error');
assert.equal(modal.html, '');
assert.match(importNotices.at(-1), /HTTP 502/);
assert.equal(importNotices.at(-1).includes('Unexpected token'), false);
await importer.previewImport(previewData.url);
assert.match(modal.html, /&lt;Album&gt;/);
assert.match(modal.html, /1 unavailable/);
const submitted = importButton.click();
await importButton.click(); // Disabled while queuing; no duplicate request.
assert.deepEqual(queued, { url: previewData.url, name: "Album's name", kind: 'album' });
finishImport({ ok: true, json: async () => ({ playlist_id: 5, message: 'Queued' }) });
await submitted;
assert.deepEqual(navigated, ['playlist', 5]);
assert.equal(polling, 1);
importButton.disabled = false; // The next preview creates a fresh button in the browser.
await importer.previewImport(previewData.url);
const failedImport = importButton.click();
finishImport(new Response('<!DOCTYPE html><title>Gateway timeout</title>', { status: 504 }));
await failedImport;
assert.match(importNotices.at(-1), /HTTP 504/);
assert.equal(importButton.disabled, false); // A failed request must leave the import retryable.
const cancelled = importer.previewImport('cancel');
modal.innerHTML = '';
deferredPreview({ ok: true, json: async () => previewData });
await cancelled;
assert.equal(modal.html, ''); // A cancelled preview cannot reopen a modal after a late response.

const downloadCalls = [];
const intervalClears = [];
let downloadState;
let running = true;
const downloadValues = { API: '/api', jobsRefreshController: null, downloadPolling: null,
  setJobsRefreshController(value) { downloadState.setExport('jobsRefreshController', value); },
  setDownloadPolling(value) { downloadState.setExport('downloadPolling', value); } };
const hidden = new Set(['hidden']);
const modalElement = { classList: { contains: name => hidden.has(name), remove: name => hidden.delete(name), add: name => hidden.add(name) } };
const jobList = {};
const badge = { style: {} };
const downloadInput = { value: 'https://www.youtube.com/watch?v=abcdefghijk' };
const collectionToggle = { checked: false };
const downloadButton = { dataset: { downloadLabel: 'Download' }, textContent: 'Download' };
const urlPreviews = [];
const downloader = await load('downloads.js', { window: {}, document: { getElementById: id => ({ 'downloads-modal': modalElement, 'jobs-list': jobList, 'download-badge': badge, 'dl-modal-url': downloadInput, 'dl-modal-collection': collectionToggle, 'dl-modal-submit': downloadButton })[id] }, AbortController, console,
  setInterval: () => 7, clearInterval: id => intervalClears.push(id),
  fetch: async url => {
    downloadCalls.push(url);
    return { ok: true, json: async () => url.includes('/jobs') ? [{ id: 1, status: running ? 'pending' : 'completed', progress: 0, requested_title: 'Song', target_playlist_id: 5 }] : {} };
  }
}, { './state.js': downloadValues, './ui.js': { ...ui, showToast() {} }, './collections.js': { ...collections, previewImport: async url => urlPreviews.push(url) } });
downloadState = downloader.imports['./state.js'];
await downloader.api.openDownloadsModal(5);
assert.ok(downloadCalls.includes('/api/jobs?playlist_id=5'));
downloader.api.closeDownloadsModal();
assert.deepEqual(intervalClears, []); // Closing the modal must keep active downloads updating.
running = false;
await downloader.api.refreshJobs();
assert.deepEqual(intervalClears, [7]);
collectionToggle.checked = true;
downloader.api.updateDownloadMode();
assert.equal(downloadButton.textContent, 'Preview collection');
const requestsBeforePreview = downloadCalls.length;
await downloader.api.handleModalDownload();
assert.deepEqual(urlPreviews, ['https://www.youtube.com/watch?v=abcdefghijk']); // Explicit collection mode routes through server validation, never the single-song downloader.
assert.equal(downloadCalls.length, requestsBeforePreview);
collectionToggle.checked = false;
downloader.api.updateDownloadMode();
assert.equal(downloadButton.textContent, 'Download');
downloadInput.value = previewData.url;
await downloader.api.handleModalDownload();
assert.equal(urlPreviews.at(-1), previewData.url); // Automatic detection works with the toggle off too.
assert.equal(downloadCalls.length, requestsBeforePreview);
downloadInput.value = 'https://www.youtube.com/watch?v=abcdefghijk';
await downloader.api.handleModalDownload();
assert.ok(downloadCalls.includes('/api/download')); // Ordinary song downloads are preserved.
assert.equal(downloadInput.value, '');
console.log('PASS: safe string handlers, collection searches/previews/URL toggle, HTML errors, imports and background download polling.');

// ReplayGain from an old song cannot overwrite the current song or a disabled toggle.
const gainNodes = [];
const gainReplies = new Map();
const preferences = new Map([['aoede.replaygain.enabled', '1']]);
class AudioContext {
  currentTime = 0;
  destination = {};
  createMediaElementSource() { return { connect() {} }; }
  createGain() {
    const node = { connect() {}, gain: { value: 1, cancelScheduledValues() {},
      linearRampToValueAtTime(value) { this.value = value; } } };
    gainNodes.push(node);
    return node;
  }
}
const engine = (await load('audio_engine.js', {
  navigator: { platform: 'Linux', userAgent: '' }, window: { AudioContext }, console,
  localStorage: { getItem: key => preferences.get(key), setItem: (key, value) => preferences.set(key, value) },
  fetch: url => new Promise(resolve => gainReplies.set(url, resolve))
}, { './state.js': { audio: { volume: 0.7 }, API: '/api' } })).api;
const oldGain = engine.applyReplayGain(1);
const newGain = engine.applyReplayGain(2);
gainReplies.get('/api/track/2/gain')({ ok: true, json: async () => ({ gain_db: 6, peak: 0.2 }) });
await newGain;
const expectedGain = gainNodes[0].gain.value;
assert.ok(expectedGain > 1.7 && expectedGain < 1.9);
gainReplies.get('/api/track/1/gain')({ ok: true, json: async () => ({ gain_db: -20, peak: 1 }) });
await oldGain;
assert.equal(gainNodes[0].gain.value, expectedGain);
const disabledGain = engine.applyReplayGain(3);
engine.setReplayGainEnabled(false);
gainReplies.get('/api/track/3/gain')({ ok: true, json: async () => ({ gain_db: 8, peak: 0.2 }) });
await disabledGain;
assert.equal(gainNodes[0].gain.value, 1);

// Every private playback entry point protects a live party, including previews and view queues.
const notices = [];
const routingGains = [];
const mediaActions = [];
const audio = new EventTarget();
audio.src = 'party-stream';
audio.currentTime = 0;
audio.duration = 100;
let plays = 0;
audio.play = async () => { plays++; };
let playerImports;
const playback = { API: '/api', audio, currentTrack: { title: 'Party' }, currentViewList: [{ db_id: 1 }],
  userQueue: [], contextQueue: [], originalContextQueue: [],
  setCurrentTrack(value) { playback.currentTrack = value; playerImports.setExport('currentTrack', value); },
  setIsPlaying(value) { playback.isPlaying = value; },
  setContextQueue() { throw new Error('Party context must stay unchanged'); } };
const playerNode = { style: {}, classList: { add() {}, remove() {} } };
const player = await load('player.js', {
  window: { setTimeout: () => 7 }, clearTimeout() {}, document: { getElementById: () => playerNode, querySelector: () => null, querySelectorAll: () => [] }, console, URL, URLSearchParams, atob,
  localStorage: { removeItem() {} },
  navigator: { mediaSession: { setActionHandler(action) { mediaActions.push(action); } } },
  lucide: { createIcons() {} }
}, { './state.js': playback, './ui.js': { showToast: message => notices.push(message), updatePlayButton() {} },
  './api.js': {}, './offline_store.js': {},
  './audio_engine.js': { resetFade() {}, applyReplayGain: value => routingGains.push(value), resumeIfSuspended() {} } });
playerImports = player.imports['./state.js'];
player.api.setPartyController({ isActive: () => true });
await player.api.playTrack({ db_id: 2 });
await player.api.playFederatedTrack({ fed_instance_id: 12, fed_remote_id: 777 });
await player.api.playPreview({ url: 'https://youtu.be/abcdefghijk' });
player.api.playFromView(0);
assert.equal(notices.length, 4);
assert.equal(audio.src, 'party-stream');
assert.equal(plays, 0);
const radioPlayer = (await load('radio.js', {}, { './state.js': playback, './ui.js': {}, './api.js': {}, './player.js': player.api })).api;
await radioPlayer.playRadioStream('station', 'Station');
await radioPlayer.playSavedRadio('https%3A%2F%2Fstation.example', 'Station');
assert.equal(plays, 0);
const viewDependencies = Object.fromEntries(['state', 'ui', 'api', 'player', 'search', 'radio', 'favorites', 'playlists', 'downloads', 'sync', 'library', 'party', 'offline_store', 'system_monitor', 'admin_downloads'].map(name => [
  `./${name}.js`, name === 'system_monitor' ? { initSystemMonitor() {} } : name === 'admin_downloads' ? { initDownloadManager() {} } : name === 'player' ? player.api : {}
]));
const guardedViews = (await load('views.js', { document: { body: { addEventListener() {} } }, window: {} }, viewDependencies)).api;
guardedViews.discoveryTogglePreview({}, 'bad');
await guardedViews.startSmartRadio('Artist', 'Song');
assert.equal(notices.length, 8); // Radios, smart radio and discovery also honor the party guard.
assert.match(await readFile(new URL('../Aoede/assets/js/main.js', import.meta.url), 'utf8'), /window\.playPreview = player\.playPreview;/);
player.api.setPartyController(null);
const remoteTrack = { title: 'Remote', fed_instance_id: 12, fed_remote_id: 777 };
await player.api.playTrack(remoteTrack, { autoplay: false });
assert.equal(audio.src, '/api/federation/proxy/12/stream/777');
assert.equal(playback.currentTrack, remoteTrack);
assert.equal(plays, 0);
assert.equal(playback.isPlaying, false);
await player.api.playPreview({ id: 'abcdefghijk', url: 'https://www.youtube.com/watch?v=abcdefghijk' });
assert.equal(audio.src, '/api/playback/preview?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabcdefghijk');
assert.equal(plays, 1);
assert.equal(playback.isPlaying, true);
assert.ok(mediaActions.includes('pause'));
assert.deepEqual(routingGains, [null, null]);
await player.api.playPreview({ id: 'abcdefghijk', source: 'youtube', title: 'Song', artist: 'Artist' });
assert.match(audio.src, /url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabcdefghijk&title=Artist\+Song/);
await player.api.playPreview({ id: 'a'.repeat(22), source: 'spotify', title: 'Song' });
assert.match(audio.src, /url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F/);
await player.api.playTrack({ is_radio: true, title: 'Station', stream_url: '/api/proxy/radio?url=station' });
assert.equal(audio.src, '/api/proxy/radio?url=station');
assert.equal(playback.isPlaying, true);

const collaboratorWindow = { USER_DATA: { username: 'owner' } };
const suggestions = { style: {} };
const collaboratorNotices = [];
const connectivity = { onLine: true };
const collaborators = (await load('playlists.js', {
  window: collaboratorWindow, navigator: connectivity, document: { addEventListener() {}, getElementById: id => id === 'collab-user-suggestions' ? suggestions : modal },
  fetch: async url => url.includes('/collaborators')
    ? { ok: true, json: async () => ({ is_owner: true, members: [{ username: 'member' }] }) }
    : { ok: false, status: 403, json: async () => ({ error: 'No permission' }) }
}, { './state.js': { API: '/api', userPlaylists: [{ id: 5, kind: 'album' }] },
  './ui.js': { ...ui, refreshIcons() {}, focusModal() {}, closeModal() {}, showToast: message => collaboratorNotices.push(message) },
  './api.js': { fetchUsersList: async () => ['owner', 'member', 'new user'].map(username => ({ username })) },
  './player.js': {}, './offline_store.js': { getLibrarySnapshot: async () => null, listOfflinePlaylists: async () => [],
    listOfflineTracks: async () => [{ title: 'Other private song', playlist_ids: [5], playlist_names: { 5: 'Other private album' } }],
    queueAction() { throw new Error('Permission failures must not be queued as offline edits'); } } })).api;
await collaborators.showCollaboratorsModal(5);
assert.deepEqual(collaboratorNotices, []);
collaboratorWindow.filterCollaboratorUsers('member', 5);
assert.equal(suggestions.style.display, 'none');
collaboratorWindow.filterCollaboratorUsers('new', 5);
assert.match(suggestions.innerHTML, /new user/);
assert.equal(suggestions.innerHTML.includes('>member<'), false);
collaborators.showDeletePlaylistModal(5, 'Album');
assert.match(modal.html, /Delete Album/);
collaborators.showRemoveFromPlaylistModal(5, 1, 'Song');
assert.match(modal.html, /Remove from Album/);
await collaborators.removeFromPlaylist(5, 1);
await collaborators.deletePlaylist(5);
assert.deepEqual(collaboratorNotices, ['No permission', 'No permission']);
connectivity.onLine = false;
const unavailable = {};
await collaborators.renderPlaylist(unavailable, 5);
assert.equal(unavailable.innerHTML.includes('Other private album'), false);
assert.match(unavailable.innerHTML, /not available offline/);
console.log('PASS: stale ReplayGain, disabling gain, party playback guards, federation routing, preview URLs and collaborator suggestions.');

const snapshots = new Map([['playlists', { payload: ['legacy private playlist'] }]]);
const idbRequest = result => {
  const request = { result };
  queueMicrotask(() => request.onsuccess());
  return request;
};
const offlineWindow = { USER_DATA: { username: 'first' }, indexedDB: {
  open() {
    return idbRequest({ transaction() { return { objectStore() { return {
      put(record) { snapshots.set(record.snapshot_name, record); return idbRequest(); },
      get(key) { return idbRequest(snapshots.get(key)); }
    }; } }; } });
  }
} };
const offline = (await load('offline_store.js', { window: offlineWindow, console }, {})).api;
assert.equal(await offline.getLibrarySnapshot('playlists'), null); // Legacy data has no verified account owner.
await offline.saveLibrarySnapshot('playlists', ['first album']);
offlineWindow.USER_DATA.username = 'second';
assert.equal(await offline.getLibrarySnapshot('playlists'), null);
await offline.saveLibrarySnapshot('playlists', ['second album']);
assert.deepEqual(await offline.getLibrarySnapshot('playlists'), ['second album']);
offlineWindow.USER_DATA.username = 'first';
assert.deepEqual(await offline.getLibrarySnapshot('playlists'), ['first album']);
offlineWindow.USER_DATA = null;
assert.equal(await offline.getLibrarySnapshot('playlists'), null);
console.log('PASS: private offline library snapshots are isolated between accounts.');

const cachedAudio = new Set();
let savedAlbum;
let shouldFail = true;
const offlineNotices = [];
const offlineButton = { querySelector: () => ({}) };
const albumDownloader = (await load('playlists.js', {
  window: {}, navigator: { onLine: true }, console: { ...console, warn() {} },
  document: { addEventListener() {}, getElementById: () => offlineButton },
  fetch: async () => ({ ok: true, json: async () => ({ id: 5, name: 'Album', kind: 'album', is_editable: true,
    tracks: [{ id: 1, title: 'First' }, { id: 2, title: 'Second' }] }) })
}, { './state.js': { API: '/api' }, './ui.js': { refreshIcons() {}, showToast: (message, type) => offlineNotices.push({ message, type }) },
  './api.js': {}, './player.js': {}, './offline_store.js': {
    saveOfflinePlaylist: async value => { savedAlbum = value; }, isTrackAvailableOfflineSync: id => cachedAudio.has(id),
    downloadTrack: async track => { if (track.id === 1 && shouldFail) throw new Error('Connection failed'); cachedAudio.add(track.id); }
  } })).api;
await albumDownloader.downloadPlaylistOffline(5);
assert.equal(savedAlbum.kind, 'album');
assert.equal(savedAlbum.is_editable, true);
assert.match(offlineButton.className, /partial/);
assert.match(offlineButton.innerHTML, /Resume \(1\/2\)/);
assert.equal(offlineNotices.at(-1).type, 'error');
shouldFail = false;
await albumDownloader.downloadPlaylistOffline(5);
assert.match(offlineButton.className, /downloaded/);
assert.equal(offlineNotices.at(-1).type, 'success');
console.log('PASS: offline albums retain their type and failed downloads stay partial until a successful retry.');
