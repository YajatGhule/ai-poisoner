# AI-voice detector

The model behind the app's **Voice check**. Download it from the
[`voice-model-v1` release](https://github.com/YajatGhule/ai-poisoner/releases/tag/voice-model-v1)
(`voice_detector.onnx`, 66 MB). The app fetches it automatically.

## Why we trained our own

We first tested the three public AI-voice detectors we could find on real human speech and on modern AI voices.
None of them were usable:

| Public model | Real voices called AI | AI voices caught |
|---|---|---|
| `as1605/Deepfake-audio-detection-V2` | 2 / 16 | 0 / 12 |
| `ai8shiro/deepfake-audio-wav2vec2-ONNX` | 0 / 16 | 0 / 12 (scores everything ≈ 0.08) |
| `garystafford/wav2vec2-deepfake-voice-detector` | 15 / 16 | 20 / 20 (calls everything AI) |

## What it is

`facebook/wav2vec2-base` up to its **first transformer layer** (frozen), then mean and standard-deviation pooling
over time, then a **logistic regression** head. The layer and regularisation were picked by
speaker-disjoint cross-validation on the training set only.
Input is 16 kHz mono, normalised per clip, about 4 seconds (`input_values [1, samples]`).
Output is `logits [1, 2]`, where index 1 is AI.
It takes about 0.2 s per 4-second window on a laptop CPU.

## Data

The AI voices read **the same sentences** as the real clips, so the model can't tell them apart by the words.
Every clip is also used as a phone-quality copy (300–3400 Hz band, Opus 16 kbps round trip).

| | Real speech | AI speech |
|---|---|---|
| **Train** | 159 clips: LibriSpeech *test-clean* (audiobooks) and People's Speech *validation* (talks, conversation) | 159 Kokoro-82M clips (22 voices) and 80 SpeechT5 clips |
| **Test** | 57 clips: LibriSpeech *test-other* (different speakers) and People's Speech recordings not used in training | 20 Kokoro clips in **6 voices not used in training**, and 29 **Meta MMS** clips (an engine never seen in training) |

## Results on the held-out test set

The app says **AI** at a score of 0.80 or more, **Human** below 0.40, and **Unsure** in between.

| Condition | Real voices said "AI" | Kokoro (unseen voices) said "AI" | MMS (unseen engine) said "AI" | AI said "Human" | AUC |
|---|---|---|---|---|---|
| Clean audio | **0%** (0/57) | **95%** | **72%** | 0% | 0.999 |
| Phone quality | **2%** (1/57) | **100%** | **79%** | 0% Kokoro, 17% MMS | 0.985 |

Anything not counted above landed in **Unsure**.

## Honest limits

- The test set is small (57 real and 49 AI clips) and uses **open-source** voice engines. Commercial voice cloning
  (ElevenLabs, OpenAI voice, phone-scam clones of a specific person) was **not tested**. Expect it to be harder.
- The real speech is English audiobooks and recorded talks. Other languages, accents, children, singing, and very
  noisy rooms weren't covered.
- The phone condition is simulated with a codec. Real speaker-to-microphone recordings add echo and noise.
- 8-bit quantization breaks this model's scores (see `quantize_eval.py`), so it ships in full precision.

**Treat the result as a warning sign, not proof.**

## Reproduce

```sh
# 1. data (see the scripts' docstrings): real clips from the HF datasets-server, AI clips from kokoro-js / transformers.js
# 2. train + evaluate + export
python train.py DATA OUT          # OUT/voice_detector_fp32.onnx, OUT/report.json
python quantize_eval.py DATA OUT  # int8 vs fp32 comparison
```
Needs `torch transformers onnx onnxruntime soundfile` and `ffmpeg`.
