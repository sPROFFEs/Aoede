"""Check the packaged icon, native close setting, and platform archives."""

import importlib.util
import io
import json
import plistlib
import tarfile
from pathlib import Path

from PIL import Image


def test_desktop_archives_include_consistent_macos_bundle(tmp_path, monkeypatch):
    root = Path(__file__).resolve().parents[3]
    spec = importlib.util.spec_from_file_location("desktop_packaging_test", root / "scripts/package_desktop.py")
    packaging = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(packaging)
    config = json.loads((root / "desktop/neutralino.config.json").read_text())
    assert config["modes"]["window"]["exitProcessOnClose"] is True
    assert config["storageLocation"] == "system"
    assert config["dataLocation"] == "system"
    assert set(config["nativeAllowList"]) == {"storage.getData", "storage.setData", "filesystem.readFile"}
    assert config["modes"]["window"]["extendUserAgentWith"] == "AoedeDesktop"
    assert "nl_token" not in (root / "desktop/resources/index.html").read_text()
    desktop = tmp_path / "desktop"
    build = desktop / "dist/aoede"
    build.mkdir(parents=True)
    (desktop / "neutralino.config.json").write_text(json.dumps(config))
    icon = desktop / "resources/icons/appIcon.png"
    icon.parent.mkdir(parents=True)
    icon.write_bytes((root / "desktop/resources/icons/appIcon.png").read_bytes())
    (build / "resources.neu").write_bytes(b"test resources")
    for suffix in ("linux_x64", "linux_arm64", "mac_universal", "mac_arm64", "mac_x64", "win_x64.exe"):
        (build / f"aoede-{suffix}").write_bytes(b"test executable")
    monkeypatch.setattr(packaging, "DESKTOP", desktop)
    packaging.main()
    archives = list((desktop / "dist/releases").glob("*.tar.gz"))
    assert len(archives) == 6
    for path in archives:
        with tarfile.open(path) as archive:
            assert archive.extractfile("resources.neu").read() == b"test resources"
            assert archive.extractfile("appIcon.png").read() == icon.read_bytes()
            if "mac-" not in path.name:
                continue
            plist = plistlib.load(archive.extractfile("Aoede.app/Contents/Info.plist"))
            assert plist["CFBundleShortVersionString"] == config["version"]
            assert plist["CFBundleIconFile"] == "appIcon.icns"
            assert archive.getmember("Aoede.app/Contents/MacOS/aoede").mode & 0o111
            assert archive.extractfile("Aoede.app/Contents/MacOS/resources.neu").read() == b"test resources"
            image = Image.open(io.BytesIO(archive.extractfile("Aoede.app/Contents/Resources/appIcon.icns").read()))
            image.size = (1024, 1024)
            image.load()
            assert image.convert("RGBA").tobytes() == Image.open(icon).convert("RGBA").tobytes()
