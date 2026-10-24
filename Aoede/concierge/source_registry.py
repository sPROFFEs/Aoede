import re
from urllib.parse import urlparse

KNOWN_SOURCES = {
    "spotify",
    "youtube",
    "musicbrainz",
    "lastfm",
    "soundcloud",
    "audius",
    "jamendo",
    "federation",
}

SOURCE_DOMAIN_HINTS = (
    ("spotify", ("spotify.com", "open.spotify.com")),
    ("youtube", ("youtube.com", "youtu.be", "music.youtube.com")),
    ("musicbrainz", ("musicbrainz.org",)),
    ("lastfm", ("last.fm",)),
    ("soundcloud", ("soundcloud.com",)),
    ("audius", ("audius.co", "audius.com")),
    ("jamendo", ("jamendo.com",)),
)


def is_allowed_download_url(value: str) -> bool:
    if not isinstance(value, str) or not 0 < len(value) <= 2048:
        return False
    if re.fullmatch(r"fed:[1-9]\d{0,18}:[1-9]\d{0,18}", value):
        return True
    if re.fullmatch(r"ytsearch(?:[1-9]\d{0,2})?:[^\r\n]{1,1000}", value):
        return True
    try:
        parsed = urlparse(value)
        host = (parsed.hostname or "").lower().rstrip(".")
        if parsed.scheme not in {"http", "https"} or parsed.username or parsed.password:
            return False
        if parsed.port not in {None, 80, 443}:
            return False
        domains = {domain for _, values in SOURCE_DOMAIN_HINTS for domain in values} | {"spotify.link"}
        return any(host == domain or host.endswith("." + domain) for domain in domains)
    except ValueError:
        return False


def normalize_source(raw_source: str | None, default: str = "external") -> str:
    source = (raw_source or "").strip().lower()
    return source if source in KNOWN_SOURCES else default


def infer_source(raw_source: str | None = None, raw_url: str | None = None, default: str = "external") -> str:
    normalized = normalize_source(raw_source, default="")
    if normalized:
        return normalized

    url = (raw_url or "").strip().lower()
    if not url:
        return default

    # Federation track ids look like "fed:1:1722". They aren't real
    # URLs, so urlparse below would silently misclassify them as
    # "external"; handle them explicitly.
    if url.startswith("fed:"):
        return "federation"

    parsed = urlparse(url)
    host = (parsed.hostname or "").lower().rstrip(".")
    for source, domains in SOURCE_DOMAIN_HINTS:
        if any(host == domain or host.endswith("." + domain) for domain in domains):
            return source
    return default
