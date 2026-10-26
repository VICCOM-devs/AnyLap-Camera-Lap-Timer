"""PNG frames -> raw YUV444 (no noise; the Kotlin side adds per-frame sensor noise).
File: int32 LE w, h, n, then per frame Y,U,V planes. Sidecar .json with crossing info."""
import sys, struct, glob, os, json
import numpy as np
from PIL import Image
B = '/home/claude/blend'; OUT = f'{B}/clips'; os.makedirs(OUT, exist_ok=True)
def yuv(path):
    a = np.asarray(Image.open(path).convert('RGB')).astype(np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    y = 0.257 * r + 0.504 * g + 0.098 * b + 16
    u = -0.148 * r - 0.291 * g + 0.439 * b + 128
    v = 0.439 * r - 0.368 * g - 0.071 * b + 128
    return [np.clip(np.rint(p), 0, 255).astype(np.uint8) for p in (y, u, v)]
def write(name, files, meta):
    fr = [yuv(p) for p in files]
    h, w = fr[0][0].shape
    with open(f'{OUT}/{name}.yuv', 'wb') as fh:
        fh.write(struct.pack('<iii', w, h, len(fr)))
        for f in fr:
            for p in f: fh.write(p.tobytes())
    json.dump(meta, open(f'{OUT}/{name}.json', 'w', encoding='utf-8'), ensure_ascii=False)
    print(name, len(fr))
what = sys.argv[1]
if what == 'base':
    write('empty', [f'{B}/race/0001.png'], {})
    write('race_victor', sorted(glob.glob(f'{B}/vrace/*.png')), {})
    write('race_mine', sorted(glob.glob(f'{B}/race/*.png')), {})
else:  # cal or pass dirs
    for d in sorted(glob.glob(f'{B}/{what}/*')):
        t = json.load(open(f'{d}/truth.json'))
        files = sorted(glob.glob(f'{d}/*.png'))
        first = int(os.path.basename(files[0])[:4])
        t['crossIdx'] = t['crossFrame'] - first   # fractional index of the crossing within this file
        t['car'] = t['car'].replace(' ', '_')
        write(f'{what}_{os.path.basename(d)}', files, t)
