#!/usr/bin/env bash
# Installs the SENTINEL desktop app for this Linux user:
#   ~/.local/opt/sentinel/        app (poisoner/) and the Chrome extension folder (extension/) to "Load unpacked"
#   ~/.local/bin/sentinel         launcher
#   app menu entry "SENTINEL"
# Usage: tools/install-desktop.sh [--samples DIR]   (DIR = demo files listed in the app)
# Needs uv and ffmpeg. Run `pnpm build` first so the extension has its runtime and model.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
samples=""
# --samples DIR[:DIR...]  demo-file folders the app lists (default: ~/testing files if it exists)
if [[ "${1:-}" == "--samples" ]]; then
  IFS=: read -ra dirs <<<"$2"; for d in "${dirs[@]}"; do samples+="${samples:+:}$(realpath "$d")"; done
elif [[ -d "$HOME/testing files" ]]; then samples="$HOME/testing files"; fi
dest="$HOME/.local/opt/sentinel"

command -v uv >/dev/null || { echo "uv is needed: https://docs.astral.sh/uv/" >&2; exit 1; }
command -v ffmpeg >/dev/null || echo "warning: ffmpeg not found, audio and video checks will not work" >&2
[[ -f "$repo/extension/lib/transformers.min.js" ]] || { echo "run 'pnpm build' first" >&2; exit 1; }

mkdir -p "$dest" "$HOME/.local/bin" "$HOME/.local/share/applications" "$HOME/.local/share/icons/hicolor/128x128/apps"
rsync -a --delete --exclude .venv --exclude __pycache__ --exclude .pytest_cache --exclude .ruff_cache "$repo/poisoner/" "$dest/poisoner/"
rsync -a --delete "$repo/extension/" "$dest/extension/"
(cd "$dest/poisoner" && uv sync --no-dev -q)
# Fetch the three detector models now, so the app works offline from the first run.
(cd "$dest/poisoner" && uv run --no-dev python -c "from aipoisoner.detect import Detector; d = Detector(); [d.download(k) for k in ('image', 'voice', 'music')]")

{
  echo '#!/usr/bin/env bash'
  [[ -n "$samples" ]] && printf 'export SENTINEL_SAMPLES=%q\n' "$samples"
  printf 'cd %q && exec uv run --no-dev aipoisoner app "$@"\n' "$dest/poisoner"
} > "$HOME/.local/bin/sentinel"
chmod +x "$HOME/.local/bin/sentinel"

cp "$repo/extension/icons/128.png" "$HOME/.local/share/icons/hicolor/128x128/apps/sentinel.png"
cat > "$HOME/.local/share/applications/sentinel.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=SENTINEL
GenericName=AI content checker
Comment=Check images, voices and music for AI, and protect your images. Runs on this computer.
Exec=$HOME/.local/bin/sentinel
Icon=sentinel
Terminal=false
Categories=Graphics;AudioVideo;Utility;
StartupWMClass=chrome-127.0.0.1__-Default
EOF
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
echo "Installed. Start it from the app menu (SENTINEL) or run: sentinel"
echo "Chrome extension folder for Load unpacked: $dest/extension"
