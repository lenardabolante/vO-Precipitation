# Creates/refreshes the "Precipitation" desktop shortcut (icon + launcher).
# Re-run after any change to run.bat, the icon, or the project location.
$root = Split-Path -Parent $PSScriptRoot
$ico  = Join-Path $root "assets\precipitation.ico"
if (-not (Test-Path $ico)) { python (Join-Path $root "tools\make_icon.py") | Out-Null }

$desktop = [Environment]::GetFolderPath("Desktop")
$lnkPath = Join-Path $desktop "Precipitation.lnk"
$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut($lnkPath)
$lnk.TargetPath       = Join-Path $root "run.bat"
$lnk.WorkingDirectory = $root
$lnk.IconLocation     = "$ico,0"
$lnk.WindowStyle      = 7   # minimized console; the game window opens normally
$lnk.Description      = "Precipitation - 2D cloud life-cycle simulator"
$lnk.Save()
Write-Output $lnkPath
