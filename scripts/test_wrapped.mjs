// Run: node scripts/test_wrapped.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../Aoede/assets/wrapped_story/story.js', import.meta.url), 'utf8');
const testSource = source.slice(0, source.indexOf('  init().catch(')) + `
  globalThis.story = {state, init, setTimer, setStoryPaused, syncPaused, stopAudio, stopStoryAudio,
    renderIntro, buildSlides, renderSlide, go, playPreviewAudio, startRepeatList, fetchJson};
})();`;
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

function launch({ reduced = false, ready = 0 } = {}) {
  let now = 0, sequence = 0;
  const timers = new Map(), nodes = new Map(), audios = [];
  class Node {
    constructor() {
      this.dataset = {}; this.attributes = {}; this.children = []; this.innerHTML = '';
      this.style = { setProperty() {} };
      this.listeners = new Map(); this.classes = new Set();
      this.classList = { add: v => this.classes.add(v), remove: v => this.classes.delete(v),
        toggle: (v, on) => on ? this.classes.add(v) : this.classes.delete(v) };
    }
    addEventListener(name, callback) { this.listeners.set(name, callback); }
    setAttribute(name, value) { this.attributes[name] = value; }
    querySelector() { return new Node(); }
    querySelectorAll() { return []; }
    appendChild(child) { this.children.push(child); }
    replaceChildren(...children) { this.children = children; }
    remove() { this.removed = true; }
  }
  class Audio {
    constructor(src = '') {
      this.src = src; this.readyState = ready; this.duration = 120; this.currentTime = 0;
      this.volume = 1; this.paused = true; this.plays = 0; this.loads = 0; this.listeners = new Map();
      audios.push(this);
    }
    addEventListener(name, callback) { this.listeners.set(name, callback); }
    removeEventListener(name, callback) { if (this.listeners.get(name) === callback) this.listeners.delete(name); }
    load() { this.loads++; }
    pause() { this.paused = true; }
    play() { this.plays++; this.paused = false; return Promise.resolve(); }
    canplay() { this.readyState = 3; const callback = this.listeners.get('canplay'); this.listeners.delete('canplay'); callback?.(); }
  }
  const document = {
    hidden: false, documentElement: new Node(), addEventListener() {},
    getElementById(id) {
      if (id === 'story-save-playlist' || id === 'story-sound-enable') return null;
      if (!nodes.has(id)) nodes.set(id, new Node());
      return nodes.get(id);
    },
    createElement: () => new Node()
  };
  const window = {
    WRAPPED_STORY: { year: 2026, username: '<alice>' }, location: { replace(url) { this.replaced = url; } }, performance: { now: () => now },
    matchMedia: () => ({ matches: reduced }), addEventListener() {},
    setTimeout(callback, delay) { const id = ++sequence; timers.set(id, { callback, at: now + delay }); return id; },
    clearTimeout: id => timers.delete(id), setInterval: () => ++sequence, clearInterval() {}
  };
  const context = vm.createContext({ window, document, Audio, navigator: {}, fetch: async () => { throw Error('Offline'); } });
  vm.runInContext(testSource, context);
  function advance(ms) {
    const end = now + ms;
    for (;;) {
      const next = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      now = next[1].at; timers.delete(next[0]); next[1].callback();
    }
    now = end;
  }
  return { ...context.story, window, document, context, nodes, audios, advance, timers };
}

const clock = launch();
let completed = 0;
clock.setTimer('slide', () => completed++, 10000);
clock.advance(2700); clock.setStoryPaused(true); clock.advance(30000);
assert.equal(completed, 0);
assert.equal(clock.state.timers.get('slide').remaining, 7300);
clock.setStoryPaused(false); clock.advance(7299); assert.equal(completed, 0);
clock.advance(1); assert.equal(completed, 1);
clock.document.hidden = true; clock.syncPaused();
clock.setStoryPaused(true); clock.document.hidden = false; clock.syncPaused();
assert.equal(clock.state.paused, true); // Returning to the app must preserve a manual pause.
clock.setStoryPaused(false); assert.equal(clock.state.paused, false);

const media = launch();
media.state.slides = [{}];
assert.equal(media.audios.length, 2); // No eager stream downloads for the repeat list.
media.playPreviewAudio('/api/stream/1', 1000);
const obsolete = media.state.currentAudio;
assert.equal(obsolete.loads, 1);
media.stopAudio(); obsolete.canplay(); await flush();
assert.equal(obsolete.plays, 0); // A late canplay after navigation must never start a track.
media.playPreviewAudio('/api/stream/2', 1000);
const current = media.state.currentAudio;
media.setStoryPaused(true); current.canplay(); await flush();
assert.equal(current.plays, 0);
media.setStoryPaused(false); await flush(); assert.equal(current.plays, 1);
media.advance(400); media.setStoryPaused(true); media.advance(20000);
assert.equal(media.state.timers.get('preview').remaining, 600);
media.setStoryPaused(false); await flush(); media.advance(600);
assert.equal(current.paused, true);
const plays = current.plays;
media.setStoryPaused(true); media.setStoryPaused(false); await flush();
assert.equal(current.plays, plays); // An expired preview must not restart on resume.

const repeat = launch({ ready: 3 });
const tracks = [1, 2, 3].map(id => ({ db_id: id, title: `Song ${id}`, duration: 180 }));
repeat.state.slides = [{ repeatTracks: tracks }];
repeat.startRepeatList(repeat.state.slides[0]); await flush();
repeat.advance(7000); repeat.setStoryPaused(true); repeat.advance(12000);
repeat.setStoryPaused(false); await flush(); repeat.advance(2999);
assert.equal(repeat.state.currentAudio.src, '/api/stream/1');
repeat.advance(1); await flush(); assert.equal(repeat.state.currentAudio.src, '/api/stream/2');
repeat.stopStoryAudio(); repeat.advance(60000);
assert.equal(repeat.timers.size, 0); assert.equal(repeat.state.timers.size, 0);
assert.equal(repeat.state.currentAudio, null);

const intro = launch();
const opening = intro.renderIntro();
assert.equal(intro.state.introVisible, true);
assert.match(intro.nodes.get('story-root').children[0].innerHTML, /aria-label="Aoede"/);
assert.doesNotMatch(intro.nodes.get('story-root').children[0].innerHTML, /data-letter="N"/);
intro.advance(1000); intro.setStoryPaused(true); intro.advance(10000);
assert.equal(intro.state.introVisible, true);
intro.setStoryPaused(false); intro.advance(1400); await opening;
assert.equal(intro.state.introVisible, false);
const skipped = launch({ reduced: true }); await skipped.renderIntro();
assert.equal(skipped.state.introVisible, false); assert.equal(skipped.state.introAudio.plays, 0);

const app = launch({ reduced: true });
const payload = { year: 2026, minutes_listened: 2100, event_count: 50, top_songs_playlist: { tracks },
  top_artists: [{ artist: '<script>bad</script>', stream_count: 10 }], top_artist_sprint: [] };
app.context.fetch = async url => {
  if (url.endsWith('/party')) throw Error('Party unavailable');
  return { ok: true, json: async () => payload };
};
app.context.navigator.mediaSession = { setActionHandler() {} }; // Android bridge without a MediaMetadata constructor.
await app.init();
assert.equal(app.context.navigator.mediaSession.metadata.title, 'Aoede Wrapped 2026');
assert.equal(app.state.slides.length, 10); assert.equal(app.state.index, 0);
assert.equal(app.state.preloadedAudios.size, 0);
app.setStoryPaused(true);
for (let i = 1; i < 10; i++) app.go(1);
assert.equal(app.state.index, 9); assert.equal(app.nodes.get('story-stage').children.length, 1);
assert.match(app.state.slides[4].html, /&lt;script&gt;bad&lt;\/script&gt;/);
app.state.keydownHandler({ key: 'ArrowLeft', target: { closest: selector => selector.includes('button') ? {} : null }, preventDefault() {}, stopPropagation() {}, stopImmediatePropagation() {} });
assert.equal(app.state.index, 8);
app.go(-1); assert.equal(app.state.index, 7); // Rapid navigation cannot leave a stale slide.
app.go(1); app.go(1); assert.equal(app.nodes.get('story-next').disabled, false);
app.go(1); assert.equal(app.window.location.replaced, '/wrapped/2026');
assert.equal(app.state.closed, true);
app.context.fetch = async () => ({ ok: true, json: async () => { throw SyntaxError('HTML'); } });
assert.equal(await app.fetchJson('/api/wrapped/2026'), null);
console.log('PASS: Wrapped clocks, pause/visibility, cancelled previews, repeat continuation, Aoede intro, reduced motion, navigation and partial API failure.');
