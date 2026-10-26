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
    insertBefore(child, target) { if (child === target) return; child.parentElement?.children.splice(child.parentElement.children.indexOf(child), 1); child.parentElement = el; el.children.splice(target ? el.children.indexOf(target) : el.children.length, 0, child); },
    removeAttribute: name => attributes.delete(name), setAttribute: (name, value) => attributes.set(name, value), getAttribute: name => attributes.get(name),
    addEventListener: (name, callback) => events.set(name, callback), querySelector: selector => el.queries[selector] || null,
    querySelectorAll: selector => el.queries[selector] || [], contains(child) { return Boolean(child && (child === el || el.children.some(node => node.contains(child)))); },
    closest: () => null, focus() { document.activeElement = el; }, getBoundingClientRect: () => ({ left: 0, top: 0, width: 100 }), scrollTo() {} };
  Object.defineProperty(el, 'firstChild', { get: () => el.children[0] || null });
  fields.set(id, el); return el;
}
const body = element('body'), full = element('fullscreen-player'), stage = element('fs-stage'), actions = element('actions'), songActions = element('fs-track-actions');
const favorite = element('fs-btn-favorite'), add = element('fs-btn-add'), lyrics = element('lyrics-panel'), queue = element('queue-panel'), lyricsBody = element('lyrics-body');
const viewButtons = ['cover', 'lyrics', 'queue'].map(value => { const button = element(`view-${value}`); button.dataset.fsView = value; return button; });
element('queue-list'); element('fs-view-label'); const minimize = element('minimize'); element('player-cover');
body.appendChild(full); full.appendChild(stage); full.appendChild(actions); full.appendChild(songActions); body.appendChild(lyrics); body.appendChild(queue);
songActions.appendChild(favorite); songActions.appendChild(add); viewButtons.forEach(button => actions.appendChild(button));
full.queries['.fs-actions'] = actions; actions.queries['.lyrics-toggle-btn'] = viewButtons[1];
full.queries['[data-fs-view]'] = viewButtons; full.queries['.fs-minimize'] = minimize; full.queries['[data-fs-view="cover"]'] = viewButtons[0];
const volumeBars = [element('volume-bar'), element('fs-volume-bar')], fills = [element('fill1'), element('fill2')], knobs = [element('knob1'), element('knob2')];
const muteButtons = [element('btn-volume-icon'), element('fs-volume-icon')];
const volumePopover = element('volume-popover'); full.queries['.fs-volume'] = volumePopover;
const detailStage = element('fs-details'), info = element('fs-info'), cover = element('fs-cover'), volumeSlot = element('fs-volume-slot'), volumePanel = element('fs-volume-panel');
const more = element('fs-more'), menu = element('fs-more-menu'), controls = element('fs-controls');
fields.set('fs-actions', actions); fields.set('fs-btn-cover', viewButtons[0]);
const shuffle = element('fs-btn-shuffle'), repeat = element('fs-btn-repeat'), sleep = element('fs-btn-sleep'), middle = element('fs-audio-action');
element('fs-btn-previous'); element('fs-btn-next'); element('fs-time-remaining');
full.appendChild(detailStage); full.appendChild(info); full.appendChild(volumeSlot); full.appendChild(more);
full.queries['.fs-more'] = more; more.appendChild(menu); volumePopover.appendChild(volumePanel); stage.appendChild(cover);
controls.appendChild(shuffle); controls.appendChild(repeat); actions.appendChild(middle); actions.appendChild(sleep);
const progress = element('fs-progress-bar'); progress.queries['.progress-knob, .volume-knob, .fs-progress-knob'] = knobs[0];
const document = { body, activeElement: body, documentElement: { dataset: { theme: 'classic' } }, addEventListener() {}, dispatchEvent() {}, getElementById: id => fields.get(id),
  querySelector: selector => selector === '#lyrics-panel .lyrics-body' ? lyricsBody : null,
  querySelectorAll: selector => selector === '.volume-bar-fill' ? fills : selector === '.volume-knob' ? knobs : selector === '#volume-bar, #fs-volume-bar' ? volumeBars : selector === '#btn-volume-icon, #fs-volume-icon' ? muteButtons : selector === '.lyrics-toggle-btn' ? [viewButtons[1]] : selector === '#progress-bar, #fs-progress-bar' ? [progress] : [] };
const window = { matchMedia: () => ({ matches: false }) };
const values = { isFullscreenPlayerOpen: false, isQueueOpen: false, currentTrack: { db_id: 1, title: 'First', artist: 'Artist', is_local: true }, userFavorites: new Set([1]), repeatMode: 'off', isPlaying: false, shuffleMode: false, contextQueue: [], contextIndex: -1, userQueue: [], audio: { duration: 120, currentTime: 0 }, ytReady: false, ytPlayer: null, API: '/api' };
let stateModule, volume = 0.7, respond = async () => ({ ok: true, json: async () => ({ plain: 'New lyrics' }) });
for (const [setter, key] of [['setIsFullscreenPlayerOpen', 'isFullscreenPlayerOpen'], ['setIsQueueOpen', 'isQueueOpen']]) values[setter] = value => { values[key] = value; stateModule.setExport(key, value); };
const context = vm.createContext({ window, document, console, AbortController, URLSearchParams, CustomEvent: class { constructor(type, data) { this.type = type; Object.assign(this, data); } }, setTimeout, clearTimeout, fetch: (...args) => respond(...args) });
function synthetic(values) {
  return new vm.SyntheticModule(Object.keys(values), function () { for (const [key, value] of Object.entries(values)) this.setExport(key, value); }, { context });
}
stateModule = synthetic(values);
const engine = synthetic({ getVolume: () => volume, setVolume: value => { volume = Math.max(0, Math.min(1, value)); } });
const imports = { './state.js': stateModule, './audio_engine.js': engine, './player.js': synthetic({ persistPlaybackSession() {} }) };
async function load(name) {
  const module = new vm.SourceTextModule(await readFile(new URL(`../Aoede/assets/js/modules/${name}.js`, import.meta.url), 'utf8') + (name === 'lyrics' ? '\nexport { parseLRC, lineAt, playbackTime };' : ''), { context });
  await module.link(key => imports[key]); await module.evaluate(); imports[`./${name}.js`] = module; return module.namespace;
}
const ui = await load('ui'), queueApi = await load('queue'), lyricsApi = await load('lyrics');
// Transport gestures: one horizontal swipe, with taps, scrolling and controls isolated.
const swipeSurface = element('swipe-surface'), swipeActions = [];
ui.setupTrackSwipe(swipeSurface, action => swipeActions.push(action));
const touch = (x, y = 0, identifier = 1) => ({ clientX: x, clientY: y, identifier });
function touchEvent(type, points, changedTouches = [], target = swipeSurface) {
  const event = { touches: points, changedTouches, target, cancelable: true, prevented: false,
    preventDefault() { this.prevented = true; } };
  swipeSurface.events.get(type)(event); return event;
}
const swipe = (start, end, target = swipeSurface) => {
  touchEvent('touchstart', [start], [], target);
  const move = touchEvent('touchmove', [end], [], target);
  touchEvent('touchend', [], [end], target);
  return move;
};
const normalMatchMedia = window.matchMedia;
const gestureClock = vm.runInContext('Date', context), realNow = gestureClock.now;
let gestureTime = 1000;
gestureClock.now = () => gestureTime;
window.matchMedia = () => ({ matches: true });
assert.equal(swipe(touch(180), touch(80)).prevented, true);
swipe(touch(80), touch(180));
assert.deepEqual(swipeActions, ['next', 'previous']);
const click = detail => ({ detail, prevented: false, stopped: false, preventDefault() { this.prevented = true; }, stopImmediatePropagation() { this.stopped = true; } });
let ghost = click(1); swipeSurface.events.get('click')(ghost);
assert.equal(ghost.prevented && ghost.stopped, true);
const keyboardClick = click(0); swipeSurface.events.get('click')(keyboardClick);
assert.equal(keyboardClick.prevented, false);
swipe(touch(120), touch(120));
ghost = click(1); swipeSurface.events.get('click')(ghost); assert.equal(ghost.prevented, false);
assert.equal(swipe(touch(120), touch(116, 100)).prevented, false);
swipe(touch(120), touch(98, 2)); // Short horizontal drag.
swipe(touch(120), touch(20, 90)); // Diagonal scroll.
swipe(touch(120), touch(20), { closest: () => ({}) }); // A button/slider/interactive child.
touchEvent('touchstart', [touch(120)]);
touchEvent('touchmove', [touch(20), touch(80, 0, 2)]);
touchEvent('touchend', [], [touch(20)]);
touchEvent('touchstart', [touch(120), touch(80, 0, 2)]);
touchEvent('touchend', [], [touch(20)]);
touchEvent('touchstart', [touch(120)]);
touchEvent('touchcancel', []);
touchEvent('touchend', [], [touch(20)]);
touchEvent('touchstart', [touch(120)]);
touchEvent('touchmove', [touch(20, 0, 2)]); // A different touch cannot finish an abandoned gesture.
touchEvent('touchend', [], [touch(20, 0, 2)]);
touchEvent('touchstart', [touch(120)]);
touchEvent('touchmove', [touch(122, 30)]);
touchEvent('touchend', [], [touch(20, 35)]); // Do not reclassify a vertical gesture at release.
touchEvent('touchstart', [touch(120)]);
gestureTime += 900;
touchEvent('touchend', [], [touch(20)]); // A long press is not a swipe.
stateModule.setExport('currentTrack', null);
swipe(touch(180), touch(80));
stateModule.setExport('currentTrack', values.currentTrack);
window.matchMedia = normalMatchMedia;
gestureClock.now = realNow;
swipe(touch(180), touch(80)); // Mouse/desktop layouts keep their existing behavior.
assert.deepEqual(swipeActions, ['next', 'previous']);
console.log('PASS: mobile track gestures preserve taps, keyboard clicks, controls, vertical scrolling and cancelled/multi-touch input.');
// Dismiss controls remain named and never submit a surrounding form, even without Lucide.
const closeButton = element('close-test'), namedClose = element('named-close-test');
namedClose.setAttribute('aria-label', 'Close collaborators');
ui.refreshIcons({ querySelectorAll: selector => selector.includes('button.modal-close') ? [closeButton, namedClose] : [] });
assert.equal(closeButton.getAttribute('aria-label'), 'Close');
assert.equal(closeButton.getAttribute('type'), 'button');
assert.equal(namedClose.getAttribute('aria-label'), 'Close collaborators');
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
// All themes embed the same panels and retain the selected view on a theme change.
for (const theme of ['classic', 'glass', 'vitrine']) {
  document.documentElement.dataset.theme = theme;
  for (const playing of [false, true, false]) {
    stateModule.setExport('isPlaying', playing);
    ui.updateFullscreenPlayButton();
    assert.equal(full.dataset.playing, String(playing));
  }
  ui.toggleFullscreenPlayer(); queueApi.toggleQueue();
  assert.equal(full.dataset.view, 'queue'); assert.equal(queue.parentElement, theme === 'vitrine' ? detailStage : stage);
  if (theme === 'vitrine') { assert.equal(cover.parentElement, info); assert.equal(volumePanel.parentElement, volumeSlot); assert.equal(shuffle.parentElement, menu); }
  lyricsApi.toggleLyricsPanel(); await new Promise(setImmediate);
  assert.equal(full.dataset.view, 'lyrics'); assert.equal(values.isQueueOpen, false);
  document.documentElement.dataset.theme = theme === 'glass' ? 'classic' : 'glass';
  ui.syncFullscreenPlayerLayout();
  assert.equal(full.dataset.view, 'lyrics'); assert.equal(lyrics.parentElement, stage);
  assert.equal(favorite.parentElement, songActions); assert.equal(add.parentElement, songActions);
  assert.equal(cover.parentElement, stage); assert.equal(volumePanel.parentElement, volumePopover); assert.equal(shuffle.parentElement, controls);
  assert.deepEqual(actions.children.map(node => node.id), ['view-cover', 'view-lyrics', 'view-queue', 'fs-audio-action', 'fs-btn-sleep']);
  queueApi.toggleQueue(); assert.equal(full.dataset.view, 'queue'); assert.equal(lyricsApi.isLyricsPanelOpen(), false);
  ui.toggleFullscreenPlayer();
  assert.equal(lyrics.parentElement, body); assert.equal(queue.parentElement, body);
  assert.equal(values.isQueueOpen, false); assert.equal(lyricsApi.isLyricsPanelOpen(), false);
}
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
ui.updateUIProgress(30, 120); assert.equal(fields.get('fs-time-remaining').innerText, '−1:30'); assert.equal(progress.getAttribute('aria-valuenow'), '25');
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
console.log('PASS: shared Classic/Glass/Vitrine player layout, theme changes with open panels, stale lyrics, synchronized volume and keyboard seeking.');

// The global player shortcut must not consume Space on a menu disclosure.
const mainSource = await readFile(new URL('../Aoede/assets/js/main.js', import.meta.url), 'utf8');
let shortcut, playClicks = 0;
document.addEventListener = (_name, callback) => { shortcut = callback; };
element('play-pause-btn').click = () => { playClicks++; };
context.state = values;
vm.runInContext(mainSource.match(/function isTypingTarget\(el\) \{[\s\S]*?\n\}/)[0] + '\n' +
  mainSource.match(/function initKeyboardShortcuts\(\) \{[\s\S]*?\n\}/)[0] + '\ninitKeyboardShortcuts();', context);
for (const tagName of ['BUTTON', 'SUMMARY', 'BODY']) {
  let prevented = false;
  shortcut({ target: { tagName }, code: 'Space', key: ' ', preventDefault() { prevented = true; } });
  assert.equal(prevented, tagName === 'BODY');
}
assert.equal(playClicks, 1);
console.log('PASS: Space opens native menu disclosures without triggering the player.');

// LRC offsets, fractions, multiple stamps, invalid seconds and backward seeks.
assert.equal(JSON.stringify(lyricsApi.parseLRC('[offset:+500]\n[00:02.00][00:04:250] Chorus\n[00:65]invalid')), JSON.stringify([{time:1.5,text:'Chorus'},{time:3.75,text:'Chorus'}]));
const lines = [{time:2}, {time:4}, {time:4}, {time:10}];
assert.equal(lyricsApi.lineAt(lines, 0), -1);
assert.equal(lyricsApi.lineAt(lines, 4), 2);
assert.equal(lyricsApi.lineAt(lines, 20), 3);
assert.equal(lyricsApi.lineAt(lines, 3), 0);
respond = async () => ({ ok: true, json: async () => ({ synced: 'malformed timestamps', plain: 'Plain fallback' }) });
await lyricsApi.loadLyricsFor({ db_id: 7, fed_instance_id: 1, title: 'Same ID' });
assert.match(lyricsBody.innerHTML, /Plain fallback/);
let requestCount = 0;
respond = async () => { requestCount++; return { ok: true, json: async () => ({ plain: 'Another node' }) }; };
await lyricsApi.loadLyricsFor({ db_id: 7, fed_instance_id: 2, title: 'Same ID' });
assert.equal(requestCount, 1);
let finishRemoved;
respond = async () => ({ ok: true, json: () => new Promise(resolve => { finishRemoved = resolve; }) });
const loading = lyricsApi.loadLyricsFor({ db_id: 42, title: 'Removed' }); await new Promise(setImmediate);
await lyricsApi.loadLyricsFor(null); finishRemoved({plain:'stale after stop'}); await loading;
assert.doesNotMatch(lyricsBody.innerHTML, /stale after stop/);
stateModule.setExport('ytPlayer', { getCurrentTime: () => 12.5 });
stateModule.setExport('currentTrack', { source: 'youtube', is_local: false });
assert.equal(lyricsApi.playbackTime(), 12.5);
stateModule.setExport('currentTrack', { source: 'youtube', fed_instance_id: 2 });
assert.equal(lyricsApi.playbackTime(), values.audio.currentTime);
console.log('PASS: lyric offsets, intro, backward seeks, malformed LRC fallback, federated identity, cleared-track races and preview clocks.');

// Exercise highlighting against rendered line nodes, including reopening a cached song.
const lyricNodes = [element('lyric-0'), element('lyric-1'), element('lyric-2')];
let scrollCalls = 0;
lyricsBody.querySelectorAll = () => lyricNodes.filter(node => node.classList.contains('active'));
lyricsBody.querySelector = selector => lyricNodes[Number(selector.match(/data-idx="(-?\d+)"/)?.[1])] || null;
lyricsBody.scrollTo = () => { scrollCalls++; };
values.audio.currentTime = 0; values.audio.duration = 999;
stateModule.setExport('currentTrack', { db_id: 1000, title: 'Timing', duration: 120 });
respond = async (url) => {
  assert.equal(new URL(url, 'https://aoede.test').searchParams.get('duration'), '120');
  return { ok: true, json: async () => ({ synced: '[00:02]First\n[00:04]Second\n[00:10]Last' }) };
};
lyricsApi.toggleLyricsPanel(); await new Promise(setImmediate);
assert.equal(lyricNodes.some(node => node.classList.contains('active')), false);
lyricsApi.tick(4.5); assert.equal(lyricNodes[1].getAttribute('aria-current'), 'true');
lyricsApi.tick(2.5); assert.equal(lyricNodes[0].getAttribute('aria-current'), 'true');
assert.equal(lyricNodes[1].getAttribute('aria-current'), undefined);
lyricsApi.closeLyricsPanel(); values.audio.currentTime = 10.5;
const previousScrolls = scrollCalls; lyricsApi.toggleLyricsPanel();
assert.equal(lyricNodes[2].getAttribute('aria-current'), 'true'); assert.ok(scrollCalls > previousScrolls);
lyricsApi.closeLyricsPanel();
respond = async () => ({ ok: true, json: async () => ({ synced: '', plain: '' }) });
await lyricsApi.loadLyricsFor({db_id:1001}); assert.match(lyricsBody.innerHTML, /No lyrics found/);
console.log('PASS: rendered highlighting, backward seek, cached reopen, recording duration and empty lyric state.');
