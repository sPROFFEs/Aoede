# Desktop apps

Aoede Desktop is a lightweight wrapper for your server's web interface. Choose a server on first launch, then sign in with your Aoede account. The server supplies the interface, themes and music; the wrapper provides the application window.

Desktop 1.2.10 uses the new silver A/waveform artwork in the window icon, macOS application bundle/Dock and Windows executable/shortcuts. Canvas dimensions and existing visual bounds are preserved. All icon variants derive from `branding/aoede.png`; see the [branding generation instructions](THEMES.md).

## Choose a download

Get the current packages from [GitHub Releases](https://github.com/sPROFFEs/Aoede/releases/latest).

| System | Release archive |
|---|---|
| Windows x64 | `aoede-win-x64.tar.gz` |
| macOS, Apple silicon or Intel | `aoede-mac-universal.tar.gz` |
| Linux x64 | `aoede-linux-x64.tar.gz` |
| Linux ARM64 | `aoede-linux-arm64.tar.gz` |

Separate macOS ARM64 and x64 archives may also be available. Extract the complete archive: the executable needs its accompanying resources. The macOS archive includes `Aoede.app`.

## Install with the helper

The repository provides installers that download the latest package and create the platform's application entry.

### Linux and macOS

```bash
curl -fsSL https://raw.githubusercontent.com/sPROFFEs/Aoede/main/install-desktop.sh | bash
```

Linux selects x64 or ARM64 and installs under `~/.local/share/aoede`, with a launcher in `~/.local/bin` and an application-menu entry. macOS installs the universal app under `/Applications/Aoede.app`.

### Windows

Run in PowerShell:

```powershell
irm https://raw.githubusercontent.com/sPROFFEs/Aoede/main/install-desktop.ps1 | iex
```

The installer uses `%LOCALAPPDATA%\Aoede` and creates Desktop and Start Menu shortcuts. It requires the Windows `tar` command to extract the archive. You can also download and extract the package manually.

## Connect or change servers

Enter the root URL of your server, for example `https://aoede.example.com`, rather than a Navidrome user endpoint. Aoede saves the address in the system application-data directory and reuses it on later launches.

To reopen server setup when the saved server is unreachable, launch the executable with `--setup`. For example:

```bash
aoede --setup
```

That command uses the Linux installer's launcher. On macOS, run `/Applications/Aoede.app/Contents/MacOS/aoede --setup`; on Windows, add `--setup` to the installed `aoede-win_x64.exe` command.

## Updates and playback

The application checks for a matching desktop package and offers a manual download when an update is available. **About & updates** retains the release links. Desktop and server version numbers are separate: updating the wrapper does not update the server, and server UI fixes normally arrive when its interface is reloaded.

Themes, playlists, lyrics and playback controls use the same frontend as the browser. See the [User Guide](USER-GUIDE.md) for those features, [Themes](THEMES.md) for appearance settings, and [Troubleshooting](TROUBLESHOOTING.md) for server or connection problems.

Server releases and desktop binaries have independent versions. Server tags such as `v1.3.0` retain the existing compatible desktop assets. The Desktop Release workflow responds to `desktop-v*` tags or explicit workflow dispatch; dispatch with `release_tag` only when a desktop binary actually needs rebuilding.
