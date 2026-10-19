"""Read-only release information shared by every signed-in client."""

import asyncio
import re
import time
from datetime import datetime, timezone
from functools import lru_cache
from urllib.parse import quote

import httpx
from build_info_service import get_build_info
from http_client import http_client

REPOSITORY = "https://github.com/sPROFFEs/Aoede"
LATEST_URL = f"{REPOSITORY}/releases/latest"
_lock = asyncio.Lock()
_release = None
_checked_at = None
_retry_at = 0
_stale = True


@lru_cache(maxsize=1)
def _current_build():
    build = get_build_info()
    return {key: build[key] for key in ("release_version", "display_version")}


def _release_details(data):
    tag = data.get("tag_name", "")
    if not isinstance(tag, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,79}", tag):
        raise ValueError("Invalid release tag")
    if data.get("draft") or data.get("prerelease"):
        raise ValueError("Not a stable release")
    body = str(data.get("body") or "")[:20000]
    paragraphs = body.replace("\r", "").split("\n\n")
    heading = next((p.lstrip("# ").strip() for p in paragraphs if p.startswith("#")), "")
    summary = next((p.strip() for p in paragraphs if p.strip() and not p.startswith(("#", "-", "!", "<"))), "")
    summary = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", summary)
    summary = re.sub(r"[*`_]", "", summary)
    summary = " ".join(summary.split())
    if len(summary) > 700:
        summary = summary[:697].rsplit(" ", 1)[0] + "…"
    downloads = []
    for asset in data.get("assets", []):
        name = asset.get("name", "")
        if not isinstance(name, str) or asset.get("state") != "uploaded":
            continue
        platform, label = "android", ""
        if name == "aoede-android.apk":
            label = "Aoede for Android"
        elif name == "aoede-android-source.zip":
            label = "Complete Android source"
        elif match := re.fullmatch(r"aoede-android-wrapper-(\d+\.\d+\.\d+)(\.apk|-source\.zip)", name):
            label = f"Wrapper {match[1]} · " + ("Backup APK" if match[2] == ".apk" else "Source")
        elif match := re.fullmatch(r"aoede-(mac|win|linux)-(arm64|x64|universal)\.tar\.gz", name):
            platform = match[1]
            label = {
                "arm64": "Apple silicon" if platform == "mac" else "ARM64",
                "x64": "Intel" if platform == "mac" else "x64",
                "universal": "Universal · Apple silicon & Intel",
            }[match[2]]
        if label:
            downloads.append(
                {
                    "platform": platform,
                    "label": label,
                    "name": name,
                    "url": f"{REPOSITORY}/releases/download/{quote(tag, safe='')}/{quote(name, safe='')}",
                }
            )
    downloads.sort(
        key=lambda item: (
            item["platform"],
            item["name"].endswith("-source.zip"),
            "wrapper" in item["name"],
            item["name"],
        )
    )
    return {
        "tag": tag,
        "title": (heading or str(data.get("name") or tag))[:200],
        "summary": summary,
        "url": f"{REPOSITORY}/releases/tag/{quote(tag, safe='')}",
        "downloads": downloads,
    }


async def get_about_info():
    global _release, _checked_at, _retry_at, _stale
    # One request per process per five minutes, including simultaneous client opens.
    async with _lock:
        if time.monotonic() >= _retry_at:
            try:
                response = await http_client.get(
                    "https://api.github.com/repos/sPROFFEs/Aoede/releases/latest",
                    headers={"Accept": "application/vnd.github+json"},
                    timeout=5.0,
                    follow_redirects=False,
                )
                response.raise_for_status()
                _release = _release_details(response.json())
                _checked_at = datetime.now(timezone.utc).isoformat()
                _stale = False
            except (httpx.HTTPError, ValueError, TypeError, AttributeError):
                _stale = True
            _retry_at = time.monotonic() + (60 if _stale else 300)
    return {
        "current": await asyncio.to_thread(_current_build),
        "release": _release,
        "checked_at": _checked_at,
        "stale": _stale,
        "releases_url": LATEST_URL,
    }
