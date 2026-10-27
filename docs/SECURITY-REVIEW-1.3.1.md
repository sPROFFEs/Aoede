# Native Android and API review · Aoede 1.3.1

Scope: the native Android client, its authenticated JSON context/settings/admin endpoints, legacy device-data migration, private browser transport and collection retry handling. This review does not certify the application, third-party providers, Android firmware or an individual deployment.

## Boundaries and controls

| Boundary | Implementation and checks |
|---|---|
| Account authentication | Existing signed `access_token` session; live account/activity/service-account/session-version/revocation checks. New tokens also bind a random account identity. No mobile bypass or client-supplied account selection. |
| New JSON APIs | `/api/mobile/v2/context`, settings and integration writes require the current user. Administrator data additionally requires the live administrator role. Explicit response fields exclude password hashes, cookie contents, tokens, decrypted provider secrets and backup paths. Private/no-store responses. |
| Mutations | Existing same-origin middleware also applies to mobile routes. Native requests send the exact configured Origin and Referer; browser sessions gain no new CORS exception. Input DTOs reject unknown fields and enforce size/enumeration/finite numeric bounds. |
| Native HTTP | Exact scheme, hostname and port; normal certificate verification; no followed credentialed redirects or shared global cookie handler. Stale session generations are rejected. Spotify previews use the authenticated server relay. Explicit user-configured HTTP remains supported and is not encrypted; use HTTPS outside trusted networks. |
| Session storage | AES-GCM authenticated encryption, AndroidKeyStore-wrapped key, origin-bound additional data, private no-backup storage, no plaintext fallback. Remember-me controls persistence. Logout revokes the server session before clearing the local vault. |
| Offline data | Database/files are scoped by backend origin and stable random account identity. Hashed filenames, bounded downloads/artwork/JSON, complete-length/SHA-256 checks, atomic replacement and recovery of an interrupted previous file. No shared external-storage access. |
| Legacy import | Explicit temporary network-blocked WebView, no app/player rendering; exact-origin/main-frame/nonce-scoped messages. Verified previous-account proof, owned collection snapshots, bounded chunks and storage checks. Unknown/unowned audio is not automatically assigned; destructive legacy actions need review. The source IndexedDB is retained. |
| Native media | One ExoPlayer/PCM sink. Island sampling does not route audio elsewhere, open a microphone or create an extra audio owner. Activity/window changes do not recreate the native player. |
| Private verification viewer | Administrator role, owner-bound expiring browser session, CSRF and rate limits. Worker bearer remains server-side. CDP is pinned to loopback, bounded and reduced to screenshots and allowlisted input operations; no client-controlled evaluation, methods or navigation. Session lock prevents cross-session races. Screenshots are not cached; the Android viewer uses `FLAG_SECURE`. |
| Distribution | Existing fixed official updater, SHA-256/size/package/version/certificate checks and installer confirmation retained. No silent installation or signing-key fallback. CI separates read-only build from release publication. |

## Confirmed defects corrected

Switching account after a connection failure could save an old queue in the newly authenticated account's cache. Queues now retain their original storage owner; leaving a party cannot restore another account's personal queue. Queued HTTP/image work and media/download connections bind their account at connection creation, so an old operation cannot pick up a new session's credentials. Sign-in/password changes stop the prior player, and a download worker exits cleanly if its session is cleared. Regression tests exercise account changes during queue teardown and before a queued request opens its connection.

Recreating a username could previously reuse the initial session-version value of a deleted account. New accounts now start with a random nonzero version and new tokens bind their random account identity. Existing account sessions are not globally invalidated. Tests reject old/wrong-identity tokens.

Native collection creation could otherwise duplicate a collection after losing a successful response. The request identifier, body hash and resulting collection are committed together; retries are scoped by account and conflicting reuse returns 409.

Large legacy playlists cannot fit in a single bridge message. The importer now transfers records and audio in bounded chunks and verifies boundaries before committing. It never deletes the old database or quietly attributes every globally stored legacy track to the current account.

An emulator run exposed `startForegroundService()` timing out before foreground promotion. The service now posts its initial media notification in `onCreate`, reads local media metadata rather than making controller RPCs, and resolves media buttons with the known receiver component. Native background resume waits for foreground promotion before requesting focus. Tests check notification availability before `onStartCommand` and deferred resume ordering. This addresses a concrete startup ordering defect; it is not evidence that every emulator/OEM failure or the earlier Bluetooth report is resolved.

## Evidence and residual limits

Runnable checks live in `tests/test_mobile_native.py`, `tests/test_schema_migrations.py`, worker browser tests, `NativeClientTest.kt` and `android/test_native_migration.mjs`. They cover unauthorized/non-admin/other-admin requests, CSRF, DTO bounds, secret exclusion, account namespaces, failed file replacement, queue retention and audio sampling pass-through. Existing checks continue to cover updater trust and the preserved compatibility bridge.

This change has not been penetration-tested against third-party services. No production credentials, physical accessory data or private user library was used. Android process termination still stops playback; OEM overlay restrictions still apply. Old downloads without a verifiable account association remain a manual migration limitation. Real device upgrade, low-storage/interruption, Bluetooth and federated-node comparisons remain necessary before claiming broad device acceptance.
