  # Cross-platform offline playback for Aoede

  ## Summary

  Implement offline support as a shared web feature that works in:

  - Safari on iPhone/iPad, especially when added to the Home Screen.
  - Chrome and installed PWAs on Android.
  - The Android APK WebView wrapper.

  The first release will support explicitly downloaded tracks, cached library metadata, offline queue playback, and deferred synchronization. Search, radio, remote federation streams, and new server downloads remain
  online-only.

  Use shared browser/WebView storage for all clients. The APK will receive wrapper changes so WebView storage, service workers, media controls, and offline startup work correctly.

  iOS storage can be evicted by the operating system, including for Home Screen web apps, so the UI must show that offline files are device-local and can be removed by the platform. citeturn0search0

  ## Implementation changes

  ### 1. Service worker and app shell

  Update the existing service worker to:

  - Precache the complete versioned application shell:
      - /portal or the authenticated app shell.
      - JavaScript modules.
      - CSS.
      - local images and icons.
      - the web manifest.

  - Add a navigation fallback so the app can open without a network connection.
  - Use a versioned cache name and delete old shell caches during activation.
  - Keep API responses and audio downloads out of the generic shell cache.
  - Provide an explicit offline response for unavailable API requests instead of returning a blank page.
  - Cache local fallback assets for icons, covers, fonts, and error states.

  The external Google Fonts, Font Awesome, Lucide, and HTMX resources currently referenced by base.html must either be served locally or have local fallbacks. Offline startup must not depend on third-party CDNs.

  ### 2. Offline storage module

  Add a dedicated client module, for example offline_store.js, backed by IndexedDB.

  Use these stores:

  tracks
    track_id
    metadata
    artwork_blob
    downloaded_at
    last_played_at
    size_bytes
    content_type
    content_length
    app_version

  audio
    track_id
    blob

  library_snapshots
    snapshot_name
    payload
    updated_at
    server_version

  pending_actions
    action_id
    type
    payload
    created_at
    attempts
    last_error

  The module must expose operations equivalent to:

  downloadTrack(track)
  getOfflineTrack(trackId)
  listOfflineTracks()
  isTrackAvailableOffline(trackId)
  deleteOfflineTrack(trackId)
  getStorageUsage()
  clearOfflineData()
  queueAction(type, payload)
  flushPendingActions()

  Store complete audio files as Blob values. Do not depend on browser HTTP cache behavior or partial 206 responses for offline playback. The existing /api/stream/{track_id} endpoint supports ranges for normal online
  playback, but a complete local Blob is more reliable for seeking and replay after disconnection.

  ### 3. Offline download flow

  Add an “Available offline” control to local track rows, the player, playlists, and the queue.

  When selected:

  - Verify the user is online.
  - Fetch the complete authenticated stream response.
  - Read the body with a progress indicator.
  - Validate that the response is successful and has an audio content type.
  - Store metadata, artwork, and audio atomically.
  - Mark the track as available only after the Blob is fully written.
  - Resume or retry interrupted downloads from the beginning.
  - Prevent duplicate downloads for the same track.
  - Permit deletion from the offline library.
  - Use user-managed storage only: do not automatically evict tracks.
  - If storage is full, show a clear error and direct the user to delete downloads.
  - Show total offline storage usage and the size of each track.
  - Keep server-side library downloads separate from browser offline downloads.

  The browser must not attempt offline downloads through the existing /api/download server job flow.

  ### 4. Player integration

  Modify the player’s source selection logic:

  online + normal local track  -> /api/stream/{id}
  offline + locally stored     -> Blob URL
  remote/federated track       -> online request only
  radio/preview                -> online request only

  Before assigning a track to the <audio> element:

  - Check whether the track exists in the offline store.
  - Prefer the local Blob when the device is offline.
  - Also prefer the local Blob when the user explicitly selects “Play offline”.
  - Create an object URL and revoke the previous object URL after the track has stopped using it.
  - Preserve seeking, repeat, shuffle, queue transitions, MediaSession metadata, and playback progress.
  - Avoid the existing stream prefetch code when the next track is already locally stored.
  - If an offline track is missing or corrupt, remove its offline marker and show a recoverable error.
  - Ensure restorePlaybackSession() can restore an offline track without contacting the server.
  - Do not send listen-progress requests while offline; enqueue the progress event instead.

  The existing offline banner must be changed from “streaming and downloads will not work” to a useful state indicator:

  - “Offline — downloaded tracks are available.”
  - “This track is not downloaded for offline use.”
  - “Back online — syncing changes.”

  ### 5. Cached library and offline views

  While online, save the user’s latest:

  - Library track list and metadata.
  - Favorites.
  - Playlists and playlist track membership.
  - Recently played data needed by the home view.
  - Offline track list.

  When offline:

  - Render the cached library and playlists.
  - Add an “Offline downloads” view.
  - Filter playback controls so unavailable tracks are visibly disabled.
  - Hide or disable search, radio, federated tracks, provider downloads, party rooms, lyrics requiring network access, and server administration actions.
  - Preserve the queue locally so users can reorder, shuffle, repeat, and play downloaded tracks offline.
  - Show the timestamp of the last successful synchronization.

  If no cached shell or metadata exists, show an offline setup screen explaining that the user must connect once before offline mode can be used.

  ### 6. Deferred synchronization

  Add an offline action queue for:

  - Favorite changes.
  - Playback progress.
  - Completed listens.
  - Playlist changes supported by the existing API.
  - Queue/session state where synchronization is already supported.

  When connectivity returns or the app becomes visible:

  - Send queued actions in creation order.
  - Use idempotency keys so retrying an action cannot duplicate it.
  - Retry transient failures with bounded backoff.
  - Leave authentication failures pending and prompt the user to sign in again.
  - Remove an action only after a successful server response.
  - Record the last error for display in an offline sync status panel.

  Use last-write-wins based on client action time and server acceptance time. Playback progress should use the greatest known playback position for the same track/session to avoid moving progress backwards.

  Refresh the server snapshot after the action queue is flushed. If an offline playlist mutation conflicts with a server update, accept the server response as authoritative and refresh the local cached playlist.

  ### 7. Authentication and security

  Offline mode must require a previously authenticated session. Or store a password has or similar in local so the user can be offline a long time ?

  Implement these rules:

  - Never store the user’s password in IndexedDB.
  - Do not store authentication tokens inside audio metadata or the offline track records.
  - Treat offline audio as private origin data.
  - Clear offline data on explicit logout.
  - Make logout behavior explicit in the UI because it removes device-local downloads.
  - Do not expose offline Blobs through publicly guessable URLs.
  - Keep the existing cookie/session authentication for online downloads.
  - Use same-origin requests and credentials for stream, cover, and metadata fetches.
  - Ensure a user cannot access another user’s offline database records through application IDs or UI parameters.

  ### 8. Android WebView wrapper

  Update the separately released Android wrapper project to:

  - Enable DOM storage.
  - Enable IndexedDB and service workers.
  - Allow the WebView cache/storage to persist across app restarts.
  - Preserve the configured server origin when the server URL changes.
  - Clear origin storage when the user explicitly logs out or removes the server.
  - Do not clear WebView data during ordinary activity recreation or app updates.
  - Support blob: media URLs in the WebView audio element.
  - Preserve the existing Android MediaSession and notification integration.
  - Keep background playback working when an offline Blob is the active source.
  - Keep play/pause/next/previous actions connected to the web player.
  - Restore the active offline track and queue after process recreation where possible.
  - Handle WebView process death by restoring the persisted IndexedDB data and playback session.
  - Display a native or web storage usage message if Android reports insufficient space.
  - Ensure the wrapper does not force every audio request through a native HTTP client that cannot read blob: URLs.

  If the current native bridge cannot preserve media controls for Blob-backed HTML audio, add a wrapper-side local media bridge that serves stored audio through a local, origin-scoped HTTPS URL. The preferred
  implementation is to keep playback in the existing HTML audio element and make the bridge observe/control that element.

  The APK release should include a migration note explaining that offline downloads are tied to the installed app’s local storage and are not automatically transferred between devices.

  ### 9. PWA behavior on iPhone and Android

  Update the manifest and installation guidance to encourage Home Screen installation.

  On first successful online session:

  - Register and activate the service worker.
  - Confirm that the shell is cached.
  - Show an optional “Enable offline playback” explanation.
  - Provide a test action such as “Check offline readiness”.
  - Report whether the browser supports IndexedDB, service workers, Blob playback, and storage estimation.

  Use navigator.storage.estimate() where available to show approximate usage. Do not promise a guaranteed storage quota. iOS Home Screen apps and Safari can still remove origin data under storage pressure or inactivity.

  ### 10. Versioning and data migration

  Version the IndexedDB schema independently from the service worker cache.

  On application startup:

  - Run IndexedDB migrations before rendering offline controls.
  - Keep old metadata readable where possible.
  - Mark audio entries with the application and content version.
  - Do not delete valid audio merely because the webapp was updated.
  - Remove records whose server track IDs no longer exist only during an explicit metadata refresh.
  - Clear incomplete download records during startup recovery.
  - Preserve offline files across normal webapp deployments.

  ## Test plan

  ### Web browser tests

  Test on:

  - Current Safari on iPhone.
  - iPhone Home Screen web app.
  - Current Chrome on Android.
  - Android Chrome installed PWA.
  - Desktop Chromium and Safari as development references.

  Scenarios:

  - First load online, then reopen with airplane mode enabled.
  - Shell starts offline after browser restart.
  - Download completes and plays fully offline.
  - Seeking works at the beginning, middle, and end of a downloaded track.
  - Track transitions work offline with queue, shuffle, repeat, and autoplay.
  - Artwork and metadata remain available offline.
  - A partially completed download never appears as playable.
  - Deleting a download removes its audio, artwork, and metadata.
  - Storage-full errors are recoverable.
  - Service worker updates do not delete existing offline tracks.
  - Missing/corrupt local audio falls back cleanly.
  - Online playback still supports HTTP ranges and normal seeking.
  - Online-to-offline transition during playback behaves predictably.
  - Offline-to-online transition flushes progress and favorites.
  - Repeated reconnects do not duplicate actions.
  - Logout clears private offline data.
  - Expired sessions do not expose cached server data to a different account.

  ### Android APK tests

  Test on the documented Android 5+ baseline and at least one current Android release:

  - WebView storage survives activity restart.
  - WebView storage survives app restart.
  - APK opens the cached shell without network.
  - Offline Blob playback continues with the screen locked.
  - Android notification controls work for offline tracks.
  - Next/previous controls work from the notification.
  - Queue restoration works after process recreation where supported.
  - Server URL changes isolate or clear the previous origin’s offline data.
  - App update preserves offline audio.
  - Low-storage behavior produces a usable error.
  - WebView service worker registration succeeds.
  - Native wrapper does not intercept or reject blob: audio URLs.

  ### Server/API regression tests

  Verify that:

  - Existing authenticated stream responses remain unchanged.
  - Range requests still return correct 206 responses.
  - Cache headers remain private.
  - Offline functionality never bypasses server authorization for online requests.
  - Existing server download jobs remain separate from device offline downloads.
  - Existing listen-progress and favorite APIs accept retried idempotent actions.

  ## Acceptance criteria

  The feature is complete when:

  - An iPhone user can add Aoede to the Home Screen, download a track while online, enable airplane mode, reopen Aoede, browse the cached offline library, and play the track with seeking.
  - The same flow works in Android Chrome.
  - The same flow works in the APK wrapper, including lock-screen media controls.
  - No incomplete or cross-user offline records are playable.
  - Offline actions synchronize after reconnecting without duplicate favorites, listens, or playlist mutations.
  - Normal online streaming, server downloads, federation, and existing Android background playback continue to work.
  - The UI clearly distinguishes server library downloads from device-local offline downloads.

  ## Assumptions and defaults

  - v1 supports downloaded tracks plus cached library and playlist metadata.
  - Offline storage is shared web/WebView storage.
  - Storage is user-managed; there is no automatic LRU eviction.
  - Sync uses an action queue with last-write-wins behavior.
  - The documented Android 5+ APK baseline remains supported, with graceful degradation where older WebViews lack required APIs.
  - Offline tracks are device-local and are not synchronized between browsers, phones, or APK installations.
  - The Android wrapper source must be obtained from the separately released Android source artifact before implementation of wrapper-specific changes.