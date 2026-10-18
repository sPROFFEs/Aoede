import ast
import asyncio
import json
import logging
from contextvars import ContextVar
from datetime import timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import auth
import database
import httpx
import security
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from request_middleware import RequestContextMiddleware
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[3]


def test_mobile_bootstrap_is_authenticated_private_revocable_and_uses_request_language(db_session):
    # Load the actual route without starting unrelated Docker/music maintenance services.
    source = (ROOT / "Aoede/concierge/main.py").read_text()
    route = next(
        node for node in ast.parse(source).body if isinstance(node, ast.FunctionDef) and node.name == "mobile_bootstrap"
    )
    app = FastAPI()
    language = ContextVar("mobile_language", default="en")
    quota_calls = []

    def quota(db):
        quota_calls.append(db)
        return 2, 10, 20

    context = dict(
        app=app,
        Request=Request,
        Session=Session,
        Depends=Depends,
        HTTPException=HTTPException,
        auth=auth,
        get_db=lambda: db_session,
        JSONResponse=JSONResponse,
        current_lang=language,
        manager=SimpleNamespace(get_pool_status=quota),
        logger=logging.getLogger(__name__),
    )
    exec(compile(ast.Module(body=[route], type_ignores=[]), "mobile-route", "exec"), context)
    app.add_middleware(
        RequestContextMiddleware,
        language_context=language,
        supported_languages={"en", "es", "de"},
        default_language="en",
        validate_request=security.validate_same_origin,
    )
    app.add_middleware(CORSMiddleware, allow_origins=["https://aoede.test:8443"], allow_credentials=True)
    alice = database.User(username="alice", hashed_password="unused", theme="vitrine", is_admin=True)
    bob = database.User(username="bob", hashed_password="unused", theme="glass", is_admin=False)
    disabled = database.User(username="disabled", hashed_password="unused", is_active=False)
    service = database.User(username="federation", hashed_password="unused", is_service_account=True)
    db_session.add_all([alice, bob, disabled, service])
    db_session.commit()

    async def check():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="https://aoede.test:8443") as client:
            response = await client.get("/api/mobile/bootstrap")
            assert response.status_code == 401 and "username" not in response.json()
            assert response.headers["cache-control"] == "private, no-store"
            for token in [
                "invalid-jwt",
                auth.create_access_token({"sub": "missing"}),
                auth.create_access_token({"sub": "disabled"}),
                auth.create_access_token({"sub": "federation"}),
                auth.create_access_token({"sub": "alice", "sv": 99}),
                auth.create_access_token({"sub": "alice"}, expires_delta=timedelta(seconds=-1)),
            ]:
                client.cookies.update({"access_token": token})
                assert (await client.get("/api/mobile/bootstrap")).status_code == 401
            assert quota_calls == []  # Unauthorized requests must not inspect storage or initialize settings.
            token = auth.create_access_token({"sub": "alice"})
            client.cookies.update({"access_token": token, "lang": "es"})
            response = await client.get("/api/mobile/bootstrap")
            assert response.json() == {
                "protocol": 1,
                "username": "alice",
                "is_admin": True,
                "theme": "vitrine",
                "language": "es",
                "pool": {"used": 2, "limit": 10, "percent": 20},
            }
            assert response.headers["cache-control"] == "private, no-store"
            assert "Cookie" in response.headers["vary"]
            assert response.headers["x-content-type-options"] == "nosniff"
            assert "hashed_password" not in response.text and "access_token" not in response.text
            assert set(response.json()) == {"protocol", "username", "is_admin", "theme", "language", "pool"}
            with patch.object(auth, "get_current_user", side_effect=RuntimeError("private database error")):
                failure = await client.get("/api/mobile/bootstrap")
                assert failure.status_code == 503 and "private database error" not in failure.text
                assert failure.headers["cache-control"] == "private, no-store"
            auth.blacklist_token(db_session, token)
            assert (await client.get("/api/mobile/bootstrap")).status_code == 401
            client.cookies.update({"access_token": auth.create_access_token({"sub": "bob"})})
            own = await client.get("/api/mobile/bootstrap?username=alice&is_admin=true&theme=vitrine")
            assert own.json()["username"] == "bob" and own.json()["is_admin"] is False
            assert own.json()["theme"] == "glass"
            assert "alice" not in own.text
            cross_origin = await client.get("/api/mobile/bootstrap", headers={"origin": "https://attacker.test"})
            assert "access-control-allow-origin" not in cross_origin.headers
            assert (await client.post("/api/mobile/bootstrap")).status_code == 403
            assert (
                await client.post("/api/mobile/bootstrap", headers={"origin": "https://aoede.test:8443"})
            ).status_code == 405

    asyncio.run(check())


def test_android_compiler_bundles_canonical_modules_themes_translations_and_no_startup_cdn(tmp_path):
    import importlib.util

    spec = importlib.util.spec_from_file_location("android_bundle", ROOT / "android/build_frontend.py")
    compiler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compiler)
    bundle = tmp_path / "web"
    compiler.build(bundle)
    manifest = json.loads((bundle / "manifest.json").read_text())
    assert manifest["protocol"] == 1
    assert (bundle / "assets/js/main.js").read_bytes() == (ROOT / "Aoede/assets/js/main.js").read_bytes()
    for theme in ("theme_glass.css", "theme_vitrine.css", "style.css"):
        assert f"assets/css/{theme}" in manifest["files"]
    for lang in ("en", "es", "de"):
        for role in ("user", "admin", "login"):
            html = (bundle / f"{lang}-{role}.html").read_text()
            assert f'lang="{lang}"' in html and 'data-client="android"' in html
            assert "https://fonts.googleapis.com" not in html and "https://unpkg.com" not in html
            assert "/assets/vendor/fontawesome/css/all.min.css" in html
            assert "/_aoede/runtime.js" in html
        catalogue = json.loads((ROOT / f"Aoede/concierge/locales/{lang}.json").read_text())
        assert catalogue["login.username_placeholder"] in (bundle / f"{lang}-login.html").read_text()
