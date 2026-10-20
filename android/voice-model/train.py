"""Train the AI-voice detector used by the Android app.

Model = frozen facebook/wav2vec2-base (layers 0..L) -> mean+std pooling over time -> logistic regression.
Exported as one ONNX graph: input_values [1, samples] (16 kHz) -> logits [1, 2] (index 1 = AI).

Data layout (see README.md for how it was built):
  DATA/{train,test}/real/*.wav        human speech, 16 kHz mono
  DATA/{train,test}/fake_raw/*.wav    AI speech (any rate; resampled here)
Train and test use different speakers, recordings, voice-engine voices, and one engine (MMS) only in test.

usage: python train.py DATA OUT_DIR
"""
import glob, json, os, random, subprocess, sys, tempfile
import numpy as np, soundfile as sf, torch
from transformers import Wav2Vec2Model

DATA, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
SR, WIN = 16000, 16000 * 4
random.seed(0); np.random.seed(0); torch.manual_seed(0)


def read(path):
    x, sr = sf.read(path, dtype='float32', always_2d=True)
    x = x.mean(1)
    if sr != SR:
        x = np.interp(np.arange(0, len(x), sr / SR), np.arange(len(x)), x).astype(np.float32)
    return x


def phone(x):
    """Phone-call quality copy: 300-3400 Hz band, Opus 16 kbps round trip (what a mic/speaker/codec chain does)."""
    with tempfile.TemporaryDirectory() as d:
        sf.write(f'{d}/a.wav', x, SR)
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', f'{d}/a.wav', '-af', 'highpass=f=300,lowpass=f=3400',
                        '-c:a', 'libopus', '-b:a', '16k', f'{d}/a.opus'], check=True)
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', f'{d}/a.opus', '-ac', '1', '-ar', str(SR), f'{d}/b.wav'], check=True)
        return read(f'{d}/b.wav')


def crop(x):
    if len(x) < WIN: x = np.pad(x, (0, WIN - len(x)))
    s = (len(x) - WIN) // 2
    return x[s:s + WIN]


def norm(x):
    return ((x - x.mean()) / np.sqrt(x.var() + 1e-7)).astype(np.float32)


def trim(x, thr=1e-3):
    """Drop near-silent 20 ms frames. TTS output has stretches of exact digital silence that real recordings
    never have; v1 of this model partly learned that shortcut (see README)."""
    fr = 320
    keep = [x[i:i + fr] for i in range(0, len(x) - fr, fr) if np.sqrt((x[i:i + fr] ** 2).mean()) > thr]
    return np.concatenate(keep) if keep else x


def noisy(x, rng):
    """Silences trimmed + a random noise floor (-75..-45 dBFS), applied to real AND AI clips alike."""
    t = trim(x)
    return (t + rng.normal(0, 10 ** (rng.uniform(-75, -45) / 20), len(t))).astype(np.float32)


def load_split(split):
    items = [(f, 0) for f in sorted(glob.glob(f'{DATA}/{split}/real/*.wav'))] + \
            [(f, 1) for f in sorted(glob.glob(f'{DATA}/{split}/fake_raw/*.wav'))]
    rng = np.random.default_rng(1 if split == 'train' else 2)
    clean, noise, tel = [], [], []
    for f, y in items:
        raw = read(f)
        x = crop(raw)
        n = crop(noisy(raw, rng))
        name = os.path.basename(f)
        clean.append((norm(x), y, name))
        noise.append((norm(n), y, name))
        tel.append((norm(crop(phone(n))), y, name))
    return clean, noise, tel


w2v = Wav2Vec2Model.from_pretrained('facebook/wav2vec2-base').eval()
NL = w2v.config.num_hidden_layers + 1


@torch.no_grad()
def feats(batch):
    out = []
    for x, _, _ in batch:
        hs = w2v(torch.from_numpy(x)[None], output_hidden_states=True).hidden_states  # NL x [1,T,768]
        out.append(torch.stack([torch.cat([h[0].mean(0), h[0].std(0)]) for h in hs]).numpy())
    return np.stack(out)  # [N, NL, 1536]


print('loading audio + noisy + phone-quality copies ...', flush=True)
tr_clean, tr_noise, tr_tel = load_split('train')
te_clean, te_noise, te_tel = load_split('test')
print(f'train {len(tr_clean)} x3  test {len(te_clean)} x3', flush=True)
SETS = [('trc', tr_clean), ('trn', tr_noise), ('trt', tr_tel), ('tec', te_clean), ('ten', te_noise), ('tet', te_tel)]
F = {k: feats(v) for k, v in SETS}
Y = {k: np.array([y for _, y, _ in v]) for k, v in SETS}
names_te = [n for _, _, n in te_clean]


def fit_lr(X, y, l2=1.0, steps=600):
    """Logistic regression with standardisation, in torch (no sklearn dependency)."""
    mu, sd = X.mean(0), X.std(0) + 1e-6
    Xt = torch.tensor((X - mu) / sd, dtype=torch.float32); yt = torch.tensor(y, dtype=torch.float32)
    w = torch.zeros(X.shape[1], requires_grad=True); b = torch.zeros(1, requires_grad=True)
    opt = torch.optim.LBFGS([w, b], max_iter=steps, line_search_fn='strong_wolfe')
    def closure():
        opt.zero_grad()
        loss = torch.nn.functional.binary_cross_entropy_with_logits(Xt @ w + b, yt) + l2 * (w ** 2).sum() / len(y)
        loss.backward(); return loss
    opt.step(closure)
    return mu, sd, w.detach().numpy(), b.detach().item()


def predict(model, X):
    mu, sd, w, b = model
    return 1 / (1 + np.exp(-(((X - mu) / sd) @ w + b)))


def auc(p, y):
    pos, neg = p[y == 1], p[y == 0]
    return float(((pos[:, None] > neg[None]).mean() + 0.5 * (pos[:, None] == neg[None]).mean()))


# Pick layer + regularisation by speaker-disjoint cross-validation on TRAIN only (test stays untouched).
Xtr_all = np.concatenate([F['trc'], F['trn'], F['trt']]); ytr = np.concatenate([Y['trc'], Y['trn'], Y['trt']])
def group_of(name):
    # real "ls_0012.wav" / fake "kokoro_af_bella_ls_0012.wav" -> same group; 8 consecutive rows ~ one speaker/recording
    src, idx = name[:-4].split('_')[-2:]
    return f'{src}{int(idx) // 8}'
groups = np.array([group_of(n) for _, _, n in tr_clean] * 3)
uniq = sorted(set(groups)); random.shuffle(uniq); folds = [set(uniq[i::4]) for i in range(4)]
best = None
for layer in range(1, NL):
    for l2 in (0.3, 3.0, 30.0):
        scores = []
        for f in folds:
            m = np.array([g in f for g in groups])
            mdl = fit_lr(Xtr_all[~m, layer], ytr[~m], l2)
            scores.append(auc(predict(mdl, Xtr_all[m, layer]), ytr[m]))
        s = float(np.mean(scores))
        if best is None or s > best[0]: best = (s, layer, l2)
print(f'chosen layer {best[1]} l2 {best[2]} (train CV AUC {best[0]:.3f})', flush=True)
_, LAYER, L2 = best
model = fit_lr(Xtr_all[:, LAYER], ytr, L2)

# ---- held-out evaluation ----
report = {'layer': LAYER, 'l2': L2, 'cv_auc': best[0], 'sets': {}}
for tag, key in (('clean', 'tec'), ('noise floor + trimmed', 'ten'), ('phone', 'tet')):
    p = predict(model, F[key][:, LAYER]); y = Y[key]
    groups_te = {'real (all)': y == 0,
                 'real LibriSpeech-other': np.array([n.startswith('lso') for n in names_te]),
                 "real People's Speech": np.array([n.startswith('ps') for n in names_te]),
                 'AI Kokoro (unseen voices)': np.array([n.startswith('kokoro') for n in names_te]),
                 'AI MMS (unseen engine)': np.array([n.startswith('mms') for n in names_te])}
    report['sets'][tag] = {'auc': auc(p, y)}
    print(f'\n[{tag}] AUC {auc(p, y):.3f}')
    for name, m in groups_te.items():
        if m.sum() == 0: continue
        for th in (0.4, 0.5, 0.8):
            frac = float((p[m] >= th).mean())
            report['sets'][tag][f'{name} >= {th}'] = frac
        print(f'  {name:28} n={m.sum():3}  flagged AI@0.5 {np.mean(p[m] >= .5):5.0%}  @0.8 {np.mean(p[m] >= .8):5.0%}  median {np.median(p[m]):.2f}')
json.dump(report, open(f'{OUT}/report.json', 'w'), indent=1)


# ---- export: wav2vec2 (truncated at LAYER) + pooling + linear head ----
class Detector(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.w2v = w2v
        self.w2v.encoder.layers = self.w2v.encoder.layers[:LAYER]  # later layers are unused
        mu, sd, w, b = model
        self.register_buffer('mu', torch.tensor(mu, dtype=torch.float32))
        self.register_buffer('sd', torch.tensor(sd, dtype=torch.float32))
        self.register_buffer('w', torch.tensor(w, dtype=torch.float32))
        self.register_buffer('b', torch.tensor(b, dtype=torch.float32))  # scalar, so logits come out [1, 2]

    def forward(self, input_values):
        h = self.w2v(input_values, output_hidden_states=True).hidden_states[LAYER][0]
        f = torch.cat([h.mean(0), h.std(0)])
        z = ((f - self.mu) / self.sd) @ self.w + self.b
        return torch.stack([-z / 2, z / 2])[None]           # softmax(...)[1] == sigmoid(z)


det = Detector().eval()
x0 = torch.from_numpy(te_clean[0][0])[None]
with torch.no_grad():
    ref = torch.softmax(det(x0), -1)[0, 1].item()
fp32 = f'{OUT}/voice_detector_fp32.onnx'
torch.onnx.export(det, (x0,), fp32, input_names=['input_values'], output_names=['logits'],
                  dynamic_axes={'input_values': {1: 'samples'}}, opset_version=17, dynamo=False)
# int8 quantization shifts this model's scores badly (it uses layer-1 features), see quantize_eval.py.
# The app ships this fp32 graph as voice_detector.onnx.
import onnxruntime as ort
s = ort.InferenceSession(fp32, providers=['CPUExecutionProvider'])
l = s.run(None, {'input_values': x0.numpy()})[0][0]
print(f'\nexported fp32 {os.path.getsize(fp32) / 1e6:.0f} MB; torch {ref:.4f} vs onnx {1 / (1 + np.exp(l[0] - l[1])):.4f}')
