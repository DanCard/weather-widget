# Hourly tap/wheel zoom and pan map against the drawn data range (desktop + Android)

Combines [261124-hourly-tap-zoom-maps-drawn-range-claude.md](261124-hourly-tap-zoom-maps-drawn-range-claude.md) and [261124-desktop-hourly-truncated-zoom-neon.md](261124-desktop-hourly-truncated-zoom-neon.md).

## Symptom

On desktop with `OPEN_WEATHER_MAP`, clicking next Sunday (Nov 29) in the daily view opens the hourly temperature graph (`WIDE`), and clicking or scrolling to zoom in around 9 AM immediately shows:

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
   - Arithmetic check: `109 + 0.75 * 18 = 122.5` → logged `122`/`123`.

## Root cause

Both platforms **draw** the hourly x-axis across the plotted data range, but **map pointer/touch x** across the nominal window:

- **Desktop rendering**: `TemperatureGraph.kt` (`dataStart..dataEnd`) and `DesktopGraphUtils.kt` (`hourlyGraphCanvasGeometry`, shared by `CloudCoverGraph` and `PrecipitationGraph`) map `points.first().dateTime..points.last().dateTime` across `0..width` (`xAtTime`), and compute `dragResidualPx` from `dataSpan`.
  - For OWM on Sun Nov 29 (`03:00..11:00`, 8 h), **9 AM is drawn at `x / width = (9 - 3) / 8 = 0.75`**.
- **Desktop input (`HourlyGraphInput.kt`)**: `hourlyGraphTapInput` passes the unclipped window `start..cutoff` (`03:00..21:00`, 18 h) to `tapZoomOffset` (for both click-zoom and wheel-zoom) and the window `spanHours` (18 h) to `panDeltaHours` (for horizontal drag-pan).
  - Clicking or scrolling at `x / width = 0.75` computes `03:00 + 0.75 * 18 h = 16:30` (`offset = 122`/`123`), jumping past 11 AM into empty afternoon hours.
  - Dragging 1 visual hour (`w / 8` px) commits `18 / 8 = 2.25` h on release, out of sync with `dragResidualPx`.
- **Android rendering & input**:
  - `TemperatureGraphRenderer`, `CloudCoverGraphRenderer`, and `PrecipitationGraphRenderer` space `hours` across `0..width` (`hourWidth = width / (hours.size - 1)`).
  - `HourlyTouchZoneMapper.zoneIndexToOffset` (called by `TemperatureTouchTargets.setupZoomTapZones` and `HourlyBottomZoneHelper.resolveZoneAction`, wired from `TemperatureViewBinder`, `PrecipViewHandler`, and `CloudCoverViewHandler`) maps the 13 zones across the nominal window `hourlyOffset ± zoom.back/forward`, not the rendered `hours` range.

When data fills the window, drawn range and nominal window agree. When a source's forecast ends mid-window (or history starts mid-window), they diverge.

### Alternative considered (not proposed)

Changing `xAtTime` / Android renderers to map across the full nominal window and leave post-horizon hours blank. Rejected for scope: larger visual/rendering change across all six graph renderers on both platforms, and alters every edge-of-data view.

## Proposed changes

### 1. `:shared` (`shared/src/main/kotlin/com/weatherwidget/shared/graph/ZoomWindow.kt`)
- Keep `HourlyZoomRules.tapOffsetHours(fraction, startOffsetHours, spanHours)` as the single shared rule, clarifying in KDoc that callers pass the **drawn** range (`first..last` plotted hour as offsets from now, falling back to the nominal window when `< 2` distinct hours exist) so taps always land on the hour under the finger/cursor.

### 2. Desktop (`desktop/src/main/kotlin/com/weatherwidget/desktop/HourlyGraphInput.kt`)
- Add `internal fun drawnAxisBounds(points: List<HourlyForecast>, fallbackStart: Long, fallbackEnd: Long): Pair<Long, Long>` returning `points.first().dateTime to points.last().dateTime` when `points.size >= 2` and `dataEnd > dataStart`, else `fallbackStart to fallbackEnd`.
- In `hourlyGraphTapInput`, derive `(axisStart, axisEnd) = drawnAxisBounds(points, start, cutoff)` and `axisSpanHours = ((axisEnd - axisStart) / 3_600_000.0).roundToInt().coerceAtLeast(1)`, and pass them to `hourlyPanZoomInput` (so wheel-zoom and horizontal drag-pan match `xAtTime` and `dragResidualPx` 1:1) and `tapZoomOffset` (with `axisStart`/`axisEnd` in `pointerInput` keys).
- In `hourlyPanZoomInput`, wrap `start`, `cutoff`, and `nowMs` in `rememberUpdatedState` so `onPointerEvent(PointerEventType.Scroll)` never captures stale axis bounds across recompositions.

### 3. Android (`app/src/main/java/com/weatherwidget/widget/`)
- **`HourlyTouchZoneMapper.kt`**: add an overload or optional `drawnRange: Pair<Double, Double>?` (or `firstHourOffsetHours`/`lastHourOffsetHours`) to `zoneIndexToOffset(...)` that maps `zoneIndex / (HOUR_ZONE_COUNT - 1)` across the drawn range when `last > first`, falling back to `(currentHourlyOffset - zoom.backHours, zoom.backHours + zoom.forwardHours)` when null/degenerate.
- **`TemperatureTouchTargets.kt`** (`setupZoomTapZones`) and **`HourlyBottomZoneHelper.kt`** (`setup` / `resolveZoneAction`): accept the drawn hour range (optional, defaulting to null for empty/fallback views) and forward it to `HourlyTouchZoneMapper.zoneIndexToOffset`.
- **`TemperatureViewBinder.kt`**, **`PrecipViewHandler.kt`**, **`CloudCoverViewHandler.kt`**: pass the rendered `hours`' first and last hour offsets (relative to `now`) when wiring `setupZoomTapZones` and `HourlyBottomZoneHelper.setup`.

### 4. Documentation
- `rg` `arch/` for `tapOffsetHours`, `HourlyTouchZoneMapper`, and tap-zoom wording; update the canonical doc if any reference describes window-only mapping.

## Tests

1. **`:shared` unit** (`TapOffsetHoursTest.kt`): verify `tapOffsetHours` on a drawn range shorter than the window (e.g. `03:00..11:00` at `fraction = 0.75` returns 9 AM's offset).
2. **Desktop unit** (`TapZoomOffsetTest.kt`): test `drawnAxisBounds` on a truncated OWM Sunday `03:00..11:00` slice inside `03:00..21:00` (`fraction = 0.75` → 9 AM) and fallback when `< 2` distinct points exist.
3. **Desktop integration** (`DesktopNoHourlyDayClickTest.kt`): Compose UI test rendering `WidgetPopup` with OWM data ending at 11 AM on day 5, clicking at 75% width (`9 AM`), and asserting zoom centers near 9 AM with no `hourly_empty_notice` or `no_hourly_message`.
4. **Android unit/Robolectric** (`HourlyTouchZoneMapperTest` / `TemperatureTouchTargets` test): verify `zoneIndexToOffset` and `setupZoomTapZones` map zone indices across a truncated rendered `hours` range and fall back cleanly when empty/single-hour.

## Verification

1. **Desktop live check**: run `./scripts/buildStart-desktop.sh`, open OWM → Sunday Nov 29 → zoom in around 9 AM (click and scroll wheel), verify it centers near 9 AM with OWM data and no "No hourly forecast" error, and capture a screenshot.
2. **Android emulator check**: verify on a running emulator with OWM at its horizon (reproduce before/after or verify tapped zone centers on 9 AM).
3. **End-of-task gate**: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
