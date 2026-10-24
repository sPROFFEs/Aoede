import asyncio
import json
import re
from pathlib import Path
from unittest.mock import MagicMock, patch

import auth
import database
import httpx
import ops_core
import pytest
from fastapi import FastAPI
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError

with patch("docker.from_env", return_value=MagicMock()):
    from routers import user
    from routers.music import sync


@pytest.mark.parametrize("theme", ["glass", "vitrine"])
def test_theme_is_private_persisted_across_devices_and_in_heartbeat(db_session, theme):
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
            assert (await anonymous.patch("/user/appearance", json={"theme": theme})).status_code == 401
        async with httpx.AsyncClient(
            transport=transport, base_url="http://theme.test", cookies=alice_cookie
        ) as device_a:
            assert (await device_a.get("/user/appearance")).json()["theme"] == "classic"
            changed = await device_a.patch("/user/appearance", json={"theme": theme, "user_id": bob.id})
            assert changed.status_code == 200 and changed.json() == {"theme": theme, "username": alice.username}
            assert changed.headers["cache-control"] == "private, no-store"
            async with httpx.AsyncClient(
                transport=transport, base_url="http://theme.test", cookies=alice_cookie
            ) as device_b:
                assert (await device_b.get("/user/appearance")).json()["theme"] == theme
                heartbeat_response = await device_b.get("/api/sync-state")
                assert heartbeat_response.headers["cache-control"] == "private, no-store"
                heartbeat = heartbeat_response.json()
                assert heartbeat["theme"] == theme and heartbeat["username"] == alice.username
                for payload in ({"theme": "unknown"}, {"theme": "<script>"}, {}):
                    assert (await device_b.patch("/user/appearance", json=payload)).status_code == 422
                assert (await device_b.patch("/user/appearance")).status_code == 422
                assert (await device_b.get("/user/appearance")).json()["theme"] == theme
                assert (await device_b.patch("/user/appearance", json={"theme": "classic"})).status_code == 200
                assert (await device_a.get("/user/appearance")).json()["theme"] == "classic"
            async with httpx.AsyncClient(
                transport=transport, base_url="http://theme.test", cookies=bob_cookie
            ) as other:
                assert (await other.get("/user/appearance")).json()["theme"] == "classic"
            with patch.object(db_session, "commit", side_effect=RuntimeError("Write unavailable")):
                assert (await device_a.patch("/user/appearance", json={"theme": theme})).status_code == 500
            assert (await device_a.get("/user/appearance")).json()["theme"] == "classic"

    asyncio.run(exercise())
    assert db_session.get(database.User, bob.id).theme == "classic"


@pytest.mark.parametrize("theme", ["classic", "glass", "vitrine"])
def test_theme_migration_preserves_users_and_rendering_starts_with_saved_theme(db_session, theme):
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT)"))
        conn.execute(text("INSERT INTO users VALUES (1, 'existing')"))
        ops_core._migration_034_user_theme(conn)
        conn.execute(text("UPDATE users SET theme='glass' WHERE id=1"))
        ops_core._migration_034_user_theme(conn)
        assert conn.execute(text("SELECT username, theme FROM users")).one() == ("existing", "glass")
    engine.dispose()

    viewer = database.User(username="saved-theme", theme=theme, hashed_password="unused")
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
    assert appearance_context(request)["user_theme"] == theme
    response = templates.TemplateResponse(
        request,
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
    assert f'data-theme="{theme}"' in response.body.decode()
    assert "/assets/css/theme_vitrine.css" in response.body.decode()
    assert "/assets/css/theme_glass.css" in response.body.decode()
    settings = templates.get_template("user_settings.html").render(
        request=request, user=viewer, user_theme=theme, dl_settings=None
    )
    radios = re.findall(r'<input type="radio" name="user-theme"[^>]+>', settings)
    assert len(radios) == 3
    assert len([radio for radio in radios if " checked" in radio]) == 1
    assert " checked" in next(radio for radio in radios if f'value="{theme}"' in radio)
    for page in ("base.html", "login.html", "wrapped_story.html"):
        source = templates.get_template(page)
        assert source is not None
    css = (Path(__file__).resolve().parents[2] / "assets/css/theme_glass.css").read_text()
    assert 'html:is([data-theme="glass"], [data-theme="vitrine"])' in css
    vitrine = (Path(__file__).resolve().parents[2] / "assets/css/theme_vitrine.css").read_text()
    assert 'html[data-theme="vitrine"]' in vitrine
    assert "forced-colors" in vitrine and "prefers-reduced-transparency" in vitrine
    assert "prefers-color-scheme: dark" in css and "prefers-reduced-motion" in css and "forced-colors" in css
    assert "var(--safe-area-bottom" in css and "@supports not" in css
    assert (
        "env(safe-area-inset-bottom" in (Path(__file__).resolve().parents[2] / "assets/css/ui_tokens.css").read_text()
    )
    assert (
        json.loads((Path(__file__).resolve().parents[1] / "locales/es.json").read_text())["appearance.title"]
        == "Apariencia"
    )


def test_vitrine_migration_keeps_preferences_foreign_keys_and_indexes():
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("PRAGMA foreign_keys=ON"))
        conn.execute(text("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT NOT NULL)"))
        conn.execute(text("CREATE UNIQUE INDEX ix_users_username ON users(username)"))
        conn.execute(text("CREATE TABLE favorites (user_id INTEGER REFERENCES users(id) ON DELETE CASCADE)"))
        conn.execute(text("INSERT INTO users VALUES (1, 'classic'), (2, 'glass')"))
        conn.execute(text("INSERT INTO favorites VALUES (1), (2)"))
        ops_core._migration_034_user_theme(conn)
        conn.execute(text("UPDATE users SET theme='glass' WHERE id=2"))
        ops_core._migration_035_vitrine_theme(conn)
        assert conn.execute(text("SELECT theme FROM users ORDER BY id")).scalars().all() == ["classic", "glass"]
        conn.execute(text("UPDATE users SET theme='vitrine' WHERE id=2"))
        ops_core._migration_035_vitrine_theme(conn)
        assert conn.execute(text("SELECT theme FROM users ORDER BY id")).scalars().all() == ["classic", "vitrine"]
        assert conn.execute(text("SELECT user_id FROM favorites ORDER BY user_id")).scalars().all() == [1, 2]
        assert conn.execute(text("PRAGMA foreign_key_check")).all() == []
        assert "ix_users_username" in {row[1] for row in conn.execute(text("PRAGMA index_list(users)"))}
        with pytest.raises(IntegrityError):
            conn.execute(text("UPDATE users SET theme='unknown'"))
        with pytest.raises(IntegrityError):
            conn.execute(text("INSERT INTO favorites VALUES (99)"))
    engine.dispose()


@pytest.mark.parametrize("old_column_present", [False, True])
def test_vitrine_migration_can_resume_after_interrupted_column_change(old_column_present):
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE users (id INTEGER PRIMARY KEY, theme_next TEXT NOT NULL DEFAULT 'classic')"))
        conn.execute(text("INSERT INTO users VALUES (1, 'glass')"))
        if old_column_present:
            conn.execute(text("ALTER TABLE users ADD COLUMN theme TEXT NOT NULL DEFAULT 'classic'"))
        ops_core._migration_035_vitrine_theme(conn)
        assert conn.execute(text("SELECT theme FROM users")).scalar() == ("classic" if old_column_present else "glass")
        assert "theme_next" not in {row[1] for row in conn.execute(text("PRAGMA table_info(users)"))}
    engine.dispose()
