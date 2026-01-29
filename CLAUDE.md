# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android weather widget app with resizable widget support. Currently in early development (requirements phase).

## Weather Data APIs

- **Primary**: NWS (National Weather Service) API
- **Fallback**: Open-Meteo API (free, can switch dynamically)

## Widget Sizing Behavior

| Size | Display |
|------|---------|
| 1x1 | Forecast high for today (+ current temp if space allows) |
| 1x3 | Yesterday, today, tomorrow (text only - skip graphs at 1 row height) |
| 2x3 | Same as 1x3 but graphical |
| 4+ cols | Add forecast days (4 cols = 2 forecast days, 5 cols = 3 forecast days, etc.) |

**Graphical display**: Bar showing high/low temperature range for each day.

## Key Requirements

- Display yesterday's actual data alongside predictions
- Graphical display when widget size permits
- Location via GPS or zip code (default: Google HQ)
- Visual style: Apple glass aesthetic

## Data Retention

- Retain historical weather data for 1 month
- Current requirements only need 1 day of history (yesterday's actual data)

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
| API failure | Try fallback API (Open-Meteo); if both fail, show cached data with error indicator |
| No data available | Display "Tap to configure" message |

## Build Requirements

- **Java**: Requires Java 21 (Java 25 is not compatible with Gradle 8.5)
- Build with: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
- Available emulators: `Generic_Foldable_API36`, `Medium_Phone_API_36`
