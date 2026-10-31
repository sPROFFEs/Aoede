"""
Recommendations engine: Spotify, YouTube, Last.fm, MusicBrainz, and local.
"""

import json
import logging
import os
import random
import time

import database
import manager
import spotify_service
import track_identity
import youtube_service
from async_utils import gather_named
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from http_client import http_client
from lastfm_service import lastfm_service
from musicbrainz_service import musicbrainz_service
from sqlalchemy.orm import Session

from .core import get_current_user_safe, get_db

router = APIRouter()
logger = logging.getLogger(__name__)


# 48-hour cache for recommendations (in seconds)
RECS_CACHE_TTL = 48 * 3600
RECS_CACHE_DIR = "/saas-data/cache"


# --- HELPER FUNCTIONS ---


async def _fetch_lastfm_recommendations(api_key: str, seed: str):
    tracks = await lastfm_service.get_top_tracks(api_key, limit=12)
    if tracks:
        return tracks
    return await lastfm_service.search_tracks(api_key, seed, limit=12)


def get_user_country(request: Request) -> str:
    """Detect user country from headers"""
    # 1. Cloudflare Priority
    cf_country = request.headers.get("cf-ipcountry")
    if cf_country and cf_country != "XX":
        return cf_country

    # 2. Accept-Language Fallback
    accept_lang = request.headers.get("accept-language")
    if accept_lang:
        try:
            primary = accept_lang.split(",")[0].split(";")[0]
            if "-" in primary:
                return primary.split("-")[1].upper()
            else:
                lang_map = {"es": "ES", "en": "US", "fr": "FR", "de": "DE", "it": "IT", "pt": "PT"}
                return lang_map.get(primary.lower(), "US")
        except Exception as e:
            logger.debug("Accept-Language country parse failed for %s: %s", accept_lang, e)

    return "US"


async def get_navidrome_top_songs(user, db: Session, limit: int = 5):
    """Get most played songs from Navidrome"""
    try:
        target_ip = manager.get_or_spawn_container(user.username)
        url = f"http://{target_ip}:4533/{user.username}/rest/getTopSongs"
        params = {
            "u": user.username,
            "p": "enc:000000",
            "v": "1.16.1",
            "c": "concierge-internal",
            "f": "json",
            "count": limit,
        }
        headers = {"x-navidrome-user": user.username}

        resp = await http_client.get(url, params=params, headers=headers, timeout=5.0, follow_redirects=True)
        if resp.status_code == 200:
            data = resp.json()
            songs = data.get("subsonic-response", {}).get("topSongs", {}).get("song", [])
            if isinstance(songs, dict):
                songs = [songs]

            return [{"title": s.get("title"), "artist": s.get("artist")} for s in songs]
        else:
            logger.warning("Navidrome top songs failed with status %s", resp.status_code)
    except Exception as e:
        logger.warning("Error fetching Navidrome history: %s", e)

    return []


async def get_navidrome_seeds(user, db: Session, limit: int = 5):
    """Hybrid strategy to get discovery seeds from Navidrome"""
    seeds = []
    seen = set()

    def add_seed(title, artist):
        key = f"{title}-{artist}"
        if key not in seen:
            seen.add(key)
            seeds.append({"title": title, "artist": artist})

    target_ip = manager.get_or_spawn_container(user.username)
    common_params = {"u": user.username, "p": "enc:000000", "v": "1.16.1", "c": "concierge-internal", "f": "json"}
    headers = {"x-navidrome-user": user.username}

    # A. TOP SONGS
    try:
        url_top = f"http://{target_ip}:4533/{user.username}/rest/getTopSongs"
        resp = await http_client.get(
            url_top, params={**common_params, "count": 10}, headers=headers, timeout=5.0, follow_redirects=True
        )
        if resp.status_code == 200:
            data = resp.json()
            items = data.get("subsonic-response", {}).get("topSongs", {}).get("song", [])
            if isinstance(items, dict):
                items = [items]
            for s in items:
                add_seed(s.get("title"), s.get("artist"))
    except Exception as e:
        logger.debug("Navidrome top-songs seed lookup failed for %s: %s", user.username, e)

    # B. RECENTLY ADDED
    try:
        url_recent = f"http://{target_ip}:4533/{user.username}/rest/getAlbumList2"
        resp = await http_client.get(
            url_recent,
            params={**common_params, "type": "newest", "size": 3},
            headers=headers,
            timeout=5.0,
            follow_redirects=True,
        )
        if resp.status_code == 200:
            data = resp.json()
            albums = data.get("subsonic-response", {}).get("albumList2", {}).get("album", [])
            if isinstance(albums, dict):
                albums = [albums]
            for a in albums:
                add_seed("", a.get("artist"))
    except Exception as e:
        logger.debug("Navidrome recent-albums seed lookup failed for %s: %s", user.username, e)

    # Return shuffled mix
    if seeds:
        random.shuffle(seeds)
        return seeds[:limit]
    return []


# --- API ENDPOINTS ---


@router.get("/api/spotify/recommendations")
async def get_spotify_recommendations(request: Request, db: Session = Depends(get_db)):
    """Get Spotify recommendations based on user's listening history"""
    user = get_current_user_safe(db, request)
    if not user or not user.download_settings:
        return JSONResponse([])

    settings = user.download_settings
    if not settings.spotify_client_id or not settings.spotify_client_secret:
        return JSONResponse({"error": "Spotify not configured"}, status_code=400)

    country = get_user_country(request)
    user_cache_path = f"/saas-data/users/{user.username}/cache/discovery_spotify.json"

    # 1. Try personalization
    try:
        seeds = await get_navidrome_seeds(user, db, limit=10)

        if seeds:
            logger.info("Generating Spotify hybrid mix for %s with %s candidates", user.username, len(seeds))
            seed_tracks = []
            seed_artists = []

            for seed in seeds:
                if len(seed_tracks) + len(seed_artists) >= 5:
                    break

                # A. Try exact song match
                track_found = False
                if seed["title"]:
                    clean_title = seed["title"].split("(")[0].split("[")[0].strip()
                    query = f"track:{clean_title} artist:{seed['artist']}"

                    item = await spotify_service.spotify_service.search_item(
                        settings.spotify_client_id, settings.spotify_client_secret, query, type="track"
                    )
                    if item:
                        seed_tracks.append(item["id"])
                        track_found = True

                # B. Fallback to artist
                if not track_found and (len(seed_tracks) + len(seed_artists) < 5):
                    query = f"artist:{seed['artist']}"
                    item = await spotify_service.spotify_service.search_item(
                        settings.spotify_client_id, settings.spotify_client_secret, query, type="artist"
                    )
                    if item:
                        seed_artists.append(item["id"])

            if seed_tracks or seed_artists:
                logger.info("Spotify final seeds: tracks=%s artists=%s", len(seed_tracks), len(seed_artists))
                recommendations = await spotify_service.spotify_service.get_recommendations(
                    settings.spotify_client_id,
                    settings.spotify_client_secret,
                    seed_tracks=seed_tracks,
                    seed_artists=seed_artists,
                    country=country,
                    cache_path=user_cache_path,
                )
                if recommendations:
                    return JSONResponse(recommendations)

    except Exception as e:
        logger.warning("Spotify personalization failed for %s: %s", user.username, e)

    # 2. Fallback to new releases
    logger.info("Using Spotify global fallback: new releases")
    releases = await spotify_service.spotify_service.get_new_releases(
        settings.spotify_client_id, settings.spotify_client_secret, country=country, cache_path=user_cache_path
    )
    return JSONResponse(releases)


@router.get("/api/youtube/recommendations")
async def get_youtube_recommendations(request: Request, db: Session = Depends(get_db)):
    """Get YouTube recommendations"""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse([])

    country = get_user_country(request)
    cookie_path = user.download_settings.youtube_cookies_path if user.download_settings else None
    user_cache_path = f"/saas-data/users/{user.username}/cache/discovery_youtube.json"

    query_override = None

    # Try personalization
    try:
        seeds = await get_navidrome_seeds(user, db, limit=1)
        if seeds:
            seed = seeds[0]
            if seed["title"]:
                query_override = f"{seed['artist']} {seed['title']} official audio"
            else:
                query_override = f"{seed['artist']} top songs official"

            logger.info("Using YouTube personalized mix for %s: %s", user.username, query_override)
    except Exception as e:
        logger.debug("YouTube personalization seed lookup failed for %s: %s", user.username, e)

    trending = await youtube_service.youtube_service.get_trending_music(
        country=country, cookie_path=cookie_path, query_override=query_override, cache_path=user_cache_path
    )
    return JSONResponse(trending)


@router.get("/api/recommendations")
async def get_recommendations(request: Request, db: Session = Depends(get_db)):
    """Get personalized, trending, and local recommendation rows"""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)

    # --- 48h CACHE CHECK ---
    os.makedirs(RECS_CACHE_DIR, exist_ok=True)
    cache_file = os.path.join(RECS_CACHE_DIR, f"recs_{user.username}.json")
    # Library-driven shelves are titled here so we know which sections
    # in the cache to drop on each refresh (always recomputed live).
    LIVE_SHELF_TITLES = {
        "Recently Added to Library",
        "Recently played",
        "On repeat lately",
        "Rediscover",
        "From artists you used to play",
    }
    try:
        if os.path.exists(cache_file):
            with open(cache_file, "r") as f:
                cached = json.load(f)
            if cached.get("expires_at", 0) > time.time():
                logger.info("Recommendations cache hit for %s", user.username)
                sections = [s for s in cached.get("sections", []) if s.get("title") not in LIVE_SHELF_TITLES]

                # Library-driven shelves first, then the cached remote
                # recommendations, then the always-on "Recently Added"
                # section. Order chosen so the user sees their own
                # library context before remote suggestions.
                from personalization_service import get_library_shelves

                lib_shelves = get_library_shelves(db, user)
                local_section = _get_local_section(db)

                merged = list(lib_shelves) + sections
                if local_section:
                    merged.append(local_section)
                return JSONResponse(merged)
    except Exception as e:
        logger.warning("Recommendations cache read error: %s", e)

    logger.info("Recommendations cache miss for %s, fetching fresh data", user.username)

    sections = []
    top_artists = set()

    try:
        # 1. Seeds from Favorites
        favs = db.query(database.UserFavorite).filter(database.UserFavorite.user_id == user.id).limit(10).all()
        for f in favs:
            if f.track and f.track.artist:
                top_artists.add(f.track.artist)

        # 2. Seeds from Playlists
        playlist_tracks = (
            db.query(database.Track)
            .join(database.PlaylistItem)
            .join(database.Playlist)
            .filter(database.Playlist.owner_id == user.id)
            .limit(20)
            .all()
        )
        for t in playlist_tracks:
            if t.artist:
                top_artists.add(t.artist)

    except Exception as e:
        logger.warning("Recommendations seed query error: %s", e)

    top_artists_list = list(top_artists)
    random.shuffle(top_artists_list)

    settings_obj = user.download_settings
    spotify_search_term = top_artists_list[0] if top_artists_list else "pop hits"
    yt_search_term = (
        top_artists_list[1] if len(top_artists_list) > 1 else (top_artists_list[0] if top_artists_list else None)
    )
    yt_query = f"{yt_search_term} music" if yt_search_term else None
    yt_cache_path = f"/saas-data/cache/yt_recs_{user.username}.json" if yt_query else None
    lastfm_key = getattr(settings_obj, "lastfm_api_key", None) if settings_obj else None
    lastfm_seed = top_artists_list[0] if top_artists_list else "rock"
    mb_seed = top_artists_list[3] if len(top_artists_list) > 3 else spotify_search_term

    provider_calls = {
        "youtube": youtube_service.youtube_service.get_trending_music(
            limit=24,
            query_override=yt_query,
            cache_path=yt_cache_path,
        ),
        "musicbrainz": musicbrainz_service.search_recordings(mb_seed, limit=12),
    }
    if settings_obj and settings_obj.spotify_client_id and settings_obj.spotify_client_secret:
        provider_calls["spotify"] = spotify_service.spotify_service.search_tracks(
            settings_obj.spotify_client_id,
            settings_obj.spotify_client_secret,
            spotify_search_term,
            type="track",
            limit=10,
        )
    if lastfm_key:
        provider_calls["lastfm"] = _fetch_lastfm_recommendations(lastfm_key, lastfm_seed)

    provider_payloads = await gather_named(
        provider_calls,
        on_error=lambda name, exc: logger.warning("Recommendations %s error: %s", name, exc),
    )

    # 1. Spotify Recommendations
    spotify_items = []
    if settings_obj and settings_obj.spotify_client_id and settings_obj.spotify_client_secret:
        try:
            sp_tracks = provider_payloads.get("spotify", [])
            for item in sp_tracks:
                title = item.get("name") or item.get("title") or "Unknown"
                artist = item.get("artist", "Unknown")
                album = item.get("album") or "Recommended"
                item_id = item.get("url") or f"https://open.spotify.com/track/{item['id']}"

                spotify_items.append(
                    {
                        "id": item_id,
                        "title": title,
                        "artist": artist,
                        "album": album,
                        "thumbnail": item.get("image", "/static/img/default_cover.png"),
                        "is_local": False,
                        "source": "spotify",
                        "preview": item.get("preview_url"),
                        "reason": f"Because you listen to {spotify_search_term}" if top_artists_list else "Curated from top hits",
                        "category": "for_you",
                        "badge": "For You",
                        # Keep the raw Spotify track id so we can backfill
                        # the preview URL later via the embed scraper —
                        # Spotify deprecated preview_url in their official
                        # API in late 2024 and most tracks return null.
                        "_spotify_id": item.get("id")
                        if isinstance(item.get("id"), str) and len(item.get("id")) == 22
                        else None,
                    }
                )

            # Backfill preview URLs for the first few items via the
            # embed scraper. Bounded to keep the cache miss latency
            # reasonable — runs once per 48h cache window.
            backfill_targets = [it for it in spotify_items if not it.get("preview") and it.get("_spotify_id")][:6]
            preview_payloads = await gather_named(
                {
                    it["_spotify_id"]: spotify_service.spotify_service.get_embed_preview(it["_spotify_id"])
                    for it in backfill_targets
                },
                on_error=lambda _name, exc: logger.debug("Embed preview scrape failed: %s", exc),
            )
            for it in backfill_targets:
                if preview_payloads.get(it["_spotify_id"]):
                    it["preview"] = preview_payloads[it["_spotify_id"]]
            for it in spotify_items:
                it.pop("_spotify_id", None)
        except Exception as e:
            logger.warning("Recommendations Spotify error: %s", e)

    if spotify_items:
        sections.append({"title": "Spotify • For You", "items": spotify_items})

    # 2. YouTube Personalized
    yt_items = []
    try:
        yt_raw = provider_payloads.get("youtube", [])
        for item in yt_raw:
            yt_items.append(
                {
                    "id": item.get("url") or f"https://www.youtube.com/watch?v={item['id']}",
                    "title": item.get("title", "Unknown"),
                    "artist": item.get("artist", "Unknown"),
                    "album": "YouTube • Taste" if yt_query else "Trending",
                    "thumbnail": item.get("image", "/static/img/default_cover.png"),
                    "is_local": False,
                    "source": "youtube",
                    "reason": f"Similar to {yt_search_term}" if (top_artists and yt_search_term) else "Trending music audition",
                    "category": "for_you" if (top_artists and yt_search_term) else "trending",
                    "badge": "Taste Match" if (top_artists and yt_search_term) else "Trending",
                }
            )
    except Exception as e:
        logger.warning("Recommendations YouTube error: %s", e)

    if yt_items:
        sections.append(
            {"title": "YouTube • Based on Your Taste" if top_artists else "YouTube • Trending Now", "items": yt_items}
        )

    # 3. Last.fm Recommendations
    lastfm_items = []
    if lastfm_key:
        try:
            lfm_raw = provider_payloads.get("lastfm", [])
            for item in lfm_raw:
                title = item.get("name") or "Unknown"
                artist = item.get("artist", "Unknown")
                thumbnail = item.get("image") or ""
                if not thumbnail:
                    thumbnail = f"/api/cover/resolve?artist={artist}&title={title}"
                lastfm_items.append(
                    {
                        "id": f"ytsearch1:{artist} {title} official audio",
                        "title": title,
                        "artist": artist,
                        "album": "Last.fm",
                        "thumbnail": thumbnail or f"/api/cover/resolve?artist={artist}&title={title}",
                        "is_local": False,
                        "source": "lastfm",
                        "reason": f"Fans of {lastfm_seed} also love this" if lastfm_seed else "Community discovery",
                        "category": "gems",
                        "badge": "Deep Cut",
                    }
                )
        except Exception as e:
            logger.warning("Recommendations Last.fm error: %s", e)

    if lastfm_items:
        sections.append({"title": "Last.fm • Discover", "items": lastfm_items})

    # 4. MusicBrainz Recommendations
    mb_items = []
    try:
        mb_raw = provider_payloads.get("musicbrainz", [])
        for item in mb_raw:
            title = item.get("name") or "Unknown"
            artist = item.get("artist", "Unknown")
            thumbnail = f"/api/cover/resolve?artist={artist}&title={title}"
            mb_items.append(
                {
                    "id": f"ytsearch1:{artist} {title} official audio",
                    "title": title,
                    "artist": artist,
                    "album": item.get("album") or "MusicBrainz",
                    "thumbnail": thumbnail,
                    "is_local": False,
                    "source": "musicbrainz",
                    "reason": f"Explore artists similar to {mb_seed}" if mb_seed else "Artist exploration",
                    "category": "gems",
                    "badge": "Explore",
                }
            )
    except Exception as e:
        logger.warning("Recommendations MusicBrainz error: %s", e)

    if mb_items:
        sections.append({"title": "MusicBrainz • Explore", "items": mb_items})

    # 5. Local Gems
    local_items = []
    try:
        local_raw = db.query(database.Track).order_by(database.Track.created_at.desc()).limit(12).all()
        for t in local_raw:
            local_items.append(
                {
                    "id": t.source_id or f"local:{t.id}",
                    "db_id": t.id,
                    "title": t.title,
                    "artist": t.artist,
                    "album": t.album,
                    "thumbnail": f"/api/cover/{t.id}",
                    "is_local": True,
                    "source": "local",
                }
            )
    except Exception as e:
        logger.warning("Recommendations local error: %s", e)

    if local_items:
        sections.append({"title": "Recently Added to Library", "items": local_items})

    # --- SAVE TO CACHE (only remote sections) ---
    try:
        # Library-driven shelves are always live, never cached.
        live_titles = {
            "Recently Added to Library",
            "Recently played",
            "On repeat lately",
            "Rediscover",
            "From artists you used to play",
        }
        remote_sections = [s for s in sections if s.get("title") not in live_titles]
        with open(cache_file, "w") as f:
            json.dump({"sections": remote_sections, "expires_at": time.time() + RECS_CACHE_TTL}, f)
        logger.info("Recommendations cache saved for %s with %s remote sections", user.username, len(remote_sections))
    except Exception as e:
        logger.warning("Recommendations cache write error: %s", e)

    # Inject the live library shelves at the TOP so the response reads
    # [library context] [remote shelves] [recently added] — same order
    # the cache-hit branch produces.
    try:
        from personalization_service import get_library_shelves

        lib_shelves = get_library_shelves(db, user)
        if lib_shelves:
            sections = list(lib_shelves) + sections
    except Exception as e:
        logger.warning("Library shelves injection failed: %s", e)

    return JSONResponse(sections)


@router.get("/api/discovery/feed")
async def discovery_feed(
    request: Request,
    db: Session = Depends(get_db),
    limit: int = 20,
    category: str = None,
):
    """Discovery feed — curated preview tracks with contextual reasons,
    badges, and instant audio previews. If cache is empty or sparse,
    it dynamically generates fresh recommendations so users always get a rich feed."""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)

    os.makedirs(RECS_CACHE_DIR, exist_ok=True)
    cache_file = os.path.join(RECS_CACHE_DIR, f"recs_{user.username}.json")
    items: list[dict] = []
    
    local_section_titles = (
        "Recently Added",
        "Recently played",
        "On repeat",
        "Rediscover",
        "From artists you used",
    )

    try:
        if os.path.exists(cache_file):
            with open(cache_file, "r") as f:
                cached = json.load(f)
            for section in cached.get("sections", []):
                title = section.get("title", "") or ""
                if any(title.startswith(prefix) for prefix in local_section_titles):
                    continue
                for item in section.get("items", []):
                    if item.get("is_local"):
                        continue
                    item_copy = dict(item)
                    if not item_copy.get("reason"):
                        t_low = title.lower()
                        if "for you" in t_low or "taste" in t_low:
                            item_copy["reason"] = "Curated for you based on your taste"
                            item_copy["category"] = "for_you"
                            item_copy["badge"] = "For You"
                        elif "trending" in t_low:
                            item_copy["reason"] = "Trending release right now"
                            item_copy["category"] = "trending"
                            item_copy["badge"] = "Trending"
                        elif "discover" in t_low or "explore" in t_low:
                            item_copy["reason"] = "Deep cut discovery for your style"
                            item_copy["category"] = "gems"
                            item_copy["badge"] = "Deep Cut"
                        else:
                            item_copy["reason"] = "Recommended for you"
                            item_copy["category"] = "for_you"
                            item_copy["badge"] = "Discovery"
                    items.append(item_copy)
    except Exception as e:
        logger.warning("Discovery feed cache read error: %s", e)

    # If cache is missing, empty, or has fewer than 6 items, generate fresh recommendations dynamically!
    if len(items) < 6:
        try:
            recs_resp = await get_recommendations(request, db)
            if hasattr(recs_resp, "body"):
                data = json.loads(recs_resp.body.decode("utf-8"))
                if isinstance(data, list):
                    for section in data:
                        title = section.get("title", "") or ""
                        if any(title.startswith(prefix) for prefix in local_section_titles):
                            continue
                        for item in section.get("items", []):
                            if item.get("is_local"):
                                continue
                            item_copy = dict(item)
                            if not item_copy.get("reason"):
                                item_copy["reason"] = "Curated for you based on your taste"
                                item_copy["category"] = "for_you"
                                item_copy["badge"] = "For You"
                            items.append(item_copy)
        except Exception as e:
            logger.warning("Dynamic recommendations generation for discovery failed: %s", e)

    # Fallback to unplayed / unfavorited local tracks if remote services gave few items
    if len(items) < 6:
        try:
            fav_track_ids = {
                f.track_id for f in db.query(database.UserFavorite.track_id)
                .filter(database.UserFavorite.user_id == user.id).all()
                if f.track_id
            }
            local_raw = (
                db.query(database.Track)
                .filter(database.Track.id.notin_(fav_track_ids) if fav_track_ids else True)
                .order_by(database.Track.play_count.asc())
                .limit(20)
                .all()
            )
            for t in local_raw:
                items.append({
                    "id": t.source_id or f"local:{t.id}",
                    "db_id": t.id,
                    "title": t.title,
                    "artist": t.artist,
                    "album": t.album,
                    "thumbnail": f"/api/cover/{t.id}",
                    "stream_url": f"/api/stream/{t.id}",
                    "is_local": True,
                    "source": "local",
                    "reason": "Hidden gem in your library",
                    "category": "gems",
                    "badge": "Library Gem",
                })
        except Exception as e:
            logger.warning("Discovery local fallback error: %s", e)

    # De-dupe through indexed normalized columns
    existing_keys = set()
    try:
        candidate_titles = {
            track_identity.normalize_text(item.get("title") or "") for item in items if item.get("title")
        }
        if candidate_titles:
            for title_norm, artist_norm in (
                db.query(database.Track.title_norm, database.Track.artist_norm)
                .filter(database.Track.title_norm.in_(candidate_titles))
                .all()
            ):
                if title_norm:
                    existing_keys.add(f"{title_norm}|{artist_norm or ''}")
    except Exception as e:
        logger.warning("Discovery feed library lookup error: %s", e)

    seen = set()
    filtered = []
    for item in items:
        title = track_identity.normalize_text(item.get("title") or "")
        artist = track_identity.normalize_text(item.get("artist") or "")
        if not title:
            continue
        key = f"{title}|{artist}"
        if key in seen:
            continue
        if not item.get("is_local") and key in existing_keys:
            continue
        seen.add(key)
        filtered.append(item)

    if category and category != "all":
        filtered = [it for it in filtered if it.get("category") == category]

    random.shuffle(filtered)
    return JSONResponse({"items": filtered[: max(1, min(limit, 60))], "total": len(filtered)})


def _get_local_section(db: Session):
    """Build local 'Recently Added' section (cheap DB query, never cached)"""
    local_items = []
    try:
        local_raw = db.query(database.Track).order_by(database.Track.created_at.desc()).limit(12).all()
        for t in local_raw:
            local_items.append(
                {
                    "id": t.source_id or f"local:{t.id}",
                    "db_id": t.id,
                    "title": t.title,
                    "artist": t.artist,
                    "album": t.album,
                    "thumbnail": f"/api/cover/{t.id}",
                    "is_local": True,
                    "source": "local",
                }
            )
    except Exception as e:
        logger.warning("Recommendations local section error: %s", e)

    if local_items:
        return {"title": "Recently Added to Library", "items": local_items}
    return None
