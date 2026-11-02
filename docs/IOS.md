# Aoede for iOS (IPA)

**Aoede for iOS** provides a dedicated, native wrapper for iPhone and iPad with continuous background audio playback, Apple Media Remote controls (Lock Screen, Control Center, AirPods), and server configuration.

---

## 1. Installation via Sideloading

Because iOS requires applications outside the App Store to be signed, you can install the `aoede-ios.ipa` file using any popular sideloading tool:

### A. SideStore (Recommended – On-device wireless renewal)
1. Install [SideStore](https://sidestore.io) on your iOS device.
2. Download `aoede-ios.ipa` from the GitHub Releases or Actions artifacts.
3. Open SideStore, go to **My Apps** → **+**, and select `aoede-ios.ipa`.
4. The app installs and auto-refreshes over your local Wi-Fi.

### B. AltStore
1. Install [AltStore](https://altstore.io) on your iPhone/iPad via AltServer (Mac/Windows).
2. Download `aoede-ios.ipa`.
3. Share the file to AltStore or tap **+** in the AltStore **My Apps** tab to install.

### C. Sideloadly (Mac & Windows)
1. Download and open [Sideloadly](https://sideloadly.io/).
2. Connect your iPhone via USB.
3. Drag and drop `aoede-ios.ipa` into Sideloadly, enter your Apple ID, and click **Start**.

### D. TrollStore (iOS 14.0 – 16.6.1 / 17.0)
- If your device is compatible with TrollStore, simply share `aoede-ios.ipa` to TrollStore for permanent installation with no 7-day expiration.

---

## 2. First Launch & Setup

1. Open **Aoede** on your iOS device.
2. Enter your server's root URL:
   - For remote servers: `https://aoede.example.com`
   - For local networks: `http://192.168.1.50:8000`
3. Tap **Connect**.
4. Log in with your Aoede user credentials.

> [!TIP]
> **Changing Server Address**: If you ever need to change the server address, navigate to `aoede://settings` inside the app or re-launch the setup view.

---

## 3. Key Capabilities & iOS Integration

- **Uninterrupted Background Playback**: Configures `AVAudioSessionCategoryPlayback` so music continues seamlessly when you lock your screen or switch apps.
- **Lock Screen & Control Center Controls**: Supports system media controls (`MPRemoteCommandCenter`) for Play, Pause, Next Track, Previous Track, and scrubbing.
- **Audio Routing & Interruption Handling**: Pauses smoothly when receiving a phone call or when Bluetooth/AirPods disconnect (`oldDeviceUnavailable`), preventing unwanted audio blasting.
- **Pull to Refresh**: Pull down from the top to reload the page state.
- **Themes & Responsive UI**: Native support for Aoede's Classic, Glass, and Vitrine themes with safe-area notch and Dynamic Island spacing.

---

## 4. Building from Source

### Using Xcode (macOS)
```bash
git clone https://github.com/sPROFFEs/Aoede.git
cd Aoede/ios
open Aoede.xcodeproj
```
Select your connected iPhone or a Simulator, choose your personal signing team under **Signing & Capabilities**, and hit **Run** (`⌘R`).

### Building the IPA via CLI
```bash
xcodebuild archive \
  -project ios/Aoede.xcodeproj \
  -scheme Aoede \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath ios/build/Aoede.xcarchive \
  CODE_SIGNING_ALLOWED=NO

bash ios/scripts/package_ios.sh ios/build ios/dist
```
The output IPA is saved to `ios/dist/aoede-ios.ipa`.
