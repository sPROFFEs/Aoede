"""Read bounded, ordered collection metadata; audio uses the existing downloader."""

import asyncio
import re
import time
from urllib.parse import parse_qs, urlparse

import metadata_cache
import spotify_service
import yt_dlp
from ytmusicapi import YTMusic

MAX_COLLECTION_TRACKS = 500
YOUTUBE_HOSTS = {"youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be"}


class CollectionError(ValueError):
    def __init__(self, message, status_code=400):
        super().__init__(message)
        self.status_code = status_code


def parse_collection_url(raw_url):
    """Return a canonical provider/kind/id tuple, never an arbitrary fetch URL."""
    try:
        parsed = urlparse((raw_url or "").strip())
        host = (parsed.hostname or "").lower()
        port = parsed.port
    except ValueError:
        return None
    if parsed.scheme not in {"http", "https"} or parsed.username or parsed.password:
        return None
    if port not in {None, 80, 443}:
        return None
    if host == "open.spotify.com":
        match = re.fullmatch(r"/(?:intl-[a-z-]+/|embed/)?(album|playlist)/([A-Za-z0-9]{22})/?", parsed.path)
        if match:
            kind, identifier = match.groups()
            return "spotify", kind, identifier, f"https://open.spotify.com/{kind}/{identifier}"
    if host in YOUTUBE_HOSTS:
        identifier = parse_qs(parsed.query).get("list", [""])[0]
        if identifier:
            if not re.fullmatch(r"[A-Za-z0-9_-]{10,150}", identifier):
                return None
            kind = "album" if identifier.startswith("OLAK5uy_") else "playlist"
            return "youtube", kind, identifier, f"https://www.youtube.com/playlist?list={identifier}"
        match = re.fullmatch(r"/browse/(MPRE[A-Za-z0-9_-]{5,150})/?", parsed.path)
        if match:
            identifier = match[1]
            return "youtube", "album", identifier, f"https://music.youtube.com/browse/{identifier}"
    return None


def _names(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return value.get("name", "")
    return ", ".join(item.get("name", "") for item in (value or []) if item)


def _image(item, key="thumbnails"):
    images = item.get(key) or []
    return images[-1].get("url", "") if images else ""


async def _spotify_get(user, path, params=None):
    settings = user.download_settings
    if not settings or not settings.spotify_client_id or not settings.spotify_client_secret:
        raise CollectionError("Configure Spotify credentials in Settings, or import a YouTube collection instead.")
    service = spotify_service.spotify_service
    token = await service._get_access_token(settings.spotify_client_id, settings.spotify_client_secret)
    if not token:
        raise CollectionError("Spotify authentication failed. Check your credentials in Settings.", 403)
    response = await service.client.get(
        f"https://api.spotify.com/v1/{path}", headers={"Authorization": f"Bearer {token}"}, params=params
    )
    if response.status_code in {403, 404}:
        raise CollectionError(
            "Spotify did not grant access to this collection. Private playlists and restricted Spotify apps "
            "may not expose their tracks; try a public YouTube collection instead.",
            response.status_code,
        )
    if response.status_code == 429:
        raise CollectionError("Spotify search limit reached. Please try again later.", 429)
    if response.status_code != 200:
        raise CollectionError("Spotify could not load the collection. Please try again later.", 502)
    return response.json()


async def _spotify_collection(user, kind, identifier):
    data = await _spotify_get(user, f"{kind}s/{identifier}")
    page = data.get("tracks") if kind == "album" else data.get("items", data.get("tracks"))
    if not isinstance(page, dict):
        raise CollectionError("Spotify returned collection details without access to its tracks.", 403)
    tracks = []
    seen_offsets = set()
    while True:
        tracks.extend(page.get("items") or [])
        if len(tracks) > MAX_COLLECTION_TRACKS or (page.get("total") or 0) > MAX_COLLECTION_TRACKS:
            raise CollectionError(f"Collections are limited to {MAX_COLLECTION_TRACKS} tracks.")
        if not page.get("next"):
            break
        # Build pagination locally instead of forwarding credentials to a supplied `next` URL.
        offset = int(page.get("offset", 0)) + len(page.get("items") or [])
        if offset in seen_offsets or not page.get("items"):
            raise CollectionError("Spotify returned incomplete collection metadata.", 502)
        seen_offsets.add(offset)
        endpoint = f"albums/{identifier}/tracks" if kind == "album" else f"playlists/{identifier}/items"
        page = await _spotify_get(user, endpoint, {"limit": 50, "offset": offset})
    result = {"name": data.get("name"), "thumbnail": _image(data, "images"), "tracks": [], "skipped": 0}
    for position, entry in enumerate(tracks, 1):
        track = entry if kind == "album" else (entry or {}).get("item", (entry or {}).get("track"))
        if (
            not track
            or not track.get("id")
            or track.get("is_local")
            or track.get("is_playable") is False
            or track.get("type", "track") != "track"
        ):
            result["skipped"] += 1
            continue
        result["tracks"].append(
            {
                "url": f"https://open.spotify.com/track/{track['id']}",
                "title": track.get("name", ""),
                "artist": _names(track.get("artists")),
                "album": data.get("name", "") if kind == "album" else (track.get("album") or {}).get("name", ""),
                "position": position,
            }
        )
    return result


def _youtube_collection(kind, identifier, url):
    try:
        client = YTMusic()
        data = (
            client.get_album(identifier)
            if identifier.startswith("MPRE")
            else client.get_playlist(identifier, limit=MAX_COLLECTION_TRACKS + 1)
        )
        entries = data.get("tracks") or []
        total = data.get("trackCount") or len(entries)
    except Exception:
        if identifier.startswith("MPRE"):
            raise CollectionError("YouTube Music could not load this album.", 502)
        # Ordinary YouTube playlists can contain videos absent from YouTube Music.
        with yt_dlp.YoutubeDL(
            {
                "quiet": True,
                "no_warnings": True,
                "extract_flat": "in_playlist",
                "skip_download": True,
                "ignoreerrors": True,
                "socket_timeout": 15,
                "retries": 1,
                "playlistend": MAX_COLLECTION_TRACKS + 1,
            }
        ) as ydl:
            data = ydl.extract_info(url, download=False)
        if not data:
            raise CollectionError("YouTube playlist unavailable. Use a public playlist URL.", 404)
        entries = list(data.get("entries") or [])
        total = data.get("playlist_count") or len(entries)
    if int(total) > MAX_COLLECTION_TRACKS or len(entries) > MAX_COLLECTION_TRACKS:
        raise CollectionError(f"Collections are limited to {MAX_COLLECTION_TRACKS} tracks.")
    result = {"name": data.get("title"), "thumbnail": _image(data), "tracks": [], "skipped": 0}
    for position, entry in enumerate(entries, 1):
        identifier = (entry or {}).get("videoId", (entry or {}).get("id"))
        if (
            not entry
            or not identifier
            or entry.get("isAvailable") is False
            or entry.get("title") in {"[Deleted video]", "[Private video]"}
        ):
            result["skipped"] += 1
            continue
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", identifier):
            result["skipped"] += 1
            continue
        result["tracks"].append(
            {
                "url": f"https://www.youtube.com/watch?v={identifier}",
                "title": entry.get("title", ""),
                "artist": _names(entry.get("artists"))
                or (_names(data.get("artists")) if kind == "album" else "")
                or entry.get("uploader", ""),
                "album": (entry.get("album") or {}).get("name", "")
                or (data.get("title", "") if kind == "album" else ""),
                "position": position,
            }
        )
    return result


async def resolve_collection(user, url):
    parsed = parse_collection_url(url)
    if not parsed:
        raise CollectionError("Use a Spotify album/playlist or a YouTube playlist/YouTube Music album URL.")
    source, kind, identifier, canonical = parsed
    key = metadata_cache.make_key("collection:v1", user=user.id, url=canonical)
    cached = metadata_cache.get(key)
    if cached and cached.get("expires_at", 0) > time.time():
        return cached["data"]
    try:
        data = (
            await _spotify_collection(user, kind, identifier)
            if source == "spotify"
            else await asyncio.to_thread(_youtube_collection, kind, identifier, canonical)
        )
    except CollectionError:
        raise
    except Exception:
        raise CollectionError("The provider could not load the collection. Please try again later.", 502)
    if not data["tracks"]:
        raise CollectionError("This collection contains no available music tracks.")
    data.update({"source": source, "kind": kind, "url": canonical, "track_count": len(data["tracks"])})
    metadata_cache.set(key, {"expires_at": time.time() + 600, "data": data})
    return data


def _youtube_search(query):
    client = YTMusic()
    results = []
    for kind in ("album", "playlist"):
        for item in client.search(query, filter=f"{kind}s", limit=10)[:10]:
            identifier = item.get("browseId", "")
            if kind == "album":
                url = f"https://music.youtube.com/browse/{identifier}"
            else:
                identifier = item.get("playlistId") or identifier.removeprefix("VL")
                url = f"https://www.youtube.com/playlist?list={identifier}"
            if not parse_collection_url(url):
                continue
            results.append(
                {
                    "name": item.get("title", ""),
                    "artist": _names(item.get("artists") or item.get("artist") or item.get("author")),
                    "thumbnail": _image(item),
                    "kind": kind,
                    "url": url,
                    "source": "youtube",
                }
            )
    return results


async def search_collections(user, query, source):
    query = query.strip()
    if not query:
        return {"items": [], "warnings": []}
    if source not in {"all", "spotify", "youtube"}:
        raise CollectionError("Collection search supports Spotify and YouTube.")
    key = metadata_cache.make_key("collection-search:v1", user=user.id, q=query, source=source)
    cached = metadata_cache.get(key)
    if cached and cached.get("expires_at", 0) > time.time():
        return cached["data"]

    async def search_spotify():
        data = await _spotify_get(user, "search", {"q": query, "type": "album,playlist", "limit": 10})
        items = []
        for kind in ("album", "playlist"):
            for item in (data.get(f"{kind}s") or {}).get("items", []):
                if not item or not item.get("id"):
                    continue
                items.append(
                    {
                        "name": item.get("name", ""),
                        "artist": _names(item.get("artists") or (item.get("owner") or {}).get("display_name")),
                        "thumbnail": _image(item, "images"),
                        "kind": kind,
                        "url": f"https://open.spotify.com/{kind}/{item['id']}",
                        "source": "spotify",
                    }
                )
        return items

    calls = {}
    if source in {"all", "youtube"}:
        calls["youtube"] = asyncio.to_thread(_youtube_search, query)
    if source in {"all", "spotify"}:
        calls["spotify"] = search_spotify()
    payloads = await asyncio.gather(*calls.values(), return_exceptions=True)
    result = {"items": [], "warnings": []}
    for provider, payload in zip(calls, payloads):
        if isinstance(payload, Exception):
            result["warnings"].append(
                str(payload)
                if isinstance(payload, CollectionError)
                else f"{provider.title()} search is temporarily unavailable."
            )
        else:
            result["items"].extend(payload)
    if not result["warnings"]:
        metadata_cache.set(key, {"expires_at": time.time() + 3600, "data": result})
    return result
