"""Quantize the exported voice detector to int8 and compare both against the held-out test set.

Result: int8 shifts the scores badly (this model uses layer-1 features), so the app ships fp32.

usage: python quantize_eval.py DATA OUT_DIR
Writes OUT_DIR/voice_detector_int8.onnx and OUT_DIR/report_int8.json.
"""
import glob, json, os, subprocess, sys, tempfile
import numpy as np, soundfile as sf, onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

DATA, OUT = sys.argv[1], sys.argv[2]
SR, WIN = 16000, 16000 * 4
fp32, int8 = f'{OUT}/voice_detector_fp32.onnx', f'{OUT}/voice_detector_int8.onnx'

# The final 1536->1 head can't be quantized per-channel (1-D weight); keep it in float, it's tiny.
quantize_dynamic(fp32, int8, weight_type=QuantType.QInt8, per_channel=True, nodes_to_exclude=['/MatMul'])


def read(path):
    x, sr = sf.read(path, dtype='float32', always_2d=True)
    x = x.mean(1)
    if sr != SR: x = np.interp(np.arange(0, len(x), sr / SR), np.arange(len(x)), x).astype(np.float32)
    return x

def phone(x):
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

def norm(x):  # same as VoiceDetector.kt
    return ((x - x.mean()) / np.sqrt(x.var() + 1e-7)).astype(np.float32)

def auc(p, y):
    pos, neg = p[y == 1], p[y == 0]
    return float((pos[:, None] > neg[None]).mean() + 0.5 * (pos[:, None] == neg[None]).mean())

sessions = {k: ort.InferenceSession(f, providers=['CPUExecutionProvider']) for k, f in (('fp32', fp32), ('int8', int8))}
def score(sess, x):
    l = sess.run(None, {'input_values': x[None]})[0][0]
    return 1 / (1 + np.exp(l[0] - l[1]))

files = [(f, 0) for f in sorted(glob.glob(f'{DATA}/test/real/*.wav'))] + [(f, 1) for f in sorted(glob.glob(f'{DATA}/test/fake_raw/*.wav'))]
report = {'size_mb': round(os.path.getsize(int8) / 1e6, 1), 'sets': {}}
for cond in ('clean', 'phone'):
    xs = [norm(crop(read(f))) if cond == 'clean' else norm(crop(phone(crop(read(f))))) for f, _ in files]
    y = np.array([lab for _, lab in files]); names = [os.path.basename(f) for f, _ in files]
    p = {k: np.array([score(s, x) for x in xs]) for k, s in sessions.items()}
    print(f'[{cond}] AUC fp32 {auc(p["fp32"], y):.3f}  int8 {auc(p["int8"], y):.3f}  max |diff| {np.abs(p["fp32"] - p["int8"]).max():.3f}')
    r = {'auc_fp32': auc(p['fp32'], y), 'auc_int8': auc(p['int8'], y)}
    for label, m in (('real', y == 0), ('AI Kokoro unseen voices', np.array([n.startswith('kokoro') for n in names])),
                     ('AI MMS unseen engine', np.array([n.startswith('mms') for n in names]))):
        for k in ('fp32', 'int8'):
            print(f'   {k} {label:26} n={m.sum():3}  >=0.4 {np.mean(p[k][m] >= .4):5.0%}  >=0.5 {np.mean(p[k][m] >= .5):5.0%}  >=0.8 {np.mean(p[k][m] >= .8):5.0%}')
            r[f'{k}: {label} >= 0.4/0.5/0.8'] = [float(np.mean(p[k][m] >= t)) for t in (.4, .5, .8)]
    report['sets'][cond] = r
json.dump(report, open(f'{OUT}/report_int8.json', 'w'), indent=1)
print('int8 size', report['size_mb'], 'MB')
