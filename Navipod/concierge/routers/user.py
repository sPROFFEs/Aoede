import json
import logging
import os
import shutil

import auth
import database
import spotify_service
from aoede_config import settings
from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from fastapi.responses import RedirectResponse
from lastfm_service import lastfm_service
from secrets_store import ENC_PREFIX
from shared_templates import templates
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/user")

DEFAULT_METADATA_PREFERENCES = ["spotify", "lastfm", "musicbrainz"]
ALLOWED_METADATA_PROVIDERS = set(DEFAULT_METADATA_PREFERENCES)


def get_db():
    db = database.SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_current_user(request: Request, db: Session = Depends(get_db)):
    try:
        return auth.get_current_user(request, db)
    except Exception:
        return None


def ensure_download_settings(db: Session, user: database.User) -> database.DownloadSettings:
    settings = db.query(database.DownloadSettings).filter(database.DownloadSettings.user_id == user.id).first()
    if not settings:
        settings = database.DownloadSettings(user_id=user.id)
        db.add(settings)
        db.commit()
        db.refresh(settings)
    _normalize_secret_storage(db, settings)
    return settings


def _normalize_secret_storage(db: Session, settings: database.DownloadSettings | None) -> None:
    if not settings:
        return

    changed = False
    fields = [
        ("_spotify_client_id", "spotify_client_id"),
        ("_spotify_client_secret", "spotify_client_secret"),
        ("_lastfm_api_key", "lastfm_api_key"),
        ("_lastfm_shared_secret", "lastfm_shared_secret"),
        ("_youtube_cookies", "youtube_cookies"),
    ]

    for raw_attr, public_attr in fields:
        raw_value = getattr(settings, raw_attr, None)
        if raw_value and not str(raw_value).startswith(ENC_PREFIX):
            setattr(settings, public_attr, getattr(settings, public_attr))
            changed = True

    if changed:
        db.commit()


def parse_metadata_preferences(raw_value: str) -> str:
    if not raw_value:
        return json.dumps(DEFAULT_METADATA_PREFERENCES)

    try:
        loaded = json.loads(raw_value)
        if isinstance(loaded, list):
            normalized = [str(v).strip().lower() for v in loaded if str(v).strip()]
            return json.dumps(normalized or DEFAULT_METADATA_PREFERENCES)
    except Exception as e:
        logger.debug("Metadata preferences JSON parse failed; falling back to comma parsing: %s", e)

    normalized = [v.strip().lower() for v in raw_value.split(",") if v.strip()]
    return json.dumps(normalized or DEFAULT_METADATA_PREFERENCES)


def build_metadata_preferences(
    priority_1: str | None,
    priority_2: str | None,
    priority_3: str | None,
    raw_value: str | None,
) -> str:
    ordered = []
    for value in [priority_1, priority_2, priority_3]:
        if not value:
            continue
        provider = value.strip().lower()
        if provider in ALLOWED_METADATA_PROVIDERS and provider not in ordered:
            ordered.append(provider)

    for provider in DEFAULT_METADATA_PREFERENCES:
        if provider not in ordered:
            ordered.append(provider)

    if ordered:
        return json.dumps(ordered)

    return parse_metadata_preferences(raw_value or "")


@router.get("/settings")
async def user_settings(request: Request, db: Session = Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        return RedirectResponse("/login")

    # Get Download Settings
    dl_settings = db.query(database.DownloadSettings).filter(database.DownloadSettings.user_id == user.id).first()
    _normalize_secret_storage(db, dl_settings)

    return templates.TemplateResponse(
        "user_settings.html",
        {
            "request": request,
            "user": user,
            "is_admin": user.is_admin,
            "username": user.username,
            "dl_settings": dl_settings,
        },
    )


@router.post("/update-api-keys")
async def update_api_keys(
    request: Request,
    spotify_client_id: str = Form(None),
    spotify_client_secret: str = Form(None),
    lastfm_api_key: str = Form(None),
    lastfm_shared_secret: str = Form(None),
    metadata_priority_1: str = Form(None),
    metadata_priority_2: str = Form(None),
    metadata_priority_3: str = Form(None),
    metadata_preferences: str = Form(None),
    db: Session = Depends(get_db),
):
    user = get_current_user(request, db)
    if not user:
        return RedirectResponse("/login")

    settings = ensure_download_settings(db, user)

    settings.spotify_client_id = spotify_client_id
    settings.spotify_client_secret = spotify_client_secret
    settings.lastfm_api_key = lastfm_api_key
    settings.lastfm_shared_secret = lastfm_shared_secret
    settings.metadata_preferences = build_metadata_preferences(
        metadata_priority_1,
        metadata_priority_2,
        metadata_priority_3,
        metadata_preferences,
    )

    db.commit()

    return templates.TemplateResponse(
        "user_settings.html",
        {
            "request": request,
            "user": user,
            "success": "Integrations updated",
            "is_admin": user.is_admin,
            "username": user.username,
            "dl_settings": settings,
        },
    )


@router.post("/validate-api-keys")
async def validate_api_keys(
    request: Request,
    spotify_client_id: str = Form(None),
    spotify_client_secret: str = Form(None),
    lastfm_api_key: str = Form(None),
    lastfm_shared_secret: str = Form(None),
    db: Session = Depends(get_db),
):
    user = get_current_user(request, db)
    if not user:
        return RedirectResponse("/login")

    settings = ensure_download_settings(db, user)
    messages = []

    if spotify_client_id and spotify_client_secret:
        ok = await spotify_service.spotify_service.validate_credentials(spotify_client_id, spotify_client_secret)
        messages.append("Spotify OK" if ok else "Spotify failed (401/403 or invalid credentials)")
    else:
        messages.append("Spotify skipped (missing credentials)")

    if lastfm_api_key:
        ok = await lastfm_service.validate_api_key(lastfm_api_key)
        messages.append("Last.fm OK" if ok else "Last.fm failed (invalid/expired API key)")
    else:
        messages.append("Last.fm skipped (missing API key)")

    if lastfm_shared_secret:
        messages.append("Last.fm shared secret saved (used only for signed/user-write methods)")

    return templates.TemplateResponse(
        "user_settings.html",
        {
            "request": request,
            "user": user,
            "success": " | ".join(messages),
            "is_admin": user.is_admin,
            "username": user.username,
            "dl_settings": settings,
        },
    )


@router.post("/upload-cookies")
async def upload_cookies(request: Request, cookies_file: UploadFile = File(...), db: Session = Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        return RedirectResponse("/login")
    settings = ensure_download_settings(db, user)

    try:
        # Save file securely
        user_dir = f"/saas-data/users/{user.username}"
        os.makedirs(user_dir, exist_ok=True)
        file_path = f"{user_dir}/cookies.txt"

        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(cookies_file.file, buffer)

        # Update DB
        settings.youtube_cookies_path = file_path
        settings.youtube_cookies = None
        db.commit()

        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "success": "Cookies uploaded successfully",
                "is_admin": user.is_admin,
                "username": user.username,
                "dl_settings": settings,
            },
        )
    except Exception as e:
        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "error": f"Upload failed: {str(e)}",
                "is_admin": user.is_admin,
                "username": user.username,
                "dl_settings": settings,
            },
        )


@router.post("/change-password")
async def change_password(
    request: Request,
    current_password: str = Form(...),
    new_password: str = Form(...),
    confirm_password: str = Form(...),
    db: Session = Depends(get_db),
):
    user = get_current_user(request, db)
    if not user:
        return RedirectResponse("/login")

    if new_password != confirm_password:
        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "error": "Passwords do not match",
                "is_admin": user.is_admin,
                "username": user.username,
            },
        )

    if not auth.is_password_strong(new_password):
        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "error": "Weak password: use 8 chars, uppercase, lowercase, numbers and symbols",
                "is_admin": user.is_admin,
                "username": user.username,
            },
        )

    if not auth.verify_password(current_password, user.hashed_password):
        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "error": "Incorrect current password",
                "is_admin": user.is_admin,
                "username": user.username,
            },
        )

    # Update password
    user.hashed_password = auth.get_password_hash(new_password)
    user.session_version = int(user.session_version or 0) + 1
    db.commit()

    response = RedirectResponse("/login", status_code=303)
    response.headers["HX-Redirect"] = "/login"
    response.delete_cookie(
        "access_token",
        path="/",
        httponly=True,
        secure=settings.COOKIE_SECURE,
        samesite="lax",
    )
    return response


# --- PROFILE PICTURE / AVATAR ---
import hashlib
import io
import mimetypes
import uuid

from fastapi.responses import FileResponse, Response
from PIL import Image, ImageDraw, ImageOps

try:
    import pillow_heif

    pillow_heif.register_heif_opener()
except ImportError:
    pass

# Prevent decompression bomb DOS attacks
Image.MAX_IMAGE_PIXELS = 25_000_000

# Allowed image extensions
ALLOWED_IMAGE_EXTENSIONS = {
    "jpg",
    "jpeg",
    "jpe",
    "jfif",
    "png",
    "apng",
    "webp",
    "gif",
    "bmp",
    "dib",
    "tif",
    "tiff",
    "avif",
    "avifs",
    "heic",
    "heif",
    "hif",
    "ico",
}

# Allowed image content types
ALLOWED_IMAGE_TYPES = {
    "image/jpeg",
    "image/pjpeg",
    "image/jpg",
    "image/png",
    "image/x-png",
    "image/apng",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/x-bmp",
    "image/x-ms-bmp",
    "image/tiff",
    "image/x-tiff",
    "image/avif",
    "image/avif-sequence",
    "image/heic",
    "image/heif",
    "image/heic-sequence",
    "image/heif-sequence",
    "image/x-icon",
    "image/vnd.microsoft.icon",
    "image/ico",
    "image/icon",
}
MAX_AVATAR_SIZE = 5 * 1024 * 1024  # 5MB max
AVATAR_OUTPUT_SIZE = (256, 256)  # Resize to this for storage efficiency


def detect_image_format(content: bytes) -> str | None:
    """Detect image format from file signature (magic bytes)."""
    if len(content) < 4:
        return None

    # JPEG: FF D8 FF
    if content.startswith(b"\xff\xd8\xff"):
        return "jpeg"

    # PNG: \x89PNG
    if content.startswith(b"\x89PNG"):
        return "png"

    # GIF: GIF87a or GIF89a
    if content.startswith(b"GIF87a") or content.startswith(b"GIF89a"):
        return "gif"

    # WebP: RIFF....WEBP
    if len(content) >= 12 and content.startswith(b"RIFF") and content[8:12] == b"WEBP":
        return "webp"

    # BMP: BM
    if content.startswith(b"BM"):
        return "bmp"

    # TIFF: II*\x00 (little-endian) or MM\x00* (big-endian)
    if content.startswith(b"II*\x00") or content.startswith(b"MM\x00*"):
        return "tiff"

    # ICO: \x00\x00\x01\x00 (ICO) or \x00\x00\x02\x00 (CUR)
    if content.startswith(b"\x00\x00\x01\x00") or content.startswith(b"\x00\x00\x02\x00"):
        return "ico"

    # AVIF / HEIF: ISOBMFF ftyp box at offset 4
    if len(content) >= 12 and content[4:8] == b"ftyp":
        brand = content[8:12].lower()
        header_chunk = content[8 : min(len(content), 64)].lower()
        if brand in (b"avif", b"avis") or b"avif" in header_chunk or b"avis" in header_chunk:
            return "avif"
        if brand in (b"heic", b"heix", b"hevc", b"heim", b"heis", b"mif1", b"msf1") or b"heic" in header_chunk:
            return "heif"

    return None


def validate_image_file(file_content: bytes, content_type: str | None = None) -> bool:
    """Validate image using magic bytes (file signature) and optional content-type."""
    detected = detect_image_format(file_content)
    if not detected:
        return False

    if not content_type:
        return True

    normalized_type = content_type.lower().split(";")[0].strip()
    if normalized_type in ("application/octet-stream", "image/*", ""):
        return True

    return normalized_type in ALLOWED_IMAGE_TYPES


@router.post("/upload-avatar")
async def upload_avatar(request: Request, avatar_file: UploadFile = File(...), db: Session = Depends(get_db)):
    """Secure avatar upload with exhaustive validation and enhanced format compatibility"""
    user = get_current_user(request, db)
    if not user:
        return RedirectResponse("/login")

    dl_settings = db.query(database.DownloadSettings).filter(database.DownloadSettings.user_id == user.id).first()

    try:
        # Read file content
        content = await avatar_file.read()

        # Size check
        if len(content) > MAX_AVATAR_SIZE:
            return templates.TemplateResponse(
                "user_settings.html",
                {
                    "request": request,
                    "user": user,
                    "error": f"Image too large. Max size: {MAX_AVATAR_SIZE // (1024 * 1024)}MB",
                    "is_admin": user.is_admin,
                    "username": user.username,
                    "dl_settings": dl_settings,
                },
            )

        # Extension check
        filename = avatar_file.filename or ""
        ext = filename.lower().split(".")[-1] if "." in filename else ""
        if ext not in ALLOWED_IMAGE_EXTENSIONS:
            return templates.TemplateResponse(
                "user_settings.html",
                {
                    "request": request,
                    "user": user,
                    "error": "Invalid file type. Allowed: JPG, PNG, WEBP, GIF, BMP, TIFF, AVIF, HEIC, ICO",
                    "is_admin": user.is_admin,
                    "username": user.username,
                    "dl_settings": dl_settings,
                },
            )

        # Content-Type / Magic bytes validation
        content_type = avatar_file.content_type or ""
        if not validate_image_file(content, content_type):
            return templates.TemplateResponse(
                "user_settings.html",
                {
                    "request": request,
                    "user": user,
                    "error": "File content does not match allowed image format",
                    "is_admin": user.is_admin,
                    "username": user.username,
                    "dl_settings": dl_settings,
                },
            )

        # Process and resize image with Pillow (also validates it's a real image)
        try:
            with Image.open(io.BytesIO(content)) as img:
                # Correct EXIF orientation (e.g. mobile photo uploads)
                img = ImageOps.exif_transpose(img) or img

                # For multi-frame images (GIF, multi-page TIFF, animated WebP), use the first frame
                if getattr(img, "is_animated", False):
                    try:
                        img.seek(0)
                    except Exception:
                        pass

                # Preserve transparency when possible or normalize color space
                if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
                    img = img.convert("RGBA")
                elif img.mode == "CMYK":
                    img = img.convert("RGB")
                elif img.mode not in ("RGB", "RGBA"):
                    img = img.convert("RGB")

                # Center-crop & fit to square output size for clean profile avatar display
                img = ImageOps.fit(img, AVATAR_OUTPUT_SIZE, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))
                img.load()

                # Generate unique filename
                unique_id = uuid.uuid4().hex[:8]
                avatar_filename = f"avatar_{user.id}_{unique_id}.webp"

                # Save to user directory
                user_dir = f"{settings.MUSIC_ROOT}/{user.username}"
                os.makedirs(user_dir, exist_ok=True)
                avatar_path = f"{user_dir}/{avatar_filename}"

                # Remove old avatar if exists
                if user.avatar_path and os.path.exists(user.avatar_path):
                    try:
                        os.remove(user.avatar_path)
                    except OSError as e:
                        logger.warning("Failed to remove previous avatar for user %s: %s", user.username, e)

                # Save as WebP for optimal size and broad compatibility
                img.save(avatar_path, "WEBP", quality=85, method=4)

        except Exception as e:
            logger.warning("Failed to process avatar image: %s", e)
            return templates.TemplateResponse(
                "user_settings.html",
                {
                    "request": request,
                    "user": user,
                    "error": "Failed to process image: Invalid or corrupt file",
                    "is_admin": user.is_admin,
                    "username": user.username,
                    "dl_settings": dl_settings,
                },
            )

        # Update DB
        user.avatar_path = avatar_path
        db.commit()

        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "success": "Avatar updated successfully!",
                "is_admin": user.is_admin,
                "username": user.username,
                "dl_settings": dl_settings,
            },
        )

    except Exception as e:
        logger.warning("Avatar upload error: %s", e)
        return templates.TemplateResponse(
            "user_settings.html",
            {
                "request": request,
                "user": user,
                "error": f"Upload failed: {str(e)}",
                "is_admin": user.is_admin,
                "username": user.username,
                "dl_settings": dl_settings,
            },
        )


@router.get("/set-language")
@router.post("/set-language")
async def set_language(lang: str, request: Request):
    """Set preferred user language in a cookie."""
    import i18n

    if lang not in i18n.SUPPORTED_LANGS:
        lang = i18n.DEFAULT_LANG
    referer = request.headers.get("referer") or "/portal"
    response = RedirectResponse(referer, status_code=303)
    response.set_cookie(
        "lang",
        lang,
        max_age=365 * 24 * 60 * 60,
        path="/",
        samesite="lax",
    )
    return response


@router.get("/avatar/{username}")
async def get_avatar(username: str, db: Session = Depends(get_db)):
    """Serve user avatar or default"""
    user = db.query(database.User).filter(database.User.username == username).first()

    if user and user.avatar_path and os.path.exists(user.avatar_path):
        media_type, _ = mimetypes.guess_type(user.avatar_path)
        return FileResponse(
            user.avatar_path,
            media_type=media_type or "image/webp",
            headers={"Cache-Control": "public, max-age=3600"},  # Cache 1 hour
        )

    # Return default avatar
    default_avatar = "/saas-data/default_avatar.webp"
    if os.path.exists(default_avatar):
        return FileResponse(default_avatar, media_type="image/webp")

    # Fallback: Generate a simple colored avatar
    # Use username hash for consistent color
    color_hash = int(hashlib.md5(username.encode()).hexdigest()[:6], 16)
    r = (color_hash >> 16) & 0xFF
    g = (color_hash >> 8) & 0xFF
    b = color_hash & 0xFF

    img = Image.new("RGB", (128, 128), (r, g, b))
    draw = ImageDraw.Draw(img)

    # Draw first letter centered
    letter = username[0].upper() if username else "?"
    bbox = draw.textbbox((0, 0), letter)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    x = (128 - text_w) / 2 - bbox[0]
    y = (128 - text_h) / 2 - bbox[1]
    draw.text((x, y), letter, fill="white")

    img_io = io.BytesIO()
    img.save(img_io, format="WEBP", quality=80)
    img_io.seek(0)

    return Response(
        content=img_io.getvalue(), media_type="image/webp", headers={"Cache-Control": "public, max-age=3600"}
    )


@router.get("/profile/{username}")
async def get_public_profile(username: str, request: Request, db: Session = Depends(get_db)):
    """Serve public user profile with shared playlists, favorites, statistics, and avatar."""
    current_user = get_current_user(request, db)
    if not current_user:
        return RedirectResponse("/login")

    target_user = (
        db.query(database.User)
        .filter(
            database.User.username == username,
            database.User.is_active == True,
            database.User.is_service_account == False,
        )
        .first()
    )
    if not target_user:
        return {"error": "User not found"}

    from routers.music.playlists import fetch_playlist_summaries

    # 1. Public Playlists
    public_playlists = fetch_playlist_summaries(
        db,
        viewer_id=current_user.id,
        owner_id=target_user.id,
        public_only=True if target_user.id != current_user.id else False,
    )

    # 2. Public Favorites
    fav_rows = (
        db.query(database.UserFavorite, database.Track)
        .join(database.Track, database.UserFavorite.track_id == database.Track.id)
        .filter(database.UserFavorite.user_id == target_user.id)
        .order_by(database.UserFavorite.id.desc())
        .limit(100)
        .all()
    )

    favorites = [
        {
            "id": track.id,
            "db_id": track.id,
            "title": track.title or "Unknown Title",
            "artist": track.artist or "Unknown Artist",
            "album": track.album or "",
            "duration": track.duration or 0,
            "thumbnail": f"/api/cover/{track.id}" if track.id else "/static/img/default_cover.png",
            "is_local": True,
            "source": "local",
        }
        for _, track in fav_rows
    ]

    # 3. Listening statistics & top artists
    import admin_statistics_service

    rows, _ = admin_statistics_service._read_user_activity(target_user.username, None)
    total_listens = sum(int(r.get("qualified_listens") or 0) for r in rows)
    total_listening_seconds = sum(int(r.get("listening_seconds") or 0) for r in rows)

    # Top artists from activity and favorites
    artist_counts: dict[str, int] = {}
    for r in rows:
        track_id = int(r.get("track_id") or 0)
        track = db.query(database.Track).filter(database.Track.id == track_id).first()
        if track and track.artist:
            artist_counts[track.artist] = artist_counts.get(track.artist, 0) + int(r.get("qualified_listens") or 1)

    for _, track in fav_rows:
        if track.artist and track.artist not in artist_counts:
            artist_counts[track.artist] = 1

    top_artists = [
        {"artist": artist, "count": count}
        for artist, count in sorted(artist_counts.items(), key=lambda x: x[1], reverse=True)[:10]
    ]

    return {
        "username": target_user.username,
        "is_admin": bool(target_user.is_admin),
        "is_self": bool(target_user.id == current_user.id),
        "avatar_url": f"/user/avatar/{target_user.username}",
        "stats": {
            "total_listens": total_listens,
            "listening_minutes": round(total_listening_seconds / 60),
            "favorites_count": len(fav_rows),
            "public_playlists_count": len(public_playlists),
            "top_artists": top_artists,
        },
        "public_playlists": public_playlists,
        "favorites": favorites,
    }


@router.get("/list")
async def list_public_users(request: Request, db: Session = Depends(get_db)):
    """List all active server users with public summary."""
    current_user = get_current_user(request, db)
    if not current_user:
        return RedirectResponse("/login")

    users = (
        db.query(database.User)
        .filter(
            database.User.is_active == True,
            database.User.is_service_account == False,
        )
        .order_by(database.User.username.asc())
        .all()
    )

    import admin_statistics_service

    from routers.music.playlists import fetch_playlist_summaries

    result = []
    for u in users:
        public_playlists = fetch_playlist_summaries(
            db, viewer_id=current_user.id, owner_id=u.id, public_only=True if u.id != current_user.id else False
        )
        fav_count = db.query(database.UserFavorite).filter(database.UserFavorite.user_id == u.id).count()
        rows, _ = admin_statistics_service._read_user_activity(u.username, None)
        total_listens = sum(int(r.get("qualified_listens") or 0) for r in rows)

        result.append(
            {
                "username": u.username,
                "avatar_url": f"/user/avatar/{u.username}",
                "is_admin": bool(u.is_admin),
                "is_self": bool(u.id == current_user.id),
                "public_playlists_count": len(public_playlists),
                "favorites_count": fav_count,
                "total_listens": total_listens,
            }
        )

    return result
