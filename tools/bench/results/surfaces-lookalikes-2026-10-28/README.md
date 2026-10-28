# Surfaces + look-alike cars · 28 Oct 2026

All 15 minis (the ten + five twins) rendered once as **car + shadow only** (Cycles, transparent film,
ground = shadow catcher; `render_minis10.py` with `LAYER=1`) and laid onto any rendered ground
(`pack.py layer <asphalt|dirt|carpet>`; grounds from `render_ground.py`: `dirt.png`, `carpet.png`).
Race10 picks the ground with `SURF=` and the grid with `GRID=`.

| Test | Races | Right | Wrong laps |
|---|---|---|---|
| Normal 10-car grid, dirt | 4 | 400/400 | 0 |
| Normal 10-car grid, carpet | 4 | 400/400 | 0 |
| Normal 10-car grid, asphalt (layer pipeline) | 3 | 300/300 | 0 |
| Look-alike grid, old lap solver | 9 | 66–88 % | 3–22 per race |
| **Look-alike grid, twin solver (groups ≥ 0.88 alike)** | 12 (4 per ground) | **1199/1200** | **0** |
| Look-alike grid, twins driving the same pace (+0.05 s) | 6 | 576/600 | 22 – one race with Red 7 ↔ Red 17 swapped for the whole race (flagged: "which is which?") |
| Same, Red 17 and Black 98 with a tape strip on the roof | 6 | 597/600 | 3 |

Look-alike grid: Red 7 / Red 17 (same body + paint, only the number; 99 % alike), Black 88 / Black 98 (96 %),
White 33 / White 8 (90–92 %), Blue 14 / Blue 41 (84–90 %), Silver 19 / Grey 91 (74–89 %).
A tape strip takes Red 7 / Red 17 to 82 % and Black 88 / 98 to 68 % (named by colour live: camera alone
60 % → 81–93 %).

`lookalikes_dirt.html` = the result page (dirt, seed 1, final code).
