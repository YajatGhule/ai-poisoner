"""The encoders the protection is aimed at.

``taesd`` is a 5 MB distilled copy of Stable Diffusion's image encoder. It is
fast enough for a laptop CPU, and a change found against it carries over to the
real encoder (measured in the README). ``sd-vae`` is the real one: slower by
about 8x, worth it on a GPU.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import Callable

import torch

MODELS = {
    "taesd": "madebyollin/taesd",
    "sd-vae": "stabilityai/sd-vae-ft-mse",
}


@dataclass
class Codec:
    """An image-to-latent encoder and, if there is one, the matching decoder."""
    name: str
    encode: Callable[[torch.Tensor], torch.Tensor]            # 0..1 image -> latent
    decode: Callable[[torch.Tensor], torch.Tensor] | None     # latent -> 0..1 image
    device: torch.device


def pick_device(prefer: str | None = None) -> torch.device:
    if prefer and prefer != "auto":
        return torch.device(prefer)
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def load(name: str = "taesd", device: str | torch.device | None = None) -> Codec:
    """Download (first time only) and build one of the encoders."""
    if name not in MODELS:
        raise ValueError(f"unknown model {name!r}; choose from {sorted(MODELS)}")
    dev = pick_device(device if isinstance(device, str) or device is None else str(device))
    if name == "taesd":
        from diffusers import AutoencoderTiny
        net = AutoencoderTiny.from_pretrained(MODELS[name]).to(dev).eval()
        encode = lambda x: net.encoder(x * 2 - 1)
        decode = lambda z: (net.decoder(z) + 1) / 2
    else:
        from diffusers import AutoencoderKL
        net = AutoencoderKL.from_pretrained(MODELS[name]).to(dev).eval()
        encode = lambda x: net.encode(x * 2 - 1).latent_dist.mean
        decode = lambda z: (net.decode(z).sample + 1) / 2
    for p in net.parameters():
        p.requires_grad_(False)
    return Codec(name, encode, decode, dev)


class Registry:
    """Loads each model once, on first use, and hands the same copy to everyone."""

    def __init__(self, device: str | None = None, loader: Callable[..., Codec] = load):
        self._device = device
        self._loader = loader
        self._codecs: dict[str, Codec] = {}
        self._lock = threading.Lock()

    def get(self, name: str) -> Codec:
        with self._lock:
            if name not in self._codecs:
                self._codecs[name] = self._loader(name, self._device)
            return self._codecs[name]

    def loaded(self) -> list[str]:
        return sorted(self._codecs)

    @property
    def device(self) -> str:
        return str(pick_device(self._device))
