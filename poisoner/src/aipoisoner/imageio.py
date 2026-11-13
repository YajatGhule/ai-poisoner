"""Reading and writing image files without losing what matters to the owner:
orientation, transparency and the colour profile."""

from __future__ import annotations

import io
from dataclasses import dataclass

import numpy as np
import torch
from PIL import Image, ImageOps

MAX_PIXELS = 40_000_000
FORMATS = {"png": ("PNG", "image/png"), "jpeg": ("JPEG", "image/jpeg"), "webp": ("WEBP", "image/webp")}

Image.MAX_IMAGE_PIXELS = MAX_PIXELS * 2


class BadImage(ValueError):
    """The upload is not an image we can protect."""


@dataclass
class Loaded:
    rgb: torch.Tensor                # [1,3,H,W] in 0..1
    alpha: Image.Image | None        # original transparency, untouched
    icc: bytes | None                # colour profile, passed through


def to_tensor(img: Image.Image) -> torch.Tensor:
    arr = np.asarray(img.convert("RGB"), dtype=np.float32) / 255.0
    return torch.from_numpy(arr).permute(2, 0, 1).unsqueeze(0).contiguous()


def to_pil(x: torch.Tensor) -> Image.Image:
    arr = (x[0].detach().clamp(0, 1).permute(1, 2, 0).cpu().numpy() * 255.0 + 0.5).astype(np.uint8)
    return Image.fromarray(arr, "RGB")


def load_image(data: bytes) -> Loaded:
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Exception as e:  # PIL raises many unrelated types for broken files
        raise BadImage("This file is not an image that can be read.") from e
    if getattr(img, "is_animated", False):
        raise BadImage("Animated images are not supported yet. Use a still image.")
    if img.width * img.height > MAX_PIXELS:
        raise BadImage(f"Image is too large ({img.width}x{img.height}). The limit is 40 megapixels.")
    if min(img.size) < 64:
        raise BadImage("Image is too small. Each side must be at least 64 pixels.")
    icc = img.info.get("icc_profile")
    img = ImageOps.exif_transpose(img)   # bake the camera rotation in
    alpha = None
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        img = img.convert("RGBA")
        alpha = img.getchannel("A")
    return Loaded(to_tensor(img), alpha, icc)


def save_image(x: torch.Tensor, loaded: Loaded, fmt: str = "png") -> tuple[bytes, str]:
    """Encode the protected pixels. Returns (bytes, mime type).

    PNG and WebP are lossless, so the change survives exactly. JPEG is written
    at quality 95 without chroma subsampling, which keeps most of it. Camera
    metadata (EXIF, including GPS) is deliberately not copied.
    """
    if fmt not in FORMATS:
        raise ValueError(f"format must be one of {sorted(FORMATS)}")
    pil_name, mime = FORMATS[fmt]
    img = to_pil(x)
    if loaded.alpha is not None and fmt != "jpeg":
        img.putalpha(loaded.alpha)
    buf = io.BytesIO()
    kw: dict = {"icc_profile": loaded.icc} if loaded.icc else {}
    if fmt == "jpeg":
        img.save(buf, pil_name, quality=95, subsampling=0, **kw)
    elif fmt == "webp":
        img.save(buf, pil_name, lossless=True, **kw)
    else:
        img.save(buf, pil_name, compress_level=6, **kw)
    return buf.getvalue(), mime
