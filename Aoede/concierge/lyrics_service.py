"""
lyrics_service - Synced lyrics fetcher using lrclib.net.

Why lrclib.net?
  - Free, no API key, no auth, no rate-limits as long as you set a
    reasonable User-Agent.
  - Crowd-sourced LRC corpus that beats Genius/Musixmatch for most
    non-English catalog and is comparable for mainstream pop.
  - Returns both `syncedLyrics` (LRC format) and `plainLyrics` so the
    frontend can fall back gracefully if synced are missing.

Caching strategy
  - Lyrics are immutable once published — we cache for 30 days in the
    shared metadata_cache.db with a recording-specific key (artist, title, album and duration) so
    every user benefits from a single fetch per song.
  - 404s are also cached (briefly, 24h) so we don't hammer lrclib for
    obscure tracks every time the user replays them. Stored as
    `{"miss": true, ...}`.
"""

from __future__ import annotations

import logging
import time
from typing import Optional

import metadata_cache
from http_client import http_client

logger = logging.getLogger(__name__)

LRCLIB_BASE = "https://lrclib.net/api"
USER_AGENT = "Aoede (https://github.com/sPROFFEs/Aoede) lyrics-fetcher"

LYRICS_HIT_TTL = 30 * 24 * 3600  # 30 days
LYRICS_MISS_TTL = 24 * 3600  # 1 day — give lrclib a chance to add it


def _is_fresh(payload: dict, ttl: int) -> bool:
    if not payload:
        return False
    cached_at = payload.get("cached_at") or 0
    return (time.time() - cached_at) < ttl


async def get_lyrics(
    *,
    title: str,
    artist: str,
    album: Optional[str] = None,
    duration: Optional[float] = None,
) -> dict:
    """Fetch synced + plain lyrics for a track. Returns:

        {
          "synced": "[00:12.00] La la la\n...",  # may be ""
          "plain":  "La la la\n...",              # may be ""
          "instrumental": False,
          "source": "lrclib" | "cache" | "miss"
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

    params = {
        "artist_name": artist,
        "track_name": title,
    }
    if album:
        params["album_name"] = album
    if duration and duration > 0:
        params["duration"] = duration

    url = f"{LRCLIB_BASE}/get"
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}

    synced = ""
    plain = ""
    instrumental = False
    found = False

    # Per-call timeouts (4s each) instead of a single 8s pool timeout.
    # The previous shape let a slow /get burn most of the budget,
    # leaving /search with no headroom and the worker effectively
    # blocked the full 8s on a hung peer.
    PER_CALL_TIMEOUT = 4.0

    try:
        resp = await http_client.get(url, params=params, headers=headers, timeout=PER_CALL_TIMEOUT)
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

    # Short-circuit on instrumental: /get already told us the track
    # has no lyrics; running /search would just confirm that and
    # waste a network round trip + worker time.
    # If the exact-match endpoint missed otherwise, try the search
    # endpoint — lrclib's /get demands tight metadata; /search is
    # fuzzy. Match the recording before choosing a result.
    search_succeeded = False
    if not found and not instrumental:
        try:
            s_resp = await http_client.get(
                f"{LRCLIB_BASE}/search",
                params={"track_name": title, "artist_name": artist},
                headers=headers,
                timeout=PER_CALL_TIMEOUT,
            )
            if s_resp.status_code == 200:
                results = s_resp.json() or []
                search_succeeded = True
                candidates = []
                for cand in results:
                    cand_title = (cand.get("trackName") or "").strip().casefold()
                    cand_artist = (cand.get("artistName") or "").strip().casefold()
                    if cand_title != title.strip().casefold() or cand_artist != artist.strip().casefold():
                        continue
                    # LRCLIB's exact endpoint uses a two-second recording tolerance.
                    # Keep it on search too, instead of taking an unrelated live/remix version.
                    candidate_duration = cand.get("duration")
                    if duration and (
                        not isinstance(candidate_duration, (int, float)) or abs(candidate_duration - duration) > 2
                    ):
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
            logger.warning("lrclib /search failed for %s — %s: %s", artist, title, e)

    if found:
        payload = {
            "cached_at": time.time(),
            "synced": synced,
            "plain": plain,
            "instrumental": instrumental,
        }
        metadata_cache.set(cache_key, payload)
        return {"synced": synced, "plain": plain, "instrumental": instrumental, "source": "lrclib"}

    # An outage is retryable, not a definitive missing record for the next day.
    if not search_succeeded:
        return {"synced": "", "plain": "", "instrumental": False, "source": "unavailable"}

    # Persist the miss with a short TTL.
    metadata_cache.set(cache_key, {"cached_at": time.time(), "miss": True})
    return {"synced": "", "plain": "", "instrumental": False, "source": "miss"}
