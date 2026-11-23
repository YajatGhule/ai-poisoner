#!/usr/bin/env bash
# Guided install of the SENTINEL Chrome extension on Linux (Chrome, Chromium, Edge or Brave).
# Browsers don't let a script add an extension that isn't from their store, so one step is yours:
# Developer mode + "Load unpacked". This copies the folder path, opens the extensions page and
# tells you when the browser has the extension.
# Usage: tools/install-extension.sh [extension folder]   (default: ~/.local/opt/sentinel/extension)
set -euo pipefail
ext="${1:-$HOME/.local/opt/sentinel/extension}"
ext="$(realpath "$ext")"
[[ -f "$ext/manifest.json" ]] || { echo "No extension at $ext. Run tools/install-desktop.sh first (or pass the folder)." >&2; exit 1; }

# browser binary, extensions page, profile folder
for b in "google-chrome-stable chrome://extensions $HOME/.config/google-chrome" \
         "google-chrome chrome://extensions $HOME/.config/google-chrome" \
         "chromium chrome://extensions $HOME/.config/chromium" \
         "microsoft-edge-stable edge://extensions $HOME/.config/microsoft-edge" \
         "brave-browser brave://extensions $HOME/.config/BraveSoftware/Brave-Browser" \
         "brave brave://extensions $HOME/.config/BraveSoftware/Brave-Browser"; do
  read -r bin page data <<<"$b"
  if command -v "$bin" >/dev/null; then break; fi
  bin=""
done
[[ -n "$bin" ]] || { echo "No Chromium-based browser found." >&2; exit 1; }

installed() { grep -lsF "\"path\":\"$ext\"" "$data"/*/Preferences "$data"/*/"Secure Preferences" >/dev/null 2>&1; }
if installed; then echo "SENTINEL is already loaded in $bin. To update, click the reload arrow on its card in $page."; exit 0; fi

if command -v wl-copy >/dev/null; then printf %s "$ext" | wl-copy
elif command -v xclip >/dev/null; then printf %s "$ext" | xclip -selection clipboard; fi
nohup "$bin" "$page" >/dev/null 2>&1 &

cat <<EOF

Install SENTINEL in $bin: three clicks
  1. On the page that just opened, turn on "Developer mode" (top right).
  2. Click "Load unpacked".
  3. Press Ctrl+L, paste the path (it is on your clipboard) and press Enter, then click Select:
       $ext
  Then pin SENTINEL from the puzzle-piece menu.

Waiting for the browser to show the extension (up to 5 minutes; Ctrl+C to stop)...
EOF
for _ in $(seq 100); do
  if installed; then echo; echo "Done: SENTINEL is installed in $bin."; exit 0; fi
  sleep 3
done
echo "Not seen yet. Make sure you picked the folder that contains manifest.json: $ext"
