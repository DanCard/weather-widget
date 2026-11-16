# Hourly graph: drop temperature and weather colouring; no blue rain high label

## Request

1. "Emulator: For today column I see 65 degrees in blue. why?" → "Lets delete the rule blue for
   temperature label when rain" → scope "Both" (daily high label and hourly labels).
2. "In hourly temperature graph lets delete line coloring based on temperature and weather."

## Evidence

- Emulator daily view, Today column, NWS: `TODAY_BAR_DEBUG … fHigh=65.0 trueHigh=64.45514
  rainChance=87`. The settled day draws two highs (`DualHighLabel.showBoth`, 0.55° miss): 64.5°
  observed red, and 65° from `forecastArgb(day)` → `WeatherColors.dailyForecastArgb` →
  `FORECAST_RAINY` (blue) because the icon is rain and the day chance is ≥ the chance-rain bar.
- Hourly graph colouring today:
  - Forecast dashed line: one colour per segment from the hour's weather (`forecastColor`: rain blue,
    night silver, twilight orange, sun amber, cloud slate). Android
    `TemperatureGraphSeriesRenderer.drawForecastSegments` / `resolveForecastSegmentColors`; desktop
    `TemperatureGraph.kt` ~line 407.
  - Fill under the expected (delta-shifted) curve: vertical temperature gradient (blue ≤ 50°,
    amber 70°, orange ≥ 90°). Android `TemperatureGraphStyle.buildTempGradient`; desktop `drawFill` /
    `buildColorStops`; both via `TemperatureColorModel.gradientStops`.
  - Fetch ("now") dot: `TemperatureColorModel.tempToColorArgb(lastObservedTemp)` on both platforms.
  - Forecast value labels and leaders: `TemperatureLabelColors.labelArgb` → hour's weather colour.
  - Actual line and labels: observed rose (unchanged).

## Proposed change (Claude's proposals marked *)

1. **Hourly forecast line**: one neutral colour for every segment — white*, the same as the ghost
   line and the comparison graph's source line. Stale-hour dimming stays.
2. **Expected fill**: keep the fill and its alpha ramp, but white* instead of the temperature
   gradient.
3. **Fetch dot**: observed rose* (it marks the last observed temperature), not a temperature colour.
4. **Hourly forecast value labels / leaders**: the forecast line's colour (white*). Actual labels
   stay rose.
5. **Daily forecast high label** (dual label, Android `DailyBarRenderer.kt:279`, desktop
   `DailyForecastGraph.kt:480`): new shared `WeatherColors.dailyForecastLabelArgb` = the daily
   colour without the rain branch (rain icon → cloud slate; sun/mixed → amber). Daily bars keep
   their blue rain colour.
6. Single shared source: `HourlyGraphDefaults`/`:shared` constant for the forecast line colour,
   used by both renderers and `TemperatureLabelColors`.
7. Delete dead code: `resolveForecastSegmentColors`, desktop `drawCurveLine` (already unused),
   `buildTempGradient`, `buildColorStops`, `TemperatureColorModel.gradientStops` and its tests.
   `tempToColorArgb` stays (Months graph, `ForecastDeltaLabel`).

Not changed: cloud/precip overlays on the hourly graph, daily bars, Months graph line.

## Tests

- `TemperatureLabelColorsTest`: forecast label is the neutral colour for rainy/sunny/night hours.
- `SnapshotBarColorTest` (or new `DailyForecastLabelColorTest`): rain icon + 87% → not
  `FORECAST_RAINY`; bar colour still `FORECAST_RAINY`.
- Update `TemperatureGraphDashContinuityTest` (it reads segment colours).
- `./scripts/unit-tests.sh`; emulator focused tests touching the graph renderer.

## Verification

- `installDebug` on all connected devices; screenshot emulator hourly and daily views (Today 65°
  label grey, hourly forecast line white, fill white, dot rose).
- Rebuild and restart the desktop app; check the same views.
- Update `arch/` docs if they describe the colours (grep showed none).

## Follow-up (2026-11-15, user: "prefer white")

Item 5 changed after the emulator showed today's 65° in slate: the daily forecast HIGH label is
white (`WeatherColors.FORECAST_NEUTRAL`, renamed from `HOURLY_FORECAST`), not a rain-free weather
colour. `dailyForecastLabelArgb` was removed. Rule: white = forecast, rose = observed.
Open question: the two white hourly lines (dashed forecast vs. faint dotted ghost) are not yet
checked on screen.
