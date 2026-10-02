(function () {
  'use strict';
  if (window !== window.top || window.AoedeAndroid || !window.AoedeMedia) return;
  var bridge = window.AoedeMedia;
  var handlers = Object.create(null);
  var requested = 'none';
  var metadata = null;
  var blocked = false;
  var primary = null;
  var audible = false;
  var failed = false;
  var lastState = '';
  var scheduled = false;

  function send(message) { bridge.postMessage(JSON.stringify(message)); }
  function resumeLocal() {
    blocked = false;
    send({ type: 'resume' });
    window.dispatchEvent(new Event('aoede-local-resume'));
  }
  function pauseLocal() {
    blocked = true;
    requested = 'paused';
    window.dispatchEvent(new Event('aoede-local-pause'));
    document.querySelectorAll('audio,video').forEach(function (element) { element.pause(); });
    audible = false;
    pushState();
  }
  window.AoedeAndroid = { version: 2, get isPaused() { return blocked; }, resumeLocal: resumeLocal, pauseLocal: pauseLocal };

  // Also protects old server versions: party sync cannot undo a hardware/noisy pause.
  var originalPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (blocked) return Promise.reject(new DOMException('Playback paused on this device', 'NotAllowedError'));
    return originalPlay.apply(this, arguments);
  };
  document.addEventListener('click', function (event) {
    if (!event.isTrusted || !blocked || !event.target.closest) return;
    if (event.target.closest('#play-pause-btn,#fs-play-pause-btn,[onclick*="resumePartyAudio"],[onclick*="playTrack"],[onclick*="playNext"],[onclick*="playPrev"],[onclick*="playFromView"],[onclick*="playPlaylist"],[onclick*="playFederatedTrack"],[onclick*="playRadio"],[onclick*="playSavedRadio"],[onclick*="playDiscoveryPreview"],[onclick*="handleCardClick"]')) {
      resumeLocal();
    }
  }, true);

  function pushMetadata() {
    var value = metadata || {};
    var covers = value.artwork || [];
    var cover = covers.length ? covers[covers.length - 1].src : '';
    try { cover = cover ? new URL(cover, location.href).href : ''; } catch (_) { cover = ''; }
    send({ type: 'metadata', title: String(value.title || '').slice(0, 512),
      artist: String(value.artist || '').slice(0, 512), album: String(value.album || '').slice(0, 512),
      artwork: cover.slice(0, 2048) });
  }
  function pushState() {
    var state = 'none';
    if (primary) {
      if (blocked && requested === 'none') state = 'none';
      else if (failed) state = 'error';
      else if (blocked) state = 'paused';
      else if (!primary.paused && !primary.ended) state = audible ? 'playing' : 'buffering';
      else if (requested === 'playing') state = 'buffering'; // Preserve the service through track loading.
      else if (requested === 'paused' && (primary.currentSrc || primary.src)) state = 'paused';
    }
    var duration = primary && Number.isFinite(primary.duration) ? Math.max(0, primary.duration) : 0;
    var position = primary && Number.isFinite(primary.currentTime) ? Math.max(0, primary.currentTime) : 0;
    var message = { type: 'state', state: state, position: Math.round(position * 1000),
      duration: Math.round(duration * 1000), rate: primary ? primary.playbackRate : 1 };
    var fingerprint = JSON.stringify(message);
    if (fingerprint !== lastState) { lastState = fingerprint; send(message); }
  }
  function scheduleState() {
    if (scheduled) return;
    scheduled = true;
    Promise.resolve().then(function () { scheduled = false; pushState(); });
  }

  var native = navigator.mediaSession;
  var session = native || {};
  var originalHandler = native && native.setActionHandler && native.setActionHandler.bind(native);
  var originalPosition = native && native.setPositionState && native.setPositionState.bind(native);
  if (!native) Object.defineProperty(navigator, 'mediaSession', { value: session, configurable: true });
  ['metadata', 'playbackState'].forEach(function (name) {
    var descriptor = native && Object.getOwnPropertyDescriptor(Object.getPrototypeOf(native), name);
    var initial = session[name];
    if (name === 'metadata') metadata = initial || null;
    else requested = initial || 'none';
    Object.defineProperty(session, name, {
      configurable: true,
      get: function () { return name === 'metadata' ? metadata : requested; },
      set: function (value) {
        if (descriptor && descriptor.set) { try { descriptor.set.call(session, value); } catch (_) {} }
        if (name === 'metadata') { metadata = value; pushMetadata(); }
        else { requested = value; scheduleState(); }
      }
    });
  });
  session.setActionHandler = function (action, callback) {
    if (callback) handlers[action] = callback; else delete handlers[action];
    send({ type: 'action', action: action, enabled: Boolean(callback) });
    if (originalHandler) {
      try { originalHandler(action, callback ? function (details) {
        if (action === 'pause' || action === 'stop') window.__aoedeMediaCommand(action);
        else { resumeLocal(); callback(details); }
      } : null); } catch (_) {}
    }
  };
  session.setPositionState = function (position) {
    if (originalPosition) { try { originalPosition(position); } catch (_) {} }
    scheduleState();
  };

  window.__aoedeMediaCommand = function (action, positionMs) {
    if (action === 'pause' || action === 'stop') {
      pauseLocal();
      if (action === 'stop') { requested = 'none'; pushState(); send({ type: 'state', state: 'none' }); }
      return;
    }
    if (action === 'play' || action === 'nexttrack' || action === 'previoustrack') resumeLocal();
    var callback = handlers[action];
    if (callback) {
      try { callback(action === 'seekto' ? { seekTime: positionMs / 1000 } : undefined); } catch (_) {}
    } else if (primary) {
      if (action === 'play') { requested = 'playing'; primary.play().catch(function () { requested = 'paused'; pushState(); }); }
      else if (action === 'seekto' && Number.isFinite(positionMs)) primary.currentTime = Math.max(0, positionMs / 1000);
      else if (action === 'nexttrack' || action === 'previoustrack') {
        var button = document.querySelector('[onclick="' + (action === 'nexttrack' ? 'playNext' : 'playPrev') + '()"]');
        if (button) button.click();
      }
    }
    scheduleState();
  };

  function scan() {
    var element = document.getElementById('audio-player') || document.querySelector('audio');
    if (!element || element === primary) return;
    primary = element;
    audible = !element.paused && element.readyState >= 3;
    ['play', 'playing', 'pause', 'ended', 'waiting', 'stalled', 'emptied', 'error', 'timeupdate', 'loadedmetadata', 'seeked', 'ratechange'].forEach(function (event) {
      element.addEventListener(event, function () {
        if (primary !== element) return;
        if (event === 'playing') { audible = true; failed = false; }
        if (['pause', 'ended', 'waiting', 'emptied', 'error'].indexOf(event) >= 0) audible = false;
        if (event === 'emptied' || event === 'play') failed = false;
        if (event === 'error') failed = true;
        scheduleState(); // Read app state after its transition handlers have run.
      });
    });
    ['play', 'pause', 'stop', 'seekto'].forEach(function (action) { send({ type: 'action', action: action, enabled: true }); });
    ['nexttrack', 'previoustrack'].forEach(function (action) {
      if (document.querySelector('[onclick="' + (action === 'nexttrack' ? 'playNext' : 'playPrev') + '()"]')) {
        send({ type: 'action', action: action, enabled: true });
      }
    });
    pushState();
  }
  scan();
  var observer = new MutationObserver(scan);
  function observe() { if (document.documentElement) { observer.observe(document.documentElement, { childList: true, subtree: true }); scan(); } }
  if (document.documentElement) observe(); else document.addEventListener('DOMContentLoaded', observe, { once: true });
  pushMetadata();
  send({ type: 'ready' });
})();
