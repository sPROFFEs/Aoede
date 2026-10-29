# Security

Aoede is self-hosted software that orchestrates containers and stores credentials for optional external providers. Treat the host and its configuration as privileged infrastructure.

## Secrets

Never commit or publicly share:

- `Aoede/.env`;
- `SECRET_KEY`;
- Cloudflare tunnel tokens;
- Spotify client secrets;
- Last.fm keys you consider private;
- YouTube `cookies.txt`;
- database files;
- backup archives containing configuration or user data.

## `SECRET_KEY`

Set a long random value before production use.

```bash
openssl rand -hex 48
```

Aoede uses this key for authentication-related signing and encryption of stored provider credentials. Keep a secure copy with your backup/recovery material.

## Sessions

- The **Remember me** flow stores a signed HttpOnly session cookie, not the user's password.
- Keep `COOKIE_SECURE=true` for HTTPS deployments.
- Use `COOKIE_SECURE=false` only for trusted plain-HTTP LAN/VPN testing.
- Web and password-based Subsonic login share failed-attempt restrictions: five failures restrict the source IP for 15 minutes. The username shown in the admin information toast attributes attempts; it does not globally block the account. Restrictions are per process and reset on restart.


## TLS

For any deployment reachable over an untrusted network, use HTTPS:

- Cloudflare Tunnel mode terminates TLS at Cloudflare's edge.
- Direct-domain mode uses Let's Encrypt.
- Internal mode has no TLS and must remain on a trusted LAN or inside a VPN.

## Docker socket

The concierge needs Docker control for user-container orchestration. A mounted Docker socket effectively gives the service powerful control over the host's container environment.

Recommendations:

- restrict host shell access;
- restrict Aoede admin access;
- keep the host patched;
- avoid running unrelated untrusted workloads on the same Docker daemon;
- review Compose changes before deploying them.

## Reverse proxy headers

`TRUST_PROXY_HEADERS` should only be enabled when requests are actually coming through a trusted proxy, and `TRUSTED_PROXY_IPS` should be scoped to the proxy addresses/CIDRs you control.

The supplied deployments trust Nginx at `172.20.0.6` and disable Uvicorn's separate forwarded-header rewrite. Existing environment files must enable this explicitly if they still contain `TRUST_PROXY_HEADERS=false`. Forwarded chains are evaluated from the right; a visitor cannot choose their identity by prepending an address.

Only the Cloudflare deployment mounts `Aoede/cloudflare-real-ip.conf`. Nginx trusts `CF-Connecting-IP` from the Docker DNS identity of `tunnel`, not from direct visitors. Keep the tunnel's dynamic address and restart Nginx if replacing that container changes its address. Internal/direct-domain templates do not mount this configuration. Backend port 8000 is published only on host loopback.

## External content and uploads

Public media relays reject private/non-global destinations, pin the validated IP while retaining TLS hostname checks, and reject executable HTML/SVG response types. Private federation requires `FEDERATION_ALLOW_PRIVATE_HOSTS=true` and a reviewed peer. Download entry points accept advertised music-provider URLs and internal search/federation references, not arbitrary URLs.

Requests are capped at 16 MiB, avatar/cover files at 5 MiB and cookie files at 1 MiB. Cookie replacements use private permissions and an atomic rename. spotDL runs as a separate CLI environment so its legacy web dependencies cannot constrain the application's patched HTTP framework; do not launch spotDL's optional web server.

## Backups

Backups can contain user data and secrets. Encrypt them at rest when stored outside the host and test restores periodically.

See [Backup & Restore](BACKUP-RESTORE.md).

## Screenshots and issue reports

Before publishing a screenshot or log, remove:

- private domain names when necessary;
- usernames you do not want public;
- tokens and secrets;
- cookies;
- private playlists/library data;
- IP addresses that reveal private infrastructure.

## License note

Security guidance does not change the project's license. Aoede is licensed for private, personal, non-commercial use only. Read the root [LICENSE](../LICENSE) for binding terms.
