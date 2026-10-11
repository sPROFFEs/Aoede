import asyncio
import json
from unittest.mock import MagicMock, patch

import collection_service as collections
import database
import httpx
import pytest
from fastapi import BackgroundTasks, FastAPI
from starlette.requests import Request

with patch("docker.from_env", return_value=MagicMock()):
    import downloader_service
    from routers.music import downloads, playlists, sync


@pytest.fixture
def importer(db_session, monkeypatch, tmp_path):
    user = database.User(username="collector", hashed_password="unused")
    db_session.add(user)
    db_session.commit()
    monkeypatch.setattr(downloads, "get_current_user_safe", lambda *_: user)
    monkeypatch.setattr(playlists, "get_current_user_safe", lambda *_: user)
    monkeypatch.setattr(downloads.manager, "get_pool_status", lambda *_: (0, 100, 0))
    monkeypatch.setattr(collections.metadata_cache, "CACHE_DB_PATH", str(tmp_path / "cache.db"))
    return user, Request({"type": "http", "headers": []}), BackgroundTasks()


@pytest.mark.parametrize(
    "url",
    [
        "https://open.spotify.com.evil.test/album/" + "a" * 22,
        "https://evil.test/?url=https://youtube.com/playlist&list=PL1234567890",
        "https://user:password@youtube.com/playlist?list=PL1234567890",
        "file:///youtube.com/playlist?list=PL1234567890",
        "https://youtube.com:9999/playlist?list=PL1234567890",
        "https://youtube.com:broken/playlist?list=PL1234567890",
        "https://youtube.com/playlist?list=short",
        "https://[broken/playlist?list=PL1234567890",
        "https://youtube.com/watch?v=abcdefghijk",
    ],
)
def test_collection_url_boundary(url):
    assert collections.parse_collection_url(url) is None


def test_collection_urls_are_canonical_and_locale_aware():
    album_id = "a" * 22
    assert collections.parse_collection_url(f"https://open.spotify.com/intl-es/album/{album_id}?si=share") == (
        "spotify",
        "album",
        album_id,
        f"https://open.spotify.com/album/{album_id}",
    )
    assert (
        collections.parse_collection_url("https://youtu.be/abcdefghijk?list=PL1234567890")[3]
        == "https://www.youtube.com/playlist?list=PL1234567890"
    )


def test_spotify_collection_paginates_modern_items_preserving_repeats_and_skips(importer, monkeypatch):
    user, _, _ = importer
    pages = [
        {
            "name": "Ordered",
            "items": {
                "items": [{"item": {"id": "a" * 22, "name": "First"}}, {"item": None}],
                "offset": 0,
                "total": 4,
                "next": "https://evil.test/steal-token",
            },
        },
        {
            "items": [{"item": {"id": "b" * 22, "name": "Second"}}, {"item": {"id": "a" * 22, "name": "First"}}],
            "next": None,
        },
    ]
    calls = []

    async def get(_user, path, params=None):
        calls.append((path, params))
        return pages.pop(0)

    monkeypatch.setattr(collections, "_spotify_get", get)
    data = asyncio.run(collections.resolve_collection(user, "https://open.spotify.com/playlist/" + "c" * 22))
    assert [track["position"] for track in data["tracks"]] == [1, 3, 4]
    assert data["tracks"][0]["url"] == data["tracks"][2]["url"]
    assert data["skipped"] == 1 and data["track_count"] == 3
    assert calls[1] == (f"playlists/{'c' * 22}/items", {"limit": 50, "offset": 2})
    assert asyncio.run(collections.resolve_collection(user, data["url"])) == data  # Preview + import fetch once.


def test_youtube_album_search_and_ordered_metadata(importer, monkeypatch):
    user, _, _ = importer

    class Music:
        def search(self, query, filter, limit):
            assert query == "Album" and limit == 10
            return (
                [{"title": "Album", "browseId": "MPREb_123456789", "artists": [{"name": "Artist"}]}]
                if filter == "albums"
                else []
            )

        def get_album(self, identifier):
            assert identifier == "MPREb_123456789"
            return {
                "title": "Album",
                "artists": [{"name": "Artist"}],
                "tracks": [
                    {"videoId": "abcdefghijk", "title": "First", "artists": [{"name": "Artist"}]},
                    {"videoId": "lmnopqrstuv", "title": "Unavailable", "isAvailable": False},
                    {"videoId": "abcdefghijk", "title": "First again"},
                ],
            }

    monkeypatch.setattr(collections, "YTMusic", Music)
    search = asyncio.run(collections.search_collections(user, "Album", "youtube"))
    assert len(search["items"]) == 1 and search["items"][0]["kind"] == "album"
    album = asyncio.run(collections.resolve_collection(user, search["items"][0]["url"]))
    assert [track["position"] for track in album["tracks"]] == [1, 3]
    assert album["skipped"] == 1 and album["tracks"][0]["album"] == "Album"
    assert album["tracks"][1]["artist"] == "Artist"  # Album-level metadata covers sparse track entries.


def test_spotify_restrictions_are_visible_without_breaking_youtube(importer, monkeypatch):
    user, _, _ = importer
    monkeypatch.setattr(collections, "_youtube_search", lambda _: [{"name": "YouTube album"}])
    data = asyncio.run(collections.search_collections(user, "Album", "all"))
    assert data["items"] == [{"name": "YouTube album"}]
    assert "Configure Spotify" in data["warnings"][0]
    with pytest.raises(collections.CollectionError, match="Configure Spotify"):
        asyncio.run(collections.resolve_collection(user, "https://open.spotify.com/album/" + "a" * 22))


def test_import_ownership_job_order_retry_and_pool_reuse(db_session, importer, monkeypatch):
    user, request, background = importer
    data = {
        "name": "Album's name",
        "source": "youtube",
        "kind": "album",
        "skipped": 1,
        "tracks": [
            {
                "url": "https://www.youtube.com/watch?v=abcdefghijk",
                "title": "First",
                "artist": "Artist",
                "album": "Album",
                "position": 1,
            },
            {
                "url": "https://www.youtube.com/watch?v=lmnopqrstuv",
                "title": "Third",
                "artist": "Artist",
                "album": "Album",
                "position": 3,
            },
            {
                "url": "https://www.youtube.com/watch?v=abcdefghijk",
                "title": "First",
                "artist": "Artist",
                "album": "Album",
                "position": 4,
            },
        ],
    }

    async def resolve(*_):
        return data

    monkeypatch.setattr(collections, "resolve_collection", resolve)
    result = asyncio.run(
        downloads.import_collection.__wrapped__(
            downloads.CollectionDownloadRequest(url="https://www.youtube.com/playlist?list=PL1234567890"),
            background,
            request,
            db_session,
        )
    )
    playlist = db_session.get(database.Playlist, result["playlist_id"])
    assert playlist.owner_id == user.id and playlist.kind == "album" and playlist.name == data["name"]
    jobs = db_session.query(database.DownloadJob).order_by(database.DownloadJob.id).all()
    assert [job.target_position for job in jobs] == [1, 3, 4]
    assert all(job.user_id == user.id and job.target_modern_playlist_id == playlist.id for job in jobs)
    assert result["skipped"] == 1 and len(background.tasks) == 1
    jobs[0].status = "failed"
    db_session.commit()
    retry = asyncio.run(downloads.retry_download(jobs[0].id, background, request, db_session))
    retried = db_session.get(database.DownloadJob, retry["job_id"])
    assert retried.target_position == 1 and retried.target_modern_playlist_id == playlist.id

    tracks = [database.Track(title=title, filepath=f"/pool/{title}.mp3") for title in ("First", "Third")]
    db_session.add_all(tracks)
    db_session.commit()
    manager = downloader_service.DownloadManager(db_session, user.id)
    assert manager._link_track_to_playlist(playlist, tracks[1], 3)
    assert manager._link_track_to_playlist(playlist, tracks[0], 4)
    assert manager._link_track_to_playlist(playlist, tracks[0], 1)
    assert not manager._link_track_to_playlist(playlist, tracks[0], 1)
    assert [item.track_id for item in sorted(playlist.items, key=lambda item: item.position)] == [
        tracks[0].id,
        tracks[1].id,
        tracks[0].id,
    ]
    assert db_session.query(database.Track).count() == 2


@pytest.mark.parametrize("track_count", [0, collections.MAX_COLLECTION_TRACKS + 1])
def test_oversized_or_empty_collection_creates_nothing(db_session, importer, monkeypatch, track_count):
    user, request, background = importer

    class Music:
        def get_playlist(self, *_args, **_kwargs):
            return {"title": "Collection", "trackCount": track_count, "tracks": []}

    monkeypatch.setattr(collections, "YTMusic", Music)
    result = asyncio.run(
        downloads.import_collection.__wrapped__(
            downloads.CollectionDownloadRequest(url="https://www.youtube.com/playlist?list=PL1234567890"),
            background,
            request,
            db_session,
        )
    )
    expected = "limited" if track_count else "no available"
    assert result.status_code == 400 and expected in json.loads(result.body)["error"]
    assert db_session.query(database.Playlist).count() == db_session.query(database.DownloadJob).count() == 0
    assert not background.tasks


def test_youtube_playlist_falls_back_to_flat_metadata_without_downloading(importer, monkeypatch):
    user, _, _ = importer

    class Music:
        def get_playlist(self, *_args, **_kwargs):
            raise ValueError("Not available on YouTube Music")

    class FlatPlaylist:
        def __init__(self, options):
            assert options["skip_download"] and options["playlistend"] == collections.MAX_COLLECTION_TRACKS + 1

        def __enter__(self):
            return self

        def __exit__(self, *_):
            pass

        def extract_info(self, url, download):
            assert not download and url == "https://www.youtube.com/playlist?list=PL1234567890"
            return {
                "title": "Playlist",
                "entries": [
                    {"id": "abcdefghijk", "title": "First", "uploader": "Artist"},
                    {"id": "lmnopqrstuv", "title": "[Private video]"},
                    {"id": "abcdefghijk", "title": "First again"},
                ],
            }

    monkeypatch.setattr(collections, "YTMusic", Music)
    monkeypatch.setattr(collections.yt_dlp, "YoutubeDL", FlatPlaylist)
    result = asyncio.run(collections.resolve_collection(user, "https://www.youtube.com/playlist?list=PL1234567890"))
    assert [track["position"] for track in result["tracks"]] == [1, 3]
    assert result["skipped"] == 1 and result["track_count"] == 2


def test_spotify_request_never_leaks_error_body(importer, monkeypatch):
    user, _, _ = importer
    user.download_settings = database.DownloadSettings(spotify_client_id="test-id", spotify_client_secret="test-secret")
    service = collections.spotify_service.spotify_service

    async def token(*_):
        return "test-token"

    async def get(url, **kwargs):
        assert url.startswith("https://api.spotify.com/v1/")
        return httpx.Response(403, text="test-secret test-token")

    monkeypatch.setattr(service, "_get_access_token", token)
    monkeypatch.setattr(service.client, "get", get)
    with pytest.raises(collections.CollectionError) as error:
        asyncio.run(collections._spotify_get(user, "albums/" + "a" * 22))
    assert error.value.status_code == 403 and "test-secret" not in str(error.value)


def test_registered_collection_routes_and_legacy_download_path(db_session, importer, monkeypatch):
    user, _, _ = importer
    data = {
        "name": "A" * 120,
        "kind": "album",
        "source": "youtube",
        "skipped": 0,
        "url": "https://www.youtube.com/playlist?list=PL1234567890",
        "track_count": 1,
        "tracks": [
            {
                "url": "https://www.youtube.com/watch?v=abcdefghijk",
                "title": "Song",
                "artist": "Artist",
                "album": "Album",
                "position": 1,
            }
        ],
    }
    batches = []

    async def resolve(*_):
        return data

    async def batch(job_ids, owner_id):
        batches.append((job_ids, owner_id))

    async def get_db():
        return db_session

    monkeypatch.setattr(collections, "resolve_collection", resolve)
    monkeypatch.setattr(downloads, "run_collection_downloads", batch)
    monkeypatch.setattr(
        downloads,
        "get_current_user_safe",
        lambda _db, req: user if req.headers.get("authorization") == "test" else None,
    )
    app = FastAPI()
    app.state.limiter = downloads.limiter
    app.include_router(downloads.router)
    app.dependency_overrides[downloads.get_db] = get_db

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            for path in ("/api/collections/resolve?url=test", "/api/collections/search?q=Album", "/api/jobs"):
                assert (await client.get(path)).status_code == 401
            assert (await client.post("/api/download/collection", json={"url": data["url"]})).status_code == 401
            client.headers["authorization"] = "test"
            assert (await client.get("/api/collections/resolve", params={"url": data["url"]})).json() == data
            assert (
                await client.post("/api/download/collection", json={"url": data["url"], "kind": "bad"})
            ).status_code == 422
            for path in ("/api/download", "/api/download/collection"):
                response = await client.post(path, json={"url": data["url"]})
                assert response.status_code == 200, response.text
                assert response.json()["kind"] == "album"
            monkeypatch.setattr(collections, "MAX_COLLECTION_TRACKS", 2)
            assert (await client.post("/api/download/collection", json={"url": data["url"]})).status_code == 409

    asyncio.run(exercise())
    assert len(batches) == 2 and all(owner == user.id for _, owner in batches)
    assert db_session.query(database.Playlist).count() == 2
    assert db_session.query(database.DownloadJob).count() == 2
    names = [playlist.name for playlist in db_session.query(database.Playlist).all()]
    assert len(set(names)) == 2 and all(len(name) <= 120 for name in names)


def test_large_failed_import_stays_retryable_and_refreshes_ui(db_session, importer, monkeypatch):
    user, request, background = importer
    monkeypatch.setattr(sync, "get_current_user_safe", lambda *_: user)
    album = database.Playlist(name="Large album", owner_id=user.id, kind="album")
    db_session.add(album)
    db_session.flush()
    jobs = [
        database.DownloadJob(
            user_id=user.id,
            input_url="https://youtu.be/abcdefghijk",
            status="failed",
            target_modern_playlist_id=album.id,
            target_position=position,
        )
        for position in range(1, 127)
    ]
    jobs[-1].status = "pending"
    db_session.add_all(jobs)
    db_session.add(
        database.DownloadJob(
            user_id=user.id,
            input_url="https://youtu.be/abcdefghijk",
            status="completed",
            target_modern_playlist_id=album.id,
            target_position=1,
        )
    )
    db_session.commit()
    assert downloads._prune_terminal_download_jobs(db_session, user.id, keep=2) == 0
    filtered = json.loads(asyncio.run(downloads.list_download_jobs(request, db_session, album.id)).body)
    assert len(filtered) == 126 and filtered[0]["status"] == "completed"
    global_jobs = json.loads(asyncio.run(downloads.list_download_jobs(request, db_session)).body)
    assert global_jobs[0]["id"] == jobs[-1].id  # Current job isn't hidden by the terminal history cap.
    detail = json.loads(asyncio.run(playlists.get_playlist(album.id, request, db_session)).body)
    assert detail["import_pending"] == 1 and detail["import_failed"] == 124
    version = json.loads(asyncio.run(sync.get_sync_state(request, db_session)).body)["version"]
    jobs[-1].status = "failed"
    db_session.commit()
    assert json.loads(asyncio.run(sync.get_sync_state(request, db_session)).body)["version"] != version
    retried = asyncio.run(downloads.retry_download(jobs[-1].id, background, request, db_session))
    assert db_session.get(database.DownloadJob, retried["job_id"]).target_position == 126
    assert asyncio.run(downloads.retry_download(jobs[-1].id, background, request, db_session)).status_code == 409
    monkeypatch.setattr(collections, "MAX_COLLECTION_TRACKS", 1)
    assert asyncio.run(downloads.retry_download(jobs[1].id, background, request, db_session)).status_code == 409


def test_retry_can_copy_job_before_old_history_is_pruned(db_session, importer):
    user, request, background = importer
    old = database.DownloadJob(user_id=user.id, input_url="https://youtu.be/abcdefghijk", status="failed")
    db_session.add(old)
    db_session.flush()
    old_id = old.id
    db_session.add_all(
        [
            database.DownloadJob(user_id=user.id, input_url="https://youtu.be/abcdefghijk", status="completed")
            for _ in range(downloads.DOWNLOAD_JOB_HISTORY_LIMIT + 1)
        ]
    )
    db_session.commit()
    result = asyncio.run(downloads.retry_download(old_id, background, request, db_session))
    assert db_session.get(database.DownloadJob, result["job_id"]).input_url == "https://youtu.be/abcdefghijk"
    assert db_session.query(database.DownloadJob).filter_by(id=old_id).first() is None
