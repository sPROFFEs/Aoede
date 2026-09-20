import importlib.util
import sys
from datetime import timedelta
from pathlib import Path

import auth
import database
import pytest
import security
from fastapi import HTTPException
from starlette.requests import Request

AUTH_BROWSER_SPEC = importlib.util.spec_from_file_location(
    "aoede_concierge_auth_browser",
    Path(__file__).resolve().parents[1] / "auth_browser.py",
)
auth_browser = importlib.util.module_from_spec(AUTH_BROWSER_SPEC)
assert AUTH_BROWSER_SPEC and AUTH_BROWSER_SPEC.loader
sys.modules["aoede_concierge_auth_browser"] = auth_browser
AUTH_BROWSER_SPEC.loader.exec_module(auth_browser)


def request_for(method="GET", *, token=None, host="aoede.test", origin=None):
    headers = [(b"host", host.encode())]
    if token:
        headers.append((b"cookie", f"access_token={token}".encode()))
    if origin:
        headers.append((b"origin", origin.encode()))
    return Request({"type": "http", "method": method, "path": "/", "headers": headers})


def test_revoked_token_is_rejected_by_central_auth(db_session):
    user = database.User(username="alice", hashed_password="unused", is_active=True)
    db_session.add(user)
    db_session.commit()
    token = auth.create_access_token({"sub": "alice"})
    auth.blacklist_token(db_session, token)

    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(request_for(token=token), db_session)

    assert exc.value.status_code == 401
    assert exc.value.detail == "Session revoked"


def test_inactive_user_is_rejected_even_with_valid_token(db_session):
    db_session.add(database.User(username="disabled", hashed_password="unused", is_active=False))
    db_session.commit()
    token = auth.create_access_token({"sub": "disabled"})

    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(request_for(token=token), db_session)

    assert exc.value.status_code == 401


def test_legacy_unsafe_username_is_rejected(db_session):
    db_session.add(database.User(username="../escape", hashed_password="unused", is_active=True))
    db_session.commit()
    token = auth.create_access_token({"sub": "../escape"})

    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(request_for(token=token), db_session)

    assert exc.value.status_code == 401


def test_password_session_version_revokes_existing_tokens(db_session):
    user = database.User(username="alice", hashed_password="unused", session_version=0)
    db_session.add(user)
    db_session.commit()
    token = auth.create_access_token({"sub": "alice", "sv": 0})

    user.session_version = 1
    db_session.commit()

    with pytest.raises(HTTPException) as exc:
        auth.get_current_user(request_for(token=token), db_session)

    assert exc.value.status_code == 401


def test_remembered_session_is_longer_but_bounded(monkeypatch):
    monkeypatch.setattr(auth, "REMEMBER_SESSION_DAYS", 30)
    assert auth.session_expiry(False) == timedelta(days=1)
    assert auth.session_expiry(True) == timedelta(days=30)

    monkeypatch.setattr(auth, "REMEMBER_SESSION_DAYS", 10_000)
    assert auth.session_expiry(True) == timedelta(days=365)


def test_login_cookie_persists_for_the_selected_token_lifetime():
    main_source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")

    assert "max_age=int(session_lifetime.total_seconds())," in main_source
    assert "if remember_me else None" not in main_source


def test_auth_browser_cookie_round_trips_without_exposing_separate_credentials():
    value = auth_browser.encode_cookie("session-123", "token-value-that-is-long-enough")

    assert auth_browser.decode_cookie(value) == ("session-123", "token-value-that-is-long-enough")
    assert auth_browser.decode_cookie("malformed") == ("", "")
    assert auth_browser.decode_cookie("short.token") == ("", "")


def test_cookie_authenticated_write_requires_same_origin():
    with pytest.raises(HTTPException) as exc:
        security.validate_same_origin(request_for("POST", token="token", origin="https://attacker.example"))
    assert exc.value.status_code == 403

    security.validate_same_origin(request_for("POST", token="token", origin="https://aoede.test"))


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.mark.parametrize("username", ["../admin", "a", "space user", "semi;colon"])
def test_unsafe_usernames_are_rejected(username):
    assert not auth.is_valid_username(username)


@pytest.mark.anyio
async def test_change_password_emits_hx_redirect_and_clears_cookie(db_session, monkeypatch):
    import routers.user as user_router

    user = database.User(
        id=50,
        username="carol",
        hashed_password=auth.get_password_hash("OldPassword123!"),
        is_active=True,
    )
    db_session.add(user)
    db_session.commit()

    monkeypatch.setattr(user_router, "get_current_user", lambda req, db: user)
    request = Request({"type": "http", "method": "POST", "path": "/user/change-password", "headers": []})

    response = await user_router.change_password(
        request=request,
        current_password="OldPassword123!",
        new_password="NewStrongPassword456!",
        confirm_password="NewStrongPassword456!",
        db=db_session,
    )

    assert response.status_code == 303
    assert response.headers.get("hx-redirect") == "/login"
    assert "access_token" in response.headers.get("set-cookie", "")


@pytest.mark.anyio
async def test_i18n_supported_languages_and_cookie_switch(monkeypatch):
    import i18n
    import routers.user as user_router

    i18n.load_translations()
    assert "en" in i18n.SUPPORTED_LANGS
    assert "es" in i18n.SUPPORTED_LANGS
    assert "de" in i18n.SUPPORTED_LANGS

    # Test translations lookup
    assert i18n.get_text("menu.home", "en") == "Home"
    assert i18n.get_text("menu.home", "es") == "Inicio" or i18n.get_text("menu.home", "es") == "Home"
    assert i18n.get_text("menu.home", "de") == "Startseite"

    request = Request({"type": "http", "method": "GET", "path": "/user/set-language?lang=de", "headers": []})
    resp = await user_router.set_language("de", request)
    assert resp.status_code == 303
    assert "lang=de" in resp.headers.get("set-cookie", "")
