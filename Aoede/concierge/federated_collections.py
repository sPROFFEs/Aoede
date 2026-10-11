"""Public discovery and scoped collection references on configured federation peers."""

import asyncio
import json
import math
import re

import database
import federation_service
from fastapi import HTTPException


def get_instance(db, instance_id):
    instance = db.query(database.FederatedInstance).filter_by(id=instance_id, enabled=True).first()
    if not instance:
        raise HTTPException(404, "Federated source not found")
    if not federation_service.status_is_playable(instance.status):
        raise HTTPException(503, "Federated source is offline", headers={"Retry-After": "60"})
    return instance


def identity_headers(user):
    if not user or not isinstance(user.id, int):
        return {}
    return {"X-Party-User-Id": str(user.id), "X-Party-Username": user.username.encode("utf-8").hex()}


async def fetch_remote(instance, path, user=None, params=None):
    try:
        async with asyncio.timeout(6):
            async with federation_service._client(instance.api_token) as client:
                client.headers.update(identity_headers(user))
                status, data = await federation_service._fetch_json_bounded(
                    client, instance.base_url.rstrip("/") + "/api/federation/" + path, params=params
                )
        if status != 200:
            raise HTTPException(status if status in {401, 403, 404, 409, 503} else 502, "Remote collection unavailable")
        if not isinstance(data, dict):
            raise HTTPException(502, "Federated source returned an invalid response")
        return data
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(502, "Could not reach the federated source")


def user_summary(instance, user):
    remote_id = int(user["id"])
    if remote_id <= 0 or not isinstance(user.get("username"), str):
        raise ValueError("Invalid remote user")
    return {
        "id": remote_id,
        "username": user["username"][:200],
        "profile_key": f"fed:{instance.id}:{remote_id}",
        "avatar_url": f"/api/federation/proxy/{instance.id}/images/avatar/{remote_id}",
        "is_admin": bool(user.get("is_admin")),
        "is_self": False,
        "is_federated": True,
        "fed_instance_id": instance.id,
        "fed_instance_name": instance.name,
        "public_playlists_count": max(0, int(user.get("public_playlists_count") or 0)),
        "favorites_count": max(0, int(user.get("favorites_count") or 0)),
        "total_listens": max(0, int(user.get("total_listens") or 0)),
    }


def collection_summary(instance, collection):
    remote_id = int(collection["id"])
    owner_id = int(collection["owner_id"])
    if remote_id <= 0 or owner_id <= 0 or not isinstance(collection.get("name"), str):
        raise ValueError("Invalid remote collection")
    return {
        "id": f"fed_{instance.id}_{remote_id}",
        "remote_playlist_id": remote_id,
        "name": collection["name"][:120],
        "kind": "album" if collection.get("kind") == "album" else "playlist",
        "track_count": max(0, int(collection.get("track_count") or 0)),
        "thumbnail": f"/api/federation/proxy/{instance.id}/images/collection/{remote_id}",
        "owner_username": str(collection.get("owner_username") or "Unknown")[:200],
        "owner_profile_key": f"fed:{instance.id}:{owner_id}",
        "is_public": bool(collection.get("is_public")),
        "is_collaborative": bool(collection.get("is_collaborative")),
        "is_owner": False,
        "is_editable": bool(collection.get("is_editable")),
        "is_federated": True,
        "fed_instance_id": instance.id,
        "fed_instance_name": instance.name,
    }


def track_metadata(track):
    duration = float(track.get("duration") or 0)
    result = {
        "title": str(track.get("title") or "Unknown title")[:2000],
        "artist": str(track.get("artist") or "Unknown artist")[:2000],
        "album": str(track.get("album") or "")[:2000],
        "duration": max(0, duration) if math.isfinite(duration) else 0,
    }
    if track.get("catalog_remote_id"):
        result["catalog_remote_id"] = int(track["catalog_remote_id"])
        result["catalog_source_url"] = str(track.get("catalog_source_url") or "")[:2048]
    return result


def remote_track(instance, playlist_id, track):
    item_id = int(track["playlist_item_id"])
    if item_id <= 0:
        raise ValueError("Invalid remote song")
    metadata = track_metadata(track)
    if metadata.get("catalog_remote_id") and not metadata.get("catalog_source_url"):
        metadata["catalog_source_url"] = instance.base_url.rstrip("/")
    return {
        **metadata,
        "id": f"fedpl_{instance.id}_{playlist_id}_{item_id}",
        "db_id": None,
        "playlist_item_id": item_id,
        "position": max(1, int(track.get("position") or 1)),
        "fed_instance_id": instance.id,
        "fed_instance_name": instance.name,
        "fed_instance_status": instance.status,
        "fed_remote_id": item_id,
        "is_local": False,
        "source": "federation",
        "thumbnail": f"/api/federation/proxy/{instance.id}/collections/{playlist_id}/images/{item_id}",
        "stream_url": f"/api/federation/proxy/{instance.id}/collections/{playlist_id}/stream/{item_id}",
    }


async def list_remote(db, user, resource):
    peers = (
        db.query(database.FederatedInstance)
        .filter(
            database.FederatedInstance.enabled.is_(True), database.FederatedInstance.status.in_(("healthy", "degraded"))
        )
        .order_by(database.FederatedInstance.name)
        .all()
    )
    payloads = await asyncio.gather(*(fetch_remote(peer, resource, user) for peer in peers), return_exceptions=True)
    result = []
    for peer, data in zip(peers, payloads):
        if not isinstance(data, dict):
            continue  # Older/offline peers must not break the local community.
        items = data.get("users" if resource == "users" else "collections")
        if not isinstance(items, list):
            continue
        for item in items:
            try:
                result.append(user_summary(peer, item) if resource == "users" else collection_summary(peer, item))
            except (KeyError, ValueError, TypeError, OverflowError):
                continue
    return result


async def get_remote_collection(instance, playlist_id, user):
    data = await fetch_remote(instance, f"collections/{playlist_id}", user)
    try:
        summary = collection_summary(instance, data)
        tracks = [remote_track(instance, playlist_id, track) for track in data["tracks"]]
        return {
            **summary,
            "tracks": tracks,
            "track_count": len(tracks),
            "revision": int(data.get("revision") or 0),
            "is_read_only": not summary["is_editable"],
            "viewer_member_id": data.get("viewer_member_id"),
        }
    except (KeyError, ValueError, TypeError, OverflowError):
        raise HTTPException(502, "Federated source returned invalid collection metadata")


async def ensure_catalog_track(db, instance, remote_id):
    if remote_id <= 0:
        raise HTTPException(400, "Invalid federated song")
    track = db.query(database.FederatedTrack).filter_by(instance_id=instance.id, remote_id=remote_id).first()
    if not track:
        data = await fetch_remote(instance, "catalog", params={"after": remote_id - 1, "limit": 1})
        rows = data.get("tracks") or []
        try:
            if not isinstance(rows, list) or not rows or int(rows[0]["id"]) != remote_id:
                raise HTTPException(404, "Federated song not found")
            metadata = track_metadata(rows[0])
        except (TypeError, ValueError, KeyError, OverflowError):
            raise HTTPException(502, "Invalid federated catalogue response")
        federation_service._upsert_federated_track(db, instance.id, {"id": remote_id, **metadata})
        db.flush()
        track = db.query(database.FederatedTrack).filter_by(instance_id=instance.id, remote_id=remote_id).first()
    return track


async def item_source(db, user, reference):
    """Validate a UI reference against our catalogue or its authoritative collection."""
    text = str(reference)
    if text.isdigit() and int(text) > 0:
        if not db.get(database.Track, int(text)):
            raise HTTPException(404, "Track not found")
        return {"track_id": int(text)}
    match = re.fullmatch(r"fed_(\d+)_(\d+)", text)
    if match:
        instance = get_instance(db, int(match[1]))
        track = await ensure_catalog_track(db, instance, int(match[2]))
        return {"federated_track_id": track.id}
    match = re.fullmatch(r"fedpl_(\d+)_(\d+)_(\d+)", text)
    if match:
        instance = get_instance(db, int(match[1]))
        data = await get_remote_collection(instance, int(match[2]), user)
        track = next((track for track in data["tracks"] if track["playlist_item_id"] == int(match[3])), None)
        if not track:
            raise HTTPException(404, "Song not in the remote collection")
        return {
            "remote_instance_id": instance.id,
            "remote_playlist_id": int(match[2]),
            "remote_item_id": int(match[3]),
            "remote_metadata_json": json.dumps(track_metadata(track)),
        }
    raise HTTPException(400, "Invalid song reference")


def instance_for_url(db, url):
    if not isinstance(url, str):
        raise HTTPException(409, "Connect the collection host to the song's source node first")
    instance = (
        db.query(database.FederatedInstance)
        .filter(
            database.FederatedInstance.enabled.is_(True),
            database.FederatedInstance.base_url.in_((url.rstrip("/"), url.rstrip("/") + "/")),
        )
        .first()
    )
    if not instance:
        raise HTTPException(409, "Connect the collection host to the song's source node first")
    return get_instance(db, instance.id)


async def invite_remote(db, playlist, profile_key):
    match = re.fullmatch(r"fed:(\d+):(\d+)", profile_key)
    if not match:
        raise HTTPException(400, "Select a remote user from the community")
    instance = get_instance(db, int(match[1]))
    # An outbound token identifies the caller's node; never trust a username alone.
    peer = (
        db.query(database.FederationOutboundPeer)
        .filter(
            database.FederationOutboundPeer.revoked.is_(False),
            database.FederationOutboundPeer.peer_url.in_(
                (instance.base_url.rstrip("/"), instance.base_url.rstrip("/") + "/")
            ),
        )
        .first()
    )
    if not peer:
        raise HTTPException(409, "Collaboration needs federation in both directions with the peer URL configured")
    data = await fetch_remote(instance, f"users/{int(match[2])}")
    member = user_summary(instance, data)
    key = f"peer:{peer.id}:{member['id']}"
    if not db.get(database.FederatedPlaylistCollaborator, (playlist.id, key)):
        db.add(
            database.FederatedPlaylistCollaborator(
                playlist_id=playlist.id,
                user_key=key,
                peer_id=peer.id,
                remote_user_id=member["id"],
                username=member["username"],
            )
        )
    return key


async def copy_remote(db, user, instance, playlist_id):
    from routers.music import playlists

    source = await get_remote_collection(instance, playlist_id, user)
    if not source["is_public"]:
        raise HTTPException(403, "Only public collections can be copied")
    target = (
        db.query(database.Playlist)
        .filter_by(owner_id=user.id, source_instance_id=instance.id, source_remote_playlist_id=playlist_id)
        .first()
    )
    action = "synced" if target else "copied"
    if not target:
        target = database.Playlist(
            owner_id=user.id,
            source_instance_id=instance.id,
            source_remote_playlist_id=playlist_id,
            name=playlists.build_unique_copy_name(db, user.id, f"{source['name']} - {source['owner_username']}"),
        )
        db.add(target)
        db.flush()
    target.kind = source["kind"]
    db.query(database.PlaylistItem).filter_by(playlist_id=target.id).delete(synchronize_session=False)
    for position, track in enumerate(source["tracks"], 1):
        db.add(
            database.PlaylistItem(
                playlist_id=target.id,
                position=position,
                remote_instance_id=instance.id,
                remote_playlist_id=playlist_id,
                remote_item_id=track["playlist_item_id"],
                remote_metadata_json=json.dumps(track_metadata(track)),
            )
        )
    target.revision = (target.revision or 0) + 1
    db.commit()
    playlists.sync_edited_playlist(db, target)
    return {
        "status": action,
        "id": target.id,
        "kind": target.kind,
        "name": target.name,
        "track_count": len(source["tracks"]),
    }


async def resolve_party_song(db, user, reference, local_url):
    """Resolve a collection item to its pool song before adding it to a party."""
    source = await item_source(db, user, reference)
    if source.get("track_id"):
        return {"track_id": source["track_id"]}
    if source.get("federated_track_id"):
        track = db.get(database.FederatedTrack, source["federated_track_id"])
    else:
        metadata = json.loads(source["remote_metadata_json"])
        remote_id = int(metadata.get("catalog_remote_id") or 0)
        origin_url = metadata.get("catalog_source_url")
        if origin_url and origin_url.rstrip("/") == local_url.rstrip("/"):
            if not db.get(database.Track, remote_id):
                raise HTTPException(404, "Party song no longer exists")
            return {"track_id": remote_id}
        instance = instance_for_url(db, origin_url)
        track = await ensure_catalog_track(db, instance, remote_id)
    return {"fed_instance_id": track.instance_id, "fed_remote_id": track.remote_id}
