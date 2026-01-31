# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android weather widget app with resizable widget support and forecast accuracy tracking.

## Weather Data APIs

- **NWS** (National Weather Service) API
- **Open-Meteo** API (free, no API key required)
- Both APIs fetched and stored equally (composite keys allow comparison)
- Widget toggles between sources; user can set a preferred primary in Settings

## Widget Sizing Behavior

| Size | Display |
|------|---------|
| 1x1 | Forecast high for today (+ current temp if space allows) |
| 1x3 | Yesterday, today, tomorrow (text only - skip graphs at 1 row height) |
| 2x3 | Same as 1x3 but graphical |
| 4+ cols | Add forecast days (4 cols = 2 forecast days, 5 cols = 3 forecast days, etc.) |

**Graphical display**: Bar showing high/low temperature range for each day. Past days can show forecast overlay (yellow bar) for accuracy comparison.

## Key Requirements

- Display yesterday's actual data alongside predictions
- Graphical display when widget size permits
- Location via GPS or zip code (default: Google HQ)
- Visual style: Apple glass aesthetic

## Forecast Accuracy Tracking

The app tracks forecast accuracy by comparing 1-day-ahead predictions against actual weather:

**Data Collection:**
- Fetches 7 days of actual historical observations from NWS observation stations
- Saves 1-day-ahead forecast snapshots daily (before 8pm cutoff)
- Stores forecasts from both NWS and Open-Meteo for comparison

**Accuracy Metrics (30-day lookback):**
- Average absolute error (°F)
- Maximum error
- Percent of days within ±3°F
- Accuracy score (0-5 scale, 5 = perfect)

**Display Modes (configurable in Settings):**
| Mode | Description |
|------|-------------|
| FORECAST_BAR (default) | Yellow bar overlay showing predicted range alongside actual |
| ACCURACY_DOT | Colored dot next to high temp (green ≤2°, yellow ≤5°, red >5°) |
| SIDE_BY_SIDE | Shows "72° (N:68°)" with source abbreviation |
| DIFFERENCE | Shows "72° (N:+4)" with temp difference |
| NONE | No forecast comparison shown |

**Key Files:**
- `AccuracyCalculator.kt` - Calculates accuracy statistics
- `ForecastSnapshotEntity.kt` - Database entity for forecast snapshots
- `StatisticsActivity.kt` - Detailed accuracy breakdown UI

## Data Retention

- Retain historical weather data for 1 month (automatic cleanup)
- Forecast snapshots also retained for 1 month

## Update Frequency

Battery-aware refresh strategy using WorkManager:

| Condition | Interval |
|-----------|----------|
| Plugged in | 30 min |
| Battery > 50% | 1 hour |
| Battery 20-50% | 2 hours |
| Battery < 20% | 4 hours |

- Support manual refresh via tap gesture
- Skip updates when widget is not visible

## Error Handling

| Scenario | Behavior |
|----------|----------|
| No network | Show cached data with "offline" indicator and last update timestamp |
| GPS unavailable | Fall back to last known location or default (Google HQ); display location name |
| API failure | Try other API; if both fail, show cached data with error indicator |
| No data available | Display "Tap to configure" message |

## Build Requirements

- **Java**: Requires Java 21 (use Android Studio's bundled JDK)
- **JAVA_HOME**: Set to `/home/dcar/Downloads/high/android-studio/jbr`
- **Gradle**: Currently using Gradle 8.13
- Build with: `./gradlew installDebug`
- Available emulators: `Generic_Foldable_API36`, `Medium_Phone_API_36`

## Testing the Widget

This is a widget-only app (no launcher activity). To test:

1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. On the emulator/device, long-press the home screen and select "Widgets"
3. Find "Weather Widget" and drag it to the home screen
4. Resize the widget to test different layouts (1x1, 1x3, 2x3, etc.)

Alternatively, use ADB to open the widget picker:
```bash
adb shell am start -a android.appwidget.action.APPWIDGET_PICK
```
