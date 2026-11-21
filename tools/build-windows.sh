#!/usr/bin/env bash
# Builds the Windows release files into dist/ (works from Linux or macOS; needs Go, bsdtar and ImageMagick):
#   dist/sentinel-windows.zip   SENTINEL.exe launcher + the app source + the Chrome extension + icon + uninstaller
#   dist/install.ps1            the installer ("irm <url> | iex")
# Run `pnpm build` first so the extension has its runtime and bundled model.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
out="$repo/dist/windows"
rm -rf "$out"; mkdir -p "$out"
[[ -f "$repo/extension/models/onnx-community/ai-image-detection-ONNX/onnx/model_quantized.onnx" ]] || { echo "run 'pnpm build' first" >&2; exit 1; }

# Icon (multi-size .ico) for the exe and the shortcuts.
magick "$repo/extension/icons/128.png" -define icon:auto-resize=128,64,48,32,16 "$out/sentinel.ico"

# The launcher (a GUI-subsystem exe, so no console window). The shortcuts carry the icon.
(
  cd "$repo/windows/launcher"
  GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-H windowsgui -s -w" -o "$out/SENTINEL.exe" .
)

# App source (no venv/caches) and the extension.
mkdir -p "$out/poisoner" "$out/extension"
rsync -a --exclude .venv --exclude __pycache__ --exclude .pytest_cache --exclude .ruff_cache --exclude tests \
  "$repo/poisoner/" "$out/poisoner/"
rsync -a "$repo/extension/" "$out/extension/"
cp "$repo/windows/uninstall.ps1" "$out/"

rm -f "$repo/dist/sentinel-windows.zip"
(cd "$out" && bsdtar -a -cf "$repo/dist/sentinel-windows.zip" .)
cp "$repo/windows/install.ps1" "$repo/dist/install.ps1"
ls -la "$repo/dist/sentinel-windows.zip" "$repo/dist/install.ps1"
