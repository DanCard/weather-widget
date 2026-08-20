# Desktop: latest-only observation loop — status summary (2026-08-20)

Implementation status for `plans/260820-desktop-observation-loop-latest-only.md`. The plan holds the
design and rationale; this file is the working record of what was built and measured.

## Trigger

User report: `~/misc/logs/sys-logging-2026-08-20.log` showed `weather-widget-` at the top of the
top-3 CPU list **3 samples in a row** (10:00:51 @ 4.1%, 10:01:20 @ 9.3%, 10:01:50 @ 5.4%), with the
**window process not running** — i.e. the headless daemon alone.

## Diagnosis (evidence-first)

Correlating the `top` samples against the daemon's autostart log
(`~/.local/state/weather-widget/autostart-20260819-203647.log`) showed the three samples map to a
burst of *redundant* network + parse + DB work:

| `top` sample | Daemon activity in that window |
|---|---|
| 10:00:51 | tail of the 10:01:11 forecast burst |
| 10:01:20 | 10:01:11 `Loop forecast refresh` (gridpoint + 5 stations × 500-row history) + 10:01:12 `Non-primary actuals` (3 sources) |
| 10:01:50 | 10:01:47 `Temp actuals loop refresh` — **the same 5 stations × 500-row history again** |

The smoking gun: the **10-minute** `Temp actuals` loop ran
`refreshObservations()` → `fetchObservationsOnly()` → `fetchNwsObservationsOnly()` →
`fetchObservationBundles(stations)`, which for each of up to 5 stations fetched
`nwsApi.getObservations(start, end)` over a **7-day** window (~500 rows/station ≈ 2 500 rows/cycle)
just to obtain the *latest* reading per station for the current-temperature IDW blend. The latest
reading alone is what that loop needs; the 7-day history is fetched identically by the hourly
forecast pull and written to the same `observations` table.

## Why history is safe to drop from this loop

History (the pink "actual" line, daily extremes, header delta) is read from the **DB**, not from
this fetch's return value, and is seeded/refreshed by three paths the 10-min loop doesn't touch:

1. `refresh()` → `fetchNwsForecast()` → `fetchObservationBundles(stations)` (launch `FULL_FORECAST`,
   hourly forecast loop, resume/network kick, location/source change).
2. `ensureHistory()` → `fetchObservationHistory()` (user pans the hourly graph past cached depth).
3. `fillNwsStationActualsIfNeeded()` (daily extremes with missing api actuals).

Missing-from-DB is handled by `determineLaunchRefreshAction` returning `FULL_FORECAST` when there is
no cache; stale degrades gracefully (holds last-known actuals) and self-heals within ≤60 min.

## Changes

| File | Change |
|---|---|
| `desktop/.../WeatherApiClient.kt` | `fetchObservationsOnly(latestOnly: Boolean = false)` + doc. |
| `desktop/.../DesktopWeatherService.kt` | `fetchObservationBundles(..., latestOnly)` skips the 7-day `getObservations` call when latest-only; latest fetched unconditionally (old `historical.isNotEmpty()` gate removed); new latest-only bundle branch keys touch/fail handling off `latestOutcome`. |
| `desktop/.../DesktopWeatherRepository.kt` | `refreshObservations()` passes `latestOnly = true`. |

## Tests

- **New `DesktopObservationLatestOnlyTest`** (2 tests): latest-only issues **zero** `getObservations`
  calls and returns exactly 2 rows (`KNUQ` latest + `NWS_BLEND`), full fetch still requests history.
- **New `DesktopObservationCpuTest`** (1 test): measures **process CPU time** (not wall time) of the
  `refreshObservations()` processing cycle against a ~1 000-row seeded DB. Measured **~66 ms CPU per
  iteration** against a 500 ms bound (~7.5× headroom). Process CPU time is immune to machine load,
  which is the whole point of the metric choice.
- Updated call sites for the new signature: `DesktopSynopticFallbackTest`, `RefreshDelayTest`,
  `DesktopRefreshObservationsTest`.

Full `:desktop:testByDurationDesktop` suite passes (271 tests, 0 failures).

### Note on the metric choice

Wall time vs CPU time came up during review. For this test, CPU time is correct: process CPU time
counts what the JVM actually burned, so a busy machine that slows the test does *not* inflate it
(wall time would). The `@Category` duration buckets are labels (partition the suite by measured wall
time), never thresholds — `validateDesktopTestCategories` only checks each file declares exactly one
bucket, so a slow-on-loaded-machine test cannot spuriously fail.

## Verification status — runtime, pending

Unit-level verified (compile + full test suite green). The **runtime** confirmation — the 10-min
`Temp actuals` cycle no longer emitting `historical observations: station=… count=500` lines, and the
recurring `weather-widget-` blips shrinking in `sys-logging` — requires rebuilding/restarting the
running daemon, which was **not** done this session (the live daemon, PID 1229789, is still the old
binary).

To confirm later, after redeploy:

```bash
grep -c 'historical observations' ~/.local/state/weather-widget/autostart-*.log   # expect no new 10-min lines
# watch ~/misc/logs/sys-logging-*.log for weather-widget- blips to drop from ~4–9% to ~1%
```

## Out of scope (follow-ups, not implemented)

1. De-colliding the hourly forecast / 30-min non-primary / 10-min observation loops (the "3 in a row"
   stacking symptom itself).
2. Relaxing `AC_OBSERVATION_MINUTES` 10 → 15 min.

## Not done

Nothing committed — no commit or push was requested.
