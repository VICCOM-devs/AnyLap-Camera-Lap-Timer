# AnyLap bench data (not app code)

- `blend/AnyLapBlender.blend`, `blend/AnyLapBlender2.blend`: Victor's test scenes (Oct 26 2026).
- `blend/AnyLap_cars10.blend`: the first scene with the ten-car grid of the 50-lap simulation built in (`tools/bench/cars10.py`).
- `blend/cars10_meta.json`: finish line, mm/px and lane→along map of the overhead rig.

The renders (~300 MB) are regenerated with `tools/bench/render_cars10.py` (~2 h on 2 cores). Results: `tools/bench/results/race50-2026-10-27/` on `feature/split-v2`.
