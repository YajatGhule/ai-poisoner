// Service worker: owns the offscreen document (which runs the model), screenshots,
// and settings. Content scripts talk only to this worker.

const OFFSCREEN = 'offscreen.html';
export const DEFAULTS = { enabled: true, mode: 'label', aiAt: 0.85, humanBelow: 0.6 };

let creating = null;
async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  creating ??= chrome.offscreen.createDocument({
    url: OFFSCREEN,
    reasons: ['WORKERS'],
    justification: 'Run the AI-image classifier (ONNX/WASM) off the service worker.',
  }).finally(() => { creating = null; });
  await creating;
}

async function toOffscreen(msg) {
  await ensureOffscreen();
  return chrome.runtime.sendMessage({ ...msg, target: 'offscreen' });
}

// captureVisibleTab is limited to ~2 calls/s, so serialise and space them out.
let captureChain = Promise.resolve();
function capture(windowId) {
  const run = captureChain.then(async () => {
    const url = await chrome.tabs.captureVisibleTab(windowId, { format: 'png' });
    await new Promise(r => setTimeout(r, 550));
    return url;
  });
  captureChain = run.catch(() => {});
  return run;
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg.target === 'offscreen') return false;
  (async () => {
    switch (msg.type) {
      case 'classify-url':
        return toOffscreen({ type: 'classify-url', url: msg.url });
      case 'classify-region': {
        const shot = await capture(sender.tab.windowId);
        return toOffscreen({ type: 'classify-region', shot, rect: msg.rect, dpr: msg.dpr });
      }
      case 'status':
        return toOffscreen({ type: 'status' });
      case 'warmup':
        return toOffscreen({ type: 'warmup' });
    }
  })().then(reply, e => reply({ error: String(e?.message ?? e) }));
  return true;
});

chrome.runtime.onInstalled.addListener(async () => {
  const cur = await chrome.storage.sync.get(DEFAULTS);
  await chrome.storage.sync.set(cur);
  toOffscreen({ type: 'warmup' }).catch(() => {});
});
