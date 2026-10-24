"""Real publisher/consumer routes against isolated nodes, without external services."""

import asyncio
from unittest.mock import MagicMock, patch

import auth
import database
import httpx
import pytest
from fastapi import FastAPI, Request, Response
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

with patch("docker.from_env", return_value=MagicMock()):
    from routers import federation
    from routers import user as user_routes
    from routers.music import playlists, streaming


@pytest.fixture
def nodes(db_session, monkeypatch):
    import admin_statistics_service

    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    database.Base.metadata.create_all(engine)
    guest = sessionmaker(bind=engine)()
    host = db_session
    apps = {}
    for name, db, other in (("host", host, "guest"), ("guest", guest, "host")):
        # Equal usernames and numeric IDs must never confer ownership on the other node.
        db.add_all(
            [
                database.User(username="listener", hashed_password="unused"),
                database.User(username="stranger", hashed_password="unused"),
            ]
        )
        db.flush()
        db.add(database.Track(id=1, title=f"{name} song", artist="Artist", filepath=f"/pool/{name}.mp3", duration=90))
        instance = database.FederatedInstance(
            name=other, base_url=f"https://{other}.example", enabled=True, status="healthy"
        )
        instance.api_token = f"collection-test-{other}"
        db.add(instance)
        token = f"collection-test-{name}"
        db.add(
            database.FederationOutboundPeer(
                name=other,
                peer_url=f"https://{other}.example",
                token_prefix=token[:12],
                token_hash=auth.get_password_hash(token),
                revoked=False,
            )
        )
        db.commit()
        app = FastAPI()
        for router in (federation.router, playlists.router, user_routes.router):
            app.include_router(router)

        app.state.db = db

        async def dependency(request: Request):
            return request.app.state.db

        app.dependency_overrides[federation.get_db] = dependency
        app.dependency_overrides[user_routes.get_db] = dependency
        apps[f"{name}.example"] = app

    def current_user(request, db):
        user_id = request.headers.get("x-test-user")
        return db.get(database.User, int(user_id)) if user_id else None

    monkeypatch.setattr(auth, "get_current_user", current_user)
    monkeypatch.setattr(federation, "get_current_user", current_user)
    monkeypatch.setattr(playlists, "sync_edited_playlist", lambda *a: None)
    monkeypatch.setattr(playlists, "generate_m3u_for_playlist", lambda *a: None)
    monkeypatch.setattr(playlists, "schedule_playlist_sync", lambda *a, **kw: None)
    monkeypatch.setattr(playlists, "schedule_navidrome_sync", lambda *a, **kw: None)
    monkeypatch.setattr(admin_statistics_service, "_read_user_activity", lambda *a: ([], None))

    def stream(track_id, request, db):
        track = db.get(database.Track, track_id)
        assert track is not None
        body = track.title.encode()[:2]
        if request.headers.get("range"):
            return Response(body, status_code=206, headers={"Content-Range": "bytes 0-1/9"}, media_type="audio/mpeg")
        return Response(body, media_type="audio/mpeg")

    async def cover(*a):
        return Response(b"image", media_type="image/png")

    monkeypatch.setattr(streaming, "stream_track_authorized", stream)
    monkeypatch.setattr(streaming, "get_cover", cover)
    original_client = httpx.AsyncClient
    requests = []

    async def dispatch(request):
        requests.append(request)
        transport = httpx.ASGITransport(app=apps[request.url.host])
        return await transport.handle_async_request(request)

    def upstream(**kwargs):
        kwargs.pop("transport", None)
        return original_client(transport=httpx.MockTransport(dispatch), **kwargs)

    monkeypatch.setattr(httpx, "AsyncClient", upstream)
    try:
        yield host, guest, original_client, apps, requests
    finally:
        guest.close()
        engine.dispose()


def test_cross_node_collaboration_and_audio(nodes):
    host, guest, client_type, apps, requests = nodes
    album = database.Playlist(name="Together", kind="album", owner_id=1, is_collaborative=True)
    public = database.Playlist(name="Public album", kind="album", owner_id=1, is_public=True)
    host.add_all([album, public])
    host.flush()
    host.add(database.PlaylistItem(playlist_id=public.id, track_id=1, position=1))
    host.add(database.UserFavorite(user_id=1, track_id=1))
    host.commit()
    base = f"/api/federation/proxy/1/collections/{album.id}"

    async def scenario():
        async with (
            client_type(
                transport=httpx.ASGITransport(app=apps["host.example"]),
                base_url="https://host.example",
                headers={"X-Test-User": "1"},
            ) as owner,
            client_type(
                transport=httpx.ASGITransport(app=apps["guest.example"]),
                base_url="https://guest.example",
                headers={"X-Test-User": "1"},
            ) as editor,
        ):
            assert (await editor.get(base)).status_code == 404
            users = (await owner.get("/user/list")).json()
            assert [u["username"] for u in users].count("listener") == 2
            remote = next(u for u in users if u.get("is_federated") and u["id"] == 1)
            assert remote["profile_key"] == "fed:1:1"
            invited = await owner.post(
                f"/api/playlists/{album.id}/collaborators",
                json={"username": "listener", "profile_key": remote["profile_key"]},
            )
            assert invited.status_code == 200, invited.text
            await owner.post(
                f"/api/playlists/{album.id}/collaborators",
                json={"username": "listener", "profile_key": remote["profile_key"]},
            )
            assert host.query(database.FederatedPlaylistCollaborator).count() == 1
            detail = (await editor.get(base)).json()
            assert detail["is_editable"] and not detail["is_owner"] and detail["kind"] == "album"
            shared = (await editor.get("/api/playlists")).json()
            assert any(pl["id"] == f"fed_1_{album.id}" for pl in shared)
            member_list = (await owner.get(f"/api/playlists/{album.id}/collaborators")).json()
            assert member_list["members"][0]["profile_key"] == remote["profile_key"]
            # Guest's local song must be fetched from its catalogue, not host's track with the same ID.
            added = await editor.post(base + "/add", json={"track_id": 1})
            assert added.status_code == 200, added.text
            assert added.json()["playlist_item_id"] > 0
            await owner.post(f"/api/playlists/{album.id}/add", json={"track_id": 1})
            local_detail = await owner.get(f"/api/playlists/{album.id}")
            assert local_detail.status_code == 200, local_detail.text
            response = await editor.get(base)
            assert response.status_code == 200, response.text
            detail = response.json()
            assert [t["title"] for t in detail["tracks"]] == ["guest song", "host song"]
            tracks = detail["tracks"]
            for track, body in zip(tracks, (b"gu", b"ho")):
                streamed = await editor.get(track["stream_url"], headers={"Range": "bytes=0-1"})
                assert streamed.status_code == 206, streamed.text
                assert streamed.content == body and streamed.headers["content-range"] == "bytes 0-1/9"
                assert (await editor.get(track["thumbnail"])).content == b"image"
            items = [
                {"track_id": t["id"], "item_id": t["playlist_item_id"], "position": i + 1}
                for i, t in enumerate(reversed(tracks))
            ]
            reordered = await editor.patch(base + "/reorder", json={"items": items, "revision": detail["revision"]})
            assert reordered.status_code == 200, reordered.text
            assert (
                await editor.patch(base + "/reorder", json={"items": items, "revision": detail["revision"]})
            ).status_code == 409
            target = tracks[0]
            removed = await editor.delete(base + f"/remove/{target['id']}?item_id={target['playlist_item_id']}")
            assert removed.status_code == 200, removed.text
            assert [track["title"] for track in (await editor.get(base)).json()["tracks"]] == ["host song"]
            assert (await editor.delete(base)).status_code == 405
            assert (await editor.post(base + "/public", json={"is_public": True})).status_code == 403
            editor.headers["X-Test-User"] = "2"
            assert (await editor.get(base)).status_code == 404
            editor.headers["X-Test-User"] = "1"
            member = (await editor.get(base + "/collaborators")).json()
            assert member["viewer_member_id"] == "peer:1:1"
            assert (await editor.delete(base + "/collaborators/peer:1:1")).status_code == 200
            assert (await editor.get(base)).status_code == 404
            assert (await editor.get(tracks[1]["stream_url"])).status_code == 404
            profile = (await editor.get("/api/federation/proxy/1/users/1")).json()
            assert not profile["is_self"]
            assert [pl["name"] for pl in profile["public_playlists"]] == ["Public album"]
            assert profile["favorites"][0]["is_local"] is False
            # The peer bearer stays between nodes; never present in browser-visible metadata.
            assert "collection-test" not in str(profile)
            assert all("x-test-user" not in request.headers for request in requests)
            assert (await editor.get("/api/federation/users")).status_code == 401
            assert (await editor.get("/api/federation/proxy/1/users/1", headers={"X-Test-User": ""})).status_code == 401

    asyncio.run(scenario())


def test_public_copy_revocation_and_missing_reciprocal_peer(nodes):
    host, guest, client_type, apps, _ = nodes
    album = database.Playlist(name="Album", kind="album", owner_id=1, is_public=True)
    host.add(album)
    host.flush()
    host.add_all([database.PlaylistItem(playlist_id=album.id, track_id=1, position=i) for i in (1, 2)])
    host.commit()
    base = f"/api/federation/proxy/1/collections/{album.id}"

    async def scenario():
        async with client_type(
            transport=httpx.ASGITransport(app=apps["guest.example"]),
            base_url="https://guest.example",
            headers={"X-Test-User": "1"},
        ) as client:
            listed = (await client.get("/api/public/playlists")).json()
            assert listed[0]["id"] == f"fed_1_{album.id}" and not listed[0]["is_editable"]
            copied = await client.post(base + "/copy")
            assert copied.status_code == 200, copied.text
            local_id = copied.json()["id"]
            assert copied.json()["kind"] == "album" and copied.json()["track_count"] == 2
            synced = await client.post(base + "/copy")
            assert synced.json()["id"] == local_id and synced.json()["status"] == "synced"
            detail = (await client.get(f"/api/playlists/{local_id}")).json()
            assert not detail["is_editable"] and len(detail["tracks"]) == 2
            assert (await client.get(detail["tracks"][0]["stream_url"])).content == b"ho"
            assert (await client.post(f"/api/playlists/{local_id}/public", json={"is_public": True})).status_code == 403
            album.is_public = False
            host.commit()
            assert (await client.get(detail["tracks"][0]["stream_url"])).status_code == 404
            assert (await client.post(base + "/copy")).status_code == 404
            instance = guest.get(database.FederatedInstance, 1)
            instance.enabled = False
            guest.commit()
            assert (await client.get("/api/public/playlists")).json() == []
            assert (await client.get(base)).status_code == 404
            instance.enabled = True
            guest.get(database.FederationOutboundPeer, 1).peer_url = None
            guest.commit()
            invitation = await client.post(
                f"/api/playlists/{local_id}/collaborators", json={"username": "listener", "profile_key": "fed:1:1"}
            )
            assert invitation.status_code == 403  # Copies remain read-only.
            local = database.Playlist(name="Local", owner_id=1)
            guest.add(local)
            guest.commit()
            invitation = await client.post(
                f"/api/playlists/{local.id}/collaborators", json={"username": "listener", "profile_key": "fed:1:1"}
            )
            assert invitation.status_code == 409, invitation.text

    asyncio.run(scenario())


def test_collection_songs_resolve_for_party_seeding_and_remote_queue(nodes, monkeypatch):
    import party_service
    from routers.music import party

    host, guest, client_type, apps, _ = nodes
    monkeypatch.setattr(
        party_service,
        "hub",
        party_service.PartyHub(db_factory=sessionmaker(bind=host.get_bind()), reservation_seconds=60),
    )
    for app in apps.values():
        app.include_router(party.router)
    album = database.Playlist(name="Remote music", owner_id=1, kind="album", is_public=True)
    guest.add(album)
    guest.flush()
    guest.add(database.PlaylistItem(playlist_id=album.id, track_id=1, position=1))
    guest.commit()

    async def scenario():
        async with (
            client_type(
                transport=httpx.ASGITransport(app=apps["host.example"]),
                base_url="https://host.example",
                headers={"X-Test-User": "1"},
            ) as owner,
            client_type(
                transport=httpx.ASGITransport(app=apps["guest.example"]),
                base_url="https://guest.example",
                headers={"X-Test-User": "1"},
            ) as listener,
        ):
            source = (await owner.get(f"/api/federation/proxy/1/collections/{album.id}")).json()["tracks"][0]
            created = await owner.post("/api/playlists", json={"name": "Mixed", "track_ids": [1, source["id"]]})
            assert created.status_code == 200, created.text
            local_id = created.json()["id"]
            assert created.json()["track_count"] == 2
            local_room = await owner.post("/api/party/rooms", json={"name": "Local", "playlist_id": local_id})
            assert local_room.status_code == 409
            room_response = await owner.post(
                "/api/party/rooms",
                json={"name": "Federated", "playlist_id": local_id, "is_federated": True, "federated_peer_ids": [1]},
            )
            assert room_response.status_code == 201, room_response.text
            room_id = room_response.json()["room"]["id"]
            host.expire_all()
            room = host.get(database.PartyRoom, room_id)
            assert room.queue_items[0].track_id == 1
            assert room.queue_items[1].track_id is None and room.queue_items[1].remote_title == "guest song"
            assert (await owner.post(f"/api/party/rooms/{room_id}/join", json={})).status_code == 200
            added = await owner.post(f"/api/party/rooms/{room_id}/queue", json={"track_reference": source["id"]})
            assert added.status_code == 201, added.text
            host.expire_all()
            assert host.get(database.PartyRoom, room_id).queue_items[-1].fed_remote_id == 1
            # A collection song coming back to its original node resolves to its local pool ID.
            await owner.post(f"/api/playlists/{local_id}/public", json={"is_public": True})
            source_back = (await listener.get(f"/api/federation/proxy/1/collections/{local_id}")).json()["tracks"][1]
            remote_base = f"/api/party/remote/1/rooms/{room_id}"
            assert (await listener.post(remote_base + "/join", json={})).status_code == 200
            added = await listener.post(remote_base + "/queue", json={"track_reference": source_back["id"]})
            assert added.status_code == 201, added.text
            host.expire_all()
            queued = host.get(database.PartyRoom, room_id).queue_items[-1]
            assert queued.fed_remote_id == 1 and queued.remote_title == "guest song"
            assert (
                await listener.get(remote_base + f"/stream/{queued.id}", headers={"Range": "bytes=0-1"})
            ).content == b"gu"
            await owner.delete(f"/api/party/rooms/{room_id}")

    asyncio.run(scenario())


def test_legacy_peers_invalid_inputs_and_token_revocation(nodes):
    host, guest, client_type, apps, _ = nodes
    public = database.Playlist(name="Public", owner_id=1, is_public=True)
    host.add(public)
    host.flush()
    host.add(database.PlaylistItem(playlist_id=public.id, track_id=1, position=1))
    host.commit()

    async def scenario():
        async with client_type(
            transport=httpx.ASGITransport(app=apps["guest.example"]),
            base_url="https://guest.example",
            headers={"X-Test-User": "1"},
        ) as client:
            base = f"/api/federation/proxy/1/collections/{public.id}"
            assert (await client.delete(base + "/remove/1?item_id=invalid")).status_code == 422
            assert (await client.delete(base + "/remove/1?item_id=999999")).status_code == 404
            assert (await client.post(base + "/add", json=["bad"])).status_code == 422
            assert (
                await client.get(base, headers={"X-Federation-Depth": "8"})
            ).status_code == 200  # Browser headers do not choose the upstream identity or depth.
            loop = await client.get(
                f"/api/federation/collections/{public.id}",
                headers={"Authorization": "Bearer collection-test-guest", "X-Federation-Depth": "8"},
            )
            assert loop.status_code == 508
            host.get(database.FederationOutboundPeer, 1).revoked = True
            host.commit()
            assert (await client.get(base)).status_code == 403
            assert (await client.get("/api/public/playlists")).json() == []
            apps["host.example"] = FastAPI()  # A reachable server without the new endpoints.
            assert (await client.get("/user/list")).status_code == 200
            assert (await client.get("/api/public/playlists")).json() == []
            assert (await client.get(base)).status_code == 404

    asyncio.run(scenario())
