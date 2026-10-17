import asyncio
from types import SimpleNamespace

import lyrics_service


def test_lyrics_search_selects_the_recording_and_keeps_versions_separate(monkeypatch):
    asyncio.run(_check_recordings(monkeypatch))


async def _check_recordings(monkeypatch):
    cache, calls = {}, []
    monkeypatch.setattr(lyrics_service.metadata_cache, "get", cache.get)
    monkeypatch.setattr(lyrics_service.metadata_cache, "set", lambda key, value: cache.update({key: value}))
    results = [
        dict(trackName="Song", artistName="Artist", duration=290, syncedLyrics="wrong live version"),
        dict(trackName="Song", artistName="Artist", duration=200, albumName="Other", syncedLyrics="other edition"),
        dict(trackName="Song", artistName="Artist", duration=201, albumName="Album", syncedLyrics="right version"),
    ]

    async def get(url, **kwargs):
        calls.append((url, kwargs))
        return SimpleNamespace(status_code=200 if url.endswith("/search") else 404, json=lambda: results)

    monkeypatch.setattr(lyrics_service.http_client, "get", get)
    matched = await lyrics_service.get_lyrics(title="Song", artist="Artist", album="Album", duration=200)
    assert matched["synced"] == "right version"
    assert (await lyrics_service.get_lyrics(title="Song", artist="Artist", album="Album", duration=200))[
        "source"
    ] == "cache"
    assert len(calls) == 2
    different = await lyrics_service.get_lyrics(title="Song", artist="Artist", album="Live", duration=290)
    assert different["synced"] == "wrong live version"
    missing = await lyrics_service.get_lyrics(title="Song", artist="Artist", duration=400)
    assert missing["source"] == "miss"
    assert len(cache) == 3

    async def unavailable(*args, **kwargs):
        return SimpleNamespace(status_code=503)

    monkeypatch.setattr(lyrics_service.http_client, "get", unavailable)
    failed = await lyrics_service.get_lyrics(title="Transient outage", artist="Artist", duration=200)
    assert failed["source"] == "unavailable"
    assert len(cache) == 3  # A transient failure must not become a 24-hour negative cache entry.
