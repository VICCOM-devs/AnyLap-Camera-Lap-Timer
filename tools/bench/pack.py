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
def write(name, files, meta, full=None):
    """full: optional list of full-resolution PNGs (same count) → extra Y plane per frame; header gets fw, fh."""
    fr = [yuv(p) for p in files]
    h, w = fr[0][0].shape
    fl = [np.asarray(Image.open(p).convert('L')) for p in full] if full else None
    fw, fh_ = (fl[0].shape[1], fl[0].shape[0]) if fl else (0, 0)
    with open(f'{OUT}/{name}.yuv', 'wb') as fh:
        fh.write(struct.pack('<iii', w, h, len(fr)))
        if fl: fh.write(struct.pack('<ii', fw, fh_))
        for k, f in enumerate(fr):
            for p in f: fh.write(p.tobytes())
            if fl: fh.write(fl[k].tobytes())
    meta = dict(meta); meta['full'] = bool(fl)
    json.dump(meta, open(f'{OUT}/{name}.json', 'w', encoding='utf-8'), ensure_ascii=False)
    print(name, len(fr), 'full' if fl else '')
what = sys.argv[1]
if what == 'base':
    write('empty', [f'{B}/race/0001.png'], {})
    write('race_victor', sorted(glob.glob(f'{B}/vrace/*.png')), {})
    write('race_mine', sorted(glob.glob(f'{B}/race/*.png')), {})
elif what in ('form', 'formfull'):
    # formfull: frames from a high-res render dir (FORM_SRC), downscaled to 480 for the engine + full-res Y
    cam = sys.argv[2]
    src = os.environ.get('FORM_SRC', f'{B}/form') if what == 'formfull' else f'{B}/form'
    tag = 'full_' if what == 'formfull' else ''
    def prep(png):
        if what != 'formfull': return png
        small = png.replace('.png', '_480.png')
        if not os.path.exists(small): Image.open(png).convert('RGB').resize((480, 270), Image.BOX).save(small)
        return small
    write(f'form_{tag}{cam}_empty', [prep(f'{src}/{cam}/empty.png')], {}, full=[f'{src}/{cam}/empty.png'] if what == 'formfull' else None)
    for d in sorted(glob.glob(f'{src}/{cam}/*/')):
        d = d.rstrip('/'); name = os.path.basename(d)
        if not os.path.exists(f'{d}/truth.json'): continue
        t = json.load(open(f'{d}/truth.json'))
        files = sorted(f for f in glob.glob(f'{d}/*.png') if not f.endswith('_480.png'))
        first = int(os.path.basename(files[0])[:4])
        for c in t['cars']: c['crossIdx'] = c['crossFrame'] - first
        write(f'form_{tag}{cam}_{name}', [prep(f) for f in files], t, full=files if what == 'formfull' else None)
elif what == 'cars10':
    src = f'{B}/form/cars10'
    write('cars10_empty', [f'{src}/empty.png'], {})
    for d in sorted(glob.glob(f'{src}/*/')):
        d = d.rstrip('/')
        if not os.path.exists(f'{d}/truth.json'): continue
        t = json.load(open(f'{d}/truth.json'))
        files = sorted(glob.glob(f'{d}/*.png'))
        if not files: continue
        first = int(os.path.basename(files[0])[:4])
        t['crossIdx'] = t['crossFrame'] - first
        t['car'] = t['car'].replace(' ', '_')
        name = f"cars10_cal_{t['car']}_{t['speed']}" if t['kind'] == 'cal' else f"cars10_pass_{t['car']}_{t['lane']}_{t['speed']}"
        if os.path.exists(f'{OUT}/{name}.yuv') and os.path.getmtime(f'{OUT}/{name}.yuv') > os.path.getmtime(files[-1]): continue
        write(name, files, t)
elif what == 'minis10':
    src = f'{B}/form/minis10'
    write('minis_empty', [f'{src}/empty.png'], {})
    for d in sorted(glob.glob(f'{src}/*/')):
        d = d.rstrip('/')
        if not os.path.exists(f'{d}/truth.json'): continue
        t = json.load(open(f'{d}/truth.json'))
        files = sorted(glob.glob(f'{d}/*.png'))
        if not files: continue
        first = int(os.path.basename(files[0])[:4])
        t['crossIdx'] = t['noseFrame'] - first       # the nose touching the line is the timing point
        t['centreIdx'] = t['crossFrame'] - first
        t['tailIdx'] = t['tailFrame'] - first
        name = f"minis_{t['kind']}_{t['car']}_{t['lane']:+.2f}_{t['speed']}_{t['angle']:+.0f}"
        if os.path.exists(f'{OUT}/{name}.yuv') and os.path.getmtime(f'{OUT}/{name}.yuv') > os.path.getmtime(files[-1]): continue
        write(name, files, t)
else:  # cal or pass dirs
    for d in sorted(glob.glob(f'{B}/{what}/*')):
        t = json.load(open(f'{d}/truth.json'))
        files = sorted(glob.glob(f'{d}/*.png'))
        first = int(os.path.basename(files[0])[:4])
        t['crossIdx'] = t['crossFrame'] - first   # fractional index of the crossing within this file
        t['car'] = t['car'].replace(' ', '_')
        write(f'{what}_{os.path.basename(d)}', files, t)
