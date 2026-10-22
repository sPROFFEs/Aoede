# Importing Music

Aoede can import online albums/playlists through the web app, or move an existing host music collection into the shared pool with the command-line importer.

## Import an online album or playlist

1. Open **Search** and enable **Albums / Playlists** to search Spotify and YouTube collections. This mode is off by default and replaces ordinary song searches while enabled. Alternatively, in **Downloads**, enable **Album / playlist URL**, paste a Spotify album/playlist, YouTube playlist, or YouTube Music album URL, and select **Preview collection**. Recognized collection URLs also open the preview when the toggle is off.
2. Select a result to preview its ordered songs, unavailable entries, name, and collection type.
3. Choose **Album** or **Playlist**, optionally change the name, and select **Import**.

Aoede creates a private collection owned by the signed-in user. Songs are downloaded into the shared pool using the existing downloader, or reused when already available. Before downloading, imports match the source ID or normalized artist/title/version against existing pool songs, including songs downloaded from another provider. Missing files are downloaded again rather than reused as broken tracks; live, remix and other recognized versions remain distinct. The collection keeps source order and repeated songs; it appears in **Your Library → Albums** or **Playlists**, without merging other songs that happen to carry the same album tag. Albums support the same playback, editing, covers, sharing, collaboration, and offline downloads as playlists. A curated album does not change the tags of songs already in the pool.

Imports support up to **500 source entries**, with at most **500 active downloads per importing user**. Unavailable/private/deleted entries are reported in the preview and skipped. Failed downloads leave the collection intact: open its **Downloads** button and retry the failed songs. Retries keep their original positions. Reordering is disabled while songs are being imported and becomes available once the active jobs finish. Interrupted jobs are marked failed after a server restart and can be retried.

Spotify collection lookup uses the Spotify credentials in **Settings**. Requests include the **ES** market, matching the existing Spotify artist/recommendation catalogue, so client-credentials requests do not incorrectly treat every song as unavailable. Both modern `items` and legacy `tracks` responses and their pagination endpoints are supported; metadata-only track summaries are followed by the dedicated tracks request.

Spotify may return a playlist's name and artwork while withholding its songs, even for a public playlist. When the API denies collection access or omits the track list, Aoede attempts Spotify's anonymous public web/embed metadata. It checks the collection identity and full published entry count, preserves source order and repeated songs, and never sends the Spotify API token or cookies to public pages. Private/unpublished collections and shortened public previews cannot be imported through this fallback; the preview explains the limitation instead of creating an incomplete or empty collection. Bad credentials, rate limits and provider outages retain their own errors. See [Spotify's February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

This is a backend fix: update the Aoede server; the current web app, Android APK and desktop wrappers use the same resolver. YouTube imports require a playlist/album visible to the server without signing in.

Collection searches are cached for an hour and previews for ten minutes. Turning off **Albums / Playlists** returns to ordinary song search without issuing collection searches.

If you reorder a partially failed import before retrying it, a retried song whose original position is occupied is appended to preserve your edits. Retry failed songs before reordering to retain the source structure exactly.

## Import local files

> [!WARNING]
> The importer **moves** supported audio files into the Aoede pool. Do not point it at your only copy of a library until you have verified your backup strategy.

## Basic import

Run from the repository root:

```bash
./import_music.sh /path/to/your/music
```

To enrich missing artwork/metadata using providers configured in Aoede:

```bash
./import_music.sh /path/to/your/music --enrich
```

## What the importer does

- Recursively scans supported audio formats.
- Moves tracks into the shared pool under an artist/album structure.
- Reads tags using Mutagen.
- Registers tracks in the Aoede database.
- Saves embedded cover artwork to the cover cache.
- Can fetch missing artwork/metadata from configured providers when `--enrich` is used.
- Detects duplicates using hash/fingerprint information and skips/reuses them as appropriate.

Supported extensions documented by the project include:

```text
mp3  m4a  flac  wav  ogg  opus  aac  wma
```

The shared pool is stored under the configured `HOST_DATA_ROOT` (default `/opt/saas-data`).

## Important behavior

Imported tracks become available to users through Aoede's library/search flows. Bulk import does not automatically create personal playlists or assign the imported tracks to one specific user.

## Useful flags

```text
--enrich
--dry-run
--workers N
--verbose
```

For the currently installed script's full option list:

```bash
./import_music.sh --help
```

## Before a large import

1. Back up the source collection.
2. Confirm free disk space under the Aoede data root.
3. Run a dry run first.
4. Configure Spotify and/or Last.fm if you want enrichment.
5. Start with a small representative folder before migrating everything.

## Related guides

- [Configuration](CONFIGURATION.md)
- [Backup & Restore](BACKUP-RESTORE.md)
- [Troubleshooting](TROUBLESHOOTING.md)
