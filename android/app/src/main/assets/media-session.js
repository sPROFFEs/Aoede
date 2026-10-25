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
  var lastControls = '';
  var pendingPlayer = false;
  var spectrum = null;
  var spectrumFailed = '';

  function closeSpectrum() {
    if (!spectrum) return;
    var old = spectrum;
    spectrum = null;
    if (old.source) old.source.disconnect();
    old.stream.getTracks().forEach(function (track) { track.stop(); });
    old.context.close().catch(function () {});
  }
  // Sample a copy of this player's audio. Never reroute its output, request a microphone,
  // or connect the copy to speakers (which would play the same track twice).
  window.__aoedeIslandLevels = function (active) {
    if (!active || blocked || !primary || primary.paused || primary.ended || !audible) {
      closeSpectrum(); return null;
    }
    var url = primary.currentSrc || primary.src;
    if (spectrum && (spectrum.element !== primary || spectrum.url !== url)) closeSpectrum();
    try {
      if (!spectrum) {
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx || !primary.captureStream || spectrumFailed === url) return null;
        var stream = primary.captureStream();
        if (!stream.getAudioTracks().length) {
          stream.getTracks().forEach(function (track) { track.stop(); });
          return null;
        }
        var context;
        try { context = new Ctx(); }
        catch (error) { stream.getTracks().forEach(function (track) { track.stop(); }); throw error; }
        spectrum = { context: context, stream: stream, element: primary, url: url };
        spectrum.analyser = context.createAnalyser();
        spectrum.analyser.fftSize = 1024;
        spectrum.analyser.smoothingTimeConstant = .45;
        spectrum.analyser.minDecibels = -80;
        spectrum.analyser.maxDecibels = -15;
        spectrum.data = new Uint8Array(spectrum.analyser.frequencyBinCount);
        spectrum.source = context.createMediaStreamSource(stream);
        spectrum.source.connect(spectrum.analyser);
      }
      if (!spectrum.stream.getAudioTracks().some(function (track) {
        return track.readyState === 'live' && track.enabled && !track.muted;
      })) return null;
      if (spectrum.context.state !== 'running') {
        spectrum.context.resume().catch(function () {});
        return null;
      }
      spectrum.analyser.getByteFrequencyData(spectrum.data);
      var edges = [60, 250, 600, 1400, 3600, 10000];
      var binHz = spectrum.context.sampleRate / spectrum.analyser.fftSize;
      var volume = primary.muted ? 0 : Math.max(0, Math.min(1, primary.volume));
      return edges.slice(0, 5).map(function (edge, band) {
        var start = Math.min(spectrum.data.length - 1, Math.max(1, Math.floor(edge / binHz)));
        var end = Math.min(spectrum.data.length, Math.max(start + 1, Math.ceil(edges[band + 1] / binHz)));
        var power = 0;
        for (var i = start; i < end; i++) power += Math.pow(spectrum.data[i] / 255, 2);
        return Math.round(Math.sqrt(power / (end - start)) * volume * 1000) / 1000;
      });
    } catch (_) {
      closeSpectrum(); spectrumFailed = url; return null;
    }
  };
  window.addEventListener('pagehide', closeSpectrum);

  function pushControls() {
    var favorite = document.getElementById('fs-btn-favorite');
    var shuffle = document.getElementById('btn-shuffle');
    var repeat = document.getElementById('btn-repeat');
    var hasTrack = !!(metadata && metadata.title && primary && (primary.currentSrc || primary.src));
    var party = !!document.querySelector('.player-footer.party-player');
    var enabled = function (button, callback) { return hasTrack && !!button && !button.disabled && typeof callback === 'function'; };
    var one = repeat && repeat.querySelector('[data-lucide="repeat-1"],.lucide-repeat-1');
    var message = { type: 'controls',
      canFavorite: enabled(favorite, window.toggleFavoriteFromPlayer),
      favorite: !!(favorite && (favorite.classList.contains('active') || favorite.classList.contains('liked'))),
      canShuffle: !party && enabled(shuffle, window.toggleShuffle),
      shuffle: !!(shuffle && shuffle.classList.contains('active-control')),
      canRepeat: !party && enabled(repeat, window.toggleRepeat),
      repeat: one ? 'one' : repeat && repeat.classList.contains('active-control') ? 'all' : 'off' };
    var value = JSON.stringify(message);
    if (value !== lastControls) { lastControls = value; send(message); }
  }
  function openPlayer() {
    if (!pendingPlayer) return;
    var panel = document.getElementById('fullscreen-player');
    if (!panel || typeof window.toggleFullscreenPlayer !== 'function') return;
    pendingPlayer = false;
    if (!panel.classList.contains('open')) window.toggleFullscreenPlayer();
    if (typeof window.showFullscreenCover === 'function') window.showFullscreenCover();
  }

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
    if (event.target.closest('#play-pause-btn,#fs-play-pause-btn,#story-pause,#story-sound-enable,[onclick*="resumePartyAudio"],[onclick*="playTrack"],[onclick*="playNext"],[onclick*="playPrev"],[onclick*="playFromView"],[onclick*="playPlaylist"],[onclick*="playFederatedTrack"],[onclick*="playRadio"],[onclick*="playSavedRadio"],[onclick*="playDiscoveryPreview"],[onclick*="handleCardClick"]')) {
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
      offline: Boolean(window.AOEDE_NATIVE_OFFLINE || navigator.onLine === false),
      artwork: cover.slice(0, cover.indexOf('data:image/') === 0 ? 192 * 1024 : 2048) });
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
        else {
          if (action === 'play' || action === 'nexttrack' || action === 'previoustrack') resumeLocal();
          callback(details);
        }
      } : null); } catch (_) {}
    }
  };
  session.setPositionState = function (position) {
    if (originalPosition) { try { originalPosition(position); } catch (_) {} }
    scheduleState();
  };

  window.__aoedeMediaCommand = function (action, positionMs) {
    if (action === 'openplayer') { pendingPlayer = true; openPlayer(); return; }
    if (action === 'favorite' || action === 'shuffle' || action === 'repeat') {
      var button = document.getElementById(action === 'favorite' ? 'fs-btn-favorite' : 'btn-' + action);
      if (button && !button.disabled && (action === 'favorite' || !document.querySelector('.player-footer.party-player'))) button.click();
      pushControls();
      return;
    }
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
    pushControls();
    openPlayer();
    var element = document.getElementById('audio-player') || document.querySelector('audio');
    if (!element || element === primary) return;
    primary = element;
    audible = !element.paused && element.readyState >= 3;
    ['play', 'playing', 'pause', 'ended', 'waiting', 'stalled', 'emptied', 'error', 'timeupdate', 'loadedmetadata', 'seeked', 'ratechange'].forEach(function (event) {
      element.addEventListener(event, function () {
        if (primary !== element) return;
        if (event === 'playing') { audible = true; failed = false; }
        if (['pause', 'ended', 'waiting', 'emptied', 'error'].indexOf(event) >= 0) audible = false;
        if (!audible) closeSpectrum();
        if (event === 'emptied' || event === 'play') spectrumFailed = '';
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
  function observe() { if (document.documentElement) { observer.observe(document.documentElement, { childList: true, subtree: true,
    attributes: true, attributeFilter: ['class', 'aria-pressed', 'disabled', 'data-lucide'] }); scan(); } }
  if (document.documentElement) observe(); else document.addEventListener('DOMContentLoaded', observe, { once: true });
  pushMetadata();
  send({ type: 'ready' });
})();
