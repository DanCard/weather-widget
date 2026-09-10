# Use one five-minute Tomorrow.io product for temperature actuals

**Date:** 2026-09-10
**Status:** implemented and runtime-verified; full staggered test gate passes
**Scope:** Tomorrow.io temperature actuals on Android and desktop
**Supersedes:** the Tomorrow.io product-selection rules in
`plans/260910-tomorrow-temperature-timeline-unification.md`

## Goal

Build Tomorrow.io's displayed actual-temperature series from one consistent product: native
five-minute intervals from the Timelines API. Do not mix realtime responses, hourly elapsed
intervals, or locally rounded timestamps into that series.

Tomorrow.io forecast fetching does not change:

```text
Forecast: existing combined Timelines request -> 1h + 1d
Actuals:  separate elapsed-only Timelines request -> 5m
```

Both paths remain in `:shared`; Android and desktop only schedule, persist, and render the shared
results.

## Confirmed evidence

Direct API responses for `37.416824,-122.08898` showed that mixing Tomorrow.io products creates a
contradictory timeline:

| Product | Timestamp | Temperature |
|---|---:|---:|
| Realtime captured at the time | 10:24 | 74.60 F |
| Later 1-minute Timeline | 10:24 | 78.07 F |
| 5-minute Timeline | 10:20 | 78.16 F |
| 5-minute Timeline | 10:25 | 78.05 F |
| Hourly Timeline | 10:00 | 77.07 F |

The API accepted `1m`, `5m`, and `15m`; it rejected `10m`. Higher resolution did not reconcile
realtime with Timeline history. The defect is therefore product mixing, not insufficient graph
resolution.

## Required behavior

1. Preserve the existing `1h + 1d` forecast request and forecast storage behavior.
2. Add a separate Timeline request with `timesteps=5m` for elapsed values only. Anchor its end to
   the latest five-minute UTC boundary so overlapping calls revise stable exact keys.
3. Preserve each API-provided five-minute timestamp exactly. Do not round realtime or forecast
   timestamps into five-minute buckets.
4. Persist five-minute values with a distinct provenance id such as
   `TOMORROW_IO_5M_HISTORY`; do not label them realtime or physical-station observations.
5. Use only that provenance id for Tomorrow.io temperature actuals, current-temperature
   resolution, daily extrema, and the solid actual graph line.
6. Stop calling `/v4/weather/realtime` from Android and desktop refresh paths.
7. Stop converting elapsed `1h` forecast intervals into Tomorrow.io observation rows.
8. Upsert five-minute values by their exact timestamp. A later fetch of the same timestamp replaces
   the earlier value, making Tomorrow.io's latest revision canonical.
9. Use the newest completed five-minute interval for the displayed current temperature and its
   timestamp. Do not substitute the forecast value merely because it is newer on the clock.
10. Keep the source label honest: `Tmrw 5-minute history`, not a station observation claim.

## Fetch windows and failure behavior

1. A full forecast refresh keeps the existing combined `1h + 1d` request and, in parallel, fetches
   the previous 23 hours of five-minute history through `now`.
2. An observations-only refresh replaces the realtime call with a small overlapping five-minute
   window, initially the previous hour through `now`. The overlap is intentional: exact-timestamp
   upserts capture provider revisions and recover a missed poll without duplicates.
3. The full window is about 277 five-minute intervals; a one-hour incremental window is about 13.
   Do not download the full day on every current-temperature poll.
4. If the five-minute call fails, retain the last successful five-minute series and report it as
   stale. Do not fall back to realtime or reclassify hourly forecasts as actuals.
5. Log one sparse summary per fetch containing the requested window, returned row count, earliest
   and latest timestamps, and replacement count. Never log the API key or every interval.

Live verification found that literal `endTime=now` anchors Tomorrow.io's intervals to the request
minute (`:09/:04/...` for a request at 12:09), so calls made in different minutes do not overlap on
exact keys. The request window is therefore anchored to the latest five-minute UTC boundary. This
does not round response records: every returned `startTime` is still persisted unchanged.

## Implementation

1. In shared `TomorrowIoApi`, add a five-minute elapsed-history method and parser. Reuse the normal
   Timeline error handling and map the returned values to `ObservationReading` with native
   timestamps and five-minute provenance.
2. Retain `getForecast()` and its combined `1h + 1d` request unchanged. Remove `getRealtime()` only
   after all Android and desktop call sites have moved to the five-minute method.
3. Narrow `TomorrowIoActuals` and `ObservationSourceMatcher` so only five-minute history enters the
   canonical Tomorrow.io actual-temperature timeline. Remove realtime/history product-merging and
   all temperature use of `CloudHourBucket`.
4. In Android `HourlyForecastStore`, prevent Tomorrow.io's elapsed hourly forecast rows from being
   passed through `HistoricalActualsBackfill`. Save the separate five-minute result instead.
5. In Android `CurrentTempRepository`, replace `fetchTomorrowIoCurrent()`'s realtime request with
   the incremental five-minute request and resolve its newest completed row.
6. In desktop `DesktopWeatherService`, replace both full-refresh realtime composition and
   observations-only realtime fetching with the same shared five-minute method. Keep repository
   and renderer code provider-neutral.
7. Keep `ActualTemperatureSeriesBuilder`, `HourDataAssembler`, Android rendering, and desktop
   rendering on the shared normalized rows. Platform renderers must receive the same ordered points
   for the same fixture.

## Retiring existing inconsistent rows

Existing `TOMORROW_IO_REALTIME` and hourly `TOMORROW_IO_RECENT_HISTORY` rows must not remain visible
after rollout:

1. Exclude both legacy ids immediately from current-temperature and graph selection.
2. After the first successful five-minute backfill for a site, delete those two legacy product ids
   for that exact site on both Android and desktop. Never delete them before replacement coverage
   exists.
3. Recompute that site's Tomorrow.io daily extrema from the five-minute rows so cached highs/lows do
   not preserve discarded values.
4. Record one sparse cleanup event with site, replacement coverage, and deleted row counts.
5. Use targeted DAO operations; no database schema migration or broad provider/database wipe is
   required.

For the observed incident this removes the realtime `10:24 = 74.60 F` row after five-minute
coverage is stored. The canonical graph uses `10:20 = 78.16 F` and `10:25 = 78.05 F`; it does not
invent a rounded 10:24 replacement.

## Tests

1. Shared API tests verify separate request shapes: forecast remains exactly `1h + 1d`; actuals use
   `5m`, an elapsed-only end time, imperial units, and native returned timestamps.
2. Parser tests retain all five-minute intervals, reject malformed/non-finite temperatures, and
   select the latest completed interval for the current value.
3. Upsert tests verify that a later fetch replaces the same five-minute timestamp while distinct
   timestamps remain distinct.
4. Shared series tests prove realtime and hourly-history ids cannot enter Tomorrow.io actuals and
   no local rounding occurs.
5. Android tests cover full refresh, observations-only refresh, guarded legacy cleanup, daily
   extrema recomputation, stale-cache behavior, and header/graph consistency.
6. Desktop tests cover the equivalent full and incremental fetch paths, guarded cleanup, current
   value, and graph inputs.
7. A shared parity fixture must produce the same ordered actual points on Android and desktop.

Every new test class receives exactly one duration category.

## Verification

1. Run focused shared, Android, and desktop tests while iterating.
2. Run `./scripts/staggered-tests.sh --install` as the full gate.
3. Build the desktop distributable and restart the running desktop app so verification uses the new
   binary.
4. On Pixel `2A191FDH300PPW`, prove device identity, trigger a Tomorrow.io refresh, and capture:
   database rows, sparse fetch/cleanup logs, and a screenshot.
5. Capture the equivalent desktop database rows, logs, and screenshot for the same location.
6. Confirm the Pixel and desktop show the same five-minute points and that no realtime or elapsed
   hourly row participates in the actual line.
7. Report production, test, and documentation lines added/deleted separately at handoff.

## Acceptance criteria

1. Forecast network behavior remains the existing combined `1h + 1d` request.
2. Tomorrow.io actual-temperature rows occur only on API-provided five-minute timestamps.
3. The current value comes from the latest completed five-minute interval.
4. A repeated fetch revises an existing exact timestamp rather than creating a duplicate.
5. The `10:24 = 74.60 F` realtime row and all other legacy realtime/hourly-history rows are absent
   from the displayed series after successful replacement coverage.
6. Android and desktop use the same shared fetch/parser/selection policy and produce identical
   points from identical input.
7. Focused tests, the full suite, desktop runtime verification, and Pixel runtime verification pass.

## Verification results

Completed on 2026-09-10:

1. Shared, desktop, and Android Short/Medium JVM suites passed together in 43 seconds.
2. The emulator suite passed: 95 total, 93 passed, and 2 skipped in 30 seconds.
3. The desktop distributable built successfully.
4. Pixel 7 Pro stored 13 aligned five-minute rows for the incremental window and rendered the
   solid Tomorrow.io actual line with an 87.7 F current label.
5. The emulator stored 277 aligned five-minute rows for the full 23-hour window, retired 260
   legacy/off-grid rows only after coverage existed, and rendered the solid actual line.
6. Desktop stored 277 aligned five-minute rows for the full 23-hour window and rendered the same
   87.7 F current label and actual-line shape.
7. A follow-up fixed the pre-existing completion race in
   `WeatherWidgetProviderDayTapSourceGapRoboTest`: the detached receiver job could suspend on
   Room's real executor after `advanceUntilIdle()` returned. The test now waits for the terminal
   day-click breadcrumb. The full staggered gate passed 4,092 unit tests plus 95 emulator tests
   (93 passed, 2 skipped), then installed the APK on both connected Android targets.

## Out of scope

- Changing Tomorrow.io forecast resolution or forecast graph interpolation.
- Claiming five-minute Timeline values are independently measured station observations.
- Reconciling Tomorrow.io values with NWS, Open-Meteo, Silurian, or personal stations.
- Changing actuals policies for any provider other than Tomorrow.io.
- Committing or pushing without an explicit request.
