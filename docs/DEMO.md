# SENTINEL demo guide

SENTINEL: on-device detection and data poisoning for AI images, voices and music (SIDT Group G14).
Three prototypes, one model set, nothing uploaded.

| Part | What it shows | Start it |
|---|---|---|
| **Desktop app** | Check any image, voice recording, song or video; protect an image | App menu → **SENTINEL**, or `sentinel` |
| **Chrome extension** | Live AI / Unsure / Human badges on every image of a web page; right-click → Protect | Loaded once from `~/.local/opt/sentinel/extension` |
| **Android app** | On-screen overlay, live voice listening, Check a file, Protect a photo | Installed APK |

## Before the demo (once)

```sh
pnpm install && pnpm build                       # extension runtime + bundled 84 MB image model
tools/install-desktop.sh --samples "<folder of demo files>"
```

Then in Chrome: `chrome://extensions` → **Developer mode** on → **Load unpacked** →
`~/.local/opt/sentinel/extension`. Pin **SENTINEL** in the puzzle-piece menu.

Everything now works **offline**: the extension carries its model, and the installer fetched the three
desktop models into `~/.cache/sentinel/models`.

## Five-minute running order

1. **Open SENTINEL** from the app menu. The status line should read *all detectors ready offline*.
2. **Image:** click demo file `ai_01.jpg` → *Likely AI-generated (99%)*. Then `real_01.jpg` → *Likely not AI*.
3. **Spliced voice:** click `long_speech.m4a` (Voice group). *Parts sound AI*, stretch **0:58–1:24**, red bars on the
   timeline exactly where the cloned voice was spliced in. Play from 0:55 to let the jury hear the change.
4. **Music:** click a `..._suno_...mp3` → *Likely AI-generated (≈95%)*; then `EAR022.mp3` → *Likely human-made*.
5. **Protect:** *Protect an image* tab → drop a photo → Light strength (about 30 s on this laptop) → show the
   *what the AI encoder rebuilds* preview and download.
6. **Browser:** open any image-heavy page (news site, Pinterest, an AI art gallery) → badges appear within a
   second or two. Hover a badge for the reason, switch the popup to **Blur**, right-click an image →
   *Protect this image with SENTINEL* (the desktop app must be open).
7. **Phone (optional):** the recorded clips in `AI detector test files/demo_clips/` are the safe fallback.

## If something goes wrong

| Symptom | Fix |
|---|---|
| Popup dot next to *Protect an image* is red | The desktop app isn't open. Start SENTINEL. |
| No badges | Popup switch on? Page images over 80 px? `chrome://` pages are blocked for extensions. |
| A real photo shows *Unsure* | Expected: the image model is cautious (AUC ≈ 0.87). Say so; it is in the report's limits. |
| A song shows *Likely a human voice* | The voice model is for speech. Click **A song** under the result to hide it. |
| Port 8765 busy | `sentinel --port 8770` (the extension's Protect page lets you change the address). |

## What we fixed for this version (0.5.0-alpha)

- Renamed everything to **SENTINEL**, the registered project title.
- **Offline extension:** the detection model ships inside the extension, so there is no 90 MB download at the
  venue and no dependence on Hugging Face being reachable.
- **Desktop app:** the three detectors (image, voice, music) now also run on the PC, with the same models and
  cutoffs as the Android app, next to the existing Protect backend. One window, one click per demo file.
- One-command **installer** with an app-menu entry; closing the window stops the server.

## Still open (honest limits)

- Real photos sometimes land in *Unsure* (and on the phone overlay, occasionally *AI*). A stronger image model
  (SigLIP-based) is the planned fix.
- AI voices from engines the model never saw mostly come out *Unsure*.
- Short Udio clips are often missed by the music model.
- Protection is measured at the Stable Diffusion encoder only; it has not been tested against a full generator.
- Live voice listening on a real phone is not yet verified end to end.
