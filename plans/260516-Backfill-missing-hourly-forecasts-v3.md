# Backfill missing hourly forecasts on graph open

## Context

When the user opens **yesterday's hourly temperature graph**, the rendered curve and peak label disagree across devices:
- **Emulator** shows peak 71.4° (wrong)
- **Samsung Fold** and **Pixel 7 Pro** show peak ≈ 70° (correct)

Root cause from Phase 1 investigation: the emulator's `hourly_forecasts` rows for 2026-05-16 were last fetched at **2026-05-15 22:22 PDT** — *before* the day started. Samsung/Pixel re-fetched throughout 2026-05-16 and have current data. The emulator's stored rows span ~5pm-prior-day → 4pm-yesterday-PDT, missing the **5pm-11pm PDT slice** of yesterday entirely.

Stale-emulator was the trigger for this report, but the underlying gap can manifest on any device whenever a fetch is missed (battery saver, crash, doze) — for **today** or **the future** just as easily as for the past. The fix should react to *missing data*, not to any specific day classification.

Comparison (peaks across the 24 stored rows per source for the 2026-05-16 UTC window):

| Source | Emulator | Samsung | Pixel | Latest Fetch (emu) |
|--------|----------|---------|-------|--------------------|
| NWS | 73.0 | 73.0 | 73.0 | **2026-05-15 22:22** |
| OPEN_METEO | 75.1 | 74.3 | 73.8 | **2026-05-15 22:22** |
| TOMORROW_IO | 74.9 | 73.0 | 74.8 | **2026-05-15 22:22** |
| SILURIAN | 69.9 | 70.3 | 70.3 | **2026-05-15 22:22** |

None of the per-source peaks equal 71.4° exactly — meaning 71.4 is an interpolated/smoothed artifact off the stale + incomplete dataset. Cross-source blending was ruled out in `computeSmoothedForecasts`: `resolveForecastsByTime` picks **one** source per hour and smoothing preserves extrema *within that source*. The fix isn't to chase the artifact — it's to make sure the data is complete.

**Intended outcome:** when the user opens an hourly graph for **any** day (past, today, or future) whose stored forecast coverage is incomplete for the visible window, the widget detects the gap and triggers a fetch. After fetch completes, the graph re-renders with the corrected data, converging with what the always-on devices show.

## Approach

**Detect coverage gap → enqueue a forecast fetch → refresh the widget.**

Two layers, in order:

1. **Gap detection** in the hourly-temp graph render path. After loading `hourly_forecasts` for the requested PDT day window, count how many of the 24 hourly slots are populated for the user's `displaySource`. If coverage is below a threshold (e.g., `< 20 of 24` slots, or any 3+ contiguous missing), call it a gap.

2. **Conditional fetch — uniform across sources.** When a gap is detected, enqueue a `WeatherWidgetWorker` one-shot with `KEY_FORCE_REFRESH=true` for **every** source that has a gap, not just non-NWS ones. The router treats all four sources symmetrically; each source's adapter does whatever its API supports:
   - **Open-Meteo:** appends `past_days=N` if the gap spans past-day hours, otherwise uses the standard window.
   - **Tomorrow.io:** equivalent past-window parameter.
   - **NWS:** uses its standard gridpoint hourly forecast endpoint. For *future* and *current-day* gaps NWS recovers normally. For *past-day* gaps NWS may not be able to retrieve historical forecasts — that's a data-source limitation, not a routing decision. The fetch is still attempted; if nothing comes back, the gap remains for NWS specifically.
   - **Silurian:** standard fetch.

   Each source-date pair gets its own 30-minute dedup window (one fetch attempt per source per target-date per 30 min) so an irrecoverable past-day NWS gap doesn't cause repeated useless attempts on every graph open.

Log `HOURLY_COVERAGE_GAP` to `app_logs` with `(widget, date, source, missingHourCount, contiguousGap)`. The graph keeps showing the current (stale) data until the fetch completes; when the worker finishes, it triggers the normal widget update path which re-renders.

Diagnostic logging added alongside the fix (the user asked for this explicitly):
- `HOURLY_COVERAGE_GAP date=YYYY-MM-DD source=X stored=Y/24 missingHours=H1,H2,...` on graph open when a gap is detected.
- `HOURLY_GRAPH_PEAK widget=W source=X displayedMin=A displayedMax=B rawSourceRows=N smoothed=true|false` at the point peak is rendered, so future "graph shows wrong number" reports include both the raw and rendered values.
- `HOURLY_PAST_FETCH_ENQUEUED source=X pastDays=N reason=coverage_gap` whenever the fetch is triggered, with backoff to prevent flapping (one fetch per source per past-date per 30 min).

## Files to modify

### Production code

- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`
  - In `loadGraphHours` (line 288-352), after `buildHourDataResult` runs, compute the per-source hourly coverage for the **visible window** in `PDT local time`. The window already exists as `startHour..endHour` (lines 140-141 of `TemperatureHourDataBuilder`); pass it back as part of `BuildHourDataResult` or recompute it locally.
  - If coverage gap is detected for the user's `displaySource`, call `HourlyCoverageBackfiller.enqueueIfNeeded(context, displaySource, dateRange)` — **no past/future gate**.
  - Log `HOURLY_COVERAGE_GAP` + `HOURLY_GRAPH_PEAK` to `app_logs` via `database.appLogDao().log(...)` (already injected in this file — see lines 358-368).

- `app/src/main/java/com/weatherwidget/widget/handlers/HourlyCoverageBackfiller.kt` (new file, single object)
  - `enqueueIfNeeded(context, source, startMs, endMs)`: dedup via a `SharedPreferences` recency map keyed on `(source, dateKey)`; skip if last enqueue within 30 minutes. On miss, enqueue a `WeatherWidgetWorker` one-shot with `KEY_FORCE_REFRESH=true` and a `KEY_BACKFILL_FOR_SOURCE` extra string `"<SOURCE_ID>:<dateRangeKey>"`.
  - **No source filtering** — every source goes through the same dedup'd enqueue path. NWS is included. If a source's API can't satisfy the request for the given date range (e.g., NWS for a past day), the fetch attempt is a no-op for that source but the dedup window still ticks, preventing repeat attempts within 30 min.

- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
  - Add a new input data key `KEY_BACKFILL_FOR_SOURCE: String?` (format: `"<SOURCE_ID>:<startMs>:<endMs>"`) parsed at the start of `doWork`. When present, plumb the source + date range through to `weatherRepository.getWeatherData(targetSourceId, ...)` — that call already supports `targetSourceId`. Add a sibling `backfillRange: ClosedRange<Long>?` parameter so per-source adapters know how far back to fetch.
  - Skip the normal data-staleness gate (`shouldTriggerNetworkFetchAfterRefresh`) when this backfill request is present — explicit gap-detection bypass.

- `app/src/main/java/com/weatherwidget/data/remote/OpenMeteoApi.kt` (and `TomorrowIoApi.kt`)
  - Plumb a `backfillRange: ClosedRange<Long>?` parameter through to the URL builder. For Open-Meteo, compute `past_days = ceil((now - backfillRange.start) / 24h)` and append `&past_days=N` to the existing `/forecast` request. The endpoint returns the same JSON shape extended backward, so the existing parser handles it unchanged.
  - Tomorrow.io: equivalent past-window parameter if available; otherwise pass through and rely on its default window.

- `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt`
  - Accept `backfillRange` for signature uniformity. The NWS gridpoint hourly forecast endpoint doesn't support a past-window parameter; for past dates the fetch returns nothing new but doesn't error. For today/future requests it behaves as normal. Document this gracefully — no log spam on empty past-day results.

### Tests

- `app/src/test/java/com/weatherwidget/widget/handlers/HourlyCoverageBackfillerTest.kt` (new)
  - `coverage gap below threshold enqueues fetch` (with mocked WorkManager)
  - `recent enqueue is deduped within 30 min — even across NWS/non-NWS sources`
  - `NWS source still enqueues a fetch (uniform routing)`
  - `today and future dates enqueue fetches just like past dates`
- `app/src/test/java/com/weatherwidget/data/remote/OpenMeteoApiTest.kt` extension:
  - `forecast URL includes past_days when backfillRange extends into the past`

## Verification

1. **Unit tests:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.HourlyCoverageBackfillerTest" --tests "com.weatherwidget.data.remote.OpenMeteoApiTest"` green.
2. **Reproduce on emulator:** confirm current DB shows yesterday's `hourly_forecasts` for OPEN_METEO covering only ~17 of 24 PDT hours. Pull DB via `python3 scripts/backup_databases.py` and check `SELECT COUNT(*) FROM hourly_forecasts WHERE dateTime BETWEEN <yesterday_local_midnight> AND <today_local_midnight> AND source='OPEN_METEO'`.
3. **Install and tap yesterday on the daily widget:** opens the temperature hourly graph. Verify `HOURLY_COVERAGE_GAP` + `HOURLY_PAST_FETCH_ENQUEUED` rows appear in `app_logs`. Wait for worker tick (or force-refresh), then verify the same query now returns 24 rows.
4. **Re-tap yesterday:** peak label on the emulator should now match the physical-device values (~70° for NWS/SILURIAN, depending on the widget's `displaySource`).
5. **Backoff sanity:** re-open the same graph again immediately. Verify NO new `HOURLY_PAST_FETCH_ENQUEUED` row appears in `app_logs` for the next 30 minutes (dedup working).

## Out of scope

- **NWS-specific past-forecast recovery strategy.** The NWS forecast endpoint doesn't expose past-issued forecasts by gridpoint. Within this plan, NWS still gets the same fetch trigger as every other source (uniform routing), but its API response for past dates is essentially a no-op. Building a more sophisticated NWS past-day backfill (e.g., synthesizing from observations, or scraping a different endpoint) is a separate effort.
- **The specific 71.4° artifact.** Not separately investigated. Smoothing within a single source preserves extrema; 71.4 is likely an interpolated curve value at the visible peak, off a sparse/stale dataset. Once data is backfilled it will resolve naturally.
- **Source-toggle divergence across devices.** Each widget on each device may have a different `displaySource` from independent `WidgetStateManager` state. If the user wants consistent source across devices, that's a different feature (cross-device state sync). Out of scope.
- **Previous-conversation work (already shipped):**
  - The daily-extreme blending fix (time-aligned IDW) for the 73.5/73.1 discrepancy.
  - The DailyClickHandlerFactory change rerouting history taps to the temperature hourly graph.
  - The DB-persisted `DAILY_EXTREME_BLEND` audit log.
