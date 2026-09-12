# Hourly gap detector forces a fetch for hours no fetch can fill

Follow-up to `performance/260912-forced-sync-refetches-under-the-lock.md` ("Also seen on the
emulator").

## Symptom

Emulator, 2026-09-12 14:37, right after a location move to Santa Clara:

```
14:36:59.670 HOURLY_HISTORY_BACKFILL source=NWS site=37.373,-121.981 offered=7 covered=0 stored=7
14:37:06.563 TEMP_GAPS_REFRESH widget=2 source=NWS missing=1 noSelected=1 spans=[09-12 06:00..09-12 07:00] … requesting immediate API update
14:37:06.602 NET_FETCH_START force=true          ← 4 sources, 10.5 s
14:37:06.951 HOURLY_HISTORY_BACKFILL source=NWS … offered=7 covered=7 stored=0
```

The move's own fetch had just completed and `ElapsedForecastBackfill` had filed every elapsed hour
NWS's grid offers (7). The WIDE window looks back 9 h, so `06:00..07:00` stays empty — and the
detector forced a full re-fetch for it, which offered the same 7 hours and stored nothing. On the
15-minute cooldown this repeats for the rest of the day at every fresh site. The Pixel showed the
same pattern on 26091001 (`03:00..05:00` at 12:09, `04:00..05:00` at 12:33).

`plans/260911-backfill-elapsed-hour-forecast-history-on-fresh-site.md` fixed the *write* side
(elapsed hours are now filed when the payload carries them). This is the *trigger* side: a gap in
an hour older than the live-table boundary is history's job, and no live fetch can change it.

## Root cause

`TemperatureGraphHoursLoader` calls `summarizeMissingForecastHours(startHour = centre − backHours,
…)` and enqueues a forced sync whenever `missingCount > 0`. The summary counts every hour of the
visible window equally; it has no notion of "already elapsed".

## Fix

- `summarizeMissingForecastHours` takes `nowMs` and splits the missing hours: `elapsedCount` = hours
  before `nowMs − ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS` (the same boundary the live-table
  filter and the elapsed backfill use), `fillableCount = missingCount − elapsedCount`.
  `diagnosticText()` adds `elapsed=N`.
- The loader forces a refresh only when `fillableCount > 0`. When the only gaps are elapsed it logs
  `TEMP_GAPS_ELAPSED_ONLY` (INFO, same 15-min cooldown key family so it does not spam every paint)
  and enqueues nothing.
- `MissingForecastHours` keeps `missingCount` (all gaps) so the log still describes exactly what the
  graph is missing.

## Tests

- `MissingForecastHoursTest`: existing case gets `nowMs` far in the future (all fillable — same
  numbers as before, plus `elapsed=0`); new case with `now` inside the window asserts the split
  (`elapsed` = gaps before `now − 1 h`, `fillable` = the rest, boundary hour counted as fillable).
- `TemperatureGraphHoursLoader` trigger: covered by the pure split — the loader's condition is a
  one-line read of `fillableCount`.

## Verification

Emulator: move location (ConfigActivity → Use Coordinates), confirm the first paint logs
`TEMP_GAPS_ELAPSED_ONLY` for the pre-grid hours and **no** second `NET_FETCH_START force=true
reason=hourly_gaps` follows the move's fetch.

## Summary (done 2026-09-12)

- `MissingForecastHours` gains `elapsedCount` / `fillableCount`; `summarizeMissingForecastHours`
  takes `nowMs` and counts gaps before `now − ELAPSED_BOUNDARY_MS` as elapsed. `diagnosticText()`
  now reads `missing=N elapsed=E noSelected=… wrongSource=… spans=[…]`.
- `TemperatureGraphHoursLoader` forces `hourly_gaps` only when `fillableCount > 0`; otherwise logs
  `TEMP_GAPS_ELAPSED_ONLY` (INFO, own 15-min cooldown key `hourly_gaps_elapsed`) and enqueues
  nothing.
- Tests: `MissingForecastHoursTest` 3 cases (split with the boundary hour counted fillable; all-elapsed
  → `fillableCount = 0`). Handlers package sweep 78 classes / 597 tests / 0 failures.
- Emulator, same move recipe as before (14:59): `NET_FETCH_START` → `COMPLETE` →
  `TEMP_GAPS_ELAPSED_ONLY missing=1 elapsed=1 spans=[09-12 06:00..09-12 07:00]`, and no second
  fetch in the following 50 s. Previous build: forced 4-source re-fetch at +12 s.
- Emulator left at Santa Clara (37.3725, −121.9809), mode `fixed`, from these experiments.
