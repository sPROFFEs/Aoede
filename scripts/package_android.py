#!/usr/bin/env python3
"""Package the release APK and Android sources, excluding generated files and keys."""

import shutil
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
project = root / "android"
output = project / "dist"
output.mkdir(exist_ok=True)
release = project / "app/build/outputs/apk/release"
apk = release / "app-release.apk"
if not apk.exists():
    apk = release / "app-release-unsigned.apk"
if not apk.exists():
    raise SystemExit("Build Android assembleRelease before packaging")
name = "aoede-android.apk" if apk.name == "app-release.apk" else "aoede-android-unsigned.apk"
shutil.copy2(apk, output / name)
with zipfile.ZipFile(output / "aoede-android-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for source in sorted(project.rglob("*")):
        relative = source.relative_to(project)
        if not source.is_file() or any(part in {"build", "dist", ".gradle", ".idea"} for part in relative.parts):
            continue
        if source.suffix in {".jks", ".keystore", ".key", ".pem"} or source.name == "local.properties":
            continue
        if source.suffix not in {".kt", ".kts", ".js", ".xml", ".png", ".properties", ".jar", ".pro", ".md", ".bat"} and source.name != "gradlew":
            continue
        archive.write(source, Path("aoede-android") / relative)
print(f"Packaged {name} and aoede-android-source.zip")
