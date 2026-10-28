"""Regression checks for the October security review; all peers/containers are mocked."""

import asyncio
import importlib.util
import io
import ipaddress
import shutil
import subprocess
import threading
from contextvars import ContextVar
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from urllib.parse import urlencode

import auth
import database
import httpx
import jwt
import network_security as network
import pytest
import request_middleware
import security
from fastapi import FastAPI, HTTPException, Request
from source_registry import infer_source, is_allowed_download_url
from starlette.datastructures import UploadFile
from starlette.responses import JSONResponse


def request(*, ip="192.0.2.12", path="/login", method="POST", headers=(), query=""):
    async def receive():
        return {"type": "http.request", "body": b""}

    return Request(
        {
            "type": "http",
            "method": method,
            "path": path,
            "scheme": "https",
            "client": (ip, 1234),
            "headers": [(b"host", b"aoede.test"), *headers],
            "query_string": query.encode(),
        },
        receive,
    )


@pytest.fixture
def main(monkeypatch):
    monkeypatch.chdir(Path(__file__).resolve().parents[2])
    with patch("docker.from_env", return_value=MagicMock()):
        import main
    monkeypatch.setattr(security, "login_attempts", {})
    return main


def test_forwarded_chain_and_rate_limiter_share_the_unspoofed_address(monkeypatch):
    from limiter import limiter

    monkeypatch.setattr(security.settings, "TRUST_PROXY_HEADERS", True)
    monkeypatch.setattr(security.settings, "TRUSTED_PROXY_IPS", "127.0.0.1,172.20.0.6")
    spoofed = [(b"x-forwarded-for", b"198.51.100.99, 192.0.2.12, 172.20.0.6")]
    proxied = request(ip="127.0.0.1", headers=spoofed)
    assert security.get_real_ip(proxied) == "192.0.2.12"
    assert limiter._key_func(proxied) == "192.0.2.12"
    assert security.get_real_ip(request(ip="203.0.113.8", headers=spoofed)) == "203.0.113.8"
    assert (
        security.get_real_ip(request(ip="127.0.0.1", headers=[(b"x-forwarded-for", b"1.1.1.1, invalid")]))
        == "127.0.0.1"
    )
    monkeypatch.setattr(security.settings, "TRUST_PROXY_HEADERS", False)
    assert security.get_real_ip(proxied) == "127.0.0.1"


def test_anonymous_login_requires_origin_and_https_scheme(monkeypatch):
    monkeypatch.setattr(security.settings, "COOKIE_SECURE", True)
    for origin in (None, "null", "https://evil.test", "http://aoede.test"):
        with pytest.raises(HTTPException) as error:
            security.validate_same_origin(request(headers=[] if origin is None else [(b"origin", origin.encode())]))
        assert error.value.status_code == 403
    security.validate_same_origin(request(headers=[(b"origin", b"https://aoede.test")]))
    # Token-based federation has no browser cookie and must remain usable.
    security.validate_same_origin(
        request(path="/api/federation/parties", headers=[(b"authorization", b"Bearer example")])
    )


def test_subsonic_rejects_disabled_service_and_unknown_accounts_and_shares_ip_lock(main, db_session, monkeypatch):
    db_session.add_all(
        [
            database.User(username="alice", hashed_password="hash", is_active=True),
            database.User(username="disabled", hashed_password="hash", is_active=False),
            database.User(username="service", hashed_password="hash", is_service_account=True),
        ]
    )
    db_session.commit()
    loop_thread = threading.get_ident()
    verifications = []

    def verify(password, hashed):
        verifications.append((hashed, threading.get_ident()))
        return password == "good" and hashed == "hash"

    monkeypatch.setattr(auth, "verify_password", verify)
    spawned = []

    def spawn(name):
        spawned.append(name)
        raise RuntimeError("authorized-test")  # Stop before any network operation.

    monkeypatch.setattr(main.manager, "get_or_spawn_container", spawn, raising=False)

    async def check():
        for name in ["disabled", "service", "missing"]:
            response = await main.gateway(
                name, "rest/ping.view", request(query=urlencode({"u": name, "p": "good"})), db_session
            )
            assert response.status_code == 403 and b"Auth failed" in response.body
        assert not spawned and verifications[-1][0] is None
        for _ in range(2):
            await main.gateway("alice", "rest/ping.view", request(query="u=alice&p=bad"), db_session)
        with pytest.raises(HTTPException) as error:
            await main.gateway("alice", "rest/ping.view", request(query="u=alice&p=good"), db_session)
        assert error.value.status_code == 429
        assert "alice" in security.get_login_blocks()
        response = await main.gateway(
            "alice", "rest/ping.view", request(ip="192.0.2.13", query="u=alice&p=enc:676f6f64"), db_session
        )
        assert response.status_code == 500 and b"authorized-test" in response.body
        assert spawned == ["alice"]  # Same username, different IP, legacy encoding works.

    asyncio.run(check())
    assert all(thread_id != loop_thread for _, thread_id in verifications)


def test_dummy_password_check_and_required_jwt_claims(monkeypatch):
    monkeypatch.setattr(auth, "SECRET_KEY", "security-review-unit-test-only-" * 3)
    dummy = MagicMock(return_value=False)
    monkeypatch.setattr(auth.pwd_context, "dummy_verify", dummy)
    assert not auth.verify_password("guess", None)
    dummy.assert_called_once_with("guess")
    assert auth.get_token_payload(auth.create_access_token({"sub": "alice"}))["sub"] == "alice"
    for payload in ({"sub": "alice"}, {"exp": 9999999999}, {"sub": 123, "exp": 9999999999}):
        assert auth.get_token_payload(jwt.encode(payload, auth.SECRET_KEY, algorithm=auth.ALGORITHM)) is None
    assert (
        auth.get_token_payload(jwt.encode({"sub": "alice", "exp": 9999999999}, auth.SECRET_KEY, algorithm="HS512"))
        is None
    )


def test_native_login_uses_the_registered_v2_context(main, db_session, monkeypatch):
    from routers import mobile

    user = database.User(username="native_login", hashed_password="synthetic-hash")
    db_session.add(user)
    db_session.commit()
    monkeypatch.setattr(
        auth, "verify_password", lambda password, hashed: password == "synthetic-pass" and hashed == "synthetic-hash"
    )
    overrides = dict(main.app.dependency_overrides)
    main.app.dependency_overrides[main.get_db] = lambda: db_session
    main.app.dependency_overrides[mobile.get_db] = lambda: db_session

    async def check():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(main.app), base_url="https://localhost") as client:
            assert (await client.get("/api/mobile/v2/context")).status_code == 401
            response = await client.post(
                "/login",
                data={"username": user.username, "password": "synthetic-pass", "remember_me": "false"},
                headers={"origin": "https://localhost", "accept": "application/json"},
            )
            assert response.status_code == 303
            assert "access_token" in client.cookies
            response = await client.get("/api/mobile/v2/context")
            assert response.status_code == 200
            data = response.json()
            assert data["protocol"] == 2 and data["account"]["username"] == user.username
            assert len(data["storage_scope"]) == 64

    try:
        asyncio.run(check())
    finally:
        main.app.dependency_overrides.clear()
        main.app.dependency_overrides.update(overrides)


def test_revoked_logout_cannot_stop_containers_and_revocation_failure_is_reported(main, db_session, monkeypatch):
    db_session.add(database.User(username="alice", hashed_password="unused"))
    db_session.commit()
    token = auth.create_access_token({"sub": "alice"})
    stopped = []
    monkeypatch.setattr(main.manager, "stop_user_container", stopped.append, raising=False)
    req = request(path="/logout", headers=[(b"cookie", f"access_token={token}".encode())])
    assert main.logout(req, db_session).status_code == 303
    assert stopped == ["alice"] and auth.is_token_blacklisted(db_session, token)
    assert main.logout(req, db_session).status_code == 303
    assert stopped == ["alice"]
    failing_db = MagicMock()
    failing_db.query.side_effect = RuntimeError("storage unavailable")
    with pytest.raises(HTTPException) as error:
        auth.blacklist_token(failing_db, "session")
    assert error.value.status_code == 503
    failing_db.rollback.assert_called_once()


def test_body_limits_cover_declared_and_chunked_uploads_and_private_cache(monkeypatch):
    monkeypatch.setattr(request_middleware, "MAX_REQUEST_BODY_BYTES", 64)
    app = FastAPI()

    @app.post("/upload")
    async def upload(request: Request):
        return {"size": len(await request.body())}

    app.add_middleware(
        request_middleware.RequestContextMiddleware,
        language_context=ContextVar("test-language", default="en"),
        supported_languages={"en"},
        default_language="en",
        validate_request=security.validate_same_origin,
    )

    async def check():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="https://aoede.test") as client:
            assert (await client.post("/upload", content=b"x" * 65)).status_code == 413

            async def chunks():
                yield b"x" * 40
                yield b"x" * 40

            assert (await client.post("/upload", content=chunks())).status_code == 413
            client.cookies.set("access_token", "test-session")
            response = await client.post("/upload", content=b"ok", headers={"origin": "https://aoede.test"})
            assert response.status_code == 200 and response.json()["size"] == 2
            assert response.headers["cache-control"] == "private, no-store"
            assert "form-action 'self'" in response.headers["content-security-policy"]
            assert response.headers["x-content-type-options"] == "nosniff"

    asyncio.run(check())


def test_dns_pinning_rejects_mixed_private_answers_and_preserves_tls_name(monkeypatch):
    answers = ["1.1.1.1"]
    monkeypatch.setattr(
        network.socket, "getaddrinfo", lambda *args, **kwargs: [(2, 1, 6, "", (ip, 443)) for ip in answers]
    )
    captured = []

    async def send(_self, request):
        captured.append(request)
        return httpx.Response(200)

    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", send)

    async def check():
        async with httpx.AsyncClient(transport=network.PinnedAsyncTransport()) as client:
            await client.get("https://provider.test:8443/audio?q=value")
            for private in ["127.0.0.1", "100.64.0.1", "169.254.169.254", "::1", "::ffff:127.0.0.1"]:
                answers[:] = ["1.1.1.1", private]
                with pytest.raises(httpx.ConnectError):
                    await client.get("https://provider.test/audio")
        assert len(captured) == 1
        pinned = captured[0]
        assert pinned.url.host == "1.1.1.1" and pinned.url.port == 8443
        assert pinned.url.query == b"q=value" and pinned.headers["host"] == "provider.test:8443"
        assert pinned.extensions["sni_hostname"] == "provider.test"

    asyncio.run(check())
    assert not network.is_public_address("224.0.0.1")


def test_party_relay_rejects_active_content_and_closes_upstream(monkeypatch):
    from routers.music import party

    clients = []
    original = httpx.AsyncClient
    content_type = ["text/html"]

    def factory(**kwargs):
        kwargs["transport"] = httpx.MockTransport(
            lambda req: httpx.Response(200, headers={"content-type": content_type[0]}, content=b"test")
        )
        client = original(**kwargs)
        clients.append(client)
        return client

    monkeypatch.setattr(party.httpx, "AsyncClient", factory)

    async def check():
        for value in ["text/html", "image/svg+xml", "application/javascript", "application/xhtml+xml"]:
            content_type[0] = value
            response = await party.relay_party_request(
                SimpleNamespace(api_token="example", base_url="https://peer.test"),
                SimpleNamespace(id=1, username="alice"),
                1,
                "join",
                request(),
            )
            assert response.status_code == 502 and clients[-1].is_closed
        assert network.safe_relay_content_type(None) == "application/octet-stream"
        for value in ["audio/mpeg", "image/jpeg", "application/json", "text/event-stream"]:
            assert network.safe_relay_content_type(value) == value

    asyncio.run(check())


def test_download_url_guard_checks_hosts_not_substrings():
    for url in [
        "https://open.spotify.com/playlist/test",
        "https://youtu.be/test",
        "https://music.youtube.com/watch?v=test",
        "https://soundcloud.com/artist/track",
        "fed:1:24",
        "ytsearch1:artist song",
    ]:
        assert is_allowed_download_url(url)
    for url in [
        "file:///etc/passwd",
        "https://127.0.0.1/youtube.com",
        "https://evil.test/spotify.com/track",
        "https://youtube.com.evil.test/watch",
        "https://youtube.com@127.0.0.1/",
        "https://youtube.com:9000/",
        "fed:0:1",
        "-o/tmp/file",
        "https://youtube.com/" + "x" * 2048,
    ]:
        assert not is_allowed_download_url(url)
    assert infer_source(raw_url="https://evil.test/spotify.com/track") == "external"


@pytest.mark.skipif(not shutil.which("docker"), reason="Nginx configuration check needs Docker (available in CI)")
def test_nginx_accepts_cloudflare_trust_configuration():
    root = Path(__file__).resolve().parents[2]
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "--network",
            "none",
            "--add-host",
            "tunnel:192.0.2.9",
            "--add-host",
            "concierge:127.0.0.1",
            "--add-host",
            "downloader:127.0.0.1",
            "--add-host",
            "updater:127.0.0.1",
            "-v",
            f"{root / 'nginx.conf'}:/etc/nginx/nginx.conf:ro",
            "-v",
            f"{root / 'cloudflare-real-ip.conf'}:/etc/nginx/aoede/real-ip.conf:ro",
            "nginx:alpine",
            "nginx",
            "-t",
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=60,
    )


def test_cookie_upload_is_bounded_atomic_and_private(db_session, tmp_path, monkeypatch):
    from routers import user

    account = database.User(username="alice", hashed_password="unused")
    db_session.add(account)
    db_session.commit()
    monkeypatch.setattr(user, "get_current_user", lambda *_: account)
    monkeypatch.setattr(user.settings, "MUSIC_ROOT", str(tmp_path))
    monkeypatch.setattr(user.templates, "TemplateResponse", lambda *_: JSONResponse({"ok": True}))
    destination = tmp_path / "alice" / "cookies.txt"

    async def check():
        assert (await user.upload_cookies(request(), UploadFile(io.BytesIO(b"cookies")), db_session)).status_code == 200
        assert destination.read_bytes() == b"cookies" and destination.stat().st_mode & 0o777 == 0o600
        assert list(destination.parent.iterdir()) == [destination]
        response = await user.upload_cookies(
            request(), UploadFile(io.BytesIO(b"x" * (user.MAX_COOKIE_FILE_BYTES + 1))), db_session
        )
        assert response.status_code == 413 and destination.read_bytes() == b"cookies"

    asyncio.run(check())


def test_new_navidrome_container_trusts_concierge_only_and_drops_privileges(monkeypatch):
    import docker

    spec = importlib.util.spec_from_file_location(
        "security_review_manager", Path(__file__).resolve().parents[1] / "manager.py"
    )
    manager = importlib.util.module_from_spec(spec)
    client = MagicMock()
    client.containers.get.side_effect = docker.errors.NotFound("no test container")
    client.containers.run.return_value.attrs = {
        "NetworkSettings": {"Networks": {"aoede-global": {"IPAddress": "172.20.0.12"}}}
    }
    with patch("docker.from_env", return_value=client):
        spec.loader.exec_module(manager)
    monkeypatch.setattr(manager, "wait_for_port", lambda *_: True)
    assert manager.get_or_spawn_container("alice") == "172.20.0.12"
    options = client.containers.run.call_args.kwargs
    assert options["cap_drop"] == ["ALL"] and options["security_opt"] == ["no-new-privileges:true"]
    trusted = [ipaddress.ip_network(value) for value in options["environment"]["ND_REVERSEPROXYWHITELIST"].split(",")]
    assert any(ipaddress.ip_address("172.20.0.5") in subnet for subnet in trusted)
    assert not any(ipaddress.ip_address("172.20.0.8") in subnet for subnet in trusted)
