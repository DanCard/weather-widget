# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **📖 For detailed architecture documentation, see [ARCHITECTURE.md](ARCHITECTURE.md)**
> - Complete system architecture and data flow
> - Two-tier update system design
> - Battery optimization strategies
> - Performance considerations

## Important Guidelines

- **Never clear app data** (`adb shell pm clear`) without explicit user consent. Cached data is valuable for testing and debugging.
- **Debugging workflow**: When investigating widget bugs, proactively pull device logs (`adb logcat`), grab the database from the device (`adb pull`), query the DB, and capture screenshots — don't just read source code.
- **Screenshots**: `adb` can prepend warning text to PNG output, making the file unreadable. Always convert to JPG before reading:
  ```bash
  adb exec-out screencap -p > /tmp/screenshot.png && convert /tmp/screenshot.png /tmp/screenshot.jpg
  ```
  Then read `/tmp/screenshot.jpg` (not the PNG).

## Project Overview

Android weather widget app with resizable widget support and forecast accuracy tracking.

## Weather Data APIs

- **NWS** (National Weather Service) API
- **Open-Meteo** API (free, no API key required)
- Both APIs fetched and stored equally (composite keys allow comparison)
- Widget toggles between sources via tap on API indicator
- User can set API preference in Settings:
  - **Alternate** (default): Pseudo-random initial source (varies daily + by widget ID)
  - **NWS Primary** or **Open-Meteo Primary**: Preferred source with fallback

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

## Widget UI Layout

- **Current temperature**: Top-left corner, large font (30sp)
- **API source indicator**: Top-right corner, clickable to toggle between NWS/Meteo
- **Navigation arrows**: Left/right sides for browsing history (30 days back) and forecast
- **Content area**: Maximized with minimal margins; arrows overlap slightly for more space
- Touch priority: API indicator rendered last (on top) with `clipChildren="false"` for reliable touch handling

## Temperature Display

- **Current temp**: Interpolated from hourly forecasts when not available from API
- **Hourly interpolation**: Smooth temperature transitions between hourly data points
- Update frequency scales with temperature change rate (1-4 updates/hour)

## Forecast Accuracy Tracking

The app tracks forecast accuracy by comparing 1-day-ahead predictions against actual weather:

**Data Collection:**
- Fetches 7 days of actual historical observations from NWS observation stations
  - **Multi-station fallback**: Tries up to 5 nearby stations when nearest station has missing data
  - **Station caching**: Station lists cached for 24 hours to reduce API calls
  - **Station tracking**: `stationId` stored in database for transparency and debugging
- Saves 1-day-ahead forecast snapshots daily (before 8pm cutoff)
- Stores forecasts from both NWS and Open-Meteo for comparison

**Important**: Forecast history requires continuous operation:
- Day 1: App saves forecast for Day 2
- Day 2: Can display Day 1's forecast vs actual (yesterday's history)
- Clearing app data destroys historical forecast snapshots

**Accuracy Metrics (30-day lookback):**
- Separate high/low temperature error tracking
- Directional bias (e.g., "forecasts run 2° high on average")
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
- `AccuracyCalculator.kt` - Calculates accuracy statistics with separate high/low and bias
- `ForecastSnapshotEntity.kt` - Database entity for forecast snapshots
- `HourlyForecastEntity.kt` - Database entity for hourly temperature data
- `TemperatureInterpolator.kt` - Interpolates current temp between hourly data points
- `TemperatureGraphRenderer.kt` - Renders graphical temperature bars with scaling fonts
- `StatisticsActivity.kt` - Detailed accuracy breakdown UI

## Data Retention

- Retain historical weather data for 1 month (automatic cleanup)
- Forecast snapshots also retained for 1 month
- Widget navigation allows browsing up to 30 days of history

## Database Schema

- **Version**: 9 (last updated: 2026-02-02)
- **WeatherEntity**: Main weather data table
  - Composite primary key: `(date, source)` to store both NWS and Open-Meteo data
  - `stationId` field (nullable): NWS observation station ID (e.g., "KSFO") - only populated for actual observations (`isActual = true`)
- **ForecastSnapshotEntity**: Historical forecast predictions
  - Composite primary key: `(targetDate, forecastDate, locationLat, locationLon, source)`
- **HourlyForecastEntity**: Hourly temperature data for interpolation
  - `temperature` field: Float (changed from Int in migration 6→7)
- **Migration path**: Supports migrations from version 1-8

## Update Strategy

**Two-Tier System**: Separates UI updates (current temp) from data fetches (API calls) for optimal battery efficiency.

**Quick Reference:**

| Update Type | Frequency | Wakeup | Purpose |
|-------------|-----------|--------|---------|
| Current Temp UI | 15-60 min (temp-based) | No (opportunistic) | Update interpolated temp from cache |
| Data Fetch | 60-480 min (battery-aware) | Yes (controlled) | Fetch from APIs |
| User Interaction | Immediate | N/A | Instant UI + conditional fetch |
| Screen Unlock | Immediate | N/A | UI update + fetch if charging & stale |

**Data Fetch Intervals** (battery-aware via WorkManager):

| Condition | Interval |
|-----------|----------|
| Plugged in | 60 min |
| Battery > 50% | 120 min |
| Battery 20-50% | 240 min |
| Battery < 20% | 480 min |

**Key Points:**
- Zero independent wakeups for UI updates (opportunistic only)
- User interactions always provide instant feedback from cache
- Background fetches only when data is stale (>30 min old)
- Current temp interpolated from hourly forecasts (no network required)

See [ARCHITECTURE.md](ARCHITECTURE.md) for complete update system design.

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
