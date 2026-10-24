// Run: node --experimental-vm-modules scripts/test_android.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const shim = await readFile(new URL('../android/app/src/main/assets/media-session.js', import.meta.url), 'utf8');
function page({ frame = false, early = false, native = true } = {}) {
  const messages = [];
  const nativeActions = new Map();
  const clicks = [];
  const elements = new Map(), observers = [];
  let inParty = false;
  class Media extends EventTarget {
    paused = true;
    ended = false;
    src = 'https://example.com/song';
    currentSrc = this.src;
    currentTime = 5;
    duration = 100;
    playbackRate = 1;
    readyState = 4;
    plays = 0;
    play() { this.plays++; this.paused = false; this.dispatchEvent(new Event('play')); return Promise.resolve(); }
    pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
  }
  class Session {
    _metadata = { title: 'Song', artist: 'Artist', artwork: [{ src: '/cover.png' }] };
    _state = 'none';
    get metadata() { return this._metadata; }
    set metadata(value) { this._metadata = value; }
    get playbackState() { return this._state; }
    set playbackState(value) { this._state = value; }
    setActionHandler(action, callback) { nativeActions.set(action, callback); }
    setPositionState(value) { this.position = value; }
  }
  const audio = new Media();
  const preview = new Media();
  const window = new EventTarget();
  window.top = frame ? {} : window;
  window.AoedeMedia = { postMessage(value) { messages.push(JSON.parse(value)); } };
  const document = new EventTarget();
  document.documentElement = early ? null : {};
  document.getElementById = (id) => elements.get(id) || (id === 'audio-player' ? audio : null);
  document.querySelector = (selector) => selector === '.player-footer.party-player' ? (inParty ? {} : null) : selector === 'audio' ? audio : { click() { clicks.push(selector); } };
  document.querySelectorAll = () => [audio, preview];
  const navigator = native ? { mediaSession: new Session() } : {};
  vm.runInNewContext(shim, { window, document, navigator, HTMLMediaElement: Media,
    MutationObserver: class { constructor(callback) { observers.push(callback); } observe(root) { assert.ok(root); } },
    location: { href: 'https://example.com/portal' }, URL, Promise, Event, DOMException, console });
  const state = () => messages.filter((message) => message.type === 'state').at(-1);
  return { window, document, navigator, audio, preview, messages, nativeActions, state, clicks, elements,
    mutate() { observers.forEach(callback => callback()); }, party(value) { inParty = value; } };
}
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

// Island controls use the existing player buttons and retain its availability rules.
{
  const p = page();
  const makeButton = () => ({ disabled: false, classes: new Set(),
    classList: { contains(name) { return this.owner.classes.has(name); } },
    querySelector() { return null; }, click() { this.clicks = (this.clicks || 0) + 1; } });
  for (const id of ['fs-btn-favorite', 'btn-shuffle', 'btn-repeat']) {
    const button = makeButton(); button.classList.owner = button; p.elements.set(id, button);
  }
  p.window.toggleFavoriteFromPlayer = () => {};
  p.window.toggleShuffle = () => {};
  p.window.toggleRepeat = () => {};
  const controls = () => p.messages.filter(message => message.type === 'controls').at(-1);
  p.mutate();
  assert.equal(controls().canFavorite, true);
  for (const [action, id] of [['favorite', 'fs-btn-favorite'], ['shuffle', 'btn-shuffle'], ['repeat', 'btn-repeat']]) {
    p.window.__aoedeMediaCommand(action);
    assert.equal(p.elements.get(id).clicks, 1);
  }
  p.elements.get('fs-btn-favorite').disabled = true;
  p.elements.get('btn-repeat').querySelector = () => ({});
  p.mutate();
  assert.equal(controls().canFavorite, false);
  assert.equal(controls().repeat, 'one');
  p.window.__aoedeMediaCommand('favorite');
  assert.equal(p.elements.get('fs-btn-favorite').clicks, 1);
  p.party(true); p.mutate();
  assert.equal(controls().canShuffle, false);
  assert.equal(controls().canRepeat, false);
  p.window.__aoedeMediaCommand('shuffle');
  assert.equal(p.elements.get('btn-shuffle').clicks, 1);
  // A deferred open is idempotent and cannot start/resume music.
  p.window.__aoedeMediaCommand('openplayer');
  const panel = { opened: false, classList: { contains() { return panel.opened; } } };
  p.elements.set('fullscreen-player', panel);
  let opens = 0;
  p.window.toggleFullscreenPlayer = () => { opens++; panel.opened = !panel.opened; };
  p.window.showFullscreenCover = () => {};
  p.mutate(); p.window.__aoedeMediaCommand('openplayer');
  assert.equal(opens, 1);
  assert.equal(panel.opened, true);
  assert.equal(p.audio.plays, 0);
}

for (const native of [true, false]) {
  const p = page({ native });
  const session = p.navigator.mediaSession;
  assert.equal(p.state().state, 'none'); // Opening the app must not announce playback.
  session.setActionHandler('play', () => { p.audio.play(); session.playbackState = 'playing'; });
  session.setActionHandler('pause', () => { throw new Error('A party-wide pause must not run'); });
  p.window.__aoedeMediaCommand('play');
  await flush();
  assert.equal(p.state().state, 'buffering'); // The play promise/event is not yet audible playback.
  p.audio.dispatchEvent(new Event('playing'));
  await flush();
  assert.equal(p.state().state, 'playing');
  assert.equal(p.state().position, 5000);
  const count = p.messages.length;
  p.preview.pause();
  await flush();
  assert.equal(p.messages.length, count); // Previews cannot overwrite the main player's state.

  p.window.__aoedeMediaCommand('pause');
  await flush();
  assert.equal(p.state().state, 'paused');
  assert.equal(p.window.AoedeAndroid.isPaused, true);
  const plays = p.audio.plays;
  await assert.rejects(p.audio.play(), { name: 'NotAllowedError' });
  session.playbackState = 'playing'; // A later room update cannot undo local suspension.
  session.setPositionState({ position: 10, duration: 100 });
  await flush();
  assert.equal(p.audio.plays, plays);
  assert.equal(p.state().state, 'paused');
  if (native) {
    session.setActionHandler('seekto', ({ seekTime }) => { p.audio.currentTime = seekTime; });
    p.nativeActions.get('pause')();
    p.nativeActions.get('seekto')({ seekTime: 12 });
    await flush();
    assert.equal(p.audio.currentTime, 12);
    assert.equal(p.window.AoedeAndroid.isPaused, true); // Seeking must not resume a local/noisy pause.
    assert.equal(p.state().state, 'paused');
  }
  p.window.__aoedeMediaCommand('play');
  await flush();
  assert.equal(p.audio.plays, plays + 1);
  assert.equal(p.window.AoedeAndroid.isPaused, false);

  p.audio.dispatchEvent(new Event('playing'));
  await flush();
  p.audio.ended = true;
  p.audio.paused = true;
  p.audio.dispatchEvent(new Event('ended'));
  // App listener order can differ: its intent to continue must survive either order.
  session.playbackState = 'playing';
  await flush();
  assert.equal(p.state().state, 'buffering');
  session.playbackState = 'none';
  await flush();
  assert.equal(p.state().state, 'none');

  p.window.__aoedeMediaCommand('stop');
  p.audio.dispatchEvent(new Event('timeupdate'));
  await flush();
  assert.equal(p.state().state, 'none'); // Stop must not be resurrected by progress events.
  if (native) {
    assert.equal(session._state, 'none'); // Preserve the underlying Chromium MediaSession API.
    assert.ok(p.nativeActions.get('pause'));
  }
}

const iframe = page({ frame: true });
assert.equal(iframe.messages.length, 0);
assert.equal(iframe.window.AoedeAndroid, undefined);
const early = page({ early: true });
early.document.documentElement = {};
early.document.dispatchEvent(new Event('DOMContentLoaded'));
assert.equal(early.state().state, 'none');

// Exercise the actual web player: party snapshots and scheduled starts respect local suspension.
const timers = new Map();
let timerId = 0;
let starts = 0;
const audio = { paused: true, readyState: 4, currentTime: 0, duration: 100,
  pause() { this.paused = true; }, async play() { starts++; this.paused = false; } };
const track = { id: 'party-track', db_id: 3, stream_url: '/stream' };
const state = { audio, currentTrack: track, setIsPlaying() {} };
const window = { AoedeAndroid: { isPaused: false, resumeLocal() { this.isPaused = false; } } };
const context = vm.createContext({ window, navigator: { mediaSession: { playbackState: 'none' } }, console,
  clearTimeout(id) { timers.delete(id); }, setTimeout(callback) { const id = ++timerId; timers.set(id, callback); return id; } });
const player = new vm.SourceTextModule(await readFile(new URL('../Aoede/assets/js/modules/player.js', import.meta.url), 'utf8'), { context });
await player.link((name) => {
  const values = name === './state.js' ? state : name === './ui.js' ? { updatePlayButton() {} } : {};
  return new vm.SyntheticModule(Object.keys(values), function () {
    for (const [key, value] of Object.entries(values)) this.setExport(key, value);
  }, { context });
});
await player.evaluate();
let blockedStarts = 0;
player.namespace.setPartyController({ isActive: () => true, onAutoplayBlocked() { blockedStarts++; } });
const room = { current_track: track, current_item_id: 2, playback_status: 'playing', playback_position_ms: 2000 };
player.namespace.suspendLocalPlayback();
assert.equal(await player.namespace.syncPartyPlayback(room), false);
assert.equal(starts, 0);
player.namespace.resumeLocalPlayback();
assert.equal(await player.namespace.syncPartyPlayback(room), true);
assert.equal(starts, 1);
audio.paused = true;
await player.namespace.syncPartyPlayback({ ...room, playback_starts_at_ms: 3000, server_time_ms: 1000 });
const pending = [...timers.values()].at(-1);
assert.ok(pending);
player.namespace.suspendLocalPlayback();
pending();
assert.equal(starts, 1);
window.AoedeAndroid.isPaused = true;
player.namespace.resumeLocalPlayback();
assert.equal(await player.namespace.syncPartyPlayback(room), true);
assert.equal(starts, 2);
audio.paused = true;
audio.play = async () => { throw new DOMException('Autoplay blocked', 'NotAllowedError'); };
await player.namespace.syncPartyPlayback({ ...room, playback_starts_at_ms: 5000, server_time_ms: 1000 });
[...timers.values()].at(-1)();
await flush();
assert.equal(blockedStarts, 1); // A delayed play rejection is reported immediately, without waiting for another SSE event.
console.log('Android media bridge and local party interruption checks passed');
