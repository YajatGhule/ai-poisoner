# AI Poisoner backend

A small server that runs **on your own computer** and protects your images against AI training and editing.
It adds a change that is hard to see but that makes the image encoder of Stable Diffusion-type models read the
picture wrongly. The Chrome extension and the Android app use it. Images are never uploaded anywhere.

> **Honest status (alpha).** The protection is measured at the model's image encoder only. No image generator was
> trained or run on protected pictures, so the effect on real style copying or editing is **not proven**.
> Published work shows this kind of protection can be weakened by heavy compression, blurring or purpose-built
> removal tools. Treat it as making misuse harder, not impossible.

## Run it

Needs [uv](https://docs.astral.sh/uv/) and Python 3.12 (uv fetches it).

```sh
cd poisoner
uv sync
uv run aipoisoner serve              # http://127.0.0.1:8765 (web page + API)
uv run aipoisoner serve --lan        # also reachable from your phone, with an access token
uv run aipoisoner protect photo.jpg  # one-off from the command line
uv run aipoisoner bench <folder>     # measure it on your own images
uv run pytest                        # 21 tests, no downloads needed
```

The first run downloads a 5 MB model. A Standard image takes about 1.5 minutes on a laptop CPU.
PyTorch is installed in its CPU-only build to keep the download small (see the comment in `pyproject.toml`
if you have an NVIDIA GPU).

## How it works

```
upload ─► JobManager (one worker) ─► Protector ─► attack.py ─► protected image
                                          │            │
                                          │            └─ PGD search against TAESD (5 MB copy of the SD encoder)
                                          └─ metrics.py: PSNR, SSIM, latent shift, after JPEG, after resize,
                                             measured again on the real SD VAE by `bench`
```

| File | What it does |
|---|---|
| `attack.py` | The search: moves the picture's latent far away (Cloak: toward a fixed pattern, Scramble: away from itself) while no pixel changes by more than the budget. Flat areas get a smaller budget. |
| `models.py` | Loads TAESD or the real SD VAE once, on first use. |
| `imageio.py` | Reads and writes images, keeping orientation, transparency and colour profile. EXIF (including GPS) is not copied. |
| `metrics.py` | The numbers shown in the UI and used in the benchmark. |
| `protector.py` | One call from image bytes to protected bytes, numbers and a model-view preview. |
| `jobs.py` | In-memory queue with progress, cancel and a waiting limit. |
| `server.py` | HTTP API and the web page. Answers only this computer's pages and browser extensions. With `--lan` every call needs the token. |
| `cli.py`, `bench.py` | The command line. |

## API

| Call | Purpose |
|---|---|
| `GET /api/health` | Version, device, strengths (no token needed) |
| `POST /api/protect` | multipart `file`, `strength` (light, standard, strong), `mode` (cloak, scramble), `format` (png, jpeg, webp). Returns a job id |
| `GET /api/jobs/{id}` | Status, step, time left, numbers when done |
| `GET /api/jobs/{id}/image` | The protected file |
| `GET /api/jobs/{id}/preview` | What the encoder rebuilds: original, then protected |
| `DELETE /api/jobs/{id}` | Cancel or forget |

Interactive docs are at `/api/docs` while the server runs.

## Measured results

Six real photos (about 960 x 640), ThinkPad T490 CPU. The search runs against TAESD. Every number except time
was measured on the **real** Stable Diffusion encoder (`sd-vae-ft-mse`), which the search never saw.
"Latent shift" is how far the protected picture moved from the original inside the encoder, as a fraction of the
original's own spread. "Random noise" is noise of exactly the same size per pixel, random direction.

| Setting | Biggest pixel change | Time | PSNR | SSIM | Latent shift | Random noise | Times stronger | After JPEG 85 | After half size |
|---|---|---|---|---|---|---|---|---|---|
| Light, Cloak | 4 / 255 | 39 s | 40.9 dB | 0.994 | 0.38 | 0.06 | 6.9 | 0.38 | 0.35 |
| Standard, Cloak | 8 / 255 | 97 s | 35.0 dB | 0.978 | 0.55 | 0.13 | 4.3 | 0.54 | 0.50 |
| Strong, Cloak | 16 / 255 | 132 s | 29.2 dB | 0.939 | 0.73 | 0.23 | 3.2 | 0.72 | 0.69 |
| Standard, Scramble | 8 / 255 | 81 s | 35.5 dB | 0.971 | 0.83 | 0.13 | 6.5 | 0.80 | 0.61 |

What this shows and does not show:

- About 95% of the effect found against TAESD carried over to the real encoder.
- It survives JPEG quality 85 and halving the size well.
- Going from 40 to 100 search steps on one photo changed the shift by under 3%, so the limit is how much the
  picture may change, not search time.
- At Standard the picture the encoder rebuilds is still clearly recognisable, with faint colour blotches. At
  Strong, grain is visible to a person in flat areas.
- Not tested: a real image generator, purification or removal attacks, other encoders (SDXL, Flux), a GPU.

Reproduce: `uv run aipoisoner bench <folder of photos> --limit 6 --strength standard`.

## Credits

The method follows the encoder attack of PhotoGuard (Salman et al., 2023), with a fixed target pattern as in Mist
(Liang and Wu, 2023). TAESD is by Ollin Boer Bohan. Related work is cited in the project report.
