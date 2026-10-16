// Offscreen document: loads the classifier once and answers classify requests.
import { env, pipeline, RawImage } from './lib/transformers.min.js';

const MODEL = 'onnx-community/ai-image-detection-ONNX'; // labels: REAL / FAKE
const AI_LABEL = 'FAKE';

env.allowLocalModels = false;
env.useWasmCache = false; // Cache API can't store chrome-extension:// URLs; the files are local anyway
env.backends.onnx.wasm.wasmPaths = {
  mjs: chrome.runtime.getURL('lib/ort-wasm-simd-threaded.asyncify.mjs'),
  wasm: chrome.runtime.getURL('lib/ort-wasm-simd-threaded.asyncify.wasm'),
};
env.backends.onnx.wasm.numThreads = 1; // extension pages are not cross-origin isolated

const status = { state: 'idle', progress: 0, error: null, scanned: 0 };
let classifier = null;

function load() {
  classifier ??= (async () => {
    status.state = 'loading';
    const files = {};
    try {
      const clf = await pipeline('image-classification', MODEL, {
        dtype: 'q8',
        device: 'wasm',
        progress_callback: p => {
          if (p.status === 'progress' && p.total) {
            files[p.file] = [p.loaded, p.total];
            const [l, t] = Object.values(files).reduce((a, [x, y]) => [a[0] + x, a[1] + y], [0, 0]);
            status.progress = l / t;
          }
        },
      });
      status.state = 'ready';
      status.progress = 1;
      return clf;
    } catch (e) {
      status.state = 'error';
      status.error = String(e?.message ?? e);
      classifier = null;
      throw e;
    }
  })();
  return classifier;
}

// Provenance metadata: cheap, and far more reliable than the model when present.
// IPTC DigitalSourceType is written by Google, OpenAI, Adobe, Meta and others.
const ASCII = new TextDecoder('latin1');
function readMetadata(buf) {
  const s = ASCII.decode(buf.byteLength > 16e6 ? buf.slice(0, 16e6) : buf);
  const c2pa = s.includes('c2pa');
  if (s.includes('compositeWithTrainedAlgorithmicMedia')) return { verdict: 'ai', why: 'Metadata: edited with generative AI', c2pa };
  if (s.includes('trainedAlgorithmicMedia')) return { verdict: 'ai', why: 'Metadata: created by generative AI', c2pa };
  if (s.includes('Made with Google AI')) return { verdict: 'ai', why: 'Metadata: Made with Google AI', c2pa };
  if (/Steps: \d+, Sampler: /.test(s)) return { verdict: 'ai', why: 'Stable Diffusion settings embedded', c2pa };
  if (s.includes('"class_type"') && s.includes('KSampler')) return { verdict: 'ai', why: 'ComfyUI workflow embedded', c2pa };
  return { verdict: null, why: null, c2pa };
}

function isRaster(b) {
  const at = (i, ...xs) => xs.every((x, j) => b[i + j] === x);
  return at(0, 0xff, 0xd8, 0xff)                         // JPEG
    || at(0, 0x89, 0x50, 0x4e, 0x47)                     // PNG
    || at(0, 0x47, 0x49, 0x46)                           // GIF
    || (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) // WebP
    || at(4, 0x66, 0x74, 0x79, 0x70)                     // AVIF / HEIC (ftyp)
    || at(0, 0x42, 0x4d);                                // BMP
}

async function score(image) {
  const clf = await load();
  const out = await clf(image, { top_k: 2 });
  status.scanned++;
  return out.find(o => o.label === AI_LABEL)?.score ?? 0;
}

const cache = new Map();
function remember(key, value) {
  cache.set(key, value);
  if (cache.size > 3000) cache.delete(cache.keys().next().value);
  return value;
}

async function classifyUrl(url) {
  if (cache.has(url)) return cache.get(url);
  const res = await fetch(url, { cache: 'force-cache', credentials: 'include' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const blob = await res.blob();
  const buf = await blob.arrayBuffer();
  // Servers often send the wrong Content-Type, so sniff the bytes instead.
  if (!isRaster(new Uint8Array(buf, 0, Math.min(16, buf.byteLength)))) return remember(url, { skip: true });
  const meta = readMetadata(buf);
  const p = meta.verdict === 'ai' ? 1 : await score(await RawImage.fromBlob(blob));
  return remember(url, { p, source: meta.verdict ? 'metadata' : 'model', why: meta.why, c2pa: meta.c2pa });
}

async function classifyRegion(shot, rect, dpr) {
  const bmp = await createImageBitmap(await (await fetch(shot)).blob());
  const x = Math.max(0, Math.round(rect.x * dpr)), y = Math.max(0, Math.round(rect.y * dpr));
  const w = Math.min(bmp.width - x, Math.round(rect.w * dpr)), h = Math.min(bmp.height - y, Math.round(rect.h * dpr));
  if (w < 32 || h < 32) return { skip: true };
  const canvas = new OffscreenCanvas(w, h);
  canvas.getContext('2d').drawImage(bmp, x, y, w, h, 0, 0, w, h);
  const p = await score(await RawImage.fromBlob(await canvas.convertToBlob({ type: 'image/png' })));
  return { p, source: 'screen', why: null, c2pa: false };
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg.target !== 'offscreen') return false;
  const job = {
    'classify-url': () => classifyUrl(msg.url),
    'classify-region': () => classifyRegion(msg.shot, msg.rect, msg.dpr),
    'status': async () => ({ ...status }),
    'warmup': async () => { load().catch(() => {}); return { ...status }; },
  }[msg.type];
  if (!job) return false;
  job().then(reply, e => reply({ error: String(e?.message ?? e) }));
  return true;
});

load().catch(() => {});
