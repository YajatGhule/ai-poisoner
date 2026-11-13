"""The protection itself: a projected-gradient search for a small change to the
picture that moves it a long way in a diffusion model's latent space.

This is the "encoder attack" from PhotoGuard (Salman et al., 2023), with the
fixed target pattern idea from Mist (Liang and Wu, 2023). Nothing in here knows
about files, HTTP or which model is loaded: it takes a tensor and an encoder.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

import torch
import torch.nn.functional as F

Encoder = Callable[[torch.Tensor], torch.Tensor]  # [N,3,H,W] in 0..1 -> latent


class Cancelled(Exception):
    """Raised inside the loop when the caller asks to stop."""


@dataclass(frozen=True)
class Strength:
    name: str
    eps: float      # largest change to any pixel, on a 0..1 scale
    steps: int      # optimisation steps
    work_px: int    # long side the optimisation runs at


STRENGTHS = {
    "light": Strength("light", 4 / 255, 30, 384),
    "standard": Strength("standard", 8 / 255, 40, 512),
    "strong": Strength("strong", 16 / 255, 60, 512),
}

MODES = ("cloak", "scramble")


def work_size(h: int, w: int, long_side: int, multiple: int = 8) -> tuple[int, int]:
    """Size the optimisation runs at: long side capped, both sides a multiple of 8."""
    scale = min(1.0, long_side / max(h, w))
    snap = lambda v: max(multiple, int(round(v * scale / multiple)) * multiple)
    return snap(h), snap(w)


def target_pattern(h: int, w: int, device=None) -> torch.Tensor:
    """A hard-edged, high-contrast pattern that looks nothing like a photo.

    Pulling a picture's latent toward this gives the model strong, wrong
    structure to latch on to. It is generated, so there is no file to ship.
    """
    ys = torch.arange(h, device=device).view(h, 1).float()
    xs = torch.arange(w, device=device).view(1, w).float()
    cell = max(8, min(h, w) // 8)
    checks = ((ys // cell + xs // cell) % 2)
    stripes = (((xs + ys) // max(4, cell // 2)) % 2)
    rings = ((torch.sqrt((ys - h / 2) ** 2 + (xs - w / 2) ** 2) // max(4, cell // 2)) % 2)
    return torch.stack([checks, stripes, rings]).unsqueeze(0).clamp(0, 1)


def texture_mask(x: torch.Tensor, floor: float = 0.45) -> torch.Tensor:
    """Per-pixel budget in floor..1: busy areas hide a change better than flat sky
    or skin, so flat areas get less of it."""
    lum = x.mean(1, keepdim=True)
    k = 7
    mean = F.avg_pool2d(lum, k, 1, k // 2, count_include_pad=False)
    var = F.avg_pool2d(lum * lum, k, 1, k // 2, count_include_pad=False) - mean * mean
    std = var.clamp_min(0).sqrt()
    busy = (std / 0.08).clamp(0, 1)
    busy = F.avg_pool2d(busy, k, 1, k // 2, count_include_pad=False)
    return floor + (1 - floor) * busy


def perturb(
    x: torch.Tensor,
    encoder: Encoder,
    strength: Strength,
    mode: str = "cloak",
    *,
    seed: int | None = 0,
    robust: bool = True,
    on_step: Callable[[int, int, float], None] | None = None,
    should_stop: Callable[[], bool] | None = None,
) -> torch.Tensor:
    """Return the change (same shape as ``x``) to add to ``x``.

    ``x`` is one RGB image, [1,3,H,W] in 0..1, at the size the optimisation
    should run at. The result obeys ``|delta| <= strength.eps`` everywhere.
    """
    if mode not in MODES:
        raise ValueError(f"mode must be one of {MODES}")
    gen = torch.Generator(device="cpu")
    if seed is not None:
        gen.manual_seed(seed)

    budget = strength.eps * texture_mask(x)
    with torch.no_grad():
        clean = encoder(x)
        target = encoder(target_pattern(x.shape[2], x.shape[3], x.device)) if mode == "cloak" else None

    delta = ((torch.rand(x.shape, generator=gen) * 2 - 1).to(x.device) * budget)
    step_size = 2.5 * strength.eps / strength.steps
    momentum = torch.zeros_like(x)

    for i in range(strength.steps):
        if should_stop and should_stop():
            raise Cancelled()
        delta.requires_grad_(True)
        adv = (x + delta).clamp(0, 1)
        if robust and i % 2 == 1:
            # Every other step, look at a slightly shrunk, slightly noisy copy, so
            # the change still works after a site resizes or recompresses the image.
            s = 0.75 + 0.25 * torch.rand(1, generator=gen).item()
            hw = work_size(int(x.shape[2] * s), int(x.shape[3] * s), 10**6)
            adv_in = F.interpolate(adv, hw, mode="bilinear", align_corners=False, antialias=True)
            adv_in = adv_in + (torch.randn(adv_in.shape, generator=gen).to(x.device) * (1.5 / 255))
        else:
            adv_in = adv
        z = encoder(adv_in)
        # The shrunk copy gives a smaller latent, so bring the reference to its size.
        goal = target if mode == "cloak" else clean
        if goal.shape != z.shape:
            goal = F.interpolate(goal, z.shape[-2:], mode="bilinear", align_corners=False)
        loss = F.mse_loss(z, goal) if mode == "cloak" else -F.mse_loss(z, goal)
        (grad,) = torch.autograd.grad(loss, delta)
        with torch.no_grad():
            grad = grad / grad.abs().mean().clamp_min(1e-12)
            momentum = 0.9 * momentum + grad
            delta = delta - step_size * momentum.sign()
            delta = torch.minimum(torch.maximum(delta, -budget), budget)
            delta = (x + delta).clamp(0, 1) - x
        if on_step:
            on_step(i + 1, strength.steps, float(loss.detach()))
    return delta.detach()


def protect_tensor(
    image: torch.Tensor,
    encoder: Encoder,
    strength: Strength,
    mode: str = "cloak",
    **kw,
) -> torch.Tensor:
    """Protect a full-size image, [1,3,H,W] in 0..1. Returns the same size.

    The search runs on a copy no larger than ``strength.work_px`` (models resize
    their input anyway), then the change is scaled back up and added to the
    original pixels, so the output keeps its full resolution and detail.
    """
    h, w = image.shape[2:]
    wh, ww = work_size(h, w, strength.work_px)
    small = image if (wh, ww) == (h, w) else F.interpolate(
        image, (wh, ww), mode="bilinear", align_corners=False, antialias=True).clamp(0, 1)
    delta = perturb(small, encoder, strength, mode, **kw)
    if (wh, ww) != (h, w):
        delta = F.interpolate(delta, (h, w), mode="bilinear", align_corners=False)
        delta = delta.clamp(-strength.eps, strength.eps)
    return (image + delta).clamp(0, 1)
