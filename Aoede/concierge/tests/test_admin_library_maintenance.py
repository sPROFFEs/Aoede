import importlib.util
import sys
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

import database
import library_maintenance
import track_identity
from sqlalchemy import text


def test_library_audit_reports_files_metadata_sources_and_size(db_session, tmp_path):
    present = tmp_path / "present.mp3"
    present.write_bytes(b"audio")
    missing = tmp_path / "missing.mp3"
    db_session.add_all(
        [
            database.Track(
                title="Complete",
                artist="Artist",
                album="Album",
                genre="Rock",
                year=2024,
                filepath=str(present),
                source_id="local:present",
                file_hash="present",
                source_provider="local",
                metadata_scanned_at=datetime.now(timezone.utc),
            ),
            database.Track(
                title="Incomplete",
                artist="",
                album="Album",
                filepath=str(missing),
                source_id="local:missing",
                file_hash="missing",
                source_provider="download",
            ),
        ]
    )
    db_session.commit()
    result = library_maintenance.build_library_audit(db_session, roots=(str(tmp_path),))

    assert result["track_count"] == 2
    assert result["total_bytes"] == 5
    assert result["missing_file_count"] == 1
    assert result["missing_files"][0]["title"] == "Incomplete"
    assert result["missing_metadata_count"] == 1
    assert result["metadata_pending_count"] == 1
    assert result["source_counts"] == {"local": 1, "download": 1}


def test_duplicate_scan_merges_overlapping_matches_and_compares_available_files(db_session, tmp_path):
    # Concierge and the standalone worker have different auth_browser modules.
    # Scope this import so combined test collection cannot bind one to the other.
    spec = importlib.util.spec_from_file_location(
        "aoede_duplicate_test_auth_browser", Path(__file__).resolve().parents[1] / "auth_browser.py"
    )
    browser = importlib.util.module_from_spec(spec)
    monkey_modules = {spec.name: browser, "auth_browser": browser}
    with patch.dict(sys.modules, monkey_modules), patch("docker.from_env", return_value=MagicMock()):
        spec.loader.exec_module(browser)
        from routers import admin

    # Legacy schemas can contain repeated source IDs/hashes; fresh schemas prevent them.
    db_session.execute(text("DROP INDEX ix_tracks_file_hash"))
    db_session.execute(text("DROP INDEX ix_tracks_source_id"))
    entries = []
    for index, (title, artist, file_hash) in enumerate(
        [
            ("Song", "Artist", "same-audio"),
            ("Song", "ARTIST", "same-audio"),
            ("Song", "Artist", "other-encoding"),
            ("Song (Live)", "Artist", "live-audio"),
            ("Unknown", "Unknown Artist", ""),
            ("Unknown", "Unknown Artist", ""),
            ("Exact one", "Artist", "exact-only"),
            ("Different tags", "Other", "exact-only"),
        ]
    ):
        audio = tmp_path / f"{index}.mp3"
        if index != 1:
            audio.write_bytes(b"audio")
        track = database.Track(
            title=title,
            artist=artist,
            album="Album",
            filepath=str(audio),
            file_hash=file_hash,
            source_id="" if index in (4, 5) else f"local:{0 if index == 1 else index}",
            duration=120,
        )
        track_identity.apply_identity_fields(track)
        entries.append(track)
    owner = database.User(username="owner", hashed_password="unused")
    db_session.add_all([owner, *entries])
    db_session.flush()
    collection = database.Playlist(name="Saved", owner_id=owner.id)
    db_session.add(collection)
    db_session.flush()
    db_session.add(database.PlaylistItem(playlist_id=collection.id, track_id=entries[2].id, position=1))
    db_session.commit()

    db_session.execute(text("CREATE VIRTUAL TABLE tracks_fts USING fts5(title, artist, album)"))
    db_session.execute(
        text("INSERT INTO tracks_fts(rowid, title, artist, album) VALUES (:id, 'Song', 'Artist', 'Album')"),
        {"id": entries[0].id},
    )
    searched = admin._query_tracks_with_fts(db_session, "Song", 50)
    assert searched[0]["album"] == "Album" and searched[0]["duration"] == 120

    data = admin._build_duplicate_scan_result(db_session, 1)
    assert data["count"] == 2 and data["returned_count"] == 1 and data["truncated"]
    assert data["exact_count"] == data["possible_count"] == 1
    group = data["groups"][0]
    assert {track["id"] for track in group["tracks"]} == {track.id for track in entries[:3]}
    assert set(group["reasons"]) == {"source_id", "file_hash", "semantic"}
    assert group["match_type"] == "possible" and group["recommended_track_id"] == entries[2].id
    assert group["tracks"][0]["file_exists"] and group["tracks"][0]["file_size"] == 5
    assert not group["tracks"][1]["file_exists"]
    assert group["tracks"][2]["playlist_count"] == 1 and group["tracks"][2]["format"] == "MP3"
    full = admin._build_duplicate_scan_result(db_session, 50)
    assert full["groups"][1]["match_type"] == "exact"
    assert {track["id"] for track in full["groups"][1]["tracks"]} == {track.id for track in entries[6:]}
    entries[2].fingerprint = None
    db_session.commit()
    exact = admin._build_duplicate_scan_result(db_session, 50)
    assert exact["exact_count"] == 2 and exact["possible_count"] == 0
    assert exact["similar_count"] == 2
    limited = admin._build_duplicate_scan_result(db_session, 3)
    assert any(group["match_type"] == "similar" for group in limited["groups"])
    for index, track in enumerate(entries[:2]):
        track.source_id = " "
        track.file_hash = f"different-{index}"
    db_session.commit()
    blank_ids = admin._build_duplicate_scan_result(db_session, 50)
    assert blank_ids["exact_count"] == blank_ids["possible_count"] == 1
