#!/usr/bin/env python3
"""Package the release APK and Android sources, excluding generated files and keys."""

import json
import re
import shutil
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
project = root / "android"
output = project / "dist"
output.mkdir(exist_ok=True)
release = project / "app/build/outputs/apk/release"
metadata = json.loads((release / "output-metadata.json").read_text())
if metadata.get("applicationId") != "com.aoede.twa" or len(metadata["elements"]) != 1:
    raise SystemExit("Unexpected Android build metadata")
build = metadata["elements"][0]
apk = release / build["outputFile"]
if apk.name not in {"app-release.apk", "app-release-unsigned.apk"} or not apk.is_file():
    raise SystemExit("Build Android assembleRelease before packaging")
version, code = build["versionName"], build["versionCode"]
if not re.fullmatch(r"\d+\.\d+\.\d+", version) or not isinstance(code, int) or code < 1:
    raise SystemExit("Invalid Android build version")
name = "aoede-android.apk" if apk.name == "app-release.apk" else "aoede-android-unsigned.apk"
shutil.copy2(apk, output / name)
(output / "android-version.txt").write_text(f"Aoede Android {version} ({code})")
with zipfile.ZipFile(output / "aoede-android-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for source in (root / "branding/aoede.png", root / "scripts/enlarge_brand_icons.py"):
        archive.write(source, Path("aoede-android") / source.relative_to(root))
    for name in ("ANDROID.md", "ANDROID-NATIVE-MIGRATION.md", "SECURITY-REVIEW-1.3.1.md", "RELEASE-1.3.1.md"):
        source = root / "docs" / name
        archive.write(source, Path("aoede-android/docs") / name)
    for source in sorted(project.rglob("*")):
        relative = source.relative_to(project)
        if not source.is_file() or any(part in {"build", "dist", ".gradle", ".kotlin", ".idea"} for part in relative.parts):
            continue
        if source.suffix in {".jks", ".keystore", ".key", ".pem", ".p12", ".pyc"} or source.name == "local.properties":
            continue
        if "__pycache__" in relative.parts:
            continue
        if source.suffix not in {
            ".kt", ".kts", ".js", ".mjs", ".xml", ".png", ".ico", ".jpg", ".jpeg", ".svg", ".webp",
            ".css", ".html", ".json", ".webmanifest", ".woff", ".woff2", ".ttf", ".otf",
            ".properties", ".jar", ".pro", ".md", ".bat", ".py", ".txt",
        } and source.name != "gradlew":
            continue
        archive.write(source, Path("aoede-android") / relative)
    # Keep editable canonical sources alongside the generated bundle for standalone builds.
    for folder in ("assets", "concierge/templates", "concierge/locales"):
        for source in sorted((root / "Aoede" / folder).rglob("*")):
            if source.is_file() and source.suffix not in {".pyc", ".mo"}:
                archive.write(source, Path("aoede-android/frontend-source") / source.relative_to(root / "Aoede"))
print(f"Packaged {name} and aoede-android-source.zip")
