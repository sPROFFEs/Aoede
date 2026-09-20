$ErrorActionPreference = 'Stop'

$Repo = "sPROFFEs/Navipod"
$Tag = "v1.2.0-wrappers"
$InstallDir = "$env:LOCALAPPDATA\Navipod"
$PackageName = "navipod-win-x64.tar.gz"

$DownloadUrl = "https://github.com/$Repo/releases/download/$Tag/$PackageName"
$ExePath = "$InstallDir\navipod-win_x64.exe"

Write-Host "🎵 Installing Navipod Desktop App ($Tag)..." -ForegroundColor Green

if (!(Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

$TempArchive = "$env:TEMP\navipod-win-x64.tar.gz"
Write-Host "⬇️ Downloading $PackageName..."
Invoke-WebRequest -Uri $DownloadUrl -OutFile $TempArchive

# Extract tar.gz using Windows built-in tar
tar -xzf $TempArchive -C $InstallDir
Remove-Item -Force $TempArchive

# Create Desktop & Start Menu Shortcuts
$WScriptShell = New-Object -ComObject WScript.Shell

# Desktop shortcut
$DesktopPath = [System.Environment]::GetFolderPath('Desktop')
$Shortcut = $WScriptShell.CreateShortcut("$DesktopPath\Navipod.lnk")
$Shortcut.TargetPath = $ExePath
$Shortcut.WorkingDirectory = $InstallDir
$Shortcut.Description = "Navipod Desktop Player"
$Shortcut.Save()

# Start Menu shortcut
$StartMenuPath = [System.Environment]::GetFolderPath('StartMenu')
$StartShortcut = $WScriptShell.CreateShortcut("$StartMenuPath\Programs\Navipod.lnk")
$StartShortcut.TargetPath = $ExePath
$StartShortcut.WorkingDirectory = $InstallDir
$StartShortcut.Description = "Navipod Desktop Player"
$StartShortcut.Save()

Write-Host ""
Write-Host "✅ Navipod Desktop App installed successfully to $InstallDir" -ForegroundColor Green
Write-Host "💡 Launch Navipod from your Desktop or Start Menu!" -ForegroundColor Yellow
