# Fix: today's "previous 24h forecast" bar renders all grey

## Context

Today's column in the daily view has 3 bars: **[24h-prior snapshot | actual thermostat | live forecast]**. The snapshot bar is "yesterday's forecast for today" and should look like the live forecast bar looked yesterday — bright yellow with a grey cloud-fraction bottom segment.

Today (Jul 12) it renders as a **solid grey slab** on both Android and desktop. Root cause: all sources stored a cloudy condition text for today (NWS "Mostly Cloudy", Open-Meteo "Overcast", Silurian "Cloudy"), and the snapshot bar is the *only* bar whose **base color** is derived from the raw snapshot condition text. Cloudy text → slate-grey base; the weather-adaptive split then paints the bottom segment grey too → grey-on-grey, indistinguishable full-grey bar. The live bar for the same day/data stays gold-top because its icon goes through the noon-cloud gate; past-day overlays likewise never grey out.

## Agreed behavior

- The snapshot bar's base color is the bright snapshot yellow, **regardless of how cloudy the snapshot condition is**. Cloudiness is already encoded by the grey bottom segment of the adaptive split (unchanged).
- One exception, preserved from current behavior (and pinned by an existing test): a **rainy** snapshot condition keeps the steel-blue base — "yesterday they predicted rain" stays visible.
- No changes to split math, cloud-ratio inputs, past-day overlays, or the live forecast bar.

## Changes

1. **`shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherColors.kt`** — add the single-source rule next to `mixedBarSplit()`:
   ```kotlin
   /** Base color for the today-column 24h-prior snapshot bar. Rain keeps the blue base;
    *  anything else (incl. cloudy) returns null = use the platform's bright snapshot yellow —
    *  cloudiness is carried by the adaptive split's grey bottom, never by greying the base. */
   fun snapshotBarOverrideArgb(isRainy: Boolean): Int? = if (isRainy) FORECAST_RAINY else null
   ```

2. **Android `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`** (`drawTodayTripleBar`, ~lines 974–983): replace the `sCondColor` block (`forecastColor(...)` + sunny/null fallback) with:
   `WeatherColors.snapshotBarOverrideArgb(sIsRainy) ?: paints.todaySnapshotYellowPaint.color`.
   `sIsSunny`/`sIsMixed` remain only where still needed for the `snapshotDay` copy (flags feed the adaptive-segment gate and cloud-ratio fallback — unchanged).

3. **Desktop `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`** (today branch, lines 167–178): replace the `sCondColor`/`snapshotColor` block with:
   `WeatherColors.snapshotBarOverrideArgb(snapshotFlags.isRainy)?.let { Color(it) } ?: Color.Yellow`.

## Tests

- **Shared**: new small test (next to `MixedBarSplitTest.kt`) — rainy → `FORECAST_RAINY`, non-rainy → null.
- **Android `DailyForecastGraphRendererRoboTest.kt`**: add `snapshotBar_cloudyCondition_keepsYellowBase` — `snapshotIconRes = ic_weather_mostly_cloudy` must yield the yellow base (`0xFFFFFF00`) with `adaptiveSegments = true`, i.e. the exact regression seen today. Existing tests `snapshotBar_usesAdaptiveColor` (rain → blue) and `snapshotBar_mixedCondition_usesYellowTop` must still pass unchanged.

## Verification

1. `./gradlew :shared:test :app:testDebugUnitTest --tests "*DailyForecastGraphRendererRobo*"` (+ the new shared test).
2. Desktop end-to-end: `scripts/buildStart-desktop.sh` (build + restart the repo autostart launcher), touch `.show`, screenshot — today's left bar must be yellow-top/grey-bottom matching the live bar's split, no longer solid grey.
3. Android end-to-end: `./gradlew installDebug` on `emulator-5554`, force a widget repaint (ACTION_REFRESH broadcast if needed after process kill), screenshot the widget — same expectation on the Sun column.
