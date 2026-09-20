import io
from pathlib import Path

import database
import pytest
from aoede_config import settings
from PIL import Image
from routers.user import (
    ALLOWED_IMAGE_EXTENSIONS,
    ALLOWED_IMAGE_TYPES,
    AVATAR_OUTPUT_SIZE,
    detect_image_format,
    get_avatar,
    get_public_profile,
    list_public_users,
    upload_avatar,
    validate_image_file,
)
from starlette.datastructures import UploadFile
from starlette.requests import Request


@pytest.fixture
def anyio_backend():
    return "asyncio"


def create_test_image(format_name: str, size=(100, 100), mode="RGB", color=(120, 150, 200)):
    buf = io.BytesIO()
    if format_name.upper() == "GIF":
        # Multi-frame animated gif test
        im1 = Image.new("RGB", size, color)
        im2 = Image.new("RGB", size, (200, 100, 50))
        im1.save(buf, format="GIF", save_all=True, append_images=[im2])
    elif format_name.upper() == "ICO":
        im = Image.new("RGBA", (32, 32), (100, 150, 200, 255))
        im.save(buf, format="ICO")
    elif mode == "RGBA":
        im = Image.new("RGBA", size, (*color[:3], 128))
        im.save(buf, format=format_name)
    else:
        im = Image.new(mode, size, color)
        im.save(buf, format=format_name)
    return buf.getvalue()


def test_allowed_extensions_and_types():
    assert "jpg" in ALLOWED_IMAGE_EXTENSIONS
    assert "jpeg" in ALLOWED_IMAGE_EXTENSIONS
    assert "png" in ALLOWED_IMAGE_EXTENSIONS
    assert "webp" in ALLOWED_IMAGE_EXTENSIONS
    assert "gif" in ALLOWED_IMAGE_EXTENSIONS
    assert "bmp" in ALLOWED_IMAGE_EXTENSIONS
    assert "tiff" in ALLOWED_IMAGE_EXTENSIONS
    assert "avif" in ALLOWED_IMAGE_EXTENSIONS
    assert "ico" in ALLOWED_IMAGE_EXTENSIONS
    assert "heic" in ALLOWED_IMAGE_EXTENSIONS

    assert "image/jpeg" in ALLOWED_IMAGE_TYPES
    assert "image/png" in ALLOWED_IMAGE_TYPES
    assert "image/webp" in ALLOWED_IMAGE_TYPES
    assert "image/gif" in ALLOWED_IMAGE_TYPES
    assert "image/bmp" in ALLOWED_IMAGE_TYPES
    assert "image/tiff" in ALLOWED_IMAGE_TYPES
    assert "image/avif" in ALLOWED_IMAGE_TYPES
    assert "image/x-icon" in ALLOWED_IMAGE_TYPES


def test_detect_image_format_and_validation():
    # JPEG
    jpeg_data = create_test_image("JPEG")
    assert detect_image_format(jpeg_data) == "jpeg"
    assert validate_image_file(jpeg_data, "image/jpeg") is True
    assert validate_image_file(jpeg_data, "application/octet-stream") is True

    # PNG
    png_data = create_test_image("PNG")
    assert detect_image_format(png_data) == "png"
    assert validate_image_file(png_data, "image/png") is True

    # WebP
    webp_data = create_test_image("WEBP")
    assert detect_image_format(webp_data) == "webp"
    assert validate_image_file(webp_data, "image/webp") is True

    # GIF
    gif_data = create_test_image("GIF")
    assert detect_image_format(gif_data) == "gif"
    assert validate_image_file(gif_data, "image/gif") is True

    # BMP
    bmp_data = create_test_image("BMP")
    assert detect_image_format(bmp_data) == "bmp"
    assert validate_image_file(bmp_data, "image/bmp") is True

    # TIFF
    tiff_data = create_test_image("TIFF")
    assert detect_image_format(tiff_data) == "tiff"
    assert validate_image_file(tiff_data, "image/tiff") is True

    # AVIF
    avif_data = create_test_image("AVIF")
    assert detect_image_format(avif_data) == "avif"
    assert validate_image_file(avif_data, "image/avif") is True

    # ICO
    ico_data = create_test_image("ICO")
    assert detect_image_format(ico_data) == "ico"
    assert validate_image_file(ico_data, "image/x-icon") is True

    # Invalid / Corrupt / Text
    invalid_data = b"<!DOCTYPE html><html><body>Not an image</body></html>"
    assert detect_image_format(invalid_data) is None
    assert validate_image_file(invalid_data, "image/png") is False
    assert validate_image_file(b"", "image/png") is False


@pytest.mark.anyio
async def test_upload_avatar_formats(db_session, monkeypatch, tmp_path):
    user = database.User(id=1, username="bob", hashed_password="pw", is_active=True)
    db_session.add(user)
    db_session.commit()

    monkeypatch.setattr("routers.user.get_current_user", lambda req, db: user)
    monkeypatch.setattr(settings, "MUSIC_ROOT", str(tmp_path / "users"))

    test_formats = [
        ("avatar.jpg", "image/jpeg", create_test_image("JPEG")),
        ("avatar.png", "image/png", create_test_image("PNG", mode="RGBA")),
        ("avatar.webp", "image/webp", create_test_image("WEBP")),
        ("avatar.gif", "image/gif", create_test_image("GIF")),
        ("avatar.bmp", "image/bmp", create_test_image("BMP")),
        ("avatar.tiff", "image/tiff", create_test_image("TIFF")),
        ("avatar.avif", "image/avif", create_test_image("AVIF")),
        ("avatar.ico", "image/x-icon", create_test_image("ICO")),
    ]

    for filename, content_type, data in test_formats:
        upload_file = UploadFile(
            filename=filename,
            file=io.BytesIO(data),
            headers={"content-type": content_type},
        )
        request = Request({"type": "http", "method": "POST", "path": "/user/upload-avatar", "headers": []})

        response = await upload_avatar(request, upload_file, db_session)
        assert response.status_code == 200
        assert user.avatar_path is not None
        assert Path(user.avatar_path).exists()

        # Verify saved output is a valid 256x256 WebP image
        with Image.open(user.avatar_path) as saved_img:
            assert saved_img.format == "WEBP"
            assert saved_img.size == AVATAR_OUTPUT_SIZE


@pytest.mark.anyio
async def test_upload_avatar_crops_non_square_to_exact_square(db_session, monkeypatch, tmp_path):
    user = database.User(id=2, username="charlie", hashed_password="pw", is_active=True)
    db_session.add(user)
    db_session.commit()

    monkeypatch.setattr("routers.user.get_current_user", lambda req, db: user)
    monkeypatch.setattr(settings, "MUSIC_ROOT", str(tmp_path / "users"))

    # 16:9 widescreen image
    wide_img_data = create_test_image("JPEG", size=(640, 360))
    upload_file = UploadFile(
        filename="photo.jpg",
        file=io.BytesIO(wide_img_data),
        headers={"content-type": "image/jpeg"},
    )
    request = Request({"type": "http", "method": "POST", "path": "/user/upload-avatar", "headers": []})

    response = await upload_avatar(request, upload_file, db_session)
    assert response.status_code == 200
    assert user.avatar_path is not None
    assert Path(user.avatar_path).exists()

    with Image.open(user.avatar_path) as saved_img:
        assert saved_img.format == "WEBP"
        assert saved_img.size == (256, 256)


@pytest.mark.anyio
async def test_upload_avatar_exif_transposition(db_session, monkeypatch, tmp_path):
    user = database.User(id=3, username="dana", hashed_password="pw", is_active=True)
    db_session.add(user)
    db_session.commit()

    monkeypatch.setattr("routers.user.get_current_user", lambda req, db: user)
    monkeypatch.setattr(settings, "MUSIC_ROOT", str(tmp_path / "users"))

    # Create an image with EXIF orientation
    im = Image.new("RGB", (200, 100), (50, 100, 150))
    exif = im.getexif()
    exif[0x0112] = 6  # Orientation: 6 = 90 deg CW
    buf = io.BytesIO()
    im.save(buf, format="JPEG", exif=exif)

    upload_file = UploadFile(
        filename="portrait.jpg",
        file=io.BytesIO(buf.getvalue()),
        headers={"content-type": "image/jpeg"},
    )
    request = Request({"type": "http", "method": "POST", "path": "/user/upload-avatar", "headers": []})

    response = await upload_avatar(request, upload_file, db_session)
    assert response.status_code == 200
    assert user.avatar_path is not None


@pytest.mark.anyio
async def test_upload_avatar_rejections(db_session, monkeypatch):
    user = database.User(id=4, username="eve", hashed_password="pw", is_active=True)
    db_session.add(user)
    db_session.commit()

    monkeypatch.setattr("routers.user.get_current_user", lambda req, db: user)
    request = Request({"type": "http", "method": "POST", "path": "/user/upload-avatar", "headers": []})

    # 1. Invalid extension
    upload_file = UploadFile(
        filename="exploit.exe",
        file=io.BytesIO(b"MZ\x90\x00"),
        headers={"content-type": "application/x-msdownload"},
    )
    resp = await upload_avatar(request, upload_file, db_session)
    assert resp.status_code == 200
    assert "Invalid file type" in resp.body.decode()

    # 2. Fake image with PNG extension but text content
    upload_file = UploadFile(
        filename="fake.png",
        file=io.BytesIO(b"not a png image file at all"),
        headers={"content-type": "image/png"},
    )
    resp = await upload_avatar(request, upload_file, db_session)
    assert resp.status_code == 200
    assert "File content does not match" in resp.body.decode()

    # 3. Oversized image (>5MB)
    huge_data = b"x" * (6 * 1024 * 1024)
    upload_file = UploadFile(
        filename="huge.jpg",
        file=io.BytesIO(huge_data),
        headers={"content-type": "image/jpeg"},
    )
    resp = await upload_avatar(request, upload_file, db_session)
    assert resp.status_code == 200
    assert "Image too large" in resp.body.decode()


@pytest.mark.anyio
async def test_get_avatar_fallback_and_existing(db_session, tmp_path):
    user = database.User(id=5, username="frank", hashed_password="pw", is_active=True)
    db_session.add(user)
    db_session.commit()

    # When no avatar uploaded: fallback generates dynamic image
    resp = await get_avatar("frank", db_session)
    assert resp.media_type == "image/webp"
    assert len(resp.body) > 0

    # Open fallback generated avatar with PIL
    with Image.open(io.BytesIO(resp.body)) as img:
        assert img.format == "WEBP"
        assert img.size == (128, 128)

    # When avatar file exists on disk
    avatar_file = tmp_path / "frank_avatar.webp"
    Image.new("RGB", (256, 256), (10, 20, 30)).save(str(avatar_file), "WEBP")
    user.avatar_path = str(avatar_file)
    db_session.commit()

    file_resp = await get_avatar("frank", db_session)
    assert file_resp.media_type == "image/webp"
    assert file_resp.path == str(avatar_file)


@pytest.mark.anyio
async def test_get_public_profile(db_session, monkeypatch):
    user_a = database.User(id=10, username="user_a", hashed_password="pw", is_active=True)
    user_b = database.User(id=11, username="user_b", hashed_password="pw", is_active=True)
    db_session.add(user_a)
    db_session.add(user_b)

    # Add public playlist for user_b
    pl = database.Playlist(id=100, name="Cool Beats", owner_id=user_b.id, is_public=True)
    db_session.add(pl)

    # Add favorite for user_b
    tr = database.Track(id=200, title="Song 1", artist="Artist 1", duration=180, filepath="/fake/song.mp3")
    db_session.add(tr)
    fav = database.UserFavorite(id=300, user_id=user_b.id, track_id=tr.id)
    db_session.add(fav)
    db_session.commit()

    monkeypatch.setattr("routers.user.get_current_user", lambda req, db: user_a)
    request = Request({"type": "http", "method": "GET", "path": "/user/profile/user_b", "headers": []})

    profile = await get_public_profile("user_b", request, db_session)
    assert profile["username"] == "user_b"
    assert profile["is_self"] is False
    assert len(profile["public_playlists"]) == 1
    assert profile["public_playlists"][0]["name"] == "Cool Beats"
    assert len(profile["favorites"]) == 1
    assert profile["favorites"][0]["title"] == "Song 1"
    assert profile["stats"]["favorites_count"] == 1


@pytest.mark.anyio
async def test_list_public_users(db_session, monkeypatch):
    user_a = database.User(id=20, username="alice", hashed_password="pw", is_active=True)
    user_b = database.User(id=21, username="bob", hashed_password="pw", is_active=True)
    service_u = database.User(id=22, username="svc", hashed_password="pw", is_active=True, is_service_account=True)
    db_session.add_all([user_a, user_b, service_u])
    db_session.commit()

    monkeypatch.setattr("routers.user.get_current_user", lambda req, db: user_a)
    request = Request({"type": "http", "method": "GET", "path": "/user/list", "headers": []})

    users = await list_public_users(request, db_session)
    usernames = [u["username"] for u in users]
    assert "alice" in usernames
    assert "bob" in usernames
    assert "svc" not in usernames
