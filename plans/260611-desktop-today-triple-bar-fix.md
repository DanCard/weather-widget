# Desktop: fix today's "triple bar" forecast dot

## Context

**Problem:** In the desktop daily view, today's column draws three range bars (observed,
forecast, day-ahead snapshot). Late in the day the **forecast** bar collapses into a small
amber **dot** on the right side of the column instead of a proper bar.

**Root cause (confirmed in code + live DB):**
- For today, the forecast bar is `drawRangeLine(centerX + tripleOffset, day.forecastHigh,
  day.forecastLow, …)` in `DailyForecastGraph.kt:113`. `drawRangeLine` is a single
  `drawLine` with `StrokeCap.Round` (`DailyForecastGraph.kt:201`) — when `high == low` the
  zero-length line renders as a filled circle = the dot.
- Today's `forecastHigh`/`forecastLow` come straight from the stored daily forecast via the
  shared `DailyDayValueResolver.resolveTodayLineValues` (passed through unchanged).
- The stored value is degenerate: live DB shows today's latest NWS daily forecast as
  `highTemp=92.0, lowTemp=92.0` (earlier batches were a normal `91/62`). It collapses in
  `NwsDailyMapper.buildDailyForecasts` at line **178**: `val low = temps.second ?: high`.
  Late in the day today's overnight low has already passed, so both the NWS night period
  (keyed to *tomorrow*) and the gridpoint min-for-today are gone → `temps.second` is null →
  **low is fabricated as `high` (92).** The high (92) is a real gridpoint value; only the low
  is fabricated.

**Intended outcome:** Today's forecast bar disappears when its low is fabricated (degenerate),
instead of rendering as a dot. The observed and snapshot bars (which already convey the range)
remain; the real forecast high is still used for the column's high label.

## Approach

Treat a fabricated `forecastLow == forecastHigh` as "no resolvable forecast low" in the shared
resolver — mirroring the existing precedent at `DesktopDailyForecastModel.kt:136`, where
degenerate snapshots are already filtered with `it.highTemp != it.lowTemp`.

### Change 1 — `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyDayValueResolver.kt`

In `resolveTodayLineValues`, when the forecast low equals the forecast high (the
`temps.second ?: high` fabrication signature), return `forecastLow = null` while keeping
`forecastHigh`:

```kotlin
val resolvedForecastLow =
    if (forecastHigh != null && forecastHigh == forecastLow) null else forecastLow
return TodayLineValues(
    solidHigh = solidHigh,
    solidLow = solidLow,
    forecastHigh = forecastHigh,
    forecastLow = resolvedForecastLow,
)
```

Why this spot: `resolveTodayLineValues` is consumed **only by the desktop**
(`DesktopDailyForecastModel.kt:155`) — Android has its own path — so the fix is desktop-scoped
with zero Android blast radius, yet stays in the shared, unit-testable layer. Exact `==`
(not an epsilon) matches the fabrication exactly and the existing snapshot-filter convention, so
genuinely narrow real ranges are untouched.

No renderer change needed: `drawRangeLine` already early-returns when `low == null`
(`DailyForecastGraph.kt:210`), so the forecast bar is skipped. The high label is unaffected —
`highForLabel` uses `listOfNotNull(solidHigh, forecastHigh, snapshotHigh).maxOrNull()`
(`DailyForecastGraph.kt:134`), so it still shows the real forecast high.

### Change 2 — new test `shared/src/test/kotlin/com/weatherwidget/shared/util/DailyDayValueResolverTest.kt`

(No test exists for this resolver yet.) Cover:
- Degenerate: `forecastHigh = 92f, forecastLow = 92f` → result `forecastLow == null`,
  `forecastHigh == 92f`.
- Normal range: `92f / 62f` → passes through unchanged.
- Solid line still resolves: `solidHigh = currentTemp ?: actualHigh`,
  `solidLow = min(actualLow, currentTemp)` (regression guard for existing behavior).
- Null forecast inputs → nulls (no crash).

## Out of scope (noted, not changed)
- The same `temps.second ?: high` fabrication produces `high == low` for the **last
  forecast day beyond the gridpoint horizon** — this is documented/asserted as expected in
  `shared/src/test/.../NwsDailyMapperBuildTest.kt`, usually off-screen, and not what the user
  reported. Left as-is to avoid disturbing that tested behavior.
- Android: does not consume `resolveTodayLineValues`; unaffected.

## Verification
1. **Unit:** `./gradlew :shared:test --tests "*DailyDayValueResolverTest"` (and
   `:desktop:test` stays green).
2. **End-to-end:** rebuild + restart with `scripts/build-start.sh`, then capture the window
   and confirm the amber dot is gone from today's column while the bright-yellow snapshot bar
   and pink observed bar remain:
   ```
   W=$(xdotool search --name "Weather Widget" | head -1)
   import -window "$W" /tmp/ww.png && convert /tmp/ww.png /tmp/ww.jpg   # then read /tmp/ww.jpg
   ```
3. **Data sanity (optional):** confirm the degenerate row that triggered it —
   `sqlite3 ~/.local/share/weather-widget/weather.db "SELECT highTemp,lowTemp FROM forecasts
   WHERE source='NWS' AND date(targetDate/1000,'unixepoch')='2026-06-11'
   ORDER BY fetchedAt DESC LIMIT 1;"` → `92.0|92.0` (the fix suppresses its bar at render time;
   it does not rewrite the stored row).
