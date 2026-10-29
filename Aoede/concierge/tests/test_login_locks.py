import asyncio
from unittest.mock import MagicMock, patch

import auth
import database
import httpx
import pytest
import security
from fastapi import FastAPI, HTTPException
from starlette.requests import Request


def request(ip):
    return Request({"type": "http", "method": "POST", "path": "/login", "client": (ip, 1234), "headers": []})


@pytest.fixture
def clock(monkeypatch):
    now = [1000.0]
    monkeypatch.setattr(security, "login_attempts", {})
    monkeypatch.setattr(security.time, "time", lambda: now[0])
    return now


def test_ip_lock_attribution_countdown_shared_ip_and_expiration(clock):
    client = request("192.0.2.1")
    for _ in range(security.MAX_ATTEMPTS):
        security.register_failed_attempt(client, "alice")
    assert security.get_login_blocks()["alice"]["remaining_seconds"] == 900
    clock[0] += 60.5
    with pytest.raises(HTTPException) as blocked:
        security.check_brute_force(client, "bob")
    assert blocked.value.status_code == 429
    blocks = security.get_login_blocks()
    assert blocks["alice"]["remaining_seconds"] == blocks["bob"]["remaining_seconds"] == 840
    assert "192.0.2.1" not in str(blocks)
    security.check_brute_force(request("192.0.2.2"), "alice")  # Another IP remains allowed.
    clock[0] = 1901
    assert security.get_login_blocks() == {}
    security.check_brute_force(client, "alice")
    assert not security.register_failed_attempt(client, "alice")
    assert security.login_attempts["192.0.2.1"]["attempts"] == 1


def test_attribution_is_bounded_and_success_clears_it(clock):
    client = request("192.0.2.1")
    for i in range(70):
        security.register_failed_attempt(client, f"user{i}")
    assert len(security.login_attempts["192.0.2.1"]["usernames"]) == 64
    security.clear_attempts(client)
    assert security.get_login_blocks() == {}


def test_update_monitor_capability_is_admin_only_job_bound_and_uncacheable(db_session, monkeypatch):
    with patch("docker.from_env", return_value=MagicMock()):
        from routers import admin
    root = database.User(username="monitor_root", hashed_password="unused", is_admin=True)
    user = database.User(username="monitor_user", hashed_password="unused")
    db_session.add_all([root, user])
    db_session.commit()
    monkeypatch.setattr(
        admin.operations_service,
        "get_admin_job",
        lambda db, job_id: {
            "id": job_id,
            "job_type": "apply_update" if job_id == 42 else "backup",
            "status": "running",
        },
    )
    minted = []

    def token(job_id):
        minted.append(job_id)
        return "a" * 64

    monkeypatch.setattr(admin.operations_service, "get_update_monitor_token", token)
    app = FastAPI()
    app.include_router(admin.router)
    app.dependency_overrides[admin.get_db] = lambda: db_session

    async def check():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="https://aoede.test") as client:
            url = "/admin/api/system/jobs/42"
            assert (await client.get(url)).status_code == 401
            client.cookies.set("access_token", auth.create_access_token({"sub": user.username}))
            assert (await client.get(url)).status_code == 403
            assert minted == []
            client.cookies.set("access_token", auth.create_access_token({"sub": root.username}))
            response = await client.get(url)
            assert response.status_code == 200
            assert response.json()["monitor_endpoint"] == "/updater/api/jobs/42?access=" + "a" * 64
            assert response.headers["cache-control"] == "private, no-store"
            assert minted == [42]
            assert "monitor_endpoint" not in (await client.get("/admin/api/system/jobs/43")).json()
            assert minted == [42]

    asyncio.run(check())


def test_admin_login_lock_endpoint_is_private_current_and_admin_only(db_session, clock):
    with patch("docker.from_env", return_value=MagicMock()):
        from routers import admin
    root = database.User(username="root", hashed_password="unused", is_admin=True)
    user = database.User(username="alice", hashed_password="unused")
    db_session.add_all([root, user])
    db_session.commit()
    for _ in range(5):
        security.register_failed_attempt(request("192.0.2.1"), user.username)
    app = FastAPI()
    app.include_router(admin.router)
    app.dependency_overrides[admin.get_db] = lambda: db_session

    async def check():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="https://aoede.test") as client:
            url = f"/admin/api/users/{user.id}/login-lock"
            assert (await client.get(url)).status_code == 401
            client.cookies.set("access_token", auth.create_access_token({"sub": "alice"}))
            assert (await client.get(url)).status_code == 403
            client.cookies.set("access_token", auth.create_access_token({"sub": "root"}))
            response = await client.get(url)
            assert response.status_code == 200 and response.json()["remaining_seconds"] == 900
            assert response.headers["cache-control"] == "private, no-store"
            assert "192.0.2.1" not in response.text
            clock[0] += 901
            assert (await client.get(url)).json()["blocked"] is False
            assert (await client.get("/admin/api/users/999/login-lock")).status_code == 404

    asyncio.run(check())
