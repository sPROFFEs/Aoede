#!/usr/bin/env bash
set -euo pipefail

# Package iOS archive into an IPA file suitable for sideloading
# (AltStore, SideStore, TrollStore, Sideloadly, Scarlet, etc.)

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${1:-$PROJECT_DIR/build}"
DIST_DIR="${2:-$PROJECT_DIR/dist}"

ARCHIVE_APP="$BUILD_DIR/Aoede.xcarchive/Products/Applications/Aoede.app"

if [ ! -d "$ARCHIVE_APP" ]; then
    # Check if app was built directly into Products/Release-iphoneos
    ALT_APP="$BUILD_DIR/Release-iphoneos/Aoede.app"
    if [ -d "$ALT_APP" ]; then
        ARCHIVE_APP="$ALT_APP"
    else
        echo "Error: Aoede.app not found in $ARCHIVE_APP or $ALT_APP" >&2
        exit 1
    fi
fi

mkdir -p "$DIST_DIR"
PAYLOAD_DIR="$BUILD_DIR/Payload"
rm -rf "$PAYLOAD_DIR"
mkdir -p "$PAYLOAD_DIR"

echo "Copying Aoede.app to Payload..."
cp -r "$ARCHIVE_APP" "$PAYLOAD_DIR/"

IPA_PATH="$DIST_DIR/aoede-ios.ipa"
rm -f "$IPA_PATH"

echo "Creating IPA archive: $IPA_PATH"
(cd "$BUILD_DIR" && zip -r -9 "$IPA_PATH" Payload)

echo "iOS IPA package created successfully:"
ls -lh "$IPA_PATH"
sha256sum "$IPA_PATH" > "$DIST_DIR/aoede-ios.ipa.sha256"
cat "$DIST_DIR/aoede-ios.ipa.sha256"
