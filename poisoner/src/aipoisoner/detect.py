"""AI-content checks for the desktop app: images, voices and music.

The same ONNX models and cutoffs as the Android app (android/app/.../Detectors.kt, Speech.kt, Prefs.kt),
run with ONNX Runtime on the CPU. Audio and video are decoded with ffmpeg.
"""

from __future__ import annotations

import contextlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

MODELS = {
    "image": ("https://huggingface.co/onnx-community/ai-image-detection-ONNX/resolve/main/onnx/model_quantized.onnx",
              "image_detector.onnx"),
    "voice": ("https://github.com/YajatGhule/ai-poisoner/releases/download/voice-model-v2/voice_detector_v2.onnx",
              "voice_detector_v2.onnx"),
    "music": ("https://github.com/YajatGhule/ai-poisoner/releases/download/music-model-v1/ai_music_detector.onnx",
              "ai_music_detector.onnx"),
}

# Cutoffs (see android Prefs.kt). A chosen file is the original, so the image file cutoffs apply.
IMAGE_AI_AT, IMAGE_HUMAN_BELOW = 0.85, 0.60
VOICE_AI_AT, VOICE_HUMAN_BELOW = 0.80, 0.40
MUSIC_AI_AT, MUSIC_HUMAN_AT = 0.80, 0.30

RATE = 16000
VOICE_WIN, VOICE_HOP = 4 * RATE, 2 * RATE
MUSIC_WIN, MUSIC_MAX = 5 * RATE, 12
MIN_WINDOWS = 3
PARTLY_AI_MIN_SEC = 8.0
MAX_AUDIO_SEC = 15 * 60

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".tif", ".tiff", ".avif", ".heic"}
VIDEO_EXT = {".mp4", ".mov", ".mkv", ".webm", ".3gp", ".avi", ".m4v"}
AUDIO_EXT = {".wav", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac"} | VIDEO_EXT
VIDEO_FRAMES = 6


class CheckError(Exception):
    """A file that can't be checked; the message is shown to the user."""


def model_dir() -> Path:
    base = os.environ.get("SENTINEL_MODELS") or os.path.join(
        os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "sentinel", "models")
    return Path(base)


def band(p: float, ai_at: float, human_below: float) -> str:
    return "ai" if p >= ai_at else "human" if p < human_below else "unsure"


def clock(sec: float) -> str:
    s = int(sec)
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60}:{s % 60:02d}"


def _softmax1(logits: np.ndarray) -> float:
    e = np.exp(logits - logits.max())
    return float(e[1] / e.sum())


@dataclass
class _Model:
    session: object
    lock: threading.Lock


class Detector:
    """Loads each model on first use (downloading it once) and keeps it."""

    def __init__(self, directory: Path | None = None):
        self.dir = directory or model_dir()
        self._models: dict[str, _Model] = {}
        self._lock = threading.Lock()

    def status(self) -> dict:
        return {k: (self.dir / f).exists() for k, (_, f) in MODELS.items()}

    def download(self, kind: str) -> Path:
        url, name = MODELS[kind]
        path = self.dir / name
        if path.exists():
            return path
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".part")
        with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        tmp.replace(path)
        return path

    def _get(self, kind: str) -> _Model:
        with self._lock:
            if kind not in self._models:
                import onnxruntime as ort
                opts = ort.SessionOptions()
                opts.intra_op_num_threads = max(1, min(4, os.cpu_count() or 1))
                sess = ort.InferenceSession(str(self.download(kind)), opts, providers=["CPUExecutionProvider"])
                self._models[kind] = _Model(sess, threading.Lock())
            return self._models[kind]

    def _run(self, kind: str, feeds: dict) -> dict:
        m = self._get(kind)
        with m.lock:
            outs = m.session.run(None, feeds)
        return {o.name: v for o, v in zip(m.session.get_outputs(), outs, strict=True)} | dict(enumerate(outs))

    # ---------------- images ----------------

    def image_score(self, img: Image.Image) -> float:
        """ViT classifier, 224x224, x/127.5 - 1, labels REAL=0 FAKE=1 (as the Chrome extension feeds it)."""
        x = np.asarray(img.convert("RGB").resize((224, 224), Image.BILINEAR), dtype=np.float32) / 127.5 - 1.0
        out = self._run("image", {"pixel_values": x.transpose(2, 0, 1)[None]})
        return _softmax1(out[0][0])

    def check_image(self, data: bytes) -> dict:
        meta = _image_metadata(data)
        try:
            img = ImageOps.exif_transpose(Image.open(io.BytesIO(data)))
            img.load()
        except Exception as e:
            raise CheckError("This image could not be opened.") from e
        p = self.image_score(img)
        verdict = meta["verdict"] or band(p, IMAGE_AI_AT, IMAGE_HUMAN_BELOW)
        head = {"ai": "Likely AI-generated", "human": "Likely not AI", "unsure": "Unsure"}[verdict]
        return {"kind": "image", "verdict": verdict, "score": round(p, 3),
                "headline": f"{head} ({round(p * 100)}% AI)" if not meta["why"] else f"{head}: {meta['why']}",
                "why": meta["why"], "c2pa": meta["c2pa"], "size": [img.width, img.height]}

    # ---------------- voice ----------------

    def voice_score(self, x: np.ndarray) -> float:
        x = (x - x.mean()) / np.sqrt(x.var() + 1e-7)
        out = self._run("voice", {"input_values": x.astype(np.float32)[None]})
        return _softmax1(out[0][0])

    def check_voice(self, x: np.ndarray) -> dict:
        starts, scores, skipped = [], [], 0
        if len(x) < VOICE_WIN:
            if len(x) >= 2 * RATE and _usable(x):
                starts, scores = [0.0], [self.voice_score(x)]
        else:
            for s in range(0, len(x) - VOICE_WIN + 1, VOICE_HOP):
                w = x[s:s + VOICE_WIN]
                if _usable(w):
                    starts.append(s / RATE)
                    scores.append(self.voice_score(w))
                else:
                    skipped += 1
        return _speech_summary(starts, scores, skipped)

    # ---------------- music ----------------

    def check_music(self, x: np.ndarray) -> dict | None:
        wins = [x[s:s + MUSIC_WIN] for s in range(0, len(x) - MUSIC_WIN + 1, MUSIC_WIN)]
        rest = len(x) % MUSIC_WIN
        if rest >= MUSIC_WIN // 2 or (not wins and rest):
            wins.append(np.pad(x[len(x) - rest:], (0, MUSIC_WIN - rest)))
        wins = [w for w in wins if np.sqrt(np.mean(w ** 2)) >= 1e-3]
        if not wins:
            return None
        if len(wins) > MUSIC_MAX:   # spread over the whole file
            wins = [wins[i] for i in np.linspace(0, len(wins) - 1, MUSIC_MAX).round().astype(int)]
        ps = []
        for w in wins:
            out = self._run("music", {"pcm": w.astype(np.float32)[None]})
            ps.append(float(np.asarray(out.get("prob_ai", out[0])).ravel()[0]))
        p = float(np.mean(ps))
        verdict = "ai" if p >= MUSIC_AI_AT else "human" if p <= MUSIC_HUMAN_AT else "unsure"
        head = {"ai": f"Likely AI-generated ({round(p * 100)}%)", "human": f"Likely human-made ({round(p * 100)}% AI)",
                "unsure": f"Unsure ({round(p * 100)}% AI)"}[verdict]
        return {"verdict": verdict, "score": round(p, 3), "windows": len(ps), "headline": "Music: " + head}

    def check_audio(self, path: str) -> dict:
        x = decode_audio(path)
        if len(x) < RATE:
            raise CheckError("The file has less than a second of sound.")
        return {"kind": "audio", "seconds": round(len(x) / RATE, 1),
                "voice": self.check_voice(x), "music": self.check_music(x)}

    def check_pcm(self, x: np.ndarray) -> dict:
        """A recording that is already 16 kHz mono float32 (live microphone listening)."""
        if len(x) < RATE:
            raise CheckError("Less than a second was recorded.")
        x = x[: MAX_AUDIO_SEC * RATE]
        return {"kind": "audio", "seconds": round(len(x) / RATE, 1),
                "voice": self.check_voice(x), "music": self.check_music(x)}

    def live_voice(self, w: np.ndarray) -> dict:
        """One live window (2-4 s): its AI-voice score, or none if it is too quiet or has dropouts."""
        if not _usable(w):
            return {"usable": False, "score": None}
        return {"usable": True, "score": round(self.voice_score(w), 4)}

    def live_music(self, w: np.ndarray) -> dict:
        """One live 5 s window: its AI-music score, or none if it is near silent."""
        w = np.pad(w[:MUSIC_WIN], (0, max(0, MUSIC_WIN - len(w))))
        if np.sqrt(np.mean(w ** 2)) < 1e-3:
            return {"score": None}
        out = self._run("music", {"pcm": w.astype(np.float32)[None]})
        return {"score": round(float(np.asarray(out.get("prob_ai", out[0])).ravel()[0]), 4)}

    def check_frames(self, path: str) -> dict | None:
        """Pictures taken at even points of a video, each checked like an image."""
        dur, _ = media_info(path)
        if not dur:
            return None
        times = [dur * (k + 0.5) / VIDEO_FRAMES for k in range(VIDEO_FRAMES)]
        scores = []
        for t in times:
            frame = video_frame(path, t)
            # Plain frames (a black screen behind a sound track) say nothing about the picture.
            if frame is not None and np.asarray(frame.convert("L"), dtype=np.float32).std() >= 8:
                scores.append((round(t, 1), round(self.image_score(frame), 3)))
        if not scores:
            return None
        ps = [p for _, p in scores]
        n_ai = sum(p >= IMAGE_AI_AT for p in ps)
        verdict = "ai" if n_ai * 2 >= len(ps) else "human" if max(ps) < IMAGE_HUMAN_BELOW else "unsure"
        head = {"ai": "Looks AI-generated", "human": "Looks real", "unsure": "Unsure"}[verdict]
        return {"verdict": verdict, "headline": f"{head} ({n_ai} of {len(ps)} frames AI)",
                "frames": [{"t": t, "score": p, "verdict": band(p, IMAGE_AI_AT, IMAGE_HUMAN_BELOW)} for t, p in scores]}

    def check_path(self, path: str, name: str | None = None) -> dict:
        """Checks a file on disk: a picture, a recording, or a video (its sound and some of its frames)."""
        name = name or Path(path).name
        ext = Path(name).suffix.lower()
        if ext in IMAGE_EXT:
            return {"name": name, **self.check_image(Path(path).read_bytes())}
        if ext not in AUDIO_EXT and _looks_like_image(Path(path).read_bytes()):
            return {"name": name, **self.check_image(Path(path).read_bytes())}
        if ext in VIDEO_EXT:
            dur, sound = media_info(path)
            frames = self.check_frames(path)
            x = None
            if sound:
                with contextlib.suppress(CheckError):
                    x = decode_audio(path)
            if frames is None and x is None:
                raise CheckError("This video could not be read.")
            out = {"name": name, "kind": "video", "seconds": round(dur or 0, 1),
                   "frames": frames, "voice": None, "music": None, "has_sound": x is not None and len(x) >= RATE}
            if out["has_sound"]:
                out["voice"], out["music"] = self.check_voice(x), self.check_music(x)
            return out
        return {"name": name, **self.check_audio(path)}

    def check_file(self, name: str, data: bytes) -> dict:
        ext = Path(name).suffix.lower()
        if ext in IMAGE_EXT or (ext not in AUDIO_EXT and _looks_like_image(data)):
            return {"name": name, **self.check_image(data)}
        # Closed before ffmpeg reads it: Windows does not let another process open a file that is still open here.
        fd, tmp = tempfile.mkstemp(suffix=ext or ".bin")
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            return self.check_path(tmp, name)
        finally:
            with contextlib.suppress(OSError):
                os.unlink(tmp)


def _usable(w: np.ndarray) -> bool:
    """Quiet windows and windows with digital silence or dropouts are skipped (they made real voices look AI)."""
    if np.sqrt(np.mean(w ** 2)) < 0.01:
        return False
    frame = RATE // 10
    n = len(w) // frame
    if n == 0:
        return True
    rms = np.sqrt(np.mean(w[:n * frame].reshape(n, frame) ** 2, axis=1))
    return (rms < 3e-4).sum() * 100 <= n * 15


def _speech_summary(starts: list[float], scores: list[float], skipped: int) -> dict:
    n = len(scores)
    share = sum(s >= VOICE_AI_AT for s in scores) / n if n else 0.0
    median = sorted(scores)[n // 2] if n else None
    stretches: list[list[float]] = []
    for a, s in zip(starts, scores, strict=True):
        if s < VOICE_AI_AT:
            continue
        b = a + VOICE_WIN / RATE
        if stretches and a <= stretches[-1][1] + VOICE_HOP / RATE:
            stretches[-1][1] = max(stretches[-1][1], b)
        else:
            stretches.append([a, b])
    verdict = None
    if n >= MIN_WINDOWS or (n == 1 and starts == [0.0]):
        if share >= 0.5 or median >= VOICE_AI_AT:
            verdict = "ai"
        elif share <= 0.1 and median < VOICE_HUMAN_BELOW:
            verdict = "human"
        else:
            verdict = "unsure"
    partly = verdict == "unsure" and sum(b - a for a, b in stretches) >= PARTLY_AI_MIN_SEC
    if verdict is None:
        headline = "Not enough clear speech to judge" if n == 0 else f"Too little clear speech ({n} windows)"
    else:
        headline = {"ai": "Likely an AI voice", "human": "Likely a human voice",
                    "unsure": "Parts sound AI" if partly else "Unsure"}[verdict]
    return {"verdict": verdict, "partly_ai": partly, "headline": headline, "windows": n, "skipped": skipped,
            "ai_share": round(share, 3), "median": None if median is None else round(median, 3),
            "stretches": [[round(a, 1), round(b, 1), f"{clock(a)}–{clock(b)}"] for a, b in stretches],
            "timeline": [[round(a, 1), round(s, 3)] for a, s in zip(starts, scores, strict=True)]}


def find_ffmpeg() -> str:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        try:
            import imageio_ffmpeg
            ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
        except Exception:   # noqa: BLE001 - optional fallback
            ffmpeg = None
    if not ffmpeg:
        raise CheckError("Checking audio and video needs ffmpeg. Install it and restart the app.")
    return ffmpeg


NO_WINDOW = {"creationflags": 0x08000000} if os.name == "nt" else {}   # no console window flash on Windows


def media_info(path: str) -> tuple[float | None, bool]:
    """(duration in seconds, has a sound track), read from ffmpeg's description of the file."""
    r = subprocess.run([find_ffmpeg(), "-nostdin", "-hide_banner", "-i", path], capture_output=True, timeout=60, **NO_WINDOW)
    m = re.search(rb"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", r.stderr)
    dur = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else None
    return dur, re.search(rb"Stream #\S+.*: Audio:", r.stderr) is not None


def video_frame(path: str, t: float) -> Image.Image | None:
    r = subprocess.run([find_ffmpeg(), "-nostdin", "-v", "error", "-ss", f"{t:.2f}", "-i", path, "-frames:v", "1",
                        "-f", "image2pipe", "-vcodec", "png", "-"], capture_output=True, timeout=60, **NO_WINDOW)
    if r.returncode != 0 or not r.stdout:
        return None
    try:
        return Image.open(io.BytesIO(r.stdout)).convert("RGB")
    except Exception:   # noqa: BLE001 - a frame that cannot be read is skipped
        return None


def decode_audio(path: str) -> np.ndarray:
    """Any audio or video file to 16 kHz mono float32, using ffmpeg."""
    ffmpeg = find_ffmpeg()
    flags = NO_WINDOW
    r = subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-i", path, "-t", str(MAX_AUDIO_SEC), "-vn",
                        "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"], capture_output=True, timeout=300, **flags)
    if r.returncode != 0 or not r.stdout:
        print(f"ffmpeg failed on {path}: {r.stderr.decode(errors='replace')[-500:]}", file=sys.stderr, flush=True)
        raise CheckError("This file has no sound track that could be read.")
    return np.frombuffer(r.stdout, dtype=np.float32).copy()


def _looks_like_image(data: bytes) -> bool:
    try:
        Image.open(io.BytesIO(data)).verify()
        return True
    except Exception:
        return False


def _image_metadata(data: bytes) -> dict:
    """Provenance markers written by generators and editors (same rules as the Chrome extension)."""
    s = data[:16_000_000].decode("latin-1")
    c2pa = "c2pa" in s
    why = None
    if "compositeWithTrainedAlgorithmicMedia" in s:
        why = "metadata says it was edited with generative AI"
    elif "trainedAlgorithmicMedia" in s:
        why = "metadata says it was created by generative AI"
    elif "Made with Google AI" in s:
        why = "metadata says Made with Google AI"
    elif "Steps: " in s and "Sampler: " in s:
        why = "Stable Diffusion settings are embedded"
    elif '"class_type"' in s and "KSampler" in s:
        why = "a ComfyUI workflow is embedded"
    return {"verdict": "ai" if why else None, "why": why, "c2pa": c2pa}
