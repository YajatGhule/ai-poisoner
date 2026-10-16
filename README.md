# AI Poisoner: AI image detector for Chrome

A Chrome extension that labels images on any web page as **AI**, **Unsure**, or **Human**, live, as you scroll.
It's the desktop prototype of the *AI Data Poisoner & Detector* design-thinking project (Capture → Processing → Overlay).

Everything runs **on your computer**. Images are never uploaded anywhere.

## How it works

```
content.js      finds <img>/<video> on the page → badge overlay (shadow DOM, fixed position)
   │
background.js   service worker: routes requests, takes tab screenshots for the fallback path
   │
offscreen.js    runs the model (transformers.js + ONNX Runtime WASM) and the metadata check
```

Each image is checked in two steps:

1. **Provenance metadata (exact).** The extension fetches the original file and looks for the IPTC
   `trainedAlgorithmicMedia` tag, which OpenAI, Google, Adobe, Meta and others write. It also looks for
   "Made with Google AI", Stable Diffusion / ComfyUI generation settings, and C2PA content credentials
   (shown as a gold **CR** chip). When any of these are found, the verdict is certain.
2. **Classifier (estimate).** If there's no metadata, a ViT model
   ([`onnx-community/ai-image-detection-ONNX`](https://huggingface.co/onnx-community/ai-image-detection-ONNX), 8-bit, ~90 MB,
   downloaded once and cached) gives a probability. Below 60% shows *Human*, 85% and above shows *AI*, and anything in between shows *Unsure*.
   The **Sensitivity** slider in the popup moves these thresholds.

If a site refuses to serve the image file to the extension, or for `<video>`, the extension screenshots just that
box on screen and classifies the pixels instead. Clicking any badge forces this screen re-check.

**Blur mode** blurs anything rated AI. Click the badge to reveal it.

## Install (developer mode)

```sh
pnpm install
pnpm build          # copies transformers.js + ONNX Runtime WASM into extension/lib
pnpm package        # optional: dist/ai-poisoner-v0.1.0.zip
```

Then open `chrome://extensions`, turn on **Developer mode**, click **Load unpacked**, and pick the `extension/` folder.
The first page you visit downloads the model (~90 MB). The popup shows the progress.

## Limits, honestly

- The classifier is a **hint, not proof**. On a small test set (20 AI and 16 real images from Wikimedia Commons)
  it ranked AI above real 87% of the time (AUC 0.87). At the default threshold it caught about half the AI images
  and wrongly flagged about 1 in 16 real photos. Newer generators and heavy compression make it worse.
- Social sites strip metadata from uploads, so the exact metadata check mostly helps with original files
  (news sites, stock photos, Commons, direct links).
- `pnpm eval <dir>` scores a folder with `ai/` and `real/` subfolders, so you can try other models.

## Not built yet

- **Poisoner**: protect your own uploads (Glaze/Nightshade-style perturbation, e.g. Mist or PhotoGuard on a GPU backend).
- Continuous video scanning (videos are currently checked once, from a screenshot).
- Android version (AccessibilityService + LiteRT).
