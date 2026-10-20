# Sim loop pill in free space, tap zone that follows it, frame cache keyed by run

Plan: `plans/261020-sim-loop-pill-free-space-placement-and-review-fixes.md`.

## What happened
- The pill from 59ca84f6 was hard-coded top-right: on the Pixel it sat under "Silur" and the gear,
  and its fixed 80×40dp touch zone started where the pill ended — taps on the pill hit the API
  toggle or settings, and the zone stole the bottom of both.
- Both loop viewers cached frames as `${domain}_${layer}_${lead}.jpg` and reused them, so a newer
  model run was never downloaded.

## What changed
- `:shared` `SimLoopPill` — pill placement via `GraphEmptySpaceFinder` (labels repel; NOW line, nav
  arrows and the curve's fill veto), over-curve fallback, and `touchBounds` (≥ 48dp, clipped off
  header/arrows). Android renderer and desktop canvas both use it.
- Android `SimLoopTouchTarget` maps the drawn pill (bitmap px, `fitCenter`) to content dp and
  sizes/moves `sim_loop_touch_zone` every render (API 31+; no pill below). Logs
  `SIM_LOOP_PILL zoneDp=… reason=placed|no_space|api<31`.
- `:shared` `WeatherNextFrameCache` — run-keyed names (run → init → frame init → path hash), prunes
  other runs and legacy names; `WeatherNextForecastClient.cachedFrame`, atomic writes,
  `WEATHERNEXT_FETCH` logs. Both viewers use the shared client; manifest failure shows a retry.
- `RainForecastActivity`: no default location, scaled decode + recycle, two reused output buffers,
  thread-safe frame map. Pill paints cached in `PrecipitationGraphStyle`. Strings `sim_loop_button`,
  `sim_loop_no_location`, `sim_loop_load_failed` in all 20 locales; allowlist entry removed.

## Verification
- 5605 unit tests pass. Emulator: pill in open plot space, tap opens the viewer, run-keyed cache
  files present. Pixel and Fold: pill in open space, taps at centre and both ends open the viewer, settings gear still opens Settings. Desktop restarted, not visually checked.
