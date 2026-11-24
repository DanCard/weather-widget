# Hourly tap, wheel and drag input follow the drawn axis (combined plan)

Combines `plans/261124-hourly-tap-zoom-maps-drawn-range-claude.md` (shared rule + Android) and
`plans/261124-desktop-hourly-truncated-zoom-neon.md` (drag-pan, fallback, precise evidence).

## Symptom (desktop, 2026-11-24 ~13:57 MST)

`OPEN_WEATHER_MAP`, daily view → tap Sunday 11/29 → hourly temperature graph. Tapping near 9 AM to
zoom in shows: `No hourly forecast for Sun Nov 29 — data ends Sun Nov 29 at 11 AM`.

## Evidence

1. `hourly_forecasts` (desktop DB): OWM's newest fetch (13:12) ends at Sun 11:00. Its free
   `/2.5/forecast` is 40 × 3 h slots, so the horizon falls mid-day.
2. Console log `~/.local/state/weather-widget/autostart-20261124-093932.log`:
   - `CLICK_DAILY date=2026-11-29 targetView=TEMPERATURE offset=118` → WIDE window Sun 03:00–21:00
     (18 h); the drawn points span only 03:00–11:00 (8 h).
   - Zoom taps saved `hourlyOffset: 118 -> 126`, `118 -> 123`, `118 -> 122` (`zoomFactor -> 0.0`).
   - `HOURLY_EMPTY_NOTICE source=OPEN_WEATHER_MAP view=2026-11-29T14:00..2026-11-29T18:00`.
3. Screenshot: the zoomed popup shows only the no-hourly message.

Arithmetic: 9 AM is drawn at (9 − 3) / 8 = 0.75 of the width; input reads 03:00 + 0.75 × 18 h =
16:30 → offset 122/123, matching the log.

## Root cause

Graphs draw x across the **data** range; input maps x across the **window**.

| Path | Axis today |
|---|---|
| Desktop `TemperatureGraph` `xAtTime` (`:371-376`), `hourlyGraphCanvasGeometry` (cloud, precip) | `points.first()..points.last()` |
| Desktop `dragResidualPx` (live slide during a drag) | data span |
| Desktop bottom-strip icon tap | `points` index (already drawn-axis) |
| Desktop body tap + wheel → `tapZoomOffset(…, start, cutoff, …)` | **window** |
| Desktop drag commit → `panDeltaHours(…, spanHours)` | **window** (18 h vs 8 h drawn: ×2.25, curve jumps on release) |
| Android `TemperatureGraphRenderer` `hourWidth` (`:164-167`) | first..last hour |
| Android `HourlyTouchZoneMapper.zoneIndexToOffset` (13 zones) | **window** (`offset ± zoom.back/forward`) |

The two agree only when data fills the window, so the bug appears at any source's horizon.

## Fix

### `:shared` (one rule)

1. `HourlyZoomRules` (`ZoomWindow.kt`): add `drawnAxis(firstPointOffsetHours, lastPointOffsetHours,
   windowStartOffsetHours, windowSpanHours)` → `(startOffsetHours, spanHours)`: the drawn range when
   there are ≥ 2 distinct points, otherwise the window (fallback).
2. `tapOffsetHours` keeps its signature; every caller passes the drawn axis from step 1.
3. Add `panSpanHours` (or reuse the drawn span) so drag converts pixels with the drawn span.

### Desktop

4. `hourlyGraphTapInput` / `hourlyPanZoomInput` (`HourlyGraphInput.kt`): take the drawn axis from
   `points.first()/last().dateTime` (the values `xAtTime` uses) for body tap, wheel centre and
   `panDeltaHours`. The `rememberUpdatedState` holders for start/cutoff/now already exist
   (`:44-48`); route the drawn bounds through them. Key the drag `pointerInput` on the drawn span.
5. All three graphs (Temperature, CloudCover, Precipitation) get it through the shared modifier.

### Android

6. `HourlyTouchZoneMapper.zoneIndexToOffset`: take the drawn axis instead of `zoom`;
   `TemperatureTouchTargets` and `HourlyBottomZoneHelper.resolveZoneAction` pass the first/last
   offset of the same `hours` list the renderer draws.
7. **Reproduce first** on the emulator (OWM, tap the horizon day, tap a zone near 9 AM) and capture
   the saved offset in logcat; the fix goes in only if the device shows the same jump.

## Tests

- `:shared` unit: `drawnAxis` + `tapOffsetHours` — 0.75 of 03:00–11:00 inside 03:00–21:00 → 9 AM
  (fails on the window mapping); < 2 points → window fallback; full-window data unchanged.
- Desktop unit: `panDeltaHours` with the drawn span (8 h) vs window (18 h).
- Desktop Compose integration (`DesktopNoHourlyDayClickTest`): `WidgetPopup` with OWM-like data ending
  11 AM on day 5 → click at 75 % width → saved offset ≈ 9 AM, no `hourly_empty_notice` /
  `no_hourly_message`; wheel at the same x centres the same hour.
- Android Robolectric (`WeatherWidgetProviderRobolectricTest`): existing `zoneIndexToOffset` cases
  stay green for full data; new truncated-`hours` case maps zone 9 of 12 to 9 AM.

## Verification

1. `./scripts/unit-tests.sh`; focused emulator run for any instrumented test touched.
2. Desktop: `./scripts/buildStart-desktop.sh`, repeat the repro (click and wheel at 9 AM, drag),
   screenshot each.
3. Android: emulator repro before/after per step 7.
4. End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Docs

`rg` `arch/` for tap-zoom / touch-zone / pan wording; record "input follows the drawn axis" in the
canonical hourly-graph doc and link from others.

## Rejected

Drawing against the full window (blank space past the horizon): larger visual change to every
edge-of-data view on both platforms, and it diverges from the existing full-width rendering.
