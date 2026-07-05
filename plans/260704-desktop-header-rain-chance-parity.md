# Plan: Desktop Header Rain Chance Parity with Android

## Overview
Currently, the Desktop app header does not show the rain chance percentage when rain is expected later in the day or when hourly data for the current single hour has 0% rain chance. On Android, the header calculates precipitation chance using a rolling **next 8-hour window** with minute-level interpolation, falling back to the daily forecast probability if hourly data is sparse.

This plan details updating the Desktop `WidgetHeader` in `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` to use the shared `PrecipProbabilityCalculator` from `:shared` and establishing a comprehensive testing regimen to verify all edge cases.

---

## Root Cause
In `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` (`WidgetHeader`), rain chance is currently computed as:

```kotlin
val currentHourData = forecast.hourly.find {
    it.dateTime >= nowEpoch - 3_600_000L && it.dateTime <= nowEpoch + 3_600_000L
}
val precipProb = currentHourData?.precipProbability?.takeIf { it > 0 }
```

### Issues:
1. **Single-Hour Window**: If current time is 2:00 PM (0% rain) and rain starts at 3:00 PM or 4:00 PM (70% chance), the desktop header displays `null` (no rain percentage).
2. **No Daily Fallback**: If hourly forecast data is missing or incomplete for the current hour, it never checks the daily forecast precipitation probability (`fallbackDailyProbability`).

---

## Technical Design

### 1. Header Calculation Update in `Main.kt`
Replace the single-hour find with `PrecipProbabilityCalculator.getNext8HourPrecipProbability(...)` from `:shared`:

```kotlin
val todayForecast = remember(forecast.daily, nowLocal) {
    forecast.daily.firstOrNull { it.date == nowLocal.toLocalDate() }
}

val precipProb = remember(forecast.hourly, displaySource, todayForecast, nowLocal) {
    PrecipProbabilityCalculator.getNext8HourPrecipProbability(
        hourlyForecasts = forecast.hourly,
        displaySourceId = displaySource.id,
        fallbackSourceId = WeatherSource.GENERIC_GAP.id,
        fallbackDailyProbability = todayForecast?.precipProbability,
        referenceTime = nowLocal
    )?.takeIf { it > 0 }
}
```

### 2. Display and Interactivity
- **Display Condition**: `precipProb != null && precipProb > 0`
- **Text Format**: `"$precipProb%"` in cyan text (`Color(0xFF4FC3F7)`)
- **Click Behavior**: Clicking the text toggles `viewMode` to `ViewMode.PRECIPITATION`.

---

## Testing Regimen

We will add a rigorous suite of automated tests in `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopUiTest.kt` (or a dedicated `DesktopHeaderPrecipTest.kt`) covering the following test scenarios:

### Test Suite Matrix

| Scenario | Inputs / Data State | Expected Result |
| :--- | :--- | :--- |
| **Current hour rain (Classic)** | Current hour has 80% rain chance | Header displays `"80%"` |
| **Upcoming rain (Next 8h window)** | Current hour = 0%, +3h = 70% | Header displays `"70%"` |
| **Peak probability selection** | Current hour = 30%, +2h = 85%, +5h = 40% | Header displays `"85%"` |
| **No rain in 8h window** | 0% across all 24 hours | Header hides rain chance text (`null`) |
| **Sparse hourly data with Daily Fallback** | `hourly` list empty, daily forecast `precipProbability = 45%` | Header falls back to `"45%"` |
| **Sparse hourly data with 0% Daily** | `hourly` list empty, daily forecast `precipProbability = 0%` | Header hides rain chance text (`null`) |
| **8-hour window boundary (+7.5h vs +9h)** | Rain at +7.5h = 60%, Rain at +9h = 95% | Header displays `"60%"` (excludes +9h outside window) |
| **Source filtering & fallback** | Active source missing precip, fallback `GENERIC_GAP` source has 50% | Header displays `"50%"` |
| **Daily View Header Parity** | `viewMode = ViewMode.DAILY`, upcoming rain = 65% | Header displays `"65%"` |
| **Hourly View Header Parity** | `viewMode = ViewMode.HOURLY`, upcoming rain = 65% | Header displays `"65%"` |
| **Header Rain Click Interaction** | Click on `"70%"` rain percentage | Toggles `config.viewMode` to `ViewMode.PRECIPITATION` |

---

## Execution & Verification Protocol

### Step 1: Pre-Implementation Baseline Run
Run existing test suite to confirm clean state:
```bash
./gradlew :shared:test :desktop:test
```

### Step 2: Implementation of Tests & Header Code
1. Update `WidgetHeader` in `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`.
2. Implement new unit test methods in `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopUiTest.kt`.

### Step 3: Automated Verification
Run full unit test suites:
```bash
./gradlew :desktop:test
./gradlew :shared:test
```

### Step 4: Visual & Interactive Inspection on Desktop App
Launch the desktop app locally:
```bash
./gradlew :desktop:run
```
Verification steps:
1. Verify that when rain is expected in the next 8 hours, the rain percentage is visible in the header next to the current temperature.
2. Click the rain percentage text in the header and verify it switches the main view to the Precipitation graph.
3. Switch between Daily View (`ViewMode.DAILY`) and Hourly View (`ViewMode.HOURLY`) and confirm rain chance is consistently rendered in both view modes.
