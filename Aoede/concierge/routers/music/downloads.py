"""
Download management endpoints.
"""

import logging
import os
from typing import Literal
from urllib.parse import parse_qs, urlparse

import collection_service
import database
import downloader_service
import manager
import metadata_service
import source_registry
from fastapi import APIRouter, BackgroundTasks, Depends, Form, Query, Request
from fastapi.responses import JSONResponse, RedirectResponse
from limiter import limiter
from playlist_files import MAX_PLAYLIST_NAME_LENGTH, normalize_playlist_name
from pydantic import BaseModel, Field
from sqlalchemy import or_
from sqlalchemy.orm import Session

from . import playlists
from .core import get_current_user_safe, get_db

router = APIRouter()
logger = logging.getLogger(__name__)

DOWNLOAD_JOB_HISTORY_LIMIT = 100
TERMINAL_DOWNLOAD_STATUSES = ("completed", "finished", "failed", "error")


# --- PYDANTIC MODELS ---


class DownloadRequest(BaseModel):
    url: str
    title: str = None
    artist: str = None
    album: str = None
    source: str = None


class CollectionDownloadRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    kind: Literal["playlist", "album"] | None = None
    name: str | None = Field(default=None, max_length=MAX_PLAYLIST_NAME_LENGTH)


def _infer_source_label(raw_source: str | None, raw_url: str | None) -> str:
    return source_registry.infer_source(raw_source, raw_url)


def _serialize_download_job(job, target: str | None = None):
    resolved_track = getattr(job, "resolved_track", None)
    resolved_track_title = None
    if resolved_track:
        resolved_track_title = (
            " - ".join(part for part in [resolved_track.artist, resolved_track.title] if part).strip()
            or resolved_track.title
        )

    return {
        "id": job.id,
        "url": job.input_url,
        "input_url": job.input_url,
        "original_input_url": job.original_input_url or job.input_url,
        "status": job.status,
        "progress": job.progress_percent,
        "filename": job.current_file,
        "track_title": job.requested_title,
        "requested_title": job.requested_title,
        "requested_artist": job.requested_artist,
        "requested_album": job.requested_album,
        "source": job.requested_source,
        "requested_source": job.requested_source,
        "resolution_mode": job.resolution_mode,
        "resolved_title": job.resolved_title,
        "resolved_artist": job.resolved_artist,
        "resolved_album": job.resolved_album,
        "resolved_track_id": job.resolved_track_id,
        "resolved_track_count": job.resolved_track_count or 0,
        "resolved_track_title": resolved_track_title,
        "engine_used": job.engine_used,
        "fallback_reason": job.fallback_reason,
        "error_type": job.error_type,
        "target_playlist_id": job.target_modern_playlist_id,
        "target_position": job.target_position,
        "detail": job.current_file,
        "error": job.error_log,
        "target": target,
        "created_at": str(job.created_at),
    }


async def _resolve_download_url(user, req: DownloadRequest) -> tuple[str, str]:
    """
    Prefer a Spotify track URL for metadata-only sources so the downloader can
    use spotDL first. Falls back to the original URL when no exact-enough match
    is available.
    """
    raw_url = (req.url or "").strip()
    title = (req.title or "").strip()
    artist = (req.artist or "").strip()

    if not raw_url:
        return raw_url, "empty"

    # Federation tracks: pass through unchanged. The downloader
    # branches on the "fed:" prefix and fetches the file directly
    # from the peer — we must NOT mangle the id by routing it
    # through the YouTube/Spotify resolver below.
    if raw_url.startswith("fed:"):
        return raw_url, "federation-direct"

    if "youtube.com/watch" in raw_url or "youtu.be/" in raw_url:
        return raw_url, "youtube-direct"

    if "spotify.com/track/" in raw_url:
        return raw_url, "spotify-direct"

    if not title or not artist or not user or not user.download_settings:
        return raw_url, "original"

    settings = user.download_settings
    try:
        resolved = await metadata_service.resolve_download_target(
            settings=settings,
            raw_url=raw_url,
            title=title,
            artist=artist,
            album=(req.album or "").strip(),
            source=(req.source or "").strip(),
        )
        return resolved.get("url") or raw_url, resolved.get("resolution_mode") or "original"
    except Exception as e:
        logger.warning("Cross-provider download resolve failed: %s", e)

    return raw_url, "original"


# --- BACKGROUND TASK ---


async def run_download_in_background(job_id: int, user_id: int):
    """Process a download job in the background"""
    bg_db = database.SessionLocal()
    try:
        dm = downloader_service.DownloadManager(bg_db, user_id)
        await dm.process_download(job_id)
        if job := bg_db.get(database.DownloadJob, job_id):
            if job.target_modern_playlist and job.status in ("completed", "finished"):
                playlists.sync_edited_playlist(bg_db, job.target_modern_playlist)
    except Exception as e:
        logger.warning("Background task error: %s", e)
    finally:
        bg_db.close()


async def run_collection_downloads(job_ids: list[int], user_id: int):
    # ponytail: sequential batch, existing semaphore still bounds simultaneous users.
    for job_id in job_ids:
        await run_download_in_background(job_id, user_id)


def _prune_terminal_download_jobs(db: Session, user_id: int, keep: int = DOWNLOAD_JOB_HISTORY_LIMIT):
    imported_position = (
        db.query(database.PlaylistItem.id)
        .filter(
            database.PlaylistItem.playlist_id == database.DownloadJob.target_modern_playlist_id,
            database.PlaylistItem.position == database.DownloadJob.target_position,
        )
        .exists()
    )
    terminal_jobs = (
        db.query(database.DownloadJob.id)
        .filter(database.DownloadJob.user_id == user_id, database.DownloadJob.status.in_(TERMINAL_DOWNLOAD_STATUSES))
        .filter(
            or_(
                database.DownloadJob.target_position.is_(None),
                database.DownloadJob.target_modern_playlist_id.is_(None),
                database.DownloadJob.status.notin_(("failed", "error")),
                imported_position,
            )
        )
        .order_by(database.DownloadJob.created_at.desc(), database.DownloadJob.id.desc())
        .all()
    )

    stale_ids = [row.id for row in terminal_jobs[keep:]]
    if not stale_ids:
        return 0

    deleted = (
        db.query(database.DownloadJob)
        .filter(database.DownloadJob.user_id == user_id, database.DownloadJob.id.in_(stale_ids))
        .delete(synchronize_session=False)
    )
    db.commit()
    return deleted


# Statuses that represent "the previous concierge process said this job
# was actively in flight." After a restart the BackgroundTasks worker
# that was driving them is gone, so every row in one of these states is
# by definition a zombie — there's no live process behind it.
NON_TERMINAL_DOWNLOAD_STATUSES = ("pending", "processing", "downloading")


def mark_stuck_download_jobs_failed(db: Session) -> int:
    """Sweep any DownloadJob still flagged as in-flight from a previous
    concierge process and mark it failed. Called from main.startup_event
    on every boot so the Downloads Manager stops showing rows that can
    never finish.

    Returns the number of rows updated. Idempotent — safe to call
    repeatedly; once a row is "failed" it's no longer matched.
    """
    stuck = db.query(database.DownloadJob).filter(database.DownloadJob.status.in_(NON_TERMINAL_DOWNLOAD_STATUSES)).all()
    for job in stuck:
        job.status = "failed"
        job.error_type = job.error_type or "interrupted_by_restart"
        job.error_log = (
            "Marked failed at concierge startup: the BackgroundTasks worker "
            "that was driving this job did not survive the previous process "
            "exit. Retry the download from the source."
        )
    if stuck:
        db.commit()
    return len(stuck)


# --- ENDPOINTS ---


@router.get("/api/collections/search")
@limiter.limit("30/minute")
async def collection_search(
    request: Request, q: str = Query(default="", max_length=200), source: str = "all", db: Session = Depends(get_db)
):
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)
    try:
        return await collection_service.search_collections(user, q, source)
    except collection_service.CollectionError as exc:
        return JSONResponse({"error": str(exc)}, status_code=exc.status_code)


@router.get("/api/collections/resolve")
@limiter.limit("15/minute")
async def collection_preview(request: Request, url: str = Query(max_length=2048), db: Session = Depends(get_db)):
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)
    try:
        return await collection_service.resolve_collection(user, url)
    except collection_service.CollectionError as exc:
        return JSONResponse({"error": str(exc)}, status_code=exc.status_code)


@router.post("/api/download/collection")
@limiter.limit("10/minute")
async def import_collection(
    req: CollectionDownloadRequest, background_tasks: BackgroundTasks, request: Request, db: Session = Depends(get_db)
):
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)
    used, limit, _ = manager.get_pool_status(db)
    if used >= limit:
        return JSONResponse({"error": "Global pool limit reached. Please delete some tracks."}, status_code=403)
    try:
        collection = await collection_service.resolve_collection(user, req.url)
        name = normalize_playlist_name(
            req.name or (collection.get("name") or "Imported collection")[:MAX_PLAYLIST_NAME_LENGTH]
        )
    except (collection_service.CollectionError, ValueError) as exc:
        return JSONResponse({"error": str(exc)}, status_code=getattr(exc, "status_code", 400))
    active_jobs = (
        db.query(database.DownloadJob)
        .filter(
            database.DownloadJob.user_id == user.id, database.DownloadJob.status.in_(NON_TERMINAL_DOWNLOAD_STATUSES)
        )
        .count()
    )
    if active_jobs + len(collection["tracks"]) > collection_service.MAX_COLLECTION_TRACKS:
        return JSONResponse(
            {"error": "Your download queue is full. Wait for current downloads to finish."}, status_code=409
        )
    playlist = database.Playlist(
        name=playlists.build_unique_copy_name(db, user.id, name), owner_id=user.id, kind=req.kind or collection["kind"]
    )
    try:
        db.add(playlist)
        db.flush()
        jobs = [
            database.DownloadJob(
                user_id=user.id,
                input_url=track["url"],
                original_input_url=track["url"],
                requested_title=track["title"],
                requested_artist=track["artist"],
                requested_album=track["album"],
                requested_source=collection["source"],
                resolution_mode=f"{collection['source']}-collection",
                target_modern_playlist_id=playlist.id,
                target_position=track["position"],
                status="pending",
                progress_percent=0,
                current_file="Queued for collection import",
            )
            for track in collection["tracks"]
        ]
        db.add_all(jobs)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("Could not queue collection import")
        return JSONResponse({"error": "Could not queue collection import."}, status_code=500)
    background_tasks.add_task(run_collection_downloads, [job.id for job in jobs], user.id)
    return {
        "status": "queued",
        "playlist_id": playlist.id,
        "name": playlist.name,
        "kind": playlist.kind,
        "track_count": len(jobs),
        "skipped": collection["skipped"],
        "message": f"{len(jobs)} songs queued in {playlist.name}",
    }


@router.post("/api/download")
async def trigger_download(
    req: DownloadRequest, background_tasks: BackgroundTasks, request: Request, db: Session = Depends(get_db)
):
    """Trigger download from external URL"""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)

    if collection_service.parse_collection_url(req.url):
        return await import_collection(CollectionDownloadRequest(url=req.url), background_tasks, request, db)

    _prune_terminal_download_jobs(db, user.id)

    # Pre-check global pool quota for immediate feedback
    pool_usage = manager.get_pool_status(db)
    if pool_usage[0] >= pool_usage[1]:  # used_gb >= limit_gb
        return JSONResponse(
            {"error": f"Global pool limit reached ({pool_usage[1]}GB). Please delete some tracks."}, status_code=403
        )

    resolved_url, resolution_mode = await _resolve_download_url(user, req)

    # Create Job
    job = database.DownloadJob(
        user_id=user.id,
        input_url=resolved_url,
        original_input_url=(req.url or "").strip() or resolved_url,
        requested_title=(req.title or "").strip() or None,
        requested_artist=(req.artist or "").strip() or None,
        requested_album=(req.album or "").strip() or None,
        requested_source=_infer_source_label(req.source, req.url),
        resolution_mode=resolution_mode,
        status="pending",
        progress_percent=0.0,
        new_playlist_name=None,  # No default playlist - just add to library
        current_file="Queued for download",
    )
    db.add(job)
    db.commit()
    db.refresh(job)

    # Schedule the background task with its own DB session. The
    # request's `db` is closed by FastAPI's Depends cleanup as soon
    # as this handler returns, so capturing it inside a long-running
    # background task left the downloader operating on a closed
    # session. run_download_in_background opens a fresh SessionLocal
    # for the task's lifetime — the form endpoint already uses it.
    background_tasks.add_task(run_download_in_background, job.id, user.id)

    message = "Download queued"
    if resolution_mode == "spotify-resolved":
        message = "Download queued using Spotify metadata fallback"
    elif resolution_mode == "youtube-resolved":
        message = "Download queued using cross-provider YouTube matching"

    return {
        "status": "queued",
        "job_id": job.id,
        "url": resolved_url,
        "message": message,
        "resolution_mode": resolution_mode,
    }


@router.post("/api/downloads/{job_id}/retry")
async def retry_download(
    job_id: int, background_tasks: BackgroundTasks, request: Request, db: Session = Depends(get_db)
):
    """Requeue a failed download as a new job (same URL, same target playlist)."""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)

    job = (
        db.query(database.DownloadJob)
        .filter(database.DownloadJob.id == job_id, database.DownloadJob.user_id == user.id)
        .first()
    )
    if not job:
        return JSONResponse({"error": "Download not found"}, status_code=404)
    if (job.status or "").lower() not in ("failed", "error"):
        return JSONResponse({"error": "Only failed downloads can be retried"}, status_code=400)

    if job.target_position is not None and job.target_modern_playlist_id is not None:
        latest = (
            db.query(database.DownloadJob)
            .filter(
                database.DownloadJob.user_id == user.id,
                database.DownloadJob.target_modern_playlist_id == job.target_modern_playlist_id,
                database.DownloadJob.target_position == job.target_position,
            )
            .order_by(database.DownloadJob.id.desc())
            .first()
        )
        if latest.status not in ("failed", "error"):
            return JSONResponse({"error": "This song has already been retried."}, status_code=409)
        active_jobs = (
            db.query(database.DownloadJob)
            .filter(
                database.DownloadJob.user_id == user.id,
                database.DownloadJob.status.in_(NON_TERMINAL_DOWNLOAD_STATUSES),
            )
            .count()
        )
        if active_jobs >= collection_service.MAX_COLLECTION_TRACKS:
            return JSONResponse({"error": "Your download queue is full."}, status_code=409)

    pool_usage = manager.get_pool_status(db)
    if pool_usage[0] >= pool_usage[1]:
        return JSONResponse(
            {"error": f"Global pool limit reached ({pool_usage[1]}GB). Please delete some tracks."}, status_code=403
        )

    new_job = database.DownloadJob(
        user_id=user.id,
        input_url=job.input_url,
        original_input_url=job.original_input_url or job.input_url,
        requested_title=job.requested_title,
        requested_artist=job.requested_artist,
        requested_album=job.requested_album,
        requested_source=job.requested_source,
        resolution_mode=job.resolution_mode,
        status="pending",
        progress_percent=0.0,
        new_playlist_name=job.new_playlist_name,
        target_modern_playlist_id=job.target_modern_playlist_id,
        target_position=job.target_position,
        current_file="Queued for retry",
    )
    db.add(new_job)
    db.commit()
    db.refresh(new_job)

    _prune_terminal_download_jobs(db, user.id)

    background_tasks.add_task(run_download_in_background, new_job.id, user.id)
    return {"status": "queued", "job_id": new_job.id, "message": "Download requeued"}


@router.get("/api/downloads/status")
async def downloads_status(request: Request, db: Session = Depends(get_db)):
    """Get status of recent downloads"""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse([])

    _prune_terminal_download_jobs(db, user.id)

    jobs = (
        db.query(database.DownloadJob)
        .filter(database.DownloadJob.user_id == user.id)
        .order_by(database.DownloadJob.created_at.desc())
        .limit(10)
        .all()
    )

    target_playlist_ids = {job.target_modern_playlist_id for job in jobs if job.target_modern_playlist_id}
    playlist_names = {}
    if target_playlist_ids:
        playlist_names = {
            row.id: row.name
            for row in db.query(database.Playlist.id, database.Playlist.name)
            .filter(
                database.Playlist.id.in_(target_playlist_ids),
                database.Playlist.owner_id == user.id,
            )
            .all()
        }

    clean_jobs = []
    for job in jobs:
        target = "General"
        if job.new_playlist_name:
            target = f"✨ {job.new_playlist_name}"
        elif job.target_modern_playlist_id:
            playlist_name = playlist_names.get(job.target_modern_playlist_id)
            target = f"📂 {playlist_name}" if playlist_name else f"Playlist #{job.target_modern_playlist_id}"

        current_file = (
            os.path.basename(job.current_file) if job.current_file and "/" in job.current_file else job.current_file
        )

        clean_jobs.append(
            {
                **_serialize_download_job(job, target=target),
                "current_file": current_file,
            }
        )
    return JSONResponse(clean_jobs)


@router.post("/api/downloads/start")
async def start_download(
    request: Request,
    background_tasks: BackgroundTasks,
    url: str = Form(...),
    target_mode: str = Form(...),
    target_playlist_id: int = Form(None),
    new_playlist_name: str = Form(None),
    is_playlist: str = Form("false"),
    db: Session = Depends(get_db),
):
    """Start download from form submission"""
    user = get_current_user_safe(db, request)
    if not user:
        return RedirectResponse("/login")

    _prune_terminal_download_jobs(db, user.id)

    playlist_mode = is_playlist.lower() == "true"

    # --- CRITICAL DESTINATION FIX ---
    if new_playlist_name and new_playlist_name.strip():
        target_mode = "new"

    if playlist_mode and target_mode != "new" and target_mode != "existing":
        target_mode = "new"
        new_playlist_name = "Playlist Importada"  # Security fallback

    # Clean URL if Single
    clean_url = url
    if not playlist_mode and ("youtube.com" in url or "youtu.be" in url):
        parsed = urlparse(url)
        query = parse_qs(parsed.query)
        if "v" in query:
            clean_url = f"https://www.youtube.com/watch?v={query['v'][0]}"
        elif parsed.netloc == "youtu.be":
            clean_url = f"https://www.youtube.com/watch?v={parsed.path.lstrip('/')}"

    # Create Job
    job = database.DownloadJob(
        user_id=user.id,
        input_url=clean_url,
        original_input_url=url,
        requested_source=_infer_source_label(None, url),
        resolution_mode="youtube-clean-url" if clean_url != url else "original",
        status="pending",
    )

    # Assign destination logic
    if target_mode == "existing" and target_playlist_id:
        playlist = (
            db.query(database.Playlist)
            .filter(
                database.Playlist.id == target_playlist_id,
                database.Playlist.owner_id == user.id,
            )
            .first()
        )
        if playlist:
            job.target_modern_playlist_id = playlist.id
    elif target_mode == "new" and new_playlist_name:
        job.new_playlist_name = new_playlist_name

    db.add(job)
    db.commit()
    db.refresh(job)

    background_tasks.add_task(run_download_in_background, job.id, user.id)
    return RedirectResponse("/downloads", status_code=303)


@router.get("/api/jobs")
async def list_download_jobs(request: Request, db: Session = Depends(get_db), playlist_id: int | None = None):
    """Get status of all download jobs for the user"""
    user = get_current_user_safe(db, request)
    if not user:
        return JSONResponse({"error": "Unauthorized"}, status_code=401)

    _prune_terminal_download_jobs(db, user.id)

    if playlist_id is not None:
        jobs = (
            db.query(database.DownloadJob)
            .filter(
                database.DownloadJob.user_id == user.id, database.DownloadJob.target_modern_playlist_id == playlist_id
            )
            .order_by(database.DownloadJob.id)
            .all()
        )
        latest = {}
        for job in jobs:
            latest[job.target_position if job.target_position is not None else f"job:{job.id}"] = job
        return JSONResponse([_serialize_download_job(job) for job in latest.values()])

    # Show in-flight jobs even when a large import has newer queued rows.
    active = (
        db.query(database.DownloadJob)
        .filter(
            database.DownloadJob.user_id == user.id, database.DownloadJob.status.in_(NON_TERMINAL_DOWNLOAD_STATUSES)
        )
        .order_by(database.DownloadJob.id)
        .all()
    )
    jobs = active + (
        db.query(database.DownloadJob)
        .filter(database.DownloadJob.user_id == user.id, database.DownloadJob.status.in_(TERMINAL_DOWNLOAD_STATUSES))
        .order_by(database.DownloadJob.created_at.desc())
        .limit(50)
        .all()
    )

    return JSONResponse([_serialize_download_job(j) for j in jobs])
