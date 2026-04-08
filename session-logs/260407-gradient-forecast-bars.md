# Gradient Forecast Bars for Mixed-Condition Days

**Date:** 2026-04-07
**Branch:** main
**Status:** Complete, deployed to emulator

## Summary

Changed daily forecast bars from solid gray for all mixed-condition days (partly cloudy, fog then sunny, mostly cloudy) to **smooth vertical gradients** that blend gold (sunny) at the top with gray (cloudy) at the bottom. The proportion of gray reflects the actual cloudiness of the condition.

## Context & Motivation

This session continues the weather-adaptive color work from the previous session. The previous session implemented:
- Weather-adaptive forecast colors (gold/gray/blue/silver) for both hourly line and daily bars
- Hot pink (#FF3366) actual/observed line replacing the old gold
- Per-segment coloring on the hourly temperature graph
- Fixes for peak forecast label suppression and label color mismatch

In this session, the user noticed that "Fog then Sunny" and "Partly Cloudy" days looked too dreary with a full solid gray bar — those days are mostly sunny and should look it. The user proposed a gradient approach where the bottom of the bar fades to gray while the top stays gold.

## Design Decision

Three options were considered:
1. **Option A: Vertical Split** — Hard boundary between gold and gray sections
2. **Option B: Smooth LinearGradient** — Smooth blend from gold to gray (CHOSEN)
3. **Option C: Accent Band** — Thin gray band at bottom only

Option B was chosen because it's elegant and easy to implement with Android's `LinearGradient` shader, which works directly with the existing `drawLine()` bar rendering.

### Cloud Ratio Mapping

Each mixed-condition icon maps to a "cloud ratio" that controls how much of the bar is gray:

| Icon / Condition | Cloud Ratio | Visual |
|-----------------|-------------|--------|
| `ic_weather_fog_sunny` | 0.15 | 85% gold, 15% gray-fade |
| `ic_weather_partly_cloudy` | 0.35 | 65% gold, 35% gray-fade |
| `ic_weather_partly_cloudy_night` | 0.35 | 65% gold, 35% gray-fade |
| `ic_weather_partly_cloudy_chance_rain` | 0.40 | 60% gold, 40% **blue**-fade |
| `ic_weather_mostly_cloudy` | 0.70 | 30% gold, 70% gray-fade |
| `ic_weather_mostly_cloudy_night` | 0.70 | 30% gold, 70% gray-fade |
| `ic_weather_fog_cloudy` | 0.70 | 30% gold, 70% gray-fade |

Non-mixed conditions (sunny, rainy, etc.) remain solid-colored — no gradient.

### Gradient Technique

Uses a 3-stop `LinearGradient` shader:
```
stops:    [0.0,        1.0 - ratio,  1.0]
colors:   [gold,       gold,         gray/blue]
```

This keeps the top portion solid gold (crisp, not muddy) and only transitions to the secondary color in the bottom portion. The bar's vertical axis maps naturally: bottom = low temp (morning) → top = high temp (afternoon), so "fog then sunny" → gray at bottom → gold at top feels intuitive.

## Files Modified

### `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`
- Added `cloudRatio(iconRes: Int): Float?` — maps icon resource to cloud ratio (null for non-mixed)
- Added `forecastBarGradient(iconRes: Int, topY: Float, bottomY: Float): LinearGradient?` — creates 3-stop vertical gradient, returns null for solid-color bars
- Special case: `ic_weather_partly_cloudy_chance_rain` uses `FORECAST_RAINY` (blue) as the bottom color instead of `FORECAST_CLOUDY` (gray)

### `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
Three bar-draw sites updated with the same pattern:
```kotlin
if (day.isMixed && day.iconRes != null) {
    paint.shader = WeatherConditionColors.forecastBarGradient(day.iconRes, topY, bottomY)
}
canvas.drawLine(x, topY, x, bottomY, paint)
paint.shader = null
```

Applied at:
1. **Future forecast bars** (line ~365) — main bars for upcoming days
2. **Forecast overlay bars** (line ~383) — paired forecast bars for past days
3. **Today's forecast bar** (line ~441) — right bar in the today triple-bar

## Files NOT Modified
- Hourly temperature graph — already has per-segment coloring, no gradient needed
- Labels, rain indicators, icons — unchanged
- Solid-color bars (sunny, rainy, history, today-observed) — unchanged
- No test files modified — gradient is visual-only, no geometry changes

## Testing

- **Unit tests:** All 695 tests pass (`./gradlew testDebugUnitTest`)
- **Visual verification:** Deployed to emulator, gradient bars visible for Thu/Fri (partly cloudy days), solid bars for sunny/rainy days
- No test changes needed since the gradient is purely visual (shader on paint) and doesn't affect geometry, bar positions, or color values reported by `BarDrawnDebug`

## Plan File
Saved to `plans/260407-gradient-forecast-bars.md`

## Conversation Prompts

1. **User:** "Daily view: For days that are mostly sunny, fog then sunny, and partly cloudy, I don't like the grey bar. Prefer for fog then sunny something like bottom 15% of the bar is grey then sunny. Something similar for other partly sunny days. What do you think?"

2. **Claude:** Presented three design options:
   - Option A: Vertical Split Gradient (hard boundary between colors)
   - Option B: Smooth LinearGradient (smooth blend, recommended)
   - Option C: Dominant Color with Accent Band (thin accent at bottom)
   - Recommended A or B; noted Option B might look muddy on narrow bars but is more elegant

3. **User:** "I'm interested in seeing what option b looks like"

4. **Claude:** Explored codebase (bar drawing code, WeatherConditionColors, WeatherIconMapper, DayData structure), designed plan with cloud ratio mapping and 3-stop LinearGradient technique, wrote plan to plan file, exited plan mode for approval.

5. **User:** "Write plan to plans/ dir and implement"

6. **Claude:** Copied plan to `plans/260407-gradient-forecast-bars.md`, implemented in 2 files (WeatherConditionColors.kt + DailyForecastGraphRenderer.kt), ran tests (all 695 pass), installed on emulator, took screenshot showing gradient bars on Thu/Fri.


## User Feedback
User was very enthusiastic: "The gradient looks awesome, thanks so much!!! Love it!"
