# User Guide

The main application is available under `/portal` after authentication. This guide covers the web app; see [Android](ANDROID.md) and [Subsonic](SUBSONIC.md) for other clients.

## Find your way around

The top search field searches music. The Home filters provide focused views:

- **All** — recent playlists, personal mixes, recommendations and other shortcuts;
- **Party** — available Party Rooms and the room creation flow;
- **Public** — playlists shared by other users on this server;
- **Discover** — remote recommendations that are not already in the local library;
- **Radios** — live radio discovery and saved stations.

The side rail gives quick access to favorites and playlists. The player remains available at the bottom while you browse.

## About and app downloads

Open **About & updates** from the user menu on desktop or **More (⋯)** on mobile. The panel is in English and shows the running server version separately from the latest published release, a short update summary and release notes. The platform tabs list the installed-frontend Android APK, the original Android wrapper backup and sources, plus available macOS, Windows and Linux packages. Only uploaded assets in the current official release are listed; an absent platform links back to the releases page.

Release metadata is checked when the panel opens, with a shared five-minute server cache. GitHub failures retain the last successful result with a visible notice and a one-minute retry delay; the official release page remains accessible when no result is available. No GitHub key or administrator permission is required. Update the backend to expose the authenticated, read-only `/api/about` endpoint; existing clients get the panel on reload, while the installed Android frontend includes it from APK 1.1.3.

## Home and personal mixes

Home adapts to the library and listening activity available to your account. Its local-library mixes include:

- **Repeat** — tracks you return to most often;
- **Deep Cuts** — familiar music outside your most obvious repeats;
- **Favorites** — liked tracks and related music already in the library;
- **Rediscovery** — liked tracks that have not been played much recently.

Open a mix to play it or save its current contents as a normal playlist. Results evolve as listening history, favorites and the shared library change.

## Discover

The **Discover** tab shows remote recommendations that are not already owned by the server. From a recommendation you can:

- preview it when a preview is available;
- download it into the shared pool;
- dismiss it from your feed.

Dismissed recommendations are remembered by that browser. Downloaded music becomes available after the download and import job completes.

## Search and download music

Search can combine results from:

- the local Aoede/Navidrome library;
- Spotify;
- YouTube;
- Last.fm;
- MusicBrainz;
- connected Aoede federation peers, when enabled by an administrator.

Use the source filters to narrow the results. Local tracks can be played immediately. Remote tracks can be previewed or downloaded when the source supports it.

You can also open the global **Downloads** panel and paste a supported Spotify, YouTube, SoundCloud, Audius or Jamendo URL. Jobs show their current state, resolved source and downloader details. Failed jobs can be retried from the same panel.

A typical job moves through these stages:

```text
queued → processing → completed
```

A job can move to **Failed** from any active stage; its details explain the error and whether a fallback was attempted.

Aoede reuses matching content already in the shared pool when possible, so two users do not need separate copies of the same track.

To import a complete collection, enable **Albums / Playlists** in Search or paste a collection URL into Downloads. Preview it, choose its name and type, and import its ordered songs into a private collection under your account. The collection's Downloads button shows progress and failed songs that can be retried. See [Importing Music](IMPORTING-MUSIC.md) for provider requirements and limits.

## Browse the library

**Your Library** separates playlists, songs, artists, albums and genres. **Songs** searches the shared pool by title, artist or album and sorts by title, artist or recently added. **Previous / Next** pages cover the full library, including more than 500 entries. Artist, album-tag and genre groups use the same song rows with queue, favorites, playlist and party actions. Favorites remain available from the heart shortcut in the side rail.

Choose **Create → Album** to create a curated album manually. **Albums** shows saved albums by default: created, imported, shared or synchronized collections. Downloading a single song does not add an album to this list. Search, sort or paginate these albums, and open one to remove songs or change their order once its import finishes. Curated albums use the same editing, collaboration, sharing, and offline controls as playlists.

Enable **Browse albums from song metadata** to browse the pool's song-tag groups separately. These groups may be incomplete and are not editable collections; turn the option off to return to saved albums. Existing song tags and pool files are preserved.

Offline library metadata is saved separately for each account. After upgrading from an older version, connect once to refresh that account's saved metadata; previously downloaded audio remains on the device.

## Normal playlists

Create a playlist from the Library view or save one from a mix. Playlist owners can:

- rename or delete the playlist;
- add, remove and drag tracks into a new order;
- upload a cover or choose a cover already present in the playlist;
- switch the playlist between private and public.

The **Public** Home tab lists playlists and curated albums shared by users on this server and connected federation nodes. You can save a read-only local copy of another user's collection and sync that copy when its source changes. Synced copies cannot be edited or published as if they were the original; create a separate playlist if you want an independent version.

## Collaborative playlists

Choose **Create → Collaborative playlist** on mobile, or use **New Playlist** in the Library to open the same creation menu. After naming the playlist, select a user in the invitation picker. Remote listeners appear as **username @ node**; select that entry to invite the correct account. A typed username without a selected remote entry refers to a local user. You can also open **Collaborators** on any ordinary playlist or curated album you own to invite users later.

Invited users see the shared playlist in their Library and in the Add to Playlist picker. They can add, remove, and reorder songs. On a phone, tap a song's reorder handle and choose **Move up** or **Move down**; desktop drag-and-drop is also supported. Changes appear for other users through the normal refresh cycle (up to 30 seconds while the app is visible). A stale reorder is rejected and the latest playlist is reloaded.

The owner alone can rename, publish, change the cover, delete the playlist, or manage collaborators. Collaborators can leave using **Leave playlist**. Removing a collaborator immediately revokes editing access; public playlists remain viewable. Public visibility does not grant editing rights. Invitations require a connection, and smart playlists and synced copies cannot accept collaborators. Collaborative edits update the owner's Navidrome export; collaboration itself is managed in the Aoede webapp.

## Federated community and collections

From **1.2.8**, Community and user search include listeners from connected nodes. Open a remote profile to see its public playlists, curated albums, favorite songs and listening statistics. Node labels distinguish accounts with the same username; a remote profile never becomes your local account.

Public collections can be played in order, shuffled, queued and copied into your Library. A synchronized copy keeps the original type and ordered entries, including repeated songs. **Sync from Source** refreshes its contents; it does not download the remote audio. The source must remain reachable and accessible. Making a source private or removing an invitation stops further access through that collection.

For collaboration, both servers must run **1.2.8 or newer** and their administrators must connect federation in both directions with matching public peer URLs. An invited listener sees the collection in Library and the Add to Playlist picker, can add songs from either connected pool, remove a specific entry, reorder songs or leave. The owning server checks every edit. Public sharing and invitations remain separate: a private collection is visible to its collaborators but does not enter the public feed.

Songs retain their source node when added to another collection or a federated party. A party host must also subscribe to each song's source node; missing catalogue entries are fetched when needed. Collections containing remote songs require a federated party when used as its initial queue.

These controls use the same web interface in browsers, Android APK and desktop wrappers. Federated audio and edits require a connection; offline audio downloads and Navidrome/Subsonic M3U exports currently contain local pool files only. Older or unavailable nodes are omitted from discovery without hiding local content. See [Administration](ADMINISTRATION.md#federation-beta) for setup.

## Smart playlists

Smart playlists are rule-based views of the current library rather than fixed track lists. When creating or editing one, you can combine:

- artist, album and genre filters;
- minimum and maximum release year;
- tracks added within a number of days;
- a minimum play count;
- tracks not played for a number of days;
- favorites only;
- result limit and sort order.

Use **Preview** before saving to check the match count and rule summary. Use **Refresh** on an existing smart playlist to recalculate it immediately. Relevant library and favorite changes can also refresh its contents automatically.

## Player controls

The compact player and full-screen player provide:

- play/pause, previous and next;
- seek, mute and volume;
- shuffle and repeat;
- favorite and add-to-playlist actions;
- lyrics, queue and sleep timer controls.

Repeat cycles through off, queue repeat and repeat-current-track. The sleep timer cycles through off, 15, 30 and 60 minutes and pauses playback when it expires.

**Classic** and **Glass** use a lower selector that switches between cover, lyrics and queue while transport controls remain visible. Favorite and playlist actions sit beside the title; volume opens from the upper corner in Classic and Glass. Vitrine places the volume slider below the transport, with lyrics, volume and queue actions along the bottom and additional controls in the upper-right menu. The compact mobile player offers Next and Play/Pause. The player adapts to portrait, landscape and wide desktop screens, with Classic's opaque dark surfaces, Glass's translucent appearance, or Vitrine's complete artwork, unboxed transport and thumbnail while reading lyrics. Lyrics follow the playing recording and respect manual scrolling; use **Follow current line** to resume automatic following immediately. Use arrow keys on a focused seek/volume slider for 5% steps, Home/End for its limits, and Escape to collapse the fullscreen player without stopping audio.

### Queue and playback state

Open the queue to drag tracks into a new order or remove them. Manually queued tracks play before the remaining album, playlist or other playback context.

Aoede saves the personal queue, current context, position, shuffle/repeat state and volume for your account. It can restore that state in another session or browser, but this is not live cross-device control: if multiple devices play at once, the latest saved state wins. Party Room playback is kept separate from personal playback state.

### Lyrics

Open the lyrics panel from the player. When synchronized lyrics are available, the current line follows playback; plain lyrics are shown otherwise. Lyrics are retrieved through the Aoede backend and cached for later use.

### Per-device playback preferences

In **Settings → Playback**, you can enable volume normalization and choose a fade duration between tracks. These preferences are stored in the current browser and do not automatically follow your account to another device.

## Favorites and smart radio

Use the heart action to maintain your favorites. Favorites influence personal mixes and can be used as a smart-playlist rule.

Artist and track menus can start a smart radio using related music already available in the local library. Results improve as the library grows.

## Live radio

Open **Home → Radios** to browse editorial stations or search by station or city. You can play a live station immediately, save it to your Navidrome library, and remove saved stations later.

## Wrapped

When an administrator enables a Wrapped period, its card appears on Home. Wrapped summarizes listening time, top tracks and artists, listening patterns and Party Room comparisons. You can also save the displayed top tracks as a playlist.

Wrapped depends on completed listening events, so current playback may not appear until the track ends or is skipped.

## Party Rooms

Party Rooms let multiple Aoede users listen to one synchronized queue. The room host controls playback, while guests follow the shared room state. A room may optionally allow guests to add songs.

Read [Party Rooms](PARTY-ROOMS.md) for creation limits, permissions and synchronization behavior.

## Request a track deletion

Use the flag/delete-request action on a local track when it should be removed from the shared library. Enter a reason for the administrator; duplicate pending requests for the same track are prevented.

The top-bar response indicator shows approvals, rejections and any administrator response. A request does not remove a file until an administrator approves it.

## Account and provider settings

Settings are grouped into **Account**, **Appearance**, **Playback**, **Integrations** and **YouTube authentication** tabs. Switching tabs preserves unsaved fields on the page; saving a form restores the selected tab. On a phone, swipe the tab strip horizontally to reach additional categories.

In **Appearance**, choose **Classic** (the default), **Glass** (translucent, inspired by Square), or **Vitrine** (Apple Music inspired, with artwork shelves, rose accents and Liquid Glass controls inspired by Vitreo Launcher). Glass and Vitrine follow your device's light/dark mode and apply throughout the web interface, including administrative tools, mobile controls and Wrapped. Your choice is saved to your account and follows you across devices; another open device updates within the visible-page heartbeat, normally 30 seconds. A connection is required to save changes. Offline launches retain that account's last confirmed theme on the device. See [Themes](THEMES.md) for design references and implementation details.

Settings let you:

- upload an avatar;
- change your password;
- configure optional Spotify and Last.fm metadata credentials;
- set metadata-provider priority;
- upload YouTube cookies for content that requires an authenticated browser session;
- configure per-device playback preferences;
- sign out.

Treat provider secrets and YouTube cookies as credentials. Do not share them or commit them to the repository.

If **Remember me** is selected at login, Aoede stores a signed, HTTP-only session cookie rather than saving the password in the browser. The session duration is controlled by the server administrator.

## Mobile listening

You can also install the web app from your browser. Safari on macOS offers **File → Add to Dock**; supported mobile browsers offer **Add to Home Screen** or **Install app**. Aoede supplies separate regular and maskable icons, including an opaque 1024 px icon for the Mac Dock. Existing installations may retain an older icon; if so, add the updated website to the Dock again.

Other clients:

- install the Aoede Android APK — see [Android](ANDROID.md);
- connect a compatible Subsonic client to your user endpoint — see [Subsonic](SUBSONIC.md).

## Need help?

See [Troubleshooting](TROUBLESHOOTING.md).
