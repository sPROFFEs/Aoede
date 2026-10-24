# Security review — 24 October 2026

Baseline: `26b6b04` (Aoede 1.2.8). This review covers the web backend, browser frontend, public and authenticated APIs, federation and media relays, download entry points, uploads, updater authentication, dependency declarations and the supplied proxy/Compose configurations.

The review uses source inspection, local test databases, ASGI requests and mocked upstream responses. No production credentials, production database or deployed network configuration was inspected. No attack was run against a public service. Findings below distinguish code defects from deployment-dependent exposure. This is a point-in-time review, not a guarantee that the application cannot be compromised.

## Threat boundaries

The protected assets are account sessions, user-owned/private collections, provider secrets, the shared music pool, administrative operations and access to the host's Docker daemon. Inputs can come from anonymous visitors, ordinary accounts, browser origins, uploaded files, external media providers and configured federation peers. A federation connection is not permission to serve executable content under the local web origin or to reach arbitrary internal services.

## Findings and remediation

| ID | Severity | Finding and condition | Remediation |
|---|---|---|---|
| WEB-01 | High | The password-based Navidrome/Subsonic gateway did not check account availability or the web login's failed-attempt store. An attacker knowing an inactive account's password could continue using that route; password guessing could bypass the web login restriction. | Apply the same active/non-service/valid-username policy and IP failure store to both authentication paths. Verify passwords away from the ASGI event loop and return generic authentication failures. |
| WEB-02 | High | Radio and federation relays copied upstream `Content-Type` into same-origin responses. A logged-in victim opening a crafted relay URL could receive attacker-controlled HTML/SVG under Aoede's origin. HttpOnly does not stop such content from issuing authenticated requests. | Reject active document types, provide a safe binary default when the upstream omits its type, and retain `nosniff`. Apply the policy to normal federation streams, party relays and image relays. |
| WEB-03 | High | URL checks resolved DNS before HTTP clients connected and resolved again. A changing DNS answer could cross the private-network boundary. The old address predicate also accepted shared address space such as `100.64.0.0/10`. | Validate and pin the actual destination IP in public/federation transports; preserve the original HTTP Host and TLS SNI. Reject non-global addresses by default. Keep private federation an explicit administrator opt-in. |
| WEB-04 | High | Trusted forwarded IP handling selected the leftmost `X-Forwarded-For` value. Nginx appended its peer address, and default forwarding settings could instead group every visitor under a proxy IP. SlowAPI used a different IP function. | Walk the trusted chain from the right, use that resolver for rate limits, and let Nginx trust Cloudflare headers only from the Docker DNS identity of the tunnel. Scope backend trust to Nginx, keep the tunnel's dynamic address, and disable Uvicorn's separate header rewrite. |
| WEB-05 | Medium | Unknown/inactive web usernames short-circuited expensive password verification, making the response path different from a normal credential failure. Bcrypt ran on the event loop. | Perform a real or dummy verification for every attempted username and offload it to a worker thread. Add an IP-based login request limit. |
| WEB-06 | Medium | Same-origin checks did not protect an initial login without an existing session cookie and compared host only. | Validate anonymous login submissions as well as cookie-authenticated writes, include the deployment's expected scheme, and add CSP `form-action 'self'`. |
| WEB-07 | Medium | Avatar/cover uploads were fully read before their size check; cookie uploads had no application size bound and overwrote their destination directly. Public access to backend port 8000 could bypass Nginx's upload restrictions. | Bound request bodies and individual reads, cap cookie files, write cookie replacements atomically with private permissions, and bind the backend's published port to loopback in supplied deployments. |
| WEB-08 | Medium | Radio station IDs and selected federated room fields were interpolated into HTML/inline handlers without the same escaping/type conversion as titles. A malicious upstream could cross the data/markup boundary. | Escape station IDs for JavaScript attributes, normalize numeric room fields/keys and restrict rendered playback status to known values. |
| WEB-09 | Medium | Generic download URLs were passed to downloader engines; source detection used substrings anywhere in a URL, including attacker-controlled paths. | Check advertised provider hostnames and supported internal search/federation references before queueing and again at engine dispatch. Bound URL length and reject credentials, arbitrary hosts and unexpected ports. |
| WEB-10 | High | The declared HTTP framework/image stack and installed transitive framework dependencies matched published advisories. spotDL's dependency constraint kept FastAPI on the old branch. | Upgrade the served framework/image stack, isolate spotDL's CLI dependencies in private virtual environments, and replace the unpatched JWT dependency with a compatible maintained implementation. Adapt template calls to the current request-first API. |
| WEB-11 | Low | The updater's internal bearer token comparison used normal string equality. Personalized dynamic responses did not consistently set cache policy. | Use constant-time comparison and add `private, no-store` where authenticated dynamic responses do not already specify their own policy. Preserve explicit private audio caching. |
| WEB-12 | Medium | Logout extracted a username from a signed token without checking whether that token was revoked or the account was still available. Replaying an obsolete token could therefore stop the user's Navidrome container. Token-revocation storage failures were also swallowed. | Require a current session before privileged logout side effects and report a 503 if revocation cannot be persisted. An obsolete token can only have its browser cookie cleared. |
| WEB-13 | Medium | New Navidrome containers trusted identity headers from the entire `172.16.0.0/12` range. Another compromised container on that network could assert a username. This requires access to the internal container network; it is not an unauthenticated public login bypass. | Trust only Concierge at `172.20.0.5/32` and loopback by default. Expose an explicit custom-network override. New user containers also drop capabilities and forbid privilege escalation. Existing containers need recreation to receive their updated environment/security options. |

The fixes are included in Aoede 1.2.9 and Android 1.1.11 (17). Desktop clients keep their independent version 1.2.9; the original Android wrapper 1.0.5 is retained as a backup.

## IP restrictions: what is actually blocked?

`security.login_attempts` is keyed by **source IP**, not username. `get_login_blocks()` associates a blocked IP with attempted usernames so the administrator can locate the event beside an account. That association does not disable the account globally. Another IP can still authenticate as the same active user; another username on a blocked IP is still restricted.

The policy remains five failed attempts followed by a 15-minute restriction, with stale failures resetting after five minutes. Web login and password-based Subsonic requests share the restriction. The username shown in the admin toast is attribution, not the key used for enforcement. The restriction is in memory and per process: a restart clears it, and multiple workers/replicas require a shared rate-limit backend or ingress enforcement. There is no new username-wide ban that an attacker could trigger to lock a victim out from every location.

## Dependency evidence

The dependency check queried OSV for declared pinned packages and selected installed transitive versions on the review date. Installed transitive versions are evidence for the local environment; production must be rebuilt to obtain the new resolved graph. Advisory aliases are not counted as independent vulnerabilities, and a package match does not prove that every vulnerable feature is reachable in Aoede.

| Package | Baseline evidence | Patched web-server dependency |
|---|---|---|
| FastAPI | `0.103.2`; [CVE-2024-24762 advisory](https://github.com/Kludex/python-multipart/security/advisories/GHSA-2jv5-9r88-3w3p). The already-new multipart package mitigated part of the old dependency issue, but the framework still constrained the stack. | `0.162.0` |
| Starlette | Installed `0.27.0`; multipart denial-of-service, Host validation and later advisories, including [GHSA-2c2j-9gv5-cj73](https://github.com/Kludex/starlette/security/advisories/GHSA-2c2j-9gv5-cj73). | `1.9.0` |
| AnyIO | Installed `3.7.1`; [TLS/IDNA advisory](https://github.com/agronholm/anyio/security/advisories/GHSA-82r6-8w77-94w6). The process-pool advisory is also listed but Aoede's reviewed paths use thread offloading, not that process-pool feature. | `4.15.1` |
| Pillow | `12.3.0`; multiple decoder/encoder advisories fixed in 13.0.0. Aoede accepts untrusted images; not every affected format/operation is used. See [GHSA-22hg-r758-rrw9](https://github.com/python-pillow/Pillow/security/advisories/GHSA-22hg-r758-rrw9). | `13.0.0` |
| python-jose | `3.5.0`; [DER algorithm-confusion advisory](https://github.com/mpdavis/python-jose/issues/414). The normal HS256/random-secret configuration does not supply a DER public key, so this is not an assertion of an exploitable default JWT bypass. | Replace with `PyJWT[crypto] 2.15.1`; retain the configured algorithm allowlist and require `exp`/`sub`. Existing valid HS256 sessions remain compatible. |

The five selected replacement versions returned no matching OSV advisories when queried. A second query covering all 76 distinct package/version pairs in the freshly resolved Concierge and worker application graphs also returned no matches. These statements are limited to those queries and date. spotDL retains its upstream-required legacy dependency graph **only inside its CLI virtual environment**; the application runs the patched global Uvicorn/FastAPI environment. Only the `spotdl` executable is linked into the command path. Do not run spotDL's optional web server.

The main changes are in `auth.py`, `security.py` and `main.py` (authentication); `network_security.py`, the federation/party/streaming routers and `http_client.py` (outbound boundaries); `request_middleware.py` and upload handlers (request limits); `source_registry.py` and downloader dispatch (URLs); `party.js`/`radio.js` (rendering); and the deployment templates/Dockerfiles (proxy trust and dependency isolation), all relative to the baseline revision above.

## Existing controls checked

- Central session validation rejects revoked tokens, inactive/service accounts and obsolete session versions; password changes revoke older sessions.
- Administrative actions check the administrator role on the server. Collection visibility and collaboration are separate permissions, including cross-node operations.
- Federation publishing uses revocable bearer tokens and scoped remote user identities. JSON catalogue reads already have a byte limit; the party discovery/relay paths receive the same protection.
- Media filesystem access uses resolved allowed roots rather than trusting stored paths alone. SQLAlchemy request queries bind values; schema SQL uses code-owned identifiers.
- Provider secret storage uses authenticated encryption derived from the server secret. Worker/provider verification endpoints require their dedicated token or an administrator's scoped session.
- The gateway forwards a header allowlist and injects its own Navidrome identity; upstream cookies and arbitrary client identity headers are not forwarded.

## Deployment changes needed

These source/configuration changes have not been applied to a running production VM by the review. Existing environment files are not silently overwritten.

1. Retain a working backup and the existing `SECRET_KEY`; this update does not require rotating credentials.
2. In the active Compose environment, enable `TRUST_PROXY_HEADERS=true` and set `TRUSTED_PROXY_IPS=127.0.0.1,::1,172.20.0.6` for the supplied network. For custom networking, substitute only the actual trusted proxy addresses. Keeping forwarding disabled means IP-based restrictions will still identify the proxy.
3. Rebuild/recreate the web, downloader and proxy services from the updated configuration. The updater service also needs the updated image after any active update finishes. The default Cloudflare deployment mounts the new optional `cloudflare-real-ip.conf`; internal/domain templates do not trust Cloudflare headers.
4. Keep the tunnel's dynamic Docker address. Nginx resolves `tunnel` at startup; if the tunnel container is replaced with a different address, recreate/restart Nginx as well. The application updater deliberately does not restart the live tunnel.
5. The backend's host port 8000 is loopback-only by default. Remote clients should use the public Nginx/TLS endpoint. Explicit custom direct-backend deployments need a reviewed proxy/IP trust configuration.
6. Install the refreshed Android frontend for client-side escaping fixes; updating a server alone cannot replace JavaScript already packaged inside an older APK.
7. Recreate existing per-user Navidrome containers during maintenance to apply the narrower identity-header trust and container security options. Preserve their mounted user data and music directories. Changing a Compose environment variable does not rewrite the environment of an already-created user container. Custom networks must set `NAVIDROME_REVERSE_PROXY_WHITELIST` to the actual Concierge address.

Cloudflare appends proxy addresses to `X-Forwarded-For`; it documents `CF-Connecting-IP` for recovering the visitor identity. Only a trusted ingress may assert that header. [Cloudflare headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/). DNS pinning preserves TLS hostname validation through the documented `sni_hostname` extension. [HTTPCore extensions](https://www.encode.io/httpcore/extensions/).

## Residual limits

- The Concierge intentionally controls Docker. Its Docker socket mount makes a successful code-execution compromise much more serious; removing that authority requires separating orchestration into another service. New per-user Navidrome containers drop capabilities and forbid privilege escalation, but this does not turn the Concierge into a sandbox.
- CSP still allows inline scripts/styles because the current frontend uses inline handlers. Escaping fixes close the identified sinks; a full nonce/event-listener migration remains architectural work.
- IP throttling is not protection against a distributed attack. Host firewall, TLS termination, upstream rate limits, backups and actual proxy addresses were not verified on the user's deployment.
- Private federation is intentionally a trust decision when explicitly enabled. Downloader engines also fetch secondary URLs from supported providers; unrestricted outbound isolation requires deployment-level egress controls.

## Validation

- Local `npm run check` passes: **382 Python tests, two optional integrations skipped**, plus the existing frontend, desktop and Android bridge/compiler checks with the upgraded server dependencies. The suite includes IP-chain spoofing, Subsonic restrictions, anonymous-login origin checks, session revocation, declared/chunked body limits, pinned DNS/TLS names, active-content relay rejection, download URL validation, private atomic cookie replacement and Navidrome container identity trust.
- Both complete application requirements files resolve successfully. All 76 distinct resolved application package/version pairs returned no OSV matches on the review date. Both isolated spotDL CLI versions install and answer `--version` (4.5.0/4.5.2); their legacy dependency graph is not counted as part of the patched web-server graph.
- JavaScript regression checks render hostile radio/party fields and execute the generated radio handlers in an isolated VM, verifying that values remain data in both mobile and desktop layouts.
- The Nginx syntax check uses an isolated Docker container in CI. It is skipped locally because this machine has no Docker CLI; no running production stack or production firewall was tested. The existing optional loudness integration test is also skipped when its runtime tool is unavailable.
- Android release packaging, signature continuity and CI results are verified before publishing the release. No claim of production penetration testing or complete invulnerability is made.
