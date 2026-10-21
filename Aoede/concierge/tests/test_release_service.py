import ast
import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, patch

import auth
import database
import httpx
import pytest
import release_service as releases
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session


def test_release_downloads_follow_actual_uploaded_assets_and_official_urls():
    names = [
        "aoede-android.apk",
        "aoede-android-source.zip",
        "aoede-android-wrapper-1.0.5.apk",
        "aoede-android-wrapper-1.0.5-source.zip",
        "aoede-mac-arm64.tar.gz",
        "aoede-mac-universal.tar.gz",
        "aoede-mac-x64.tar.gz",
        "aoede-win-x64.tar.gz",
        "aoede-linux-x64.tar.gz",
        "aoede-linux-arm64.tar.gz",
    ]
    data = {
        "tag_name": "v1.2.9",
        "body": "## Android update\n\n**Better playback** and [widgets](https://example.test).",
        "assets": [
            {"name": name, "state": "uploaded", "browser_download_url": "javascript:alert(1)"} for name in names
        ],
    }
    data["assets"] += [
        {"name": "aoede-other.exe", "state": "uploaded"},
        {"name": "aoede-win-arm64.tar.gz", "state": "new"},
    ]
    release = releases._release_details(data)
    assert release["summary"] == "Better playback and widgets."
    assert release["title"] == "Android update"
    assert {item["name"] for item in release["downloads"]} == set(names)
    assert release["downloads"][0]["name"] == "aoede-android.apk"
    assert release["downloads"][1]["name"] == "aoede-android-wrapper-1.0.5.apk"
    assert all(
        item["url"] == f"{releases.REPOSITORY}/releases/download/v1.2.9/{item['name']}" for item in release["downloads"]
    )
    assert {item["platform"] for item in release["downloads"]} == {"android", "mac", "win", "linux"}
    for change in ({"tag_name": "../evil"}, {"draft": True}, {"prerelease": True}):
        with pytest.raises(ValueError):
            releases._release_details(data | change)


def test_release_cache_coalesces_requests_retries_errors_and_retains_last_success(monkeypatch):
    for name, value in {
        "_release": None,
        "_checked_at": None,
        "_retry_at": 0,
        "_stale": True,
        "_lock": asyncio.Lock(),
    }.items():
        monkeypatch.setattr(releases, name, value)
    monkeypatch.setattr(
        releases, "_current_build", lambda: {"release_version": "v1.2.8", "display_version": "v1.2.8+r2"}
    )
    response = httpx.Response(
        200, json={"tag_name": "v1.2.7", "assets": []}, request=httpx.Request("GET", releases.LATEST_URL)
    )
    fetch = AsyncMock(return_value=response)
    monkeypatch.setattr(releases.http_client, "get", fetch)

    async def check():
        results = await asyncio.gather(*(releases.get_about_info() for _ in range(10)))
        assert fetch.await_count == 1
        assert all(
            result["current"]["release_version"] == "v1.2.8"
            and result["release"]["tag"] == "v1.2.7"
            and not result["stale"]
            for result in results
        )
        checked = results[0]["checked_at"]
        monkeypatch.setattr(releases, "_retry_at", 0)
        fetch.side_effect = httpx.ConnectError("unavailable")
        failed = await releases.get_about_info()
        assert failed["stale"] and failed["release"] == results[0]["release"] and failed["checked_at"] == checked
        await releases.get_about_info()
        assert fetch.await_count == 2
        monkeypatch.setattr(releases, "_retry_at", 0)
        monkeypatch.setattr(releases, "_release", None)
        assert (await releases.get_about_info())["release"] is None
        monkeypatch.setattr(releases, "_retry_at", 0)
        fetch.side_effect = None
        assert not (await releases.get_about_info())["stale"]
        assert fetch.call_args.args[0] == "https://api.github.com/repos/sPROFFEs/Aoede/releases/latest"
        assert fetch.call_args.kwargs["follow_redirects"] is False

    asyncio.run(check())


def test_about_route_requires_active_session_allows_non_admin_and_never_runs_updates(db_session):
    source = (Path(__file__).resolve().parents[1] / "main.py").read_text()
    route = next(
        node for node in ast.parse(source).body if isinstance(node, ast.AsyncFunctionDef) and node.name == "about_info"
    )
    app = FastAPI()
    context = dict(
        app=app,
        Request=Request,
        Session=Session,
        Depends=Depends,
        HTTPException=HTTPException,
        auth=auth,
        get_db=lambda: db_session,
        JSONResponse=JSONResponse,
        release_service=releases,
    )
    exec(compile(ast.Module(body=[route], type_ignores=[]), "about-route", "exec"), context)
    db_session.add_all(
        [
            database.User(username="listener", hashed_password="unused", is_admin=False),
            database.User(username="disabled", hashed_password="unused", is_active=False),
        ]
    )
    db_session.commit()

    async def check():
        with patch.object(
            releases,
            "get_about_info",
            AsyncMock(return_value={"current": {"release_version": "v1.2.8"}, "release": None}),
        ) as get:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="https://aoede.test") as client:
                for token in ("", "invalid", auth.create_access_token({"sub": "disabled"})):
                    client.cookies.update({"access_token": token})
                    assert (await client.get("/api/about")).status_code == 401
                get.assert_not_awaited()
                token = auth.create_access_token({"sub": "listener"})
                client.cookies.update({"access_token": token})
                response = await client.get("/api/about?repo=https://attacker.test&force=true")
                assert response.status_code == 200
                assert response.headers["cache-control"] == "private, no-store"
                assert response.json()["current"]["release_version"] == "v1.2.8"
                auth.blacklist_token(db_session, token)
                assert (await client.get("/api/about")).status_code == 401
                assert (await client.post("/api/about")).status_code == 405

    asyncio.run(check())


def test_client_versions_come_from_published_binary_labels_not_server_tags():
    data = {
        "tag_name": "v1.2.7",
        "assets": [
            {"name": "aoede-android.apk", "state": "uploaded", "label": "Aoede Android 1.1.9 (15)"},
            {"name": "aoede-mac-universal.tar.gz", "state": "uploaded", "label": "Aoede Desktop 1.2.9"},
            {"name": "aoede-win-x64.tar.gz", "state": "uploaded", "label": "malformed"},
            {"name": "aoede-android-wrapper-1.0.5.apk", "state": "uploaded", "label": "Aoede Android 999.0.0 (999)"},
        ],
    }
    files = {item["name"]: item for item in releases._release_details(data)["downloads"]}
    assert files["aoede-android.apk"]["version"] == "1.1.9"
    assert files["aoede-android.apk"]["version_code"] == 15
    assert files["aoede-mac-universal.tar.gz"]["arch"] == "universal"
    assert files["aoede-mac-universal.tar.gz"]["version"] == "1.2.9"
    assert "version" not in files["aoede-win-x64.tar.gz"]
    assert "client" not in files["aoede-android-wrapper-1.0.5.apk"]
