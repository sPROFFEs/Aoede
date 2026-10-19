"""Metadata-only candidates for manual review, never automatic deduplication."""

import re
from collections import Counter, defaultdict, deque
from difflib import SequenceMatcher
from pathlib import Path

import database
import track_identity

MAX_CANDIDATES = 64
_NOISE = r"(?:official(?:\s+music)?\s*(?:video|audio)?|lyrics?|hd|hq|4k|1080p)"


def _metadata(track):
    title = (track.title or "").strip()
    artist = re.sub(r"(?:\s*-\s*Topic|VEVO)$", "", track.artist or "", flags=re.I).strip()
    if track_identity.normalize_text(title) in track_identity.PLACEHOLDER_VALUES:
        title = Path(track.filepath or "").stem
    title = re.sub(r"^\s*\d{1,3}[._\s-]+", "", title)
    title = re.sub(rf"\s*[-–—]\s*{_NOISE}\s*$", "", title, flags=re.I)
    parts = re.split(r"\s+[-–—]\s+", title, maxsplit=1)
    artist_norm = track_identity.normalize_text(artist)
    if len(parts) == 2 and (
        artist_norm in track_identity.PLACEHOLDER_VALUES or track_identity.normalize_text(parts[0]) == artist_norm
    ):
        artist, title = parts
    title = re.sub(r"([\[(])\s*(\d{4})\s+(remaster\w*)", r"\1\3 \2", title, flags=re.I)
    title, version = track_identity.extract_version_tag(title)
    title = re.sub(rf"[\[(][^\]\)]*\b{_NOISE}\b[^\]\)]*[\])]", "", title, flags=re.I)
    artist = re.split(r"\s+(?:feat\.?|ft\.?|featuring)\s+", artist, maxsplit=1, flags=re.I)[0]
    artist = track_identity.normalize_text(artist)
    title = track_identity.normalize_text(title)
    if title in track_identity.PLACEHOLDER_VALUES or (
        artist in track_identity.PLACEHOLDER_VALUES
        and re.fullmatch(r"(?:track|pista|untitled|audio|song)\s*\d*", title)
    ):
        return None
    if artist in track_identity.PLACEHOLDER_VALUES:
        artist = ""
    compact = title.replace(" ", "")[:160]
    if len(compact) < 4:
        return None
    return {
        "track": track,
        "title": title,
        "artist": artist,
        "version": version,
        "grams": {compact[i : i + 3] for i in range(len(compact) - 2)},
    }


def _ratio(left, right):
    # Sorting inputs makes SequenceMatcher's asymmetric tie-breaking deterministic.
    return max(
        SequenceMatcher(None, *sorted((a, b)), autojunk=False).ratio()
        for a, b in (
            (left.replace(" ", ""), right.replace(" ", "")),
            (" ".join(sorted(left.split())), " ".join(sorted(right.split()))),
        )
    )


def _similarity(left, right):
    versions = {left["version"], right["version"]}
    if len(versions) > 1 and versions != {"original", "remaster"}:
        return None
    duration_a, duration_b = left["track"].duration or 0, right["track"].duration or 0
    delta = abs(duration_a - duration_b) if min(duration_a, duration_b) > 0 else None
    if delta is not None and delta > max(3, min(8, max(duration_a, duration_b) * 0.025)):
        return None
    title = _ratio(left["title"], right["title"])
    if title < 0.90:
        return None
    artist = _ratio(left["artist"], right["artist"]) if left["artist"] and right["artist"] else None
    weak_artist = artist is None or artist < 0.8
    if weak_artist and (title < 0.99 or len(left["title"].replace(" ", "")) < 12 or delta is None or delta > 2):
        return None
    if delta is None and (title < 0.98 or artist is None or artist < 0.94):
        return None
    score = round(
        100 * (0.65 * title + 0.25 * (artist if artist is not None else 0.6) + 0.1 * (1 if delta is not None else 0.5))
    )
    return {
        "score": min(score, 85 if weak_artist or len(versions) > 1 else 100),
        "title": round(title * 100),
        "artist": round(artist * 100) if artist is not None else None,
        "duration_delta": delta,
        "different_versions": len(versions) > 1,
    }


def find_similar_tracks(db, known_groups, limit=500):
    known = defaultdict(set)
    for index, group in enumerate(known_groups):
        for track in group["tracks"]:
            known[track["id"]].add(index)
    postings = defaultdict(lambda: deque(maxlen=MAX_CANDIDATES))
    identities = {}
    matches, limited = [], False
    # ponytail: 64 postings/candidates per trigram; use an on-disk similarity index if coverage needs to grow.
    for track in db.query(database.Track).order_by(database.Track.id).yield_per(500):
        current = _metadata(track)
        if not current:
            continue
        candidates, records = Counter(), {}
        for gram in current["grams"]:
            for previous in postings[gram]:
                previous_id = previous["track"].id
                if known[track.id] & known[previous_id]:
                    continue
                candidates[previous_id] += 1
                records[previous_id] = previous
        identity = (current["title"], current["artist"], current["version"])
        identical = identities.setdefault(identity, current)
        identical_id = identical["track"].id
        if identical_id != track.id and not known[track.id] & known[identical_id]:
            candidates[identical_id] = len(current["grams"]) + 1
            records[identical_id] = identical
        if len(candidates) > MAX_CANDIDATES:
            limited = True
        for previous_id in sorted(candidates, key=lambda key: (-candidates[key], key))[:MAX_CANDIDATES]:
            previous = records[previous_id]
            similarity = _similarity(previous, current)
            if similarity:
                matches.append({"tracks": [previous["track"], track], "similarity": similarity})
                if len(matches) >= limit:
                    return matches, True
        for gram in current["grams"]:
            limited |= len(postings[gram]) == MAX_CANDIDATES
            postings[gram].append(current)
    return matches, limited
