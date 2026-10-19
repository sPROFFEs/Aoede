import json
import threading
import time
from contextlib import closing
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import database
import wrapped_service as wrapped
from sqlalchemy import event


def summary(user, year=2025):
    return {
        "year": year,
        "generated_at": wrapped._iso_now(),
        "wrapped_schema_version": 1,
        "user": {"id": user.id, "username": user.username},
        "raw_event_count": 3,
        "qualified_event_count": 1,
        "played_seconds": 210,
        "minutes_listened": 3.5,
        "top_songs": [],
        "top_artists": [{"artist": "Shared Artist", "stream_count": 1}],
        "top_artist_sprint": [],
    }


def test_regeneration_builds_each_listener_once_and_reuses_summaries_for_party_and_snapshot(
    db_session, tmp_path, monkeypatch
):
    monkeypatch.setattr(wrapped, "get_wrapped_summary_db_path", lambda: tmp_path / "wrapped.db")
    monkeypatch.setattr(wrapped, "utcnow", lambda: datetime(2026, 10, 19, tzinfo=timezone.utc))
    listeners = [database.User(username=name, hashed_password="unused") for name in ("alice", "bob")]
    db_session.add_all(
        [
            *listeners,
            database.User(username="service", hashed_password="unused", is_service_account=True),
            database.User(username="inactive", hashed_password="unused", is_active=False),
        ]
    )
    db_session.commit()
    builds, artwork_calls = [], []

    def build(db, user, year, **kwargs):
        builds.append(user.username)
        assert kwargs["resolve_artist_images"] is False
        return summary(user, year)

    def artwork(names, **kwargs):
        artwork_calls.append(names)
        return {"shared artist": "/art.jpg"}

    monkeypatch.setattr(wrapped, "build_user_wrapped_summary", build)
    monkeypatch.setattr(wrapped, "_resolve_artist_images", artwork)
    monkeypatch.setattr(
        wrapped,
        "get_or_build_user_wrapped_summary",
        lambda *a, **kw: (_ for _ in ()).throw(AssertionError("Listener rebuilt")),
    )
    result = wrapped.regenerate_wrapped_year(db_session, 2025)
    assert builds == ["alice", "bob"] and len(artwork_calls) == 1
    assert result["user_count"] == result["party_user_count"] == result["snapshot"]["new_user_snapshots"] == 2
    assert (
        wrapped.get_cached_user_wrapped_summary(listeners[0].id, 2025)["top_artists"][0]["artist_image"] == "/art.jpg"
    )


def test_snapshot_resolves_cache_writes_before_opening_its_transaction_and_stays_immutable(
    db_session, tmp_path, monkeypatch
):
    monkeypatch.setattr(wrapped, "get_wrapped_summary_db_path", lambda: tmp_path / "wrapped.db")
    monkeypatch.setattr(wrapped, "utcnow", lambda: datetime(2026, 10, 19, tzinfo=timezone.utc))
    users = [database.User(username=name, hashed_password="unused") for name in ("alice", "bob")]
    db_session.add_all(users)
    db_session.commit()
    wrapped.ensure_wrapped_summary_db()
    monkeypatch.setattr(
        wrapped,
        "get_or_build_user_wrapped_summary",
        lambda db, user, year, **kw: wrapped.save_user_wrapped_summary(summary(user, year)),
    )
    monkeypatch.setattr(wrapped, "get_or_build_party_summary", lambda *a, **kw: {"year": 2025, "user_count": 2})
    first = wrapped.finalize_wrapped_year_snapshot(db_session, 2025)
    second = wrapped.finalize_wrapped_year_snapshot(db_session, 2025)
    assert first["new_user_snapshots"] == 2 and second["new_user_snapshots"] == 0
    with closing(wrapped._connect_summary()) as connection:
        assert connection.execute("SELECT COUNT(*) FROM wrapped_yearly_snapshots").fetchone()[0] == 2


def test_artist_images_are_deduplicated_bounded_parallel_cached_and_keep_stale_images_on_failure(tmp_path, monkeypatch):
    cache = tmp_path / "artists.json"
    monkeypatch.setattr(wrapped, "WRAPPED_ARTIST_IMAGE_CACHE_PATH", cache)
    monkeypatch.setattr(wrapped, "_safe_unix_now", lambda: 100000000)
    cache.write_text(
        json.dumps(
            {
                "artists": {
                    "cached": {"image_url": "/cached.jpg", "checked_at": 99999999},
                    "stale": {"image_url": "/old.jpg", "checked_at": "bad timestamp"},
                }
            }
        )
    )
    lock = threading.Lock()
    active, maximum, calls = 0, 0, []

    def fetch(name):
        nonlocal active, maximum
        with lock:
            calls.append(name)
            active += 1
            maximum = max(maximum, active)
        time.sleep(0.02)
        with lock:
            active -= 1
        return "" if name == "Stale" else f"/{name}.jpg"

    monkeypatch.setattr(wrapped, "_extract_artist_image_from_youtube", fetch)
    names = ["Artist A", "artist a", "Artist B", "Artist C", "Artist D", "Cached", "Stale"]
    images = wrapped._resolve_artist_images(names, allow_fetch=True)
    assert len(calls) == 5 and 2 <= maximum <= 4
    assert images["cached"] == "/cached.jpg" and images["stale"] == "/old.jpg"
    wrapped._resolve_artist_images(names, allow_fetch=True)
    assert len(calls) == 5


def test_large_track_lookup_batches_sqlite_parameters(db_session):
    tracks = [database.Track(title=f"Track {i}", filepath=f"{i}.mp3") for i in range(1001)]
    db_session.add_all(tracks)
    db_session.commit()
    ids = {track.id for track in tracks}
    parameters = []

    def query(conn, cursor, statement, values, context, many):
        if " IN (" in statement:
            parameters.append(len(values))

    event.listen(db_session.bind, "before_cursor_execute", query)
    try:
        assert set(wrapped._track_lookup(db_session, ids)) == ids
        assert parameters == [500, 500, 1]
    finally:
        event.remove(db_session.bind, "before_cursor_execute", query)


def test_terminal_tracking_events_count_one_listen_per_session(db_session, monkeypatch):
    user = database.User(username="listener", hashed_password="unused")
    track = database.Track(title="A song", artist="Artist", filepath="song.mp3", duration=210)
    db_session.add_all([user, track])
    db_session.commit()
    monkeypatch.setattr(wrapped, "get_wrapped_settings", lambda db: {"artist_clip_message": "Your year"})
    monkeypatch.setattr(wrapped, "_resolve_artist_images", lambda *a, **kw: {})
    rows = [
        {
            "id": i,
            "event_type": kind,
            "session_id": "one-listen",
            "track_id": track.id,
            "played_seconds": seconds,
            "duration_seconds": 210,
            "timestamp_utc": "2025-06-01T10:00:00+00:00",
        }
        for i, (kind, seconds) in enumerate([("play_begin", 0), ("play_30s", 30), ("play_complete", 210)], 1)
    ]
    result = wrapped._build_user_summary_from_rows(db_session, user, 2025, rows)
    assert result["raw_event_count"] == 3 and result["qualified_event_count"] == 1
    assert result["played_seconds"] == 210 and result["top_songs"][0]["stream_count"] == 1


def test_top_songs_save_batches_track_lookup_keeps_order_and_avoids_empty_playlists(db_session, monkeypatch):
    with patch("docker.from_env", return_value=MagicMock()):
        from routers.music import favorites, playlists
        from routers.music import wrapped as routes

    user = database.User(username="alice", hashed_password="unused")
    tracks = [database.Track(title=f"Song {index}", filepath=f"/song{index}.mp3") for index in range(3)]
    db_session.add_all([user, *tracks])
    db_session.commit()
    ids = [track.id for track in tracks]
    monkeypatch.setattr(routes, "get_current_user_safe", lambda *a: user)
    monkeypatch.setattr(wrapped, "get_wrapped_settings", lambda db: {"visible": True, "year": 2026})
    payload = {"year": 2026, "top_songs_playlist": {"tracks": [{"db_id": id} for id in [ids[2], 999, ids[0], ids[2]]]}}
    monkeypatch.setattr(wrapped, "get_or_build_user_wrapped_summary", lambda *a: payload)
    for module, name in [
        (favorites, "schedule_navidrome_sync"),
        (playlists, "generate_m3u_for_playlist"),
        (playlists, "schedule_playlist_sync"),
    ]:
        monkeypatch.setattr(module, name, lambda *a, **kw: None)
    selects = []

    def record(connection, cursor, statement, parameters, context, executemany):
        if "FROM tracks" in statement:
            selects.append(statement)

    event.listen(db_session.bind, "before_cursor_execute", record)
    try:
        result = routes.save_wrapped_top_songs_playlist(2026, None, db_session)
        assert result.status_code == 200 and json.loads(result.body)["track_count"] == 2
        assert len(selects) == 1
        saved = db_session.query(database.PlaylistItem).order_by(database.PlaylistItem.position).all()
        assert [(item.track_id, item.position) for item in saved] == [(ids[2], 0), (ids[0], 1)]
        payload["top_songs_playlist"]["tracks"] = [{"db_id": 999}]
        assert routes.save_wrapped_top_songs_playlist(2026, None, db_session).status_code == 404
        assert db_session.query(database.Playlist).count() == 1
    finally:
        event.remove(db_session.bind, "before_cursor_execute", record)
