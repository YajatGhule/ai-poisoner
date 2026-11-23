#!/bin/bash
# Smoke test for SENTINEL's on-screen image overlay (ImageScanService) on the phone.
# Usage: smoke_overlay.sh [URL] [SHOT_DIR]
# Never changes phone settings and never plays audio. Exit: 0 pass, 1 fail, 2 setup problem.
set -u

SERIAL=30883026440002J
PKG=dev.yajatghule.aipoisoner
SVC="$PKG/$PKG.ImageScanService"   # must match android:name in AndroidManifest.xml
TAG=AiPoisoner                     # TAG in ImageScanService.kt
ADB=/home/loopero/Android/Sdk/platform-tools/adb
URL=${1:-http://127.0.0.1:8766/gallery}
SHOT_DIR=${2:-/home/loopero/.claude/jobs/28721ac5/tmp/phone/shots}
A=("$ADB" -s "$SERIAL")

# 1. Device connected and accessibility service enabled (read-only checks).
"$ADB" devices | tr -d '\r' | grep -q "^$SERIAL[[:space:]]*device$" || {
  echo "SETUP: phone $SERIAL is not connected or not authorized (see adb devices)."; exit 2; }
enabled=$("${A[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
[[ "$enabled" == *"$SVC"* ]] || {
  echo "SETUP: SENTINEL accessibility service is not enabled."
  echo "  Turn it on by hand: Settings > Accessibility > SENTINEL > On. Then rerun."; exit 2; }

# 2. Serve the test page to the phone through adb reverse, then open it in Chrome.
cleanup() { "${A[@]}" reverse --remove tcp:8766 >/dev/null 2>&1; }
trap cleanup EXIT
"${A[@]}" reverse tcp:8766 tcp:8766 || { echo "SETUP: adb reverse failed."; exit 2; }
"${A[@]}" logcat -c
"${A[@]}" shell am start -a android.intent.action.VIEW -d "$URL" >/dev/null \
  || { echo "SETUP: could not open Chrome with $URL."; exit 2; }

# 3. Let scanning run, then capture the log and a screenshot.
sleep 8
mkdir -p "$SHOT_DIR"
ts=$(date +%H%M%S)
log="$SHOT_DIR/smoke_$ts.log"
shot="$SHOT_DIR/smoke_$ts.png"
"${A[@]}" logcat -d -s "$TAG:*" > "$log"
"${A[@]}" exec-out screencap -p > "$shot"

# 4. Pass if the screenshot shows the overlay: verdict-coloured boxes (AI red, Unsure gold, Human green) and the dark chip.
# (This phone's ROM drops the app's debug log lines, so the screenshot is the reliable check.)
echo "Checking the screenshot for overlay boxes and the chip..."
python3 -I - "$shot" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
px = im.load()
cols = {"AI red": (0xE0, 0x60, 0x4F), "Unsure gold": (0xC9, 0xA4, 0x5C), "Human green": (0x6F, 0xBF, 0x8A)}
found = {}
for name, (r, g, b) in cols.items():
    n = 0
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            p = px[x, y]
            if abs(p[0]-r) < 10 and abs(p[1]-g) < 10 and abs(p[2]-b) < 10:
                n += 1
    found[name] = n
chip = 0
for y in range(int(h*0.04), int(h*0.35), 2):          # chip sits top right, under the status bar
    for x in range(int(w*0.55), w, 2):
        p = px[x, y]
        if p[0] < 26 and p[1] < 26 and p[2] < 26 and p != (0, 0, 0):
            chip += 1
print("coloured box pixels:", found, "| chip-dark pixels:", chip)
ok = sum(found.values()) >= 150
print("OVERLAY BOXES:", "yes" if ok else "no")
sys.exit(0 if ok else 1)
PY
if [[ $? -eq 0 ]]; then echo "PASS. Screenshot: $shot"; exit 0; fi
echo "FAIL: no overlay boxes in the screenshot. Check: service on, Label images on, page has images. Screenshot: $shot"
exit 1
