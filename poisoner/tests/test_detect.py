"""Detection logic with stand-in models, so no download is needed."""

import io
import shutil
import wave

import numpy as np
import pytest
from fastapi.testclient import TestClient

from aipoisoner import detect
from aipoisoner.detect import RATE, Detector, _speech_summary
from aipoisoner.server import create_app

from conftest import picture


class FakeDetector(Detector):
    """Image score from mean brightness; voice score from loudness; music fixed."""

    def __init__(self):
        super().__init__()
        self.calls = []

    def _run(self, kind, feeds):
        self.calls.append(kind)
        x = next(iter(feeds.values()))
        if kind == "image":
            return {0: np.array([[0.0, 4.0 if x.mean() > 0 else -4.0]])}
        if kind == "voice":
            return {0: np.array([[0.0, 4.0 if np.abs(x).max() > 3 else -4.0]])}
        return {"prob_ai": np.array([[0.9]]), 0: np.array([[0.9]])}


def wav_bytes(x: np.ndarray) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    return buf.getvalue()


def test_image_verdict_and_metadata():
    d = FakeDetector()
    r = d.check_file("a.png", picture())
    assert r["kind"] == "image" and r["verdict"] in ("ai", "human") and 0 <= r["score"] <= 1
    tagged = picture() + b"trainedAlgorithmicMedia"
    r = d.check_file("b.png", tagged)
    assert r["verdict"] == "ai" and "generative AI" in r["why"]


def test_speech_summary_finds_spliced_stretch():
    starts = [i * 2.0 for i in range(30)]
    scores = [0.95 if 20 <= s < 34 else 0.02 for s in starts]
    s = _speech_summary(starts, scores, 0)
    assert s["verdict"] == "unsure" and s["partly_ai"] and s["headline"] == "Parts sound AI"
    assert s["stretches"][0][:2] == [20.0, 36.0]
    assert _speech_summary([0.0, 2.0, 4.0], [0.9, 0.95, 0.99], 0)["verdict"] == "ai"
    assert _speech_summary([0.0, 2.0, 4.0], [0.01, 0.02, 0.03], 0)["verdict"] == "human"
    assert _speech_summary([], [], 3)["verdict"] is None


def test_quiet_windows_are_skipped():
    d = FakeDetector()
    s = d.check_voice(np.zeros(RATE * 12, dtype=np.float32))
    assert s["windows"] == 0 and s["skipped"] > 0 and "voice" not in d.calls


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="needs ffmpeg")
def test_audio_file_end_to_end():
    t = np.arange(RATE * 12) / RATE
    x = (0.3 * np.sin(2 * np.pi * 220 * t) * (1 + 0.5 * np.sin(2 * np.pi * 3 * t))).astype(np.float32)
    r = FakeDetector().check_file("tone.wav", wav_bytes(x))
    assert r["kind"] == "audio" and abs(r["seconds"] - 12) < 0.2
    assert r["voice"]["windows"] == 5 and r["voice"]["verdict"] == "human"
    assert r["music"]["verdict"] == "ai" and r["music"]["windows"] == 2


def test_check_endpoint(protector, tmp_path):
    (tmp_path / "images").mkdir()
    (tmp_path / "images" / "x.png").write_bytes(picture())
    (tmp_path / "notes.txt").write_text("not a sample")
    with TestClient(create_app(protector, detector=FakeDetector(), samples=tmp_path)) as c:
        r = c.post("/api/check", files={"file": ("p.png", picture(), "image/png")})
        assert r.status_code == 200 and r.json()["kind"] == "image"
        assert c.post("/api/check", files={"file": ("e.png", b"", "image/png")}).status_code == 422
        lst = c.get("/api/samples").json()
        assert [s["name"] for s in lst] == ["images/x.png"]
        assert c.post("/api/samples/0/check").json()["name"] == "x.png"
        assert c.get("/api/samples/0/file").status_code == 200
        g = c.get("/gallery")
        assert g.status_code == 200 and "/api/samples/0/file" in g.text and "x.png" in g.text
        assert c.post("/api/samples/5/check").status_code == 404
        assert set(c.get("/api/health").json()["detectors"]) == {"image", "voice", "music"}


def test_model_dir_env(monkeypatch, tmp_path):
    monkeypatch.setenv("SENTINEL_MODELS", str(tmp_path))
    assert detect.model_dir() == tmp_path
