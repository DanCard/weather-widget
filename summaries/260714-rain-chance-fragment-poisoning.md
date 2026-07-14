# Frozen rain chance poisoned by GPS-jitter fragments

**Date:** 2026-07-14

## Symptom

Samsung showed yesterday's rain chance as **9%** on the daily bar while its own hourly rain-chance
graph — drawn from the same database — showed **5%**. Emulator and desktop both showed 5%.

## Root cause

`ForecastRepository.snapshotDisplayedRainChance` (and its desktop twin) computed the day/night window
max from **raw `LocationMatch` proximity-box hourly rows**. GPS jitter had split the site into several
coordinate fragments, all inside the ~7mi box:

| location | hours | 8am–8pm max |
|---|---|---|
| 37.422, −122.087 | 12 | 13 |
| 37.424, −122.088 | 11 | **9** ← what got frozen |
| 37.39, −122.081 | 12 | 6 |
| **37.417, −122.089** (real site) | 12 | **4** |

The reducer is `max`, the most fragile possible choice over a poisoned set: one bad fragment wins
outright. Every *display* path already collapses the box to one site via `HourlyForecastSelector` /
`DailyForecastSelector`; the freeze path did not. Hence one device disagreeing with itself — the graph
selected a site, the archive did not.

The emulator escaped only by luck: its stale fragments are in Austin (30.267, −97.743), far outside
the box, so they were filtered and the real site survived.

Secondary finding: the freeze's `FREEZE_RAIN_CHANCE` log fired **once** on the Samsung (00:58,
`resolvedDay=9`) and never again — because the poisoned value never changed, the "did it move?" gate
kept quiet. A stuck value looks identical to a stable one in the logs.

## Changes

1. **`:shared` `DailyRainLabels.resolveLiveDayNightChanceAtSite(...)`** — runs `HourlyForecastSelector`
   (site + freshest-per-hour) before the window max, preserving the GENERIC_GAP fallback via the new
   `selectSiteHourly()`. Both freeze call sites (Android + desktop) now use it.
2. **`:shared` `FrozenRainChanceRepair`** — re-derives already-frozen columns from
   `hourly_forecast_history`, which is append-only and `fetchedAt`-stamped. Only snapshots fetched
   BEFORE the window closed (day 8pm / night next-8am) are read, so it reproduces what the site's rows
   said while the day was live rather than hindcasting.
3. **`ForecastRepository.repairFrozenRainChanceIfNeeded`** — one-time pass (pref
   `rain_chance_site_repair_done_v1`), wired into the worker's non-uiOnly branch. Unlike the existing
   backfill it rewrites rows that already hold a value — that's the point. Conservative: a null
   re-derivation (history aged past its 30-day retention) leaves the archived value alone; never turns
   a populated archive into nulls. Logs `RAIN_CHANCE_REPAIR`.
4. New DAO `getHistoryInRangeAllSnapshots` + `HourlyForecastHistoryEntity.toHourlyForecast()`.

Desktop gets the freeze fix (it has the identical raw read and would break the moment its coordinates
jittered) but no repair runner — its archived values are correct.

## Verification

- `DailyRainChanceSiteSelectionTest` (:shared, 5 tests) uses the device's real coordinates/values:
  asserts raw box max = 13 (the bug), site-resolved = 4 (the fix), freeze == graph, freshest-wins
  still holds, and that the repair ignores post-window snapshots.
- `ForecastRepositoryRepairRainChanceTest` (Robolectric, 3 tests) — repairs 9 → 4; leaves the value
  alone when history can't re-derive; runs once.
- Proven to fail: removing the site selection from `FrozenRainChanceRepair` makes the repair test fail
  `expected:<4> but was:<13>`, confirming the fragments really do reach it through the box.
- Full `:shared` and `:app` unit suites green; desktop compiles.
- **User verified the fix on the Samsung.**
