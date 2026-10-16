// Copies the transformers.js browser bundle and the ONNX Runtime WASM files into extension/lib.
// MV3 forbids remotely hosted code, so these must ship inside the extension.
import { copyFileSync, mkdirSync, readdirSync, realpathSync } from 'node:fs';
import { join } from 'node:path';

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
