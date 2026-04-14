# Tomorrow.io Integration Plan

## Objective
Add Tomorrow.io as a new weather API provider (`WeatherSource.TOMORROW_IO`) to the Weather Widget project, enabling high-accuracy, hyper-local forecasting and extending the widget's API comparison capabilities. 

## Key Files & Context
1. **`app/build.gradle.kts`**: Inject the API key into `BuildConfig`.
2. **`app/src/main/java/com/weatherwidget/data/model/WeatherSource.kt`**: Register `TOMORROW_IO` as a new enum value.
3. **`app/src/main/java/com/weatherwidget/data/remote/TomorrowIoApi.kt`**: Create the new Ktor-based API client.
4. **`app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`**: Wire the new API client into the data fetching logic.
5. **`app/src/main/java/com/weatherwidget/di/NetworkModule.kt`**: Register `TomorrowIoApi` with Hilt.
6. **`app/src/main/java/com/weatherwidget/widget/handlers/ApiSourceWarningHelper.kt`**: Add `TOMORROW_IO` to the list of APIs that require an API key.

## Implementation Steps

### 1. API Key Configuration
- Add `TOMORROW_IO_API_KEY` to `local.properties` (the user has provided key `NBRkGyM2NDWnGPJMjeOrzQfU84OmrC6T`).
- Update `app/build.gradle.kts` to read `TOMORROW_IO_API_KEY` from `local.properties` and expose it via `BuildConfig.TOMORROW_IO_API_KEY`.

### 2. Domain Model Updates
- Update `WeatherSource.kt`:
  ```kotlin
  TOMORROW_IO(
      id = "TOMORROW_IO",
      displayName = "Tomorrow.io",
      shortDisplayName = "Tmrw",
  )
  ```
- Update `ApiSourceWarningHelper.kt` to ensure `requiresApiKey()` returns `true` for `WeatherSource.TOMORROW_IO`.

### 3. Create `TomorrowIoApi.kt`
- Create a new Ktor client matching the pattern of `OpenWeatherMapApi` and `VisualCrossingApi`.
- Construct requests to `/v4/timelines` with `timesteps=1h` (for hourly) and `timesteps=1d` (for daily).
- Include `cloudCover`, `temperature`, `precipitationProbability`, `precipitationSum`, and `weatherCode` in the `fields` parameter.
- Implement a `mapWeatherCode(code: Int): String` function to translate Tomorrow.io codes (e.g., 1000 = Clear, 1102 = Mostly Cloudy) to the widget's internal condition string matrix to ensure icons render correctly.

### 4. Repository Integration
- Inject `TomorrowIoApi` into `ForecastRepository`.
- In `ForecastRepository.fetchFromSource()`, add a branch for `WeatherSource.TOMORROW_IO`:
  - Call `tomorrowIoApi.getForecast()`.
  - Convert the returned models into `ForecastEntity` and `HourlyForecastEntity`.
  - Ensure the timestamps (which Tomorrow.io returns in UTC/ISO8601 format) are correctly parsed into `epochMilli` using `ZoneId.systemDefault()`.

### 5. Validation & Testing
- Write `TomorrowIoApiTest.kt` to verify JSON parsing using a mocked `HttpClient`.
- Manually configure the widget to use the Tomorrow.io source and verify:
  1. The API fetch succeeds and `app_logs` records the fetch.
  2. The Daily and Hourly graphs render correctly without exceptions.
  3. The weather icons map properly based on the `weatherCode` translation.

## Verification
- Run `./scripts/unit-tests.sh` to ensure no existing tests are broken.
- Ensure the newly added `TomorrowIoApiTest` passes.
- Switch the widget to "Tomorrow.io" on the emulator and verify UI stability (e.g., no overlapping labels, smooth temperature curves).