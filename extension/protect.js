// Full-tab page: protect an image through the local backend (poisoner/, `aipoisoner serve`).
const $ = id => document.getElementById(id);
const DEFAULT_URL = 'http://127.0.0.1:8765';
const HINTS = {
  light: 'Hardest to see. Weakest effect.', standard: 'Good balance. Faint grain if you zoom in.',
  strong: 'Visible grain on flat areas. Strongest effect.',
  cloak: 'Pulls the image toward a fixed pattern, so the AI sees wrong shapes.',
  scramble: 'Pushes the image away from itself, in no particular direction.' };
let base = DEFAULT_URL, token = '', file = null, job = null, timer = null, urls = [], strengths = {}, up = null;

const cleanUrl = v => {
  try { const u = new URL(v.trim()); return /^https?:$/.test(u.protocol) ? u.origin : null; } catch { return null; }
};
const api = (path, opt = {}) => fetch(base + '/api' + path, { ...opt, headers: { ...(token && { 'X-AIP-Token': token }), ...opt.headers } });
const fail = msg => { $('err').textContent = msg; $('err').hidden = !msg; };
const fmt = s => s >= 60 ? `${Math.round(s / 60)} min` : `${s} s`;
const busy = () => !$('work').hidden || !$('done').hidden;

function hints() {
  const f = new FormData($('form')), st = strengths[f.get('strength')];
  $('shint').textContent = HINTS[f.get('strength')] + (st ? ` Changes each pixel by at most ${st.eps_255} of 255, in ${st.steps} steps.` : '');
  $('mhint').textContent = HINTS[f.get('mode')];
}

async function health(manual) {
  if (manual) $('retrynote').textContent = 'Checking…';
  let h = null;
  try {
    const r = await api('/health', { signal: AbortSignal.timeout(4000) });
    h = await r.json();
    if (!r.ok || !h.ok) h = null;
  } catch {}
  up = !!h;
  $('setup').hidden = up;
  $('form').hidden = !up || busy();
  $('retrynote').textContent = manual && !up ? 'Still not answering.' : '';
  if (!h) { $('dot').className = 'dot bad'; $('health').textContent = `Backend is not answering at ${base}.`; return; }
  strengths = h.strengths || {}; hints();
  $('dot').className = 'dot ok';
  $('health').textContent = `Backend ready · ${h.device === 'cpu' ? 'CPU (a few minutes per image)' : 'GPU'} · v${h.version}` +
    (h.model_loaded ? '' : ' · the model downloads on first use (5 MB)');
  if (h.token_required && !token) fail('This server needs an access token. Add it under "Backend address" below.');
}
const loop = () => health().finally(() => setTimeout(loop, up ? 15000 : 4000));

const dropLabel = (title, sub, rest = ' or drop one here') => {
  const t = $('droptext'); t.replaceChildren();
  const b = document.createElement('b'); b.textContent = title;
  const s = document.createElement('small'); s.textContent = sub;
  t.append(b, rest, document.createElement('br'), s);
};
function pick(f) {
  if (!f) return;
  if (!f.type.startsWith('image/')) return fail('That file is not an image.');
  if (f.size > 30 * 1024 * 1024) return fail('That file is larger than 30 MB.');
  fail(''); file = f;
  dropLabel(f.name, 'Click to choose a different image', '');
  $('go').disabled = false;
}
$('file').onchange = e => pick(e.target.files[0]);
const drop = $('drop');
['dragenter', 'dragover'].forEach(n => drop.addEventListener(n, e => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave', 'drop'].forEach(n => drop.addEventListener(n, e => { e.preventDefault(); drop.classList.remove('over'); }));
drop.addEventListener('drop', e => pick(e.dataTransfer.files[0]));
$('form').onchange = hints;

// Image chosen from the right-click menu: protect.html?src=<url>
async function preload() {
  const src = new URLSearchParams(location.search).get('src');
  if (!src) return;
  let u;
  try { u = new URL(src); } catch { return fail('The image address from the right-click menu is not valid.'); }
  if (!/^(https?|data|blob):$/.test(u.protocol)) return fail('Only http, https, data and blob image addresses can be loaded.');
  $('droptext').textContent = 'Loading the image…';
  try {
    const r = await fetch(src);
    if (!r.ok) throw new Error(r.status);
    const blob = await r.blob();
    if (!blob.type.startsWith('image/')) throw new Error('not an image');
    const ext = (blob.type.split('/')[1] || 'png').replace('jpeg', 'jpg').replace(/\W.*/, '');
    let name = u.protocol.startsWith('http') ? decodeURIComponent(u.pathname.split('/').pop() || '') : '';
    if (!/\.\w{2,4}$/.test(name)) name = (name || 'image') + '.' + ext;
    pick(new File([blob], name.slice(-80), { type: blob.type }));
  } catch {
    dropLabel('Choose an image', 'PNG, JPEG or WebP, up to 30 MB');
    fail('Could not load that image (the site may block downloads, or the link expired). Save it and choose the file instead.');
  }
}

$('form').onsubmit = async e => {
  e.preventDefault(); if (!file) return;
  fail(''); const body = new FormData($('form')); body.set('file', file);
  $('go').disabled = true;
  try {
    const r = await api('/protect', { method: 'POST', body });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(typeof j.detail === 'string' ? j.detail : 'The backend refused the image.');
    job = j.id; $('form').hidden = true; $('done').hidden = true; $('work').hidden = false;
    $('wtitle').textContent = 'Waiting…'; $('wtext').textContent = ''; poll();
  } catch (err) { fail(err.message || 'Could not reach the backend.'); $('go').disabled = false; }
};
async function poll() {
  clearTimeout(timer);
  let j;
  try { const r = await api('/jobs/' + job); j = await r.json(); if (!r.ok) throw new Error(j.detail); }
  catch (err) { return reset(err.message || 'Lost contact with the backend.'); }
  if (j.status === 'queued') { $('wtitle').textContent = 'Waiting…'; $('wtext').textContent = j.queue_position ? `${j.queue_position} image(s) ahead of this one.` : 'Loading the model.'; }
  if (j.status === 'running') {
    $('wtitle').textContent = 'Working…'; $('fill').style.width = (j.progress * 100) + '%';
    $('bar').setAttribute('aria-valuenow', Math.round(j.progress * 100));
    $('wtext').textContent = j.step ? `Step ${j.step} of ${j.steps}` + (j.eta_seconds != null ? ` · about ${fmt(j.eta_seconds)} left` : '') : 'Loading the model.';
  }
  if (j.status === 'done') return show(j);
  if (j.status === 'error') return reset(j.error || 'The backend could not protect this image.');
  if (j.status === 'cancelled') return reset('');
  timer = setTimeout(poll, 1000);
}
async function blobUrl(path) {
  const r = await api(path); if (!r.ok) throw new Error('Could not fetch the result.');
  const u = URL.createObjectURL(await r.blob()); urls.push(u); return u;
}
async function show(j) {
  try {
    urls.forEach(URL.revokeObjectURL); urls = [];
    $('before').src = URL.createObjectURL(file); urls.push($('before').src);
    $('after').src = await blobUrl(`/jobs/${job}/image`);
    $('view').src = await blobUrl(`/jobs/${job}/preview`);
  } catch (err) { return reset(err.message); }
  $('save').href = $('after').src;
  $('save').download = file.name.replace(/\.[^.]+$/, '') + '_protected.' + (j.format === 'jpeg' ? 'jpg' : j.format);
  const m = j.metrics, rows = [
    ['How similar it looks', 'PSNR; above about 35 dB is hard to notice', m.psnr_db + ' dB'],
    ['Structure kept', 'SSIM; 1.00 is identical', m.ssim.toFixed(3)],
    ['Largest change to any pixel', '', m.max_change + ' / 255'],
    ['Latent shift', 'how far the AI encoder’s reading moved', m.latent_shift.toFixed(2)],
    ['Compared with random noise of the same size', '', m.times_random_noise + '× stronger'],
    ['Shift left after JPEG (quality 85)', '', m.shift_after_jpeg85.toFixed(2)],
    ['Shift left after halving the size', '', m.shift_after_half_size.toFixed(2)],
    ['Time taken', '', fmt(Math.round(j.seconds))]];
  $('numbers').replaceChildren(...rows.map(([a, note, v]) => {
    const tr = document.createElement('tr'), l = tr.insertCell(), r = tr.insertCell();
    l.append(a);
    if (note) { const s = document.createElement('small'); s.textContent = note; l.append(s); }
    r.textContent = v; return tr;
  }));
  $('work').hidden = true; $('done').hidden = false; $('done-h').focus();
}
function reset(msg) {
  clearTimeout(timer); job = null; fail(msg || '');
  $('work').hidden = true; $('done').hidden = true; $('fill').style.width = 0; $('go').disabled = !file;
  $('form').hidden = !up;
}
$('cancel').onclick = async () => { if (job) await api('/jobs/' + job, { method: 'DELETE' }).catch(() => {}); reset(''); $('go').focus(); };
$('again').onclick = () => {
  if (job) api('/jobs/' + job, { method: 'DELETE' }).catch(() => {});
  file = null; $('file').value = '';
  dropLabel('Choose an image', 'PNG, JPEG or WebP, up to 30 MB');
  reset(''); $('file').focus();
};

$('retry').onclick = () => health(true);
$('sform').onsubmit = async e => {
  e.preventDefault();
  const origin = cleanUrl($('addr').value.trim() || DEFAULT_URL);
  if (!origin) return void ($('snote').textContent = 'Use a full address starting with http:// or https://');
  base = origin; token = $('tok').value.trim(); $('addr').value = base;
  await chrome.storage.local.set({ backendUrl: base, backendToken: token });
  $('snote').textContent = 'Saved.'; fail(''); health(true);
};

(async () => {
  const s = await chrome.storage.local.get({ backendUrl: DEFAULT_URL, backendToken: '' });
  base = cleanUrl(s.backendUrl) || DEFAULT_URL; token = s.backendToken || '';
  $('addr').value = base; $('tok').value = token;
  if (base !== DEFAULT_URL || token) $('settings').open = true;
  hints(); loop(); preload();
})();
