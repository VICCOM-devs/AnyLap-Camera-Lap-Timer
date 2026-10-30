# AnyLap recognition model – `tools/ml/`

A neural network that tells RC cars apart from a crop at the finish line – any car, any ground, any angle and scale,
as long as the camera gets some light. Trained on millions of **composed** pictures (a sprite library of random cars
rendered in Blender + a fast compositor) plus the **real crops** the app shares, and shipped to phones through the
`anylap_models` table without an app update.

```
sprites/render_sprites.py   Blender: random cars (minis10 generator) x 132 camera angles, car + shadow on film
synth/compose.py            compositor: random ground, scale 12–150 px, rotation, shadow, stripe, neighbours, blur, noise, JPEG
train/model.py              CarNet (~0.6 M params): 64-d signature + "one / two / no car" + nose position; ArcFace head for training
train/train.py              trainer (AMP, OneCycle); 10 % of cars held out = "cars nobody has seen"
train/evaluate.py           the app's job as a test: calibrate from a few views, name crossings by nearest signature
train/real_crops.py         download + use the app's shared crops (extra identities, real-world eval)
export/export.py            ONNX + LiteRT (.tflite), sha256 + metadata for anylap_models
```

## Setup on the PC (Windows, RTX 3060 Ti)

1. **Blender 4.2+** (installed). Its own Python needs Pillow for the liveries – in a terminal:
   `"C:\Program Files\Blender Foundation\Blender 4.5\4.5\python\bin\python.exe" -m pip install pillow`
   (adjust the version in the path; numpy is bundled). In Blender: Edit → Preferences → System → Cycles Render Devices → OptiX.
2. **Python 3.11–3.13** with CUDA PyTorch:
   ```
   py -m venv D:\anylap-ml\venv
   D:\anylap-ml\venv\Scripts\pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
   D:\anylap-ml\venv\Scripts\pip install numpy pillow onnx ai-edge-torch
   ```
   (`ai-edge-torch` is only for the `.tflite`; skip it if it fails to install, ONNX still exports.)
3. Clone the repo (this folder is enough) – e.g. `D:\AnyLap`.

## Run

**1. Sprite library (one evening, GPU).** 300 cars × 132 views × 2 renders ≈ 5–6 h on the 3060 Ti at 24 samples.
```
blender -b -P D:\AnyLap\tools\ml\sprites\render_sprites.py -- --out D:\anylap-ml\sprites --cars 300 --seed 0
```
Resumable (finished cars are skipped); `--first 300 --cars 300 --seed 0` adds another 300 later. `--quick` renders a tiny
set for a smoke test. Check a few `D:\anylap-ml\sprites\car0000\*_car.png` in any viewer.

**2. Grounds (optional, recommended).** Drop photos of real floors into `D:\anylap-ml\grounds\` – the track carpet,
asphalt, concrete, dirt, your kitchen floor, phone snaps are fine. The compositor mixes them 50/50 with procedural grounds.

**3. Train (hours).** ~500 composed samples/s from 6 workers on the i5; the GPU idles – that's fine.
```
D:\anylap-ml\venv\Scripts\python D:\AnyLap\tools\ml\train\train.py --sprites D:\anylap-ml\sprites --photos D:\anylap-ml\grounds --out D:\anylap-ml\runs\v1 --steps 60000 --workers 6
```
Every 2000 steps it prints `EVAL {...}`: `accuracy` = naming held-out cars from 6 calibration views (the number that matters),
broken down by elevation, car size in px, blur and neighbours; `count_accuracy`; `nose_err_px_median`. `best.pt` = best of those.
Look-alike stress: `python tools\ml\train\evaluate.py runs\v1\best.pt sprites --lookalike`.

**4. Real crops (once testers have raced).** Needs the Supabase service key (Project Settings → API) – set it in the terminal, never in a file:
```
set SUPABASE_URL=https://ecdkjmvfjusznvggzvfs.supabase.co
set SUPABASE_SERVICE_KEY=...
python tools\ml\train\real_crops.py download D:\anylap-ml\real
python tools\ml\train\train.py ... --real D:\anylap-ml\real --resume runs\v1\best.pt --out runs\v2
```
The real eval accuracy (`"real": {...}` in EVAL) is the one to watch from then on.

**5. Export + publish.**
```
python tools\ml\export\export.py runs\v2\best.pt --out D:\anylap-ml\export\v2 --version 2 --int8 --sprites D:\anylap-ml\sprites
```
Upload `carnet.tflite` to a public place (GitHub release of the repo is fine), then one row in `anylap_models`
(name `signature`, platform `android`, version, url, sha256, bytes, min_app_build) – the app downloads it on launch.

## Design notes
- **Signature, not classifier.** The net maps a crop to 64 numbers; a race compares crossings with the calibrated cars' signatures.
  Nothing is trained per user, and a car it has never seen works the same way – that's what held-out evaluation measures.
- **Angles and scale are nuisance variables**, not inputs: the hemisphere of camera views and the 12–150 px scale range are
  mixed into every batch, so the signature learns to ignore them (and the eval buckets show where it doesn't).
- **Identical cars stay identical.** Two cars with the same body and livery give the same signature; the tape strip and the
  lap rhythm (`LapSolver` twins) remain the answer there.
- **Real data wins.** Synthetic gets the model to "good"; the crops from real races (hard cases first – every Check card is one)
  take it to "right on this track". Keep the real eval set honest: never train on it.
- Input is 64 × 64 from the **full-resolution** frame around the car (the app will cut it from the ≤ 1280 px stream like it does
  for the bibs), not from the 480 px analysis frame.
