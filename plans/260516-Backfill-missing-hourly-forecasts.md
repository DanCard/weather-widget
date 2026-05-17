# Backfill missing hourly forecasts on past-day graph open

## Context

When the user opens **yesterday's hourly temperature graph**, the rendered curve and peak label disagree across devices:
- **Emulator** shows peak 71.4° (wrong)
- **Samsung Fold** and **Pixel 7 Pro** show peak ≈ 70° (correct)

Root cause from Phase 1 investigation: the emulator's `hourly_forecasts` rows for 2026-05-16 were last fetched at **2026-05-15 22:22 PDT** — *before* the day started. Samsung/Pixel re-fetched throughout 2026-05-16 and have current data. The emulator's stored rows span ~5pm-prior-day → 4pm-yesterday-PDT, missing the **5pm-11pm PDT slice** of yesterday entirely.

Comparison (peaks across the 24 stored rows per source for the 2026-05-16 UTC window):

| Source | Emulator | Samsung | Pixel | Latest Fetch (emu) |
|--------|----------|---------|-------|--------------------|
| NWS | 73.0 | 73.0 | 73.0 | **2026-05-15 22:22** |
| OPEN_METEO | 75.1 | 74.3 | 73.8 | **2026-05-15 22:22** |
| TOMORROW_IO | 74.9 | 73.0 | 74.8 | **2026-05-15 22:22** |
| SILURIAN | 69.9 | 70.3 | 70.3 | **2026-05-15 22:22** |

None of the per-source peaks equal 71.4° exactly — meaning 71.4 is an interpolated/smoothed artifact off the stale + incomplete dataset. Cross-source blending was ruled out in `computeSmoothedForecasts`: `resolveForecastsByTime` picks **one** source per hour and smoothing preserves extrema *within that source*. The fix isn't to chase the artifact — it's to make sure the data is complete.

**Intended outcome:** when the user opens an hourly graph for a past day whose stored forecast coverage is incomplete for the visible window, the widget detects the gap and triggers a fetch that can backfill recent past hours. After fetch completes, the graph re-renders with the corrected data, converging with what the always-on devices show.

## Approach

**Detect coverage gap → enqueue a forecast fetch → refresh the widget.**

Two layers, in order:

1. **Gap detection** in the hourly-temp graph render path. After loading `hourly_forecasts` for the requested PDT day window, count how many of the 24 hourly slots are populated for the user's `displaySource`. If coverage is below a threshold (e.g., `< 20 of 24` slots, or any 3+ contiguous missing), call it a gap.

2. **Conditional fetch.** When a gap is detected, enqueue a `WeatherWidgetWorker` one-shot with `KEY_FORCE_REFRESH=true` and a new input flag (`KEY_BACKFILL_PAST_DAYS=N`) so the worker requests `past_days=N` on Open-Meteo/Tomorrow.io paths. NWS is exempted (its API can't return past-day forecasts). Log `HOURLY_COVERAGE_GAP` to `app_logs` with `(widget, date, source, missingHourCount, contiguousGap)`. The graph keeps showing the current (stale) data until the fetch completes; when the worker finishes, it triggers the normal widget update path which re-renders.

Diagnostic logging added alongside the fix (the user asked for this explicitly):
- `HOURLY_COVERAGE_GAP date=YYYY-MM-DD source=X stored=Y/24 missingHours=H1,H2,...` on graph open when a gap is detected.
- `HOURLY_GRAPH_PEAK widget=W source=X displayedMin=A displayedMax=B rawSourceRows=N smoothed=true|false` at the point peak is rendered, so future "graph shows wrong number" reports include both the raw and rendered values.
- `HOURLY_PAST_FETCH_ENQUEUED source=X pastDays=N reason=coverage_gap` whenever the fetch is triggered, with backoff to prevent flapping (one fetch per source per past-date per 30 min).

## Files to modify

### Production code

- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
  - In `loadGraphHours` (line 288-352), after `buildHourDataResult` runs, compute the per-source hourly coverage for the **visible window** in `PDT local time`. The window already exists as `startHour..endHour` (lines 140-141 of `TemperatureHourDataBuilder`); pass it back as part of `BuildHourDataResult` or recompute it locally.
  - If coverage gap is detected AND the target day is in the past (`startHour.toLocalDate().isBefore(LocalDate.now())`), call a new `HourlyCoverageBackfiller.enqueueIfNeeded(context, displaySource, dateRange)`.
  - Log `HOURLY_COVERAGE_GAP` + `HOURLY_GRAPH_PEAK` to `app_logs` via `database.appLogDao().log(...)` (already injected in this file — see lines 358-368).

- `app/src/main/java/com/weatherwidget/widget/handlers/HourlyCoverageBackfiller.kt` (new file, single object)
  - `enqueueIfNeeded(context, source, startMs, endMs)`: dedup via a `SharedPreferences` recency map keyed on `(source, dateKey)`; skip if last enqueue within 30 minutes. On miss, enqueue a `WeatherWidgetWorker` one-shot with `KEY_FORCE_REFRESH=true` and a new `KEY_BACKFILL_PAST_DAYS_FOR_SOURCE` extra (string `"OPEN_METEO:2"` / `"TOMORROW_IO:2"`).
  - Skip silently for NWS (can't fetch historical forecasts).

- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
  - Add a new input data key `KEY_BACKFILL_PAST_DAYS_FOR_SOURCE: String?` parsed at the start of `doWork`. When present, pass `pastDays` through to the per-source fetch path (currently inside `weatherRepository.getWeatherData` — needs a similar parameter forwarded down to the API client classes).
  - Skip the normal data-staleness gate (`shouldTriggerNetworkFetchAfterRefresh`) when this backfill request is present — explicit gap-detection bypass.

- `app/src/main/java/com/weatherwidget/data/remote/OpenMeteoApi.kt` (and `TomorrowIoApi.kt` if it has an equivalent)
  - Plumb a `pastDays: Int = 0` parameter through to the URL builder. For Open-Meteo, append `&past_days=N` to the existing `/forecast` request. The endpoint returns the same JSON shape extended backward, so the existing parser handles it unchanged.
  - NWS path: untouched.

### Tests

- `app/src/test/java/com/weatherwidget/widget/handlers/HourlyCoverageBackfillerTest.kt` (new)
  - `coverage gap below threshold enqueues fetch` (with mocked WorkManager)
  - `recent enqueue is deduped within 30 min`
  - `NWS source is skipped`
  - `today's date is skipped (no past-day backfill for current day)`
- `app/src/test/java/com/weatherwidget/data/remote/OpenMeteoApiTest.kt` extension:
  - `forecast URL includes past_days when pastDays > 0`

## Verification

1. **Unit tests:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.HourlyCoverageBackfillerTest" --tests "com.weatherwidget.data.remote.OpenMeteoApiTest"` green.
2. **Reproduce on emulator:** confirm current DB shows yesterday's `hourly_forecasts` for OPEN_METEO covering only ~17 of 24 PDT hours. Pull DB via `python3 scripts/backup_databases.py` and check `SELECT COUNT(*) FROM hourly_forecasts WHERE dateTime BETWEEN <yesterday_local_midnight> AND <today_local_midnight> AND source='OPEN_METEO'`.
3. **Install and tap yesterday on the daily widget:** opens the temperature hourly graph. Verify `HOURLY_COVERAGE_GAP` + `HOURLY_PAST_FETCH_ENQUEUED` rows appear in `app_logs`. Wait for worker tick (or force-refresh), then verify the same query now returns 24 rows.
4. **Re-tap yesterday:** peak label on the emulator should now match the physical-device values (~70° for NWS/SILURIAN, depending on the widget's `displaySource`).
5. **Backoff sanity:** re-open the same graph again immediately. Verify NO new `HOURLY_PAST_FETCH_ENQUEUED` row appears in `app_logs` for the next 30 minutes (dedup working).

## Out of scope

- **NWS past-forecast backfill.** NWS has no historical forecast endpoint accessible by gridpoint; we can't retroactively rebuild NWS hourly forecasts after the fact. If the emulator's NWS data is stale-and-gappy, it will remain so. This affects only stale-emulator scenarios on devices not actively running yesterday — physical devices that ran throughout the day are unaffected.
- **The specific 71.4° artifact.** Not separately investigated. Smoothing within a single source preserves extrema; 71.4 is likely an interpolated curve value at the visible peak, off a sparse/stale dataset. Once data is backfilled it will resolve naturally.
- **Source-toggle divergence across devices.** Each widget on each device may have a different `displaySource` from independent `WidgetStateManager` state. If the user wants consistent source across devices, that's a different feature (cross-device state sync). Out of scope.
- **Previous-conversation work (already shipped):**
  - The daily-extreme blending fix (time-aligned IDW) for the 73.5/73.1 discrepancy.
  - The DailyClickHandlerFactory change rerouting history taps to the temperature hourly graph.
  - The DB-persisted `DAILY_EXTREME_BLEND` audit log.
