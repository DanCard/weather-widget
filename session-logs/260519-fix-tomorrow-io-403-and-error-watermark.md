# Tomorrow.io 403 fix + generalized error watermark

**Date:** 2026-05-19
**Branch:** main

## Problem (as reported)

> "Tomorrow API isn't working on any of the attached devices. On hourly temp graph, current observations are missing."

Two symptoms: (1) the Tomorrow.io source produced no usable data on any device, and
(2) the hourly temperature graph showed no observed/"current observations" line when
Tomorrow.io was the active source.

## Investigation

Devices attached: Pixel 7 Pro (`2A191FDH300PPW`), Samsung SM-F936U1 (`RFCT71FR9NT`),
`emulator-5554`.

Pulled live logcat from the emulator (per CLAUDE.md debugging workflow) rather than only
reading source. Key evidence:

```
W FETCH_TMRW_FAIL: source=TOMORROW_IO code=HTTP_403
  detail={"code":403003,"type":"Forbidden Action",
  "message":"...startTime cannot be more than 24 hours in the past."}

D TemperatureHourDataBuilder: buildHourDataList: source=TOMORROW_IO, sourceRows=0, ...
D TEMP_ACTUALS_PERF: ... source=TOMORROW_IO ... rawObs=0 ... emitted=0
I CURRENT_TEMP_DISPLAY: source=TOMORROW_IO anchorType=forecast observedTemp=none ...
```

Confirmed the API itself is healthy with a direct read-only `curl` to the timelines
endpoint using a `startTime` safely under 24h — it returned 121 intervals spanning 24h
of past data through 5 days of forecast. So the key/plan work; only the requested window
was out of policy.

## Root cause

`TomorrowIoApi.getForecast()` built its hourly `startTime` as:

```kotlin
OffsetDateTime.now().minusHours(72).truncatedTo(ChronoUnit.HOURS)   // committed code
```

The Tomorrow.io plan tied to this key **rejects any `startTime` more than 24h in the past**
(HTTP 403, code 403003). `minusHours(72)` always violates this, so *every* hourly fetch
403'd → no hourly rows stored → `ForecastRepository.saveHistoricalActuals()` had nothing
to backfill into `ObservationEntity` → the graph's observed line was empty and current temp
fell back to a forecast estimate.

An in-progress uncommitted edit had already changed `72` → `24`, but
`truncatedTo(HOURS)` rounds *downward* to the top of the hour, pushing the timestamp up to
59 min further into the past (e.g. at 18:35 → `18:00` yesterday = 24h35m ago), so it still
exceeds the 24h ceiling whenever the current minute > 0.

## Changes

### 1. Tomorrow.io startTime (the reported bug)
`app/src/main/java/com/weatherwidget/data/remote/TomorrowIoApi.kt`
- `minusHours(24)` → `minusHours(23)` (then truncated). Guarantees the start is between
  23h00m and 23h59m in the past — always under the 24h plan ceiling, while still capturing
  all of the current day's past hours the graph needs.

### 2. Generalized "error watermark" (new feature, per user request)
Existing infra (commit `a540bfc`) drew a watermark on every graph type, but only when a
source hit HTTP 429. The user asked that *continuous* serious errors (e.g. the 403, 401,
network) also surface instead of being silently swallowed. Design decisions confirmed with
the user via AskUserQuestion:
- **Trigger:** show the watermark after **≥3 consecutive failures** for a source; reset on
  first success (debounces transient blips).
- **Style:** a **single generic watermark** reusing the existing visual.

Implementation:
- `WidgetStateManager.kt`: replaced the boolean `source_rate_limited_<id>` pref + methods
  (`isSourceRateLimited`/`setSourceRateLimited`) with a per-source consecutive-failure
  counter (`source_fail_count_<id>`):
  - `recordSourceFetchFailure(source)` / `recordSourceFetchSuccess(source)` (reset to 0)
  - `getSourceFailureCount(source)` and `isSourceErrored(source)` (count ≥ threshold)
  - `const SOURCE_FAILURE_WATERMARK_THRESHOLD = 3`
- `CurrentTempRepository.kt`: per-source success → `recordSourceFetchSuccess`; both catch
  blocks → `recordSourceFetchFailure` (dropped the 429-only special-casing). Removed the
  `setSourceRateLimited` call inside `checkAndRethrowFailure` (the rethrown exception is now
  recorded by the outer catch).
- `ForecastRepository.safeFetch`: success → `recordSourceFetchSuccess`; catch →
  `recordSourceFetchFailure` (removed the 429 cause-walk).
- Honest renames (the flag is no longer rate-limit-specific):
  - `WidgetStateManager.isSourceRateLimited` → `isSourceErrored`
  - renderer/handler flag `isRateLimited` → `showErrorWatermark` (8 files)
  - `GraphRenderUtils.drawRateLimitedWatermark` → `drawErrorWatermark`
  - watermark text `"BEING RATE LIMITED"` → `"UPDATES FAILING"`

Note: the pre-existing uncommitted diff in `WeatherApi.kt`, `SilurianApi.kt`, and
`TomorrowIoApi.kt` (explicit HTTP status checks that throw `ApiAccessException` instead of
swallowing failures) was **kept** — it is exactly what surfaced the 403 in logs, and the
per-source catch handling means it never crashes sibling sources.

## Tests

- `TomorrowIoApiTest`: added a regression test capturing the outgoing request and asserting
  `startTime` parses to **>0h and <24h** in the past.
- `WidgetStateManagerTest`: replaced the old boolean test with three covering the threshold
  (no watermark below 3), reset-on-success, and per-source independence.
- `RateLimitedWatermarkRobolectricTest`: updated param name + expected text.
- Result: `./gradlew testDebugUnitTest --tests TomorrowIoApiTest --tests WidgetStateManagerTest
  --tests RateLimitedWatermarkRobolectricTest` → **BUILD SUCCESSFUL**, all passing.

## Verification (emulator, post-install)

`./gradlew installDebug` (installed on all 3 devices), then triggered a refresh via
`am broadcast -a com.weatherwidget.ACTION_REFRESH`:
- Freshness summary: `TOMORROW_IO:1m/90m:fresh` — a successful fetch 1 min ago (impossible
  before, when every fetch 403'd).
- `CURRENT_TEMP_DISPLAY: source=TOMORROW_IO anchorType=observed_delta observedTemp=84.6`
  (was `anchorType=forecast observedTemp=none`) — observations are now backfilled, so the
  graph's observed line renders.
- The 403 "startTime cannot be more than 24 hours in the past" no longer appears.
- A transient `HTTP_429 "Too Many Calls"` showed up afterward — expected, caused by the
  test-volume of repeated fetches (curl probe + manual refreshes); it clears on the next
  successful fetch and, under the new model, would only watermark after 3 consecutive fails.

## Follow-ups / notes

- Plan limitation: Tomorrow.io caps history at 24h, but the graph lookback is
  `HOURLY_LOOKBACK_HOURS = 72`. The graph's past region beyond ~24h will stay empty for
  Tomorrow.io — a paid-plan restriction, not a bug.
- The internal `showErrorWatermark` flow is wired through all four graph view handlers; no
  further UI work needed for the watermark to appear on any graph for the active source.
