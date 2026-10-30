"""
lyrics_service - Multi-tier synchronized lyrics fetcher.

Tier 1: Local library sidecar (.lrc) and embedded audio tags (mutagen).
Tier 2: lrclib.net (free, community-sourced, synced + plain lyrics).
Tier 3: NetEase Cloud Music (music.163.com) fallback for synchronized lyrics.

Caching strategy:
  - Hits are cached for 30 days in metadata_cache.
  - Misses are cached for 24 hours.
  - Outages return 'unavailable' without writing negative cache entries.
"""

from __future__ import annotations

import logging
import re
import time
from typing import TYPE_CHECKING, Optional

import metadata_cache
from http_client import http_client

if TYPE_CHECKING:
    from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)

LRCLIB_BASE = "https://lrclib.net/api"
USER_AGENT = "Aoede (https://github.com/sPROFFEs/Aoede) lyrics-fetcher"

LYRICS_HIT_TTL = 30 * 24 * 3600  # 30 days
LYRICS_MISS_TTL = 24 * 3600  # 1 day — give providers a chance to add it


def _is_fresh(payload: dict, ttl: int) -> bool:
    if not payload:
        return False
    cached_at = payload.get("cached_at") or 0
    return (time.time() - cached_at) < ttl


def _clean_metadata(title: str, artist: str) -> tuple[str, str]:
    t = (title or "").strip()
    a = (artist or "").strip()

    # Strip track numbering prefix like "01 - ", "01. "
    t = re.sub(r"^\s*\d{1,3}[._\s-]+", "", t)

    # Strip featured artists from title: (feat. ...), [feat. ...], feat. ..., etc.
    t = re.sub(r"[\(\[]\s*(?:feat\.?|ft\.?|featuring)\b.*?[\)\]]", "", t, flags=re.I)
    t = re.sub(r"\s+[-–—]?\s*(?:feat\.?|ft\.?|featuring)\b.*$", "", t, flags=re.I)

    # Strip video/audio tags: (Official Video), [Official Audio], etc.
    t = re.sub(
        r"[\(\[]\s*(?:official(?:\s+music)?\s*(?:video|audio)?|lyric(?:\s+video)?|hd|hq|4k|1080p|explicit|clean|visualizer|album\s+version|audio)\s*[\)\]]",
        "",
        t,
        flags=re.I,
    )

    # Strip remaster/version noise: (2011 Remaster), (Remastered 2021), [Remastered], - Remastered, etc.
    t = re.sub(
        r"[\(\[]\s*(?:\d{4}\s+)?(?:remaster\w*|reissue|anniversary\s+edition|deluxe(?:\s+edition)?|bonus\s+track|radio\s+edit|extended\s+mix)\s*[\)\]]",
        "",
        t,
        flags=re.I,
    )
    t = re.sub(r"\s*[-–—]\s*(?:\d{4}\s+)?(?:remaster\w*|reissue|live|edit).*$", "", t, flags=re.I)
    t = re.sub(r"\s+", " ", t).strip(" -–—,.;:")

    # Clean artist: strip " - Topic", "VEVO", and secondary artists
    a = re.sub(r"(?:\s*[-–—]\s*Topic|VEVO)$", "", a, flags=re.I).strip()
    a_parts = re.split(r"\s+(?:feat\.?|ft\.?|featuring|&|,|/|x)\s+", a, maxsplit=1, flags=re.I)
    primary_artist = a_parts[0].strip() if a_parts else a

    return (t or title, primary_artist or artist)


def _normalize_text(val: str) -> str:
    v = (val or "").casefold()
    v = re.sub(r"[\(\[\{].*?[\)\]\}]", "", v)
    v = re.sub(r"[^a-z0-9]+", " ", v)
    return re.sub(r"\s+", " ", v).strip()


def _get_local_lyrics(track_id: Optional[int], db: Optional[Session] = None) -> Optional[dict]:
    if not track_id:
        return None
    session = db
    close_session = False
    try:
        if session is None:
            import database

            session = database.SessionLocal()
            close_session = True

        import database

        track = session.query(database.Track).filter(database.Track.id == track_id).first()
        if not track or not track.filepath:
            return None

        from routers.music.streaming import _resolve_allowed_media_path

        file_path = _resolve_allowed_media_path(track.filepath)
        if not file_path:
            return None

        # 1. Sidecar .lrc file next to track
        lrc_path = file_path.with_suffix(".lrc")
        if lrc_path.is_file():
            try:
                text = lrc_path.read_text(encoding="utf-8")
                if text.strip():
                    return {"synced": text, "plain": "", "instrumental": False, "source": "local_lrc"}
            except Exception:
                try:
                    text = lrc_path.read_text(encoding="latin-1", errors="replace")
                    if text.strip():
                        return {"synced": text, "plain": "", "instrumental": False, "source": "local_lrc"}
                except Exception:
                    pass

        # 2. Embedded audio tags via mutagen
        try:
            import mutagen

            m = mutagen.File(str(file_path))
            if m is not None:
                # Vorbis / FLAC
                for k in ("LYRICS", "lyrics", "UNSYNCEDLYRICS", "unsyncedlyrics"):
                    if k in m:
                        val = m[k]
                        text = val[0] if isinstance(val, (list, tuple)) else str(val)
                        if text.strip():
                            is_synced = bool(re.search(r"\[\d{1,2}:\d{2}", text))
                            return {
                                "synced": text if is_synced else "",
                                "plain": "" if is_synced else text,
                                "instrumental": False,
                                "source": "local_tag",
                            }
                # ID3 / MP4 tags
                tags = getattr(m, "tags", None) or {}
                for tag_key, tag_val in tags.items():
                    if tag_key.startswith("USLT"):
                        text = getattr(tag_val, "text", str(tag_val))
                        if text.strip():
                            is_synced = bool(re.search(r"\[\d{1,2}:\d{2}", text))
                            return {
                                "synced": text if is_synced else "",
                                "plain": "" if is_synced else text,
                                "instrumental": False,
                                "source": "local_tag",
                            }
                if "\xa9lyr" in tags:
                    val = tags["\xa9lyr"]
                    text = val[0] if isinstance(val, (list, tuple)) else str(val)
                    if text.strip():
                        is_synced = bool(re.search(r"\[\d{1,2}:\d{2}", text))
                        return {
                            "synced": text if is_synced else "",
                            "plain": "" if is_synced else text,
                            "instrumental": False,
                            "source": "local_tag",
                        }
        except Exception as e:
            logger.debug("Failed checking embedded tags for %s: %s", file_path, e)
    except Exception as e:
        logger.debug("Local lyrics resolution failed for track %s: %s", track_id, e)
    finally:
        if close_session and session is not None:
            session.close()
    return None


async def _fetch_netease_lyrics(
    title: str, artist: str, duration: Optional[float] = None
) -> Optional[dict]:
    query = f"{title} {artist}".strip()
    if not query:
        return None
    headers = {
        "User-Agent": USER_AGENT,
        "Referer": "https://music.163.com/",
    }
    try:
        s_resp = await http_client.get(
            "https://music.163.com/api/search/get/web",
            params={"s": query, "type": 1, "offset": 0, "limit": 4},
            headers=headers,
            timeout=3.5,
        )
        if s_resp.status_code == 200:
            data = s_resp.json() or {}
            songs = (data.get("result") or {}).get("songs") or []
            if songs:
                best_song = None
                best_diff = 9999.0
                for s in songs:
                    song_dur = (s.get("dt") or s.get("duration") or 0) / 1000.0
                    diff = abs(song_dur - duration) if (duration and song_dur > 0) else 0.0
                    if duration and song_dur > 0 and diff > 8.0:
                        continue
                    if diff < best_diff:
                        best_diff = diff
                        best_song = s
                target_song = best_song or songs[0]
                song_id = target_song.get("id")
                if song_id:
                    l_resp = await http_client.get(
                        f"https://music.163.com/api/song/lyric?id={song_id}&lv=1&kv=1&tv=-1",
                        headers=headers,
                        timeout=3.5,
                    )
                    if l_resp.status_code == 200:
                        l_data = l_resp.json() or {}
                        if l_data.get("nolyrics"):
                            return {"synced": "", "plain": "", "instrumental": True, "source": "netease"}
                        lyric = (l_data.get("lrc") or {}).get("lyric") or ""
                        if lyric.strip():
                            is_synced = bool(re.search(r"\[\d{1,2}:\d{2}", lyric))
                            return {
                                "synced": lyric if is_synced else "",
                                "plain": "" if is_synced else lyric,
                                "instrumental": False,
                                "source": "netease",
                            }
    except Exception as e:
        logger.debug("NetEase fallback failed for %s: %s", query, e)
    return None


async def get_lyrics(
    *,
    title: str,
    artist: str,
    album: Optional[str] = None,
    duration: Optional[float] = None,
    track_id: Optional[int] = None,
    db: Optional[Session] = None,
) -> dict:
    """Fetch synced + plain lyrics for a track. Returns:

        {
          "synced": "[00:12.00] La la la\n...",  # may be ""
          "plain":  "La la la\n...",              # may be ""
          "instrumental": False,
          "source": "lrclib" | "netease" | "local_lrc" | "local_tag" | "cache" | "miss"
        }

    The 'miss' response is intentional — we want the frontend to render
    a friendly "no lyrics found" state rather than retry."""

    if not (title and artist):
        return {"synced": "", "plain": "", "instrumental": False, "source": "miss"}

    cache_key = metadata_cache.make_key(
        "lyrics-v2", artist=artist, title=title, album=album, duration=round(duration or 0)
    )
    cached = metadata_cache.get(cache_key)

    # Honour the cache — both hits and misses, but with different TTLs
    # so a recently-uploaded song can still win on the next refresh.
    if cached:
        ttl = LYRICS_MISS_TTL if cached.get("miss") else LYRICS_HIT_TTL
        if _is_fresh(cached, ttl):
            if cached.get("miss"):
                return {
                    "synced": "",
                    "plain": "",
                    "instrumental": False,
                    "source": "cache_miss",
                }
            return {
                "synced": cached.get("synced", ""),
                "plain": cached.get("plain", ""),
                "instrumental": cached.get("instrumental", False),
                "source": "cache",
            }

    # 1. Check local audio file for sidecar .lrc or embedded tags
    local = _get_local_lyrics(track_id, db)
    if local and (local.get("synced") or local.get("plain")):
        payload = {
            "cached_at": time.time(),
            "synced": local.get("synced", ""),
            "plain": local.get("plain", ""),
            "instrumental": False,
        }
        metadata_cache.set(cache_key, payload)
        return local

    clean_title, clean_artist = _clean_metadata(title, artist)

    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    PER_CALL_TIMEOUT = 4.0

    synced = ""
    plain = ""
    instrumental = False
    found = False

    # 2. Try exact /get from lrclib
    params = {"artist_name": artist, "track_name": title}
    if album:
        params["album_name"] = album
    if duration and duration > 0:
        params["duration"] = duration

    try:
        resp = await http_client.get(
            f"{LRCLIB_BASE}/get", params=params, headers=headers, timeout=PER_CALL_TIMEOUT
        )
        if resp.status_code == 200:
            data = resp.json()
            synced = data.get("syncedLyrics") or ""
            plain = data.get("plainLyrics") or ""
            instrumental = bool(data.get("instrumental"))
            found = bool(synced or plain or instrumental)
        elif resp.status_code != 404:
            logger.warning("lrclib /get unexpected status %s for %s — %s", resp.status_code, artist, title)
    except Exception as e:
        logger.warning("lrclib /get failed for %s — %s: %s", artist, title, e)

    # 3. If exact /get missed and title/artist was cleaned, try clean /get
    if not found and not instrumental and (clean_title != title or clean_artist != artist):
        clean_params = {"artist_name": clean_artist, "track_name": clean_title}
        if duration and duration > 0:
            clean_params["duration"] = duration
        try:
            resp = await http_client.get(
                f"{LRCLIB_BASE}/get", params=clean_params, headers=headers, timeout=PER_CALL_TIMEOUT
            )
            if resp.status_code == 200:
                data = resp.json()
                synced = data.get("syncedLyrics") or ""
                plain = data.get("plainLyrics") or ""
                instrumental = bool(data.get("instrumental"))
                found = bool(synced or plain or instrumental)
        except Exception as e:
            logger.debug("lrclib clean /get failed for %s — %s: %s", clean_artist, clean_title, e)

    # 4. If exact and clean /get missed, try /search endpoint
    search_succeeded = False
    if not found and not instrumental:
        search_query = {"track_name": clean_title, "artist_name": clean_artist}
        try:
            s_resp = await http_client.get(
                f"{LRCLIB_BASE}/search",
                params=search_query,
                headers=headers,
                timeout=PER_CALL_TIMEOUT,
            )
            if s_resp.status_code == 200:
                results = s_resp.json() or []
                search_succeeded = True
                candidates = []
                clean_title_cf = clean_title.casefold()
                clean_artist_cf = clean_artist.casefold()
                for cand in results:
                    cand_title = (cand.get("trackName") or "").strip().casefold()
                    cand_artist = (cand.get("artistName") or "").strip().casefold()
                    title_matched = (
                        cand_title == title.strip().casefold()
                        or cand_title == clean_title_cf
                        or _normalize_text(cand_title) == _normalize_text(title)
                        or _normalize_text(cand_title) == _normalize_text(clean_title)
                    )
                    artist_matched = (
                        cand_artist == artist.strip().casefold()
                        or cand_artist == clean_artist_cf
                        or clean_artist_cf in cand_artist
                        or cand_artist in clean_artist_cf
                    )
                    if not (title_matched and artist_matched):
                        continue

                    candidate_duration = cand.get("duration")
                    dur_diff = (
                        abs(candidate_duration - duration)
                        if (duration and isinstance(candidate_duration, (int, float)))
                        else 0
                    )
                    # Relaxed tolerance of 6 seconds to handle file lead-in / lead-out padding
                    if duration and (not isinstance(candidate_duration, (int, float)) or dur_diff > 6):
                        continue
                    if cand.get("syncedLyrics") or cand.get("plainLyrics") or cand.get("instrumental"):
                        candidates.append(cand)

                candidates.sort(
                    key=lambda c: (
                        (c.get("albumName") or "").strip().casefold() != (album or "").strip().casefold(),
                        not bool(c.get("syncedLyrics")),
                        abs((c.get("duration") or 0) - (duration or 0)),
                    )
                )
                if candidates:
                    cand = candidates[0]
                    synced = cand.get("syncedLyrics") or ""
                    plain = cand.get("plainLyrics") or ""
                    instrumental = bool(cand.get("instrumental"))
                    found = True
        except Exception as e:
            logger.warning("lrclib /search failed for %s — %s: %s", clean_artist, clean_title, e)

    if found:
        payload = {
            "cached_at": time.time(),
            "synced": synced,
            "plain": plain,
            "instrumental": instrumental,
        }
        metadata_cache.set(cache_key, payload)
        return {"synced": synced, "plain": plain, "instrumental": instrumental, "source": "lrclib"}

    # 5. NetEase Cloud Music fallback for synchronized lyrics
    if not found and not instrumental:
        try:
            netease_res = await _fetch_netease_lyrics(clean_title, clean_artist, duration)
            if netease_res and (
                netease_res.get("synced")
                or netease_res.get("plain")
                or netease_res.get("instrumental")
            ):
                payload = {
                    "cached_at": time.time(),
                    "synced": netease_res.get("synced", ""),
                    "plain": netease_res.get("plain", ""),
                    "instrumental": netease_res.get("instrumental", False),
                }
                metadata_cache.set(cache_key, payload)
                return netease_res
        except Exception as e:
            logger.debug("NetEase fallback failed: %s", e)

    # An outage is retryable, not a definitive missing record for the next day.
    if not search_succeeded:
        return {"synced": "", "plain": "", "instrumental": False, "source": "unavailable"}

    # Persist the miss with a short TTL.
    metadata_cache.set(cache_key, {"cached_at": time.time(), "miss": True})
    return {"synced": "", "plain": "", "instrumental": False, "source": "miss"}
