# Removes SENTINEL from this computer: %LOCALAPPDATA%\SENTINEL and the shortcuts.
# The "SENTINEL Test Files" folder on the Desktop is left for you to delete.
# The Chrome extension is removed in chrome://extensions (click Remove on its card).
$ErrorActionPreference = 'Continue'
$Dest = Join-Path $env:LOCALAPPDATA 'SENTINEL'
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($Dest, 'OrdinalIgnoreCase') } |
    Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
$Desktop = [Environment]::GetFolderPath('Desktop')
$Programs = [Environment]::GetFolderPath('Programs')
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $Desktop 'SENTINEL.lnk'), (Join-Path $Programs 'SENTINEL.lnk'), (Join-Path $Programs 'Uninstall SENTINEL.lnk')
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Dest
if (Test-Path $Dest) { Write-Host "Some files are still in use. Close SENTINEL and run this again: $Dest" -ForegroundColor Yellow }
else { Write-Host 'SENTINEL was removed.' -ForegroundColor Green }
Write-Host 'Remove the Chrome extension in chrome://extensions. The Desktop folder "SENTINEL Test Files" is yours to delete.'
Start-Sleep -Seconds 4
