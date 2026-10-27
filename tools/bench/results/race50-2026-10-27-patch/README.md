# 10 cars · 50 laps · after the Oct 27 patch (PR #13)

Same grid and schedule as `../race50-2026-10-27/`, engine with front-edge timing (Settings default) and laps counted
through `LapIdentity` (lap timing as a second opinion on names).

| Seed | Laps counted for the right car | Laps given to a car that didn't drive them | Not counted (tray / missed) | Timing, front of car (mean / p95) | Close pairs in the right order |
|---|---|---|---|---|---|
| 1 | 507 / 510 | **0** (was 1) | 1 tray + 2 missed | 0.9 / 2.3 ms | 14 / 15 |
| 2 | 510 / 510 | **0** | 0 | 0.9 / 2.1 ms | 23 / 24 |

Before the patch (centre timing, old lap rule): seed 1 had a 4.9 s "lap" for White 21 (a phantom piece of Blue 3);
lap timing now parks it. Formation bench (three rigs) unchanged.
