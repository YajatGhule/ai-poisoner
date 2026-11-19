"""Numbers that say how visible the change is and how much it moved the latent."""

from __future__ import annotations

import io
import math

import torch
import torch.nn.functional as F
from PIL import Image

from .attack import Encoder, work_size
from .imageio import to_pil, to_tensor


def psnr(a: torch.Tensor, b: torch.Tensor) -> float:
    """Peak signal-to-noise ratio in dB. Above about 35 is hard to see."""
    mse = F.mse_loss(a, b).item()
    return float("inf") if mse == 0 else 10 * math.log10(1 / mse)


def ssim(a: torch.Tensor, b: torch.Tensor) -> float:
    """Structural similarity, 0..1, on luminance with the usual 11 px Gaussian window."""
    ya, yb = a.mean(1, keepdim=True), b.mean(1, keepdim=True)
    k = 11
    g = torch.exp(-((torch.arange(k) - k // 2) ** 2) / (2 * 1.5 ** 2))
    g = (g / g.sum()).to(a.device)
    win = (g.view(1, 1, k, 1) * g.view(1, 1, 1, k))
    f = lambda t: F.conv2d(t, win, padding=k // 2)
    ma, mb = f(ya), f(yb)
    va, vb, cov = f(ya * ya) - ma * ma, f(yb * yb) - mb * mb, f(ya * yb) - ma * mb
    c1, c2 = 0.01 ** 2, 0.03 ** 2
    s = ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
    return float(s.mean())


def _small(x: torch.Tensor, px: int) -> torch.Tensor:
    hw = work_size(x.shape[2], x.shape[3], px)
    if hw == tuple(x.shape[2:]):
        return x
    return F.interpolate(x, hw, mode="bilinear", align_corners=False, antialias=True).clamp(0, 1)


@torch.no_grad()
def latent_shift(encoder: Encoder, clean: torch.Tensor, other: torch.Tensor, px: int = 512) -> float:
    """How far ``other`` sits from ``clean`` in latent space, as a fraction of the
    clean latent's own spread. 0 means the model sees the same picture."""
    zc = encoder(_small(clean, px))
    zo = encoder(_small(other, px))
    return float((zo - zc).norm() / (zc - zc.mean()).norm().clamp_min(1e-12))


def jpeg(x: torch.Tensor, quality: int) -> torch.Tensor:
    """Round-trip through JPEG, as an upload to a social site would."""
    buf = io.BytesIO()
    to_pil(x).save(buf, "JPEG", quality=quality)
    return to_tensor(Image.open(io.BytesIO(buf.getvalue()))).to(x.device)


def halve(x: torch.Tensor) -> torch.Tensor:
    h, w = x.shape[2:]
    return F.interpolate(x, (max(8, h // 2), max(8, w // 2)), mode="bilinear",
                         align_corners=False, antialias=True).clamp(0, 1)


@torch.no_grad()
def report(encoder: Encoder, clean: torch.Tensor, protected: torch.Tensor, eps: float,
           px: int = 512, seed: int = 0) -> dict:
    """Everything the UI and the benchmark show for one image."""
    # The fair comparison is noise of exactly the same size: keep how much each pixel
    # changed and flip a coin for the direction. Same PSNR, no intent behind it.
    gen = torch.Generator().manual_seed(seed)
    signs = (torch.randint(0, 2, clean.shape, generator=gen).float() * 2 - 1).to(clean.device)
    noisy = (clean + signs * (protected - clean).abs()).clamp(0, 1)
    shift = latent_shift(encoder, clean, protected, px)
    base = latent_shift(encoder, clean, noisy, px)
    # After a lossy step, compare with the clean picture put through the same step,
    # so the number is the protection that is left, not the damage JPEG does anyway.
    jq = latent_shift(encoder, jpeg(clean, 85), jpeg(protected, 85), px)
    hv = latent_shift(encoder, halve(clean), halve(protected), px)
    return {
        "psnr_db": round(psnr(clean, protected), 2),
        "ssim": round(ssim(clean, protected), 4),
        "max_change": round(float((protected - clean).abs().max()) * 255, 1),
        "latent_shift": round(shift, 3),
        "random_noise_shift": round(base, 3),
        "times_random_noise": round(shift / base, 1) if base > 0 else None,
        "shift_after_jpeg85": round(jq, 3),
        "shift_after_half_size": round(hv, 3),
    }
