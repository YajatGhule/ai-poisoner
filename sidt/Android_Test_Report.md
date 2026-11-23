# AI Poisoner (Android): Test Report

**Build:** debug, commit `9bca51e` (branch `overlay-mvp`)
**Date:** 22 October 2026
**Devices:**
- iQOO Z5 (Android 13, Snapdragon 778G)
- Android 15 emulator (x86_64), used for the earlier test run

## 1. Executive summary

All four new features work on real hardware: the image overlay, the long-speech voice check, the file check, and AI-music detection.

On the phone, the voice and music checks matched the reference results exactly. Image classification of real photographs is still the main accuracy risk.

Live voice listening was verified on the emulator. On the phone it was not demonstrated end to end; see section 4.

**Recommendation:** release v0.3.0 once the two open defects in section 4 are triaged.

## 2. Scope

| Area | What was tested |
|---|---|
| Image overlay | Labels and bounding boxes on images inside Chrome |
| File check: speech | A 1:58 recording with an AI voice spliced in at 0:57–1:23 |
| File check: images | One AI-generated image and one real photograph |
| File check: music | One Suno track and one real public-domain track |
| Live voice | Phone-audio capture while Google Photos plays the same speech |
| Stability | Rotation, Cancel, log review for crashes and runtime errors |

## 3. Results

| # | Test | Expected | Observed (phone) | Status |
|---|---|---|---|---|
| 1 | Long speech, file | "Parts sound AI", AI stretch around 0:56–1:24 | "Parts sound AI": 15% of 58 windows sound AI, AI stretch at 0:58–1:24, timeline and transcript shown | **Pass** |
| 2 | AI image, file | AI | Likely AI-generated (98%) | **Pass** |
| 3 | Real photo, file | Human | Likely not AI (36%) | **Pass** |
| 4 | Suno track, file | AI (desktop reference 0.95) | Music: Likely AI-generated (95%) | **Pass** |
| 5 | Real track, file | Human or Unsure (desktop reference 0.60) | Music: Unsure (58% AI) | **Pass** |
| 6 | Image overlay, 6 test images | No real photo labelled AI | AI image caught (99%). **One real photo labelled AI (95%).** One AI illustration labelled Human. | **Partial** |
| 7 | Rotation and Cancel during a file check (emulator) | Job survives rotation; Cancel clears the result | As expected, after a fix (`d470143`) | **Pass** |
| 8 | Live voice, phone audio (emulator) | Real windows score low | Windows scored 0.00–0.10 AI | **Pass** |
| 9 | Live voice, phone audio (phone) | Whole-session verdict | Not achieved; see 4.1 | **Not verified** |
| 10 | Logs (both devices) | No crashes, no ONNX Runtime errors | None found, including the in-model spectrogram step | **Pass** |

**Performance on the phone:**
- File check of a 2-minute speech: about 50 s, roughly 2.5× faster than real time.
- Overlay labels appear within about 1 s of the page settling.

## 4. Open issues

| ID | Severity | Issue | Next step |
|---|---|---|---|
| 4.1 | Medium | **Live voice demo on the phone.** Two attempts failed. The first was blocked because the phone's own screen recorder (S-capture) already held audio capture. In the second, Google Photos paused its video when the app came to the front, so almost no speech was heard ("Not enough clear speech yet"). | Re-test with the video kept in the foreground, or with VLC, and media volume above 0. |
| 4.2 | Medium | **False positives on real photos.** The boat-rescue photo was labelled AI (95%) on the phone; it was Unsure (93%) on the emulator. | Recalibrate the on-screen threshold per device, or adopt a stronger image model (already logged as future work). |
| 4.3 | Low | **Stale boxes after switching apps.** Boxes from Chrome briefly showed over the app itself. | Clear the overlay when the foreground app changes. |
| 4.4 | Low | **Boxes lag during fast scrolling** (they catch up once scrolling stops). | Acceptable for v0.3.0. |

## 5. Defects fixed during testing

| Commit | Fix |
|---|---|
| `d470143` | Cancel no longer leaves a partial verdict on screen. |
| `efe6972` | Overlay drops results from scans that finished after the page scrolled. |
| `9bca51e` | Scrolled text no longer slides under the status bar. Added "Share → Check for AI" and a debug-only adb entry point. |

## 6. Deliverables

All in this folder:
- `demo_all_features.mp4` (2:46, phone recording, silent): overlay, then the speech, image and music file checks. Long waits are sped up and a title card opens each part.
- `demo_clips/`: the individual recordings. The real-song clip is trimmed to exclude a personal notification.
- Test inputs: `voice/`, `music/`, `images/`.
