"""Versioned native-client context. Mutations keep the existing session and CSRF boundary."""

import hashlib
import json
from typing import Literal

import auth
import auth_browser
import database
import downloader_worker_client
from aoede_config import settings as config
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, Response
from limiter import limiter
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from .user import ensure_download_settings, get_db

router = APIRouter(prefix="/api/mobile/v2", tags=["native mobile"])
PRIVATE = {"Cache-Control": "private, no-store", "Vary": "Cookie, Accept-Language"}


class Preferences(BaseModel):
    model_config = ConfigDict(extra="forbid")
    theme: Literal["classic", "glass", "vitrine"] | None = None
    language: Literal["en", "es", "de"] | None = None
    metadata_preferences: list[Literal["spotify", "lastfm", "musicbrainz"]] | None = Field(
        default=None, min_length=1, max_length=3
    )


class Integrations(BaseModel):
    model_config = ConfigDict(extra="forbid")
    spotify_client_id: str | None = Field(default=None, max_length=512)
    spotify_client_secret: str | None = Field(default=None, max_length=512)
    lastfm_api_key: str | None = Field(default=None, max_length=512)
    lastfm_shared_secret: str | None = Field(default=None, max_length=512)


def current_user(request: Request, db: Session = Depends(get_db)):
    # No alternate token, service-account exception, or client-controlled user id.
    return auth.get_current_user(request, db)


def current_admin(user: database.User = Depends(current_user)):
    if not user.is_admin:
        raise HTTPException(403, "Administrator access required")
    return user


def preferences(user, db):
    integrations = db.query(database.DownloadSettings).filter_by(user_id=user.id).first()
    try:
        priority = json.loads(integrations.metadata_preferences) if integrations else []
    except (ValueError, TypeError):
        priority = []
    return {
        "theme": user.theme or "classic",
        "metadata_preferences": priority or ["spotify", "lastfm", "musicbrainz"],
        "integrations": {
            # Deliberately return presence, never decrypted credentials/cookies or disk paths.
            name: bool(getattr(integrations, "_" + name, None))
            for name in ("spotify_client_id", "spotify_client_secret", "lastfm_api_key", "lastfm_shared_secret")
        },
    }


@router.get("/context")
def context(request: Request, user: database.User = Depends(current_user)):
    # Namespace only; this value is never accepted as authorization. Keep it stable
    # through password/key rotation and change it when an account is recreated.
    if not user.native_account_id:
        raise HTTPException(503, "Account migration is pending")
    scope = hashlib.sha256(f"native-v2:{user.native_account_id}".encode()).hexdigest()
    return JSONResponse(
        {
            "protocol": 2,
            "account": {"id": user.id, "username": user.username, "is_admin": bool(user.is_admin)},
            "storage_scope": scope,
            "theme": user.theme or "classic",
            "language": request.cookies.get("lang", "en")
            if request.cookies.get("lang", "en") in {"en", "es", "de"}
            else "en",
            "capabilities": ["native_context_v2", "private_settings", "existing_cookie_auth", "federated_collections"],
        },
        headers=PRIVATE,
    )


@router.get("/settings")
def get_preferences(request: Request, user: database.User = Depends(current_user), db: Session = Depends(get_db)):
    data = preferences(user, db)
    data["language"] = (
        request.cookies.get("lang", "en") if request.cookies.get("lang", "en") in {"en", "es", "de"} else "en"
    )
    return JSONResponse(data, headers=PRIVATE)


@router.patch("/settings")
def update_preferences(body: Preferences, user: database.User = Depends(current_user), db: Session = Depends(get_db)):
    if body.theme is not None:
        user.theme = body.theme
    if body.metadata_preferences is not None:
        if len(set(body.metadata_preferences)) != len(body.metadata_preferences):
            raise HTTPException(422, "Providers must be distinct")
        ensure_download_settings(db, user).metadata_preferences = json.dumps(body.metadata_preferences)
    db.commit()
    response = JSONResponse(preferences(user, db), headers=PRIVATE)
    if body.language:
        response.set_cookie("lang", body.language, samesite="lax", secure=config.COOKIE_SECURE, max_age=31536000)
    return response


@router.put("/integrations")
def update_integrations(body: Integrations, user: database.User = Depends(current_user), db: Session = Depends(get_db)):
    record = ensure_download_settings(db, user)
    for field in body.model_fields_set:
        setattr(record, field, getattr(body, field) or None)
    db.commit()
    return JSONResponse(preferences(user, db), headers=PRIVATE)


@router.get("/admin/users")
def users(admin: database.User = Depends(current_admin), db: Session = Depends(get_db)):
    import security

    blocks = security.get_login_blocks()
    return JSONResponse(
        {
            "users": [
                {
                    "id": u.id,
                    "username": u.username,
                    "is_admin": bool(u.is_admin),
                    "is_active": bool(u.is_active),
                    "login_lock": blocks.get(u.username, {"blocked": False, "remaining_seconds": 0}),
                }
                for u in db.query(database.User)
                .filter(database.User.is_service_account.is_(False))
                .order_by(database.User.username)
            ]
        },
        headers=PRIVATE,
    )


@router.get("/admin/system")
def system(admin: database.User = Depends(current_admin), db: Session = Depends(get_db)):
    import operations_service
    import wrapped_service

    def public_fields(value, keys):
        return {key: value.get(key) for key in keys if key in value}

    build = public_fields(
        operations_service.get_build_info(), ("version", "commit", "full_commit", "branch", "build_date")
    )
    backups = operations_service.get_backup_state(db)
    for slot in ("current", "previous"):
        if isinstance(backups.get(slot), dict):
            backups[slot] = public_fields(
                backups[slot], ("slot", "filename", "size_bytes", "size_label", "created_at", "exists")
            )
    updates = public_fields(
        operations_service.get_update_state(db),
        ("status", "message", "checked_at", "behind_count", "ahead_count", "update_available"),
    )
    jobs = [
        public_fields(job, ("id", "job_type", "status", "message", "started_at", "finished_at"))
        for job in operations_service.get_recent_admin_jobs(db, limit=8)
    ]
    return JSONResponse(
        jsonable_encoder(
            {
                "build": build,
                "backups": backups,
                "updates": updates,
                "wrapped": wrapped_service.get_wrapped_settings(db),
                "jobs": jobs,
            }
        ),
        headers=PRIVATE,
    )


@router.get("/admin/delete-requests")
def delete_requests(admin: database.User = Depends(current_admin), db: Session = Depends(get_db)):
    rows = (
        db.query(database.TrackDeleteRequest, database.User.username)
        .join(database.User, database.User.id == database.TrackDeleteRequest.user_id)
        .filter(database.TrackDeleteRequest.status == "pending")
        .order_by(database.TrackDeleteRequest.id)
        .limit(200)
        .all()
    )
    return JSONResponse(
        [
            {
                "id": row.id,
                "track_id": row.track_id,
                "track_title": row.track_title,
                "track_artist": row.track_artist,
                "requester": username,
                "reason": row.reason or "",
            }
            for row, username in rows
        ],
        headers=PRIVATE,
    )


class BrowserInput(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    action: Literal["click", "text", "key", "scroll"]
    x: float = Field(default=0, ge=0, le=2560)
    y: float = Field(default=0, ge=0, le=1600)
    delta: float = Field(default=0, ge=-1200, le=1200)
    text: str = Field(default="", max_length=2048)
    key: Literal["", "Enter", "Tab", "Backspace", "Escape", "ArrowLeft", "ArrowUp", "ArrowRight", "ArrowDown"] = ""


def owned_browser(session_id: str, admin):
    if not auth_browser.owned_by(session_id, int(admin.id)):
        raise HTTPException(403, "Browser session is unavailable for this administrator")


@router.get("/admin/browser/{session_id}/frame")
@limiter.limit("120/minute")
def browser_frame(session_id: str, request: Request, admin: database.User = Depends(current_admin)):
    owned_browser(session_id, admin)
    try:
        data = downloader_worker_client.native_browser_frame(session_id)
    except Exception as exc:
        raise HTTPException(503, "Browser frame is not ready") from exc
    return Response(data, media_type="image/jpeg", headers={**PRIVATE, "Cross-Origin-Resource-Policy": "same-origin"})


@router.post("/admin/browser/{session_id}/input")
@limiter.limit("180/minute")
def browser_input(session_id: str, body: BrowserInput, request: Request, admin: database.User = Depends(current_admin)):
    owned_browser(session_id, admin)
    try:
        return JSONResponse(
            downloader_worker_client.native_browser_input(session_id, body.model_dump()), headers=PRIVATE
        )
    except Exception as exc:
        raise HTTPException(503, "Browser input unavailable") from exc
