# AI Poisoner for Android

The Android version of the AI Poisoner detector. Two features, both running **on the phone**:

1. **Image labels in any app.** An Accessibility Service finds the pictures on screen (Instagram, Chrome,
   WhatsApp, anything), screenshots just those boxes, and draws an **AI / Unsure / Human** badge on each one.
   It uses the same model as the Chrome extension. Optionally it **hides** images rated AI.
2. **Voice check.** It listens to the **microphone** or to **audio other apps are playing** (YouTube, Reels,
   voice notes) and shows a live verdict, *likely AI voice* or *likely human*, plus a **live transcript**.

> **Status: built and tested on an emulator, not yet on a physical phone.** The app builds from the command line
> and was tested on an Android 15 emulator (x86_64, Google APIs). Real phones differ (cameras, vendors' Android
> skins, audio apps), so expect some rough edges.

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
| AI-voice detector | 122 MB | trained for this project ([details and accuracy](voice-model/README.md)), published as a [release asset](https://github.com/YajatGhule/ai-poisoner/releases/tag/voice-model-v2) |
| Speech-to-text | 40 MB | [Vosk small English](https://alphacephei.com/vosk/models) |

## Building the APK

You need **Android Studio** (it bundles the right Java and the Android SDK), or the command-line SDK
(platform 35, build-tools 35) plus **JDK 21**. Gradle 8.11 does not run on newer Java versions such as 27.
The command-line build was done this way.

**Android Studio:** *File → Open* → choose this `android/` folder → wait for Gradle sync → *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

**Command line:**
```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease          # → four APKs in app/build/outputs/apk/release/
```
This produces one APK per CPU type (see the table below). The release build is signed with the debug key so it
installs directly. Use your own key before publishing anywhere.

If Gradle's own download times out at the start (it can happen when Java picks IPv6 on a network that doesn't
really support it), force IPv4:
```sh
GRADLE_OPTS="-Djava.net.preferIPv4Stack=true" ./gradlew assembleRelease
```

## Installing on a phone (Android 11 or newer)

### 1. Pick an APK

Download one file from the [**latest release**](https://github.com/YajatGhule/ai-poisoner/releases/latest).
They are the same app, built for different phone processors:

| File | Size | Use it on |
|---|---|---|
| `app-arm64-v8a-release.apk` | ~30 MB | Almost every phone from the last ~8 years. **Recommended.** |
| `app-armeabi-v7a-release.apk` | ~24 MB | Older 32-bit phones |
| `app-x86_64-release.apk` | ~33 MB | Emulators and Chromebooks |
| `app-universal-release.apk` | ~82 MB | Works on everything, just bigger |

Not sure which one you have? Use **universal**. It always works.

### 2. Install and set up

1. On the phone, open the release page, download the APK and open it. Android will ask you to allow installing
   from this source (your browser or file manager). Allow it, then install.
2. Open **AI Poisoner** and tap **Download** next to each model (about 250 MB in total, once; use Wi-Fi).
3. **Image labels:** tap *Open Accessibility settings* → *Installed apps* → **AI Poisoner image labels** → turn it on.
   - If the switch is **greyed out**, Android is blocking it because the app came from an APK (Android 13+).
     Go to *Settings → Apps → AI Poisoner → ⋮ (top right) → Allow restricted settings*, then try again.
4. **Voice check:** tap **Microphone** or **Phone audio**, and allow the microphone and notifications.
   *Phone audio* also asks to "start recording or casting". That's how Android gives an app access to other apps' sound.

## Limits

- **Calls can't be checked directly.** Android does not let any app capture phone calls or WhatsApp/VoIP calls.
  Put the call on speaker and use **Microphone**.
- Some apps opt out of audio capture. *Phone audio* then hears silence. **Chrome is one of them** (its package
  lacks `ALLOW_AUDIO_PLAYBACK_CAPTURE`). YouTube, YouTube Music, Google Photos and Google Messages allow capture
  and worked in testing.
- **Voice verdicts need a few seconds of continuous sound.** Windows with digital silence or dropouts are skipped,
  because they made real voices look synthetic.
- **Image labels are less accurate here than in the Chrome extension.** The app only sees the downscaled,
  cropped picture on screen, which pushes real photos toward "AI". So the app uses a stricter cutoff: **AI only at
  95% or more**; 60–95% shows **Unsure**. In a simulation on our 36-image test set, this flagged 0% of real photos
  as AI and caught about 45% of AI images. The rest show as Unsure. On the emulator test page, no real photo was
  labelled AI.
- The **first image scan after starting can take several seconds** while the model warms up. Later scans are
  fast, and results are cached per picture.
- `takeScreenshot()` is limited to about 3 per second, so badges appear about half a second after scrolling stops.
- Images are found through the accessibility tree. Apps that draw images in a way that isn't reported
  (some games, custom canvases) won't get badges.
- **Both detectors are estimates, not proof.** For the voice detector, see the measured accuracy in
  [voice-model/README.md](voice-model/README.md). It was not tested on commercial voice cloning. Never use a verdict alone to accuse someone.

## What could make it better

- **A stronger image model.** In a small benchmark on simulated phone crops,
  [`Ateeqq/ai-vs-human-image-detector`](https://huggingface.co/Ateeqq/ai-vs-human-image-detector) (SigLIP) ranked
  AI vs real much better than the current model (AUC 0.95 vs 0.85). But it is 327 MB in full precision, it lost
  most of that edge when quantized to a phone-friendly 82 MB, and its license wasn't checked. Worth retrying with
  better quantization and a bigger test set.
- **More voice engines in training.** The voice detector is reliable on Kokoro-style voices but mostly says "Unsure"
  for an engine it has never seen. Training on more engines (and on real phone recordings) is the obvious next step.
