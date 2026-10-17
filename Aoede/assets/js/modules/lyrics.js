/** Synced/plain lyrics share one panel and the active playback clock. */
import * as state from './state.js';
import * as ui from './ui.js';

let _currentLines = [];
let _currentTrackId = null;
let _activeIdx = -1;
let _hasSynced = false;
let _isVisible = false;
let _fetchAbort = null;
let _frame = null;
let _lastFrame = 0;
let _followAfter = 0;
const PANEL_ID = 'lyrics-panel';
const LRC_TIME = /\[(\d{1,3}):(\d{2}(?:[.:]\d{1,3})?)\]/g;

function parseLRC(text) {
  if (typeof text !== 'string') return [];
  // Positive LRC offsets advance the lyrics, in milliseconds.
  const offset = Number(text.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0) / 1000;
  const out = [];
  for (const raw of text.split(/\r?\n/)) {
    const matches = [...raw.matchAll(LRC_TIME)];
    const line = raw.replace(LRC_TIME, '').trim();
    for (const match of matches) {
      const seconds = Number(match[2].replace(':', '.'));
      if (seconds < 60) out.push({ time: Math.max(0, Number(match[1]) * 60 + seconds - offset), text: line });
    }
  }
  return out.sort((a, b) => a.time - b.time);
}

function lineAt(lines, time) {
  let lo = 0,
    hi = lines.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (lines[mid].time <= time) lo = mid + 1;
    else hi = mid;
  }
  return lo - 1;
}

function playbackTime() {
  const track = state.currentTrack;
  if (track?.source === 'youtube' && !track.is_local && track.fed_instance_id == null && state.ytReady)
    return Number(state.ytPlayer?.getCurrentTime?.()) || 0;
  return Number(state.audio?.currentTime) || 0;
}

export async function loadLyricsFor(track) {
  if (!track) {
    _fetchAbort?.abort();
    _fetchAbort = null;
    _currentTrackId = null;
    _currentLines = [];
    _hasSynced = false;
    _renderEmpty('Nothing playing.');
    return;
  }
  const tid = JSON.stringify([
    track.fed_instance_id ?? track.source ?? 'local',
    track.fed_remote_id ?? track.db_id ?? track.id,
    track.title,
    track.artist,
    track.album,
    track.duration
  ]);
  if (tid === _currentTrackId) {
    tick(undefined, true);
    return;
  }
  _currentTrackId = tid;
  _fetchAbort?.abort();
  const controller = (_fetchAbort = new AbortController());
  _currentLines = [];
  _hasSynced = false;
  _activeIdx = -1;
  _followAfter = 0;
  _renderEmpty('Loading lyrics…');
  // Track metadata belongs to this recording; audio.duration may still be the previous one.
  const rawDuration = Number(track.duration);
  const params = new URLSearchParams({
    title: (track.title || '').slice(0, 300),
    artist: (track.artist || '').slice(0, 200),
    album: (track.album || '').slice(0, 300),
    duration: String(Number.isFinite(rawDuration) && rawDuration > 0 ? Math.min(86400, rawDuration) : 0)
  });
  const timeout = setTimeout(() => controller.abort(), 10000);
  try {
    const res = await fetch(`${state.API}/lyrics?${params}`, { signal: controller.signal });
    if (controller !== _fetchAbort) return;
    if (!res.ok) throw new Error('Could not load lyrics.');
    const data = await res.json();
    if (controller !== _fetchAbort) return;
    if (data.instrumental) {
      _renderEmpty('Instrumental track.');
      return;
    }
    _currentLines = parseLRC(data.synced);
    _hasSynced = _currentLines.length > 0;
    if (!_hasSynced && typeof data.plain === 'string' && data.plain.trim())
      _currentLines = data.plain.split(/\r?\n/).map((text) => ({ time: -1, text }));
    if (!_currentLines.length) {
      _renderEmpty('No lyrics found for this track.');
      return;
    }
    const body = _body();
    if (!body) return;
    body.innerHTML = _currentLines
      .map((line, index) => `<div class="lyric-line" data-idx="${index}">${ui.escHtml(line.text.trim() || '♪')}</div>`)
      .join('');
    body.scrollTop = 0;
    _container().dataset.synced = String(_hasSynced);
    tick(undefined, true);
    _schedule();
  } catch (error) {
    if (controller !== _fetchAbort) return;
    _currentTrackId = null;
    _renderEmpty('Could not load lyrics. Close and reopen to retry.');
  } finally {
    clearTimeout(timeout);
  }
}

function _container() {
  return document.getElementById(PANEL_ID);
}
function _body() {
  return document.querySelector(`#${PANEL_ID} .lyrics-body`);
}
function _renderEmpty(message) {
  const body = _body();
  if (body) body.innerHTML = `<div class="lyrics-empty" role="status">${ui.escHtml(message)}</div>`;
  _container()?.removeAttribute('data-synced');
  const follow = document.getElementById('lyrics-follow');
  if (follow) follow.hidden = true;
}

export function tick(time = playbackTime(), force = false) {
  if (!_isVisible || !_hasSynced || !_currentLines.length || !Number.isFinite(time)) return;
  const idx = lineAt(_currentLines, time);
  if (_followAfter && Date.now() >= _followAfter) {
    _followAfter = 0;
    force = true;
  }
  if (idx === _activeIdx && !force) return;
  _activeIdx = idx;
  const body = _body();
  if (!body) return;
  body.querySelectorAll('.lyric-line.active').forEach((el) => {
    el.classList.remove('active');
    el.removeAttribute('aria-current');
  });
  const line = body.querySelector(`.lyric-line[data-idx="${idx}"]`);
  if (line) {
    line.classList.add('active');
    line.setAttribute('aria-current', 'true');
    if (!_followAfter)
      body.scrollTo({
        top:
          body.scrollTop +
          line.getBoundingClientRect().top -
          body.getBoundingClientRect().top -
          (body.clientHeight - line.clientHeight) / 2,
        behavior: force || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
      });
  }
  const follow = document.getElementById('lyrics-follow');
  if (follow) follow.hidden = !_followAfter;
}

function _schedule() {
  if (_frame != null || !_isVisible || !_hasSynced || !state.isPlaying || document.hidden) return;
  _frame =
    window.requestAnimationFrame?.((now) => {
      _frame = null;
      if (now - _lastFrame >= 80) {
        tick();
        _lastFrame = now;
      }
      _schedule();
    }) ?? null;
}
function _stop() {
  if (_frame != null) window.cancelAnimationFrame?.(_frame);
  _frame = null;
}

export function toggleLyricsPanel() {
  const panel = _container();
  if (!panel) return;
  if (panel.classList.contains('open')) {
    closeLyricsPanel();
    return;
  }
  _isVisible = true;
  if (state.isFullscreenPlayerOpen && state.isQueueOpen) window.toggleQueue?.();
  panel.classList.add('open');
  document.body.classList.add('lyrics-panel-open');
  _followAfter = 0;
  loadLyricsFor(state.currentTrack);
  document.querySelectorAll('.lyrics-toggle-btn').forEach((button) => button.classList.add('active'));
  ui.syncFullscreenPlayerLayout();
  ui.refreshIcons(panel);
  tick(undefined, true);
  _schedule();
}

export function closeLyricsPanel() {
  _container()?.classList.remove('open');
  document.body.classList.remove('lyrics-panel-open');
  _isVisible = false;
  _stop();
  document.querySelectorAll('.lyrics-toggle-btn').forEach((button) => button.classList.remove('active'));
  ui.syncFullscreenPlayerLayout();
}
export function isLyricsPanelOpen() {
  return _isVisible;
}

export function initLyrics() {
  if (_container()) return;
  const panel = document.createElement('aside');
  panel.id = PANEL_ID;
  panel.className = 'lyrics-panel';
  panel.setAttribute('aria-label', ui.t('player.lyrics', 'Lyrics'));
  panel.innerHTML = `
    <header class="lyrics-head"><div class="lyrics-head-meta"><i data-lucide="mic-vocal"></i><span>${ui.escHtml(ui.t('player.lyrics', 'Lyrics'))}</span></div>
    <button class="lyrics-close" type="button" onclick="closeLyricsPanel()" title="Close"><i data-lucide="x"></i></button></header>
    <div class="lyrics-body" tabindex="0" aria-label="Lyrics text"></div>
    <footer class="lyrics-foot"><button type="button" id="lyrics-follow" hidden>Follow current line</button><span class="lyrics-credit">via lrclib.net</span></footer>`;
  document.body.appendChild(panel);
  ui.refreshIcons(panel);
  const hold = () => {
    if (!_hasSynced) return;
    _followAfter = Date.now() + 5000;
    document.getElementById('lyrics-follow').hidden = false;
  };
  _body().addEventListener('wheel', hold, { passive: true });
  _body().addEventListener('touchstart', hold, { passive: true });
  _body().addEventListener('keydown', (event) => {
    if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) hold();
  });
  document.getElementById('lyrics-follow').addEventListener('click', () => {
    _followAfter = 0;
    tick(undefined, true);
  });
  for (const event of ['timeupdate', 'seeked', 'loadedmetadata', 'playing', 'pause'])
    state.audio?.addEventListener(event, () => {
      tick();
      _stop();
      _schedule();
    });
  // The shared progress dispatcher also covers YouTube previews.
  document.addEventListener('aoede:playbacktime', (event) => {
    tick(event.detail.current);
    _schedule();
  });
  document.addEventListener('visibilitychange', () => {
    _stop();
    if (!document.hidden) {
      tick(undefined, true);
      _schedule();
    }
  });
}
export function onTrackChange(track) {
  _stop();
  if (_isVisible || !track) loadLyricsFor(track);
}
