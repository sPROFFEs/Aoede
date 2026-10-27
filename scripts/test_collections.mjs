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
const translationWindow = {};
const ui = (await load('ui.js', { document: { addEventListener() {} }, window: translationWindow }, { './state.js': {}, './audio_engine.js': {} })).api;
translationWindow.__I18N__ = { count: 'Mostrando {start}–{end} de {total}', greeting: 'Party con {user}' };
assert.equal(ui.t('count', '', { start: 0, end: 0, total: 0 }), 'Mostrando 0–0 de 0');
assert.equal(ui.t('greeting', '', { user: 'Alice "$&" <test>' }), 'Party con Alice "$&" <test>');
assert.equal(ui.t('missing', 'Hello {user}', { user: 'Bob' }), 'Hello Bob');
assert.equal(ui.t('missing', 'Keep {unknown}'), 'Keep {unknown}');
assert.equal(ui.t('greeting', '', Object.create({ user: 'inherited' })), 'Party con {user}');
delete translationWindow.__I18N__;
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
let selectedProvider;
search.api.setSource({ classList: { add() {} }, setAttribute(name, value) { if (name === 'aria-pressed') selectedProvider = value; } }, 'local');
await new Promise(setImmediate);
assert.equal(search.api.collectionSearchEnabled, false);
assert.equal(toggle.checked, false);
assert.equal(selectedProvider, 'true');
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
const shellClasses = new Set();
const playerNode = { style: {}, classList: { add() {}, remove() {} } };
const player = await load('player.js', {
  window: { setTimeout: () => 7 }, clearTimeout() {}, document: { body: { classList: { toggle(name, value) { value ? shellClasses.add(name) : shellClasses.delete(name); } } }, getElementById: () => playerNode, querySelector: () => null, querySelectorAll: () => [] }, console, URL, URLSearchParams, atob,
  localStorage: { removeItem() {} },
  navigator: { mediaSession: { setActionHandler(action) { mediaActions.push(action); } } },
  lucide: { createIcons() {} }
}, { './state.js': playback, './ui.js': { showToast: message => notices.push(message), updatePlayButton() {} },
  './api.js': {}, './offline_store.js': {},
  './audio_engine.js': { resetFade() {}, applyReplayGain: value => routingGains.push(value), resumeIfSuspended() {} } });
playerImports = player.imports['./state.js'];
player.api.syncPlayerShellVisibility({ title: 'Track' });
assert.equal(shellClasses.has('has-player'), true);
player.api.syncPlayerShellVisibility(null);
assert.equal(shellClasses.has('has-player'), false);
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
const viewDependencies = Object.fromEntries(['state', 'ui', 'api', 'player', 'audio_engine', 'search', 'radio', 'favorites', 'playlists', 'downloads', 'sync', 'library', 'party', 'offline_store', 'system_monitor', 'admin_downloads', 'appearance'].map(name => [
  `./${name}.js`, name === 'system_monitor' ? { initSystemMonitor() {} } : name === 'admin_downloads' ? { initDownloadManager() {} } : name === 'appearance' ? { syncThemeControls() {} } : name === 'player' ? player.api : {}
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
await player.api.playTrack({ ...remoteTrack, stream_url: '/api/federation/proxy/12/collections/8/stream/23' }, { autoplay: false });
assert.equal(audio.src, '/api/federation/proxy/12/collections/8/stream/23');
await player.api.playPreview({ id: 'abcdefghijk', source: 'youtube', title: 'Song', artist: 'Artist' });
assert.match(audio.src, /url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabcdefghijk&title=Artist\+Song/);
await player.api.playPreview({ id: 'a'.repeat(22), source: 'spotify', title: 'Song' });
assert.match(audio.src, /url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F/);
await player.api.playTrack({ is_radio: true, title: 'Station', stream_url: '/api/proxy/radio?url=station' });
assert.equal(audio.src, '/api/proxy/radio?url=station');
assert.equal(playback.isPlaying, true);


const socialApi = (await load('api.js', {}, { './state.js': { API: '/api' }, './offline_store.js': {}, './sync.js': {} })).api;
const playlistPath = socialApi.playlistPath;
assert.equal(playlistPath(5, '/add'), '/api/playlists/5/add');
assert.equal(playlistPath('fed_2_3', '/reorder'), '/api/federation/proxy/2/collections/3/reorder');

modal.dataset = {};
const collaboratorWindow = { USER_DATA: { username: 'owner' } };
const suggestions = { style: {} };
const collaboratorNotices = [];
let deleteConfirmation, allowDeletion = false;
const connectivity = { onLine: true };
const collaborators = (await load('playlists.js', {
  window: collaboratorWindow, navigator: connectivity, document: { addEventListener() {}, getElementById: id => id === 'collab-user-suggestions' ? suggestions : modal },
  fetch: async url => url.includes('/collaborators')
    ? { ok: true, json: async () => ({ is_owner: true, members: [{ username: 'member' }] }) }
    : { ok: false, status: 403, json: async () => ({ error: 'No permission' }) }
}, { './state.js': { API: '/api', userPlaylists: [{ id: 5, kind: 'album' }] },
  './ui.js': { ...ui, refreshIcons() {}, focusModal() {}, closeModal() {}, confirmDialog: async options => { deleteConfirmation = options; return allowDeletion; }, showToast: message => collaboratorNotices.push(message) },
  './api.js': { playlistPath, fetchUsersList: async () => ['owner', 'member', 'new user'].map(username => ({ username })) },
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
await collaborators.showDeletePlaylistModal(5, 'Album');
assert.match(deleteConfirmation.title, /Delete Album/);
assert.match(deleteConfirmation.message, /Album/);
assert.deepEqual(collaboratorNotices, []); // Cancelling does not send a delete request.
collaborators.showRemoveFromPlaylistModal(5, 1, 'Song');
assert.match(modal.html, /Remove from Album/);
await collaborators.removeFromPlaylist(5, 1);
allowDeletion = true;
await collaborators.showDeletePlaylistModal(5, 'Album');
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
  './api.js': { playlistPath }, './player.js': {}, './offline_store.js': {
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

// Remote collections retain scoped IDs in playback and every inline editing action.
let renderedTracks;
const federationHtml = { querySelector: () => null };
const federatedData = { id: 'fed_2_3', name: 'Together', kind: 'album', is_public: false, is_editable: true, is_owner: false,
  is_federated: true, fed_instance_name: 'Remote', owner_username: 'listener', owner_profile_key: 'fed:2:1', revision: 4,
  tracks: [{ id: 'fedpl_2_3_17', title: 'Remote song', is_local: false, source: 'federation', db_id: null, playlist_item_id: 17,
    fed_instance_id: 2, fed_remote_id: 17, stream_url: '/api/federation/proxy/2/collections/3/stream/17' }] };
const remotePlaylists = (await load('playlists.js', { navigator: { onLine: true }, window: { createPlaylistTrackRow: () => '', innerWidth: 600 },
  document: { addEventListener() {}, getElementById: () => ({ innerHTML: '', dataset: {} }) },
  fetch: async url => { assert.equal(url, playlistPath('fed_2_3')); return Response.json(federatedData); }
}, { './state.js': { API: '/api', currentViewList: [], setCurrentViewList: tracks => { renderedTracks = tracks; } }, './ui.js': { ...ui, refreshIcons() {} },
  './api.js': { playlistPath }, './player.js': {}, './offline_store.js': { saveLibrarySnapshot() {}, isTrackAvailableOfflineSync: () => false } })).api;
await remotePlaylists.renderPlaylist(federationHtml, 'fed_2_3');
assert.equal(renderedTracks[0].is_local, false);
assert.equal(renderedTracks[0].db_id, null);
assert.equal(renderedTracks[0].stream_url, federatedData.tracks[0].stream_url);
assert.match(federationHtml.innerHTML, /leaveCollaborativePlaylist\('fed_2_3'\)/);
assert.match(federationHtml.innerHTML, /loadView\('profile', 'fed:2:1'\)/);
assert.equal(federationHtml.innerHTML.includes('togglePlaylistOfflineDownload'), false);
assert.equal(federationHtml.innerHTML.includes('NaN'), false);
const rowDeps = { ...viewDependencies, './ui.js': { ...ui, fmtTime: () => '1:30' }, './state.js': { userFavorites: new Set() },
  './offline_store.js': { isTrackAvailableOfflineSync: () => false } };
const socialRows = (await load('views.js', { btoa, document: { body: { addEventListener() {} } }, navigator: { onLine: true }, window: {} }, rowDeps)).api;
const row = socialRows.createPlaylistTrackRow(federatedData.tracks[0], 0, 'fed_2_3');
assert.match(row, /showRemoveFromPlaylistModal\('fed_2_3', 'fedpl_2_3_17',/);
assert.match(row, /showAddToPlaylistModal\('fedpl_2_3_17'\)/);
assert.equal(row.includes('toggleFavorite('), false);
console.log('PASS: federated album playback, owner/profile links, scoped editing controls and unavailable offline actions.');

let clickedSong;
const playableRows = (await load('views.js', { atob, document: { body: { addEventListener() {} } }, window: {} }, { ...rowDeps,
  './player.js': { playTrack: track => { clickedSong = track; } }, './downloads.js': { triggerDownload() { throw new Error('Federated cards must play, not download a provider URL'); } } })).api;
playableRows.handleCardClick(btoa(encodeURIComponent(JSON.stringify(federatedData.tracks[0]))));
assert.equal(clickedSong.id, 'fedpl_2_3_17');

// Saved albums are separate from tag groups; all library pages remain reachable.
const libraryPage = { innerHTML: '' };
const libraryQuery = { value: '' };
const librarySort = { value: 'name' };
const librarySnapshots = new Map();
const libraryNetwork = { onLine: true };
const savedAlbums = Array.from({ length: 55 }, (_, index) => ({ id: index + 1, kind: 'album', name: `Saved ${String(index + 1).padStart(2, '0')}`, track_count: index + 1 }));
savedAlbums.push({ id: 100, kind: 'playlist', name: 'Not an album' });
const facetCalls = [];
const songCalls = [];
let libraryTracks;
let deferAlbums = false;
let resolveAlbums;
const library = (await load('library.js', {
  window: { createPlaylistTrackRow: track => `<div data-song="${track.id}">${track.title}</div>` }, navigator: libraryNetwork, console,
  document: { getElementById: id => ({ 'view-container': libraryPage, 'library-facet-query': libraryQuery, 'library-facet-sort': librarySort })[id] }
}, { './ui.js': { ...ui, refreshIcons() {} }, './state.js': { setUserPlaylists() {}, setCurrentViewList: tracks => { libraryTracks = tracks; } },
  './api.js': {
    fetchPlaylists: async () => deferAlbums ? new Promise(resolve => { resolveAlbums = resolve; }) : savedAlbums,
    fetchLibraryFacets: async (kind, options) => { facetCalls.push({ kind, ...options }); return { items: [{ name: 'Single song metadata group', track_count: 1 }], total: 1 }; },
    fetchLibraryTracks: async options => { songCalls.push(options); return { items: [{ id: options.offset + 1, title: 'Pool song' }], total: 600, has_more: options.offset + 50 < 600 }; }
  }, './offline_store.js': { saveLibrarySnapshot: async (key, data) => librarySnapshots.set(key, data), getLibrarySnapshot: async key => librarySnapshots.get(key) }
})).api;
await library.renderLibrary(libraryPage, 'albums');
assert.match(libraryPage.innerHTML, /Your albums/);
assert.match(libraryPage.innerHTML, /Saved 01/);
assert.equal(libraryPage.innerHTML.includes('Saved 51'), false);
assert.equal(libraryPage.innerHTML.includes('Not an album'), false);
assert.equal(facetCalls.length, 0);
await library.loadMoreLibraryFacets();
assert.match(libraryPage.innerHTML, /Showing 51–55 of 55/);
assert.equal(libraryPage.innerHTML.includes('Saved 01'), false);
await library.previousLibraryPage();
libraryQuery.value = '55';
await library.reloadLibraryFacets();
assert.match(libraryPage.innerHTML, /Saved 55/);
assert.equal(libraryPage.innerHTML.includes('Saved 01'), false);
await library.toggleLibraryAlbumMetadata(true);
assert.match(libraryPage.innerHTML, /Single song metadata group/);
assert.equal(libraryPage.innerHTML.includes('Your albums'), false);
assert.deepEqual(facetCalls.at(-1), { kind: 'albums', q: '', sort: 'name', limit: 50, offset: 0 });
await library.toggleLibraryAlbumMetadata(false);
libraryNetwork.onLine = false;
await library.renderLibrary(libraryPage, 'albums');
assert.match(libraryPage.innerHTML, /Saved 01/);
assert.equal(libraryPage.innerHTML.includes('Single song metadata group'), false);
libraryNetwork.onLine = true;
await library.switchLibraryKind('songs');
assert.equal(songCalls.at(-1).sort, 'title');
assert.equal(libraryTracks[0].id, 1);
for (let page = 0; page < 11; page++) await library.loadMoreLibraryFacets();
assert.equal(songCalls.at(-1).offset, 550); // No former 500-row ceiling.
assert.match(libraryPage.innerHTML, /data-song="551"/);
await library.previousLibraryPage();
const fetchedSongs = songCalls.length;
libraryNetwork.onLine = false;
await library.loadMoreLibraryFacets();
assert.equal(songCalls.length, fetchedSongs);
assert.match(libraryPage.innerHTML, /data-song="551"/); // Correct cached page, not another page's tracks.
libraryNetwork.onLine = true;
deferAlbums = true;
const oldAlbums = library.renderLibrary(libraryPage, 'albums');
await library.switchLibraryKind('songs');
const currentLibrary = libraryPage.innerHTML;
resolveAlbums(savedAlbums);
await oldAlbums;
assert.equal(libraryPage.innerHTML, currentLibrary);
console.log('PASS: saved albums, optional metadata browsing, filtered collections and online/offline library pagination beyond 500 songs.');

// The same collection search, sort and pagination work for All and Playlists.
deferAlbums = false;
savedAlbums.push({ id: 'fed:2:8', kind: 'playlist', name: 'Remote collection', owner_username: 'Remote creator', fed_instance_name: 'Other node', track_count: 100 });
await library.renderLibrary(libraryPage, 'all');
assert.match(libraryPage.innerHTML, /Saved 01/);
await library.loadMoreLibraryFacets();
assert.match(libraryPage.innerHTML, /Not an album/);
await library.switchLibraryKind('playlists');
assert.equal(libraryPage.innerHTML.includes('Saved 01'), false);
libraryQuery.value = 'Other node';
await library.reloadLibraryFacets();
assert.match(libraryPage.innerHTML, /Remote collection/);
assert.match(libraryPage.innerHTML, /fed:2:8/);
assert.equal(libraryPage.innerHTML.includes('Not an album'), false);
libraryNetwork.onLine = false;
await library.reloadLibraryFacets();
assert.match(libraryPage.innerHTML, /Remote collection/);
libraryNetwork.onLine = true;
await library.switchLibraryKind('all');
libraryQuery.value = '';
librarySort.value = 'count';
await library.reloadLibraryFacets();
const sortedCollections = [...libraryPage.innerHTML.matchAll(/class="library-card-name"[^>]*>([^<]+)</g)].map(match => match[1]);
assert.equal(sortedCollections[0], 'Remote collection');
assert.equal(sortedCollections[1], 'Saved 55');
console.log('PASS: unified collection kinds, creator/node search, offline filtering and track-count sorting.');

translationWindow.__I18N__ = JSON.parse(await readFile(new URL('../Aoede/concierge/locales/es.json', import.meta.url), 'utf8'));
translationWindow.__I18N__['library.search_saved'] = 'Buscar "música" & álbumes';
await library.renderLibrary(libraryPage, 'all');
assert.match(libraryPage.innerHTML, /placeholder="Buscar &quot;música&quot; &amp; álbumes"/);
assert.match(libraryPage.innerHTML, /Mostrando 1–50 de 58/);
assert.match(libraryPage.innerHTML, />Anterior<\/button>/);
assert.equal(libraryPage.innerHTML.includes('{start}'), false);
delete translationWindow.__I18N__;
console.log('PASS: localized runtime collection copy, parameter substitution and HTML-safe translated placeholders.');

// Saved collections are available before first play, with one fresh row per ID.
const sidebarMarkup = { innerHTML: '', querySelectorAll: () => [] };
const sidebarQuery = { value: '' };
const sidebarKind = { value: 'all' };
const sidebarRows = [{ dataset: { kind: 'playlist' }, textContent: 'Evening' }, { dataset: { kind: 'album' }, textContent: 'Morning' }];
const sidebarViews = (await load('views.js', {
  document: { body: { addEventListener() {} }, getElementById: id => ({ 'sidebar-items': sidebarMarkup, 'sidebar-query': sidebarQuery, 'sidebar-kind': sidebarKind })[id], querySelectorAll: () => sidebarRows }, window: { matchMedia: () => ({ matches: true }) },
}, { ...viewDependencies, './ui.js': { ...ui, refreshIcons() {} }, './state.js': {
  recentPlaylists: [{ id: 1, name: 'Old name' }], userPlaylists: [{ id: 1, name: 'Fresh name' }, { id: 'fed:2:8', name: 'Not played yet', kind: 'album' }], recentMixes: [], recentRadios: [],
} })).api;
sidebarViews.renderSidebarPlaylists();
assert.equal((sidebarMarkup.innerHTML.match(/data-sb-key="playlist-1"/g) || []).length, 1);
assert.match(sidebarMarkup.innerHTML, /Fresh name/);
assert.match(sidebarMarkup.innerHTML, /Not played yet/);
sidebarKind.value = 'album';sidebarViews.filterSidebarLibrary();
assert.equal(sidebarRows[0].hidden, true);assert.equal(sidebarRows[1].hidden, false);
sidebarQuery.value = 'evening';sidebarViews.filterSidebarLibrary();
assert.equal(sidebarRows[1].hidden, true);
sidebarKind.value = 'all';sidebarViews.filterSidebarLibrary();
assert.equal(sidebarRows[0].hidden, false);
let shelfScroll;
sidebarViews.scrollHomeShelf({ closest: () => ({ querySelector: () => ({ clientWidth: 400, scrollBy: value => { shelfScroll = value; } }) }) }, 1);
assert.equal(shelfScroll.left, 320);
assert.equal(shelfScroll.behavior, 'instant');
console.log('PASS: complete sidebar collections, fresh deduplicated metadata and combined type/text filtering.');

// Shared settings tabs survive fragment replacements and support keyboard navigation.
const settingStorage = new Map();
const settingsWindow = { location: { search: '' }, USER_DATA: { username: 'first' }, sessionStorage: { getItem: key => settingStorage.get(key), setItem: (key, value) => settingStorage.set(key, value) } };
const settingsUi = (await load('ui.js', { URLSearchParams, document: { addEventListener() {} }, window: settingsWindow }, { './state.js': {}, './audio_engine.js': {} })).api;
function settingsRoot() {
  const tabs = ['account', 'playback', 'integrations'].map(name => ({ dataset: { settingsTab: name }, attributes: {}, setAttribute(key, value) { this.attributes[key] = value; }, closest() { return this; }, focus() { this.focused = true; } }));
  const panels = ['account', 'account', 'playback', 'integrations'].map((name, index) => ({ id: index === 2 ? 'existing-playback-id' : '', dataset: { settingsPanel: name }, attributes: {}, setAttribute(key, value) { this.attributes[key] = value; } }));
  const events = {};
  const root = { dataset: { settingsTabs: 'test' }, querySelectorAll: selector => selector === '[data-settings-tab]' ? tabs : panels, addEventListener: (name, callback) => { assert.equal(events[name], undefined); events[name] = callback; } };
  settingsUi.initSettingsTabs({ querySelectorAll: () => [root] });
  return { root, tabs, panels, events };
}
let settings = settingsRoot();
assert.deepEqual(settings.panels.map(panel => panel.hidden), [false, false, true, true]);
assert.equal(settings.tabs[2].tabIndex, -1);
settings.events.keydown({ target: settings.tabs[0], key: 'ArrowRight', preventDefault() {} });
assert.equal(settings.tabs[1].focused, true);
assert.equal(settings.tabs[1].attributes['aria-controls'], 'existing-playback-id');
assert.equal(settings.panels[2].hidden, false);
settingsUi.initSettingsTabs({ querySelectorAll: () => [settings.root] }); // No duplicate listeners.
settings = settingsRoot();
assert.equal(settings.tabs[1].attributes['aria-selected'], 'true'); // HTMX/form refresh retains the tab.
settings.events.keydown({ target: settings.tabs[1], key: 'End', preventDefault() {} });
assert.equal(settings.panels[3].hidden, false);
settingsWindow.sessionStorage = { getItem() { throw new Error('Wrapper storage unavailable'); }, setItem() { throw new Error('Wrapper storage unavailable'); } };
settings = settingsRoot();
assert.equal(settings.tabs[2].attributes['aria-selected'], 'true');
settingsWindow.USER_DATA.username = 'second';
settings = settingsRoot();
assert.equal(settings.tabs[0].attributes['aria-selected'], 'true');
console.log('PASS: settings tab grouping, existing panel IDs, keyboard controls, restored selection and wrapper storage fallback.');

const duplicateFilter = { value: 'all' };
const duplicateQuery = { value: '' };
const visibleDuplicates = {};
const duplicateGroups = ['exact', 'possible', 'similar'].map(matchType => ({ dataset: { matchType, search: 'song artist' } }));
const duplicateHandlers = {};
const duplicatePanel = { dataset: {}, isConnected: true, contains: () => true, addEventListener: (name, callback) => { duplicateHandlers[name] = callback; },
  querySelectorAll: () => duplicateGroups, querySelector: selector => selector.includes('="type"') ? duplicateFilter : selector.includes('="query"') ? duplicateQuery : visibleDuplicates };
const scanButton = {};
let comparedTrack;
const admin = (await load('admin.js', { document: { getElementById: id => id === 'duplicate-results' ? duplicatePanel : scanButton }, window: { playTrack: track => { comparedTrack = track; } },
  fetch: async (_url, options) => Response.json(options?.method === 'POST' ? { job_id: 1 } : { status: 'completed', details: { result: {
    count: 2, exact_count: 0, possible_count: 1, similar_count: 1, groups: [{ match_type: 'possible', recommended_track_id: 1, reasons: ['semantic'], tracks: [
      { id: 1, title: '<Song>', artist: 'Artist', filepath: '"unsafe', file_exists: true, format: 'MP3', file_size: 100, playlist_count: 2 },
      { id: 2, title: '<Song>', artist: 'Artist', file_exists: false }
    ] }, { match_type: 'similar', reasons: ['similarity'], similarity: { score: 96, title: 98, artist: 100, duration_delta: 2, different_versions: true }, tracks: [{ id: 3, title: 'Similar <Song>', artist: 'Artist', file_exists: false }, { id: 4, title: 'Similar song', artist: 'Artist', file_exists: false }] }] } } })
}, { './state.js': {}, './ui.js': ui })).api;
await admin.adminFindDuplicates();
assert.match(duplicatePanel.innerHTML, /Possible metadata match/);
assert.match(duplicatePanel.innerHTML, /96% metadata score/);
assert.match(duplicatePanel.innerHTML, /Duration difference: 2s/);
assert.match(duplicatePanel.innerHTML, /Different version tags/);
assert.match(duplicatePanel.innerHTML, /Similar &lt;Song&gt;/);
assert.match(duplicatePanel.innerHTML, /Suggested to keep/);
assert.match(duplicatePanel.innerHTML, /File missing/);
assert.match(duplicatePanel.innerHTML, /&lt;Song&gt;/);
assert.equal((duplicatePanel.innerHTML.match(/data-action="play-admin-track"/g) || []).length, 1);
const comparisonData = decodeHtml(duplicatePanel.innerHTML.match(/data-action="play-admin-track" data-track="([^"]+)"/)[1]);
duplicateHandlers.click({ target: { closest: () => ({ dataset: { track: comparisonData } }) } });
assert.equal(comparedTrack.db_id, 1);
assert.equal(comparedTrack.title, '<Song>');
assert.equal(scanButton.disabled, false);
duplicateFilter.value = 'exact';
duplicateHandlers.change({ target: { matches: () => true } });
assert.deepEqual(duplicateGroups.map(group => group.hidden), [false, true, true]);
duplicateFilter.value = 'similar';
duplicateHandlers.change({ target: { matches: () => true } });
assert.deepEqual(duplicateGroups.map(group => group.hidden), [true, true, false]);
duplicateQuery.value = 'not found';
duplicateHandlers.input({ target: { matches: () => true } });
assert.deepEqual(duplicateGroups.map(group => group.hidden), [true, true, true]);
console.log('PASS: duplicate match filters, file comparison, manual review hints and escaped metadata.');

const lockNotices = [];
let lockData = { username: 'alice', blocked: true, blocked_sources: 2, remaining_seconds: 61 };
const lockButton = { disabled: false, classList: { toggle(_, active) { this.active = active; } } };
const locks = (await load('admin.js', { fetch: async () => Response.json(lockData) }, {
  './state.js': {}, './ui.js': { ...ui, t: (key, fallback, values) => key === 'admin.login_blocked' ? `${values.user}: ${values.sources} IPs, ${values.minutes}m ${values.seconds}s` : key === 'admin.login_unblocked' ? `${values.user}: no active restriction` : fallback,
    showToast: (message, type) => lockNotices.push({ message, type }) }
})).api;
await locks.showLoginLockInfo(1, lockButton);
assert.equal(lockNotices[0].message, 'alice: 2 IPs, 1m 1s');
assert.equal(lockButton.classList.active, true);
assert.equal(lockButton.disabled, false);
lockData = { username: 'alice', blocked: false, blocked_sources: 0, remaining_seconds: 0 };
await locks.showLoginLockInfo(1, lockButton);
assert.equal(lockNotices[1].message, 'alice: no active restriction');
assert.equal(lockButton.classList.active, false);
console.log('PASS: admin sign-in restriction toast reports remaining time and clears expired status.');
