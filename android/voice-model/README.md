# AI-voice detector

The model behind the app's **Voice check**. Download it from the
[`voice-model-v2` release](https://github.com/YajatGhule/ai-poisoner/releases/tag/voice-model-v2)
(`voice_detector_v2.onnx`, 122 MB). The app fetches it automatically.

## Why we trained our own

We first tested the three public AI-voice detectors we could find on real human speech and on modern AI voices.
None of them were usable:

| Public model | Real voices called AI | AI voices caught |
|---|---|---|
| `as1605/Deepfake-audio-detection-V2` | 2 / 16 | 0 / 12 |
| `ai8shiro/deepfake-audio-wav2vec2-ONNX` | 0 / 16 | 0 / 12 (scores everything ≈ 0.08) |
| `garystafford/wav2vec2-deepfake-voice-detector` | 15 / 16 | 20 / 20 (calls everything AI) |

## v1 learned a shortcut, v2 fixes it

AI voice engines output **perfect digital silence** between words. Real recordings always have some background noise.
Our first model (v1) partly learned to detect that silence instead of the voice. We caught it while testing the app:

| v1, AI voices caught (score ≥ 0.80) | As generated | With a faint noise floor (−60 dB) added |
|---|---|---|
| Kokoro (unseen voices) | 100% | **50%** |
| MMS (unseen engine) | 90% | **41%** |

Once audio picks up any hiss (a speaker, a codec, a re-upload), v1's detection roughly halved.

**v2** is trained with silences trimmed and a random noise floor (−75 to −45 dB) added to **both** real and AI clips,
so silence carries no information. Run through the same check, v2 gives the same answer either way:

| v2, AI voices caught (≥ 0.80) | As generated | Silence trimmed | Noise floor added | Trimmed + noise |
|---|---|---|---|---|
| Kokoro (unseen voices) | 100% | 100% | 100% | 100% |
| MMS (unseen engine) | 14% | 41% | 17% | 21% |
| *Real voices wrongly flagged* | 0% | 0% | 2% | 2% |

## What it is

`facebook/wav2vec2-base` up to its **third transformer layer** (frozen), then mean and standard-deviation pooling over
time, then a **logistic regression** head. The layer and regularisation were picked by speaker-disjoint
cross-validation on the training set only.

- **Input:** 16 kHz mono, normalised per clip, about 4 seconds (`input_values [1, samples]`).
- **Output:** `logits [1, 2]`, where index 1 is AI.
- **Speed:** about 0.2 s per 4-second window on a laptop CPU.
- **Size:** it ships in full precision (122 MB) because 8-bit quantization shifted the scores badly (see `quantize_eval.py`).

## Data

The AI voices read **the same sentences** as the real clips, so the model can't tell them apart by the words.
Every clip is used three ways:
- as recorded,
- with silences trimmed and a noise floor added,
- the noisy copy put through a simulated phone line (300–3400 Hz band, Opus 16 kbps).

| | Real speech | AI speech |
|---|---|---|
| **Train** | 159 clips: LibriSpeech *test-clean* (audiobooks) and People's Speech *validation* (talks, conversation) | 159 Kokoro-82M clips (22 voices) and 80 SpeechT5 clips |
| **Test** | 57 clips: LibriSpeech *test-other* (different speakers) and People's Speech recordings not used in training | 20 Kokoro clips in **6 voices not used in training**, and 29 **Meta MMS** clips (an engine never seen in training) |

## Results on the held-out test set (v2)

The app says **AI** at a score of 0.80 or more, **Human** below 0.40, and **Unsure** in between.
Full numbers are in [`report_v2.json`](report_v2.json).

| Condition | Real voices said "AI" | Kokoro, unseen voices: "AI" | MMS, unseen engine: "AI" | AUC |
|---|---|---|---|---|
| Clean audio | **0%** | **100%** | 31% | 0.99 |
| Noise floor + trimmed | **0%** | **100%** | 41% | 0.98 |
| Phone quality | **0%** | **100%** | 34% | 0.94 |

**What this means:** it's reliable against voices from the *kind* of engine it was trained on. Kokoro is a modern,
popular open model, and the Kokoro test voices were new to it. It flags almost no real voices.
It is **weak against an engine it has never seen**: most MMS clips land in "Unsure", not "AI".

### On the emulator

Played through Google Photos and captured with the app's *Phone audio* mode:
- the AI track came out as "Likely an AI voice",
- the real-speaker track came out as "Likely a human voice".

The app also skips 4-second windows that contain digital silence or dropouts.

## Honest limits

- The test set is small (57 real and 49 AI clips) and uses **open-source** engines only. Commercial voice cloning
  (ElevenLabs, OpenAI voices, phone-scam clones of a specific person) was **not tested**. Given the MMS result, expect
  many of those to show as "Unsure".
- The real speech is English audiobooks and recorded talks. Other languages, accents, children, singing, and very
  noisy rooms weren't covered.
- The phone condition is simulated with a codec. Real speaker-to-microphone recordings add echo and room noise.

**Treat "AI" as a warning sign and "Unsure" as "can't tell". Neither is proof.**

## Reproduce

```sh
# 1. data: real clips from the HF datasets-server, AI clips from kokoro-js / transformers.js (see the docstrings)
# 2. train + evaluate + export
python train.py DATA OUT          # OUT/voice_detector_fp32.onnx, OUT/report.json
python quantize_eval.py DATA OUT  # int8 vs fp32 comparison (v1)
```
Needs `torch transformers onnx onnxruntime soundfile` and `ffmpeg`.
