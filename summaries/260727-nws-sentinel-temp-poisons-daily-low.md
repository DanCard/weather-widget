# NWS `-100°F` sentinel poisons tomorrow's daily low

**Date:** 2026-07-27
**Plan:** `plans/260727-nws-sentinel-temp-poisons-daily-low.md`

## Outcome

Reported as a Samsung display bug ("-100 for tomorrow"). It was never Samsung-specific — all three
Android devices held the identical row, and the desktop did too.

NWS is serving a `-100°F` missing-data sentinel. There is no `-100` literal anywhere in the source;
the value is upstream. It arrived on **both** paths that feed the daily low at once:

- `/forecast` period: `"Tonight" temperature: -100`
- `/gridpoints` minTemperature: `-73.33333333333333` degC — exactly -100.0°F

That exactness identified it as a sentinel rather than weather. All four surfaces now render
correctly against the still-poisoned live feed.

## What changed

Two distinct defects, both required.

**1. No plausibility gate at ingest.** `NwsTemperaturePlausibility` (`-80f..140f`) in `:shared`,
applied to both poisoned paths — the gridpoint extremes parse and
`NwsDailyMapper.applyForecastPeriods`. Because both merges "fill nulls only", filtering one path
alone would let the other supply identical garbage. Rejected slots are left null, and the period's
condition and precip data are retained.

Recovery comes from NWS's *own* hourly series, which was completely clean (0 implausible values
across 156 periods) and already cached on-device — so no extra network call, no cross-source mixing
that would corrupt accuracy tracking, and no synthetic climate-normal value.
`fillTemperatureGapsFromHourly` follows the existing `initPrecipFromHourly` /
`initConditionsFromHourly` idiom and runs after the grid and period merges, so sane daily data
always wins and hourly is strictly a repair path.

**2. Stored rows still reached the renderer.** This is why the desktop stayed broken *after* the
data was correct. `DesktopWeatherDao.kt:856` builds the previous-forecast snapshot overlay by
deliberately skipping the newest batch, so it kept reading the three surviving `-100` rows.
Diagnostic signature: label and geometry disagree — the number read `58°` from the fresh row while
the bar and icon were drawn off-screen from the stale one. Fixed with a read-side guard,
`Float?.orNullIfImplausibleTempF()`, applied to all three historical read sites in
`DesktopWeatherDao`. Android was immune: `ForecastDao` always orders `batchFetchedAt DESC` or
selects `MAX(batchFetchedAt)`, never excluding the newest batch.

Ingest filters protect new rows; the read guard protects against rows already stored. Both are
needed, because rows persist for the 1-month retention window.

Desktop parity: `buildDailyForecasts` gained an optional `hourlyPeriods` argument so the desktop
gets the same repair as Android. Permanent breadcrumb tag `NWS_TEMP_REJECTED` records raw value,
origin path, and window.

## Verification

Live end-to-end, with NWS still serving the sentinel at the time of test:

| Surface | Result |
|---|---|
| Pixel 7 Pro `2A191FDH300PPW` | 78/58 |
| Samsung SM-F936U1 `RFCT71FR9NT` | 78/58 |
| Emulator `emulator-5554` | 78/58 |
| Desktop | 78/58, graph rescaled — confirmed by screenshot |

Both paths were caught on the live run, exactly as the analysis predicted:

```
count=2 GRID:min date=2026-07-28 low=-100.0; FCST:Tonight date=2026-07-28 low=-100.0
repaired=1 GRID:min date=2026-07-28 low=-100.0 -> 58.0 (14h)
```

## Regression coverage

1. New `NwsSentinelTemperatureTest` in `:shared` (9 tests) covers: gate rejects the sentinel, NaN
   and infinity, and boundary inclusivity; period sentinel rejected without becoming the low;
   rejected period keeps condition and precip; hourly repair over the originating window; repair
   never overwrites a sane value from another path; sane forecasts wholly unaffected; gridpoint
   sentinel dropped then repaired; desktop entry point renders the repaired day; missing hourly
   leaves the slot null rather than fabricating a value; and the read guard on stored values.
2. The suite was proven able to fail — widening `MIN_PLAUSIBLE_F` to `-200f` broke 5 of the tests.
3. Full suites green: **552 shared, 1614 app, 0 failures**.

## Notes

- The repair yields **58°**, not the 59° first predicted. The grid rejection's 14-hour window
  catches the 06:00 sunrise minimum that the narrower 18:00→06:00 "Tonight" span misses. 58° is
  demonstrably correct — it is exactly what NWS itself reported for that slot in every uncorrupted
  fetch that day (09:50, 11:03, 11:22).
- One bad value corrupted the whole widget rather than its own cell: the daily graph scales its
  y-axis to the data range, so a single -100 collapsed all seven days into a sliver
  (`renderGraph: days=7, minTemp=-100.0, maxTemp=84.0`).
- Samsung and desktop appeared "still broken" mid-verification only because their staleness gates
  had not yet permitted a fresh fetch (`isDataStale=false`, `NWS:20m/60m:fresh`).

## Deferred

- **Purging the three stored `-100` rows.** No longer needed for display correctness — the read
  guard neutralises them — but they remain in the 1-month history and would skew forecast-accuracy
  comparisons. A destructive write to real data, so left for an explicit decision.
- **A general render-side y-axis guard**, so an outlier from any source cannot collapse a graph.
  The read guard covers the known path only.
