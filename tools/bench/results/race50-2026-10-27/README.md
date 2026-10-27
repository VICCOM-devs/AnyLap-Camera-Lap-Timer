# 10 cars · 50 laps · simulated race (Oct 27 2026)

Engine at PR #12 commit e011c3a (`SPLIT_WEAK` 0.75), overhead camera 1.8 m above the line, 60 fps, 1/120 s, 480×270.

| Seed | Crossings | Found | Missed | Phantom | Wrong car | Unknown | Timing (mean / max) |
|---|---|---|---|---|---|---|---|
| 1 | 510 | 508 | 2 | 3 | 1 | 1 | 1.2 / 10 ms |
| 2 | 510 | 510 | 0 | 2 | 0 | 1 | 1.2 / 11 ms |

- `race50_seed1.html`, `race50_seed2.html`: the app-style result pages (open in a browser; crops embedded). Seed 1 is also the claude.ai artifact *AnyLap · 10 cars · 50 laps · simulated race*.
- `race50_<seed>.json`: raw output of `Race50.kt` – cars, pace, spins, engine laps, true laps, review items.
- Renders (73 pass folders, ~2000 PNG frames) and the Blender scenes (`AnyLapBlender.blend`, `AnyLapBlender2.blend`, `AnyLap_cars10.blend` with the ten cars built in) are on the GitHub release **bench-cars10-2026-10-27**.

Reproduce: unpack the renders into `blend/form/cars10`, `python3 -I tools/bench/pack.py cars10`, compile `ClipBench.kt FormBench.kt Race50.kt` against the engine, run `clip.Race50Kt <seed> 51`, then `python3 tools/bench/build_race50.py <seed>`.
Note: the page's "caught by lap sanity" count is applied by `build_race50.py` (a lap under 60 % of the car's median is not counted) – not yet in `RaceEngine`.
