from pathlib import Path

ASSETS_ROOT = Path(__file__).resolve().parents[2] / "assets"


def test_mobile_shell_owns_gutters_for_all_views_including_async_home_and_parties():
    css = (ASSETS_ROOT / "css" / "mobile_shell.css").read_text(encoding="utf-8")
    assert "padding: 0 16px var(--mobile-bottom-clearance) !important;" in css
    vitrine = (ASSETS_ROOT / "css" / "theme_vitrine.css").read_text(encoding="utf-8")
    assert ".view-content { padding: 0 0 var(--mobile-bottom-clearance)" not in vitrine
    assert "#view-container > .party-autoplay-banner {\n    width: auto;" in css


def test_party_mobile_controls_keep_touch_sized_targets():
    css = (ASSETS_ROOT / "css" / "ui_party.css").read_text(encoding="utf-8")

    assert ".party-owner-controls button,\n    .party-row-remove { min-width: 44px; min-height: 44px; }" in css
    assert ".party-search input { min-height: 44px; }" in css
    assert ".party-delete-btn { min-height: 44px; }" in css
