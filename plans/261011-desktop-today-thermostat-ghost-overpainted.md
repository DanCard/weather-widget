# Desktop today thermostat: earlier high painted solid instead of ghost

## Rule (both platforms)
- Solid mercury: `barTopHigh` (current temp ?: observed high ?: forecast high) down to `mercuryBottom` (bulb).
- Ghost (translucent, Android alpha 75/255, desktop 0.3): from `ghostHigh` (today's observed peak) down to
  the mercury top, only when the peak is warmer than the current reading.
- Bulb at the mercury bottom; never warmer than the mercury top.

## Bug
`5010337e` (Silurian today high from Synoptic) drew the desktop solid mercury from
`max(barTopHigh, ghostHigh)`, so the solid red covered the ghost span: the earlier high showed
as full-strength mercury. Android (`DailyBarRenderer.drawTodayTripleBar`) was unaffected.

## Fix
`DailyForecastGraph.kt`: solid mercury starts at `barTopHigh ?: ghostHigh`; `barTop` (the max) is kept
only for the column's panel/label extent.

## Follow-up: shared geometry (same day, user's call)
The spans now live in `:shared` `TodayThermostat.segments(barTopHigh, solidLow, ghostHigh)` →
`mercuryTop`, `mercuryBottom` (bulb), `ghostTop` (null unless the peak is warmer), `top` (extent).
Both renderers draw from it; Android keeps only the pixel minimum bar height (`resolveBarEndpoints`).
Ghost alpha is `TodayThermostat.GHOST_ALPHA_255` = 75 (Android's value; desktop moves 0.30 → 0.294).
Tests: `shared/.../graph/TodayThermostatTest.kt`.
