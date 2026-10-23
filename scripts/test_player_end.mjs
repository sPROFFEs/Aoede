// Run: node --experimental-vm-modules scripts/test_player_end.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

class Audio extends EventTarget {
  src = '';
  paused = true;
  ended = false;
  currentTime = 0;
  duration = 120;
  async play() { this.paused = false; this.ended = false; this.dispatchEvent(new Event('play')); }
  pause() { if (!this.paused) { this.paused = true; queueMicrotask(() => this.dispatchEvent(new Event('pause'))); } }
  removeAttribute(name) { if (name === 'src') this.src = ''; }
  load() { this.currentTime = 0; this.ended = false; }
}
const audio = new Audio(), actions = new Map(), storage = new Map();
const classes = () => {
  const values = new Set();
  return { contains: name => values.has(name), toggle(name, enabled) { enabled ? values.add(name) : values.delete(name); } };
};
const footer = { classList: classes(), querySelector: () => null, addEventListener() {} };
const document = Object.assign(new EventTarget(), {
  body: { classList: classes() }, visibilityState: 'visible',
  documentElement: { dataset: { theme: 'classic' } },
  getElementById: id => id === 'audio-player' ? audio : ['player-title', 'player-cover'].includes(id) ? {} : null,
  querySelector: selector => selector === '.player-footer' ? footer : null,
  querySelectorAll: () => []
});
const navigator = { onLine: true, userAgent: 'Test', mediaSession: {
  playbackState: 'none', metadata: null, setActionHandler: (action, handler) => actions.set(action, handler)
} };
const context = vm.createContext({ document, navigator, console, URL, URLSearchParams, Event, AbortController,
  localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
  window: Object.assign(new EventTarget(), { setTimeout: () => 1, setInterval: () => 1, matchMedia: () => ({ matches: false }) }),
  setTimeout: () => 1, clearTimeout() {}, clearInterval() {},
  fetch: async () => ({ ok: true, json: async () => ({ db_id: 3, title: 'Random' }) })
});
const source = async name => new vm.SourceTextModule(await readFile(new URL(`../Aoede/assets/js/modules/${name}.js`, import.meta.url), 'utf8'), { context });
const synthetic = values => new vm.SyntheticModule(Object.keys(values), function () {
  for (const [key, value] of Object.entries(values)) this.setExport(key, value);
}, { context });
const stateModule = await source('state');
await stateModule.link(() => assert.fail('state has no imports')); await stateModule.evaluate();
const state = stateModule.namespace;
const imports = {
  './state.js': stateModule,
  './ui.js': synthetic({ updatePlayButton() {}, updateUIProgress() {}, updateVolumeUI() {}, refreshIcons() {}, setupDraggable() {},
    toggleFullscreenPlayer: () => state.setIsFullscreenPlayerOpen(!state.isFullscreenPlayerOpen), showToast: (message, type) => { if (type === 'error') assert.fail(message); } }),
  './api.js': synthetic({ recordListenEvent: async () => {}, fetchRandomTrack: async () => ({ db_id: 3, title: 'Random' }) }),
  './audio_engine.js': synthetic({ setVolume() {}, resumeIfSuspended() {}, resetFade() {}, fadeIn() {}, applyReplayGain() {}, getCrossfadeSeconds: () => 0 }),
  './offline_store.js': synthetic({ isTrackAvailableOfflineSync: () => false, isTrackAvailableOffline: async () => false })
};
const module = await source('player'); await module.link(name => imports[name]); await module.evaluate();
const player = module.namespace;
player.setupPlayer();
const first = { db_id: 1, title: 'First' }, last = { db_id: 2, title: 'Last' };
const flush = () => new Promise(setImmediate);
function reset(queue = [first, last], index = queue.length - 1) {
  player.setPartyController(null);
  state.setContextQueue(queue); state.setContextIndex(index); state.setUserQueue([]);
  state.setCurrentTrack(queue[index] || last); state.setIsPlaying(true); state.setIsFullscreenPlayerOpen(true);
  state.setRepeatMode('off'); state.setShuffleMode(false);
  Object.assign(audio, { src: '/api/stream/2', currentTime: 42, duration: 120, paused: false, ended: false, _endHandled: false });
  navigator.mediaSession.playbackState = 'playing';
  player.syncPlayerShellVisibility();
}
for (const theme of ['classic', 'glass', 'vitrine']) {
  document.documentElement.dataset.theme = theme;
  for (const queue of [[first, last], [last], []]) {
    for (const paused of [false, true]) {
      reset(queue); audio.paused = paused; state.setIsPlaying(!paused);
      const track = state.currentTrack;
      await player.playNext(); await actions.get('nexttrack')(); await player.playNext(new Event('click'));
      assert.equal(state.currentTrack, track); assert.equal(state.isPlaying, !paused);
      assert.equal(audio.src, '/api/stream/2'); assert.equal(audio.currentTime, 42); assert.equal(audio.paused, paused);
      assert.equal(state.isFullscreenPlayerOpen, true); assert.equal(footer.classList.contains('player-hidden'), false);
    }
  }
}
reset(); state.setRepeatMode('all'); await player.playNext(); await flush();
assert.equal(state.currentTrack, first); assert.equal(state.contextIndex, 0); assert.equal(audio.paused, false);
reset(); state.setUserQueue([first]); await player.playNext(); await flush();
assert.equal(state.currentTrack, first); assert.equal(state.contextIndex, 1); assert.equal(state.userQueue.length, 0);
reset(); state.setShuffleMode(true); await player.playNext(); await flush();
assert.equal(state.currentTrack.db_id, 3);
reset(); state.setRepeatMode('one'); await player.playNext(); assert.equal(audio.currentTime, 42);
audio.currentTime = 120; audio.dispatchEvent(new Event('ended')); await flush();
assert.equal(state.currentTrack, last); assert.equal(audio.currentTime, 0); assert.equal(audio.paused, false);
reset(); let partyNext = 0;
player.setPartyController({ isActive: () => true, control(action) { assert.equal(action, 'next'); partyNext++; } });
await player.playNext(); assert.equal(partyNext, 1); assert.equal(state.currentTrack, last);
// The final note must play fully; final cleanup also handles background and missing ended events.
for (const finish of ['ended', 'timeupdate', 'visibilitychange']) {
  reset(); storage.set('aoede.playback.session.v1', 'old session');
  audio.currentTime = 119.9; audio.dispatchEvent(new Event('timeupdate'));
  assert.equal(state.currentTrack, last); assert.equal(audio._endHandled, false); assert.equal(audio.paused, false);
  audio.currentTime = 120;
  if (finish === 'visibilitychange') { audio.ended = true; document.dispatchEvent(new Event(finish)); }
  else audio.dispatchEvent(new Event(finish));
  await flush(); // Includes the old resource's queued pause event.
  assert.equal(state.currentTrack, null); assert.equal(state.isPlaying, false); assert.equal(audio.paused, true);
  assert.equal(audio.src, ''); assert.equal(state.isFullscreenPlayerOpen, false);
  assert.equal(navigator.mediaSession.playbackState, 'none'); assert.equal(navigator.mediaSession.metadata, null);
  assert.equal(storage.has('aoede.playback.session.v1'), false); assert.equal(footer.classList.contains('player-hidden'), true);
}
console.log('PASS: final-track Next is inert across themes, queues and media controls; repeat, shuffle, party and natural/background completion remain functional.');
