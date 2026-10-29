#!/usr/bin/env python3
"""Loopback-only synthetic data for comparing the real mobile web UI and native UI.

Run with the repository Python environment. No production accounts or services
are used. Open http://127.0.0.1:8765/?theme=classic (also glass/vitrine).
"""

import io
import json
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

from jinja2 import Environment, FileSystemLoader, select_autoescape
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "Aoede" if (ROOT / "Aoede").is_dir() else Path(__file__).parent / "frontend-source"
EN = json.loads((WEB / "concierge/locales/en.json").read_text())
TEMPLATES = Environment(loader=FileSystemLoader(WEB / "concierge/templates"), autoescape=select_autoescape())
TEMPLATES.globals.update(
    _=lambda key: EN.get(key, key), current_lang=lambda: "en", get_all_translations=lambda: EN, static_v="visual"
)
TRACKS = [
    {
        "id": i,
        "db_id": i,
        "title": title,
        "artist": artist,
        "album": album,
        "duration": 240,
        "thumbnail": f"/api/cover/{i}",
        "source": "local",
        "is_favorite": i == 1,
    }
    for i, (title, artist, album) in enumerate(
        [
            ("Midnight City", "M83", "Hurry Up, We're Dreaming"),
            ("Something About Us", "Daft Punk", "Discovery"),
            ("Pink + White", "Frank Ocean", "Blonde"),
            ("Space Song", "Beach House", "Depression Cherry"),
            ("Nights", "Frank Ocean", "Blonde"),
            ("Tadow", "Masego & FKJ", "Lady Lady"),
        ],
        1,
    )
]
COLLECTIONS = [
    {
        "id": i,
        "name": name,
        "kind": kind,
        "track_count": 6,
        "tracks": TRACKS,
        "thumbnail": f"/fixture/art/{i}.png",
        "owner_username": "visual",
        "is_owner": True,
        "is_public": True,
        "is_collaborative": i == 2,
        "description": "A soundtrack for the moments in between.",
    }
    for i, (name, kind) in enumerate(
        [("Evening essentials", "playlist"), ("On repeat", "playlist"), ("Discovery", "album")], 1
    )
]
MIXES = [
    {
        "key": "daily",
        "title": "Daily Mix",
        "description": "M83, Beach House, Daft Punk",
        "thumbnail": "/fixture/art/4.png",
        "tracks": TRACKS,
    }
]
ROOMS = [
    {
        "id": 1,
        "name": "Evening listening",
        "owner_username": "visual",
        "owner_id": 1,
        "active_users": 2,
        "max_users": 5,
        "playback_status": "paused",
        "is_owner": True,
        "allow_guests_queue": True,
        "can_add_songs": True,
        "current_index": 0,
        "current_track": TRACKS[0],
        "participants": [{"username": "visual"}, {"username": "listener"}],
        "queue": [{"item_id": i, "track": track, "added_by": "visual"} for i, track in enumerate(TRACKS[:3], 1)],
    }
]


class Reference(BaseHTTPRequestHandler):
    theme = "classic"
    jobs = {}
    members = [{"id": 2, "username": "listener", "profile_key": "listener", "is_federated": False}]

    def log_message(self, *_):
        pass

    def send(self, value, mime="application/json", code=200, headers=None):
        data = value if isinstance(value, bytes) else json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for name, content in (headers or {}).items():
            self.send_header(name, content)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlsplit(self.path)
        path = unquote(url.path)
        query = parse_qs(url.query)
        if path == "/api/admin/federation/outbound":
            return self.send(
                [
                    {
                        "id": 1,
                        "name": "Connected partner",
                        "peer_url": "https://partner.example",
                        "revoked": False,
                        "token_prefix": "fixture",
                    }
                ]
            )
        if path.startswith("/admin/api/system/jobs/") or path.startswith("/updater/api/jobs/"):
            if path.startswith("/updater/") and query.get("access") != ["b" * 64]:
                return self.send({}, code=403)
            job_id = int(path.rsplit("/", 1)[-1])
            count = Reference.jobs.get(job_id, 0)
            Reference.jobs[job_id] = count + 1
            if count in (1, 2):
                return self.send({"error": "Fixture services restarting"}, code=503)
            return self.send(
                {
                    "id": job_id,
                    "monitor_endpoint": f"/updater/api/jobs/{job_id}?access=" + "b" * 64,
                    "job_type": "apply_update",
                    "status": "running" if count < 2 else "completed",
                    "message": "Recreating services" if count < 2 else "Update applied successfully",
                    "details": {"phase": "recreate" if count < 2 else "done", "progress": 65 if count < 2 else 100},
                }
            )
        if path == "/fixture/keyboard":
            return self.send(
                b'<!doctype html><input id="first"><input type="checkbox" id="check"><input id="last"><button id="submit" onclick="this.dataset.clicked=\'yes\'">Continue</button>',
                "text/html",
            )
        if path.startswith("/wrapped/story/"):
            return self.send(
                TEMPLATES.get_template("wrapped_story.html")
                .render(year=2026, username="visual", user_theme=Reference.theme)
                .encode(),
                "text/html; charset=utf-8",
            )
        if path in {"/", "/login"}:
            theme = query.get("theme", [Reference.theme])[0]
            if theme not in {"classic", "glass", "vitrine"}:
                return self.send({}, code=400)
            Reference.theme = theme
            html = TEMPLATES.get_template("login.html" if path == "/login" else "app_shell.html").render(
                username="visual",
                is_admin=True,
                user_theme=theme,
                error=None,
                pool={"used": "1.2", "limit": "10", "percent": 12},
            )
            # No third-party fonts, analytics, network media or YouTube requests in visual fixtures.
            return self.send(html.encode(), "text/html; charset=utf-8")
        if path.startswith(("/assets/", "/static/")):
            file = (WEB / "assets" / path.split("/", 2)[2]).resolve()
            if not file.is_relative_to((WEB / "assets").resolve()) or not file.is_file():
                return self.send({}, code=404)
            import mimetypes

            return self.send(file.read_bytes(), mimetypes.guess_type(file.name)[0] or "application/octet-stream")
        if path == "/user/settings":
            html = TEMPLATES.get_template("user_settings.html").render(
                user={"username": "visual", "is_admin": True},
                user_theme=Reference.theme,
                dl_settings={},
                error=None,
                success=None,
            )
            return self.send(html.encode(), "text/html; charset=utf-8")
        if path == "/api/cover/resolve":
            path = "/fixture/art/2.png"
        elif path.startswith("/api/cover/") and path.rsplit("/", 1)[-1].isdigit():
            return self.send(b"", code=307, headers={"Location": "/fixture/art/" + path.rsplit("/", 1)[-1] + ".png"})
        if path.startswith("/fixture/art/") or path.startswith("/user/avatar/"):
            number = int(Path(path).stem) if path.startswith("/fixture/") else 1
            colors = [(40, 77, 96), (201, 122, 81), (113, 129, 100), (141, 65, 77), (82, 80, 119), (192, 151, 74)]
            base = colors[(number - 1) % len(colors)]
            image = Image.new("RGB", (480, 480), base)
            draw = ImageDraw.Draw(image)
            for i in range(24):
                fill = tuple(min(255, c + i * 2) for c in base)
                draw.ellipse((i * 12 - 180, i * 10 - 140, 700 - i * 6, 600 - i * 6), fill=fill)
            draw.text((28, 394), TRACKS[(number - 1) % len(TRACKS)]["artist"].upper(), fill="white", font_size=30)
            buffer = io.BytesIO()
            image.save(buffer, "PNG")
            return self.send(buffer.getvalue(), "image/png")
        if path.startswith("/api/stream/"):
            buffer = io.BytesIO()
            with wave.open(buffer, "wb") as audio:
                audio.setparams((1, 2, 22050, 0, "NONE", "not compressed"))
                audio.writeframes(b"\0\0" * (22050 * 240))
            data = buffer.getvalue()
            header = self.headers.get("Range")
            if header:
                start = int(header.split("=", 1)[1].split("-", 1)[0] or "0")
                return self.send(
                    data[start:],
                    "audio/wav",
                    206,
                    {"Content-Range": f"bytes {start}-{len(data) - 1}/{len(data)}", "Accept-Ranges": "bytes"},
                )
            return self.send(data, "audio/wav", headers={"Accept-Ranges": "bytes"})
        if path == "/api/mobile/v2/context":
            return self.send(
                {
                    "protocol": 2,
                    "storage_scope": "a" * 64,
                    "account": {"id": 1, "username": "visual", "is_admin": True},
                    "theme": Reference.theme,
                    "language": "en",
                }
            )
        if path == "/api/mobile/v2/settings":
            return self.send({"theme": Reference.theme, "language": "en", "integrations": {}})
        if path == "/api/public/playlists":
            return self.send(COLLECTIONS)
        if path == "/user/list":
            return self.send(
                [
                    {
                        "username": name,
                        "avatar_url": "/user/avatar/" + name,
                        "public_playlists_count": 3,
                        "favorites_count": 6,
                        "total_listens": 1290,
                    }
                    for name in ("visual", "listener", "another listener")
                ]
            )
        if path.startswith("/api/artist/"):
            return self.send(
                {
                    "name": "M83",
                    "spotify": {"image": "/fixture/art/1.png", "followers": 2400},
                    "info": {
                        "tags": ["electronic", "indie"],
                        "listeners": 6400,
                        "bio": "A synthetic artist profile used to verify the interface.",
                    },
                    "local_tracks": TRACKS[:1],
                    "top_tracks": [{"title": "A new sample", "artist": "M83"}],
                    "albums": [
                        {
                            "name": "Evening essentials",
                            "image": "/fixture/art/1.png",
                            "release_date": "2026-01-01",
                            "type": "album",
                            "url": "",
                        }
                    ],
                    "similar": [],
                }
            )
        if path == "/api/mobile/v2/admin/users":
            return self.send({"users": [{"id": 1, "username": "visual", "is_admin": True}]})
        if path == "/api/mobile/v2/admin/system":
            return self.send(
                {
                    "build": {"version": "1.3.1", "branch": "main", "commit": "visual"},
                    "updates": {"status": "idle", "message": "Aoede is up to date"},
                    "backups": {},
                    "wrapped": {},
                    "jobs": [],
                }
            )
        if path == "/admin/api/system-stats":
            return self.send(
                {
                    "cpu_usage": 12.5,
                    "ram": {"percent": 31.0, "used_gb": 2.5, "total_gb": 8},
                    "pool": {"used": 12.5, "limit": 100, "percent": 12.5},
                }
            )
        if path == "/admin/api/user-statistics":
            return self.send(
                {
                    "totals": {"users": 1, "active_users": 1, "qualified_listens": 42, "listening_seconds": 4800},
                    "users": [
                        {
                            "username": "listener",
                            "is_active": True,
                            "qualified_listens": 42,
                            "listening_seconds": 4800,
                            "unique_tracks": 6,
                            "playlist_count": 3,
                            "favorite_count": 6,
                        }
                    ],
                    "pagination": {"total": 1, "limit": 25, "offset": 0},
                }
            )
        if path == "/api/recommendations":
            return self.send(
                [
                    {"title": "Made for you", "items": TRACKS},
                    {"title": "Recently added", "items": list(reversed(TRACKS))},
                ]
            )
        if path == "/api/playlists":
            return self.send(COLLECTIONS)
        if path.startswith("/api/playlists/") and path.endswith("/collaborators"):
            return self.send({"is_owner": True, "members": Reference.members})
        if path.startswith("/api/playlists/"):
            return self.send(COLLECTIONS[(int(path.split("/")[3]) - 1) % 3])
        if path in {"/api/favorites", "/api/search", "/api/discovery"}:
            return self.send(TRACKS)
        if path == "/api/library/tracks":
            return self.send({"items": TRACKS, "total": len(TRACKS), "has_more": False})
        if path == "/api/discovery/feed":
            return self.send({"items": TRACKS, "total": len(TRACKS)})
        if path == "/api/lyrics":
            return self.send(
                {
                    "synced_lyrics": "[00:00.00]A quiet tone across the screen\n[00:04.00]A sample made for this preview\n[00:08.00]The next line follows in its time"
                }
            )
        if path == "/api/mixes":
            return self.send(MIXES)
        if path.startswith("/api/mixes/"):
            return self.send(MIXES[0])
        if path == "/api/recent-activity":
            return self.send({"playlists": COLLECTIONS, "mixes": MIXES, "radios": []})
        if path.startswith("/user/profile/"):
            return self.send(
                {
                    "username": "visual",
                    "avatar_url": "/user/avatar/visual",
                    "stats": {"total_listens": 1290, "listening_minutes": 3800},
                    "public_playlists": COLLECTIONS,
                    "favorites": TRACKS,
                }
            )
        if path.startswith("/api/wrapped/"):
            if query.get("summary") == ["false"]:
                return self.send({"enabled": False, "visible": False, "year": 2026})
            if path.endswith("/party"):
                return self.send(
                    {
                        "enabled": True,
                        "visible": True,
                        "most_minutes_listened": [{"rank": 1, "username": "visual", "minutes_listened": 3800}],
                        "biggest_repeaters": [{"username": "listener", "stream_count": 82}],
                    }
                )
            return self.send(
                {
                    "enabled": True,
                    "visible": True,
                    "year": 2026,
                    "minutes_listened": 3800,
                    "event_count": 1290,
                    "qualified_event_count": 1200,
                    "top_songs_playlist": {
                        "tracks": [{**track, "stream_count": 82 - i * 4} for i, track in enumerate(TRACKS)]
                    },
                    "top_artists": [
                        {"artist": "M83", "stream_count": 240, "artist_image": "/fixture/art/1.png"},
                        {"artist": "Daft Punk", "stream_count": 180, "artist_image": "/fixture/art/2.png"},
                    ],
                    "top_artist_sprint": [{"month": 1, "artists": [{"artist": "M83", "stream_count": 24}]}],
                    "artist_clip": {"title": "A message from Aoede", "message": "The songs that made this year yours."},
                }
            )
        if path == "/api/collections/resolve":
            return self.send(
                {
                    "url": "https://open.spotify.com/playlist/fixture",
                    "kind": "playlist",
                    "name": "Evening essentials",
                    "source": "Spotify",
                    "track_count": len(TRACKS),
                    "tracks": [{**track, "position": i + 1} for i, track in enumerate(TRACKS)],
                }
            )
        if path == "/api/jobs":
            return self.send(
                [
                    {
                        "id": 1,
                        "resolved_track_title": "A synthetic download",
                        "status": "completed",
                        "progress": 100,
                        "source": "spotify",
                        "detail": "Available in your library",
                        "created_at": "2026-10-28T12:00:00Z",
                    },
                    {
                        "id": 2,
                        "track_title": "Another sample",
                        "status": "failed",
                        "progress": 12,
                        "source": "youtube",
                        "error": "A synthetic connection error",
                        "created_at": "2026-10-28T12:01:00Z",
                    },
                ]
            )
        if path == "/api/radio/browse":
            return self.send(
                [{"title": "Night radio", "image": "/fixture/art/1.png", "count": 3, "page": {"url": "/browse/night"}}]
            )
        if path == "/api/radio/search":
            return self.send(
                [
                    {
                        "_source": {
                            "type": "channel",
                            "page": {
                                "title": "A sample station",
                                "subtitle": "London",
                                "url": "/listen/sample/testch1",
                            },
                        }
                    }
                ]
            )
        if path == "/api/radio/list":
            return self.send(
                [
                    {
                        "id": "testch2",
                        "name": "Saved station",
                        "streamUrl": "https://radio.garden/api/ara/content/listen/testch2/channel.mp3",
                    }
                ]
            )
        if path == "/api/playback/state":
            return self.send({"queue": [], "volume": 0.7})
        if path == "/api/party/rooms":
            return self.send({"rooms": ROOMS})
        return self.send([])

    def do_POST(self):
        length = min(int(self.headers.get("Content-Length", "0")), 4096)
        data = self.rfile.read(length)
        if self.path == "/api/smart-playlists/preview":
            return self.send({"track_count": len(TRACKS), "summary": "Matching the selected rules", "tracks": TRACKS})
        if self.path in {
            "/admin/system/updates/check",
            "/admin/system/updates/apply",
            "/admin/system/wrapped/regenerate",
        }:
            Reference.jobs[42] = 0
            return self.send(
                b"", code=303, headers={"Location": "/admin/system/updates/jobs/42?msg=Operation%20started"}
            )
        if self.path == "/login":
            body = parse_qs(data.decode())
            if body.get("username") != ["visual"] or body.get("password") != ["fixture-only"]:
                return self.send({}, code=401)
            return self.send(
                b"",
                code=303,
                headers={"Location": "/portal", "Set-Cookie": "access_token=fixture-only; Path=/; HttpOnly"},
            )
        if self.path.endswith("/top-songs/playlist"):
            return self.send({"id": 1, "track_count": len(TRACKS)})
        if self.path == "/api/download/collection":
            return self.send({"playlist_id": 1, "message": "Import queued"})
        if self.path.endswith("/collaborators"):
            payload = json.loads(data)
            Reference.members.append(
                {
                    "id": len(Reference.members) + 2,
                    "username": payload["username"],
                    "profile_key": payload.get("profile_key", payload["username"]),
                }
            )
            return self.send({"ok": True})
        return self.send({"ok": True})

    def do_DELETE(self):
        if "/collaborators/" in self.path:
            Reference.members = [
                member for member in Reference.members if str(member["id"]) != self.path.rsplit("/", 1)[-1]
            ]
        self.send({"ok": True})

    def do_PATCH(self):
        body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length", "0")), 4096)))
        theme = body.get("theme")
        if theme in {"classic", "glass", "vitrine"}:
            Reference.theme = theme
        self.send({"ok": True})


if __name__ == "__main__":
    print("Synthetic visual fixture: http://127.0.0.1:8765", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8765), Reference).serve_forever()
