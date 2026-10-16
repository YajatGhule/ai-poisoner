// Builds dist/ai-poisoner-v<version>.zip, ready for "Load unpacked" (after unzipping) or the Chrome Web Store.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs';

const ext = new URL('../extension/', import.meta.url).pathname;
const dist = new URL('../dist/', import.meta.url).pathname;
if (!existsSync(ext + 'lib/transformers.min.js')) execFileSync('node', [new URL('./vendor.mjs', import.meta.url).pathname], { stdio: 'inherit' });

const { version } = JSON.parse(readFileSync(ext + 'manifest.json', 'utf8'));
mkdirSync(dist, { recursive: true });
const zip = `${dist}ai-poisoner-v${version}.zip`;
rmSync(zip, { force: true });
execFileSync('bsdtar', ['-a', '-cf', zip, '-C', ext, '.'], { stdio: 'inherit' });
console.log('wrote', zip);
