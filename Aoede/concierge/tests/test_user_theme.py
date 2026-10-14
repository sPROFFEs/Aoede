import asyncio
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import auth
import database
import httpx
import ops_core
from fastapi import FastAPI
from sqlalchemy import create_engine, text

with patch("docker.from_env", return_value=MagicMock()):
    from routers import user
    from routers.music import sync


def test_theme_is_private_persisted_across_devices_and_in_heartbeat(db_session):
    alice = database.User(username="theme-alice", hashed_password="unused")
    bob = database.User(username="theme-bob", hashed_password="unused")
    db_session.add_all([alice, bob])
    db_session.commit()
    alice_cookie = {"access_token": auth.create_access_token({"sub": alice.username})}
    bob_cookie = {"access_token": auth.create_access_token({"sub": bob.username})}
    app = FastAPI()
    app.include_router(user.router)
    app.include_router(sync.router)
    app.dependency_overrides[user.get_db] = lambda: db_session
    app.dependency_overrides[sync.get_db] = lambda: db_session

    async def exercise():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://theme.test") as anonymous:
            assert (await anonymous.get("/user/appearance")).status_code == 401
            assert (await anonymous.patch("/user/appearance", json={"theme": "glass"})).status_code == 401
        async with httpx.AsyncClient(
            transport=transport, base_url="http://theme.test", cookies=alice_cookie
        ) as device_a:
            assert (await device_a.get("/user/appearance")).json()["theme"] == "classic"
            changed = await device_a.patch("/user/appearance", json={"theme": "glass", "user_id": bob.id})
            assert changed.status_code == 200 and changed.json() == {"theme": "glass", "username": alice.username}
            assert changed.headers["cache-control"] == "private, no-store"
            async with httpx.AsyncClient(
                transport=transport, base_url="http://theme.test", cookies=alice_cookie
            ) as device_b:
                assert (await device_b.get("/user/appearance")).json()["theme"] == "glass"
                heartbeat_response = await device_b.get("/api/sync-state")
                assert heartbeat_response.headers["cache-control"] == "private, no-store"
                heartbeat = heartbeat_response.json()
                assert heartbeat["theme"] == "glass" and heartbeat["username"] == alice.username
                for payload in ({"theme": "unknown"}, {"theme": "<script>"}, {}):
                    assert (await device_b.patch("/user/appearance", json=payload)).status_code == 422
                assert (await device_b.patch("/user/appearance")).status_code == 422
                assert (await device_b.get("/user/appearance")).json()["theme"] == "glass"
                assert (await device_b.patch("/user/appearance", json={"theme": "classic"})).status_code == 200
                assert (await device_a.get("/user/appearance")).json()["theme"] == "classic"
            async with httpx.AsyncClient(
                transport=transport, base_url="http://theme.test", cookies=bob_cookie
            ) as other:
                assert (await other.get("/user/appearance")).json()["theme"] == "classic"
            with patch.object(db_session, "commit", side_effect=RuntimeError("Write unavailable")):
                assert (await device_a.patch("/user/appearance", json={"theme": "glass"})).status_code == 500
            assert (await device_a.get("/user/appearance")).json()["theme"] == "classic"

    asyncio.run(exercise())
    assert db_session.get(database.User, bob.id).theme == "classic"


def test_theme_migration_preserves_users_and_rendering_starts_with_saved_theme(db_session):
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT)"))
        conn.execute(text("INSERT INTO users VALUES (1, 'existing')"))
        ops_core._migration_034_user_theme(conn)
        conn.execute(text("UPDATE users SET theme='glass' WHERE id=1"))
        ops_core._migration_034_user_theme(conn)
        assert conn.execute(text("SELECT username, theme FROM users")).one() == ("existing", "glass")
    engine.dispose()

    viewer = database.User(username="saved-theme", theme="glass", hashed_password="unused")
    db_session.add(viewer)
    db_session.commit()
    from shared_templates import appearance_context, templates
    from starlette.requests import Request

    request = Request(
        {
            "type": "http",
            "headers": [(b"cookie", f"access_token={auth.create_access_token({'sub': viewer.username})}".encode())],
        }
    )
    auth.get_current_user(request, db_session)
    assert appearance_context(request)["user_theme"] == "glass"
    response = templates.TemplateResponse(
        "app_shell.html",
        {
            "request": request,
            "username": viewer.username,
            "is_admin": False,
            "pool": {"used": 0, "limit": 1, "percent": 0},
            "initial_view": "home",
            "initial_param": None,
        },
    )
    assert 'data-theme="glass"' in response.body.decode()
    assert "/assets/css/theme_glass.css" in response.body.decode()
    for page in ("base.html", "login.html", "wrapped_story.html"):
        source = templates.get_template(page)
        assert source is not None
    css = (Path(__file__).resolve().parents[2] / "assets/css/theme_glass.css").read_text()
    assert 'html[data-theme="glass"]' in css
    assert "prefers-color-scheme: dark" in css and "prefers-reduced-motion" in css and "forced-colors" in css
    assert "safe-area-inset-bottom" in css and "@supports not" in css
    assert (
        json.loads((Path(__file__).resolve().parents[1] / "locales/es.json").read_text())["appearance.title"]
        == "Apariencia"
    )
