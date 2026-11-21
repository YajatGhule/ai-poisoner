// Copies the transformers.js browser bundle and the ONNX Runtime WASM files into extension/lib.
// MV3 forbids remotely hosted code, so these must ship inside the extension.
import { copyFileSync, existsSync, mkdirSync, readdirSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const out = new URL('../extension/lib/', import.meta.url).pathname;
mkdirSync(out, { recursive: true });

// Resolve through pnpm's symlinks so the sibling onnxruntime-web is reachable.
const root = new URL('../node_modules/', import.meta.url).pathname;
const tf = realpathSync(join(root, '@huggingface/transformers'));
copyFileSync(join(tf, 'dist/transformers.min.js'), join(out, 'transformers.min.js'));

const ort = join(realpathSync(join(tf, '../../onnxruntime-web')), 'dist');
for (const f of readdirSync(ort).filter(f => /^ort-wasm-simd-threaded\.asyncify\.(mjs|wasm)$/.test(f))) {
  copyFileSync(join(ort, f), join(out, f));
}
console.log('vendored into', out, readdirSync(out));

// The detection model ships inside the extension too, so it works offline (for example at a demo)
// and needs no 90 MB download on first use. Downloaded once from Hugging Face, then kept.
const MODEL = 'onnx-community/ai-image-detection-ONNX';
const modelDir = new URL(`../extension/models/${MODEL}/`, import.meta.url).pathname;
for (const f of ['config.json', 'preprocessor_config.json', 'onnx/model_quantized.onnx']) {
  const dst = join(modelDir, f);
  if (existsSync(dst)) continue;
  mkdirSync(dirname(dst), { recursive: true });
  const res = await fetch(`https://huggingface.co/${MODEL}/resolve/main/${f}`);
  if (!res.ok) throw new Error(`download ${f}: HTTP ${res.status}`);
  writeFileSync(dst, Buffer.from(await res.arrayBuffer()));
  console.log('model file', f, statSync(dst).size, 'bytes');
}
