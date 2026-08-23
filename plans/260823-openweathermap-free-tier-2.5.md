# Plan: Migrate OpenWeatherMap to Free 2.5 Endpoints (/weather and /forecast)

## Overview
Currently, `OpenWeatherMapApi` calls the One Call 3.0 endpoint (`https://api.openweathermap.org/data/3.0/onecall`), which requires an active credit card and subscription plan even for the free call allotment.

This plan migrates `OpenWeatherMapApi` in `:shared` to use OpenWeatherMap's completely free 2.5 API endpoints:
1. `GET /data/2.5/weather` — Real-time current conditions (temperature, condition, timestamp).
2. `GET /data/2.5/forecast` — 5-day / 3-hour forecast (40 periods: temperature, pop, rain/snow, cloud cover, conditions).

These endpoints require only a standard free OpenWeatherMap API key with **no credit card**.

---

## Technical Design

### 1. API Endpoints and Fetch Flow
- `BASE_URL_CURRENT`: `https://api.openweathermap.org/data/2.5/weather`
- `BASE_URL_FORECAST`: `https://api.openweathermap.org/data/2.5/forecast`

In `OpenWeatherMapApi.getForecast(lat: Double, lon: Double): RawFetch`:
1. Validate presence of `apiKey`.
2. Fetch `/data/2.5/weather` and `/data/2.5/forecast` concurrently.
3. **Parse Current Conditions** (`/data/2.5/weather`):
   - `providerCurrentTemp` = `main.temp`
   - `providerCurrentCondition` = `weather[0].main`
   - `providerCurrentObservedAt` = `dt * 1000L`
4. **Parse 5-Day / 3-Hour Forecast** (`/data/2.5/forecast`):
   - Timezone offset: `city.timezone` (seconds from UTC).
   - Hourly Mapping:
     - Map each 3-hour item to `HourlyForecast` (`dateTime = dt * 1000L`, `temperature = main.temp`, `condition = weather[0].main`, `precipProbability = (pop * 100).toInt()`, `precipAmountMm = (rain["3h"] + snow["3h"])`, `cloudCover = clouds.all`, `source = "OPEN_WEATHER_MAP"`).
     - Linearly interpolate intermediate hourly points between 3-hour entries to provide smooth 1-hour resolution for temperature and graph rendering.
   - Daily Mapping:
     - Group 3-hour periods by local calendar date (`YYYY-MM-DD`).
     - `highTemp` = max temperature across the day's periods.
     - `lowTemp` = min temperature across the day's periods.
     - `precipProbability` = max `pop` percentage across the day.
     - `precipAmountMm` = sum of `rain["3h"]` and `snow["3h"]` for the day.
     - `condition` = representative condition (midday 12:00-15:00 period, or highest precipitation/severity condition).
     - `iconToken` = corresponding icon token.
5. Return `RawFetch(hourly = hourlyList, daily = dailyList, providerCurrentTemp = currentTemp, providerCurrentCondition = currentCondition, providerCurrentObservedAt = currentObservedAt)`.

### 2. Dual-Platform Parity
- The API client lives in `:shared` (`shared/src/main/kotlin/com/weatherwidget/data/remote/OpenWeatherMapApi.kt`), which is consumed directly by:
  - **Android (`:app`)**: `ForecastFetchCoordinator.kt` and `CurrentTempRepository.kt`.
  - **Linux Desktop (`:desktop`)**: `DesktopWeatherService.kt`.
- Both platforms immediately benefit without duplicating parsing logic.

### 3. Error Handling
- Preserve HTTP status checking and `ApiAccessException` throwing for 401 (invalid/missing API key) and 429 (rate limits).

---

## Proposed Changes

### `:shared`
- **Modify** `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenWeatherMapApi.kt`:
  - Replace `https://api.openweathermap.org/data/3.0/onecall` with concurrent requests to `/data/2.5/weather` and `/data/2.5/forecast`.
  - Implement JSON parsing and data aggregation for current, daily, and hourly structures.

### Tests
- **Update** `app/src/test/java/com/weatherwidget/data/remote/OpenWeatherMapApiTest.kt`:
  - Mock responses for both `/data/2.5/weather` and `/data/2.5/forecast`.
  - Test daily high/low and precipitation aggregation from 3-hour intervals.
  - Test current conditions extraction.
  - Test error handling (401/429/invalid key).

---

## Verification Plan

### Automated Tests
```bash
./gradlew :shared:test
./gradlew :app:testShortDebugUnitTest --tests com.weatherwidget.data.remote.OpenWeatherMapApiTest
./gradlew test
```

### Build Verification
```bash
./gradlew assembleDebug
./gradlew :desktop:createDistributable
```
