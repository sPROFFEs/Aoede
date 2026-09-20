#!/usr/bin/env bash
set -e

REPO="sPROFFEs/Navipod"
TAG="v1.2.0-wrappers"

echo "🎵 Installing Navipod Desktop App ($TAG)..."

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$OS" in
    linux)
        case "$ARCH" in
            x86_64|amd64) PACKAGE_NAME="navipod-linux-x64.tar.gz"; BINARY_NAME="navipod-linux_x64" ;;
            arm64|aarch64) PACKAGE_NAME="navipod-linux-arm64.tar.gz"; BINARY_NAME="navipod-linux_arm64" ;;
            *) echo "❌ Unsupported architecture: $ARCH"; exit 1 ;;
        esac

        APP_DIR="$HOME/.local/share/navipod"
        BIN_DIR="$HOME/.local/bin"
        DESKTOP_DIR="$HOME/.local/share/applications"
        ICON_DIR="$HOME/.local/share/icons/hicolor/512x512/apps"
        
        mkdir -p "$APP_DIR" "$BIN_DIR" "$DESKTOP_DIR" "$ICON_DIR"
        
        echo "⬇️  Downloading ${PACKAGE_NAME}..."
        curl -fsSL "https://github.com/${REPO}/releases/download/${TAG}/${PACKAGE_NAME}" | tar -xz -C "$APP_DIR"
        
        chmod +x "$APP_DIR/$BINARY_NAME"
        
        # Launcher script
        cat <<EOF > "$BIN_DIR/navipod"
#!/usr/bin/env bash
cd "$APP_DIR" && exec "./$BINARY_NAME" "\$@"
EOF
        chmod +x "$BIN_DIR/navipod"

        echo "🖼️  Installing App Icon..."
        curl -fsSL "https://raw.githubusercontent.com/${REPO}/main/Navipod/assets/icon.png" -o "$ICON_DIR/navipod.png" 2>/dev/null || true
        
        echo "📝 Creating Desktop entry..."
        cat <<EOF > "$DESKTOP_DIR/navipod.desktop"
[Desktop Entry]
Name=Navipod
Comment=Limitless music, zero trace
Exec=$BIN_DIR/navipod
Icon=$ICON_DIR/navipod.png
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;Music;
StartupWMClass=com.navipod.desktop
EOF
        chmod +x "$DESKTOP_DIR/navipod.desktop"
        
        echo ""
        echo "✅ Navipod Desktop App installed successfully to $APP_DIR"
        echo "💡 Run 'navipod' in your terminal or open it from your Application Menu!"
        ;;
    darwin)
        PACKAGE_NAME="navipod-mac-universal.tar.gz"
        BINARY_NAME="navipod-mac_universal"
        APP_PATH="/Applications/Navipod.app"

        echo "⬇️  Downloading ${PACKAGE_NAME}..."
        TEMP_DIR="$(mktemp -d)"
        curl -fsSL "https://github.com/${REPO}/releases/download/${TAG}/${PACKAGE_NAME}" | tar -xz -C "$TEMP_DIR"

        mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources"
        cp "$TEMP_DIR/$BINARY_NAME" "$APP_PATH/Contents/MacOS/navipod"
        cp "$TEMP_DIR/resources.neu" "$APP_PATH/Contents/MacOS/resources.neu"
        chmod +x "$APP_PATH/Contents/MacOS/navipod"

        curl -fsSL "https://raw.githubusercontent.com/${REPO}/main/Navipod/assets/icon.png" -o "$APP_PATH/Contents/Resources/appIcon.png" 2>/dev/null || true

        cat <<EOF > "$APP_PATH/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>navipod</string>
    <key>CFBundleIconFile</key>
    <string>appIcon.png</string>
    <key>CFBundleIdentifier</key>
    <string>com.navipod.desktop</string>
    <key>CFBundleName</key>
    <string>Navipod</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>1.2.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.13</string>
</dict>
</plist>
EOF
        rm -rf "$TEMP_DIR"
        echo ""
        echo "✅ Navipod.app installed to /Applications/Navipod.app"
        echo "💡 Launch Navipod directly from your Applications folder or Spotlight!"
        ;;
    *)
        echo "❌ Unsupported OS: $OS"
        exit 1
        ;;
esac
