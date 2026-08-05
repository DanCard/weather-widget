# Detailed Header Setting

## Summary

Add a user-facing setting ("Show detailed header") that, when enabled, replaces the
plain date text in the daily forecast header with a two-line cluster showing:
- Line 1: date + dominant station name + observation age
- Line 2: day-over-day delta (today's actual vs yesterday's actual)

## Data Plumbing

### 1. Track dominant station during blend

In `ActualTemperatureSeriesBuilder.blendObservationSeries()`, alongside
`computeWeightedBlend`, track which station contributed the highest weight to the
most recent emitted point. Return as `dominantStation: BlendContribution?` on
`BlendObservationResult`.

### 2. Bubble through resolution chain

```
ActualsAggregator.resolveCurrentObservation()
  → return dominant station alongside Triple<Float, Long, Long>
  → CurrentTempResolver (app module wrapper)
  → DailyViewHandler via ObservationData
```

### 3. Day-over-day delta

Already available: `dailyActuals[yesterday]` in `DailyViewHandler`. Compute
`yesterdayActual.highTemp - todayWeather.highTemp` (or today's actual high so far).

## Settings

### Android

- `WidgetStateManager` preference key: `KEY_DETAILED_HEADER` (default false)
- `SettingsActivity`: toggle switch in the "Display" section
- Read on every render via `stateManager.isDetailedHeaderEnabled(appWidgetId)`

### Desktop

- `DesktopConfig` field: `detailedHeader: Boolean` (default false)
- Settings panel toggle
- Stored in `config.json`

## Header Rendering

### Android (`DailyViewHandler` / `DailyForecastHeaderRenderer`)

When detailed header enabled:
- Center cluster becomes two lines (smaller text) instead of one
- Line 1: "Tue 4 · KBFI 12m" — date + station name + age minutes
- Line 2: "vs yest +3°" — compact day-over-day delta
- Fallback when no station info: just show date + delta on one line

Fallback when no dominant station: show date only (standard mode)
Fallback when no yesterday actual: omit day-over-day delta

### Desktop (`WidgetHeader` composable)

Same logic, same rendering, same config key for consistency.

## Bar compaction

Only if the second header line would overlap with the top of the forecast bars:
reduce vertical space allocated to bars by ~0.3 row equivalent. Apply only when
the setting is on and the widget has 2–3 rows (where space is tight).
