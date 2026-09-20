from pathlib import Path


def test_service_worker_offline_support_and_caching_contract():
    assets_root = Path(__file__).resolve().parents[2] / "assets"
    sw_path = assets_root / "sw.js"
    assert sw_path.exists()
    content = sw_path.read_text(encoding="utf-8")

    # Versioned cache
    assert "aoede-shell-" in content

    # Precached core assets
    assert "'/portal'" in content
    assert "'/assets/js/modules/offline_store.js'" in content
    assert "'/assets/site.webmanifest'" in content

    # Navigation fallback
    assert "request.mode === 'navigate'" in content
    assert "caches.match('/portal')" in content

    # API offline handling
    assert "url.pathname.startsWith('/api/')" in content
    assert "offline" in content


def test_app_shell_offline_readiness_and_importmap():
    templates_root = Path(__file__).resolve().parents[1] / "templates"
    base_html = (templates_root / "base.html").read_text(encoding="utf-8")

    # Import map includes offline_store
    assert '"/assets/js/modules/offline_store.js"' in base_html

    # Sidebar contains Offline Downloads navigation item
    assert "loadView('offline')" in base_html
    assert "Offline Downloads" in base_html

    # Local fallback for offline startup
    assert "/assets/vendor/lucide.min.js" in base_html
    assert "/assets/vendor/htmx.min.js" in base_html


def test_offline_store_module_and_contract():
    js_root = Path(__file__).resolve().parents[2] / "assets" / "js"
    store_file = js_root / "modules" / "offline_store.js"
    assert store_file.exists()
    content = store_file.read_text(encoding="utf-8")

    # IndexedDB stores defined
    assert "tracks" in content
    assert "audio" in content
    assert "library_snapshots" in content
    assert "pending_actions" in content

    # Core operations exported
    assert "export async function downloadTrack" in content
    assert "export async function getOfflineTrack" in content
    assert "export async function getOfflineAudioBlob" in content
    assert "export async function listOfflineTracks" in content
    assert "export async function isTrackAvailableOffline" in content
    assert "export async function deleteOfflineTrack" in content
    assert "export async function getStorageUsage" in content
    assert "export async function clearOfflineData" in content
    assert "export async function queueAction" in content
    assert "export async function flushPendingActions" in content
    assert "export async function saveLibrarySnapshot" in content
    assert "export async function getLibrarySnapshot" in content
    assert "export async function saveOfflinePlaylist" in content
    assert "export async function listOfflinePlaylists" in content
    assert "export async function deleteOfflinePlaylist" in content
    assert "export async function listOfflineSingles" in content
    assert "export async function checkOfflineReadiness" in content


def test_player_and_views_offline_integration():
    js_root = Path(__file__).resolve().parents[2] / "assets" / "js"
    player_file = js_root / "modules" / "player.js"
    views_file = js_root / "modules" / "views.js"

    player_content = player_file.read_text(encoding="utf-8")
    views_content = views_file.read_text(encoding="utf-8")

    # Player integrates offline store
    assert "import * as offlineStore from './offline_store.js';" in player_content
    assert "offlineStore.getOfflineAudioBlob" in player_content
    assert "URL.createObjectURL" in player_content
    assert "URL.revokeObjectURL" in player_content
    assert "This track is not downloaded for offline use" in player_content

    # Views integrates offline downloads and status indicators
    assert "import * as offlineStore from './offline_store.js';" in views_content
    assert "renderOfflineDownloads" in views_content
    assert "downloadOfflineTrackAction" in views_content
    assert "deleteOfflineTrackAction" in views_content
    assert "track-offline-badge" in views_content
