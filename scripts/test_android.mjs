// Run: node --experimental-vm-modules scripts/test_android.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const shim = await readFile(new URL('../android/app/src/main/assets/media-session.js', import.meta.url), 'utf8');
function page({ frame = false, early = false, native = true, audioContext = null, captureStream = null, imageClass = null, canvas = null, objectUrls = null } = {}) {
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
    volume = 1;
    muted = false;
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
  if (audioContext) window.AudioContext = audioContext;
  if (captureStream) audio.captureStream = captureStream;
  window.top = frame ? {} : window;
  window.AoedeMedia = { postMessage(value) { messages.push(JSON.parse(value)); } };
  const document = new EventTarget();
  document.documentElement = early ? null : {};
  document.getElementById = (id) => elements.get(id) || (id === 'audio-player' ? audio : null);
  document.querySelector = (selector) => selector === '.player-footer.party-player' ? (inParty ? {} : null) : selector === 'audio' ? audio : { click() { clicks.push(selector); } };
  document.querySelectorAll = () => [audio, preview];
  document.createElement = () => canvas;
  const navigator = native ? { mediaSession: new Session() } : {};
  vm.runInNewContext(shim, { window, document, navigator, HTMLMediaElement: Media,
    MutationObserver: class { constructor(callback) { observers.push(callback); } observe(root) { assert.ok(root); } },
    location: { href: 'https://example.com/portal' }, URL: objectUrls || URL, Image: imageClass, Promise, Event, DOMException, console });
  const state = () => messages.filter((message) => message.type === 'state').at(-1);
  return { window, document, navigator, audio, preview, messages, nativeActions, state, clicks, elements,
    mutate() { observers.forEach(callback => callback()); }, party(value) { inParty = value; } };
}
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

// Offline covers use the downloaded blob and bounded inline artwork, without a new HTTP fetch.
{
  const track = { db_id: 8, title: 'Offline song', thumbnail: 'https://example.com/offline-cover.png' };
  const inline = 'data:image/jpeg;base64,' + 'a'.repeat(5000);
  const covers = new Map([['player-cover', {}], ['fs-cover', {}]]);
  const revoked = [];
  const p = page({
    imageClass: class { naturalWidth = 512; naturalHeight = 512; set src(value) { assert.equal(value, 'blob:downloaded-cover'); queueMicrotask(() => this.onload()); } },
    objectUrls: Object.assign(class extends URL {}, { createObjectURL: () => 'blob:downloaded-cover', revokeObjectURL: value => revoked.push(value) }),
    canvas: { getContext: () => ({ drawImage() {} }), toDataURL: () => inline }
  });
  for (const [id, cover] of covers) p.elements.set(id, cover);
  const context = vm.createContext({ window: p.window, navigator: p.navigator, document: p.document, console });
  const player = new vm.SourceTextModule(await readFile(new URL('../Aoede/assets/js/modules/player.js', import.meta.url), 'utf8') + '\nexport { updateMediaSessionMetadata };', { context });
  await player.link(name => {
    const values = name === './state.js' ? { currentTrack: track, audio: { duration: 0 } }
      : name === './offline_store.js' ? { getOfflineTrack: async id => { assert.equal(id, 8); return { artwork_blob: { size: 1000 } }; } } : {};
    return new vm.SyntheticModule(Object.keys(values), function () { for (const [key, value] of Object.entries(values)) this.setExport(key, value); }, { context });
  });
  await player.evaluate();
  player.namespace.updateMediaSessionMetadata(track);
  await new Promise(setImmediate);
  assert.equal(p.navigator.mediaSession.metadata.artwork[0].src, inline);
  assert.equal(p.navigator.mediaSession.metadata.artwork[0].type, 'image/jpeg');
  assert.equal(covers.get('player-cover').src, inline);
  assert.equal(covers.get('fs-cover').src, inline);
  assert.deepEqual(revoked, ['blob:downloaded-cover']);
  assert.equal(p.messages.filter(message => message.type === 'metadata').at(-1).artwork, inline);
  const remote = { db_id: 8, fed_instance_id: 2, title: 'Remote song', thumbnail: 'https://remote.example/art.png' };
  player.namespace.updateMediaSessionMetadata(remote);
  assert.equal(p.navigator.mediaSession.metadata.artwork[0].src, remote.thumbnail); // A remote ID cannot reuse a local cover.
  let finishOldCover;
  p.window.AoedeAndroid.withOfflineArtwork({ artwork: [] }, 9, () => new Promise(resolve => { finishOldCover = resolve; }));
  p.window.AoedeAndroid.withOfflineArtwork({ artwork: [] }, null);
  finishOldCover({ artwork_blob: { size: 1000 } });
  await new Promise(setImmediate);
  assert.equal(p.navigator.mediaSession.metadata.artwork[0].src, remote.thumbnail);
  assert.deepEqual(revoked, ['blob:downloaded-cover']); // A discarded lookup cannot allocate or publish another cover.
}

// The island reads real frequency bands from a disposable copy, never the audible output.
{
  const contexts = [], streams = [];
  let tone = [0, 0];
  class AudioContext {
    state = 'running'; sampleRate = 48000;
    constructor() { contexts.push(this); }
    createAnalyser() {
      return { frequencyBinCount: 512, getByteFrequencyData(data) {
        data.fill(0);
        if (tone[1]) data[Math.round(tone[0] / (48000 / 1024))] = tone[1];
      } };
    }
    createMediaStreamSource(stream) {
      assert.equal(stream, streams.at(-1));
      return { connect(node) { assert.ok(node.getByteFrequencyData); }, disconnect() {} };
    }
    createMediaElementSource() { assert.fail('Visuals must not reroute the playing audio'); }
    close() { this.state = 'closed'; return Promise.resolve(); }
    resume() { this.state = 'running'; return Promise.resolve(); }
  }
  const p = page({ audioContext: AudioContext, captureStream() {
    const track = { stopped: false, readyState: 'live', enabled: true, muted: false, stop() { this.stopped = true; } };
    const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
    streams.push(stream); return stream;
  } });
  const levels = () => Array.from(p.window.__aoedeIslandLevels(true) || []);
  assert.deepEqual(levels(), []);
  assert.equal(contexts.length, 0); // Paused/off islands allocate nothing.
  p.window.__aoedeMediaCommand('play'); p.audio.dispatchEvent(new Event('playing')); await flush();
  assert.deepEqual(levels(), [0, 0, 0, 0, 0]); // Silence has no invented beat.
  tone = [140, 255];
  const bass = levels();
  assert.ok(bass[0] > 0 && bass.slice(1).every(n => n === 0));
  tone = [6000, 255];
  const treble = levels();
  assert.ok(treble[4] > 0 && treble.slice(0, 4).every(n => n === 0));
  p.audio.volume = .5;
  assert.ok(Math.abs(levels()[4] * 2 - treble[4]) <= .002);
  p.audio.muted = true;
  assert.deepEqual(levels(), [0, 0, 0, 0, 0]); p.audio.muted = false;
  assert.equal(contexts.length, 1);
  streams[0].getTracks()[0].muted = true;
  assert.equal(p.window.__aoedeIslandLevels(true), null); // Protected audio gets the requested animation fallback.
  streams[0].getTracks()[0].muted = false;
  const plays = p.audio.plays;
  p.audio.src = p.audio.currentSrc = 'https://example.com/second-song';
  levels();
  assert.equal(contexts[0].state, 'closed');
  assert.equal(streams[0].getTracks()[0].stopped, true);
  assert.equal(contexts.length, 1); // Disposal is asynchronous: wait before allocating the next graph.
  await new Promise(setImmediate); levels();
  assert.equal(contexts.length, 2); // Source swaps cannot keep sampling a finished track.
  p.window.__aoedeIslandLevels(false);
  assert.equal(contexts[1].state, 'closed');
  assert.equal(p.audio.paused, false);
  assert.equal(p.audio.plays, plays);
  assert.equal(p.audio.volume, .5);
  await new Promise(setImmediate); levels(); p.window.__aoedeMediaCommand('pause');
  assert.equal(contexts[2].state, 'closed');
  assert.deepEqual(levels(), []);
  const unavailable = page({ audioContext: AudioContext, captureStream() { throw new DOMException('No capture', 'SecurityError'); } });
  unavailable.window.__aoedeMediaCommand('play'); unavailable.audio.dispatchEvent(new Event('playing')); await flush();
  assert.equal(unavailable.window.__aoedeIslandLevels(true), null);
  assert.equal(unavailable.audio.paused, false); // Cross-origin/unsupported sources must keep playing.
  let captures = 0;
  const empty = page({ audioContext: AudioContext, captureStream() {
    captures++; return { getAudioTracks: () => [], getTracks: () => [] };
  } });
  empty.window.__aoedeMediaCommand('play'); empty.audio.dispatchEvent(new Event('playing')); await flush();
  for (let i = 0; i < 5; i++) assert.equal(empty.window.__aoedeIslandLevels(true), null);
  assert.equal(captures, 1); // Unsupported WebViews must not capture/dispose streams on every sample.
  let resumes = 0;
  class SuspendedContext extends AudioContext {
    state = 'suspended';
    resume() { resumes++; return new Promise(() => {}); }
  }
  const suspended = page({ audioContext: SuspendedContext, captureStream() {
    const track = { readyState: 'live', enabled: true, muted: false, stop() {} };
    const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
    streams.push(stream); return stream;
  } });
  suspended.window.__aoedeMediaCommand('play'); suspended.audio.dispatchEvent(new Event('playing')); await flush();
  for (let i = 0; i < 5; i++) assert.equal(suspended.window.__aoedeIslandLevels(true), null);
  assert.equal(resumes, 1); assert.equal(suspended.audio.paused, false);
  suspended.window.__aoedeIslandLevels(false);
  assert.equal(contexts.at(-1).state, 'closed');
}

// Reordered native requests and deferred WebAudio close/resume must not recreate an obsolete graph.
{
  let captures = 0, closes = 0, stopped = 0, finishClose, rejectResume;
  const contexts = [];
  class DeferredContext {
    state = 'suspended'; sampleRate = 48000;
    constructor() { contexts.push(this); }
    createAnalyser() { return { frequencyBinCount: 512, getByteFrequencyData(data) { data.fill(0); } }; }
    createMediaStreamSource() { return { connect() {}, disconnect() { throw new Error('optional node cleanup failure'); } }; }
    resume() { return new Promise((_, reject) => { rejectResume = reject; }); }
    close() { closes++; return new Promise(resolve => { finishClose = () => { this.state = 'closed'; resolve(); }; }); }
  }
  const p = page({ audioContext: DeferredContext, captureStream() {
    captures++;
    const tracks = [{ readyState: 'live', enabled: true, muted: false, stop() { throw new Error('one track cleanup failed'); } },
      { readyState: 'live', enabled: true, muted: false, stop() { stopped++; } }];
    return { getTracks: () => tracks, getAudioTracks: () => tracks };
  } });
  p.window.__aoedeMediaCommand('play'); p.audio.dispatchEvent(new Event('playing')); await flush();
  const plays = p.audio.plays;
  assert.equal(p.window.__aoedeIslandLevels(true, 1), null);
  p.window.__aoedeIslandLevels(false, 2);
  for (let i = 0; i < 30; i++) {
    p.window.__aoedeIslandLevels(true, 1); // A queued request from before stop is stale.
    p.window.__aoedeIslandLevels(true, 3); // Even a fresh request must wait for disposal.
  }
  assert.equal(captures, 1); assert.equal(closes, 1); assert.equal(stopped,1);
  rejectResume(new Error('resume cancelled by close')); await new Promise(setImmediate);
  finishClose(); await new Promise(setImmediate);
  p.window.__aoedeIslandLevels(false, 4);
  p.window.__aoedeIslandLevels(true, 3);
  assert.equal(captures, 1); // Stop remains authoritative after the old promises settle.
  p.window.__aoedeIslandLevels(true, 5);
  assert.equal(captures, 2);
  p.window.__aoedeIslandLevels(false, 4);
  assert.equal(closes, 1); // A stale stop cannot dispose a newer graph.
  assert.equal(p.audio.plays, plays); assert.equal(p.audio.paused, false); assert.equal(p.audio.volume, 1);

  let failedCaptures = 0;
  class FailedClose extends DeferredContext { state = 'running'; close() { return Promise.reject(new Error('Close failed')); } }
  const failed = page({audioContext:FailedClose,captureStream(){
    failedCaptures++; const track={readyState:'live',enabled:true,muted:false,stop(){}};
    return {getTracks:()=>[track],getAudioTracks:()=>[track]};
  }});
  failed.window.__aoedeMediaCommand('play'); failed.audio.dispatchEvent(new Event('playing')); await flush();
  failed.window.__aoedeIslandLevels(true,1); failed.window.__aoedeIslandLevels(false,2);
  await new Promise(setImmediate);
  for(let i=3;i<20;i++)failed.window.__aoedeIslandLevels(true,i);
  failed.audio.src = failed.audio.currentSrc = 'https://example.com/new-source';
  failed.audio.dispatchEvent(new Event('play')); failed.audio.dispatchEvent(new Event('playing')); await flush();
  failed.window.__aoedeIslandLevels(true,20);
  assert.equal(failedCaptures,1); assert.equal(failed.audio.paused,false);
}

// Playback progress can recover a missed WebView "playing" event after buffering.
{
  const p = page();
  p.window.__aoedeMediaCommand('play'); p.audio.dispatchEvent(new Event('playing')); await flush();
  p.audio.readyState = 2; p.audio.dispatchEvent(new Event('waiting')); await flush();
  assert.equal(p.state().state, 'buffering');
  p.audio.readyState = 3; p.audio.currentTime++;
  p.audio.dispatchEvent(new Event('timeupdate')); await flush();
  assert.equal(p.state().state, 'playing');
  p.window.__aoedeMediaCommand('pause');
  p.audio.dispatchEvent(new Event('timeupdate')); await flush();
  assert.equal(p.state().state, 'paused');
}

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
