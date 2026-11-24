# Desktop hourly zoom/pan on truncated forecast windows

## Problem

On desktop with `OPEN_WEATHER_MAP`, clicking next Sunday (Nov 29) in the daily view opens the hourly temperature graph, and trying to zoom in around 9 AM immediately shows:

> `No hourly forecast for Sun Nov 29 — data ends Sun Nov 29 at 11 AM`

## Evidence

1. **Screenshot & database (`~/.local/share/weather-widget/weather.db`)**:
   - Desktop window (`0x05200007`) displays `No hourly forecast for Sun Nov 29 — data ends Sun Nov 29 at 11 AM`.
   - `hourly_forecasts` for `OPEN_WEATHER_MAP` has hourly rows on `2026-11-29` from `00:00` through `11:00` (OWM's 5-day horizon ends at 11 AM local time).
2. **Console log (`~/.local/state/weather-widget/autostart-20261124-093932.log`) & `app_logs`**:
   - `13:57:37` `CLICK_DAILY`: `date=2026-11-29 targetView=TEMPERATURE offset=118` (Sun `12:00`, `WIDE` window `03:00..21:00`, 18 h span).
   - `13:58:15` `CONFIG_SAVE`: `hourlyOffset: 118 -> 122, zoomFactor: 0.2555302 -> 0.0` (centers on Sun `16:00`, `NARROW` 4 h window `14:00..18:00`).
   - `13:58:15` `HOURLY_EMPTY_NOTICE`: `source=OPEN_WEATHER_MAP view=2026-11-29T14:00..2026-11-29T18:00 -> "No hourly forecast for Sun Nov 29 — data ends Sun Nov 29 at 11 AM"`.
   - Earlier clicks at `13:57:13` (`offset 118 -> 126`, `18:00..22:00`) and `13:57:27` (`offset 118 -> 123`, `15:00..19:00`) show the same rightward jump past 11 AM.

## Root cause

All three desktop hourly graphs (`TemperatureGraph.kt`, `CloudCoverGraph.kt`, `PrecipitationGraph.kt`) map x-coordinates differently for **rendering** vs **pointer input**:

- **Rendering (`xAtTime`)**: `TemperatureGraph.kt` (`dataStart..dataEnd`) and `DesktopGraphUtils.kt` (`hourlyGraphCanvasGeometry`) stretch `points.first().dateTime..points.last().dateTime` across `0..width` so the curve fills the full width (matching Android's index-based spacing).
  - For OWM on Sun Nov 29 (`03:00..11:00`, 8 h), **9 AM is drawn at `x / width = (9 - 3) / 8 = 0.75`**.
- **Pointer input (`HourlyGraphInput.kt`)**: `hourlyGraphTapInput` passes the unclipped window bounds `start..cutoff` (`03:00..21:00`, 18 h) to `tapZoomOffset` (for both click-zoom and wheel-zoom) and `spanHours` (18 h) to `panDeltaHours` (for horizontal drag-pan).
  - Clicking or scrolling at `x / width = 0.75` computes `03:00 + 0.75 * 18 h = 16:30` (`offset = 122` or `123`), jumping past 11 AM into empty afternoon hours.
  - Meanwhile, the bottom 44 dp icon strip tap in `HourlyGraphInput.kt` already indexed across `points` (`0..points.lastIndex`), and `dragResidualPx` already used `dataSpan`, so only graph-body tap/wheel zoom and drag commit were out of sync.

## Design options

- **Option A (Recommended) — Map pointer input against the drawn data span (`points.first().dateTime..points.last().dateTime`)**:
  - Preserves the existing full-width curve rendering (consistent with Android's index-based spacing and existing desktop behavior).
  - Keeps `xAtTime`, bottom-strip taps, `dragResidualPx`, click-zoom, wheel-zoom, and drag-pan all using the same `dataStart..dataEnd` axis.
  - Clicking or scrolling at 9 AM (`0.75 * width`) computes `03:00 + 0.75 * 8 h = 09:00` and zooms cleanly to `07:00..11:00` with full OWM data.
  - Note: Android (`HourlyTouchZoneMapper` + `TemperatureTouchTargets`) has a similar window-vs-drawn-hours assumption when a provider's horizon ends mid-window, though this task is scoped to desktop (`desktop:`).
- **Option B — Change `xAtTime` to map the full `start..cutoff` window instead of `dataStart..dataEnd`**:
  - Leaves `11:00..21:00` visibly blank on the right side of the graph so the missing afternoon is obvious before zooming.
  - Larger visual/behavioral change across all three desktop graphs (also affects normal non-truncated views where `CloudCoverGraph`/`PrecipitationGraph` exclude `cutoff`, and diverges from Android's full-width index spacing).

## Proposed changes (Option A)

1. **`desktop/src/main/kotlin/com/weatherwidget/desktop/HourlyGraphInput.kt`**:
   - Add `internal fun drawnAxisBounds(points: List<HourlyForecast>, fallbackStart: Long, fallbackEnd: Long): Pair<Long, Long>` returning `points.first().dateTime to points.last().dateTime` when `points.size >= 2` and `dataEnd > dataStart`, else `fallbackStart to fallbackEnd`.
   - In `hourlyGraphTapInput`, derive `(axisStart, axisEnd) = drawnAxisBounds(points, start, cutoff)` and `axisSpanHours = ((axisEnd - axisStart) / 3_600_000.0).roundToInt().coerceAtLeast(1)`, and pass them to `hourlyPanZoomInput` and `tapZoomOffset` (with `axisStart`/`axisEnd` in `pointerInput` keys).
   - In `hourlyPanZoomInput`, wrap `start`, `cutoff`, and `nowMs` with `rememberUpdatedState` so `onPointerEvent(PointerEventType.Scroll)` never captures stale axis bounds across recompositions.
2. **Tests**:
   - `desktop/src/test/kotlin/com/weatherwidget/desktop/TapZoomOffsetTest.kt`: add unit tests for `drawnAxisBounds` (truncated OWM Sunday window `03:00..11:00` inside `03:00..21:00` mapping `0.75` to 9 AM; fallback when `< 2` distinct timestamps).
   - `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopNoHourlyDayClickTest.kt`: add a Compose UI integration test rendering `WidgetPopup` with OWM data ending at 11 AM on day 5, clicking at 75% width (`9 AM`), and asserting zoom centers near 9 AM with no `hourly_empty_notice` or `no_hourly_message`.

## Verification

1. Run desktop JVM + Compose UI tests: `./gradlew :desktop:test`.
2. Rebuild and restart the desktop app via `./scripts/buildStart-desktop.sh`.
3. Verify live in the desktop app on `OPEN_WEATHER_MAP`: click next Sunday (Nov 29), zoom in around 9 AM (click and/or scroll wheel), confirm the graph centers around 9 AM with OWM data and no "No hourly forecast" error, and capture a verification screenshot.
