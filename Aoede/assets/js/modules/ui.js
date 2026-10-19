/**
 * ui.js - UI Utilities
 * Toast notifications, modals, formatters, draggable controls
 */

import * as state from './state.js';
import * as audioEngine from './audio_engine.js';

const settingsSelections = new Map();

export function initSettingsTabs(scope = document) {
  scope.querySelectorAll('[data-settings-tabs]').forEach((root) => {
    if (root.dataset.tabsInitialized) return;
    const tabs = Array.from(root.querySelectorAll('[data-settings-tab]'));
    const panels = Array.from(root.querySelectorAll('[data-settings-panel]'));
    if (!tabs.length || !panels.length) return;
    root.dataset.tabsInitialized = 'true';
    const key = `aoede-settings:${window.USER_DATA?.username || ''}:${root.dataset.settingsTabs}`;
    const id = `settings-${root.dataset.settingsTabs}`;
    tabs.forEach((tab) => {
      tab.id = `${id}-${tab.dataset.settingsTab}`;
      tab.setAttribute('role', 'tab');
      tab.setAttribute(
        'aria-controls',
        panels
          .filter((panel) => panel.dataset.settingsPanel === tab.dataset.settingsTab)
          .map((panel) => panel.id || `${id}-panel-${panels.indexOf(panel)}`)
          .join(' ')
      );
    });
    panels.forEach((panel, index) => {
      panel.id ||= `${id}-panel-${index}`;
      panel.setAttribute('role', 'tabpanel');
      panel.setAttribute('aria-labelledby', `${id}-${panel.dataset.settingsPanel}`);
      panel.tabIndex = 0;
    });
    function select(tab) {
      tabs.forEach((item) => {
        item.setAttribute('aria-selected', String(item === tab));
        item.tabIndex = item === tab ? 0 : -1;
      });
      panels.forEach((panel) => {
        panel.hidden = panel.dataset.settingsPanel !== tab.dataset.settingsTab;
      });
      settingsSelections.set(key, tab.dataset.settingsTab);
      try {
        window.sessionStorage.setItem(key, tab.dataset.settingsTab);
      } catch (_) {
        /* Wrapper storage may be unavailable. */
      }
    }
    let saved = settingsSelections.get(key);
    try {
      saved ||= window.sessionStorage.getItem(key);
    } catch (_) {
      /* Keep the in-memory selection. */
    }
    select(tabs.find((tab) => tab.dataset.settingsTab === saved) || tabs[0]);
    root.addEventListener('click', (event) => {
      const tab = event.target.closest('[data-settings-tab]');
      if (tabs.includes(tab)) select(tab);
    });
    root.addEventListener('keydown', (event) => {
      const index = tabs.indexOf(event.target.closest('[data-settings-tab]'));
      if (index < 0 || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const next =
        event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? tabs.length - 1
            : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
      select(tabs[next]);
      tabs[next].focus();
    });
  });
}

// === FORMATTERS ===

export function t(key, fallback = '', values = {}) {
  const message = (typeof window !== 'undefined' && window.__I18N__?.[key]) || fallback || key;
  return String(message).replace(/\{(\w+)\}/g, (token, name) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : token
  );
}

export function fmtTime(s) {
  if (!s || isNaN(s) || !isFinite(s)) return '0:00';
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec < 10 ? '0' : ''}${sec}`;
}

export function getGreeting() {
  const h = new Date().getHours();
  if (h < 12) return t('home.greeting_morning', 'Good morning');
  if (h < 18) return t('home.greeting_afternoon', 'Good afternoon');
  return t('home.greeting_evening', 'Good evening');
}

export function escHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** String content inside a single-quoted JS argument in an HTML attribute. */
export function escJs(value) {
  return escHtml(
    String(value ?? '')
      .replace(/\\/g, '\\\\')
      .replace(/'/g, "\\'")
      .replace(/\r/g, '\\r')
      .replace(/\n/g, '\\n')
      .replace(/\u2028/g, '\\u2028')
      .replace(/\u2029/g, '\\u2029')
  );
}

// Scoped lucide icon refresh. lucide.createIcons() with no args walks the
// WHOLE document — on a freshly-rendered home page (hundreds of cards) that
// triggers a layout/reflow Safari iOS can't keep up with → the freeze.
// Pass an Element to scope the work to its subtree.
export function refreshIcons(scope) {
  const root = scope?.querySelectorAll ? scope : document;
  root
    .querySelectorAll(
      'button.modal-close, button.create-menu-close, button.atp-close, button.lyrics-close, button[data-auth-browser-close], .queue-header button[onclick="toggleQueue()"]'
    )
    .forEach((button) => {
      if (!button.getAttribute('aria-label')) button.setAttribute('aria-label', t('common.close', 'Close'));
      if (!button.getAttribute('type')) button.setAttribute('type', 'button');
    });
  if (!window.lucide?.createIcons) return;
  if (scope && scope.querySelectorAll) {
    const nodes = scope.querySelectorAll('[data-lucide]');
    if (nodes.length) window.lucide.createIcons({ nodes });
    return;
  }
  window.lucide.createIcons();
}

/**
 * Spotify-style tab bar shown at the top of every Browse-level surface
 * (Home / Party / Public / Discover / Radios) so users can flip between them
 * without leaving the visual context. Each chip calls loadView() with
 * the corresponding canonical view name; the active one is highlighted
 * based on the argument.
 *
 * Note: 'Discover Radios' is shortened to just 'Radios' here because
 * we now also have a 'Discover' tab for the preview-feed view, and
 * the two side by side were ambiguous.
 */
export function homeTabsBar(activeTab) {
  const tabs = [
    { key: 'all', label: t('tabs.all', 'All'), view: 'home' },
    { key: 'party', label: t('tabs.party', 'Party'), view: 'party' },
    { key: 'public', label: t('tabs.public', 'Public'), view: 'public' },
    { key: 'community', label: t('tabs.community', 'Community'), view: 'community' },
    { key: 'discovery', label: t('tabs.discover', 'Discover'), view: 'discovery' },
    { key: 'discover_radios', label: t('tabs.radios', 'Radios'), view: 'discover_radios' }
  ];
  return `
    <nav class="home-tabs" aria-label="${t('nav.browse', 'Browse')}">
        ${tabs
          .map(
            (t) =>
              `<button type="button" class="home-tab${t.key === activeTab ? ' active' : ''}"${t.key === activeTab ? ' aria-current="page"' : ''}
                       onclick="loadView('${t.view}')">${t.label}</button>`
          )
          .join('')}
    </nav>`;
}

// === TOAST NOTIFICATIONS ===
//
// Spotify-aligned: flat charcoal surface, no blur, green accent for
// success/error color-coding via left border. Supports an optional
// action button (e.g. "Undo") rendered inline to the right of the
// message — matches Spotify's "Added to X" pattern.

let _toastTimer = null;

/**
 * Show a toast notification.
 * @param {string} msg - Message text.
 * @param {'info'|'success'|'error'} type - Controls accent color.
 * @param {{label:string, callback:Function}|null} action - Optional
 *   inline action button (e.g. {label:'Undo', callback:fn}). When
 *   provided, the toast stays for 5s (vs 3s) and dismisses on click.
 */
export function showToast(msg, type = 'info', action = null) {
  document.querySelectorAll('.toast-msg').forEach((t) => t.remove());
  if (_toastTimer) {
    clearTimeout(_toastTimer);
    _toastTimer = null;
  }
  const toast = document.createElement('div');
  toast.className = `toast-msg toast-${type}`;
  toast.setAttribute('role', type === 'error' ? 'alert' : 'status');
  if (action) toast.classList.add('toast-msg--action');

  const text = document.createElement('span');
  text.className = 'toast-msg-text';
  text.innerText = msg;
  toast.appendChild(text);

  if (action) {
    const btn = document.createElement('button');
    btn.className = 'toast-msg-action';
    btn.innerText = action.label;
    btn.onclick = () => {
      _dismissToast(toast);
      try {
        action.callback();
      } catch (e) {
        console.error('[toast] action error:', e);
      }
    };
    toast.appendChild(btn);
  }

  document.body.appendChild(toast);
  requestAnimationFrame(() => toast.classList.add('toast-msg--show'));
  _toastTimer = setTimeout(() => _dismissToast(toast), action ? 5000 : 3000);
}

function _dismissToast(toast) {
  if (!toast || !toast.parentNode) return;
  if (_toastTimer) {
    clearTimeout(_toastTimer);
    _toastTimer = null;
  }
  toast.classList.remove('toast-msg--show');
  setTimeout(() => toast.remove(), 300);
}

// === MODAL UTILITIES ===

let modalReturnFocus = null;

export function confirmDialog({ title = 'Confirm', message, confirmLabel = 'Continue', tone = 'danger' }) {
  closeModal();
  return new Promise((resolve) => {
    let confirmed = false;
    document.addEventListener('aoede:modalclosed', () => resolve(confirmed), { once: true });
    const container = document.getElementById('modal-container');
    container.innerHTML = `
      <div class="modal-overlay">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="confirm-dialog-title">
          <h2 id="confirm-dialog-title">${escHtml(title)}</h2>
          <p>${escHtml(message)}</p>
          <div class="modal-actions">
            <button type="button" data-role="cancel" class="btn-secondary">${t('common.cancel', 'Cancel')}</button>
            <button type="button" data-role="confirm" class="${tone === 'danger' ? 'modal-btn-danger' : 'btn-primary'}">${escHtml(confirmLabel)}</button>
          </div>
        </div>
      </div>`;
    const overlay = container.firstElementChild;
    overlay.addEventListener('click', (event) => {
      if (event.target === overlay) closeModal();
    });
    container.querySelector('[data-role="cancel"]').addEventListener('click', closeModal);
    container.querySelector('[data-role="confirm"]').addEventListener('click', () => {
      confirmed = true;
      closeModal();
    });
    focusModal();
  });
}

export function focusModal() {
  const modal = document.querySelector('#modal-container .modal');
  if (!modal) return;
  if (!modalReturnFocus?.isConnected) modalReturnFocus = document.activeElement;
  const focusable = () =>
    Array.from(
      modal.querySelectorAll('a[href], button:not(:disabled), input:not(:disabled), select, [tabindex="0"]')
    ).filter((element) => element.tabIndex >= 0 && element.getClientRects().length);
  modal.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeModal();
    }
    if (event.key !== 'Tab') return;
    const nodes = focusable();
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  });
  focusable()[0]?.focus();
}

export function closeModal() {
  document.getElementById('modal-container').innerHTML = '';
  (modalReturnFocus?.closest('details:not([open])')?.querySelector('summary') || modalReturnFocus)?.focus();
  modalReturnFocus = null;
  document.dispatchEvent(new Event('aoede:modalclosed'));
}

// === VOLUME MUTE TOGGLE ===

let savedVolume = 0.7;

function _persistVolumeIfWired() {
  if (typeof window.aoedeOnVolumeChange === 'function') {
    window.aoedeOnVolumeChange(audioEngine.getVolume());
  }
}

export function toggleMute() {
  if (audioEngine.getVolume() > 0) {
    savedVolume = audioEngine.getVolume();
    audioEngine.setVolume(0);
  } else {
    audioEngine.setVolume(savedVolume);
  }

  updateVolumeUI();
  _persistVolumeIfWired();
}

// === VOLUME NUDGE (keyboard shortcuts) ===

export function nudgeVolume(delta) {
  audioEngine.setVolume(audioEngine.getVolume() + delta);
  updateVolumeUI();
  _persistVolumeIfWired();
}

export function updateVolumeUI() {
  const pct = audioEngine.getVolume() * 100;
  document.querySelectorAll('.volume-bar-fill').forEach((fill) => {
    fill.style.width = `${pct}%`;
  });
  document.querySelectorAll('.volume-knob').forEach((knob) => {
    knob.style.left = `${pct}%`;
    knob.style.transform = 'translate(-50%, -50%)';
  });
  document.querySelectorAll('#volume-bar, #fs-volume-bar').forEach((bar) => {
    bar.setAttribute('aria-valuenow', String(Math.round(pct)));
    bar.setAttribute('aria-valuetext', `${Math.round(pct)}%`);
  });
  document.querySelectorAll('#btn-volume-icon, #fs-volume-icon').forEach((btn) => {
    const muted = String(pct === 0);
    if (btn.dataset.muted !== muted) {
      btn.dataset.muted = muted;
      btn.innerHTML = `<i data-lucide="${pct === 0 ? 'volume-x' : 'volume-2'}"></i>`;
      refreshIcons(btn);
    }
    btn.setAttribute('aria-pressed', muted);
    btn.setAttribute('aria-label', pct === 0 ? t('player.unmute', 'Unmute') : t('player.mute', 'Mute'));
  });
}

// === SLEEP TIMER ===
// Cycles Off → 15 → 30 → 60 min → Off. When it fires, playback pauses.
// ponytail: plain setTimeout — survives view changes (module-level), not reloads.

const SLEEP_STEPS_MIN = [15, 30, 60];
let sleepTimerId = null;
let sleepTimerMinutes = 0;

function _setSleepButtonState(active) {
  document.querySelectorAll('#fs-btn-sleep').forEach((btn) => {
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-pressed', String(active));
    btn.style.color = active ? 'var(--accent, #1DB954)' : '';
  });
}

export function cycleSleepTimer() {
  if (sleepTimerId) {
    clearTimeout(sleepTimerId);
    sleepTimerId = null;
  }

  const idx = SLEEP_STEPS_MIN.indexOf(sleepTimerMinutes);
  const next = idx === -1 ? SLEEP_STEPS_MIN[0] : SLEEP_STEPS_MIN[idx + 1] || 0;
  sleepTimerMinutes = next;

  if (!next) {
    _setSleepButtonState(false);
    showToast('Sleep timer off');
    return;
  }

  sleepTimerId = setTimeout(
    () => {
      sleepTimerId = null;
      sleepTimerMinutes = 0;
      _setSleepButtonState(false);
      if (!state.audio.paused) state.audio.pause();
      showToast('Sleep timer: playback paused');
    },
    next * 60 * 1000
  );
  _setSleepButtonState(true);
  showToast(`Sleep timer: ${next} min`);
}

// === DRAGGABLE CONTROLS ===
// For progress bars and volume sliders with touch support

export function setupDraggable(element, callback) {
  if (!element) return;
  let isDragging = false;
  const knob = element.querySelector('.progress-knob, .volume-knob, .fs-progress-knob');
  element.setAttribute('role', 'slider');
  element.tabIndex = 0;
  element.setAttribute('aria-valuemin', '0');
  element.setAttribute('aria-valuemax', '100');
  element.setAttribute('aria-valuenow', element.getAttribute('aria-valuenow') || '0');
  element.addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    event.stopPropagation();
    if (element.getAttribute('aria-disabled') === 'true') return;
    const value = Number(element.getAttribute('aria-valuenow')) || 0;
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? 100
          : value + (['ArrowLeft', 'ArrowDown'].includes(event.key) ? -5 : 5);
    const pct = Math.max(0, Math.min(100, next)) / 100;
    element.setAttribute('aria-valuenow', String(Math.round(pct * 100)));
    callback(pct, false);
  });

  const getPositionFromEvent = (e) => {
    const rect = element.getBoundingClientRect();
    let clientX = e.clientX;
    if (e.touches && e.touches.length > 0) {
      clientX = e.touches[0].clientX;
    }
    let pct = (clientX - rect.left) / rect.width;
    return Math.max(0, Math.min(1, pct));
  };

  const update = (e) => {
    const pct = getPositionFromEvent(e);
    element.setAttribute('aria-valuenow', String(Math.round(pct * 100)));
    const fill = element.querySelector('.progress-bar-fill, .volume-bar-fill, .fs-progress-fill');
    if (fill) fill.style.width = `${pct * 100}%`;
    if (knob) {
      knob.style.left = `${pct * 100}%`;
      knob.style.transform = `translate(-50%, -50%)`;
    }
    callback(pct, isDragging);
  };

  // Mouse events
  element.addEventListener('mousedown', (e) => {
    if (element.getAttribute('aria-disabled') === 'true') return;
    isDragging = true;
    update(e);
  });

  document.addEventListener('mousemove', (e) => {
    if (isDragging) {
      e.preventDefault();
      update(e);
    }
  });

  document.addEventListener('mouseup', (e) => {
    if (isDragging) {
      isDragging = false;
      callback(getPositionFromEvent(e), false);
    }
  });

  // Touch events for mobile
  element.addEventListener(
    'touchstart',
    (e) => {
      if (element.getAttribute('aria-disabled') === 'true') return;
      isDragging = true;
      update(e);
    },
    { passive: true }
  );

  element.addEventListener(
    'touchmove',
    (e) => {
      if (isDragging) {
        e.preventDefault();
        update(e);
      }
    },
    { passive: false }
  );

  element.addEventListener('touchend', (e) => {
    if (isDragging) {
      isDragging = false;
      const lastTouch = e.changedTouches[0];
      if (lastTouch) {
        const rect = element.getBoundingClientRect();
        let pct = (lastTouch.clientX - rect.left) / rect.width;
        pct = Math.max(0, Math.min(1, pct));
        callback(pct, false);
      }
    }
  });
}

// === FULLSCREEN PLAYER ===

let fullscreenReturnFocus = null;

/** Reuse the same controls and panels without changing playback state. */
export function syncFullscreenPlayerLayout() {
  const panel = document.getElementById('fullscreen-player');
  const stage = document.getElementById('fs-stage');
  if (!panel || !stage) return;
  const embedded = state.isFullscreenPlayerOpen;
  const vitrine = document.documentElement.dataset.theme === 'vitrine';
  const details = document.getElementById('fs-details');
  const lyrics = document.getElementById('lyrics-panel');
  const queue = document.getElementById('queue-panel');
  if (embedded && lyrics?.classList.contains('open') && state.isQueueOpen) window.toggleQueue?.();
  [lyrics, queue].filter(Boolean).forEach((child) => {
    const host = embedded ? (vitrine ? details : stage) : document.body;
    if (child.parentElement !== host) host.appendChild(child);
    child.inert = !child.classList.contains('open');
  });
  const view = lyrics?.classList.contains('open') ? 'lyrics' : queue?.classList.contains('open') ? 'queue' : 'cover';
  panel.dataset.view = embedded ? view : 'cover';
  panel.dataset.playing = String(state.isPlaying);
  const cover = document.getElementById('fs-cover');
  const info = document.getElementById('fs-info');
  const coverHost = vitrine && embedded && view !== 'cover' ? info : stage;
  if (cover && coverHost && cover.parentElement !== coverHost) coverHost.insertBefore(cover, coverHost.firstChild);
  const volume = document.getElementById('fs-volume-panel');
  const volumeHost = vitrine ? document.getElementById('fs-volume-slot') : panel.querySelector('.fs-volume');
  if (volume && volumeHost && volume.parentElement !== volumeHost) volumeHost.appendChild(volume);
  const menu = document.getElementById('fs-more-menu');
  const controls = document.getElementById('fs-controls');
  const actions = document.getElementById('fs-actions');
  if (menu && controls && actions) {
    for (const id of ['fs-btn-shuffle', 'fs-btn-repeat', 'fs-btn-cover', 'fs-btn-sleep']) {
      const button = document.getElementById(id);
      if (!button) continue;
      if (vitrine) {
        if (button.parentElement !== menu) menu.appendChild(button);
      } else if (id === 'fs-btn-shuffle') controls.insertBefore(button, controls.firstChild);
      else if (id === 'fs-btn-repeat') controls.appendChild(button);
      else if (id === 'fs-btn-cover') actions.insertBefore(button, actions.firstChild);
      else actions.appendChild(button);
    }
  }
  if (
    embedded &&
    panel.contains(document.activeElement) &&
    document.activeElement.closest('.lyrics-panel:not(.open), .queue-panel:not(.open)')
  )
    panel.querySelector(vitrine ? '.fs-minimize' : '[data-fs-view="cover"]')?.focus();
  panel.querySelectorAll('[data-fs-view]').forEach((button) => {
    const selected = button.dataset.fsView === panel.dataset.view;
    button.classList.toggle('active', selected);
    button.setAttribute('aria-pressed', String(selected));
  });
  const label = document.getElementById('fs-view-label');
  if (label)
    label.textContent =
      panel.dataset.view === 'cover'
        ? state.currentTrack?.artist || t('player.now_playing', 'Now playing')
        : panel.dataset.view === 'lyrics'
          ? t('player.lyrics', 'Lyrics')
          : t('player.queue', 'Queue');
}

export function showFullscreenCover() {
  window.closeLyricsPanel?.();
  if (state.isQueueOpen) window.toggleQueue?.();
  syncFullscreenPlayerLayout();
}

export function initFullscreenPlayer() {
  const panel = document.getElementById('fullscreen-player');
  if (!panel || panel.dataset.keyboardBound) return;
  panel.dataset.keyboardBound = 'true';
  const volume = panel.querySelector('.fs-volume');
  const more = panel.querySelector('.fs-more');
  panel.addEventListener('click', (event) => {
    // Icon refreshes may detach event.target before this listener runs.
    if (volume && !event.composedPath().includes(volume)) volume.open = false;
    if (more && !event.composedPath().includes(more)) more.open = false;
  });
  panel.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      const disclosure = more?.open ? more : volume?.open ? volume : null;
      if (disclosure) {
        disclosure.open = false;
        disclosure.querySelector('summary')?.focus();
      } else toggleFullscreenPlayer();
    } else if (event.key === 'Tab') {
      const buttons = Array.from(panel.querySelectorAll('button:not(:disabled), summary, [tabindex="0"]')).filter(
        (el) => el.getClientRects().length && !el.closest('[inert]')
      );
      const target =
        event.shiftKey && document.activeElement === buttons[0]
          ? buttons.at(-1)
          : !event.shiftKey && document.activeElement === buttons.at(-1)
            ? buttons[0]
            : null;
      if (target) {
        event.preventDefault();
        target.focus();
      }
    }
  });
  document.getElementById('player-cover')?.addEventListener('keydown', (event) => {
    if (['Enter', ' '].includes(event.key)) {
      event.preventDefault();
      event.stopPropagation();
      toggleFullscreenPlayer();
    }
  });
  syncFullscreenPlayerLayout();
}

export function toggleFullscreenPlayer() {
  const panel = document.getElementById('fullscreen-player');
  if (!panel) return;
  state.setIsFullscreenPlayerOpen(!state.isFullscreenPlayerOpen);
  panel.setAttribute('aria-hidden', String(!state.isFullscreenPlayerOpen));

  if (state.isFullscreenPlayerOpen) {
    fullscreenReturnFocus = document.activeElement;
    panel.classList.add('open');
    syncFullscreenPlayerLayout();
    updateFullscreenPlayButton();
    refreshIcons(panel);
    panel.querySelector('.fs-minimize')?.focus();
  } else {
    showFullscreenCover();
    panel.classList.remove('open');
    const volume = panel.querySelector('.fs-volume');
    if (volume) volume.open = false;
    const more = panel.querySelector('.fs-more');
    if (more) more.open = false;
    syncFullscreenPlayerLayout();
    (fullscreenReturnFocus?.isConnected ? fullscreenReturnFocus : document.getElementById('player-cover'))?.focus();
    fullscreenReturnFocus = null;
  }
}

export function updateFullscreenPlayButton() {
  syncFullscreenPlayerLayout();
  const vitrine = document.documentElement.dataset.theme === 'vitrine';
  for (const [id, modern, classic] of [
    ['fs-btn-previous', 'rewind', 'skip-back'],
    ['fs-btn-next', 'fast-forward', 'skip-forward']
  ]) {
    const button = document.getElementById(id);
    const glyph = vitrine ? modern : classic;
    if (button && button.dataset.glyph !== glyph) {
      button.innerHTML = `<i data-lucide="${glyph}"></i>`;
      button.dataset.glyph = glyph;
    }
  }
  const fsPlayBtn = document.getElementById('fs-play-pause-btn');
  const fsShuffle = document.getElementById('fs-btn-shuffle');
  const fsRepeat = document.getElementById('fs-btn-repeat');
  const fsFavorite = document.getElementById('fs-btn-favorite');
  const fsAdd = document.getElementById('fs-btn-add');
  const local = Boolean(
    state.currentTrack?.is_local || (state.currentTrack?.db_id && state.currentTrack?.fed_instance_id == null)
  );
  if (fsFavorite) fsFavorite.disabled = !local;
  if (fsAdd) fsAdd.disabled = !local && state.currentTrack?.fed_instance_id == null;

  if (fsPlayBtn) {
    fsPlayBtn.innerHTML = `<i data-lucide="${state.isPlaying ? 'pause' : 'play'}"></i>`;
    fsPlayBtn.classList.toggle('is-playing', state.isPlaying);
    fsPlayBtn.setAttribute('aria-label', state.isPlaying ? 'Pause' : 'Play');
    fsPlayBtn.title = state.isPlaying ? 'Pause' : 'Play';
  }

  if (fsShuffle) {
    fsShuffle.classList.toggle('active-control', state.shuffleMode);
    fsShuffle.setAttribute('aria-pressed', String(state.shuffleMode));
  }

  if (fsRepeat) {
    fsRepeat.classList.toggle('active-control', state.repeatMode !== 'off');
    fsRepeat.setAttribute('aria-pressed', String(state.repeatMode !== 'off'));
    if (state.repeatMode === 'one') {
      fsRepeat.innerHTML = `<i data-lucide="repeat-1"></i>`;
    } else {
      fsRepeat.innerHTML = `<i data-lucide="repeat"></i>`;
    }
  }

  if (fsFavorite && state.currentTrack) {
    const trackId = state.currentTrack.db_id || state.currentTrack.id;
    const isFav = state.userFavorites.has(trackId);
    fsFavorite.classList.toggle('active', isFav);
    fsFavorite.setAttribute('aria-pressed', String(isFav));
    if (isFav) {
      fsFavorite.innerHTML = `<i data-lucide="heart" fill="currentColor"></i>`;
    } else {
      fsFavorite.innerHTML = `<i data-lucide="heart"></i>`;
    }
  }

  // Scoped to the fullscreen panel — refreshes ONLY the icons we just
  // rewrote, not every [data-lucide] in the document. Prevents the
  // double-pass race that left the FS repeat/shuffle button stale.
  const fsPanel = document.getElementById('fullscreen-player');
  refreshIcons(fsPanel || document);
}

// === PLAY BUTTON ===

export function updatePlayButton() {
  const btn = document.getElementById('play-pause-btn');
  if (btn) {
    btn.innerHTML = `<i data-lucide="${state.isPlaying ? 'pause' : 'play'}"></i>`;
    btn.classList.toggle('is-playing', state.isPlaying);
    btn.setAttribute('aria-label', state.isPlaying ? 'Pause' : 'Play');
    btn.title = state.isPlaying ? 'Pause' : 'Play';
  }
  updateFullscreenPlayButton();
  // updateFullscreenPlayButton already refreshes the fullscreen panel.
  // Bottom button is one element — scope to it.
  refreshIcons(btn || document);
}

// === PROGRESS UPDATE ===

export function updateUIProgress(current, total) {
  document.dispatchEvent(new CustomEvent('aoede:playbacktime', { detail: { current, total } }));
  if (state.isSeeking) return;

  const bar = document.getElementById('progress-fill');
  const knob = document.querySelector('#progress-bar .progress-knob');
  const currentTime = document.getElementById('time-current');
  const totalTime = document.getElementById('time-total');
  const pct = Number.isFinite(total) && total > 0 ? Math.max(0, Math.min(100, (current / total) * 100 || 0)) : 0;

  if (bar) bar.style.width = `${pct}%`;
  if (knob) knob.style.left = `${pct}%`;
  if (currentTime) currentTime.innerText = fmtTime(current);
  if (totalTime) totalTime.innerText = fmtTime(total);

  // Fullscreen player sync
  const fsBar = document.getElementById('fs-progress-fill');
  const fsKnob = document.querySelector('#fs-progress-bar .fs-progress-knob');
  const fsCurrent = document.getElementById('fs-time-current');
  const fsTotal = document.getElementById('fs-time-total');

  if (fsBar) fsBar.style.width = `${pct}%`;
  if (fsKnob) fsKnob.style.left = `${pct}%`;
  if (fsCurrent) fsCurrent.innerText = fmtTime(current);
  if (fsTotal) fsTotal.innerText = fmtTime(total);
  const remaining = document.getElementById('fs-time-remaining');
  if (remaining) remaining.innerText = `−${fmtTime(Math.max(0, total - current))}`;
  document.querySelectorAll('#progress-bar, #fs-progress-bar').forEach((slider) => {
    slider.setAttribute('aria-valuenow', String(Math.round(Math.max(0, Math.min(100, pct)))));
    slider.setAttribute('aria-valuetext', `${fmtTime(current)} / ${fmtTime(total)}`);
    slider.setAttribute('aria-disabled', String(!Number.isFinite(total) || total <= 0));
  });
}
