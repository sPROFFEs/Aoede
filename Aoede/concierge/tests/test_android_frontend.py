import ast
import asyncio
import json
import logging
from contextvars import ContextVar
from datetime import timedelta
from html.parser import HTMLParser
from pathlib import Path
from threading import Lock
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
from starlette.background import BackgroundTask

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

    def quota(db, *, cached_only=False):
        assert cached_only  # A cold filesystem scan must never block the initial response.
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
        BackgroundTask=BackgroundTask,
        current_lang=language,
        manager=SimpleNamespace(get_pool_status=quota, _measure_pool_bytes=lambda: None),
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


def test_mobile_quota_can_read_stale_cache_without_scanning_and_refresh_after_response():
    import os
    import time

    source = (ROOT / "Aoede/concierge/manager.py").read_text()
    measure = next(
        node
        for node in ast.parse(source).body
        if isinstance(node, ast.FunctionDef) and node.name == "_measure_pool_bytes"
    )
    cache = {"measure_root": None, "used_bytes": 0, "timestamp": 0}
    scans = []
    context = dict(
        os=os,
        time=time,
        DATA_ROOT="/saas-data/users",
        _pool_status_lock=Lock(),
        _pool_status_cache=cache,
        settings=SimpleNamespace(POOL_STATUS_CACHE_TTL_SECONDS=60),
        get_dir_size=lambda root: scans.append(root) or 42,
    )
    exec(compile(ast.Module(body=[measure], type_ignores=[]), "pool-measure", "exec"), context)
    measure = context["_measure_pool_bytes"]
    assert measure(cached_only=True) == 0 and not scans
    cache.update(measure_root="/saas-data", used_bytes=12)
    assert measure(cached_only=True) == 12 and not scans  # Even an expired cache avoids disk IO.

    async def check():
        sent = []

        async def send(message):
            sent.append(message["type"])
            assert not scans  # Both HTTP headers and body leave before disk measurement starts.

        await JSONResponse({}, background=BackgroundTask(measure))({"type": "http"}, None, send)
        assert sent == ["http.response.start", "http.response.body"]

    asyncio.run(check())
    assert scans == ["/saas-data"] and measure(cached_only=True) == 42
    assert measure() == 42 and len(scans) == 1


def test_android_compiler_bundles_canonical_modules_themes_translations_and_no_startup_cdn(tmp_path):
    import importlib.util

    spec = importlib.util.spec_from_file_location("android_bundle", ROOT / "android/build_frontend.py")
    compiler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compiler)
    bundle = tmp_path / "web"
    compiler.build(bundle)
    manifest = json.loads((bundle / "manifest.json").read_text())
    assert manifest["protocol"] == 1

    class LoginText(HTMLParser):
        def __init__(self):
            super().__init__()
            self.script = False
            self.text = []

        def handle_starttag(self, tag, attrs):
            if tag == "script":
                self.script = True

        def handle_endtag(self, tag):
            if tag == "script":
                self.script = False

        def handle_data(self, data):
            if not self.script:
                self.text.append(data)

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
        login = (bundle / f"{lang}-login.html").read_text()
        assert catalogue["login.username_placeholder"] in login
        visible = LoginText()
        visible.feed(login)
        assert "navigator.serviceWorker.register" not in "".join(visible.text)
        assert login.count('src="/_aoede/media-session.js"') == 1


def test_android_packaging_uses_current_build_metadata_instead_of_stale_signed_apk(tmp_path):
    import subprocess
    import sys
    import zipfile

    root = Path(__file__).resolve().parents[3]
    script = tmp_path / "scripts/package_android.py"
    script.parent.mkdir()
    script.write_text((root / "scripts/package_android.py").read_text())
    for name in ("branding/aoede.png", "scripts/enlarge_brand_icons.py"):
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((root / name).read_bytes())
    for name in ("ANDROID.md", "ANDROID-NATIVE-MIGRATION.md", "SECURITY-REVIEW-1.3.1.md", "RELEASE-1.3.1.md"):
        target = tmp_path / "docs" / name
        target.parent.mkdir(exist_ok=True)
        target.write_text("Native client documentation")
    release = tmp_path / "android/app/build/outputs/apk/release"
    release.mkdir(parents=True)
    (release / "app-release.apk").write_bytes(b"stale signed build")
    (release / "app-release-unsigned.apk").write_bytes(b"current unsigned build")
    (release / "output-metadata.json").write_text(
        json.dumps(
            {
                "applicationId": "com.aoede.twa",
                "elements": [{"versionName": "1.9.0", "versionCode": 99, "outputFile": "app-release-unsigned.apk"}],
            }
        )
    )
    (tmp_path / "android/private.p12").write_text("must not be packaged")
    for folder in ("assets", "concierge/templates", "concierge/locales"):
        directory = tmp_path / "Aoede" / folder
        directory.mkdir(parents=True)
        (directory / "source.txt").write_text("editable frontend source")
    subprocess.run([sys.executable, str(script)], check=True, capture_output=True)
    output = tmp_path / "android/dist"
    assert (output / "aoede-android-unsigned.apk").read_bytes() == b"current unsigned build"
    assert not (output / "aoede-android.apk").exists()
    assert (output / "android-version.txt").read_text() == "Aoede Android 1.9.0 (99)"
    with zipfile.ZipFile(output / "aoede-android-source.zip") as archive:
        assert not any(name.endswith(".p12") for name in archive.namelist())
        assert archive.read("aoede-android/frontend-source/assets/source.txt") == b"editable frontend source"
        assert archive.read("aoede-android/docs/ANDROID-NATIVE-MIGRATION.md") == b"Native client documentation"
        for name in ("branding/aoede.png", "scripts/enlarge_brand_icons.py"):
            assert archive.read(f"aoede-android/{name}") == (root / name).read_bytes()
