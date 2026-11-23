# SENTINEL installer for Windows 10/11 (64-bit). In PowerShell:
#
#   irm https://github.com/YajatGhule/ai-poisoner/releases/download/v0.5.0-alpha/install.ps1 | iex
#
# Installs to %LOCALAPPDATA%\SENTINEL (no admin rights needed):
#   SENTINEL.exe, the app, a private Python 3.12 (via uv), the AI models and the Chrome extension folder.
# Adds SENTINEL to the Desktop and the Start menu, and a "SENTINEL Test Files" folder on the Desktop.
# Needs about 3 GB of disk space and an internet connection while installing; the app then works offline.

& {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest is very slow with the progress bar
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $Version = 'v0.5.0-alpha'
    $Base = "https://github.com/YajatGhule/ai-poisoner/releases/download/$Version"
    $UvUrl = 'https://github.com/astral-sh/uv/releases/latest/download/uv-x86_64-pc-windows-msvc.zip'
    $Dest = Join-Path $env:LOCALAPPDATA 'SENTINEL'
    $Desktop = [Environment]::GetFolderPath('Desktop')
    $Programs = [Environment]::GetFolderPath('Programs')
    $Tests = Join-Path $Desktop 'SENTINEL Test Files'

    function Step($msg) { Write-Host ''; Write-Host "==> $msg" -ForegroundColor Cyan }
    # For testing a build before it is released: SENTINEL_LOCAL_ASSETS = a folder holding the release files.
    function Get-File($url, $out) {
        $local = if ($env:SENTINEL_LOCAL_ASSETS) { Join-Path $env:SENTINEL_LOCAL_ASSETS (Split-Path $url -Leaf) }
        if ($local -and (Test-Path $local)) { Copy-Item $local $out }
        else { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $out }
    }

    Write-Host 'SENTINEL: on-device detection and data poisoning for AI images, voices and music' -ForegroundColor Yellow
    Write-Host "Installing $Version to $Dest"
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'SENTINEL needs 64-bit Windows.' }

    # An older copy that is still running would lock its files.
    Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($Dest, 'OrdinalIgnoreCase') } |
        Stop-Process -Force -ErrorAction SilentlyContinue

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ('sentinel-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $tmp, $Dest | Out-Null
    try {
        Step 'Downloading SENTINEL'
        Get-File "$Base/sentinel-windows.zip" "$tmp\app.zip"
        # Replace the app files; keep python\, env\, models\, hf\ and cache\ so reinstalling is quick.
        foreach ($d in 'poisoner', 'extension') { Remove-Item -Recurse -Force (Join-Path $Dest $d) -ErrorAction SilentlyContinue }
        Expand-Archive -Force "$tmp\app.zip" $Dest

        Step 'Downloading uv (it installs Python for SENTINEL only)'
        Get-File $UvUrl "$tmp\uv.zip"
        Expand-Archive -Force "$tmp\uv.zip" (Join-Path $Dest 'uv')
        $Uv = Join-Path $Dest 'uv\uv.exe'
        if (-not (Test-Path $Uv)) { throw 'uv.exe was not found in the uv download.' }

        # The same settings SENTINEL.exe uses, so everything stays inside $Dest.
        $env:UV_PYTHON_INSTALL_DIR = Join-Path $Dest 'python'
        $env:UV_CACHE_DIR = Join-Path $Dest 'cache'
        $env:UV_PROJECT_ENVIRONMENT = Join-Path $Dest 'env'
        $env:UV_PYTHON_PREFERENCE = 'only-managed'
        $env:SENTINEL_MODELS = Join-Path $Dest 'models'
        $env:HF_HOME = Join-Path $Dest 'hf'
        $env:PYTHONIOENCODING = 'utf-8'
        $project = Join-Path $Dest 'poisoner'

        Step 'Installing Python 3.12 and the AI libraries (about 1 GB, a few minutes)'
        $ErrorActionPreference = 'Continue'   # uv prints progress on stderr
        & $Uv sync --project $project --no-dev --frozen
        if ($LASTEXITCODE -ne 0) { throw "Installing the Python libraries failed (uv exit code $LASTEXITCODE)." }
        $ErrorActionPreference = 'Stop'

        Step 'Downloading the AI models (about 250 MB)'
        $ErrorActionPreference = 'Continue'
        & $Uv run --project $project --no-dev --frozen aipoisoner setup
        if ($LASTEXITCODE -ne 0) { throw "Downloading the models failed (exit code $LASTEXITCODE). Check the internet connection and run the installer again." }
        $ErrorActionPreference = 'Stop'

        Step "Putting the test files on your Desktop ($Tests)"
        Get-File "$Base/sentinel-test-files.zip" "$tmp\tests.zip"
        Expand-Archive -Force "$tmp\tests.zip" $Desktop

        Step 'Adding shortcuts'
        $shell = New-Object -ComObject WScript.Shell
        $exe = Join-Path $Dest 'SENTINEL.exe'
        $icon = Join-Path $Dest 'sentinel.ico'
        foreach ($lnkPath in (Join-Path $Desktop 'SENTINEL.lnk'), (Join-Path $Programs 'SENTINEL.lnk')) {
            $l = $shell.CreateShortcut($lnkPath)
            $l.TargetPath = $exe
            $l.Arguments = "--samples `"$Tests`""
            $l.WorkingDirectory = $Dest
            $l.IconLocation = "$icon,0"
            $l.Description = 'Check images, voices and music for AI, and protect your images. Runs on this computer.'
            $l.Save()
        }
        $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        foreach ($lnkPath in (Join-Path $Programs 'Install SENTINEL Chrome extension.lnk'), (Join-Path $Tests 'Install the Chrome extension.lnk')) {
            $e = $shell.CreateShortcut($lnkPath)
            $e.TargetPath = $ps
            $e.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Dest\install-extension.ps1`""
            $e.IconLocation = "$icon,0"
            $e.Description = 'Opens the browser extensions page and guides you through loading SENTINEL.'
            $e.Save()
        }
        $u = $shell.CreateShortcut((Join-Path $Programs 'Uninstall SENTINEL.lnk'))
        $u.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $u.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Dest\uninstall.ps1`""
        $u.IconLocation = "$icon,0"
        $u.Save()
    }
    finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }

    $ext = Join-Path $Dest 'extension'
    try { Set-Clipboard -Value $ext } catch { }
    Write-Host ''
    Write-Host 'SENTINEL is installed.' -ForegroundColor Green
    Write-Host "  App:          Desktop > SENTINEL (or the Start menu)"
    Write-Host "  Test files:   $Tests  (read 'HOW TO TEST.txt')"
    Write-Host "  Chrome extension folder (copied to your clipboard): $ext"
    Write-Host '    chrome://extensions > Developer mode > Load unpacked > paste the folder path'
    Write-Host "  Uninstall:    Start menu > Uninstall SENTINEL"

    if (-not $env:SENTINEL_NO_LAUNCH) {
        Start-Process -FilePath (Join-Path $Dest 'SENTINEL.exe') -ArgumentList "--samples `"$Tests`""
        Start-Process -FilePath 'explorer.exe' -ArgumentList "`"$Tests`""
        # The guided extension setup, in its own window.
        Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') `
            -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$Dest\install-extension.ps1`""
    }
}
