# Blender clip bench

Runs the real vision engine (commonMain `vision/`) on Blender renders with exact ground truth.
Dev tool, not part of the app build. Paths are hard-coded to a `/home/claude/blend` work dir.

1. `pip install bpy==4.5.*` (Python 3.11, run with `python -I` from outside the work dir).
2. `truth.py` – crossings (fractional frame, speed, lane) from the scene's animation.
   Cars are objects named `… bil`; the finish line is the cyan face (material 3) of `Bana`.
3. `render_calibration.py` – the race clip at 480×270 plus each car alone, slow (3 m/s) and fast
   (7 m/s), props frozen at frame 1. `render_passes.py` – each car alone at 5.5 and 8.5 m/s in
   lanes −0.3/−0.1/0.1/0.3 m (race passes for the lap compositor). Cycles CPU, ~4 s/frame.
4. `along.py` – lane → position along the line in the image (perspective), for the scorer.
5. `pack.py base|cal|pass` – PNG → raw YUV clips (`clips/*.yuv` + json).
6. Build the engine jar (`kotlinc vision/*.kt` + stub `Sport/Direction/LineSpec`) and
   `kotlinc ClipBench.kt -cp engine.jar`; run `clip.ClipBenchKt lap1` (the rendered clips) or
   `laps 1 12` (12 composited 3-lap races: passes laid on the empty track at random ~18 s lap
   times, with forced overtakes and dead heats; sensor noise added per frame).

Results Oct 26 2026 are in `claude/plan-and-decisions.md` (claude.ai project) and PR #9.
