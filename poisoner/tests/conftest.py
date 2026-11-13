"""Tests use a tiny stand-in encoder, so they need no download and run in seconds."""

import io

import numpy as np
import pytest
import torch
from PIL import Image

from aipoisoner.models import Codec, Registry
from aipoisoner.protector import Protector


def tiny_codec(name="taesd", device=None) -> Codec:
    torch.manual_seed(1)
    enc = torch.nn.Sequential(
        torch.nn.Conv2d(3, 8, 3, 2, 1), torch.nn.Tanh(),
        torch.nn.Conv2d(8, 8, 3, 2, 1), torch.nn.Tanh(),
        torch.nn.Conv2d(8, 4, 3, 2, 1)).eval()
    dec = torch.nn.Sequential(torch.nn.Upsample(scale_factor=8), torch.nn.Conv2d(4, 3, 3, 1, 1), torch.nn.Sigmoid()).eval()
    for p in list(enc.parameters()) + list(dec.parameters()):
        p.requires_grad_(False)
    return Codec(name, enc, dec, torch.device("cpu"))


def picture(w=160, h=120, mode="RGB", fmt="PNG", seed=0) -> bytes:
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    base = np.stack([xx / w, yy / h, (xx + yy) / (w + h)], -1)
    arr = (np.clip(base + rng.normal(0, 0.05, base.shape), 0, 1) * 255).astype(np.uint8)
    img = Image.fromarray(arr, "RGB")
    if mode == "RGBA":
        img.putalpha(Image.fromarray((xx / w * 255).astype(np.uint8), "L"))
    buf = io.BytesIO()
    img.save(buf, fmt)
    return buf.getvalue()


@pytest.fixture
def protector():
    return Protector(Registry(loader=tiny_codec))


@pytest.fixture
def png():
    return picture()
