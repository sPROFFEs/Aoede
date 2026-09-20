#!/usr/bin/env bash
set -e

REPO="sPROFFEs/Navipod"
TAG="v1.2.0-wrappers"
INSTALL_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/512x512/apps"

echo "🎵 Installing Navipod Desktop Player ($TAG)..."

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$ARCH" in
    x86_64|amd64) ARCH_NAME="amd64" ;;
    arm64|aarch64) ARCH_NAME="arm64" ;;
    *) echo "❌ Unsupported architecture: $ARCH"; exit 1 ;;
esac

case "$OS" in
    linux)
        BINARY_NAME="navipod-linux-${ARCH_NAME}"
        DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/${BINARY_NAME}"
        
        mkdir -p "$INSTALL_DIR" "$DESKTOP_DIR" "$ICON_DIR"
        
        echo "⬇️  Downloading ${BINARY_NAME}..."
        curl -fsSL "$DOWNLOAD_URL" -o "$INSTALL_DIR/navipod"
        chmod +x "$INSTALL_DIR/navipod"
        
        echo "🖼️  Installing App Icon..."
        curl -fsSL "https://raw.githubusercontent.com/${REPO}/main/Navipod/assets/icon.png" -o "$ICON_DIR/navipod.png" 2>/dev/null || true
        
        echo "📝 Creating Desktop entry..."
        cat <<EOF > "$DESKTOP_DIR/navipod.desktop"
[Desktop Entry]
Name=Navipod
Comment=Limitless music, zero trace
Exec=$INSTALL_DIR/navipod
Icon=$ICON_DIR/navipod.png
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Player;Music;
StartupWMClass=Navipod
EOF
        chmod +x "$DESKTOP_DIR/navipod.desktop"
        
        echo ""
        echo "✅ Navipod Desktop installed successfully to $INSTALL_DIR/navipod"
        echo "💡 Run 'navipod' in terminal or launch it from your application menu."
        ;;
    darwin)
        BINARY_NAME="navipod-darwin-${ARCH_NAME}"
        DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/${BINARY_NAME}"
        APP_PATH="/Applications/Navipod.app"
        
        echo "⬇️  Downloading ${BINARY_NAME}..."
        mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources"
        curl -fsSL "$DOWNLOAD_URL" -o "$APP_PATH/Contents/MacOS/navipod"
        chmod +x "$APP_PATH/Contents/MacOS/navipod"
        
        cat <<EOF > "$APP_PATH/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>navipod</string>
    <key>CFBundleIdentifier</key>
    <string>com.navipod.desktop</string>
    <key>CFBundleName</key>
    <string>Navipod</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${TAG}</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.13</string>
</dict>
</plist>
EOF
        
        echo ""
        echo "✅ Navipod Desktop installed to /Applications/Navipod.app"
        echo "💡 Open Navipod from your Applications folder or Spotlight search."
        ;;
    *)
        echo "❌ Unsupported OS: $OS"
        exit 1
        ;;
esac
