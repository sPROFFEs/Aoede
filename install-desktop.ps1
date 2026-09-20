$ErrorActionPreference = 'Stop'

$Repo = "sPROFFEs/Aoede"
$Tag = "v1.2.0-wrappers"
$InstallDir = "$env:LOCALAPPDATA\Aoede"
$PackageName = "aoede-win-x64.tar.gz"

$DownloadUrl = "https://github.com/$Repo/releases/download/$Tag/$PackageName"
$ExePath = "$InstallDir\aoede-win_x64.exe"

Write-Host "🎵 Installing Aoede Desktop App ($Tag)..." -ForegroundColor Green

if (!(Test-Path $InstallDir)) {
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

$TempArchive = "$env:TEMP\aoede-win-x64.tar.gz"
Write-Host "⬇️ Downloading $PackageName..."
Invoke-WebRequest -Uri $DownloadUrl -OutFile $TempArchive

# Extract tar.gz using Windows built-in tar
tar -xzf $TempArchive -C $InstallDir
Remove-Item -Force $TempArchive

# Create Desktop & Start Menu Shortcuts
$WScriptShell = New-Object -ComObject WScript.Shell

# Desktop shortcut
$DesktopPath = [System.Environment]::GetFolderPath('Desktop')
$Shortcut = $WScriptShell.CreateShortcut("$DesktopPath\Aoede.lnk")
$Shortcut.TargetPath = $ExePath
$Shortcut.WorkingDirectory = $InstallDir
$Shortcut.Description = "Aoede Desktop Player"
$Shortcut.Save()

# Start Menu shortcut
$StartMenuPath = [System.Environment]::GetFolderPath('StartMenu')
$StartShortcut = $WScriptShell.CreateShortcut("$StartMenuPath\Programs\Aoede.lnk")
$StartShortcut.TargetPath = $ExePath
$StartShortcut.WorkingDirectory = $InstallDir
$StartShortcut.Description = "Aoede Desktop Player"
$StartShortcut.Save()

Write-Host ""
Write-Host "✅ Aoede Desktop App installed successfully to $InstallDir" -ForegroundColor Green
Write-Host "💡 Launch Aoede from your Desktop or Start Menu!" -ForegroundColor Yellow
