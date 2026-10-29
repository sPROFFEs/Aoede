import asyncio
import sys
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock

import httpx
from fastapi import FastAPI

# Match the existing worker tests: route checks do not need a running Docker daemon.
manager_stub = ModuleType("manager")
manager_stub.invalidate_pool_status_cache = lambda: None
manager_stub.get_pool_status = lambda _db: (0, 0, 0)
sys.modules.setdefault("manager", manager_stub)

from routers.music import artist


def test_artist_names_with_slashes_keep_authentication_and_validation(monkeypatch):
    user = SimpleNamespace(download_settings=None)
    lookup = AsyncMock(return_value={})
    monkeypatch.setattr(artist, "get_current_user_safe", lambda *_: user)
    monkeypatch.setattr(artist, "get_artist_view", lookup)
    monkeypatch.setattr(artist, "_local_tracks_for_artist", lambda *_: [])
    monkeypatch.setattr(artist, "get_user_country", lambda *_: "US")
    app = FastAPI()
    app.include_router(artist.router)
    app.dependency_overrides[artist.get_db] = lambda: None

    async def check():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="https://artist.test") as client:
            result = await client.get("/api/artist/AC%2FDC")
            assert result.status_code == 200 and result.json()["name"] == "AC/DC"
            assert lookup.call_args.args == ("AC/DC",)
            assert (await client.get("/api/artist/" + "a" * 257)).status_code == 400
            monkeypatch.setattr(artist, "get_current_user_safe", lambda *_: None)
            assert (await client.get("/api/artist/AC%2FDC")).status_code == 401
            assert lookup.await_count == 1

    asyncio.run(check())
