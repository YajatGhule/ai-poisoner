"""Measure the protection on a folder of images.

The search runs against one encoder; the interesting question is whether the
result also fools a different one. So each protected image is measured again
with ``check_with`` (by default the real Stable Diffusion encoder).
"""

from __future__ import annotations

import io
import statistics
from pathlib import Path

from PIL import Image

from . import metrics
from .attack import STRENGTHS
from .imageio import load_image, to_tensor
from .models import load
from .protector import Protector


def run(files: list[Path], protector: Protector, strength: str, mode: str, *,
        check_with: str = "sd-vae", device: str = "auto", save_dir: Path | None = None) -> dict:
    level = STRENGTHS[strength]
    checker = None if check_with in ("none", protector.model) else load(check_with, device)
    rows = []
    for f in files:
        data = f.read_bytes()
        r = protector.protect(data, strength, mode, "png")
        row = {"file": f.name, "width": r.width, "height": r.height, "seconds": r.seconds,
               protector.model: {k: v for k, v in r.metrics.items() if isinstance(v, (int, float))}}
        if checker:
            clean = load_image(data).rgb.to(checker.device)
            prot = to_tensor(Image.open(io.BytesIO(r.image))).to(checker.device)
            row[checker.name] = metrics.report(checker.encode, clean, prot, level.eps, level.work_px)
        if save_dir:
            save_dir.mkdir(parents=True, exist_ok=True)
            (save_dir / f"{f.stem}_protected.png").write_bytes(r.image)
            (save_dir / f"{f.stem}_model_view.png").write_bytes(r.preview)
        rows.append(row)
        print(f"{f.name}: done in {r.seconds}s", flush=True)

    def mean(model: str, key: str):
        vals = [row[model][key] for row in rows if model in row and row[model].get(key) is not None]
        return round(statistics.fmean(vals), 3) if vals else None

    keys = ["psnr_db", "ssim", "latent_shift", "random_noise_shift", "times_random_noise",
            "shift_after_jpeg85", "shift_after_half_size"]
    names = [protector.model] + ([checker.name] if checker else [])
    return {
        "images": len(rows), "strength": strength, "mode": mode, "optimised_against": protector.model,
        "mean_seconds": round(statistics.fmean(r["seconds"] for r in rows), 1),
        "mean": {n: {k: mean(n, k) for k in keys} for n in names},
        "rows": rows,
    }
