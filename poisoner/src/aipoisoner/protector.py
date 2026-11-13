"""One call that takes image bytes and returns protected image bytes.

The server, the command line and the tests all go through here.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Callable

import torch
import torch.nn.functional as F

from . import metrics
from .attack import MODES, STRENGTHS, protect_tensor, work_size
from .imageio import FORMATS, load_image, save_image, to_pil
from .models import Registry


@dataclass
class Result:
    image: bytes
    mime: str
    preview: bytes          # PNG: clean and protected, as the model reconstructs them
    metrics: dict
    width: int
    height: int
    seconds: float


def check_options(strength: str, mode: str, fmt: str) -> None:
    if strength not in STRENGTHS:
        raise ValueError(f"strength must be one of {sorted(STRENGTHS)}")
    if mode not in MODES:
        raise ValueError(f"mode must be one of {list(MODES)}")
    if fmt not in FORMATS:
        raise ValueError(f"format must be one of {sorted(FORMATS)}")


class Protector:
    def __init__(self, registry: Registry | None = None, model: str = "taesd"):
        self.registry = registry or Registry()
        self.model = model

    def protect(
        self,
        data: bytes,
        strength: str = "standard",
        mode: str = "cloak",
        fmt: str = "png",
        *,
        on_step: Callable[[int, int, float], None] | None = None,
        should_stop: Callable[[], bool] | None = None,
        seed: int | None = 0,
    ) -> Result:
        check_options(strength, mode, fmt)
        t0 = time.monotonic()
        loaded = load_image(data)
        codec = self.registry.get(self.model)
        level = STRENGTHS[strength]
        clean = loaded.rgb.to(codec.device)
        protected = protect_tensor(clean, codec.encode, level, mode,
                                   seed=seed, on_step=on_step, should_stop=should_stop)
        # Measure the pixels as they will be saved (8 bits per channel), not the floats.
        protected = (protected * 255).round() / 255
        out, mime = save_image(protected, loaded, fmt)
        numbers = metrics.report(codec.encode, clean, protected, level.eps, level.work_px)
        numbers.update(model=codec.name, strength=strength, mode=mode, steps=level.steps,
                       eps_255=round(level.eps * 255, 1))
        return Result(out, mime, self._preview(codec, clean, protected), numbers,
                      clean.shape[3], clean.shape[2], round(time.monotonic() - t0, 1))

    @torch.no_grad()
    def _preview(self, codec, clean: torch.Tensor, protected: torch.Tensor) -> bytes:
        """What comes out when the model encodes and decodes each picture, side by side."""
        import io
        if codec.decode is None:
            return b""
        hw = work_size(clean.shape[2], clean.shape[3], 384)
        small = lambda t: F.interpolate(t, hw, mode="bilinear", align_corners=False, antialias=True).clamp(0, 1)
        recon = lambda t: codec.decode(codec.encode(small(t))).clamp(0, 1)
        both = torch.cat([recon(clean), recon(protected)], dim=3)
        buf = io.BytesIO()
        to_pil(both).save(buf, "PNG")
        return buf.getvalue()
