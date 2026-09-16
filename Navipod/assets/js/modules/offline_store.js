/**
 * offline_store.js - Cross-platform IndexedDB Offline Storage & Synchronization
 *
 * Provides resilient local audio, metadata snapshots, and deferred action queues
 * for desktop browsers, mobile Safari (iOS PWA), Android Chrome, and WebView wrapper.
 */

const DB_NAME = 'navipod_offline_db';
const DB_VERSION = 1;
const APP_VERSION = '1.0';

let _dbPromise = null;
const _offlineTrackIdSet = new Set();
let _isInitialized = false;

/**
 * Open or upgrade the IndexedDB database
 */
export function getDB() {
  if (_dbPromise) return _dbPromise;

  _dbPromise = new Promise((resolve, reject) => {
    const idb =
      typeof window !== 'undefined'
        ? window.indexedDB
        : typeof globalThis !== 'undefined'
          ? globalThis.indexedDB
          : null;
    if (!idb) {
      reject(new Error('IndexedDB is not supported in this environment.'));
      return;
    }

    const request = idb.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = event.target.result;

      // 1. tracks: Metadata and download records
      if (!db.objectStoreNames.contains('tracks')) {
        const trackStore = db.createObjectStore('tracks', { keyPath: 'track_id' });
        trackStore.createIndex('downloaded_at', 'downloaded_at', { unique: false });
        trackStore.createIndex('status', 'status', { unique: false });
      }

      // 2. audio: Complete audio Blob storage
      if (!db.objectStoreNames.contains('audio')) {
        db.createObjectStore('audio', { keyPath: 'track_id' });
      }

      // 3. library_snapshots: Cached library, playlists, favorites, and home feeds
      if (!db.objectStoreNames.contains('library_snapshots')) {
        db.createObjectStore('library_snapshots', { keyPath: 'snapshot_name' });
      }

      // 4. pending_actions: Offline mutation queue for background synchronization
      if (!db.objectStoreNames.contains('pending_actions')) {
        const actionStore = db.createObjectStore('pending_actions', { keyPath: 'action_id', autoIncrement: true });
        actionStore.createIndex('created_at', 'created_at', { unique: false });
        actionStore.createIndex('type', 'type', { unique: false });
      }
    };

    request.onsuccess = () => {
      resolve(request.result);
    };

    request.onerror = () => {
      _dbPromise = null;
      reject(request.error);
    };
  });

  return _dbPromise;
}

/**
 * Initialize offline store on startup:
 *  - Cleans up any interrupted/incomplete downloads
 *  - Caches available offline track IDs in memory for instant lookups
 */
export async function initOfflineStore() {
  if (_isInitialized) return;
  try {
    const db = await getDB();

    // Clean up partial downloads (status !== 'complete') and populate ID cache
    const tx = db.transaction(['tracks', 'audio'], 'readwrite');
    const trackStore = tx.objectStore('tracks');
    const audioStore = tx.objectStore('audio');

    const getAllReq = trackStore.getAll();
    getAllReq.onsuccess = () => {
      const allRecords = getAllReq.result || [];
      _offlineTrackIdSet.clear();

      for (const record of allRecords) {
        if (record.status !== 'complete') {
          // Incomplete or interrupted download from previous crash
          trackStore.delete(record.track_id);
          audioStore.delete(record.track_id);
        } else {
          _offlineTrackIdSet.add(Number(record.track_id));
        }
      }
      _isInitialized = true;
    };
  } catch (err) {
    console.warn('[OFFLINE-STORE] Initialization error:', err);
  }
}

/**
 * Fast synchronous check using memory set
 */
export function isTrackAvailableOfflineSync(trackId) {
  if (!trackId) return false;
  return _offlineTrackIdSet.has(Number(trackId));
}

/**
 * Check if a track is available offline (IndexedDB lookup)
 */
export async function isTrackAvailableOffline(trackId) {
  if (!trackId) return false;
  const numId = Number(trackId);
  if (_offlineTrackIdSet.has(numId)) return true;

  try {
    const db = await getDB();
    return new Promise((resolve) => {
      const tx = db.transaction('tracks', 'readonly');
      const req = tx.objectStore('tracks').get(numId);
      req.onsuccess = () => {
        const item = req.result;
        const exists = Boolean(item && item.status === 'complete');
        if (exists) _offlineTrackIdSet.add(numId);
        resolve(exists);
      };
      req.onerror = () => resolve(false);
    });
  } catch {
    return false;
  }
}

/**
 * Download a track for offline playback
 * @param {Object} track
 * @param {Function} [onProgress] - Callback (percent, loadedBytes, totalBytes)
 */
export async function downloadTrack(track, onProgress = null) {
  if (!track) throw new Error('No track provided');
  const trackId = Number(track.db_id || track.id);
  if (!trackId) throw new Error('Track has no valid database ID');

  if (!navigator.onLine) {
    throw new Error('Cannot download tracks while offline');
  }

  // If already downloaded, return existing record
  const existing = await getOfflineTrack(trackId);
  if (existing && existing.status === 'complete') {
    if (typeof onProgress === 'function') onProgress(100, existing.size_bytes, existing.size_bytes);
    return existing;
  }

  const db = await getDB();

  // 1. Mark status as 'downloading'
  const tempRecord = {
    track_id: trackId,
    metadata: {
      id: trackId,
      db_id: trackId,
      title: track.title || 'Unknown Title',
      artist: track.artist || 'Unknown Artist',
      album: track.album || '',
      duration: Number(track.duration || 0),
      thumbnail: track.thumbnail || '',
      source: 'local',
      is_local: true,
      is_offline: true,
      track_number: track.track_number || null,
      year: track.year || null,
      genre: track.genre || ''
    },
    artwork_blob: null,
    downloaded_at: Date.now(),
    last_played_at: null,
    size_bytes: 0,
    content_type: 'audio/mpeg',
    content_length: 0,
    app_version: APP_VERSION,
    status: 'downloading'
  };

  await new Promise((resolve, reject) => {
    const tx = db.transaction('tracks', 'readwrite');
    const req = tx.objectStore('tracks').put(tempRecord);
    req.onsuccess = () => resolve(true);
    req.onerror = () => reject(req.error);
  });

  try {
    // 2. Fetch complete stream with progress tracking
    const streamUrl = `/api/stream/${trackId}`;
    const response = await fetch(streamUrl);
    if (!response.ok) {
      throw new Error(`Server returned status ${response.status} when fetching audio stream`);
    }

    const contentType = response.headers.get('content-type') || 'audio/mpeg';
    const contentLengthHeader = response.headers.get('content-length');
    const totalBytes = contentLengthHeader ? parseInt(contentLengthHeader, 10) : 0;

    const reader = response.body.getReader();
    const chunks = [];
    let loadedBytes = 0;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      loadedBytes += value.length;

      if (typeof onProgress === 'function') {
        const percent = totalBytes > 0 ? Math.min(99, Math.round((loadedBytes / totalBytes) * 100)) : 0;
        onProgress(percent, loadedBytes, totalBytes);
      }
    }

    const audioBlob = new Blob(chunks, { type: contentType });

    // 3. Optional: fetch artwork thumbnail blob for offline cover display
    let artworkBlob = null;
    if (track.thumbnail && !track.thumbnail.startsWith('data:')) {
      try {
        const thumbRes = await fetch(track.thumbnail);
        if (thumbRes.ok) {
          artworkBlob = await thumbRes.blob();
        }
      } catch (err) {
        console.warn('[OFFLINE-STORE] Thumbnail fetch skipped:', err);
      }
    }

    // 4. Save both audio Blob and completed track record atomically
    const finalRecord = {
      ...tempRecord,
      artwork_blob: artworkBlob,
      size_bytes: audioBlob.size,
      content_type: contentType,
      content_length: audioBlob.size,
      status: 'complete'
    };

    await new Promise((resolve, reject) => {
      const tx = db.transaction(['tracks', 'audio'], 'readwrite');
      tx.oncomplete = () => resolve(true);
      tx.onerror = () => reject(tx.error);

      tx.objectStore('audio').put({ track_id: trackId, blob: audioBlob });
      tx.objectStore('tracks').put(finalRecord);
    });

    _offlineTrackIdSet.add(trackId);
    if (typeof onProgress === 'function') {
      onProgress(100, audioBlob.size, audioBlob.size);
    }

    window.dispatchEvent(
      new CustomEvent('navipod:offline-changed', {
        detail: { type: 'added', trackId, track: finalRecord.metadata }
      })
    );

    return finalRecord;
  } catch (error) {
    // Clean up partial record on failure
    try {
      const cleanTx = db.transaction(['tracks', 'audio'], 'readwrite');
      cleanTx.objectStore('tracks').delete(trackId);
      cleanTx.objectStore('audio').delete(trackId);
    } catch {
      /* ignore */
    }
    _offlineTrackIdSet.delete(trackId);
    throw error;
  }
}

/**
 * Get track metadata and record from IndexedDB
 */
export async function getOfflineTrack(trackId) {
  if (!trackId) return null;
  const db = await getDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('tracks', 'readonly');
    const req = tx.objectStore('tracks').get(Number(trackId));
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
}

/**
 * Get audio Blob for offline playback
 */
export async function getOfflineAudioBlob(trackId) {
  if (!trackId) return null;
  const db = await getDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('audio', 'readonly');
    const req = tx.objectStore('audio').get(Number(trackId));
    req.onsuccess = () => resolve(req.result ? req.result.blob : null);
    req.onerror = () => reject(req.error);
  });
}

/**
 * List all fully downloaded offline tracks
 */
export async function listOfflineTracks() {
  const db = await getDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('tracks', 'readonly');
    const req = tx.objectStore('tracks').getAll();
    req.onsuccess = () => {
      const all = req.result || [];
      const completed = all
        .filter((item) => item && item.status === 'complete')
        .map((item) => ({
          ...item.metadata,
          offline_record: {
            size_bytes: item.size_bytes,
            downloaded_at: item.downloaded_at,
            last_played_at: item.last_played_at
          }
        }));
      resolve(completed);
    };
    req.onerror = () => reject(req.error);
  });
}

/**
 * Delete a track from offline storage
 */
export async function deleteOfflineTrack(trackId) {
  if (!trackId) return false;
  const numId = Number(trackId);
  const db = await getDB();

  await new Promise((resolve, reject) => {
    const tx = db.transaction(['tracks', 'audio'], 'readwrite');
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => reject(tx.error);

    tx.objectStore('tracks').delete(numId);
    tx.objectStore('audio').delete(numId);
  });

  _offlineTrackIdSet.delete(numId);

  window.dispatchEvent(
    new CustomEvent('navipod:offline-changed', {
      detail: { type: 'removed', trackId: numId }
    })
  );

  return true;
}

/**
 * Calculate total offline storage usage and quota estimate
 */
export async function getStorageUsage() {
  let bytesUsed = 0;
  let trackCount = 0;

  try {
    const db = await getDB();
    const tracks = await new Promise((resolve) => {
      const tx = db.transaction('tracks', 'readonly');
      const req = tx.objectStore('tracks').getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });

    for (const t of tracks) {
      if (t && t.status === 'complete') {
        trackCount++;
        bytesUsed += Number(t.size_bytes || 0);
      }
    }
  } catch (err) {
    console.warn('[OFFLINE-STORE] getStorageUsage error:', err);
  }

  let quotaBytes = null;
  let usagePercent = null;

  if (typeof navigator !== 'undefined' && navigator.storage?.estimate) {
    try {
      const estimate = await navigator.storage.estimate();
      if (estimate.quota) {
        quotaBytes = estimate.quota;
        if (estimate.usage) {
          usagePercent = Math.min(100, Math.round((estimate.usage / estimate.quota) * 100));
        }
      }
    } catch {
      /* ignore */
    }
  }

  return {
    bytesUsed,
    trackCount,
    quotaBytes,
    usagePercent
  };
}

/**
 * Clear all offline stored data (used on explicit logout or user action)
 */
export async function clearOfflineData() {
  const db = await getDB();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(['tracks', 'audio', 'library_snapshots', 'pending_actions'], 'readwrite');
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => reject(tx.error);

    tx.objectStore('tracks').clear();
    tx.objectStore('audio').clear();
    tx.objectStore('library_snapshots').clear();
    tx.objectStore('pending_actions').clear();
  });

  _offlineTrackIdSet.clear();

  window.dispatchEvent(
    new CustomEvent('navipod:offline-changed', {
      detail: { type: 'cleared' }
    })
  );
}

/**
 * Save a cached snapshot of server metadata (library, favorites, playlists, etc.)
 */
export async function saveLibrarySnapshot(snapshotName, payload, serverVersion = null) {
  if (!snapshotName) return;
  try {
    const db = await getDB();
    const record = {
      snapshot_name: snapshotName,
      payload,
      updated_at: Date.now(),
      server_version: serverVersion
    };
    await new Promise((resolve, reject) => {
      const tx = db.transaction('library_snapshots', 'readwrite');
      const req = tx.objectStore('library_snapshots').put(record);
      req.onsuccess = () => resolve(true);
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('[OFFLINE-STORE] Snapshot save failed for', snapshotName, err);
  }
}

/**
 * Retrieve cached snapshot
 */
export async function getLibrarySnapshot(snapshotName) {
  if (!snapshotName) return null;
  try {
    const db = await getDB();
    return new Promise((resolve) => {
      const tx = db.transaction('library_snapshots', 'readonly');
      const req = tx.objectStore('library_snapshots').get(snapshotName);
      req.onsuccess = () => resolve(req.result ? req.result.payload : null);
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

/**
 * Queue an offline mutation action for deferred synchronization
 */
export async function queueAction(type, payload) {
  if (!type) return null;
  try {
    const db = await getDB();
    const idempotencyKey =
      typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `act_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

    const actionRecord = {
      type,
      payload,
      created_at: Date.now(),
      attempts: 0,
      last_error: null,
      idempotency_key: idempotencyKey
    };

    return new Promise((resolve, reject) => {
      const tx = db.transaction('pending_actions', 'readwrite');
      const req = tx.objectStore('pending_actions').add(actionRecord);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('[OFFLINE-STORE] Action queueing error:', err);
    return null;
  }
}

/**
 * List all pending deferred actions
 */
export async function getPendingActions() {
  try {
    const db = await getDB();
    return new Promise((resolve) => {
      const tx = db.transaction('pending_actions', 'readonly');
      const req = tx.objectStore('pending_actions').getAll();
      req.onsuccess = () => resolve(req.result || []);
      req.onerror = () => resolve([]);
    });
  } catch {
    return [];
  }
}

/**
 * Remove a completed action
 */
export async function removePendingAction(actionId) {
  try {
    const db = await getDB();
    await new Promise((resolve, reject) => {
      const tx = db.transaction('pending_actions', 'readwrite');
      const req = tx.objectStore('pending_actions').delete(actionId);
      req.onsuccess = () => resolve(true);
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('[OFFLINE-STORE] removePendingAction error:', err);
  }
}

/**
 * Flush pending offline actions when connectivity returns
 */
export async function flushPendingActions() {
  if (!navigator.onLine) return;

  const actions = await getPendingActions();
  if (!actions.length) return;

  console.log(`[OFFLINE-STORE] Flushing ${actions.length} pending offline actions...`);

  for (const action of actions) {
    try {
      let success = false;

      if (action.type === 'toggle_favorite') {
        const { trackId, liked } = action.payload;
        const res = await fetch(`/api/favorites/${trackId}`, {
          method: liked ? 'POST' : 'DELETE',
          headers: { 'X-Idempotency-Key': action.idempotency_key }
        });
        success = res.ok || (res.status === 404 && !liked);
      } else if (action.type === 'listen_progress' || action.type === 'track_completed') {
        const res = await fetch('/api/activity/listen', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Idempotency-Key': action.idempotency_key
          },
          body: JSON.stringify(action.payload)
        });
        success = res.ok;
      } else if (action.type === 'create_playlist') {
        const res = await fetch('/api/playlists', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Idempotency-Key': action.idempotency_key
          },
          body: JSON.stringify(action.payload)
        });
        success = res.ok;
      } else if (action.type === 'add_to_playlist') {
        const { playlistId, trackId } = action.payload;
        const res = await fetch(`/api/playlists/${playlistId}/add`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Idempotency-Key': action.idempotency_key
          },
          body: JSON.stringify({ track_id: trackId })
        });
        success = res.ok;
      } else if (action.type === 'remove_from_playlist') {
        const { playlistId, trackId } = action.payload;
        const res = await fetch(`/api/playlists/${playlistId}/remove/${trackId}`, {
          method: 'DELETE',
          headers: { 'X-Idempotency-Key': action.idempotency_key }
        });
        success = res.ok || res.status === 404;
      } else if (action.type === 'edit_playlist') {
        const { playlistId, name } = action.payload;
        const res = await fetch(`/api/playlists/${playlistId}`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'X-Idempotency-Key': action.idempotency_key
          },
          body: JSON.stringify({ name })
        });
        success = res.ok;
      } else if (action.type === 'delete_playlist') {
        const { playlistId } = action.payload;
        const res = await fetch(`/api/playlists/${playlistId}`, {
          method: 'DELETE',
          headers: { 'X-Idempotency-Key': action.idempotency_key }
        });
        success = res.ok || res.status === 404;
      } else {
        // Unknown action type; drop to prevent blocking queue
        success = true;
      }

      if (success) {
        await removePendingAction(action.action_id);
      } else {
        action.attempts = (action.attempts || 0) + 1;
        if (action.attempts > 5) {
          // Drop after 5 persistent failures
          await removePendingAction(action.action_id);
        }
      }
    } catch (err) {
      console.warn('[OFFLINE-STORE] Failed to sync action:', action, err);
      break; // Pause syncing until network stabilizes
    }
  }
}

/**
 * Report readiness of offline features in this browser/device
 */
export async function checkOfflineReadiness() {
  const hasIndexedDB =
    typeof window !== 'undefined'
      ? Boolean(window.indexedDB)
      : typeof globalThis !== 'undefined'
        ? Boolean(globalThis.indexedDB)
        : false;
  const hasServiceWorker = typeof navigator !== 'undefined' && 'serviceWorker' in navigator;
  const hasBlobPlayback = typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function';
  const hasStorageEstimate = typeof navigator !== 'undefined' && typeof navigator.storage?.estimate === 'function';

  let storageInfo = { bytesUsed: 0, trackCount: 0 };
  if (hasIndexedDB) {
    storageInfo = await getStorageUsage();
  }

  return {
    supported: hasIndexedDB && hasBlobPlayback,
    hasIndexedDB,
    hasServiceWorker,
    hasBlobPlayback,
    hasStorageEstimate,
    storageInfo
  };
}
