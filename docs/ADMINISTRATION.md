# Administration

Administrative tools are exposed from the Aoede settings/monitoring areas for admin accounts.

## Admin areas

The admin navigation is divided into three main areas:

- **User Admin** — users, library maintenance, deletion requests and federation;
- **System Monitor** — runtime health, user statistics, backups, Wrapped and updates;
- **Download Manager** — downloader policy, jobs and lossless-provider sessions.

All routes and API endpoints behind these pages require an authenticated administrator account.

Each area has its own task tabs. **Admin** separates **Users**, **Library**, **Duplicates**, **Maintenance** and **Federation**. **System Monitor** separates **Overview**, **Statistics**, **Updates**, **Backups**, **Wrapped** and **Jobs**. **Download Manager** separates **Runtime**, **Provider sessions** and **Engine policy**. Tabs scroll horizontally on smaller screens, support arrow/Home/End keys, and retain the selected task after a form response or navigation in the same session.

Administrative screens use the viewer's **Settings → Appearance** theme. The additional Glass theme is an account preference, not a server-wide setting. Migration `034_user_theme` adds it with Classic as the default. Deploy the updated Concierge together with its templates and assets; the desktop/Android web wrappers consume the frontend update without rebuilding their binaries. See [Themes](THEMES.md).

## User management

Use **User Admin** to create standard or administrator accounts, reset a user's password, or delete an account. Account deletion is destructive; create a backup first if the user's data may be needed later.

## Library management

The **Library**, **Duplicates** and **Maintenance** tabs provide separate operations:

- **Search tracks** — find an indexed track and remove it deliberately;
- **Find duplicates** — scan for likely duplicate groups for manual review; the scan does not automatically delete files;
- **Audit Library** — compare indexed records with files, report missing files, incomplete metadata, source totals and loudness-analysis coverage;
- **Remove broken entries** — delete database records whose audio file no longer exists;
- **Rescan metadata** — rebuild metadata from the current files;
- **Scan loudness** — measure tracks used by volume normalization. Large libraries can take hours.

Long-running operations appear as background jobs. Do not repeatedly start the same scan because a page refresh makes it look idle; check the current/recent jobs first.

The duplicate scan ignores blank source IDs/hashes and combines overlapping exact/tag matches. **Exact** groups share a source ID or file hash across all members; **Possible** groups share metadata requiring manual listening. **Similar tracks** are separate pairs: fresh normalization handles provider labels, accents, punctuation, leading track numbers, artist/title prefixes, featured credits and small spelling changes. A missing title can use the filename. Similarity uses title, artist and compatible durations, and still works without stored fingerprints. Missing/different artist tags require an almost identical distinctive title and durations within two seconds. Live/acoustic/remix versions remain separate from studio recordings; original/remaster suggestions carry a version warning.

Each similarity pair shows a metadata score, title/artist agreement and duration difference. This is a review aid, not an audio fingerprint or probability that the recordings are identical. Approximate links do not merge exact groups. Results reserve space for similarity pairs when many exact groups fill the response limit. The fuzzy search bounds postings and comparisons to 64 candidates per track and returns up to 500 similarity pairs; a visible limit notice means some fuzzy candidates may be missed. Normalized identical metadata also uses a separate index so older matches survive posting saturation. The scan runs as an administrator background job; the legacy GET endpoint uses a worker thread.

Filter results by match type or song/artist/album/filename, and compare provider, duration, format, size, file availability and collection usage. The suggested song to keep prioritizes an available file, then more collection references, then the oldest entry; it is not an audio-quality assessment. Nothing is deleted automatically. Deleting a pool song removes its collection and favorite references, so compare candidates first. This does not remove intentional repeated entries within an imported playlist.

For a host-side bulk migration of an existing collection, use [Importing Music](IMPORTING-MUSIC.md) instead.

## Song deletion requests

Users can request removal of a shared track without receiving direct delete permission. Review these in the **Song Delete Inbox**:

- **Approve** removes the requested track;
- **Reject** leaves it in place;
- an optional admin response is shown to the requesting user.

Review the track and reason carefully: approval affects the shared pool, not just the requesting user's view.

## Download Manager

Open `Admin → Download Manager` to inspect the isolated downloader, switch between automatic, worker-only and legacy modes, and inspect SpotiFLAC lossless-provider sessions.

SpotiFLAC binds verification to the network and browser context that starts the challenge. The Download Manager can open a short-lived noVNC browser running inside the downloader worker, so verification and grant exchange use the worker's network. The session is single-admin, expires automatically, and is stopped when the administrator closes it. Aoede never asks for TIDAL, Qobuz, Deezer or Amazon credentials.

The default **Automatic** policy tries the isolated worker first and retains the Concierge downloader as a compatibility fallback. **Isolated worker only** disables that fallback; **Legacy Concierge only** bypasses the isolated worker.

A lossless provider is attempted only while its signed session is connected and unexpired. To connect one:

1. Select **Connect** for the provider.
2. Open its verification browser.
3. Complete the challenge in the embedded browser. The browser may turn black or close after successful verification.
4. Select **Check verification** in Aoede to exchange and store the signed grant.
5. Confirm that the provider status changes to **Connected**.

The worker checks connected sessions every 15 minutes and invokes the provider's normal signed refresh before expiry, even when no downloads are running. The status label updates when the page is loaded, manually refreshed, or verification completes; it is not a second-by-second validity probe. A provider can revoke a session early, so a download failure may be the first signal between refreshes. Use **Disconnect** to remove a stored session.

After pulling a version that changes the downloader image, rebuild it as part of the normal update:

```bash
cd Aoede
docker compose up -d --build downloader concierge
```

## Create an admin manually

If setup did not create the first admin, run this from the directory containing `docker-compose.yaml`:

```bash
read -r -p "Admin username: " AOEDE_ADMIN_USERNAME
read -r -s -p "Admin password: " AOEDE_ADMIN_PASSWORD; printf '\n'
export AOEDE_ADMIN_USERNAME AOEDE_ADMIN_PASSWORD

docker compose exec -T \
  -e AOEDE_ADMIN_USERNAME \
  -e AOEDE_ADMIN_PASSWORD \
  concierge python create_admin.py

unset AOEDE_ADMIN_USERNAME AOEDE_ADMIN_PASSWORD
```

The password is read without terminal echo and passed through the environment rather than as a visible command-line argument.

## System Monitor

The System Monitor combines operational information and actions:

- CPU, memory, shared-pool usage and the running build/revision;
- per-user listening totals for 24 hours, 7 days, 30 days, year or all time;
- update checks and application;
- current and previous rotating backup slots plus the daily backup schedule;
- Wrapped visibility, schedule, regeneration and per-user reset;
- recent background jobs and storage cleanup.

User statistics can be sorted and paginated. Listening events are finalized when playback ends or is skipped, so a currently playing track may not appear immediately.

Storage cleanup and Wrapped reset actions are intentionally destructive. Read their confirmation text and create a backup before using them to repair data.

## Updates from the UI

The documented flow is:

```text
Admin → System Monitor → Updates → Check for Updates → Apply Update
```

The update workflow can create a backup, run schema migrations, rebuild containers when needed and perform health checks.

## CLI update

From the repository:

```bash
git pull
cd Aoede
docker compose up -d --build
```

If you use a copied deployment template, pay attention to merge conflicts in the live Compose/nginx files so you do not accidentally switch deployment mode.

## Federation (beta)

Federation lets Aoede servers discover and stream each other's music pools. Catalogue metadata is mirrored locally; audio remains on its source server. It is off until an administrator configures it. Version **1.2.8** also publishes active community profiles, their public collections and favorites/statistics to authorized peers, and supports invited collaborators across nodes.

To allow another server to access this one, issue a named federation token and give it securely to the remote administrator. To consume a remote catalog, add its Aoede URL and the token it issued to you, then run a sync. Connections can be disabled, resynchronized or removed, and issued tokens can be revoked.

Only federate with administrators you trust. Use HTTPS, transmit tokens through a private channel, and revoke a token when the relationship ends.

For cross-node collaboration:

1. Upgrade both nodes to **1.2.8 or newer**. Startup runs migration `033_federated_collections`, preserving existing playlists and adding remote membership/source references.
2. On node A, issue a token for B and set that peer's URL to B's public Aoede base URL. B subscribes to A using the token.
3. On B, issue a token for A with A's public URL. A subscribes to B using that token. Peer URLs must match the subscription URLs; a different hostname, protocol or base path does not identify the same node.
4. Check that both subscriptions are enabled and healthy/degraded, then invite a remote listener from the collection's **Collaborators** picker.

A one-way subscription is sufficient to browse profiles and public collections and stream their songs. Collaboration needs both directions so the collection owner can verify the invited account's node and access its music. Tokens identify nodes; remote users have identities scoped to the issuing peer and user ID. Peer administrators assert their own users' identities, so this is a trust relationship rather than shared passwords or shared admin access.

Private collections stay off public discovery. The owning node validates access to collection details, media and every edit. Revoking a peer token blocks its requests and invitations; disabling a subscription blocks its local proxies. Copies are read-only references and do not preserve access after a source becomes private or unavailable. Remote audio is played through authenticated Aoede proxies, including artwork and seek ranges; bearer tokens are never sent to clients. Navidrome exports contain local files only.

## Backups

Aoede provides rotating application backup slots and also benefits from a host-level backup of the complete data root and `.env`.

See [Backup & Restore](BACKUP-RESTORE.md).

## Logs

A useful first diagnostic command is:

```bash
docker compose logs -f concierge
```

For downloader/provider diagnostics:

```bash
docker compose logs -f downloader
```

For a broader service view:

```bash
docker compose ps
```

## Security note

The concierge service controls Docker to orchestrate user containers. Treat the host as privileged infrastructure and restrict administrative access accordingly.

See [Security](SECURITY.md).
