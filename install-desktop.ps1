$ErrorActionPreference = 'Stop'

$Repo = "sPROFFEs/Navipod"
$Tag = "v1.2.0"
$InstallDir = "$env:LOCALAPPDATA\Navipod"
$BinaryName = "navipod-windows-amd64.exe"

if ([System.Environment]::Is64BitOperatingSystem) {
    if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") {
        $BinaryName = "navipod-windows-arm64.exe"
    }
}

$DownloadUrl = "https://github.com/$Repo/releases/download/$Tag/$BinaryName"
$ExePath = "$InstallDir\navipod.exe"

Write-Host "🎵 Installing Navipod Desktop Player ($Tag)..." -ForegroundColor Green

if (!(Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

Write-Host "⬇️ Downloading $BinaryName..."
Invoke-WebRequest -Uri $DownloadUrl -OutFile $ExePath

# Create Desktop & Start Menu Shortcuts
$WScriptShell = New-Object -ComObject WScript.Shell

# Desktop shortcut
$DesktopPath = [System.Environment]::GetFolderPath('Desktop')
$Shortcut = $WScriptShell.CreateShortcut("$DesktopPath\Navipod.lnk")
$Shortcut.TargetPath = $ExePath
$Shortcut.Description = "Navipod Desktop Player"
$Shortcut.Save()

# Start Menu shortcut
$StartMenuPath = [System.Environment]::GetFolderPath('StartMenu')
$StartShortcut = $WScriptShell.CreateShortcut("$StartMenuPath\Programs\Navipod.lnk")
$StartShortcut.TargetPath = $ExePath
$StartShortcut.Description = "Navipod Desktop Player"
$StartShortcut.Save()

Write-Host ""
Write-Host "✅ Navipod Desktop installed successfully to $ExePath" -ForegroundColor Green
Write-Host "💡 Launch Navipod from your Desktop or Start Menu!" -ForegroundColor Yellow
