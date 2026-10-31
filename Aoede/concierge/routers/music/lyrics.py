"""Lyrics endpoint — wraps lyrics_service so the frontend never talks
to lrclib directly (CORS, plus we want the shared cache)."""

import logging
import math

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse
from lyrics_service import get_lyrics
from sqlalchemy.orm import Session

from .core import get_current_user_safe, get_db

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/api/lyrics")
async def lyrics(
    request: Request,
    db: Session = Depends(get_db),
    title: str = Query("", max_length=1000),
    artist: str = Query("", max_length=1000),
    album: str = Query("", max_length=1000),
    duration: float | str | None = Query("0"),
    track_id: int | None = Query(None),
):
    """Returns synced + plain lyrics for a track. Cached per-song
    globally so a popular song gets fetched once for the whole instance.
    Frontend handles the empty-state when both are blank."""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)

    clean_title = (title or "").strip()[:300]
    clean_artist = (artist or "").strip()[:200]
    clean_album = (album or "").strip()[:300]

    dur_val = 0.0
    if duration is not None:
        try:
            val = float(duration)
            if not math.isnan(val) and val > 0:
                dur_val = min(86400.0, val / 1000.0 if val > 10000.0 else val)
        except (ValueError, TypeError):
            dur_val = 0.0

    data = await get_lyrics(
        title=clean_title,
        artist=clean_artist,
        album=clean_album or None,
        duration=dur_val if dur_val > 0 else None,
        track_id=track_id,
        db=db,
    )
    return JSONResponse(data, status_code=503 if data.get("source") == "unavailable" else 200)
