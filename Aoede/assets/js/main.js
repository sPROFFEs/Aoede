/**
 * main.js - Application Entry Point
 * Imports all modules and exposes functions to window
 */

// === MODULE IMPORTS ===
import * as state from './modules/state.js';
import * as ui from './modules/ui.js';
import * as api from './modules/api.js';
import * as player from './modules/player.js';
import * as queue from './modules/queue.js';
import * as search from './modules/search.js';
import * as radio from './modules/radio.js';
import * as favorites from './modules/favorites.js';
import * as playlists from './modules/playlists.js';
import * as downloads from './modules/downloads.js';
import * as views from './modules/views.js';
import * as library from './modules/library.js';
import * as offlineStore from './modules/offline_store.js';
// admin.js is dynamically imported below for is_admin users only.
// Saves ~24 KB on every non-admin page load.
import * as lyrics from './modules/lyrics.js';
import * as audioEngine from './modules/audio_engine.js';
import * as party from './modules/party.js';

function initUserMenu() {
  const userMenu = document.getElementById('user-menu');
  if (!userMenu || userMenu.dataset.bound === 'true') return;
  userMenu.dataset.bound = 'true';

  document.addEventListener('click', (event) => {
    if (!userMenu.hasAttribute('open')) return;
    if (userMenu.contains(event.target)) return;
    userMenu.removeAttribute('open');
  });

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (!userMenu.hasAttribute('open')) return;
    userMenu.removeAttribute('open');
  });
}

// === GLOBAL KEYBOARD SHORTCUTS ===
// Space = play/pause, ←/→ = seek ±10s, ↑/↓ = volume, / = focus search.
// All skipped while typing in an input/textarea/select or with modifiers held.

function isTypingTarget(el) {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

function initKeyboardShortcuts() {
  document.addEventListener('keydown', (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (isTypingTarget(e.target)) return;

    if (e.key === '/') {
      e.preventDefault();
      const input = document.getElementById('search-input');
      if (input) {
        input.focus();
      } else {
        Promise.resolve(views.loadView('search')).then(() => {
          document.getElementById('search-input')?.focus();
        });
      }
      return;
    }

    if (!state.currentTrack) return;

    if (e.code === 'Space') {
      // Let a focused button keep its native Space behavior.
      if (e.target?.tagName === 'BUTTON') return;
      e.preventDefault();
      document.getElementById('play-pause-btn')?.click();
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      if (party.controller.isActive()) party.seekTo(Math.max(0, state.audio.currentTime - 10));
      else state.audio.currentTime = Math.max(0, state.audio.currentTime - 10);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      if (state.audio.duration) {
        const target = Math.min(state.audio.duration - 0.5, state.audio.currentTime + 10);
        if (party.controller.isActive()) party.seekTo(target);
        else state.audio.currentTime = target;
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      ui.nudgeVolume(0.05);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      ui.nudgeVolume(-0.05);
    }
  });
}

// === OFFLINE AWARENESS ===

function updateOfflineUIState(isOffline) {
  if (isOffline) {
    document.body.dataset.offline = 'true';
    if (!document.getElementById('offline-mode-indicator')) {
      const banner = document.createElement('div');
      banner.id = 'offline-mode-indicator';
      banner.className = 'offline-mode-indicator';
      banner.innerHTML = `<i data-lucide="cloud-off" width="14" height="14"></i> <span>Offline Mode — Playing from device storage</span>`;
      document.body.prepend(banner);
      ui.refreshIcons(banner);
    }
  } else {
    document.body.removeAttribute('data-offline');
    document.getElementById('offline-mode-indicator')?.remove();
  }
}

function initOfflineAwareness() {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    updateOfflineUIState(true);
  }

  window.addEventListener('offline', () => {
    updateOfflineUIState(true);
    ui.showToast('You are offline — downloaded tracks are available.', 'info');
    document.querySelectorAll('.track-row').forEach((row) => {
      const idx = row.dataset.idx;
      if (idx !== undefined && state.currentViewList?.[idx]) {
        const item = state.currentViewList[idx];
        const isOfflineReady = offlineStore.isTrackAvailableOfflineSync(item.db_id || item.id);
        if (!isOfflineReady && !item.is_offline) {
          row.classList.add('offline-disabled');
        }
      }
    });
  });
  window.addEventListener('online', async () => {
    updateOfflineUIState(false);
    ui.showToast('Back online — syncing changes.', 'success');
    document.querySelectorAll('.track-row.offline-disabled').forEach((row) => {
      row.classList.remove('offline-disabled');
    });
    try {
      await offlineStore.flushPendingActions();
      await views.loadUserData();
    } catch (e) {
      console.warn('[OFFLINE] Online sync error:', e);
    }
  });
}

// === EXPOSE FUNCTIONS TO WINDOW FOR HTML ONCLICK HANDLERS ===

// State
window.toggleSidebar = state.toggleSidebar;

// UI
window.showToast = ui.showToast;
window.closeModal = ui.closeModal;
window.toggleFullscreenPlayer = ui.toggleFullscreenPlayer;
window.toggleMute = ui.toggleMute;
window.fmtTime = ui.fmtTime;
window.escHtml = ui.escHtml;
window.cycleSleepTimer = ui.cycleSleepTimer;

// Player
window.playTrack = player.playTrack;
window.playNext = player.playNext;
window.playPrev = player.playPrev;
window.playFromView = player.playFromView;
window.playFederatedTrack = player.playFederatedTrack;

// Queue
window.addToQueue = queue.addToQueue;
window.addToQueueCurrent = queue.addToQueueCurrent;
window.removeFromQueue = queue.removeFromQueue;
window.toggleShuffle = queue.toggleShuffle;
window.toggleRepeat = queue.toggleRepeat;
window.toggleQueue = queue.toggleQueue;
window.renderQueue = queue.renderQueue;

// Search
window.handleSearch = search.handleSearch;
window.setSource = search.setSource;
window.executeSearch = search.executeSearch;
window.downloadUrl = search.downloadUrl;

// Radio
window.renderRadio = radio.renderRadio;
window.loadRadioPlaylists = radio.loadRadioPlaylists;
window.loadRadioPlaylist = radio.loadRadioPlaylist;
window.executeRadioSearch = radio.executeRadioSearch;
window.renderSavedRadios = radio.renderSavedRadios;
window.playRadioStream = radio.playRadioStream;
window.injectRadioToNavidrome = radio.injectRadioToNavidrome;
window.loadSidebarRadios = radio.loadSidebarRadios;
window.playSavedRadio = radio.playSavedRadio;
window.deleteSavedRadio = radio.deleteSavedRadio;
window.showDeleteRadioModal = radio.showDeleteRadioModal;

// Favorites
window.toggleFavorite = favorites.toggleFavorite;
window.toggleFavoriteCurrent = favorites.toggleFavoriteCurrent;
window.toggleFavoriteFromPlayer = favorites.toggleFavoriteFromPlayer;

// Playlists
window.showAddToPlaylistModal = playlists.showAddToPlaylistModal;
window.closeAddToPlaylistFlyout = playlists.closeAddToPlaylistFlyout;
window.showCreatePlaylistInline = playlists.showCreatePlaylistInline;
window.createPlaylistInline = playlists.createPlaylistInline;
window._atpBackToList = playlists._atpBackToList;
window.showCreatePlaylistModal = playlists.showCreatePlaylistModal;
window.showCreateMenu = playlists.showCreateMenu;
window.startPlaylistCreation = playlists.startPlaylistCreation;
window.showCollaboratorsModal = playlists.showCollaboratorsModal;
window.updateCollaborator = playlists.updateCollaborator;
window.leaveCollaborativePlaylist = playlists.leaveCollaborativePlaylist;
window.movePlaylistTrack = playlists.movePlaylistTrack;
window.showDeletePlaylistModal = playlists.showDeletePlaylistModal;
window.showEditPlaylistModal = playlists.showEditPlaylistModal;
window.createPlaylist = playlists.createPlaylist;
window.addToPlaylist = playlists.addToPlaylist;
window.removeFromPlaylist = playlists.removeFromPlaylist;
window.deletePlaylist = playlists.deletePlaylist;
window.editPlaylistName = playlists.editPlaylistName;
window.togglePlaylistPublic = playlists.togglePlaylistPublic;
window.copyPublicPlaylist = playlists.copyPublicPlaylist;
window.addToPlaylistCurrent = playlists.addToPlaylistCurrent;
window.showAddToPlaylistFromPlayer = playlists.showAddToPlaylistFromPlayer;
window.playPlaylistInOrder = playlists.playPlaylistInOrder;
window.playPlaylistShuffle = playlists.playPlaylistShuffle;
window.downloadPlaylistOffline = playlists.downloadPlaylistOffline;
window.togglePlaylistOfflineDownload = playlists.togglePlaylistOfflineDownload;
window.showRemoveFromPlaylistModal = playlists.showRemoveFromPlaylistModal;
window.openPlaylistCoverUpload = playlists.openPlaylistCoverUpload;
window.handlePlaylistCoverUpload = playlists.handlePlaylistCoverUpload;
window.showPlaylistCoverTrackModal = playlists.showPlaylistCoverTrackModal;
window.setPlaylistCoverFromTrack = playlists.setPlaylistCoverFromTrack;
window.resetPlaylistCover = playlists.resetPlaylistCover;

// Offline Storage
window.downloadOfflineTrack = offlineStore.downloadTrack;
window.downloadOfflineTrackAction = views.downloadOfflineTrackAction;
window.deleteOfflineTrackAction = views.deleteOfflineTrackAction;
window.deleteOfflinePlaylistAction = views.deleteOfflinePlaylistAction;
window.clearOfflineData = offlineStore.clearOfflineData;
window.clearAllOfflineData = views.clearAllOfflineData;
window.showClearOfflineConfirmModal = views.showClearOfflineConfirmModal;
window.checkOfflineReadinessAction = views.checkOfflineReadinessAction;
window.renderOfflineDownloads = views.renderOfflineDownloads;

// Library
window.switchLibraryKind = library.switchLibraryKind;
window.openLibraryFacet = library.openLibraryFacet;
window.reloadLibraryFacets = library.reloadLibraryFacets;
window.loadMoreLibraryFacets = library.loadMoreLibraryFacets;
window.showCreateSmartPlaylistModal = library.showCreateSmartPlaylistModal;
window.showEditSmartPlaylistModal = library.showEditSmartPlaylistModal;
window.previewSmartPlaylist = library.previewSmartPlaylist;
window.saveSmartPlaylist = library.saveSmartPlaylist;
window.createSmartPlaylist = library.createSmartPlaylist;
window.refreshSmartPlaylist = library.refreshSmartPlaylist;

// Downloads
window.openDownloadsModal = downloads.openDownloadsModal;
window.closeDownloadsModal = downloads.closeDownloadsModal;
window.openDeleteResponsesModal = downloads.openDeleteResponsesModal;
window.closeDeleteResponsesModal = downloads.closeDeleteResponsesModal;
window.handleModalDownload = downloads.handleModalDownload;
window.triggerDownload = downloads.triggerDownload;
window.showDownloadConfirmModal = downloads.showDownloadConfirmModal;
window.executeDownload = downloads.executeDownload;
window.retryDownload = downloads.retryDownload;

// Views
window.loadView = views.loadView;
window.createCard = views.createCard;
window.createMixCard = views.createMixCard;
window.createPlaylistCard = views.createPlaylistCard;
window.createTrackRow = views.createTrackRow;
window.createPlaylistTrackRow = views.createPlaylistTrackRow;
window.handleCardClick = views.handleCardClick;
window.playPreview = views.playPreview;
window.renderSidebarPlaylists = views.renderSidebarPlaylists;
window.refreshRecentActivity = views.refreshRecentActivity;
window.trackRecentPlaylist = views.trackRecentPlaylist;
window.loadUserData = views.loadUserData;
window.showSaveMixModal = views.showSaveMixModal;
window.discoveryTogglePreview = views.discoveryTogglePreview;
window.discoveryDismiss = views.discoveryDismiss;
window.discoveryDownload = views.discoveryDownload;
window.saveMixAsPlaylistAction = views.saveMixAsPlaylistAction;
window.saveWrappedTopSongsPlaylist = views.saveWrappedTopSongsPlaylist;
window.showTrackDeleteRequestModal = views.showTrackDeleteRequestModal;
window.submitTrackDeleteRequest = views.submitTrackDeleteRequest;
window.showTrackActionsSheet = views.showTrackActionsSheet;
window.closeTrackActionsSheet = views.closeTrackActionsSheet;
window.openTrackMenu = views.openTrackMenu;
window.openAddToPlaylistFlyout = views.openAddToPlaylistFlyout;
window.showContextMenu = views.showContextMenu;
window.closeContextMenu = views.closeContextMenu;
window.renderArtist = views.renderArtist;
window.renderProfile = views.renderProfile;
window.renderCommunity = views.renderCommunity;
window.startSmartRadio = views.startSmartRadio;
window.setCrossfadePending = views.setCrossfadePending;
window.markPlaybackPrefsDirty = views.markPlaybackPrefsDirty;
window.savePlaybackPrefs = views.savePlaybackPrefs;

// Party rooms
window.partyController = party.controller;
window.showCreatePartyModal = party.showCreateModal;
window.createPartyRoom = party.createRoom;
window.partyControl = party.control;
window.resumePartyAudio = party.resumeAudio;
window.searchPartyTracks = party.searchTracks;
window.addPartyTrack = party.addTrack;
window.removePartyTrack = party.removeTrack;
window.deletePartyRoom = party.deleteRoom;
window.leavePartyRoom = party.leave;

// Drop the user into the search view with a pre-filled query and run
// it immediately. Used by the artist view's "Complete this album" CTA
// and the top-tracks download buttons — they don't need their own
// download flow, the existing search → download path is enough.
// Lyrics
window.toggleLyricsPanel = lyrics.toggleLyricsPanel;
window.closeLyricsPanel = lyrics.closeLyricsPanel;
window.onLyricsTrackChange = lyrics.onTrackChange;

// Audio engine prefs (settings page calls these)
window.audioEngineSetReplayGain = audioEngine.setReplayGainEnabled;
window.audioEngineSetCrossfade = audioEngine.setCrossfadeSeconds;

window.startSearchAndDownload = async function (query) {
  if (!query) return;
  await views.loadView('search');
  const input = document.getElementById('search-input');
  if (input) {
    input.value = query;
    if (typeof window.executeSearch === 'function') {
      window.executeSearch(query);
    } else {
      // Fallback: trigger an input event so search.js picks it up.
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
};

// Admin — dynamically loaded for is_admin users only so non-admins don't
// pay the ~24 KB admin.js cost on every page load.
if (window.USER_DATA?.is_admin) {
  import('./modules/admin.js').then((admin) => {
    window.toggleReset = admin.toggleReset;
    window.adminAction = admin.adminAction;
    window.handleAdminForm = admin.handleAdminForm;
    window.deleteUser = admin.deleteUser;
    window.toggleRole = admin.toggleRole;
    window.createUser = admin.createUser;
    window.resetPassword = admin.resetPassword;
    window.adminSearchLibrary = admin.adminSearchLibrary;
    window.adminFindDuplicates = admin.adminFindDuplicates;
    window.adminAuditLibrary = admin.adminAuditLibrary;
    window.adminRescanMetadata = admin.adminRescanMetadata;
    window.adminLoudnessScan = admin.adminLoudnessScan;
    window.showDeleteTrackModal = admin.showDeleteTrackModal;
    window.adminDeleteTrack = admin.adminDeleteTrack;

    // Federation admin panel
    window.federationAddInstance = admin.federationAddInstance;
    window.federationSyncNow = admin.federationSyncNow;
    window.federationToggleEnabled = admin.federationToggleEnabled;
    window.federationDeleteInstance = admin.federationDeleteInstance;
    window.federationIssueToken = admin.federationIssueToken;
    window.federationRevokeOutbound = admin.federationRevokeOutbound;
    window.federationDeleteOutbound = admin.federationDeleteOutbound;
    window.initAdminFederationPanel = admin.initAdminFederationPanel;
  });
}

// === YOUTUBE API CALLBACK ===
window.onYouTubeIframeAPIReady = () => {
  player.setupYouTubePlayer();
};

// === INITIALIZATION ===
document.addEventListener('DOMContentLoaded', async () => {
  console.log('[MAIN] Aoede ES6 Modules Initialized');

  await offlineStore.initOfflineStore();

  initUserMenu();
  initKeyboardShortcuts();
  initOfflineAwareness();
  views.initSpaHistory();
  views.initContextMenu();

  // YouTube IFrame API is loaded lazily on first preview to avoid an
  // unconditional cross-origin script on app boot. See player.playPreview.

  // Load user data (favorites, playlists)
  views.loadUserData();
  downloads.initDownloadHud();

  // Setup player controls
  player.setupPlayer();
  player.setPartyController(party.controller);

  // Restore persisted volume (server is authoritative, cross-device).
  // Runs async — applies the localStorage value instantly then reconciles
  // with the server so the bar never flashes the wrong level for long.
  player.restoreVolume();

  // Lyrics panel — injected after player so the audio element exists
  // and the panel's timeupdate listener can bind cleanly.
  lyrics.initLyrics();

  // Keep heartbeat quiet while backgrounded
  views.initHeartbeatLifecycle();

  const restoredSession = await player.restorePlaybackSession();

  // Load initial view
  // Load initial view only if we are on the root/portal path.
  // window.AOEDE_INITIAL_VIEW / AOEDE_INITIAL_PARAM are set by app_shell.html
  // (a non-blocking inline <script> that runs before this deferred module).
  // We are the *only* caller of loadView on page load — the template no longer
  // calls it a second time, eliminating the double-render (fix Q-10).
  const isDeviceOffline = typeof navigator !== 'undefined' && !navigator.onLine;
  const _serverView = isDeviceOffline ? 'offline' : (window.AOEDE_INITIAL_VIEW ?? 'home');
  const _serverParam = isDeviceOffline ? null : (window.AOEDE_INITIAL_PARAM ?? null);

  if (window.location.pathname === '/' || window.location.pathname === '/index.html') {
    if (isDeviceOffline) {
      views.loadView('offline', null, { replaceHistory: true });
    } else if (restoredSession?.view && restoredSession.view !== 'home') {
      views.loadView(restoredSession.view, restoredSession.param ?? null, { replaceHistory: true });
    } else {
      views.loadView(_serverView, _serverParam, { replaceHistory: true });
    }
  } else if (window.location.pathname === '/portal') {
    if (isDeviceOffline) {
      views.loadView('offline', null, { replaceHistory: true });
    } else if (restoredSession?.view && restoredSession.view !== 'home') {
      views.loadView(restoredSession.view, restoredSession.param ?? null, { replaceHistory: true });
    } else {
      views.loadView(_serverView, _serverParam, { replaceHistory: true });
    }
  } else {
    // If we are on a different page (e.g. /admin/system), we just set the view state without rendering
    // logic to highlight sidebar can be added here if needed
    console.log('[MAIN] Preserving server-rendered content for path:', window.location.pathname);
    if (window.location.pathname.includes('/admin/system')) state.setCurrentViewName('system_monitor');
    else if (window.location.pathname.includes('/admin/downloads')) state.setCurrentViewName('admin_downloads');
  }

  // Initialize Lucide icons
  if (window.lucide) {
    lucide.createIcons();
  }

  // Register PWA service worker
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch((err) => {
      console.warn('[SW] Registration failed:', err);
    });
  }
});
