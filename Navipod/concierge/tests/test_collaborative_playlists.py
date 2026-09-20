import asyncio
import json
from unittest.mock import MagicMock, patch

import database
import ops_core
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError
from starlette.requests import Request

with patch("docker.from_env", return_value=MagicMock()):
    from routers.music import playlists, sync


def run(handler, *args, **kwargs):
    return asyncio.run(handler(*args, **kwargs))


def payload(response):
    return json.loads(response.body)


@pytest.fixture
def collaboration(db_session, monkeypatch):
    users = [database.User(username=name, hashed_password="hash") for name in ("owner", "editor", "stranger")]
    db_session.add_all(users)
    db_session.flush()
    tracks = [database.Track(title=f"Song {i}", filepath=f"/pool/{i}.mp3") for i in range(3)]
    db_session.add_all(tracks)
    playlist = database.Playlist(name="Together", owner_id=users[0].id, is_collaborative=True)
    db_session.add(playlist)
    db_session.commit()
    acting = [users[0]]
    monkeypatch.setattr(playlists, "get_current_user_safe", lambda *_: acting[0])
    monkeypatch.setattr(sync, "get_current_user_safe", lambda *_: acting[0])
    exports = []
    monkeypatch.setattr(playlists, "generate_m3u_for_playlist", lambda db, pl, username: exports.append(username))
    monkeypatch.setattr(playlists, "schedule_playlist_sync", lambda *a, **kw: None)
    monkeypatch.setattr(playlists, "schedule_navidrome_sync", lambda *a, **kw: None)

    async def clean(*args):
        return True

    monkeypatch.setattr(playlists, "clean_remote_playlist", clean)
    return users, tracks, playlist, acting, exports, Request({"type": "http"})


def invite(db, context):
    users, _, playlist, acting, _, request = context
    acting[0] = users[0]
    response = run(
        playlists.add_collaborator, playlist.id, playlists.CollaboratorRequest(username="editor"), request, db
    )
    assert response.status_code == 200


def test_member_access_and_owner_only_controls(db_session, collaboration):
    users, tracks, playlist, acting, exports, request = collaboration
    acting[0] = users[1]
    assert run(playlists.get_playlist, playlist.id, request, db_session).status_code == 404
    invite(db_session, collaboration)
    invite(db_session, collaboration)  # Idempotent invitation.
    assert db_session.query(database.PlaylistCollaborator).count() == 1
    acting[0] = users[1]
    detail = payload(run(playlists.get_playlist, playlist.id, request, db_session))
    assert detail["is_editable"] and not detail["is_owner"]
    listed = payload(run(playlists.list_playlists, request, db_session))
    assert [pl["id"] for pl in listed] == [playlist.id]
    assert listed[0]["is_collaborative"] and listed[0]["is_editable"]
    assert playlists.fetch_playlist_summaries(db_session, viewer_id=users[1].id, owner_id=users[1].id) == []
    assert (
        run(
            playlists.add_to_playlist,
            playlist.id,
            playlists.AddToPlaylistRequest(track_id=tracks[0].id),
            request,
            db_session,
        ).status_code
        == 200
    )
    assert exports == ["owner"]
    assert (
        run(
            playlists.update_playlist, playlist.id, playlists.PlaylistUpdateRequest(name="Stolen"), request, db_session
        ).status_code
        == 404
    )
    assert (
        run(
            playlists.set_playlist_public,
            playlist.id,
            playlists.PublishPlaylistRequest(is_public=True),
            request,
            db_session,
        ).status_code
        == 404
    )
    assert run(playlists.delete_playlist, playlist.id, request, db_session).status_code == 404
    assert (
        run(
            playlists.add_collaborator,
            playlist.id,
            playlists.CollaboratorRequest(username="stranger"),
            request,
            db_session,
        ).status_code
        == 404
    )
    assert run(playlists.reset_playlist_cover, playlist.id, request, db_session).status_code == 404
    assert run(playlists.remove_from_playlist, playlist.id, tracks[0].id, request, db_session).status_code == 200


def test_revocation_public_viewers_and_self_leave(db_session, collaboration):
    users, tracks, playlist, acting, _, request = collaboration
    invite(db_session, collaboration)
    acting[0] = users[2]
    assert (
        run(
            playlists.add_to_playlist,
            playlist.id,
            playlists.AddToPlaylistRequest(track_id=tracks[0].id),
            request,
            db_session,
        ).status_code
        == 404
    )
    playlist.is_public = True
    db_session.commit()
    assert run(playlists.get_playlist, playlist.id, request, db_session).status_code == 200
    assert (
        run(
            playlists.add_to_playlist,
            playlist.id,
            playlists.AddToPlaylistRequest(track_id=tracks[0].id),
            request,
            db_session,
        ).status_code
        == 403
    )
    assert run(playlists.list_collaborators, playlist.id, request, db_session).status_code == 404
    acting[0] = users[0]
    assert run(playlists.remove_collaborator, playlist.id, users[1].id, request, db_session).status_code == 200
    acting[0] = users[1]
    assert payload(run(playlists.list_playlists, request, db_session)) == []
    assert not payload(run(playlists.get_playlist, playlist.id, request, db_session))["is_editable"]
    invite(db_session, collaboration)
    acting[0] = users[1]
    assert run(playlists.remove_collaborator, playlist.id, users[1].id, request, db_session).status_code == 200
    acting[0] = None
    assert run(playlists.list_collaborators, playlist.id, request, db_session).status_code == 401


def test_reorder_detects_stale_edits_and_changes_heartbeat(db_session, collaboration):
    users, tracks, playlist, acting, _, request = collaboration
    invite(db_session, collaboration)
    acting[0] = users[1]
    for track in tracks:
        run(
            playlists.add_to_playlist,
            playlist.id,
            playlists.AddToPlaylistRequest(track_id=track.id),
            request,
            db_session,
        )
    before = payload(run(sync.get_sync_state, request, db_session))["version"]
    revision = playlist.revision
    body = playlists.ReorderRequest(
        items=[{"track_id": t.id, "position": i + 1} for i, t in enumerate(reversed(tracks))], revision=revision
    )
    assert run(playlists.reorder_playlist_tracks, playlist.id, body, request, db_session).status_code == 200
    assert [i.track_id for i in sorted(playlist.items, key=lambda i: i.position)] == [t.id for t in reversed(tracks)]
    assert payload(run(sync.get_sync_state, request, db_session))["version"] != before
    assert run(playlists.reorder_playlist_tracks, playlist.id, body, request, db_session).status_code == 409
    malformed = playlists.ReorderRequest(items=[{"track_id": tracks[0].id, "position": 1}], revision=playlist.revision)
    assert run(playlists.reorder_playlist_tracks, playlist.id, malformed, request, db_session).status_code == 400


def test_creation_validation_and_cascade(db_session, collaboration):
    users, _, playlist, acting, _, request = collaboration
    response = run(
        playlists.create_playlist,
        playlists.CreatePlaylistRequest(name="Shared", is_collaborative=True),
        request,
        db_session,
    )
    assert payload(response)["is_collaborative"]
    playlist.smart_rules_json = "{}"
    db_session.commit()
    assert (
        run(
            playlists.add_collaborator,
            playlist.id,
            playlists.CollaboratorRequest(username="editor"),
            request,
            db_session,
        ).status_code
        == 403
    )
    playlist.smart_rules_json = None
    playlist.source_playlist_id = 999
    db_session.commit()
    assert (
        run(
            playlists.add_collaborator,
            playlist.id,
            playlists.CollaboratorRequest(username="editor"),
            request,
            db_session,
        ).status_code
        == 403
    )
    playlist.source_playlist_id = None
    db_session.commit()
    assert (
        run(
            playlists.add_collaborator,
            playlist.id,
            playlists.CollaboratorRequest(username="missing"),
            request,
            db_session,
        ).status_code
        == 404
    )
    assert (
        run(
            playlists.add_collaborator,
            playlist.id,
            playlists.CollaboratorRequest(username="owner"),
            request,
            db_session,
        ).status_code
        == 400
    )
    invite(db_session, collaboration)
    assert run(playlists.delete_playlist, playlist.id, request, db_session).status_code == 200
    assert db_session.query(database.PlaylistCollaborator).count() == 0


def test_collaboration_migration_preserves_existing_playlists_and_cascades():
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("PRAGMA foreign_keys=ON"))
        conn.execute(text("CREATE TABLE users (id INTEGER PRIMARY KEY)"))
        conn.execute(text("CREATE TABLE playlists (id INTEGER PRIMARY KEY, name TEXT)"))
        conn.execute(text("INSERT INTO users VALUES (1), (2)"))
        conn.execute(text("INSERT INTO playlists VALUES (1, 'Existing')"))
        ops_core._migration_030_collaborative_playlists(conn)
        ops_core._migration_030_collaborative_playlists(conn)
        assert conn.execute(text("SELECT name, is_collaborative, revision FROM playlists")).one() == ("Existing", 0, 0)
        conn.execute(text("INSERT INTO playlist_collaborators VALUES (1, 2)"))
        with pytest.raises(IntegrityError):
            conn.execute(text("INSERT INTO playlist_collaborators VALUES (1, 2)"))
        conn.execute(text("DELETE FROM users WHERE id=2"))
        assert conn.execute(text("SELECT count(*) FROM playlist_collaborators")).scalar() == 0
        conn.execute(text("INSERT INTO playlist_collaborators VALUES (1, 1)"))
        conn.execute(text("DELETE FROM playlists WHERE id=1"))
        assert conn.execute(text("SELECT count(*) FROM playlist_collaborators")).scalar() == 0
    engine.dispose()
