#!/usr/bin/env bash
set -e

REPO="sPROFFEs/Aoede"
TAG="v1.2.1"

echo "🎵 Installing Aoede Desktop App ($TAG)..."

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$OS" in
    linux)
        case "$ARCH" in
            x86_64|amd64) PACKAGE_NAME="aoede-linux-x64.tar.gz"; BINARY_NAME="aoede-linux_x64" ;;
            arm64|aarch64) PACKAGE_NAME="aoede-linux-arm64.tar.gz"; BINARY_NAME="aoede-linux_arm64" ;;
            *) echo "❌ Unsupported architecture: $ARCH"; exit 1 ;;
        esac

        APP_DIR="$HOME/.local/share/aoede"
        BIN_DIR="$HOME/.local/bin"
        DESKTOP_DIR="$HOME/.local/share/applications"
        ICON_DIR="$HOME/.local/share/icons/hicolor/512x512/apps"
        
        mkdir -p "$APP_DIR" "$BIN_DIR" "$DESKTOP_DIR" "$ICON_DIR"
        
        echo "⬇️  Downloading ${PACKAGE_NAME}..."
        curl -fsSL "https://github.com/${REPO}/releases/download/${TAG}/${PACKAGE_NAME}" | tar -xz -C "$APP_DIR"
        
        chmod +x "$APP_DIR/$BINARY_NAME"
        
        # Launcher script
        cat <<EOF > "$BIN_DIR/aoede"
#!/usr/bin/env bash
cd "$APP_DIR" && exec "./$BINARY_NAME" "\$@"
EOF
        chmod +x "$BIN_DIR/aoede"

        echo "🖼️  Installing App Icon..."
        curl -fsSL "https://raw.githubusercontent.com/${REPO}/main/Aoede/assets/icon.png" -o "$ICON_DIR/aoede.png" 2>/dev/null || true
        
        echo "📝 Creating Desktop entry..."
        cat <<EOF > "$DESKTOP_DIR/aoede.desktop"
[Desktop Entry]
Name=Aoede
Comment=Limitless music, zero trace
Exec=$BIN_DIR/aoede
Icon=$ICON_DIR/aoede.png
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;Music;
StartupWMClass=com.aoede.desktop
EOF
        chmod +x "$DESKTOP_DIR/aoede.desktop"
        
        echo ""
        echo "✅ Aoede Desktop App installed successfully to $APP_DIR"
        echo "💡 Run 'aoede' in your terminal or open it from your Application Menu!"
        ;;
    darwin)
        PACKAGE_NAME="aoede-mac-universal.tar.gz"
        BINARY_NAME="aoede-mac_universal"
        APP_PATH="/Applications/Aoede.app"

        echo "⬇️  Downloading ${PACKAGE_NAME}..."
        TEMP_DIR="$(mktemp -d)"
        curl -fsSL "https://github.com/${REPO}/releases/download/${TAG}/${PACKAGE_NAME}" | tar -xz -C "$TEMP_DIR"

        rm -rf "$APP_PATH"
        mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources"
        cp "$TEMP_DIR/$BINARY_NAME" "$APP_PATH/Contents/MacOS/aoede"
        cp "$TEMP_DIR/resources.neu" "$APP_PATH/Contents/MacOS/resources.neu"
        cp "$TEMP_DIR/resources.neu" "$APP_PATH/Contents/Resources/resources.neu"
        chmod +x "$APP_PATH/Contents/MacOS/aoede"

        ICON_SOURCE="$APP_PATH/Contents/Resources/appIcon.png"
        curl -fsSL "https://raw.githubusercontent.com/${REPO}/main/Aoede/assets/icon.png" -o "$ICON_SOURCE"

        # Finder and the Dock use an ICNS file for the bundle icon.  Registering
        # the source PNG directly can make the icon render at the wrong scale.
        ICONSET_DIR="$TEMP_DIR/Aoede.iconset"
        mkdir -p "$ICONSET_DIR"
        sips -z 16 16 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_16x16.png" >/dev/null
        sips -z 32 32 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_16x16@2x.png" >/dev/null
        sips -z 32 32 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_32x32.png" >/dev/null
        sips -z 64 64 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_32x32@2x.png" >/dev/null
        sips -z 128 128 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_128x128.png" >/dev/null
        sips -z 256 256 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null
        sips -z 256 256 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_256x256.png" >/dev/null
        sips -z 512 512 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null
        sips -z 512 512 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_512x512.png" >/dev/null
        sips -z 1024 1024 "$ICON_SOURCE" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null
        iconutil -c icns "$ICONSET_DIR" -o "$APP_PATH/Contents/Resources/appIcon.icns"

        cat <<EOF > "$APP_PATH/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>aoede</string>
    <key>CFBundleIconFile</key>
    <string>appIcon.icns</string>
    <key>CFBundleIconName</key>
    <string>appIcon</string>
    <key>CFBundleIdentifier</key>
    <string>com.aoede.desktop</string>
    <key>CFBundleName</key>
    <string>Aoede</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${TAG}</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.13</string>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
EOF
        # Remove quarantine attribute on macOS if present
        xattr -dr com.apple.quarantine "$APP_PATH" 2>/dev/null || true
        rm -rf "$TEMP_DIR"
        echo ""
        echo "✅ Aoede.app installed to /Applications/Aoede.app"
        echo "💡 Launch Aoede directly from your Applications folder or Spotlight!"
        ;;
    *)
        echo "❌ Unsupported OS: $OS"
        exit 1
        ;;
esac
