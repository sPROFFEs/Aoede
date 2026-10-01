"""Package a completed Neutralino build: python scripts/package_desktop.py."""

import json
import plistlib
import shutil
import tarfile
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
DESKTOP = ROOT / "desktop"


def main():
    config = json.loads((DESKTOP / "neutralino.config.json").read_text())
    build = DESKTOP / "dist/aoede"
    output = DESKTOP / "dist/releases"
    output.mkdir(parents=True, exist_ok=True)
    icon = DESKTOP / "resources/icons/appIcon.png"
    platforms = {
        "linux-x64": "linux_x64",
        "linux-arm64": "linux_arm64",
        "mac-universal": "mac_universal",
        "mac-arm64": "mac_arm64",
        "mac-x64": "mac_x64",
        "win-x64": "win_x64.exe",
    }
    for platform, suffix in platforms.items():
        binary = f"aoede-{suffix}"
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            for name in (binary, "resources.neu"):
                shutil.copy2(build / name, stage / name)
            shutil.copy2(icon, stage / "appIcon.png")
            if platform.startswith("mac-"):
                contents = stage / "Aoede.app/Contents"
                native = contents / "MacOS"
                resources = contents / "Resources"
                native.mkdir(parents=True)
                resources.mkdir()
                shutil.copy2(build / binary, native / "aoede")
                (native / "aoede").chmod(0o755)
                shutil.copy2(build / "resources.neu", native / "resources.neu")
                Image.open(icon).save(resources / "appIcon.icns", format="ICNS")
                shutil.copy2(icon, resources / "appIcon.png")
                with (contents / "Info.plist").open("wb") as file:
                    plistlib.dump(
                        {
                            "CFBundleExecutable": "aoede",
                            "CFBundleIconFile": "appIcon.icns",
                            "CFBundleIdentifier": config["applicationId"],
                            "CFBundleName": "Aoede",
                            "CFBundlePackageType": "APPL",
                            "CFBundleShortVersionString": config["version"],
                            "CFBundleVersion": config["version"],
                            "LSMinimumSystemVersion": "10.15",
                            "NSHighResolutionCapable": True,
                        },
                        file,
                    )
            target = output / f"aoede-{platform}.tar.gz"
            with tarfile.open(target, "w:gz") as archive:
                for path in sorted(stage.iterdir()):
                    archive.add(path, arcname=path.name)
            print(target)


if __name__ == "__main__":
    main()
