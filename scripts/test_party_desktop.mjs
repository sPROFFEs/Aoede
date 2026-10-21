// Run: node --experimental-vm-modules scripts/test_party_desktop.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

async function load(relative, globals, dependencies) {
  const context = vm.createContext(globals);
  const module = new vm.SourceTextModule(await readFile(new URL(`../${relative}`, import.meta.url), 'utf8'), { context });
  await module.link((name) => {
    const values = dependencies[name];
    return new vm.SyntheticModule(Object.keys(values), function () {
      for (const [key, value] of Object.entries(values)) this.setExport(key, value);
    }, { context });
  });
  await module.evaluate();
  return module.namespace;
}

for (const platform of ['MacIntel', 'Win32', 'Linux x86_64', 'iPhone']) {
  const audio = { volume: 0.7 };
  const preview = { volume: 1 }, iframeVolumes = [];
  const gains = [];
  class Context {
    state = 'suspended';
    currentTime = 0;
    destination = {};
    createMediaElementSource() { return { connect() {} }; }
    createGain() {
      const node = { connect() {}, gain: { value: 1, setValueAtTime(value) { this.value = value; } } };
      gains.push(node);
      return node;
    }
    async resume() { this.state = 'running'; }
  }
  const engine = await load('Aoede/assets/js/modules/audio_engine.js', {
    navigator: { platform, userAgent: `${platform} AoedeDesktop` },
    window: { AudioContext: Context, __discoveryPreviewAudio: preview }, console
  }, { './state.js': { audio, ytPlayer: { setVolume: value => iframeVolumes.push(value) } } });
  if (platform === 'MacIntel') {
    assert.equal(engine.ensureInitialized(), true); // ReplayGain can initialize before volume restoration.
    assert.equal(audio.volume, 1); // Do not attenuate once in the element and again in the gain node.
    assert.equal(gains.at(-1).gain.value, 0.7);
    await engine.resumeIfSuspended();
  }
  engine.setVolume(0.3);
  assert.equal(engine.getVolume(), 0.3);
  assert.equal(preview.volume, 0.3);
  assert.equal(iframeVolumes.at(-1), 30);
  if (platform === 'MacIntel') {
    assert.equal(audio.volume, 1);
    assert.equal(gains.at(-1).gain.value, 0.3);
    engine.setVolume(0);
    assert.equal(gains.at(-1).gain.value, 0);
    engine.setVolume(0.8);
    assert.equal(gains.length, 3); // One graph; no duplicate media source or double attenuation.
    assert.equal(gains.at(-1).gain.value, 0.8);
  } else {
    assert.equal(audio.volume, 0.3);
    assert.equal(gains.length, 0);
  }
}

// Confirmation uses the existing app modal, including cancel, Escape and focus return.
const modalDocument = new EventTarget();
const returnFocus = { isConnected: true, closest() { return null; }, focus() { modalDocument.activeElement = this; } };
modalDocument.activeElement = returnFocus;
const overlay = new EventTarget();
const modal = new EventTarget();
const cancel = new EventTarget();
const confirm = new EventTarget();
for (const button of [cancel, confirm]) {
  button.focus = () => { modalDocument.activeElement = button; };
  button.tabIndex = 0;
  button.getClientRects = () => [{}];
}
modal.querySelectorAll = () => [cancel, confirm];
const modalContainer = {
  firstElementChild: overlay,
  querySelector: (selector) => selector.includes('cancel') ? cancel : confirm,
  set innerHTML(value) { this.html = value; }
};
modalDocument.getElementById = () => modalContainer;
modalDocument.querySelector = () => modal;
const ui = await load('Aoede/assets/js/modules/ui.js', { document: modalDocument, Event, window: {} }, {
  './state.js': {}, './audio_engine.js': {}
});
for (const outcome of ['confirm', 'cancel', 'escape', 'backdrop']) {
  const pending = ui.confirmDialog({ title: 'Delete <room>', message: 'Name & details' });
  assert.equal(modalDocument.activeElement, cancel);
  assert.match(modalContainer.html, /Delete &lt;room&gt;/);
  if (outcome === 'escape') {
    const event = new Event('keydown', { cancelable: true });
    Object.defineProperty(event, 'key', { value: 'Escape' });
    modal.dispatchEvent(event);
  } else (outcome === 'confirm' ? confirm : outcome === 'cancel' ? cancel : overlay).dispatchEvent(new Event('click'));
  assert.equal(await pending, outcome === 'confirm');
  assert.equal(modalDocument.activeElement, returnFocus);
}

// Download links stay inside the trap; hidden platform panels do not receive focus.
const link = { tabIndex: 0, getClientRects: () => [{}], focus() { modalDocument.activeElement = this; } };
const hiddenLink = { tabIndex: 0, getClientRects: () => [], focus() { throw new Error('Hidden link focused'); } };
modal.querySelectorAll = selector => {
  assert.match(selector, /a\[href\]/);
  return [cancel, confirm, link, hiddenLink];
};
const pendingLinkDialog = ui.confirmDialog({ message: 'Download' });
cancel.focus();
const reverseTab = new Event('keydown', { cancelable: true });
Object.defineProperties(reverseTab, { key: { value: 'Tab' }, shiftKey: { value: true } });
modal.dispatchEvent(reverseTab);
assert.equal(modalDocument.activeElement, link);
const summary = { focus() { modalDocument.activeElement = this; } };
returnFocus.closest = () => ({ querySelector: () => summary });
cancel.dispatchEvent(new Event('click'));
await pendingLinkDialog;
assert.equal(modalDocument.activeElement, summary);

const paths = [];
const queueBodies = [];
const song = { db_id: null, source: 'federated', fed_instance_id: 12, fed_remote_id: 777,
  title: `Song's "name"`, artist: 'Artist', thumbnail: 'cover' };
const addButton = { dataset: { trackIndex: '0' }, addEventListener(_event, callback) { this.click = callback; } };
const searchResults = { isConnected: true, querySelectorAll: () => [addButton] };
const deletions = [];
let confirmed = false;
let prompts = 0;
let navigations = 0;
let source;
const room = { id: 2, owner_id: 1, name: 'Party', current_item_id: 9, current_index: 0,
  allow_guests_queue: true, participants: [], playback_status: 'paused', revision: 1,
  current_track: { id: 1, db_id: 1, title: 'Remote song', artist: 'Artist' }, queue: [] };
const container = { innerHTML: '', classList: { toggle() {} } };
const state = { audio: { currentTime: 0, pause() {}, removeAttribute() {}, load() {} }, userQueue: [], contextQueue: [], originalContextQueue: [],
  currentViewName: 'party_room', currentViewParam: 'fed_7_2' };
for (const name of ['setUserQueue', 'setContextQueue', 'setOriginalContextQueue', 'setContextIndex', 'setShuffleMode', 'setRepeatMode', 'setCurrentTrack']) state[name] = () => {};
class EventSource {
  constructor(path) { paths.push(path); source = this; this.listeners = {}; }
  addEventListener(event, handler) { this.listeners[event] = handler; }
  close() {}
}
let synced;
let localPaused = true;
let resumes = 0;
const party = await load('Aoede/assets/js/modules/party.js', {
  fetch: async (path, options) => {
    paths.push(path);
    if (options.method === 'DELETE') deletions.push(path);
    if (path.endsWith('/queue')) queueBodies.push(JSON.parse(options.body));
    return { ok: true, json: async () => path.includes('/tracks?') ? { tracks: [song] } : ({ room }) };
  },
  document: { getElementById: id => id === 'party-search-results' ? searchResults : container, querySelector: () => null },
  window: { loadView: async () => { navigations++; }, confirm() { throw new Error('Native dialog must not be used'); } }, EventSource, clearTimeout, setTimeout, console, AbortController, atob
}, {
  './state.js': state,
  './ui.js': { escHtml: ui.escHtml, t: (_key, fallback) => fallback, refreshIcons() {}, initSettingsTabs() {}, showToast() {}, homeTabsBar: () => '', confirmDialog: async () => { prompts++; return confirmed; } },
  './player.js': { syncPartyPlayback: async (value) => { synced = value; return !localPaused; }, syncPlayerShellVisibility() {}, resumeLocalPlayback() { localPaused = false; resumes++; } }
});
assert.match(party.renderHomeShelf([{ ...room, is_remote_federated: true, remote_instance_id: 7 }]), /'fed_7_2'/);
await party.renderPartyRoom(container, 'fed_7_2');
assert.deepEqual(paths, ['/api/party/remote/7/rooms/2/join', '/api/party/remote/7/rooms/2/events']);
source.listeners.state({ data: JSON.stringify({ type: 'state', room }) });
await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(synced.current_track.stream_url, '/api/party/remote/7/rooms/2/stream/9');
assert.equal(synced.current_track.db_id, null);
assert.equal(synced.is_owner, false);
await party.control('ready', 9);
assert.equal(paths.at(-1), '/api/party/remote/7/rooms/2/control');
assert.equal(resumes, 0); // Automatic synchronization cannot undo a headphone/local pause.
localPaused = false;
room.playback_status = 'playing';
room.revision++;
source.listeners.state({ data: JSON.stringify({ type: 'state', room }) });
await new Promise(resolve => setTimeout(resolve, 0));
assert.doesNotMatch(container.innerHTML, /resumePartyAudio/);
party.controller.onAutoplayBlocked();
assert.match(container.innerHTML, /resumePartyAudio/); // Scheduled autoplay failures need an action inside the room.
party.searchTracks("Song's name");
await new Promise(resolve => setTimeout(resolve, 300));
assert.equal(searchResults.innerHTML.includes('onclick='), false);
assert.match(searchResults.innerHTML, /&quot;/);
await addButton.click();
assert.deepEqual(queueBodies.at(-1), { fed_instance_id: 12, fed_remote_id: 777 }); // Room search IDs belong to the host.
await party.addLibraryTrack(btoa(encodeURIComponent(JSON.stringify({ id: 777, is_local: true }))));
assert.deepEqual(queueBodies.at(-1), { origin_url: 'library', track_id: 777 }); // Library IDs need explicit origin translation.
await party.deleteRoom();
assert.equal(prompts, 0); // A remote guest cannot delete the host's room.
room.is_owner = true;
room.playback_status = 'paused';
await party.renderPartyRoom(container, 2);
localPaused = true;
const roomPlay = party.control('play');
assert.equal(localPaused, false); // Room Play unlocks this device synchronously, before the network/SSE round trip.
assert.equal(resumes, 1);
await roomPlay;
await party.deleteRoom();
assert.equal(prompts, 1);
assert.equal(deletions.length, 0);
confirmed = true;
await party.deleteRoom();
assert.deepEqual(deletions, ['/api/party/rooms/2']);
assert.equal(navigations, 1);

let destination;
const views = await load('Aoede/assets/js/modules/views.js', {
  document: { body: { addEventListener() {} } },
  window: { location: { assign: (value) => { destination = value; } } }, console, URL, AbortController, setTimeout, clearTimeout,
  fetch: async () => ({ redirected: true, url: 'https://aoede.example/login' })
}, Object.fromEntries(['state', 'ui', 'api', 'player', 'audio_engine', 'search', 'radio', 'favorites', 'playlists', 'downloads', 'sync', 'library', 'party', 'offline_store', 'system_monitor', 'admin_downloads', 'appearance'].map(name => [
  `./${name}.js`, name === 'system_monitor' ? { initSystemMonitor() {} } : name === 'admin_downloads' ? { initDownloadManager() {} } : name === 'appearance' ? { syncThemeControls() {} } : {}
])));
await views.loadView('settings_admin');
assert.equal(destination, '/admin/');
await views.renderExternalView({}, '/user/settings');
assert.equal(destination, 'https://aoede.example/login');
console.log('PASS: app confirmation/cancellation, owner deletion, remote isolation, admin navigation, party streams and platform volume.');

const notices = [];
const fields = {
  'fed-add-name': { value: 'Peer' }, 'fed-add-url': { value: 'https://peer.example' }, 'fed-add-token': { value: 'test' },
  'fed-issue-name': { value: '' }, 'fed-issue-url': { value: 'https://peer.example' }
};
const admin = await load('Aoede/assets/js/modules/admin.js', {
  document: { getElementById: (id) => fields[id] }, window: {},
  alert() { throw new Error('A native JavaScript dialog must not be required'); },
  fetch: async () => ({ ok: false, status: 400, json: async () => ({ detail: 'Server rejected the request' }) })
}, { './state.js': {}, './ui.js': { showToast: (message, type) => notices.push({ message, type }) } });
await admin.federationAddInstance({ preventDefault() {} });
await admin.federationIssueToken({ preventDefault() {} });
fields['fed-issue-name'].value = 'Peer';
await admin.federationIssueToken({ preventDefault() {} });
assert.deepEqual(notices.map(n => n.type), ['error', 'error', 'error']);
assert.match(notices[0].message, /Failed to add peer/);
assert.equal(notices[1].message, 'Peer name is required.');
assert.match(notices[2].message, /Failed to issue token/);
console.log('PASS: federation errors use in-app notices without native JavaScript dialogs.');
