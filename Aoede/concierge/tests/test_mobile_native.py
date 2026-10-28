import asyncio
import importlib.util
import json
import sys
from contextvars import ContextVar
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

import auth
import database
import httpx
import security
from fastapi import FastAPI
from request_middleware import RequestContextMiddleware

# The worker is a separate process with its own module of the same name.
spec = importlib.util.spec_from_file_location(
    "native_contract_browser", Path(__file__).resolve().parents[1] / "auth_browser.py"
)
browser = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {spec.name: browser, "auth_browser": browser}):
    spec.loader.exec_module(browser)
    from routers import mobile


def test_native_contract_keeps_existing_auth_csrf_and_account_isolation(db_session):
    alice = database.User(username="native_alice", hashed_password=auth.get_password_hash("Strong!Pass12"))
    bob = database.User(username="native_bob", hashed_password=auth.get_password_hash("Strong!Pass12"))
    admin = database.User(username="native_admin", hashed_password="unused", is_admin=True)
    service = database.User(username="native_peer", hashed_password="unused", is_service_account=True)
    db_session.add_all([alice, bob, admin, service])
    db_session.commit()
    app = FastAPI()
    app.include_router(mobile.router)
    app.dependency_overrides[mobile.get_db] = lambda: db_session
    app.add_middleware(
        RequestContextMiddleware,
        language_context=ContextVar("native_lang", default="en"),
        supported_languages={"en", "es", "de"},
        default_language="en",
        validate_request=security.validate_same_origin,
    )
    origin = "https://native.example"

    def cookie(user, *, expired=False):
        token = auth.create_access_token(
            {"sub": user.username, "sv": user.session_version or 0},
            expires_delta=timedelta(seconds=-1 if expired else 300),
        )
        return {"access_token": token}

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=origin) as client:
            paths = ["context", "settings", "admin/users", "admin/system", "admin/delete-requests"]
            for path in paths:
                assert (await client.get("/api/mobile/v2/" + path)).status_code == 401
            for user in (alice, bob):
                client.cookies.update(cookie(user))
                r = await client.get("/api/mobile/v2/context")
                assert r.status_code == 200 and r.headers["cache-control"] == "private, no-store"
                data = r.json()
                assert data["protocol"] == 2 and data["account"]["username"] == user.username
                assert len(data["storage_scope"]) == 64
                assert user.hashed_password not in r.text
                assert (await client.get("/api/mobile/v2/admin/users")).status_code == 403
                assert (await client.get("/api/mobile/v2/admin/system")).status_code == 403
                assert (await client.get("/api/mobile/v2/admin/delete-requests")).status_code == 403
            client.cookies.update(cookie(alice))
            before = (await client.get("/api/mobile/v2/context")).json()["storage_scope"]
            for headers in ({}, {"origin": "https://attacker.example"}, {"origin": "http://native.example"}):
                r = await client.patch("/api/mobile/v2/settings", json={"theme": "vitrine"}, headers=headers)
                assert r.status_code == 403
            headers = {"origin": origin}
            for body in (
                {"theme": "unknown"},
                {"is_admin": True},
                {"user_id": bob.id},
                {"metadata_preferences": ["spotify", "spotify"]},
            ):
                assert (await client.patch("/api/mobile/v2/settings", json=body, headers=headers)).status_code == 422
            assert (
                await client.patch(
                    "/api/mobile/v2/settings", json={"theme": "vitrine", "language": "de"}, headers=headers
                )
            ).status_code == 200
            assert alice.theme == "vitrine" and bob.theme != "vitrine"
            secret = "do-not-expose-native-test-secret"
            r = await client.put("/api/mobile/v2/integrations", json={"spotify_client_secret": secret}, headers=headers)
            assert r.status_code == 200 and secret not in r.text
            assert r.json()["integrations"]["spotify_client_secret"] is True
            row = db_session.query(database.DownloadSettings).filter_by(user_id=alice.id).one()
            assert row._spotify_client_secret != secret and row.spotify_client_secret == secret
            client.cookies.update(cookie(bob))
            assert not (await client.get("/api/mobile/v2/settings")).json()["integrations"]["spotify_client_secret"]
            assert (await client.get("/api/mobile/v2/context")).json()["storage_scope"] != before
            client.cookies.update(cookie(service))
            assert (await client.get("/api/mobile/v2/context")).status_code == 401
            client.cookies.update(cookie(alice, expired=True))
            assert (await client.get("/api/mobile/v2/context")).status_code == 401
            client.cookies.update(cookie(alice))
            auth.blacklist_token(db_session, client.cookies.get("access_token"))
            assert (await client.get("/api/mobile/v2/settings")).status_code == 401
            client.cookies.update(cookie(admin))
            r = await client.get("/api/mobile/v2/admin/users")
            assert r.status_code == 200
            assert all("hashed_password" not in item and "session_version" not in item for item in r.json()["users"])
            assert service.username not in r.text
            alice.hashed_password = "rotated"
            alice.session_version = 1
            db_session.commit()
            client.cookies.update(cookie(alice))
            assert (await client.get("/api/mobile/v2/context")).json()["storage_scope"] == before
            alice.native_account_id = "a-new-account-identity"
            db_session.commit()
            assert (await client.get("/api/mobile/v2/context")).json()["storage_scope"] != before

    asyncio.run(exercise())


def test_native_system_build_fields_do_not_expand_private_operational_data(db_session, monkeypatch):
    import operations_service
    import wrapped_service

    monkeypatch.setattr(
        operations_service,
        "get_build_info",
        lambda: {"release_version": "1.3.1", "channel": "main", "revision": "12", "private_key": "fixture-secret"},
    )
    monkeypatch.setattr(
        operations_service,
        "get_backup_state",
        lambda db: {"current": {"filename": "backup.zip", "exists": True, "file_path": "/private/server/backup.zip"}},
    )
    monkeypatch.setattr(
        operations_service,
        "get_update_state",
        lambda db: {"message": "Up to date", "internal_credentials": "fixture-secret"},
    )
    monkeypatch.setattr(
        operations_service,
        "get_recent_admin_jobs",
        lambda db, limit: [{"id": 1, "status": "completed", "stdout": "fixture-secret"}],
    )
    monkeypatch.setattr(wrapped_service, "get_wrapped_settings", lambda db: {"enabled": False})
    response = mobile.system(admin=object(), db=db_session)
    data = json.loads(response.body)
    assert data["build"] == {"release_version": "1.3.1", "channel": "main", "revision": "12"}
    assert data["backups"]["current"] == {"filename": "backup.zip", "exists": True}
    assert b"fixture-secret" not in response.body and b"/private/server" not in response.body
    assert response.headers["cache-control"] == "private, no-store"


def test_native_account_form_negotiation_never_serializes_template_context():
    from shared_templates import templates
    from starlette.requests import Request

    for path in ("change-password", "update-api-keys", "validate-api-keys", "upload-cookies", "upload-avatar"):
        request = Request(
            {
                "type": "http",
                "method": "POST",
                "scheme": "https",
                "path": "/user/" + path,
                "server": ("native.example", 443),
                "headers": [(b"accept", b"application/json")],
            }
        )
        context = {
            "request": request,
            "success": "Saved",
            "user": {"hashed_password": "synthetic-private-value"},
            "dl_settings": {"spotify_client_secret": "synthetic-private-value"},
        }
        response = templates.TemplateResponse(request, "user_settings.html", context)
        assert response.status_code == 200 and json.loads(response.body) == {"ok": True, "message": "Saved"}
        assert b"synthetic-private-value" not in response.body
        context["error"] = "Invalid image"
        response = templates.TemplateResponse(request, "user_settings.html", context)
        assert response.status_code == 400 and json.loads(response.body) == {"error": "Invalid image"}


def test_native_collection_retry_is_transactional_and_cannot_reuse_another_accounts_result(db_session, monkeypatch):
    from unittest.mock import MagicMock, patch
    from uuid import uuid4

    with patch("docker.from_env", return_value=MagicMock()):
        from routers.music import playlists

    first = database.User(username="native_first", hashed_password="unused")
    second = database.User(username="native_second", hashed_password="unused")
    db_session.add_all([first, second])
    db_session.commit()
    acting = [first]
    monkeypatch.setattr(playlists, "get_current_user_safe", lambda *_: acting[0])
    monkeypatch.setattr(playlists, "generate_m3u_for_playlist", lambda *args: None)
    monkeypatch.setattr(playlists, "schedule_playlist_sync", lambda *args: None)
    monkeypatch.setattr(playlists, "schedule_navidrome_sync", lambda *args, **kwargs: None)
    from starlette.requests import Request

    request = Request({"type": "http"})
    body = playlists.CreatePlaylistRequest(name="Native retry", request_id=uuid4())
    result = asyncio.run(playlists.create_playlist(body, request, db_session))
    original = json.loads(result.body)["id"]
    replay = asyncio.run(playlists.create_playlist(body, request, db_session))
    assert json.loads(replay.body)["id"] == original
    assert db_session.query(database.Playlist).count() == 1
    assert db_session.query(database.NativeCollectionOperation).count() == 1
    conflicting = body.model_copy(update={"name": "Different name"})
    assert asyncio.run(playlists.create_playlist(conflicting, request, db_session)).status_code == 409
    acting[0] = second
    other = asyncio.run(playlists.create_playlist(body, request, db_session))
    assert json.loads(other.body)["id"] != original
    assert db_session.query(database.Playlist).count() == 2
    db_session.delete(db_session.get(database.Playlist, original))
    db_session.commit()
    acting[0] = first
    assert asyncio.run(playlists.create_playlist(body, request, db_session)).status_code == 409


def test_native_browser_requires_admin_session_owner_and_valid_input(db_session, monkeypatch):
    from limiter import limiter

    auth_browser = mobile.auth_browser

    owner = database.User(username="browser_owner", hashed_password="unused", is_admin=True)
    other = database.User(username="browser_other", hashed_password="unused", is_admin=True)
    listener = database.User(username="browser_listener", hashed_password="unused")
    db_session.add_all([owner, other, listener])
    db_session.commit()
    session_id = "native_browser_test_session"
    auth_browser.create(session_id, "deezer", owner.id, 60)
    calls = []
    monkeypatch.setattr(
        mobile.downloader_worker_client,
        "native_browser_frame",
        lambda sid: calls.append(sid) or b"\xff\xd8\xffsynthetic",
    )
    monkeypatch.setattr(
        mobile.downloader_worker_client,
        "native_browser_input",
        lambda sid, body: calls.append((sid, body)) or {"ok": True},
    )
    app = FastAPI()
    app.state.limiter = limiter
    app.include_router(mobile.router)
    app.dependency_overrides[mobile.get_db] = lambda: db_session
    app.add_middleware(
        RequestContextMiddleware,
        language_context=ContextVar("browser_lang", default="en"),
        supported_languages={"en"},
        default_language="en",
        validate_request=security.validate_same_origin,
    )

    async def exercise():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="https://browser.example"
        ) as client:
            path = f"/api/mobile/v2/admin/browser/{session_id}"
            assert (await client.get(path + "/frame")).status_code == 401
            for user, status in ((listener, 403), (other, 403), (owner, 200)):
                client.cookies.update({"access_token": auth.create_access_token({"sub": user.username, "sv": 0})})
                response = await client.get(path + "/frame")
                assert response.status_code == status
            assert calls == [session_id]
            assert response.headers["cache-control"] == "private, no-store"
            assert response.headers["cross-origin-resource-policy"] == "same-origin"
            assert (
                await client.post(path + "/input", json={"action": "text", "text": "synthetic text"})
            ).status_code == 403
            headers = {"origin": "https://browser.example"}
            for payload in (
                {"action": "Runtime.evaluate"},
                {"action": "click", "x": 100000},
                {"action": "text", "text": "x" * 2049},
                {"action": "key", "key": "F12"},
                {"action": "text", "session_id": "other"},
            ):
                assert (await client.post(path + "/input", json=payload, headers=headers)).status_code == 422
            assert len(calls) == 1
            assert (
                await client.post(path + "/input", json={"action": "click", "x": 100, "y": 50}, headers=headers)
            ).status_code == 200
            for key in ("Space", "Shift+Tab", "Delete", "Home", "End", "PageUp", "PageDown", "Control+A"):
                assert (
                    await client.post(path + "/input", json={"action": "key", "key": key}, headers=headers)
                ).status_code == 200
                assert calls[-1][1]["key"] == key
            auth_browser.remove(session_id)
            assert (await client.get(path + "/frame")).status_code == 403

    try:
        asyncio.run(exercise())
    finally:
        auth_browser.remove(session_id)


def test_recreated_username_cannot_reuse_a_preexisting_cookie_and_new_identity_is_bound(db_session):
    from starlette.requests import Request

    old = auth.create_user_in_db(db_session, "recreated_user", "Strong!Pass12")
    old_token = auth.create_access_token({"sub": old.username, "sv": old.session_version})
    old_identity = old.native_account_id
    db_session.delete(old)
    db_session.commit()
    new = auth.create_user_in_db(db_session, "recreated_user", "Strong!Pass12")
    assert new.native_account_id != old_identity
    assert not auth.verify_token(old_token, new.username, db_session)
    assert new.session_version != 0
    wrong_identity = auth.create_access_token({"sub": new.username, "sv": new.session_version, "aid": old_identity})
    assert not auth.verify_token(wrong_identity, new.username, db_session)
    token = auth.create_access_token({"sub": new.username, "sv": new.session_version, "aid": new.native_account_id})
    assert auth.verify_token(token, new.username, db_session)
    request = Request({"type": "http", "headers": [(b"cookie", f"access_token={token}".encode())]})
    assert auth.get_current_user(request, db_session).id == new.id


def test_native_preview_uses_existing_authorized_relay_without_credentialed_redirect(monkeypatch, db_session):
    from unittest.mock import AsyncMock, MagicMock

    from starlette.requests import Request

    with patch("docker.from_env", return_value=MagicMock()):
        from routers.music import sync

    request = Request({"type": "http", "method": "GET", "headers": []})
    monkeypatch.setattr(sync, "get_current_user_safe", lambda *_: None)
    assert (
        asyncio.run(sync.preview_playback(request, spotify_id="synthetic-id", relay=True, db=db_session)).status_code
        == 401
    )
    monkeypatch.setattr(sync, "get_current_user_safe", lambda *_: object())
    preview = AsyncMock(return_value="https://p.scdn.co/mp3-preview/synthetic.mp3")
    monkeypatch.setattr(sync.spotify_service.spotify_service, "get_embed_preview", preview)
    native = asyncio.run(sync.preview_playback(request, spotify_id="synthetic-id", relay=True, db=db_session))
    assert native.status_code == 200 and native.media_type == "audio/mpeg" and "location" not in native.headers
    browser = asyncio.run(sync.preview_playback(request, spotify_id="synthetic-id", relay=False, db=db_session))
    assert browser.status_code == 307 and browser.headers["location"].startswith("https://p.scdn.co/")
