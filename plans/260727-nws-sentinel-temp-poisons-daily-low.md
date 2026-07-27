# NWS `-100°F` Sentinel Poisons Tomorrow's Daily Low

## Evidence

1. Reported as a Samsung display bug ("-100 for tomorrow"). It is not device-specific: the
   identical row is present on all three devices, which is what a data-layer defect looks like.

   | Device | targetDate | source | high | low |
   |---|---|---|---|---|
   | Pixel 7 Pro `2A191FDH300PPW` | 2026-07-28 | NWS | 78.0 | **-100.0** |
   | Samsung SM-F936U1 `RFCT71FR9NT` | 2026-07-28 | NWS | 78.0 | **-100.0** |
   | Emulator `emulator-5554` | 2026-07-28 | NWS | 78.0 | **-100.0** |

2. Every other source for 2026-07-28 is sane: `OPEN_METEO 82/54`, `TOMORROW_IO 81/54`.
   NWS's own earlier fetches that day were sane too (`80/58` at 11:22). The bad value first
   appears in the 11:52:56 fetch — 1 poisoned response out of 75 `NWS_PERIOD_SUMMARY` rows.

3. There is no `-100` literal anywhere in the source. The value originates upstream. NWS is
   serving it live at time of writing, on **both** relevant endpoints:

   ```
   /gridpoints/MTR/93,87/forecast
     Tonight  2026-07-27T18:00 .. 2026-07-28T06:00   temp=-100F

   /gridpoints/MTR/93,87   (minTemperature, wmoUnit:degC)
     2026-07-28T03:00:00+00:00/PT14H   -73.33333333333333
   ```

   `-73.3333°C` converts to **exactly** `-100.0°F`. That exactness is the tell: `-100°F` is an
   internal NWS missing-data sentinel leaking through the public API in both representations.

4. The attribution logic is correct; only the input is bad. `NwsDailyMapper.applyForecastPeriods`
   (`NwsDailyMapper.kt:65`) files a nighttime low under `extractNwsForecastDate(period.endTime)`.
   "Tonight" ends `2026-07-28T06:00`, so the low legitimately belongs to tomorrow. It faithfully
   filed `-100` there.

5. **The gridpoint path is what actually wrote the value**, not the text forecast. In
   `NwsForecastMapper.kt`, `mergeGridpointTemperatures` runs at line 107, *before*
   `applyForecastPeriods` at line 123, and both only ever fill nulls — so the grid value lands
   first and wins. The `NWS_GRID_TEMP_PRIMARY` tag names this precedence. Confirmed in the log:

   ```
   NWS_GRID_TEMP_PRIMARY  dates=8  2026-07-27:h=77.0/l=56.0,2026-07-28:h=78.0/l=-100.0,...
   ```

   The text forecast's matching `-100` was never consulted. Both paths are nonetheless poisoned,
   so filtering only one lets the other fill the gap with identical garbage.

6. One bad value corrupts the entire display, not one cell — this is the "messed up" symptom:

   ```
   DailyGraphRenderer: renderGraph: days=7, minTemp=-100.0, maxTemp=84.0, widthPx=517, heightPx=435
   TempUtils: formatTemp: in=-100.0 useCelsius=false out=-100°
   ```

   The y-axis stretched to -100, squashing all seven days into a sliver.

7. **The hourly forecast is clean** (user observation, verified). Zero implausible values across
   all 156 periods of the live hourly response, and zero across the entire `hourly_forecasts`
   table for every source. NWS's own hourly data puts tonight's low at **59°F**, consistent with
   its earlier daily fetches (58°) and with the other sources (54–57°).

   The clean data is **already cached on-device** — the Pixel's `hourly_forecasts` rows for the
   Tonight window match the live API exactly (`74,70,67,64,62,61,61,60,59,59,59,59`), so recovery
   needs no additional network call.

8. Window convention matters. Deriving over the *originating* window is what keeps the repair
   faithful. In the event the grid rejection won, and its window (`03:00Z/PT14H` = 20:00 local
   through 10:00 next morning) yields **58°** — the 06:00 sunrise minimum, which sits outside the
   narrower 18:00→06:00 "Tonight" span that gives 59°. 58° is demonstrably right: it is exactly
   what NWS itself reported for that slot in every uncorrupted fetch that day (09:50, 11:03,
   11:22). Cross-check: the calendar-day hourly *max* is exactly 78°, matching the trusted high.

9. A missing low is a well-supported state — `DailyViewLogic` guards on `lowTemp != null` at
   lines 116, 341, 379, and `formatTemp` accepts null. Rejecting a bad value is safe.

## Goal

Never display an upstream sentinel as a real temperature, and recover the true value from NWS's
own clean hourly data rather than degrading to a blank, a cross-source borrow, or a synthetic
climate normal.

## Plan

1. Add a shared plausibility guard (single range constant in `:shared`, so Android and desktop
   both inherit it) and apply it at both poisoned entry points: the gridpoint extremes parse and
   `NwsDailyMapper.applyForecastPeriods`. Rejected values leave the slot null instead of writing
   garbage; the period's condition and precip data are retained.

2. Add `fillTemperatureGapsFromHourly`, following the existing `initPrecipFromHourly` /
   `initConditionsFromHourly` idiom (`NwsForecastMapper.kt:105-106`). Run it *after* the grid and
   period merges so it only fills slots still null — sane daily data always wins, and hourly is
   strictly a repair path. Derive over the originating window's `[start, end)` span rather than the
   calendar day, so the repair matches whichever NWS window was rejected. `hourlyPeriods` is
   already in scope, so this needs no new plumbing or network call.

3. Log a permanent `NWS_TEMP_REJECTED` breadcrumb with the raw value, the source path
   (grid vs. period), and the validTime, so the next occurrence is one query away.

4. Add focused `:shared` JVM tests for:
   - gridpoint `-73.33°C` rejected;
   - forecast period `-100°F` rejected;
   - hourly fallback recovering `59°` over the Tonight window;
   - sane values passing through untouched;
   - boundary values at the range edges.

5. Verify on device: rebuild, force a refresh, and confirm from logs and a screenshot that
   tomorrow's low reads 59° and that `renderGraph` reports a sane `minTemp` instead of -100.

## Second defect, found during verification: stored rows still reached the renderer

With ingest fixed and a clean `58°` row written, the desktop still drew Tuesday's low at the
bottom of the panel — correct *label*, off-screen *geometry*. Cause (`DesktopWeatherDao.kt:856`):

```kotlin
if (latestBatch != 0L && rs.getLong("batchFetchedAt") == latestBatch) continue
```

That query builds the previous-forecast snapshot overlay and therefore **skips the newest batch by
design**, so it kept reading exactly the three surviving `-100` rows. Label and geometry disagreed
because they came from different rows. Android was immune: `ForecastDao` always orders
`batchFetchedAt DESC` or selects `MAX(batchFetchedAt)`, never excluding the newest batch.

Fix: `Float?.orNullIfImplausibleTempF()` in `:shared`, applied to all three historical read sites in
`DesktopWeatherDao` (snapshot overlay, `getForecastsInRangeBySource`, `getForecastEvolution`). A
sentinel reaching a renderer is worse than a gap — it drags geometry and axis scaling off-screen
while the label beside it shows a healthy number.

This makes the ingest filter the fix for *new* data and the read guard the fix for *already-stored*
data; both are needed because rows persist for the 1-month retention window.

## Still deferred

- **Purge the stored `-100` rows.** No longer needed for display correctness (the read guard
  neutralises them), but they remain in the 1-month history and would distort forecast-accuracy
  comparisons. Destructive write to real data — left for an explicit decision.
- **Render-side y-axis guard** so an outlier from any source, not just NWS, cannot collapse a
  graph. The read guard covers the known path; this would be the general backstop.
