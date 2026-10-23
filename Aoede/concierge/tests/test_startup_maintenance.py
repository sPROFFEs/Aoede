import asyncio
from pathlib import Path
from unittest.mock import MagicMock, patch

import database
import pytest
import track_identity
import yaml
from sqlalchemy.pool import NullPool

PROJECT_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def main_app(monkeypatch):
    monkeypatch.chdir(PROJECT_ROOT)
    with patch("docker.from_env", return_value=MagicMock()):
        import main

    monkeypatch.setattr(main.settings, "SECRET_KEY", "test-startup-secret-" * 3)
    return main


def test_startup_recovers_jobs_before_serving_without_scanning_library(main_app, db_session, monkeypatch, caplog):
    main = main_app
    interrupted = database.AdminJob(job_type="backup", status="running", triggered_by="system")
    updating = database.AdminJob(job_type="apply_update", status="running", triggered_by="system")
    db_session.add_all([interrupted, updating])
    db_session.commit()
    interrupted_id, updating_id = interrupted.id, updating.id
    monkeypatch.setattr(main.database, "SessionLocal", lambda: db_session)
    migrations = MagicMock(return_value=[])
    scan = MagicMock(side_effect=AssertionError("Library scan must not gate startup"))
    monkeypatch.setattr(main.operations_service, "apply_schema_migrations", migrations)
    monkeypatch.setattr(main.track_identity, "sync_track_identities", scan)
    monkeypatch.setattr(main.media_metadata, "backfill_library_metadata", scan)
    monkeypatch.setattr(main.settings, "STARTUP_LOUDNESS_BACKFILL", False)
    tasks = []
    monkeypatch.setattr(main.asyncio, "create_task", tasks.append)
    with caplog.at_level("INFO"):
        try:
            asyncio.run(main.startup_event())
        finally:
            for task in tasks:
                task.close()
    migrations.assert_called_once()
    scan.assert_not_called()
    assert db_session.get(database.AdminJob, interrupted_id).status == "failed"
    assert db_session.get(database.AdminJob, updating_id).status == "running"
    assert "Startup ready in" in caplog.text


def test_library_maintenance_waits_then_scans_off_loop_and_survives_identity_failure(main_app, db_session, monkeypatch):
    main = main_app
    calls = []
    monkeypatch.setattr(main.database, "SessionLocal", lambda: db_session)

    def identity(db):
        calls.append("identity")
        raise RuntimeError("test identity failure")

    monkeypatch.setattr(main.track_identity, "sync_track_identities", identity)
    monkeypatch.setattr(main.media_metadata, "backfill_library_metadata", lambda: calls.append("metadata"))

    async def check():
        waiting, proceed = asyncio.Event(), asyncio.Event()

        async def sleep(seconds):
            calls.append(seconds)
            waiting.set()
            await proceed.wait()

        monkeypatch.setattr(main.asyncio, "sleep", sleep)
        task = asyncio.create_task(main.library_metadata_backfill())
        await waiting.wait()
        assert calls == [30]
        proceed.set()
        await task
        assert calls == [30, "identity", "metadata"]

    asyncio.run(check())


def test_maintenance_schedulers_wait_before_first_work(main_app, monkeypatch):
    import backup_service

    main = main_app
    delays = []

    async def stop_at_delay(seconds):
        delays.append(seconds)
        raise asyncio.CancelledError

    monkeypatch.setattr(main.asyncio, "sleep", stop_at_delay)
    for scheduler, delay in [
        (main.cache_cleanup_scheduler, 60),
        (backup_service.autobackup_scheduler, 90),
        (main.wrapped_daily_scheduler, 120),
        (main.loudness_backfill, 180),
    ]:
        with pytest.raises(asyncio.CancelledError):
            asyncio.run(scheduler())
        assert delays[-1] == delay


def test_sqlite_does_not_cap_concurrent_streaming_requests():
    assert isinstance(database.engine.pool, NullPool)


def test_identity_sync_only_reads_tracks_missing_identity_fields(db_session, monkeypatch):
    complete = database.Track(
        title="Complete",
        artist="Artist",
        filepath="/music/complete.mp3",
        artist_norm="artist",
        title_norm="complete",
        version_tag="original",
        fingerprint="artist::complete::original",
    )
    incomplete = database.Track(title="Missing", artist="Artist", filepath="/music/missing.mp3")
    db_session.add_all([complete, incomplete])
    db_session.commit()

    calls = []
    original = track_identity.compute_track_identity

    def recording_identity(artist, title):
        calls.append((artist, title))
        return original(artist, title)

    monkeypatch.setattr(track_identity, "compute_track_identity", recording_identity)

    assert track_identity.sync_track_identities(db_session) == 1
    assert calls == [("Artist", "Missing")]


def test_concierge_permission_repair_is_guarded_by_a_migration_marker():
    entrypoint = (PROJECT_ROOT / "concierge" / "entrypoint.sh").read_text(encoding="utf-8")

    assert 'PERMISSION_MARKER="/saas-data/.permissions-v2"' in entrypoint
    assert 'if [ ! -f "$PERMISSION_MARKER" ]; then' in entrypoint
    assert 'touch "$PERMISSION_MARKER"' in entrypoint


def test_runtime_tracing_and_startup_loudness_are_opt_in_in_all_compose_variants():
    compose_paths = [
        PROJECT_ROOT / "docker-compose.yaml",
        PROJECT_ROOT / "deployment-templates" / "internal" / "docker-compose.yaml",
        PROJECT_ROOT / "deployment-templates" / "domain" / "docker-compose.yaml",
    ]

    for compose_path in compose_paths:
        payload = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
        environment = payload["services"]["concierge"]["environment"]
        assert "PYTHONTRACEMALLOC=${PYTHONTRACEMALLOC:-0}" in environment
        assert "STARTUP_LOUDNESS_BACKFILL=${STARTUP_LOUDNESS_BACKFILL:-false}" in environment


def test_short_remote_requests_share_connection_pool_and_shutdown_closes_provider_clients():
    concierge_root = PROJECT_ROOT / "concierge"
    request_sources = [
        concierge_root / "lyrics_service.py",
        concierge_root / "routers" / "music" / "favorites.py",
        concierge_root / "routers" / "music" / "playlists.py",
    ]

    for path in request_sources:
        source = path.read_text(encoding="utf-8")
        assert "from http_client import http_client" in source
        assert "httpx.AsyncClient" not in source

    main_source = (concierge_root / "main.py").read_text(encoding="utf-8")
    assert "lastfm_service.lastfm_service.client.aclose()" in main_source
    assert "musicbrainz_service.musicbrainz_service.client.aclose()" in main_source
    assert "spotify_service.spotify_service.client.aclose()" in main_source
