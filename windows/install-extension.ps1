# Installs the SENTINEL Chrome extension (Chrome, Edge or Brave on Windows), as far as a browser allows.
#
#   irm https://github.com/YajatGhule/ai-poisoner/releases/download/v0.5.0-alpha/install-extension.ps1 | iex
#
# Browsers do not let a script add an extension that is not from their store, so one step is yours:
# turning on Developer mode and clicking "Load unpacked". This script does the rest: gets the extension
# folder, copies its path, opens the extensions page and Explorer, and tells you when the browser has it.

& {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $ZipUrl = 'https://github.com/YajatGhule/ai-poisoner/releases/download/v0.5.0-alpha/sentinel-v0.5.0.zip'
    $Ext = Join-Path $env:LOCALAPPDATA 'SENTINEL\extension'

    function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }

    # 1. The extension folder: from the SENTINEL install, or downloaded on its own.
    if (-not (Test-Path (Join-Path $Ext 'manifest.json'))) {
        Say 'Downloading the SENTINEL extension (about 75 MB)...' Cyan
        New-Item -ItemType Directory -Force $Ext | Out-Null
        $zip = Join-Path ([IO.Path]::GetTempPath()) 'sentinel-extension.zip'
        Invoke-WebRequest -UseBasicParsing -Uri $ZipUrl -OutFile $zip
        Expand-Archive -Force $zip $Ext
        Remove-Item $zip -Force
    }
    $version = (Get-Content (Join-Path $Ext 'manifest.json') -Raw | ConvertFrom-Json).version_name

    # 2. Which browser: Chrome first, then Edge, then Brave.
    $browsers = @(
        @{ Name = 'Google Chrome'; Exe = 'Google\Chrome\Application\chrome.exe'; Page = 'chrome://extensions'; Data = 'Google\Chrome\User Data' },
        @{ Name = 'Microsoft Edge'; Exe = 'Microsoft\Edge\Application\msedge.exe'; Page = 'edge://extensions'; Data = 'Microsoft\Edge\User Data' },
        @{ Name = 'Brave'; Exe = 'BraveSoftware\Brave-Browser\Application\brave.exe'; Page = 'brave://extensions'; Data = 'BraveSoftware\Brave-Browser\User Data' }
    )
    $roots = $env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA
    $b = $null
    foreach ($cand in $browsers) {
        foreach ($r in $roots) {
            if ($r -and (Test-Path (Join-Path $r $cand.Exe))) { $b = $cand; $b.Path = Join-Path $r $cand.Exe; break }
        }
        if ($b) { break }
    }
    if (-not $b) { throw 'No Chrome, Edge or Brave found. Install Google Chrome and run this again.' }

    # Already installed? (Browsers list unpacked extensions with their folder in the profile preferences.)
    $dataDir = Join-Path $env:LOCALAPPDATA $b.Data
    $needle = ($Ext -replace '\\', '\\')
    function Test-Installed {
        Get-ChildItem $dataDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            foreach ($f in 'Secure Preferences', 'Preferences') {
                $p = Join-Path $_.FullName $f
                $txt = if (Test-Path $p) { Get-Content $p -Raw -ErrorAction SilentlyContinue }
                if ($txt -and $txt.Contains($needle)) { return $true }
            }
        } | Select-Object -First 1
    }
    if (Test-Installed) {
        Say "SENTINEL $version is already loaded in $($b.Name). To update it, click the reload arrow on its card in $($b.Page)." Green
        return
    }

    # 3. Get everything ready for the one click.
    try { Set-Clipboard -Value $Ext } catch { }
    Start-Process -FilePath $b.Path -ArgumentList $b.Page
    Start-Sleep -Seconds 2

    Say ''
    Say "Install SENTINEL $version in $($b.Name): three clicks" Yellow
    Say "  1. On the page that just opened, turn on 'Developer mode' (top right in Chrome, left side in Edge)."
    Say "  2. Click 'Load unpacked'."
    Say "  3. In the folder window, press Ctrl+V in the address bar and Enter (the path is on your clipboard),"
    Say "     then click 'Select Folder'. The folder is:"
    Say "       $Ext" White
    Say '  Then click the puzzle-piece icon in the toolbar and pin SENTINEL.'
    Say ''
    Say 'Waiting for the browser to show the extension (up to 5 minutes; Ctrl+C to stop)...' Cyan

    # 4. Confirm it worked.
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        if (Test-Installed) {
            Say ''
            Say "Done: SENTINEL is installed in $($b.Name)." Green
            Say 'Try it: with the SENTINEL app open, double-click "Test page for the Chrome extension" in the'
            Say '"SENTINEL Test Files" folder on the Desktop, or open any website with pictures.'
            return
        }
        Start-Sleep -Seconds 3
    }
    Say "Not seen yet. If 'Load unpacked' showed an error, make sure you picked the folder that contains manifest.json:" Yellow
    Say "  $Ext"
}
