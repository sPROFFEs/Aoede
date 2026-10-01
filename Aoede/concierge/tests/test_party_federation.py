"""Remote party routing, identity isolation, and host permissions."""

import asyncio
import importlib.util
import json
import sys
import types
from pathlib import Path
from types import SimpleNamespace

import database
import httpx
import party_service
from fastapi import FastAPI, HTTPException, Response
from test_party_lifecycle import _hub_for, _load_party_router_module, _room_fixture


def _federation_routes(party):
    package = types.ModuleType("party_federation_testpkg")
    package.__path__ = []
    music = types.ModuleType("party_federation_testpkg.music")
    music.__path__ = []
    music.party = party
    core = types.ModuleType("party_federation_testpkg.music.core")
    core.get_db = lambda: None
    for module in (package, music, core):
        sys.modules[module.__name__] = module
    path = Path(__file__).resolve().parents[1] / "routers/federation.py"
    spec = importlib.util.spec_from_file_location("party_federation_testpkg.federation", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_remote_join_queue_and_permissions(db_session, monkeypatch):
    owner, _, tracks, room = _room_fixture(db_session, max_users=2)
    room.is_federated = True
    db_session.commit()
    routes = _load_party_router_module()
    publisher = _federation_routes(routes)
    hub = _hub_for(db_session, reservation_grace=30)
    monkeypatch.setattr(party_service, "hub", hub)
    peer = SimpleNamespace(id=7, name="Remote", peer_url="https://remote.example")

    def verify(_db, _request, token):
        if token != "Bearer test-party-token":
            raise HTTPException(401)
        return peer

    monkeypatch.setattr(publisher, "_verify_federation_token", verify)

    async def get_db():
        return db_session

    host = FastAPI()
    host.include_router(publisher.router)
    host.dependency_overrides[publisher.get_db] = get_db
    original_client = httpx.AsyncClient

    def upstream_client(**kwargs):
        return original_client(transport=httpx.ASGITransport(app=host), **kwargs)

    monkeypatch.setattr(routes.httpx, "AsyncClient", upstream_client)
    instance = database.FederatedInstance(name="Host", base_url="https://host.example", enabled=True)
    instance.api_token = "test-party-token"
    db_session.add(instance)
    db_session.commit()
    # Same numeric ID as the host's owner must still be a guest.
    routes.get_current_user_safe = lambda _db, _request: owner
    streaming = types.ModuleType("party_federation_testpkg.music.streaming")

    def stream_track(track_id, request, _db):
        assert track_id == tracks[0].id
        assert request.headers["range"] == "bytes=0-1"
        return Response(b"ab", status_code=206, headers={"Content-Range": "bytes 0-1/4"}, media_type="audio/mpeg")

    streaming.stream_track_authorized = stream_track
    monkeypatch.setitem(sys.modules, streaming.__name__, streaming)
    remote = FastAPI()
    remote.include_router(routes.router)
    remote.dependency_overrides[routes.get_db] = get_db
    base = f"/api/party/remote/{instance.id}/rooms/{room.id}"

    async def scenario():
        async with original_client(
            transport=httpx.ASGITransport(app=remote), base_url="https://remote.example"
        ) as client:
            joined = await client.post(f"{base}/join", json={})
            assert joined.status_code == 200, joined.text
            assert joined.json()["room"]["is_owner"] is False
            assert hub.is_member(room.id, f"peer:7:{owner.id}")
            assert not hub.is_member(room.id, owner.id)
            streamed = await client.get(f"{base}/stream/{room.queue_items[0].id}", headers={"Range": "bytes=0-1"})
            assert streamed.status_code == 206
            assert streamed.content == b"ab"
            assert streamed.headers["content-range"] == "bytes 0-1/4"
            added = await client.post(f"{base}/queue", json={"track_id": tracks[0].id})
            assert added.status_code == 201, added.text
            assert room.queue_items[-1].added_by_user_id is None
            assert (await client.post(f"{base}/control", json={"action": "play"})).status_code == 403
            assert (await client.delete(f"{base}/queue/{room.queue_items[0].id}")).status_code == 403
            assert (await client.post(f"{base}/control", json={"position_ms": -1})).status_code == 422
            assert (await client.get(f"{base}/tracks")).status_code == 200
            assert (await client.get(f"{base}/../../health")).status_code == 404
            peer.id = 8
            assert (await client.post(f"{base}/join", json={})).status_code == 200
            peer.id = 9
            assert (await client.post(f"{base}/join", json={})).status_code == 409
            room.allow_guests_queue = False
            peer.id = 7
            assert (await client.post(f"{base}/queue", json={"track_id": tracks[0].id})).status_code == 403
            routes.get_current_user_safe = lambda _db, _request: None
            assert (await client.post(f"{base}/join", json={})).status_code == 401

    asyncio.run(scenario())


def test_party_search_uses_federated_catalog_columns(db_session):
    _, _, _, room = _room_fixture(db_session)
    room.is_federated = True
    instance = database.FederatedInstance(name="Catalog", base_url="https://catalog.example", enabled=True)
    db_session.add(instance)
    db_session.flush()
    db_session.add(
        database.FederatedTrack(
            instance_id=instance.id,
            remote_id=42,
            title="Remote Song",
            artist="Remote Artist",
            cover_url="https://catalog.example/cover/42",
        )
    )
    db_session.commit()
    found = party_service.search_tracks(db_session, "Remote Song", room=room)
    assert found[0]["fed_remote_id"] == 42
    assert found[0]["thumbnail"] == "https://catalog.example/cover/42"


def test_peer_visibility_and_event_membership(db_session, monkeypatch):
    owner, _, _, room = _room_fixture(db_session)
    routes = _load_party_router_module()
    publisher = _federation_routes(routes)
    hub = _hub_for(db_session)
    monkeypatch.setattr(party_service, "hub", hub)
    instance = database.FederatedInstance(name="Invited", base_url="https://invited.example", enabled=True)
    db_session.add(instance)
    db_session.flush()
    room.federated_peers.append(database.PartyRoomFederatedPeer(instance_id=instance.id, instance=instance))
    peer = SimpleNamespace(id=7, name="Invited", peer_url="https://invited.example/")
    assert not party_service.visible_to_peer(room, peer)
    room.is_federated = True
    db_session.commit()
    assert party_service.visible_to_peer(room, peer)
    assert party_service.visible_to_peer(room, SimpleNamespace(peer_url=None))
    assert not party_service.visible_to_peer(room, SimpleNamespace(peer_url="https://other.example"))
    monkeypatch.setattr(publisher, "_verify_federation_token", lambda *_: peer)
    from starlette.requests import Request

    request = Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/events",
            "headers": [(b"x-party-user-id", str(owner.id).encode()), (b"x-party-username", b"4775657374")],
        }
    )

    async def scenario():
        response = await publisher.federation_party(room.id, request, "events", db_session, "test")
        actor = f"peer:{peer.id}:{owner.id}"
        assert hub.is_connected(room.id, actor)
        event = await anext(response.body_iterator)
        payload = json.loads(event.split("data: ", 1)[1])
        assert payload["room"]["is_owner"] is False
        assert payload["room"]["participants"] == [{"id": actor, "username": "Guest @ Invited"}]
        await response.body_iterator.aclose()
        assert not hub.is_connected(room.id, actor)

    asyncio.run(scenario())
