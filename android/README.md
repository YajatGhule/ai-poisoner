# AI Poisoner for Android

The Android version of the AI Poisoner detector. Two features, both running **on the phone**:

1. **Image labels in any app.** An Accessibility Service finds the pictures on screen (Instagram, Chrome,
   WhatsApp, anything), screenshots just those boxes, and draws an **AI / Unsure / Human** badge on each one.
   It uses the same model as the Chrome extension. Optionally it **hides** images rated AI.
2. **Voice check.** It listens to the **microphone** or to **audio other apps are playing** (YouTube, Reels,
   voice notes) and shows a live verdict, *likely AI voice* or *likely human*, plus a **live transcript**.

> **Status: source code only, not built yet.** Every Kotlin file type-checks against the Android 15 API
> (`kotlinc` + `android.jar`), but the APK hasn't been built or run on a phone. Expect a round of fixes the
> first time it runs on a device.

## How it works

```
ImageScanService (AccessibilityService)
  window changes / scrolls ─► find ImageView/Image nodes ─► takeScreenshot() ─► crop each box
     ─► ImageDetector (ONNX, ViT, 224×224) ─► OverlayView badges (TYPE_ACCESSIBILITY_OVERLAY)

VoiceService (foreground service)
  AudioRecord: MIC  or  AudioPlaybackCapture (MediaProjection)        16 kHz mono
     ├─► Transcriber (Vosk, offline) ──────────────────────► live transcript
     └─► last 4 s, every 2 s, skip silence ─► VoiceDetector (ONNX) ─► smoothed verdict
                                                                     ─► notification + MainActivity
```

| File | What it does |
|---|---|
| `MainActivity.kt` | The single screen: model downloads, accessibility switch, voice controls, transcript |
| `ImageScanService.kt` | Accessibility service: finds images, screenshots, scores, caches by perceptual hash |
| `OverlayView.kt` | Draws badges (and the optional "AI image hidden" cover) over other apps |
| `VoiceService.kt` | Foreground service that records the mic or other apps' audio and runs both models |
| `Detectors.kt` | ONNX Runtime wrappers for the image and voice models |
| `Transcriber.kt` | Vosk speech-to-text wrapper |
| `Models.kt` | Downloads the three models on first use (they are not bundled, so the APK stays small) |
| `voice-model/` | How the AI-voice model was trained and evaluated |

### Models (downloaded inside the app)

| Model | Size | Source |
|---|---|---|
| Image detector | 87 MB | [`onnx-community/ai-image-detection-ONNX`](https://huggingface.co/onnx-community/ai-image-detection-ONNX) (8-bit) |
| AI-voice detector | 66 MB | trained for this project ([details and accuracy](voice-model/README.md)), published as a [release asset](https://github.com/YajatGhule/ai-poisoner/releases/tag/voice-model-v1) |
| Speech-to-text | 40 MB | [Vosk small English](https://alphacephei.com/vosk/models) |

## Building the APK

You need **Android Studio** (it bundles the right Java and the Android SDK), or the command-line SDK plus **JDK 21**.
Gradle 8.11 does not run on newer Java versions such as 27.

**Android Studio:** *File → Open* → choose this `android/` folder → wait for Gradle sync → *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

**Command line:**
```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk
```
The release build is signed with the debug key so it installs directly. Use your own key before publishing anywhere.

## Installing on a phone (Android 11 or newer)

1. Copy `app-release.apk` to the phone and open it. Allow "install unknown apps" for your file manager when asked.
2. Open **AI Poisoner** and tap **Download** next to each model (about 195 MB in total, once).
3. **Image labels:** tap *Open Accessibility settings* → *Installed apps* → **AI Poisoner image labels** → turn it on.
   - If the switch is **greyed out**, Android is blocking it because the app came from an APK (Android 13+).
     Go to *Settings → Apps → AI Poisoner → ⋮ (top right) → Allow restricted settings*, then try again.
4. **Voice check:** tap **Microphone** or **Phone audio**, and allow the microphone and notifications.
   *Phone audio* also asks to "start recording or casting". That's how Android gives an app access to other apps' sound.

## Limits

- **Calls can't be checked directly.** Android does not let any app capture phone calls or WhatsApp/VoIP calls.
  Put the call on speaker and use **Microphone**.
- Some apps opt out of audio capture. *Phone audio* then hears silence.
- `takeScreenshot()` is limited to about 3 per second, so badges appear about half a second after scrolling stops.
- Images are found through the accessibility tree. Apps that draw images in a way that isn't reported
  (some games, custom canvases) won't get badges.
- **Both detectors are estimates, not proof.** On held-out tests the voice detector called 0–2% of real voices AI
  and caught 72–100% of AI voices ([details](voice-model/README.md)), but it was not tested on commercial voice
  cloning. Never use a verdict alone to accuse someone.
