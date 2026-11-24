# Hourly tap/wheel zoom maps against the drawn data range

## Symptom (desktop, 2026-11-24 13:57 MST)

OWM source, daily view, tap Sunday 11/29 → hourly temperature graph (WIDE). Tap near 9 AM to zoom
in → "No hourly forecast for Sun Nov 29 — data ends Sun Nov 29 at 11 AM".

## Evidence

- `autostart-20261124-093932.log`:
  - `13:51:39 CLICK_DAILY date=2026-11-29 targetView=TEMPERATURE offset=118` → WIDE window
    `winStartHrsBack=-109`, i.e. Sun 02:57–20:57 (9 h back / 9 h forward of +118 h).
  - OWM's newest fetch (13:12) ends at Sun 11:00 (`hourly_forecasts`), so the window's points are
    Sun ~03:00–11:00 (`FORECAST_EXTREMA … forecastRange=-1..8`).
  - Zoom taps saved `hourlyOffset: 118 -> 126`, `118 -> 123`, `118 -> 122` with `zoomFactor -> 0.0`.
    +122 h = Sun ~16:00, five hours past the last data point.
- Screenshot: the zoomed view shows only the no-hourly message.

## Root cause

The graph *draws* x across the data range, but taps and the wheel *map* x across the nominal window:

- Desktop `TemperatureGraph.kt:371-376`: `xAtTime` spans `points.first()..points.last()`
  (`CloudCoverGraph`/`PrecipitationGraph` likewise via `startMs = points.first().dateTime`).
- Desktop `HourlyGraphInput.tapZoomOffset` (tap and wheel) passes `start..cutoff`, the window.
- Android `TemperatureGraphRenderer.kt:164-167`: `hourWidth` from first..last hour;
  `HourlyTouchZoneMapper.zoneIndexToOffset` maps zones across `offset ± zoom.back/forward`.

When the data fills the window the two agree. When it ends mid-window (OWM's 5-day edge, any
source's horizon) the curve is stretched across the full width, so a tap at the drawn 9 AM
(fraction ≈ 0.75 of 03:00–11:00) is read as 02:57 + 0.75 × 18 h ≈ 16:30.

Arithmetic check: 109 + 0.75 × 18 = 122.5 → logged 122/123. ✔

## Proposed fix (shared rule, both platforms)

1. `:shared` `HourlyZoomRules.tapOffsetHours` takes the **drawn** range: callers pass the first and
   last plotted hour (as offsets from now) instead of the window start/span. One rule, so the tap
   always lands on the hour under the finger.
2. Desktop: `hourlyGraphTapInput` / `hourlyPanZoomInput` receive `points.first()/last().dateTime`
   (the same values `xAtTime` uses) for tap and wheel-centre mapping. Pan stays window-based
   (it moves hours, not x positions).
3. Android: `HourlyTouchZoneMapper.zoneIndexToOffset` takes the rendered hours' first/last offsets;
   `TemperatureTouchTargets` and `HourlyBottomZoneHelper` pass them (the same `hours` list the
   renderer receives). **Not yet reproduced on a device**: verify on the emulator with OWM at its
   horizon before and after.

Not proposed (Claude's alternative, rejected for scope): draw the graph against the full window and
leave the post-horizon part blank. Bigger rendering change, and changes every edge-of-data view.

## Tests

- `:shared` unit: `tapOffsetHours` with a data range shorter than the window: a tap at 0.75 of
  03:00–11:00 returns 9 AM's offset (fails on the old window mapping).
- Integration (desktop): Compose UI test with a Sunday-truncated OWM hourly fixture through
  `TemperatureGraph` + `hourlyGraphTapInput`: a tap at the 9 AM x saves an offset of 9 AM ±1 and the
  zoomed view has points (no `no_hourly_message`).
- Android Robolectric/instrumented: `TemperatureTouchTargets` zone centres follow the drawn range for
  a truncated `hours` list.
- Manual: desktop repro above (xdotool tap + screenshot); emulator equivalent.

## Docs

`rg` `arch/` for `tapOffsetHours` / tap-zoom wording and update the canonical description.
