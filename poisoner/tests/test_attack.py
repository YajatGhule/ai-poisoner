import io

import pytest
import torch
from PIL import Image

from aipoisoner import metrics
from aipoisoner.attack import (STRENGTHS, Cancelled, Strength, perturb, protect_tensor,
                               target_pattern, texture_mask, work_size)
from aipoisoner.imageio import BadImage, load_image, to_tensor
from conftest import picture, tiny_codec

FAST = Strength("test", 8 / 255, 12, 96)


def image(w=128, h=96):
    return load_image(picture(w, h)).rgb


def test_work_size_caps_long_side_and_snaps_to_eight():
    assert work_size(1000, 2000, 512) == (256, 512)
    assert work_size(100, 100, 512) == (96, 96)          # small images are not enlarged, only snapped
    assert work_size(3, 5, 512) == (8, 8)


def test_change_stays_inside_the_budget():
    x = image()
    delta = perturb(x, tiny_codec().encode, FAST)
    assert delta.shape == x.shape
    assert float(delta.abs().max()) <= FAST.eps + 1e-6
    assert float((x + delta).min()) >= 0 and float((x + delta).max()) <= 1


def test_flat_areas_get_a_smaller_budget():
    x = torch.full((1, 3, 64, 64), 0.5)
    x[..., 32:] = torch.rand(1, 3, 64, 32)
    m = texture_mask(x)
    assert float(m[..., :16].mean()) < float(m[..., 48:].mean())
    assert float(m.min()) >= 0.45 - 1e-6 and float(m.max()) <= 1 + 1e-6


@pytest.mark.parametrize("mode", ["cloak", "scramble"])
def test_search_beats_random_noise(mode):
    enc = tiny_codec().encode
    x = image()
    out = protect_tensor(x, enc, FAST, mode, robust=False)
    r = metrics.report(enc, x, out, FAST.eps, FAST.work_px)
    assert r["latent_shift"] > 1.5 * r["random_noise_shift"]


def test_cloak_moves_toward_the_target():
    enc = tiny_codec().encode
    x = image(96, 96)
    target = enc(target_pattern(96, 96))
    before = float((enc(x) - target).norm())
    after = float((enc(x + perturb(x, enc, FAST, robust=False)) - target).norm())
    assert after < before


def test_full_size_output_keeps_size_and_budget():
    x = image(400, 300)
    out = protect_tensor(x, tiny_codec().encode, FAST)
    assert out.shape == x.shape
    assert float((out - x).abs().max()) <= FAST.eps + 1e-6


def test_same_seed_gives_same_result():
    enc, x = tiny_codec().encode, image()
    assert torch.equal(perturb(x, enc, FAST, seed=3), perturb(x, enc, FAST, seed=3))


def test_can_be_cancelled():
    calls = []
    with pytest.raises(Cancelled):
        perturb(image(), tiny_codec().encode, FAST, should_stop=lambda: len(calls) >= 3,
                on_step=lambda i, n, loss: calls.append(i))
    assert calls == [1, 2, 3]


def test_unknown_mode_is_rejected():
    with pytest.raises(ValueError):
        perturb(image(), tiny_codec().encode, FAST, "nope")


def test_strength_levels_are_ordered():
    assert STRENGTHS["light"].eps < STRENGTHS["standard"].eps < STRENGTHS["strong"].eps


def test_protector_keeps_transparency_and_reports_numbers(protector):
    r = protector.protect(picture(96, 80, "RGBA"), "light")
    out = Image.open(io.BytesIO(r.image))
    assert out.size == (96, 80) and out.mode == "RGBA"
    assert out.getchannel("A").tobytes() == Image.open(io.BytesIO(picture(96, 80, "RGBA"))).getchannel("A").tobytes()
    assert r.metrics["max_change"] <= 4.0 and r.metrics["psnr_db"] > 30
    assert Image.open(io.BytesIO(r.preview)).size[0] == 2 * 96


def test_jpeg_output_and_bad_input(protector):
    r = protector.protect(picture(96, 80, fmt="JPEG"), "light", fmt="jpeg")
    assert r.mime == "image/jpeg" and Image.open(io.BytesIO(r.image)).format == "JPEG"
    with pytest.raises(BadImage):
        protector.protect(b"not an image")
    with pytest.raises(BadImage):
        protector.protect(picture(32, 32))
    with pytest.raises(ValueError):
        protector.protect(picture(), "extreme")


def test_metrics_basics():
    x = image()
    assert metrics.psnr(x, x) == float("inf")
    assert metrics.ssim(x, x) == pytest.approx(1.0, abs=1e-4)
    noisy = (x + 0.1 * torch.randn_like(x)).clamp(0, 1)
    assert metrics.ssim(x, noisy) < 0.95 and metrics.psnr(x, noisy) < 25
    assert to_tensor(Image.new("L", (8, 8))).shape == (1, 3, 8, 8)
