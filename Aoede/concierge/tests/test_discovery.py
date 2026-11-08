import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
from fastapi import FastAPI

with patch("docker.from_env", return_value=MagicMock()):
    from routers.music import recommendations as recs


def test_discovery_categories_preserve_explanations_and_infer_missing_fields():
    result = recs._discovery_sections(
        [
            {"title": "YouTube Trending", "items": [{"title": "Hit", "reason": "Custom explanation"}]},
            {"title": "Spotify For You", "items": [{"title": "Personal", "category": "for_you"}]},
            {"title": "Explore", "items": [{"title": "Gem", "is_local": False}]},
            {"title": "Recently Added", "items": [{"title": "Owned", "is_local": True}]},
        ]
    )
    assert [item["category"] for item in result] == ["trending", "for_you", "gems"]
    assert result[0]["reason"] == "Custom explanation"
    assert all(item["badge"] and item["reason"] for item in result)


def test_trending_requests_fetch_their_own_category_and_keep_authentication(db_session, monkeypatch, tmp_path):
    monkeypatch.setattr(recs, "RECS_CACHE_DIR", str(tmp_path))
    user = SimpleNamespace(id=1, username="discovery", download_settings=None)
    monkeypatch.setattr(recs, "get_current_user_safe", lambda *_: user)
    personal = [{"title": f"Personal {i}", "artist": "Artist", "reason": "Taste match"} for i in range(40)]
    (tmp_path / "recs_discovery.json").write_text(json.dumps({"sections": [{"title": "For You", "items": personal}]}))
    trending = AsyncMock(
        return_value=[
            {
                "id": "sample",
                "url": "https://www.youtube.com/watch?v=sample",
                "title": "Real trending result",
                "artist": "Singer",
                "image": "https://i.ytimg.com/vi/sample/mqdefault.jpg",
            }
        ]
    )
    monkeypatch.setattr(recs.youtube_service.youtube_service, "get_trending_music", trending)
    app = FastAPI()
    app.state.limiter = recs.limiter
    app.include_router(recs.router)
    app.dependency_overrides[recs.get_db] = lambda: db_session

    async def check():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="https://discovery.test"
        ) as client:
            all_items = (await client.get("/api/discovery/feed?limit=30")).json()["items"]
            assert len(all_items) == 30 and all(item["category"] == "for_you" for item in all_items)
            assert trending.await_count == 0
            result = await client.get("/api/discovery/feed?limit=30&category=trending")
            assert result.status_code == 200
            assert result.json()["items"][0]["title"] == "Real trending result"
            assert result.json()["items"][0]["category"] == "trending"
            assert trending.call_args.kwargs.get("query_override") is None
            assert "cookie_path" not in trending.call_args.kwargs
            trending.side_effect = OSError("provider unavailable")
            empty = await client.get("/api/discovery/feed?category=trending")
            assert empty.status_code == 200 and empty.json()["items"] == []
            assert (await client.get("/api/discovery/feed?category=invalid")).status_code == 400
            monkeypatch.setattr(recs, "get_current_user_safe", lambda *_: None)
            assert (await client.get("/api/discovery/feed?category=trending")).status_code == 401
            assert trending.await_count == 2

    asyncio.run(check())
