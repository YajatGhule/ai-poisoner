// Content script: finds images/videos on the page, asks the extension to score them,
// and draws badges in a fixed overlay (inside a shadow root so page CSS can't touch it).
(() => {
  if (window.__aiPoisoner) return;
  window.__aiPoisoner = true;

  const MIN = 80;            // ignore icons, avatars, spacers
  const CONCURRENCY = 2;
  let settings = { enabled: true, mode: 'label', aiAt: 0.85, humanBelow: 0.6 };

  // ---------- overlay ----------
  const host = document.createElement('ai-poisoner-overlay');
  host.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483646;';
  const root = host.attachShadow({ mode: 'closed' });
  root.innerHTML = `<style>
    .b{position:fixed;display:flex;align-items:center;gap:6px;height:22px;padding:0 9px 0 7px;
       border-radius:11px;background:rgba(14,13,12,.82);backdrop-filter:blur(6px);color:#ede7db;
       font:500 11px/1 Inter,ui-sans-serif,system-ui,sans-serif;letter-spacing:.02em;white-space:nowrap;
       pointer-events:auto;cursor:pointer;box-shadow:0 1px 6px rgba(0,0,0,.35);
       border:1px solid rgba(255,255,255,.08);user-select:none}
    .b i{width:7px;height:7px;border-radius:50%;flex:none}
    .b.ai i{background:#e0604f} .b.unsure i{background:#c9a45c} .b.human i{background:#6fbf8a}
    .b.wait i{background:#8d867c;animation:p 1s infinite alternate} @keyframes p{to{opacity:.25}}
    .b .why{display:none;color:#b9b2a6;font-weight:400;padding-left:6px;border-left:1px solid #3a3632}
    .b:hover .why{display:inline}
    .b .cr{font-size:9px;letter-spacing:.08em;color:#c9a45c;border:1px solid #c9a45c;border-radius:3px;padding:1px 3px}
  </style>`;

  // ---------- tracked elements ----------
  const tracked = new Map(); // el -> { badge, result, key, blurred, revealed, origFilter }
  const queue = [];
  let active = 0;

  const keyOf = el => el.tagName === 'IMG' ? (el.currentSrc || el.src) : (el.currentSrc || el.src || el.poster || 'video');

  // Thumbnails are tiny; the model does better on the largest version the page offers.
  function bestUrl(img) {
    let best = img.currentSrc || img.src, bestW = img.naturalWidth || 0;
    for (const part of (img.srcset || '').split(/,\s+/)) {
      const [u, d = ''] = part.trim().split(/\s+/);
      const w = d.endsWith('w') ? parseInt(d) : d.endsWith('x') ? parseFloat(d) * (img.naturalWidth || 300) : 0;
      if (u && w > bestW && w <= 2048) { best = new URL(u, document.baseURI).href; bestW = w; }
    }
    return best;
  }
  const bigEnough = el => {
    const r = el.getBoundingClientRect();
    return r.width >= MIN && r.height >= MIN;
  };

  function verdict(p) {
    if (p >= settings.aiAt) return 'ai';
    if (p < settings.humanBelow) return 'human';
    return 'unsure';
  }

  function render(el) {
    const t = tracked.get(el);
    if (!t) return;
    const b = t.badge;
    const res = t.result;
    if (!res) { b.className = 'b wait'; b.innerHTML = '<i></i>Scanning'; return; }
    if (res.error) { b.className = 'b wait'; b.innerHTML = '<i></i>?<span class="why">Couldn’t read image · click to retry</span>'; return; }
    const v = verdict(res.p);
    const pct = Math.round(res.p * 100);
    const label = { ai: `AI ${pct}%`, unsure: `Unsure ${pct}%`, human: 'Human' }[v];
    const why = res.why ?? `${res.source === 'screen' ? 'Screen check' : 'Model'} · ${pct}% AI`;
    b.className = `b ${v}`;
    b.innerHTML = `<i></i>${label}${res.c2pa ? '<span class="cr">CR</span>' : ''}<span class="why">${why}</span>`;
    b.title = t.blurred ? 'Click to reveal' : 'Click to re-check from screen';
    applyBlur(el, settings.mode === 'blur' && v === 'ai' && !t.revealed);
  }

  function applyBlur(el, on) {
    const t = tracked.get(el);
    if (on === !!t.blurred) return;
    if (on) t.origFilter = el.style.filter;
    el.style.filter = on ? 'blur(22px) saturate(.6)' : t.origFilter;
    t.blurred = on;
  }

  function track(el) {
    if (tracked.has(el) || !settings.enabled) return;
    const badge = document.createElement('div');
    badge.addEventListener('click', e => {
      e.stopPropagation();
      const t = tracked.get(el);
      if (!t) return;
      if (t.blurred) { t.revealed = true; applyBlur(el, false); render(el); return; }
      t.result = null; render(el); enqueue(el, true);   // re-check from the screen
    });
    root.appendChild(badge);
    tracked.set(el, { badge, result: null, key: keyOf(el) });
    render(el);
    place();
    enqueue(el, false);
  }

  function untrack(el) {
    const t = tracked.get(el);
    if (!t) return;
    t.badge.remove();
    if (t.blurred) el.style.filter = t.origFilter;
    tracked.delete(el);
  }

  // ---------- scanning ----------
  function enqueue(el, fromScreen) {
    queue.push({ el, fromScreen });
    pump();
  }

  function pump() {
    while (active < CONCURRENCY && queue.length) {
      const job = queue.shift();
      if (!tracked.has(job.el)) continue;
      active++;
      scan(job).finally(() => { active--; pump(); });
    }
  }

  async function scan({ el, fromScreen }) {
    const url = el.tagName === 'IMG' ? bestUrl(el) : keyOf(el);
    const viaUrl = !fromScreen && el.tagName === 'IMG' && /^(https?:|data:image)/.test(url);
    let res = viaUrl ? await send({ type: 'classify-url', url }) : await screenCheck(el);
    if (res?.error && viaUrl) res = await screenCheck(el);   // CDN refused us: use the pixels on screen
    const t = tracked.get(el);
    if (!t) return;
    if (res?.skip) { untrack(el); return; }
    t.result = res ?? { error: 'no response' };
    render(el);
  }

  async function screenCheck(el) {
    // Wait until the element is actually on screen, then screenshot just that box.
    for (let i = 0; i < 40 && !inView(el); i++) await sleep(500);
    if (!inView(el)) return { error: 'not visible' };
    const r = el.getBoundingClientRect();
    const rect = {
      x: Math.max(0, r.left), y: Math.max(0, r.top),
      w: Math.min(innerWidth, r.right) - Math.max(0, r.left),
      h: Math.min(innerHeight, r.bottom) - Math.max(0, r.top),
    };
    hiders++;
    host.style.visibility = 'hidden';               // keep our badges out of the screenshot
    await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
    let shown = false;
    const show = () => { if (!shown) { shown = true; if (--hiders === 0) host.style.visibility = ''; } };
    const id = ++captureIds;
    onCaptured.set(id, show);
    try { return await send({ type: 'classify-region', id, rect, dpr: devicePixelRatio }); }
    finally { onCaptured.delete(id); show(); }
  }

  // Background says "screenshot taken": badges can come back while the model runs.
  let hiders = 0, captureIds = 0;
  const onCaptured = new Map();
  chrome.runtime.onMessage.addListener(msg => {
    if (msg.type === 'captured') onCaptured.get(msg.id)?.();
  });

  const inView = el => {
    const r = el.getBoundingClientRect();
    return r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth && r.width >= MIN;
  };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const send = msg => chrome.runtime.sendMessage(msg).catch(e => ({ error: String(e) }));

  // ---------- positioning ----------
  let raf = 0;
  function place() {
    if (raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      for (const [el, t] of tracked) {
        if (!el.isConnected) { untrack(el); continue; }
        // src swapped (carousels, lazy loading): start over
        if (keyOf(el) !== t.key) { untrack(el); consider(el); continue; }
        const r = el.getBoundingClientRect();
        const visible = r.width >= MIN && r.height >= MIN && r.bottom > 0 && r.top < innerHeight
          && getComputedStyle(el).visibility !== 'hidden';
        t.badge.style.display = visible ? '' : 'none';
        if (visible) {
          t.badge.style.left = `${Math.max(4, r.left + 8)}px`;
          t.badge.style.top = `${Math.max(4, r.top + 8)}px`;
        }
      }
    });
  }

  // ---------- discovery ----------
  const io = new IntersectionObserver(entries => {
    for (const e of entries) if (e.isIntersecting && bigEnough(e.target)) { io.unobserve(e.target); track(e.target); }
  }, { rootMargin: '400px' });

  function consider(el) {
    if (tracked.has(el) || !settings.enabled) return;
    if (el.tagName === 'IMG' && !el.complete) { el.addEventListener('load', () => consider(el), { once: true }); return; }
    io.observe(el);
  }

  function scanTree(node) {
    if (node.nodeType !== 1) return;
    if (node.matches('img,video')) consider(node);
    node.querySelectorAll('img,video').forEach(consider);
  }

  const mo = new MutationObserver(muts => {
    for (const m of muts) {
      if (m.type === 'attributes') { if (m.target.matches('img,video')) consider(m.target); continue; }
      m.addedNodes.forEach(scanTree);
    }
    place();
  });

  function start() {
    if (!host.isConnected) document.documentElement.appendChild(host);
    scanTree(document.body);
    mo.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'srcset'] });
  }

  function stop() {
    mo.disconnect();
    io.disconnect();
    queue.length = 0;
    for (const el of [...tracked.keys()]) untrack(el);
    host.remove();
  }

  addEventListener('scroll', place, { capture: true, passive: true });
  addEventListener('resize', place, { passive: true });
  setInterval(place, 700);   // catches layout shifts that fire no events

  chrome.storage.sync.get(settings).then(s => {
    settings = s;
    if (settings.enabled) start();
  });
  chrome.storage.onChanged.addListener(changes => {
    const was = settings.enabled;
    for (const [k, { newValue }] of Object.entries(changes)) settings[k] = newValue;
    if (was && !settings.enabled) stop();
    else if (!was && settings.enabled) start();
    else for (const el of tracked.keys()) render(el);
  });
})();
