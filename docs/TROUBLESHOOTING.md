# Troubleshooting

Start with service state and concierge logs:

```bash
cd Aoede/Aoede
docker compose ps
docker compose logs -f concierge
```

## Server takes minutes to return after a VM restart

Measure the local origin and tunnel separately. In the active deployment directory, use these read-only checks after the restart:

```bash
systemd-analyze time
systemd-analyze critical-chain docker.service
docker compose ps
docker compose logs --timestamps --since=20m concierge nginx tunnel
curl --max-time 5 -sS -o /dev/null -w 'Local HTTP %{http_code}, %{time_total}s\n' http://127.0.0.1/login
```

The local URL above assumes the default Cloudflare deployment's port 80. For a custom mapping use its local port and public Host header if required by `ALLOWED_HOSTS`. The `internal` and `domain` templates do not have a `tunnel` service; omit it from the logs command. An HTTP 200/302 on `/login` means the origin is responding; a 502 points to Nginx reaching an unavailable Concierge, and a 400 may indicate a Host restriction rather than slow startup. Never disable Host or certificate validation to diagnose startup.

Compare the `Startup schema ready`, `Startup recovery complete` and `Startup ready` timestamps in Concierge logs. If startup has not reached `Startup ready`, inspect the preceding errors before restarting services. Large libraries, interrupted operations and a first permission migration can take longer. Background scans may continue after the sign-in page is available.

If local login responds well before the public domain, inspect the tunnel's timestamped connection/retry errors and the VM's network/DNS readiness. Cloudflared normally selects QUIC automatically and can fall back to HTTP/2; change its protocol only after the logs establish a transport problem. See [Cloudflare Tunnel troubleshooting](https://developers.cloudflare.com/tunnel/troubleshooting/). Compose `depends_on` with `service_started` controls creation order, not application readiness, so service timestamps and local probes are more useful than assuming the tunnel waits for the downloader. See [Docker startup order](https://docs.docker.com/compose/how-tos/startup-order/). Logs may contain private hostnames; redact credentials and personal data before sharing them.

## Android opens slowly or shows “Open directly”

Update both the APK (1.1.7 or newer) and backend. Older clients waited indefinitely for playback queue state before rendering Home, and Home waited for every feed, including federated party discovery. A restored settings/admin view could also show “Open directly” if a page request or redundant CDN script failed; Android's installed shell deliberately blocks those external vendor scripts.

The updated APK starts on Home, bounds queue recovery, displays shelves independently and reuses its installed scripts. Failed settings/admin pages show their path and HTTP/network/timeout reason with Retry. A 401/403 bootstrap response still returns to login; a 5xx response never silently loads a cached account. If the error persists, report the displayed path/status and whether it happens on Wi-Fi, mobile data or both. The backend also moves pool filesystem measurement out of the bootstrap response; the initial quota is the last measurement, refreshed for subsequent launches.

## Downloads fail

Check:

- the host has outbound internet access;
- YouTube cookies are current if the content/session requires them;
- Spotify credentials are valid when Spotify-dependent discovery/enrichment is involved;
- the Download Manager shows the expected **Automatic**, **Isolated worker only** or **Legacy Concierge only** policy;
- any required lossless-provider session is connected;
- the concierge and downloader logs contain no provider, disk or permission error.

Inspect both services while retrying one job:

```bash
docker compose logs -f concierge downloader
```

The global Downloads panel includes the selected engine, fallback and error details for a failed job. Use those details before changing provider policy.

## Downloader worker is unavailable

Check its state and startup error:

```bash
docker compose ps downloader
docker compose logs --tail=200 downloader
```

After pulling a release that changes the worker image, rebuild both sides of the integration:

```bash
docker compose up -d --build downloader concierge
```

Permission errors for `/downloads`, `.worker-token` or `.auth-browser` mean the mounted data directory is not writable by the worker's configured user. Fix the host-directory ownership for your deployment and recreate the downloader container; do not make the directory world-writable.

## Provider verification fails

Open **Admin → Download Manager** and start the provider's embedded verification browser. Complete the challenge there, then select **Check verification** in the Aoede modal. The embedded browser may close or show a black screen after the challenge succeeds; this is expected while the grant returns to the worker.

If the challenge reports a network change, verification failure or Cloudflare Turnstile error:

- keep the downloader worker on one stable outbound connection for the entire flow;
- temporarily avoid a VPN or proxy that rotates or filters the worker's public IP;
- do not complete the challenge in a separate local browser;
- stop a stale verification browser before starting another one;
- check downloader logs for Chromium profile-lock or startup errors.

Verification is bound to the worker's browser and network context. A challenge completed from another machine or public IP cannot be safely transferred into the worker session.

If startup reports `pthread_create: Resource temporarily unavailable`, Chromium could not create a thread. Current Compose files use `DOWNLOADER_PIDS_LIMIT=1024` and `init: true` to reap orphaned children. The [Linux PID controller](https://docs.kernel.org/admin-guide/cgroup-v2.html#pid) counts threads against this budget; [Docker's init option](https://docs.docker.com/reference/compose-file/services/#init) reaps child processes.

After updating the source and your active Compose file, apply the worker changes with:

```bash
docker compose up -d --build --force-recreate downloader
```

A plain container restart does not apply a changed process limit or init setting. Custom deployment files must include the same downloader settings. Inspect the downloader logs for resource-exhaustion errors. Check actual PID and memory pressure before increasing the limit further:

```bash
docker stats --no-stream aoede_downloader
docker compose exec downloader sh -c 'cat /sys/fs/cgroup/pids.current /sys/fs/cgroup/pids.max /sys/fs/cgroup/pids.events'
```

The cgroup paths above apply to cgroup v2. A nonzero `max` counter in `pids.events` records attempts that hit the PID limit. If the task budget has room, inspect available memory and any host-level process limits as well. Missing D-Bus or CPU-frequency files can accompany Chromium's logs and do not alone establish the cause of a startup failure.

If the verification browser repeatedly fails to start, update both downloader and Concierge and check the worker logs. A slow host can set `AUTH_BROWSER_STARTUP_TIMEOUT=60` in its Compose environment. Recreate both services after changing it:

```bash
docker compose up -d --build downloader concierge
```

Wait for the browser to become ready before verifying. Close a failed session before trying again.

## Provider says Connected but downloads fail

Provider session lifetimes are controlled by the provider. The worker checks stored sessions every 15 minutes and attempts normal signed renewal before expiry, including while idle.

The **Connected** label refreshes when the Download Manager loads, when you select Refresh, and after a verification check. It is not continuously polled, and a provider may revoke a session before its recorded expiry. Refresh the page/status, inspect downloader logs, and reconnect the provider if the next download reports an authentication failure.

## Age-restricted YouTube content fails

Export a fresh Netscape-format `cookies.txt` from a logged-in browser session and upload it again in **Settings → Engine**.

Treat this file as a credential and never commit it.

## Covers are missing

Configure Spotify and/or Last.fm in **Settings → Engine** and check provider connectivity. Metadata priority can also affect which provider is used first.

## Permission errors under the data root

For the default data root, the project documents:

```bash
sudo chown -R 1000:1000 /opt/saas-data
sudo find /opt/saas-data -type d -exec chmod 750 {} +
sudo find /opt/saas-data -type f -exec chmod 640 {} +
```

If you use a custom `HOST_DATA_ROOT`, substitute that path.

## Backend cannot control Docker

Verify that the Docker socket is mounted into the concierge container as expected by the active Compose file:

```text
/var/run/docker.sock
```

Also verify Docker itself is healthy on the host.

## Internal deployment login loops

Plain HTTP requires:

```dotenv
COOKIE_SECURE=false
```

A browser will reject a `Secure` session cookie over HTTP, producing an apparent login loop.

## Update notification does not appear

Check that:

- you are logged in as an admin;
- the backend can reach the configured update repository;
- `UPDATE_SOURCE_REPO_URL` / `UPDATE_SOURCE_BRANCH` are correct for your deployment;
- concierge logs do not show outbound network errors.

## TLS certificate renewed but old certificate is served

Reload nginx:

```bash
docker compose exec nginx nginx -s reload
```

For direct-domain deployments, set up the daily reload described in [Deployment](DEPLOYMENT.md).

## Certbot rate limit during setup testing

Use the Let's Encrypt staging endpoint while debugging repeated issuance attempts, then switch back to production once the flow is correct.

## Large import behaves unexpectedly

Before retrying:

```bash
./import_music.sh --help
```

Use `--dry-run` and a small test folder. Remember that the importer moves source audio into the shared pool.

## Still stuck?

Collect these before opening an issue:

```bash
docker compose ps
docker compose logs --tail=200 concierge
docker compose config
```

Remove secrets, private domain information, tokens, cookies and personal library data before sharing logs publicly.
