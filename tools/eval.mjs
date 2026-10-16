// Score test images with candidate detector models: node tools/eval.mjs <dir with ai/ and real/>
import { pipeline } from '@huggingface/transformers';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';

const root = process.argv[2];
const models = [
  ['onnx-community/ai-image-detect-distilled-ONNX', 'q8', l => l === 'fake'],
  ['onnx-community/SMOGY-Ai-images-detector-ONNX', 'q8', l => l === 'artificial'],
  ['onnx-community/ai-image-detection-ONNX', 'q8', l => l === 'FAKE'],
];
for (const [id, dtype, isAi] of models) {
  const clf = await pipeline('image-classification', id, { dtype });
  let correct = 0, n = 0; const t0 = Date.now();
  for (const cls of ['ai', 'real']) {
    for (const f of readdirSync(join(root, cls))) {
      const out = await clf(join(root, cls, f), { top_k: 2 });
      const pAi = out.filter(o => isAi(o.label)).reduce((a, o) => a + o.score, 0);
      if ((pAi > 0.5) === (cls === 'ai')) correct++;
      n++;
      process.stdout.write(`${cls}:${pAi.toFixed(2)} `);
    }
  }
  console.log(`\n${id}: ${correct}/${n} correct, ${((Date.now() - t0) / n).toFixed(0)} ms/img\n`);
}
