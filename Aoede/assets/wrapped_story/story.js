/* global window, document, fetch, Audio, MediaMetadata, navigator */

(function () {
  const state = {
    year: Number(window.WRAPPED_STORY?.year || new Date().getFullYear()),
    username: window.WRAPPED_STORY?.username || '',
    slides: [],
    index: 0,
    paused: false,
    userPaused: false,
    closed: false,
    timers: new Map(),
    bgFadeTimer: null,
    intervalMs: 10000,
    currentAudio: null,
    preloadedAudios: new Map(),
    preview: null,
    activePreviewToken: 0,
    finishIntro: null,
    introVisible: false,
    introAudio: new Audio('/assets/wrapped_story/audio/light-transition.mp3'),
    backgroundAudio: new Audio('/assets/wrapped_story/audio/wrapped-background.mp3'),
    loudness: {
      context: null,
      compressor: null,
      analyser: null,
      gain: null,
      data: null,
      sourceByAudio: new WeakMap(),
      timer: null,
      targetDb: -20,
      currentGain: 1,
      enabled: false
    },
    keydownHandler: null
  };
  const maxReasonableMinutes = 365 * 24 * 60;
  const repeatTrackMs = 10000;
  const PREVIEW_BASE_VOLUME_TOP = 0.27;
  const PREVIEW_BASE_VOLUME_REPEAT = 0.24;
  const BACKGROUND_VOLUME_NORMAL = 0.12;
  const BACKGROUND_VOLUME_DUCK = 0.045;

  const root = document.getElementById('story-root');
  const bg = document.getElementById('story-bg');
  const stage = document.getElementById('story-stage');
  const progress = document.getElementById('story-progress');
  const prev = document.getElementById('story-prev');
  const next = document.getElementById('story-next');
  const pause = document.getElementById('story-pause');
  const close = document.getElementById('story-close');
  const resumeTop = document.getElementById('story-resume-top');

  state.backgroundAudio.loop = true;
  state.backgroundAudio.volume = 0;
  state.introAudio.volume = 0.54;

  function esc(value) {
    return String(value ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#039;');
  }

  function number(value) {
    const parsed = Number(value || 0);
    if (!Number.isFinite(parsed) || parsed < 0) return '0';
    return parsed.toLocaleString();
  }

  function durationUnit(value, singular, plural) {
    const formatted = Number.isInteger(value)
      ? value.toLocaleString()
      : value.toLocaleString(undefined, { maximumFractionDigits: 1 });
    return {
      value: formatted,
      unit: value === 1 ? singular : plural,
      label: `${formatted} ${value === 1 ? singular : plural}`
    };
  }

  function listeningDuration(minutes) {
    const parsed = Number(minutes || 0);
    if (!Number.isFinite(parsed) || parsed <= 0) return durationUnit(0, 'second', 'seconds');

    const seconds = parsed * 60;
    if (seconds < 60) return durationUnit(Math.round(seconds), 'second', 'seconds');
    if (parsed < 60) return durationUnit(Math.round(parsed), 'minute', 'minutes');

    const hours = parsed / 60;
    if (hours < 24) return durationUnit(Number(hours.toFixed(hours >= 10 ? 0 : 1)), 'hour', 'hours');

    const days = hours / 24;
    if (days < 365) return durationUnit(Number(days.toFixed(days >= 10 ? 0 : 1)), 'day', 'days');

    const years = days / 365;
    return durationUnit(Number(years.toFixed(years >= 10 ? 0 : 1)), 'year', 'years');
  }

  function titleCase(value) {
    const text = String(value || '');
    return text ? text.charAt(0).toUpperCase() + text.slice(1) : '';
  }

  function validMinutes(value) {
    const parsed = Number(value || 0);
    return Number.isFinite(parsed) && parsed > 0 && parsed <= maxReasonableMinutes;
  }

  function validCount(value) {
    const parsed = Number(value || 0);
    return Number.isFinite(parsed) && parsed > 0 && parsed < 1000000;
  }

  function safeMinutes(value) {
    return validMinutes(value) ? Number(value) : 0;
  }

  function safeStreamCount(value) {
    return validCount(value) ? Number(value) : 0;
  }

  function hasPlayableTrack(track) {
    return Boolean(track?.db_id || track?.id);
  }

  function avatarUrl(username) {
    return `/user/avatar/${encodeURIComponent(username || '?')}`;
  }

  function trackCover(track) {
    const id = track?.db_id || track?.id;
    return id ? `/api/cover/${encodeURIComponent(id)}` : '/static/img/default_cover.png';
  }

  function trackStream(track) {
    const id = track?.db_id || track?.id;
    return id ? `/api/stream/${encodeURIComponent(id)}` : '';
  }

  function initials(value) {
    return String(value || '?')
      .trim()
      .slice(0, 2)
      .toUpperCase();
  }

  function artistBadge(artistName, imageUrl) {
    const safeImage = String(imageUrl || '').trim();
    if (safeImage) {
      return `<img class="story-artist-mark" src="${esc(safeImage)}" alt="${esc(artistName || 'Artist')}">`;
    }
    return `<span class="story-artist-mark">${esc(initials(artistName))}</span>`;
  }

  function repeatIcon() {
    return `<svg class="story-title-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="m17 2 4 4-4 4"></path>
      <path d="M3 11V9a3 3 0 0 1 3-3h15"></path>
      <path d="m7 22-4-4 4-4"></path>
      <path d="M21 13v2a3 3 0 0 1-3 3H3"></path>
    </svg>`;
  }

  function icon(name) {
    const icons = {
      prev: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 18 9 12l6-6"></path></svg>',
      next: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"></path></svg>',
      pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14"></path><path d="M16 5v14"></path></svg>',
      play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"></path></svg>'
    };
    return icons[name] || '';
  }

  function updateWrappedMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.playbackState = state.paused ? 'paused' : 'playing';
    } catch {
      /* Optional WebView API. */
    }
  }

  function setupWrappedMediaSession() {
    if (!('mediaSession' in navigator)) return;
    const metadata = {
      title: `Aoede Wrapped ${state.year}`,
      artist: state.username || 'Aoede',
      album: 'Wrapped'
    };
    try {
      navigator.mediaSession.metadata = typeof MediaMetadata === 'function' ? new MediaMetadata(metadata) : metadata;
    } catch {
      /* Unsupported native metadata must not prevent story navigation. */
    }
    ['play', 'pause'].forEach((action) => {
      try {
        navigator.mediaSession.setActionHandler(action, () => {
          setStoryPaused(action === 'pause');
        });
      } catch {
        /* Some WebViews expose only part of MediaSession. */
      }
    });
    ['previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto'].forEach((action) => {
      try {
        navigator.mediaSession.setActionHandler(action, null);
      } catch {
        // Some browsers expose only a subset of MediaSession actions.
      }
    });
    updateWrappedMediaSession();
  }

  function fadeAudio(audio, targetVolume, duration = 900) {
    window.clearInterval(state.bgFadeTimer);
    const startVolume = Number(audio.volume || 0);
    const startedAt = window.performance.now();
    state.bgFadeTimer = window.setInterval(() => {
      const pct = Math.min(1, (window.performance.now() - startedAt) / duration);
      audio.volume = Math.max(0, Math.min(1, startVolume + (targetVolume - startVolume) * pct));
      if (pct >= 1) window.clearInterval(state.bgFadeTimer);
    }, 50);
  }

  function startBackgroundAudio() {
    if (state.paused || state.closed || state.introVisible) return;
    state.backgroundAudio
      .play()
      .then(() => {
        if (state.paused || state.closed) state.backgroundAudio.pause();
        else
          fadeAudio(
            state.backgroundAudio,
            state.preview && !state.preview.finished ? BACKGROUND_VOLUME_DUCK : BACKGROUND_VOLUME_NORMAL,
            1200
          );
      })
      .catch(showSoundPrompt);
  }

  function stopPreviewLoudnessNormalization() {
    const loudness = state.loudness;
    window.clearInterval(loudness.timer);
    loudness.timer = null;
    if (loudness.gain && loudness.context) {
      try {
        loudness.gain.gain.setTargetAtTime(1, loudness.context.currentTime, 0.06);
      } catch {
        loudness.gain.gain.value = 1;
      }
    }
    loudness.currentGain = 1;
  }

  function ensurePreviewLoudnessNodes(audio) {
    const loudness = state.loudness;
    if (!window.AudioContext && !window.webkitAudioContext) return false;

    if (!loudness.context) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      loudness.context = new Ctx();
      loudness.compressor = loudness.context.createDynamicsCompressor();
      loudness.compressor.threshold.value = -24;
      loudness.compressor.knee.value = 18;
      loudness.compressor.ratio.value = 3;
      loudness.compressor.attack.value = 0.003;
      loudness.compressor.release.value = 0.2;
      loudness.analyser = loudness.context.createAnalyser();
      loudness.analyser.fftSize = 2048;
      loudness.data = new Float32Array(loudness.analyser.fftSize);
      loudness.gain = loudness.context.createGain();
      loudness.gain.gain.value = 1;
      loudness.compressor.connect(loudness.analyser);
      loudness.analyser.connect(loudness.gain);
      loudness.gain.connect(loudness.context.destination);
    }

    if (!loudness.sourceByAudio.has(audio)) {
      try {
        const source = loudness.context.createMediaElementSource(audio);
        source.connect(loudness.compressor);
        loudness.sourceByAudio.set(audio, source);
      } catch {
        return false;
      }
    }
    loudness.enabled = true;
    if (loudness.context.state === 'suspended') {
      loudness.context.resume().catch(() => {});
    }
    return true;
  }

  function startPreviewLoudnessNormalization(audio) {
    if (!ensurePreviewLoudnessNodes(audio)) return;
    const loudness = state.loudness;
    stopPreviewLoudnessNormalization();
    loudness.currentGain = 1;
    loudness.timer = window.setInterval(() => {
      if (state.currentAudio !== audio || audio.paused) return;
      loudness.analyser.getFloatTimeDomainData(loudness.data);
      let sumSquares = 0;
      for (let i = 0; i < loudness.data.length; i += 1) {
        const sample = loudness.data[i];
        sumSquares += sample * sample;
      }
      const rms = Math.sqrt(sumSquares / loudness.data.length);
      const db = 20 * Math.log10(Math.max(rms, 1e-8));
      let desiredGain = Math.pow(10, (loudness.targetDb - db) / 20);
      desiredGain = Math.min(2.1, Math.max(0.45, desiredGain));
      loudness.currentGain = loudness.currentGain + (desiredGain - loudness.currentGain) * 0.16;
      try {
        loudness.gain.gain.setTargetAtTime(loudness.currentGain, loudness.context.currentTime, 0.07);
      } catch {
        loudness.gain.gain.value = loudness.currentGain;
      }
    }, 130);
  }

  // One clock for slide, preview and repeat slots: pauses preserve their remaining time.
  function clearTimer(name) {
    window.clearTimeout(state.timers.get(name)?.id);
    state.timers.delete(name);
  }

  function armTimer(name, timer) {
    timer.startedAt = window.performance.now();
    timer.id = window.setTimeout(() => {
      state.timers.delete(name);
      if (!state.closed) timer.callback();
    }, timer.remaining);
  }

  function setTimer(name, callback, remaining) {
    clearTimer(name);
    const timer = { callback, remaining, startedAt: 0, id: null };
    state.timers.set(name, timer);
    if (!state.paused && !state.closed) armTimer(name, timer);
  }

  function showSoundPrompt() {
    if (state.closed || document.getElementById('story-sound-enable')) return;
    const button = document.createElement('button');
    button.id = 'story-sound-enable';
    button.className = 'story-sound-enable';
    button.type = 'button';
    button.textContent = 'Enable sound';
    button.addEventListener(
      'click',
      () => {
        button.remove();
        setStoryPaused(false);
        resumePreview();
        startBackgroundAudio();
      },
      { once: true }
    );
    root.appendChild(button);
  }

  function reducedMotion() {
    return (
      window.matchMedia('(prefers-reduced-motion: reduce)').matches ||
      document.documentElement.dataset.nativeEffects === 'reduced'
    );
  }

  function renderIntro() {
    if (reducedMotion()) return Promise.resolve();
    return new Promise((resolve) => {
      const intro = document.createElement('div');
      intro.className = 'story-intro';
      intro.innerHTML = `<div class="story-intro-orbit" aria-hidden="true"><i></i><i></i><i></i></div>
        <div class="story-intro-identity"><span class="story-intro-eyebrow">Your year in sound</span>
        <div class="story-intro-word" aria-label="Aoede">${'AOEDE'
          .split('')
          .map((letter, index) => `<span aria-hidden="true" style="--letter:${index}">${letter}</span>`)
          .join('')}</div>
        <div class="story-intro-edition">WRAPPED <strong>${state.year}</strong></div></div>
        <button class="story-link story-intro-skip" type="button">Skip intro</button>`;
      state.introVisible = true;
      const finish = () => {
        clearTimer('intro');
        clearTimer('intro-exit');
        intro.remove();
        state.introVisible = false;
        state.finishIntro = null;
        state.introAudio.pause();
        resolve();
      };
      state.finishIntro = finish;
      intro.querySelector('button').addEventListener('click', finish);
      root.appendChild(intro);
      if (!state.paused) state.introAudio.play().catch(() => {});
      setTimer(
        'intro',
        () => {
          intro.classList.add('is-leaving');
          setTimer('intro-exit', finish, 300);
        },
        2100
      );
    });
  }

  function createBackdrop() {
    bg.innerHTML =
      '<i class="story-shape story-ring"></i><i class="story-shape story-ribbon"></i><i class="story-shape story-disc"></i>';
    root.classList.toggle('is-reduced-motion', reducedMotion());
  }

  function setSlideTheme() {
    const palettes = [
      ['#ff9369', '#b994ff'],
      ['#b9ef78', '#76ccba'],
      ['#faba77', '#ff7e98'],
      ['#d1b2ff', '#8c9aff'],
      ['#f4ed83', '#ff9d85']
    ];
    const [a, b] = palettes[state.index % palettes.length];
    root.dataset.slide = String(state.index);
    bg.style.setProperty('--story-panel-a', a);
    bg.style.setProperty('--story-panel-b', b);
    bg.style.setProperty('--story-turn', `${state.index * 27}deg`);
  }

  async function fetchJson(url) {
    try {
      const res = await fetch(url, { credentials: 'same-origin' });
      return res.ok ? await res.json() : null;
    } catch {
      return null;
    }
  }

  function listItems(items, mapper, className = '') {
    if (!items.length) return '<p class="story-copy">No data yet.</p>';
    return `<ol class="story-list ${className}">${items.map(mapper).join('')}</ol>`;
  }

  function trackRow(track, index) {
    return `<li class="story-list-with-art">
      <span class="story-rank">#${index + 1}</span>
      <img class="story-thumb" src="${trackCover(track)}" alt="">
      <strong>${esc(track.title)}</strong>
      <em>${number(track.stream_count)} plays</em>
    </li>`;
  }

  function repeatPreview(track) {
    if (!track) return '';
    return `<div class="story-repeat-now" id="story-repeat-now" aria-live="polite">
      <img src="${trackCover(track)}" alt="">
      <div>
        <span>Now previewing</span>
        <strong>${esc(track.title)}</strong>
        <em>${esc(track.artist || 'Unknown Artist')}</em>
      </div>
    </div>`;
  }

  function userRow(item, index, value) {
    return `<li class="story-list-with-avatar">
      <span class="story-rank">#${index + 1}</span>
      <img class="story-avatar" src="${avatarUrl(item.username)}" alt="">
      <strong>${esc(item.username || 'Unknown')}</strong>
      <em>${esc(value)}</em>
    </li>`;
  }

  function sprintLeader(sprint) {
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return (sprint || [])
      .filter((month) => month.artists?.length)
      .slice(-6)
      .map((month) => ({
        month: months[(Number(month.month) || 1) - 1] || month.month,
        artist: month.artists[0].artist,
        plays: Number(month.artists[0].stream_count || 0),
        artist_image: month.artists[0].artist_image || ''
      }));
  }

  function buildSlides(wrapped, party) {
    const topSongs = wrapped.top_songs_playlist?.tracks || [];
    const topArtists = wrapped.top_artists || [];
    const topSong = topSongs[0];
    const topArtist = topArtists[0];
    const sprint = sprintLeader(wrapped.top_artist_sprint);
    const partyMinutes = party?.most_minutes_listened || [];
    const repeaters = party?.biggest_repeaters || [];
    const listenedMinutes = safeMinutes(wrapped.minutes_listened);
    const personalDuration = listeningDuration(listenedMinutes);
    const trackedListens = safeStreamCount(wrapped.qualified_event_count || wrapped.event_count);
    const topPlayableSongs = topSongs.filter((track) => hasPlayableTrack(track)).slice(0, 100);
    const repeatPlayable = topPlayableSongs.slice(0, 5);
    const validPartyMinutes = partyMinutes.filter((item) => validMinutes(item.minutes_listened)).slice(0, 5);
    const validRepeaters = repeaters.filter((item) => validCount(item.stream_count)).slice(0, 5);
    const hasTopSong = hasPlayableTrack(topSong) && validCount(topSong?.stream_count);
    const hasTopArtist = topArtist && validCount(topArtist.stream_count);
    const hasSprint = sprint.length > 0;

    return [
      {
        kicker: `Aoede Wrapped ${wrapped.year}`,
        title: `<span class="story-user-intro"><img src="${avatarUrl(state.username)}" alt="">${esc(state.username || 'Your year')}</span>`,
        copy: 'A fast pass through what actually played, repeated and survived the year.',
        className: 'story-slide-intro'
      },
      {
        kicker: `${titleCase(personalDuration.unit)} listened`,
        title:
          listenedMinutes > 0
            ? `<span class="story-duration"><span class="story-big-number">${esc(personalDuration.value)}</span><span>${esc(personalDuration.unit)}</span></span>`
            : 'No listening time yet',
        copy:
          listenedMinutes > 0
            ? `${number(trackedListens)} tracked listens across your library.`
            : 'Start listening and this slide will unlock with real stats.',
        className: listenedMinutes > 0 ? '' : 'story-slide-copy'
      },
      {
        kicker: 'Your top song',
        title: hasTopSong
          ? `<span class="story-feature-track"><img src="${trackCover(topSong)}" alt=""><span>${esc(topSong.title)}</span></span>`
          : 'No top song yet',
        copy: hasTopSong
          ? `${esc(topSong.artist || 'Unknown Artist')} - ${number(topSong.stream_count)} plays`
          : 'Play more tracks and this slide will unlock with your #1.',
        className: 'story-slide-feature',
        audioSrc: hasTopSong ? trackStream(topSong) : '',
        audioDuration: hasTopSong ? Number(topSong?.duration || 0) : 0
      },
      {
        kicker: 'Top songs',
        title: `${repeatIcon()}<span>The repeat list</span>`,
        html:
          repeatPlayable.length > 0
            ? `${repeatPreview(repeatPlayable[0])}${listItems(repeatPlayable, trackRow, 'story-list-media story-repeat-list')}`
            : '<p class="story-copy">No repeat list yet. Keep listening to unlock your top replayed tracks.</p>',
        className: 'story-slide-list story-slide-repeat',
        repeatTracks: repeatPlayable,
        durationMs: Math.max(
          state.intervalMs,
          repeatPlayable.filter((track) => trackStream(track)).length * repeatTrackMs
        )
      },
      {
        kicker: 'Top artists',
        title: esc(hasTopArtist ? topArtist.artist : 'No top artists yet'),
        html: listItems(topArtists.filter((artist) => validCount(artist.stream_count)).slice(0, 5), (artist, index) => {
          return `<li class="story-list-with-art">
            <span class="story-rank">#${index + 1}</span>
            ${artistBadge(artist.artist, artist.artist_image)}
            <strong>${esc(artist.artist)}</strong>
            <em>${number(artist.stream_count)} plays</em>
          </li>`;
        }),
        className: 'story-slide-list'
      },
      {
        kicker: 'Top artist sprint',
        title: 'Who led each month',
        html: listItems(hasSprint ? sprint : [], (item) => {
          return `<li class="story-list-with-art">
            <span class="story-rank">${esc(item.month)}</span>
            ${artistBadge(item.artist, item.artist_image)}
            <strong>${esc(item.artist)}</strong>
            <em>${number(item.plays)} plays</em>
          </li>`;
        }),
        className: 'story-slide-list story-slide-sprint'
      },
      {
        kicker: 'Wrapped Party',
        title: 'Most listening time',
        html:
          validPartyMinutes.length > 0
            ? listItems(validPartyMinutes, (item, index) => {
                return userRow(item, index, listeningDuration(item.minutes_listened).label);
              })
            : '<p class="story-copy">No party ranking yet. Ask everyone to listen more and come back later.</p>',
        className: 'story-slide-list'
      },
      {
        kicker: 'Wrapped Party',
        title: 'Biggest repeaters',
        copy: 'Users whose #1 song got the most repeated plays.',
        html:
          validRepeaters.length > 0
            ? listItems(validRepeaters, (item, index) => {
                return userRow(item, index, `${number(item.stream_count)} plays`);
              })
            : '<p class="story-copy">No repeater ranking yet. Replays will appear here once data is stable.</p>',
        className: 'story-slide-list'
      },
      {
        kicker: wrapped.artist_clip?.title || 'A message from Aoede',
        title: 'For the record',
        copy: wrapped.artist_clip?.message || '',
        className: 'story-slide-copy'
      },
      {
        kicker: 'Summary',
        title: 'Your wrapped at a glance',
        copy: 'Save your top songs playlist, then open the full summary.',
        html: `<div class="story-resume-grid">
            <div><span>Listened</span><strong>${esc(personalDuration.label)}</strong></div>
            <div><span>Tracked listens</span><strong>${number(trackedListens)}</strong></div>
            <div><span>Top artist</span><strong>${esc(hasTopArtist ? topArtist.artist : 'No data')}</strong></div>
            <div><span>Top song</span><strong>${esc(hasTopSong ? topSong.title : 'No data')}</strong></div>
            <div><span>Top Songs playlist</span><strong>${number(topPlayableSongs.length)} tracks</strong></div>
        </div>
        <div class="story-actions">
            <button class="story-action" type="button" id="story-save-playlist" ${topPlayableSongs.length ? '' : 'disabled'}>Save Top Songs</button>
            <a class="story-action secondary" id="story-open-summary" href="/wrapped/${state.year}">Summary</a>
        </div>`,
        className: 'story-slide-copy'
      }
    ];
  }

  function slideDuration(slide = state.slides[state.index]) {
    return Number(slide?.durationMs || state.intervalMs);
  }

  function renderProgress(slide) {
    root.style.setProperty('--story-interval', `${slideDuration(slide)}ms`);
    progress.innerHTML = state.slides
      .map((_, index) => {
        const cls = index < state.index ? 'done' : index === state.index ? 'active' : '';
        return `<div class="story-progress-segment ${cls}"><div class="story-progress-fill"></div></div>`;
      })
      .join('');
  }

  function renderSlide(direction = 1) {
    const slide = state.slides[state.index];
    if (!slide || state.closed) return;
    stopAudio();
    renderProgress(slide);
    const article = document.createElement('article');
    article.className = `story-slide ${slide.className || ''} ${direction < 0 ? 'slide-enter-left' : 'slide-enter-right'}`;
    if (state.paused) article.classList.add('is-static');
    article.dataset.slide = String(state.index);
    article.innerHTML = `
        <div class="story-kicker">${esc(slide.kicker)}</div>
        <h1 class="story-title">${slide.title}</h1>
        ${slide.copy ? `<p class="story-copy">${esc(slide.copy)}</p>` : ''}
        ${slide.html || ''}`;
    article.querySelectorAll('.story-list li, .story-resume-grid > div').forEach((item, index) => {
      item.style.setProperty('--item-index', index);
    });
    stage.replaceChildren(article);
    stage.scrollTop = 0;
    setSlideTheme();
    bindSlideActions();
    playSlideAudio(slide);
    startRepeatList(slide);
    prev.disabled = state.index === 0;
    next.disabled = false;
    next.setAttribute('aria-label', state.index === state.slides.length - 1 ? 'Open summary' : 'Next slide');
    scheduleNext();
  }

  function stopAudio(clearRepeat = true) {
    clearTimer('preview');
    if (clearRepeat) clearTimer('repeat');
    state.activePreviewToken += 1;
    if (state.preview) {
      state.preview.audio.removeEventListener('canplay', state.preview.onReady);
      state.preview = null;
    }
    stopPreviewLoudnessNormalization();
    if (state.currentAudio) {
      state.currentAudio.pause();
      if (state.currentAudio.readyState > 0) state.currentAudio.currentTime = 0;
      state.currentAudio = null;
    }
    if (!state.closed) fadeAudio(state.backgroundAudio, BACKGROUND_VOLUME_NORMAL, 600);
  }

  function resumePreview() {
    const preview = state.preview;
    if (!preview || preview.finished || state.paused || state.closed || preview.audio.readyState < 3) return;
    const { audio, token, durationMs, fallbackDuration } = preview;
    if (!preview.positioned) {
      const duration = Number(audio.duration || fallbackDuration || 0);
      if (Number.isFinite(duration) && duration > 18) {
        const maxStart = Math.max(0, duration - durationMs / 1000 - 2);
        audio.currentTime = Math.floor(Math.random() * maxStart);
      }
      preview.positioned = true;
    }
    audio
      .play()
      .then(() => {
        if (state.activePreviewToken !== token || state.closed) return;
        if (state.paused) {
          audio.pause();
          return;
        }
        fadeAudio(state.backgroundAudio, BACKGROUND_VOLUME_DUCK, 450);
        startPreviewLoudnessNormalization(audio);
        updateWrappedMediaSession();
        if (!state.timers.has('preview'))
          setTimer(
            'preview',
            () => {
              preview.finished = true;
              audio.pause();
              stopPreviewLoudnessNormalization();
              fadeAudio(state.backgroundAudio, BACKGROUND_VOLUME_NORMAL, 450);
            },
            durationMs
          );
      })
      .catch(showSoundPrompt);
  }

  function playPreviewAudio(src, durationMs, volume = PREVIEW_BASE_VOLUME_TOP, fallbackDuration = 0) {
    if (!src || state.closed) return;
    // Invalidate loading callbacks before reusing a media element on another slide.
    stopAudio(false);
    let audio = state.preloadedAudios.get(src);
    if (!audio) {
      audio = new Audio();
      audio.preload = 'none';
      audio.src = src;
      state.preloadedAudios.set(src, audio);
    }
    audio.volume = volume;
    state.currentAudio = audio;
    state.preview = {
      audio,
      token: state.activePreviewToken,
      durationMs,
      fallbackDuration,
      positioned: false,
      finished: false,
      onReady: resumePreview
    };
    audio.addEventListener('canplay', resumePreview, { once: true });
    if (audio.readyState >= 3) resumePreview();
    else {
      audio.preload = 'auto';
      audio.load();
    }
  }

  function playSlideAudio(slide) {
    if (!slide.audioSrc) return;
    playPreviewAudio(slide.audioSrc, slideDuration(slide) - 400, PREVIEW_BASE_VOLUME_TOP, slide.audioDuration);
  }

  function startRepeatList(slide) {
    const tracks = (slide.repeatTracks || []).filter((track) => trackStream(track));
    if (!tracks.length) return;

    clearTimer('preview');
    clearTimer('repeat');

    const now = document.getElementById('story-repeat-now');
    const rows = [...stage.querySelectorAll('.story-repeat-list li')];
    const slotMs = repeatTrackMs;
    let index = 0;

    const showTrack = () => {
      if (state.closed || state.slides[state.index] !== slide) return;
      const track = tracks[index % tracks.length];
      rows.forEach((row, rowIndex) => row.classList.toggle('is-playing', rowIndex === index % tracks.length));
      if (now) {
        now.classList.remove('is-changing');
        void now.offsetWidth;
        now.classList.add('is-changing');
        now.innerHTML = `
          <img src="${trackCover(track)}" alt="">
          <div>
            <span>Now previewing</span>
            <strong>${esc(track.title)}</strong>
            <em>${esc(track.artist || 'Unknown Artist')}</em>
          </div>`;
      }
      playPreviewAudio(trackStream(track), slotMs - 350, PREVIEW_BASE_VOLUME_REPEAT, Number(track.duration || 0));
      index += 1;
      if (index < tracks.length) {
        setTimer('repeat', showTrack, slotMs);
      }
    };

    showTrack();
  }

  function bindSlideActions() {
    document.getElementById('story-open-summary')?.addEventListener('click', (event) => {
      event.preventDefault();
      closeStoryToResume();
    });
    const save = document.getElementById('story-save-playlist');
    if (!save) return;
    save.addEventListener('click', async () => {
      if (save.disabled) return;
      save.disabled = true;
      save.textContent = 'Saving…';
      try {
        const res = await fetch(`/api/wrapped/${encodeURIComponent(state.year)}/top-songs/playlist`, {
          method: 'POST',
          credentials: 'same-origin'
        });
        if (!res.ok) throw new Error('Could not save');
        const payload = await res.json();
        try {
          window.sessionStorage.setItem('aoede:lastSavedWrappedPlaylist', String(payload.id || ''));
        } catch {
          /* Storage may be disabled in a private browser. */
        }
        closeStoryToResume();
      } catch {
        save.textContent = 'Try saving again';
        save.disabled = false;
      }
    });
  }

  function scheduleNext() {
    clearTimer('slide');
    if (state.index < state.slides.length - 1) setTimer('slide', () => go(1), slideDuration());
  }

  function go(direction) {
    if (state.introVisible || state.closed || !state.slides.length) return;
    const nextIndex = Math.min(Math.max(state.index + direction, 0), state.slides.length - 1);
    if (nextIndex === state.index) {
      if (direction > 0 && state.index === state.slides.length - 1) closeStoryToResume();
      return;
    }
    state.index = nextIndex;
    renderSlide(direction);
  }

  function syncPaused(forcePaused = false) {
    const paused = state.userPaused || document.hidden || forcePaused;
    if (state.closed || state.paused === paused) return;
    state.paused = paused;
    root.classList.toggle('is-paused', paused);
    pause.innerHTML = icon(paused ? 'play' : 'pause');
    pause.setAttribute('aria-label', paused ? 'Play story' : 'Pause story');
    pause.setAttribute('aria-pressed', String(paused));
    for (const [name, timer] of state.timers) {
      if (paused) {
        window.clearTimeout(timer.id);
        timer.remaining = Math.max(0, timer.remaining - (window.performance.now() - timer.startedAt));
      } else armTimer(name, timer);
    }
    if (paused) {
      state.currentAudio?.pause();
      state.backgroundAudio.pause();
      state.introAudio.pause();
      window.clearInterval(state.bgFadeTimer);
      stopPreviewLoudnessNormalization();
    } else if (state.introVisible) state.introAudio.play().catch(() => {});
    else if (state.slides.length) {
      startBackgroundAudio();
      resumePreview();
    }
    updateWrappedMediaSession();
  }

  function setStoryPaused(paused) {
    state.userPaused = paused;
    syncPaused();
  }

  function teardownWrappedMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      ['play', 'pause', 'previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto'].forEach((action) => {
        try {
          navigator.mediaSession.setActionHandler(action, null);
        } catch {
          // Ignore unsupported actions.
        }
      });
      navigator.mediaSession.playbackState = 'none';
    } catch {
      // Ignore teardown failures.
    }
  }

  function stopStoryAudio() {
    state.closed = true;
    state.finishIntro?.();
    for (const name of state.timers.keys()) clearTimer(name);
    stopAudio();
    window.clearInterval(state.bgFadeTimer);
    state.backgroundAudio.pause();
    state.introAudio.pause();
    teardownWrappedMediaSession();
  }

  function closeStoryToResume() {
    stopStoryAudio();
    window.location.replace(`/wrapped/${state.year}`);
  }

  function closeStory() {
    stopStoryAudio();
    window.location.replace('/portal#home');
  }

  async function init() {
    createBackdrop();
    resumeTop.href = `/wrapped/${state.year}`;
    setupWrappedMediaSession();
    close.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      closeStory();
    });
    prev.addEventListener('click', () => go(-1));
    next.addEventListener('click', () => go(1));
    prev.innerHTML = icon('prev');
    next.innerHTML = icon('next');
    pause.innerHTML = icon('pause');
    prev.setAttribute('aria-label', 'Previous slide');
    next.setAttribute('aria-label', 'Next slide');
    pause.setAttribute('aria-label', 'Pause story');
    prev.disabled = next.disabled = true;
    syncPaused();
    pause.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      setStoryPaused(!state.userPaused);
    });
    state.keydownHandler = (event) => {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.target.closest?.('input, textarea, select, [contenteditable]')) return;
      if (event.key === ' ' && event.target.closest?.('a, button')) return;
      if (event.key === 'ArrowLeft') {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        go(-1);
      }
      if (event.key === 'ArrowRight') {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        go(1);
      }
      if (event.key === ' ') {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        setStoryPaused(!state.userPaused);
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        closeStory();
      }
    };
    window.addEventListener('keydown', state.keydownHandler, true);
    document.addEventListener('visibilitychange', () => syncPaused());
    window.addEventListener('pagehide', (event) => {
      if (event.persisted) {
        syncPaused(true);
        teardownWrappedMediaSession();
      } else stopStoryAudio();
    });
    window.addEventListener('pageshow', (event) => {
      if (event.persisted) {
        setupWrappedMediaSession();
        syncPaused();
      }
    });
    resumeTop.addEventListener('click', (event) => {
      event.preventDefault();
      closeStoryToResume();
    });
    const intro = renderIntro();

    const [wrapped, party] = await Promise.all([
      fetchJson(`/api/wrapped/${encodeURIComponent(state.year)}`),
      fetchJson(`/api/wrapped/${encodeURIComponent(state.year)}/party`)
    ]);

    await intro;
    if (state.closed) return;
    if (!wrapped) {
      stage.innerHTML =
        '<div class="story-error">Could not load Wrapped. Check your connection and <a class="story-link" href="">try again</a>.</div>';
      return;
    }
    if (wrapped.visible === false || wrapped.enabled === false || wrapped.error) {
      stage.innerHTML =
        '<div class="story-error">Not enough listening data yet. Keep playing and come back later.</div>';
      return;
    }

    state.year = Number(wrapped.year || state.year);
    resumeTop.href = `/wrapped/${state.year}`;
    state.slides = buildSlides(wrapped, party);

    setupWrappedMediaSession();
    renderSlide(1);
    if (!state.paused) startBackgroundAudio();
  }

  init().catch(() => {
    stopStoryAudio();
    stage.innerHTML =
      '<div class="story-error">Wrapped could not start. <a class="story-link" href="">Try again</a>.</div>';
  });
})();
