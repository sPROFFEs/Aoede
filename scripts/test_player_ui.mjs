// Run: node --experimental-vm-modules scripts/test_player_ui.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const fields = new Map();
function element(id) {
  const classes = new Set(), attributes = new Map(), events = new Map();
  const el = { id, dataset: {}, style: {}, children: [], queries: {}, events, isConnected: true,
    classList: { add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name), toggle(name, value) { value ? classes.add(name) : classes.delete(name); } },
    appendChild(child) { child.parentElement?.children.splice(child.parentElement.children.indexOf(child), 1); child.parentElement = el; el.children.push(child); },
    insertBefore(child, target) { child.parentElement?.children.splice(child.parentElement.children.indexOf(child), 1); child.parentElement = el; el.children.splice(el.children.indexOf(target), 0, child); },
    setAttribute: (name, value) => attributes.set(name, value), getAttribute: name => attributes.get(name),
    addEventListener: (name, callback) => events.set(name, callback), querySelector: selector => el.queries[selector] || null,
    querySelectorAll: selector => el.queries[selector] || [], contains(child) { return Boolean(child && (child === el || el.children.some(node => node.contains(child)))); },
    closest: () => null, focus() { document.activeElement = el; }, getBoundingClientRect: () => ({ left: 0, top: 0, width: 100 }), scrollTo() {} };
  fields.set(id, el); return el;
}
const body = element('body'), full = element('fullscreen-player'), stage = element('fs-stage'), actions = element('actions'), songActions = element('fs-track-actions');
const favorite = element('fs-btn-favorite'), add = element('fs-btn-add'), lyrics = element('lyrics-panel'), queue = element('queue-panel'), lyricsBody = element('lyrics-body');
const viewButtons = ['cover', 'lyrics', 'queue'].map(value => { const button = element(`view-${value}`); button.dataset.fsView = value; return button; });
element('queue-list'); element('fs-view-label'); const minimize = element('minimize'); element('player-cover');
body.appendChild(full); full.appendChild(stage); full.appendChild(actions); full.appendChild(songActions); body.appendChild(lyrics); body.appendChild(queue);
actions.appendChild(favorite); actions.appendChild(add); viewButtons.forEach(button => actions.appendChild(button));
full.queries['.fs-actions'] = actions; actions.queries['.lyrics-toggle-btn'] = viewButtons[1];
full.queries['[data-fs-view]'] = viewButtons; full.queries['.fs-minimize'] = minimize; full.queries['[data-fs-view="cover"]'] = viewButtons[0];
const volumeBars = [element('volume-bar'), element('fs-volume-bar')], fills = [element('fill1'), element('fill2')], knobs = [element('knob1'), element('knob2')];
const muteButtons = [element('btn-volume-icon'), element('fs-volume-icon')];
const volumePopover = element('volume-popover'); full.queries['.fs-volume'] = volumePopover;
const progress = element('fs-progress-bar'); progress.queries['.progress-knob, .volume-knob, .fs-progress-knob'] = knobs[0];
const document = { body, activeElement: body, documentElement: { dataset: { theme: 'glass' } }, addEventListener() {}, getElementById: id => fields.get(id),
  querySelector: selector => selector === '#lyrics-panel .lyrics-body' ? lyricsBody : null,
  querySelectorAll: selector => selector === '.volume-bar-fill' ? fills : selector === '.volume-knob' ? knobs : selector === '#volume-bar, #fs-volume-bar' ? volumeBars : selector === '#btn-volume-icon, #fs-volume-icon' ? muteButtons : selector === '.lyrics-toggle-btn' ? [viewButtons[1]] : selector === '#progress-bar, #fs-progress-bar' ? [progress] : [] };
const window = { matchMedia: () => ({ matches: false }) };
const values = { isFullscreenPlayerOpen: false, isQueueOpen: false, currentTrack: { db_id: 1, title: 'First', artist: 'Artist', is_local: true }, userFavorites: new Set([1]), repeatMode: 'off', isPlaying: false, shuffleMode: false, contextQueue: [], contextIndex: -1, userQueue: [], audio: { duration: 120, currentTime: 0 }, ytReady: false, ytPlayer: null, API: '/api' };
let stateModule, volume = 0.7, respond = async () => ({ ok: true, json: async () => ({ plain: 'New lyrics' }) });
for (const [setter, key] of [['setIsFullscreenPlayerOpen', 'isFullscreenPlayerOpen'], ['setIsQueueOpen', 'isQueueOpen']]) values[setter] = value => { values[key] = value; stateModule.setExport(key, value); };
const context = vm.createContext({ window, document, console, AbortController, URLSearchParams, setTimeout, clearTimeout, fetch: (...args) => respond(...args) });
function synthetic(values) {
  return new vm.SyntheticModule(Object.keys(values), function () { for (const [key, value] of Object.entries(values)) this.setExport(key, value); }, { context });
}
stateModule = synthetic(values);
const engine = synthetic({ getVolume: () => volume, setVolume: value => { volume = Math.max(0, Math.min(1, value)); } });
const imports = { './state.js': stateModule, './audio_engine.js': engine, './player.js': synthetic({ persistPlaybackSession() {} }) };
async function load(name) {
  const module = new vm.SourceTextModule(await readFile(new URL(`../Aoede/assets/js/modules/${name}.js`, import.meta.url), 'utf8'), { context });
  await module.link(key => imports[key]); await module.evaluate(); imports[`./${name}.js`] = module; return module.namespace;
}
const ui = await load('ui'), queueApi = await load('queue'), lyricsApi = await load('lyrics');
imports['./api.js'] = synthetic({}); imports['./offline_store.js'] = synthetic({});
const favoritesApi = await load('favorites');
window.toggleQueue = queueApi.toggleQueue; window.closeLyricsPanel = lyricsApi.closeLyricsPanel;
ui.initFullscreenPlayer();
volumePopover.open = true;
full.events.get('click')({ composedPath: () => [muteButtons[1], volumePopover, full] });
assert.equal(volumePopover.open, true); // A replaced SVG target still belongs to the popup's event path.
full.events.get('click')({ composedPath: () => [stage, full] });
assert.equal(volumePopover.open, false);
assert.equal(favorite.parentElement, songActions);
ui.toggleFullscreenPlayer();
assert.equal(lyrics.parentElement, stage); assert.equal(queue.parentElement, stage);
assert.equal(full.getAttribute('aria-hidden'), 'false');
assert.equal(document.activeElement, minimize);

// A response whose JSON decoding finishes after a new track must not replace it.
let finishOld;
respond = async () => ({ ok: true, json: () => new Promise(resolve => { finishOld = resolve; }) });
lyricsApi.toggleLyricsPanel(); await new Promise(setImmediate);
assert.equal(full.dataset.view, 'lyrics');
values.currentTrack = { db_id: 2, title: 'Second', artist: 'Other artist' }; stateModule.setExport('currentTrack', values.currentTrack);
respond = async () => ({ ok: true, json: async () => ({ plain: 'Current song lyrics' }) });
lyricsApi.onTrackChange(values.currentTrack); await new Promise(setImmediate);
finishOld({ plain: 'Stale lyrics' }); await new Promise(setImmediate);
assert.match(lyricsBody.innerHTML, /Current song lyrics/); assert.doesNotMatch(lyricsBody.innerHTML, /Stale lyrics/);
queueApi.toggleQueue(); assert.equal(full.dataset.view, 'queue'); assert.equal(lyricsApi.isLyricsPanelOpen(), false);
lyricsApi.toggleLyricsPanel(); assert.equal(full.dataset.view, 'lyrics'); assert.equal(values.isQueueOpen, false);
lyricsApi.closeLyricsPanel();
values.currentTrack = { db_id: 3, title: 'Retry', artist: 'Artist' }; stateModule.setExport('currentTrack', values.currentTrack);
let lyricRequests = 0;
respond = async () => { lyricRequests++; return { ok: false }; };
lyricsApi.toggleLyricsPanel(); await new Promise(setImmediate); lyricsApi.closeLyricsPanel();
respond = async () => { lyricRequests++; return { ok: true, json: async () => ({ plain: 'Retried lyrics' }) }; };
lyricsApi.toggleLyricsPanel(); await new Promise(setImmediate);
assert.equal(lyricRequests, 2); assert.match(lyricsBody.innerHTML, /Retried lyrics/);
ui.showFullscreenCover(); assert.equal(full.dataset.view, 'cover');
ui.toggleFullscreenPlayer(); assert.equal(lyrics.parentElement, body); assert.equal(queue.parentElement, body);
assert.equal(full.getAttribute('aria-hidden'), 'true');
document.documentElement.dataset.theme = 'classic'; ui.syncFullscreenPlayerLayout();
assert.deepEqual(actions.children.slice(0, 3).map(node => node.id), ['view-cover', 'fs-btn-favorite', 'fs-btn-add']);
assert.equal(favorite.parentElement, actions); assert.equal(add.parentElement, actions);
respond = async () => ({ ok: true, json: async () => ({ liked: false }) });
await favoritesApi.toggleFavorite(1, favorite);
assert.equal(favorite.classList.contains('active'), false); assert.equal(favorite.getAttribute('aria-pressed'), 'false');
values.currentTrack = { id: 'fed_3_4', fed_instance_id: 3, fed_remote_id: 4, is_local: false }; stateModule.setExport('currentTrack', values.currentTrack);
ui.updateFullscreenPlayButton(); assert.equal(favorite.disabled, true); assert.equal(add.disabled, false);
values.currentTrack = { id: 'youtube-preview', is_local: false }; stateModule.setExport('currentTrack', values.currentTrack);
ui.updateFullscreenPlayButton(); assert.equal(add.disabled, true);
values.currentTrack = { db_id: 1 }; stateModule.setExport('currentTrack', values.currentTrack);
ui.updateFullscreenPlayButton(); assert.equal(favorite.disabled, false); // Older saved local tracks may omit is_local.

ui.updateVolumeUI(); assert.deepEqual(fills.map(fill => fill.style.width), ['70%', '70%']);
ui.toggleMute(); assert.equal(volume, 0); assert.equal(muteButtons[1].getAttribute('aria-pressed'), 'true');
ui.toggleMute(); assert.equal(volume, 0.7);
ui.nudgeVolume(1); assert.deepEqual(fills.map(fill => fill.style.width), ['100%', '100%']);
const seeks = [];
ui.setupDraggable(progress, (pct, dragging) => seeks.push([pct, dragging]));
ui.updateUIProgress(30, 120); assert.equal(progress.getAttribute('aria-valuenow'), '25');
let stopped = false, prevented = false;
const key = value => progress.events.get('keydown')({ key: value, preventDefault() { prevented = true; }, stopPropagation() { stopped = true; } });
key('ArrowRight'); assert.deepEqual(seeks.at(-1), [0.3, false]); assert.ok(stopped && prevented);
key('End'); assert.deepEqual(seeks.at(-1), [1, false]); key('Home'); assert.deepEqual(seeks.at(-1), [0, false]);
ui.updateUIProgress(10, Infinity); assert.equal(progress.getAttribute('aria-disabled'), 'true');
const count = seeks.length; key('ArrowRight'); assert.equal(seeks.length, count);
progress.events.get('mousedown')({ clientX: 50 }); assert.equal(seeks.length, count);
// Exercise the seek dispatcher without exporting an internal helper in production.
const playerModule = new vm.SourceTextModule((await readFile(new URL('../Aoede/assets/js/modules/player.js', import.meta.url), 'utf8')) + '\nexport { seekToPercentage };', { context });
await playerModule.link(key => imports[key]); await playerModule.evaluate();
const routedSeeks = [];
values.ytPlayer = { seekTo: (...args) => routedSeeks.push(args) };
stateModule.setExport('ytPlayer', values.ytPlayer);
stateModule.setExport('currentTrack', { source: 'youtube', is_local: false });
playerModule.namespace.seekToPercentage(0.5); assert.equal(routedSeeks.length, 0);
stateModule.setExport('ytReady', true);
playerModule.namespace.seekToPercentage(0.5); assert.deepEqual(routedSeeks.at(-1), [15, true]);
let partySeek;
playerModule.namespace.setPartyController({ isActive: () => true, seekTo: value => { partySeek = value; } });
playerModule.namespace.seekToPercentage(0.25); assert.equal(partySeek, 30); assert.equal(routedSeeks.length, 1);
playerModule.namespace.setPartyController(null);stateModule.setExport('currentTrack', { db_id: 1, is_local: true });
values.audio.duration = 120; playerModule.namespace.seekToPercentage(0.25); assert.equal(values.audio.currentTime, 30);
values.audio.duration = Infinity; playerModule.namespace.seekToPercentage(0.5); assert.equal(values.audio.currentTime, 30);
console.log('PASS: shared fullscreen panels, Classic restoration, stale lyrics, synchronized volume and keyboard seeking.');
