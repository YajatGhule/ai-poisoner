const DEFAULTS = { enabled: true, mode: 'label', aiAt: 0.85, humanBelow: 0.6 };
const $ = id => document.getElementById(id);

chrome.storage.sync.get(DEFAULTS).then(s => {
  $('enabled').setAttribute('aria-pressed', s.enabled);
  for (const b of $('mode').children) b.setAttribute('aria-pressed', b.dataset.v === s.mode);
  // The slider is "sensitivity": further right flags more images, so it inverts the AI threshold.
  $('sens').value = (1.57 - s.aiAt).toFixed(2);
});

$('enabled').onclick = e => {
  const on = e.currentTarget.getAttribute('aria-pressed') !== 'true';
  e.currentTarget.setAttribute('aria-pressed', on);
  chrome.storage.sync.set({ enabled: on });
};
$('mode').onclick = e => {
  const v = e.target.dataset.v;
  if (!v) return;
  for (const b of $('mode').children) b.setAttribute('aria-pressed', b === e.target);
  chrome.storage.sync.set({ mode: v });
};
$('sens').oninput = e => {
  const aiAt = +(1.57 - e.target.value).toFixed(2);
  chrome.storage.sync.set({ aiAt, humanBelow: Math.min(0.6, aiAt - 0.1) });
};

async function poll() {
  const s = await chrome.runtime.sendMessage({ type: 'status' }).catch(() => null);
  if (s && !s.error) {
    $('dot').className = `dot ${s.state}`;
    $('st').textContent = {
      idle: 'Model idle',
      loading: `Downloading model… ${Math.round(s.progress * 100)}% (once, ~90 MB)`,
      ready: `Model ready · ${s.scanned} image${s.scanned === 1 ? '' : 's'} checked`,
      error: `Model error: ${s.error}`,
    }[s.state];
  }
  setTimeout(poll, 800);
}
poll();
