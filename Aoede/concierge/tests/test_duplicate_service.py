from types import SimpleNamespace

import database
import duplicate_service
import pytest


def metadata(title, artist="Artist", duration=210, filepath="song.mp3"):
    return duplicate_service._metadata(
        SimpleNamespace(title=title, artist=artist, duration=duration, filepath=filepath)
    )


@pytest.mark.parametrize(
    "left,right",
    [
        (metadata("Northern Lights"), metadata("01. Artist - Northern Lights (Official Video)", "Artist - Topic", 212)),
        (metadata("Northern Lights"), metadata("Northern Ligths")),
        (metadata("Northern Lights", "Artíst"), metadata("Northern Lights [Official Audio]", "ARTISTVEVO")),
        (metadata("Déjà vu tonight", "Artist feat. Guest"), metadata("Deja Vu Tonight", "Artist")),
        (metadata("Northern Lights"), metadata("Northern Lights", "Unknown Artist", 211)),
        (metadata("Northern Lights"), metadata("Northern Lights", "Different uploader", 210)),
        (metadata("Northern Lights"), metadata("Northern Lights (2020 Remaster)")),
        (metadata("Northern Lights"), metadata("", "Artist", 210, "Artist - Northern Lights.mp3")),
    ],
)
def test_similar_metadata_handles_upload_noise_typos_missing_tags_and_remasters(left, right):
    match = duplicate_service._similarity(left, right)
    assert match and 75 <= match["score"] <= 100
    assert match == duplicate_service._similarity(right, left)


@pytest.mark.parametrize(
    "left,right",
    [
        (metadata("Northern Lights"), metadata("Southern Journey")),
        (metadata("Northern Lights"), metadata("Northern Lights", duration=260)),
        (metadata("Northern Lights"), metadata("Northern Lights (Live)")),
        (metadata("Northern Lights"), metadata("Northern Lights (Acoustic)")),
        (metadata("A Song", "Artist"), metadata("A Song", "Another performer")),
        (metadata("Northern Lights", duration=0), metadata("Northern Lights", "Unknown Artist", 0)),
    ],
)
def test_unrelated_songs_recording_versions_and_weak_evidence_are_not_suggestions(left, right):
    assert duplicate_service._similarity(left, right) is None


def test_similarity_scan_does_not_merge_soft_links_into_exact_groups_and_reports_limits(db_session):
    entries = [
        database.Track(title=title, artist="Artist", duration=210, filepath=f"{index}.mp3")
        for index, title in enumerate(
            ["Northern Lights", "Northern Lights (Official Audio)", "Northern Ligths", "Unrelated song"]
        )
    ]
    db_session.add_all(entries)
    db_session.commit()
    known = [{"tracks": [{"id": entries[0].id}, {"id": entries[1].id}]}]
    pairs, limited = duplicate_service.find_similar_tracks(db_session, known)
    assert not limited
    assert {tuple(item.id for item in pair["tracks"]) for pair in pairs} == {
        (entries[0].id, entries[2].id),
        (entries[1].id, entries[2].id),
    }
    assert all(len(pair["tracks"]) == 2 for pair in pairs)
    assert duplicate_service.find_similar_tracks(db_session, known, limit=1)[1]
    assert metadata("Track 01", "Unknown Artist") is None


def test_normalized_identity_still_matches_when_fuzzy_postings_are_saturated(db_session):
    original = database.Track(title="Northern Lights", artist="Artist", duration=210, filepath="original.mp3")
    db_session.add(original)
    db_session.flush()
    db_session.add_all(
        database.Track(
            title=f"Northern Lights draft {i}", artist="Other", duration=1000 + i * 20, filepath=f"draft-{i}.mp3"
        )
        for i in range(duplicate_service.MAX_CANDIDATES + 5)
    )
    copy = database.Track(
        title="Artist - Northern Lights (Official Video)", artist="Artist", duration=211, filepath="copy.mp3"
    )
    db_session.add(copy)
    db_session.commit()
    pairs, limited = duplicate_service.find_similar_tracks(db_session, [])
    assert limited
    assert any({track.id for track in pair["tracks"]} == {original.id, copy.id} for pair in pairs)
