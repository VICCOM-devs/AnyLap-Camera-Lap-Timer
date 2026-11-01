# Real-video bench

The app's vision engine and end-of-race audit on **real race footage**, scored against a crossing list
checked by eye. A change to the engine must keep every clip here at its `expect` level.

| Clip | What it is | Line | Truth | Today (zoom to the line) | Whole frame (before Nov 2026) |
|---|---|---|---|---|---|
| `mini-z-cup-2022` | YouTube, Kyosho Mini-Z Cup 2022, 8 Mini-Z cars, fixed high camera over the whole track, 1280×720, 30 fps, 8 min race | bottom straight (Victor, Nov 1 2026) | 384 crossings, 42–51 per car | **384/384 found, 0 extra, 99.4 % named live, 100 % after the audit, every lap count exact – PASS** | 328/384 found, 73 % named live, 80 % after the audit – FAIL |

## Run

```
tools/bench/real/run.sh mini-z-cup-2022 /path/to/KYOSHO-Mini-Z-Cup-SnapYT_App.mp4            # zoom to the line (the app)
tools/bench/real/run.sh mini-z-cup-2022 /path/to/KYOSHO-Mini-Z-Cup-SnapYT_App.mp4 --whole-frame
```

Needs `ffmpeg`, a JDK and `kotlinc` 2.2.x (`KOTLINC=/path/to/kotlinc`). ~3 min per run (two passes over the
clip). Prints found / extra / named / after-audit / laps per car and the misses, ends with `PASS` or
`FAIL` (exit code 1). `--out dir` writes every engine event as CSV. **Videos are not in git** (size,
copyright): ask Victor for the file, or download the clip again.

## How a clip is scored (`RealBench.kt`)

Frames are fed the way the Android camera feeds them: YUV 4:2:0, the region the analyzer asks for
(`FrameAnalyzer.regionFor` → `Roi.forLine`) sampled nearest-pixel to 480 px wide.

1. **Calibration** – each car's first *N* lone passes after `calibration.after` (nobody else at the line
   within `aloneWithin` s), captured like the wizard does: fingerprint, top-down picture, size hint. Same
   camera and light as the race, as in the app. These passes are not scored.
2. **Race** – the clip with the calibrated cars, appearance gate on.
3. **Audit** – `LapSolver` over every logged crossing (gated, rescues included), twins by look, from
   `start` (lights out) as in race mode – what `RaceEngine.audit()` does when a race is saved.

Pairing engine ↔ truth: ≤ 0.2 s apart, cost = time + place along the line. Timing is compared with
the truth tracker's own sub-frame time – both are centroid-interpolated at the clip's frame rate, so it
is a consistency check, not absolute timing.

## Adding a clip

Good clips: a camera that doesn't move, a whole race (or long practice), the line visible end to end,
1080p and 60 fps if possible, close racing (abreast, nose to tail) – this first clip has only 2
arrivals within 0.1 s of each other.

1. Pick the line in video pixels (x1,y1,x2,y2 – same direction as in the app).
2. `python3 -I make_truth.py video.mp4 --from <s> --to <s> --line x1,y1,x2,y2 --out work/` – an
   independent band tracker at full resolution finds every crossing and draws numbered tiles on
   `work/sheet_NN.png`, grouped by colour.
3. Look at every sheet; write `index,car` into `work/labels.csv` for each tile whose colour group is
   wrong (or `drop` for a hand / marshal). `--finish` prints each car's lap rhythm: a lap ~2× the usual
   means a missing crossing (check the raw video there – a crash, or a car the tracker lost), a very
   short one a duplicate or a wrong label. Repeat until the rhythm is explained.
4. Write `clips/<name>.json` (copy `mini-z-cup-2022.json`: size, fps, from/to/scoreUntil, start = lights
   out if it is a race, line in 0..1, calibration rule, expect, crossings) and run it.
