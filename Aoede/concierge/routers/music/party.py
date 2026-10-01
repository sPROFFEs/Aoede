"""Authenticated party-room API and server-sent event stream."""

import asyncio
import re

import database
import httpx
import party_service
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from .core import get_current_user_safe, get_db

router = APIRouter(prefix="/api/party", tags=["party"])


class CreateRoomRequest(BaseModel):
    name: str | None = Field(default=None, max_length=party_service.MAX_ROOM_NAME)
    max_users: int = Field(default=5, ge=party_service.MIN_ROOM_USERS, le=party_service.MAX_ROOM_USERS)
    allow_guests_queue: bool = True
    playlist_id: int | None = None
    is_federated: bool = False
    federated_peer_ids: list[int] | None = None


class AddTrackRequest(BaseModel):
    track_id: int | None = None
    fed_instance_id: int | None = None
    fed_remote_id: int | None = None
    remote_title: str | None = None
    remote_artist: str | None = None
    remote_album: str | None = None
    remote_duration: float | None = None
    remote_thumbnail: str | None = None


class ControlRequest(BaseModel):
    action: str
    position_ms: int = Field(default=0, ge=0)
    expected_item_id: int | None = None


def _user_or_401(db: Session, request: Request):
    user = getattr(request.state, "party_peer_user", None) or get_current_user_safe(db, request)
    if not user or user.is_service_account:
        return None, JSONResponse({"error": "Unauthorized"}, status_code=401)
    return user, None


def _error(exc: party_service.PartyError) -> JSONResponse:
    return JSONResponse({"error": str(exc)}, status_code=exc.status_code)


def _room_payload(room: database.PartyRoom, user: database.User, include_queue: bool = True) -> dict:
    payload = party_service.serialize_room(room, party_service.hub.presence(room.id), include_queue=include_queue)
    payload["is_owner"] = room.owner_id == user.id
    payload["can_add_songs"] = room.owner_id == user.id or bool(room.allow_guests_queue)
    return payload


async def relay_party_request(instance, user, room_id: int, path: str, request: Request):
    """Relay only party operations to a configured peer; keep its token server-side."""
    headers = {
        "Authorization": f"Bearer {instance.api_token}",
        "User-Agent": "Aoede-Federation/1.0",
        "X-Party-User-Id": str(user.id),
        "X-Party-Username": user.username.encode("utf-8").hex(),
        "Content-Type": "application/json",
    }
    if request.headers.get("range"):
        headers["Range"] = request.headers["range"]
    client = httpx.AsyncClient(timeout=httpx.Timeout(30, read=None if path == "events" else 30))
    url = f"{instance.base_url.rstrip('/')}/api/federation/parties/{room_id}"
    if path:
        url += f"/{path}"
    try:
        upstream = await client.send(
            client.build_request(
                request.method, url, headers=headers, params=request.query_params, content=await request.body()
            ),
            stream=True,
        )
    except httpx.HTTPError:
        await client.aclose()
        return JSONResponse({"error": "Could not reach the party host"}, status_code=502)

    async def close():
        try:
            await upstream.aclose()
        finally:
            await client.aclose()

    async def body():
        try:
            async for chunk in upstream.aiter_bytes():
                yield chunk
        finally:
            await close()

    from starlette.background import BackgroundTask

    response_headers = {
        k: v for k, v in upstream.headers.items() if k in {"content-type", "content-range", "accept-ranges"}
    }
    if path == "events":
        response_headers.update({"Cache-Control": "no-cache, no-transform", "X-Accel-Buffering": "no"})
    return StreamingResponse(
        body(), status_code=upstream.status_code, headers=response_headers, background=BackgroundTask(close)
    )


@router.api_route("/remote/{instance_id}/rooms/{room_id}", methods=["GET"])
@router.api_route("/remote/{instance_id}/rooms/{room_id}/{path:path}", methods=["GET", "POST", "DELETE"])
async def remote_room(instance_id: int, room_id: int, request: Request, path: str = "", db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    allowed = {
        "GET": r"(?:|events|tracks|stream/[1-9][0-9]*)",
        "POST": r"(?:join|queue|control)",
        "DELETE": r"queue/[1-9][0-9]*",
    }
    if not re.fullmatch(allowed.get(request.method, r"(?!)"), path):
        return JSONResponse({"error": "Party operation not found"}, status_code=404)
    instance = db.query(database.FederatedInstance).filter_by(id=instance_id, enabled=True).first()
    if not instance:
        return JSONResponse({"error": "Instance not found"}, status_code=404)
    return await relay_party_request(instance, user, room_id, path, request)


@router.get("/peers")
async def list_available_peers(request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    peers = (
        db.query(database.FederatedInstance)
        .filter(database.FederatedInstance.enabled.is_(True))
        .order_by(database.FederatedInstance.name)
        .all()
    )
    return JSONResponse(
        {
            "peers": [
                {
                    "id": p.id,
                    "name": p.name,
                    "base_url": p.base_url,
                    "status": p.status,
                }
                for p in peers
            ]
        }
    )


@router.get("/rooms")
async def list_rooms(request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    local_rooms = db.query(database.PartyRoom).order_by(database.PartyRoom.created_at.desc()).all()
    out = [_room_payload(room, user, include_queue=False) for room in local_rooms]

    # Query active federated rooms from enabled peers
    peers = db.query(database.FederatedInstance).filter(database.FederatedInstance.enabled.is_(True)).all()
    for p in peers:
        try:
            headers = {"User-Agent": "Aoede-Federation/1.0"}
            if p.api_token:
                headers["Authorization"] = f"Bearer {p.api_token}"
            async with httpx.AsyncClient(timeout=3.0) as client:
                res = await client.get(f"{p.base_url.rstrip('/')}/api/federation/parties", headers=headers)
            if res.status_code == 200:
                data = res.json()
                for r in data.get("rooms", []):
                    r["id"] = int(r["id"])
                    if r["id"] <= 0:
                        continue
                    r["is_remote_federated"] = True
                    r["remote_instance_id"] = p.id
                    r["remote_instance_name"] = p.name
                    r["remote_base_url"] = p.base_url
                    r["is_owner"] = False
                    r["can_add_songs"] = bool(r.get("allow_guests_queue"))
                    out.append(r)
        except Exception:
            continue

    return JSONResponse({"rooms": out})


@router.post("/rooms")
async def create_room(payload: CreateRoomRequest, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.create_room(
            db,
            user,
            payload.name,
            payload.max_users,
            payload.allow_guests_queue,
            payload.playlist_id,
            is_federated=payload.is_federated,
            federated_peer_ids=payload.federated_peer_ids,
        )
        await party_service.hub.open_room(room.id)
        return JSONResponse({"room": _room_payload(room, user)}, status_code=201)
    except party_service.PartyError as exc:
        return _error(exc)


@router.get("/rooms/{room_id}")
async def room_detail(room_id: int, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        party_service.normalize_playback(room)
        db.commit()
        return JSONResponse({"room": _room_payload(room, user)})
    except party_service.PartyError as exc:
        return _error(exc)


@router.post("/rooms/{room_id}/join")
async def check_join(room_id: int, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        await party_service.hub.reserve(room, user)
        return JSONResponse({"room": _room_payload(room, user)})
    except party_service.PartyError as exc:
        return _error(exc)


@router.delete("/rooms/{room_id}")
async def delete_room(room_id: int, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        if room.owner_id != user.id:
            raise party_service.PartyError("Only the room owner can delete it", 403)
        db.delete(room)
        db.commit()
        await party_service.hub.close_room(room_id)
        return JSONResponse({"status": "deleted"})
    except party_service.PartyError as exc:
        return _error(exc)


@router.get("/rooms/{room_id}/tracks")
async def room_track_search(room_id: int, request: Request, q: str = "", db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        party_service.require_membership(room, user)
        if user.id != room.owner_id and not room.allow_guests_queue:
            raise party_service.PartyError("Only the room owner can add songs", 403)
        return JSONResponse({"tracks": party_service.search_tracks(db, q, room=room)})
    except party_service.PartyError as exc:
        return _error(exc)


@router.post("/rooms/{room_id}/queue")
async def add_queue_track(room_id: int, payload: AddTrackRequest, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        party_service.require_membership(room, user)
        party_service.add_track(
            db,
            room,
            user,
            payload.track_id,
            fed_instance_id=payload.fed_instance_id,
            fed_remote_id=payload.fed_remote_id,
            remote_title=payload.remote_title,
            remote_artist=payload.remote_artist,
            remote_album=payload.remote_album,
            remote_duration=payload.remote_duration,
            remote_thumbnail=payload.remote_thumbnail,
        )
        await party_service.hub.broadcast(room_id)
        return JSONResponse({"status": "added"}, status_code=201)
    except party_service.PartyError as exc:
        return _error(exc)


@router.delete("/rooms/{room_id}/queue/{item_id}")
async def remove_queue_track(room_id: int, item_id: int, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        party_service.require_membership(room, user)
        party_service.remove_queue_item(db, room, user, item_id)
        await party_service.hub.broadcast(room_id)
        return JSONResponse({"status": "removed"})
    except party_service.PartyError as exc:
        return _error(exc)


@router.post("/rooms/{room_id}/control")
async def control_playback(room_id: int, payload: ControlRequest, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        party_service.require_membership(room, user)
        party_service.control_room(
            db,
            room,
            user,
            payload.action,
            payload.position_ms,
            payload.expected_item_id,
            allow_guest_ended=not party_service.hub.is_member(room.id, room.owner_id),
            allow_guest_ready=not party_service.hub.is_member(room.id, room.owner_id),
        )
        await party_service.hub.broadcast(room_id)
        return JSONResponse({"status": "ok"})
    except party_service.PartyError as exc:
        return _error(exc)


@router.get("/rooms/{room_id}/events")
async def room_events(room_id: int, request: Request, db: Session = Depends(get_db)):
    user, response = _user_or_401(db, request)
    if response:
        return response
    try:
        room = party_service.get_room(db, room_id)
        queue = await party_service.hub.subscribe(room, user)
        initial = _room_payload(room, user)
    except party_service.PartyError as exc:
        return _error(exc)

    async def events():
        try:
            yield f'event: state\ndata: {{"type":"state","room":{_json(initial)}}}\n\n'
            await party_service.hub.broadcast(room_id)
            while True:
                if await request.is_disconnected():
                    break
                try:
                    data = await asyncio.wait_for(queue.get(), timeout=15)
                    yield f"event: state\ndata: {data}\n\n"
                except asyncio.TimeoutError:
                    yield ": heartbeat\n\n"
        finally:
            await party_service.hub.unsubscribe(room_id, user.id, queue)

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


def _json(value: dict) -> str:
    import json

    return json.dumps(value, separators=(",", ":"))
