# OWM Partial Trailing Day & Climate-Normal-High Split Bar

## Evidence

- Desktop DB (`~/.local/share/weather-widget/weather.db`), Scottsdale AZ (`33.645, -111.911`), latest `OPEN_WEATHER_MAP` fetch (`2026-11-24 08:35:06`):
  - `forecasts` for `2026-11-29` (Sunday, D+5): `highTemp = 57.5`, `lowTemp = 54.4` (compared to NWS `73°/54°`, Silurian `71.5°/46.4°`, Open-Meteo `67.8°/60.9°`).
  - `hourly_forecasts` for `OPEN_WEATHER_MAP` on `2026-11-29` only cover `00:00`–`07:00` local (3-hour slots `02:00 = 57.5°`, `05:00 = 54.4°`, `08:00 = 55.6°`).
  - Same pattern occurred the previous day for `2026-11-28`: `05:12` and `09:34` fetches stored `59.2° / 55.3°` before afternoon fetches reached midday/afternoon (`70.2°` at `12:04`, `72.0°` at `14:44`).

## Root Cause

1. OpenWeatherMap `/data/2.5/forecast` returns 40 three-hour slots (120 hours). Starting mid-day, 120 hours spans 6 calendar days, so the trailing day (D+5) only contains overnight/early-morning slots until afternoon.
2. `OpenWeatherMapApi.buildDailyForecasts` unconditionally sets `highTemp = dayPeriods.maxOf { it.temp }` for every grouped date, turning a 2 AM temperature (`57.5°`) into the day's forecast high.
3. For a terminal low-only future day (`highTemp == null && lowTemp != null`), neither platform currently fills `highTemp` from the climate normal while keeping the real forecast `lowTemp` and rendering the top half of the bar green.

## Proposed Changes

### 1. `:shared` — OWM trailing-day bounds (`OpenWeatherMapApi.kt`)
- In `buildDailyForecasts(periods)`:
  - Keep the first day (`date == firstDate`, today) unchanged (`SameDayExtremeCutoff` + `PartialForecastDays.todayRow` govern today).
  - For any subsequent day (`date != firstDate`):
    - `maxHour = dayPeriods.maxOf { it.hourOfDay }`.
    - `maxHour < 5`: neither morning low (`05:00`/`06:00`/`07:00` across the three 3-hour UTC alignments) nor afternoon high is covered — omit the day so climate normals fill both ends.
    - `maxHour in 5..14`: morning low is covered, afternoon high (`15:00`/`16:00`/`17:00`) is not — emit `DailyForecast` with `highTemp = null`, `lowTemp = lowTemp`.
    - `maxHour >= 15`: both morning low and afternoon high are covered — emit both `highTemp` and `lowTemp`.

### 2. `:shared` — Terminal low-only climate-normal high (`ForecastTypes.kt`, `PartialForecastDays.kt`, `DailyColumnBuilder.kt`)
- Add `val isClimateNormalHigh: Boolean = false` to `DailyForecast` (in-memory only; no DB schema change — SQLite stores `highTemp = NULL, lowTemp = <forecast>`).
- Add `WeatherColors.CLIMATE_NORMAL_GREEN = 0xFF34C759.toInt()` (shared constant for both renderers).
- Update `PartialForecastDays`:
  - Generalize terminal low-only detection (`isTerminalLowOnlyFutureDay`) to apply to any source's last non-climate future day with `high == null && low != null` (covers both OWM's trailing morning-only day and NWS's terminal low-only day).
  - In `futureFromNormals` / `fillPartialFutureDays`: when a future day is a terminal low-only day and a climate normal exists for `MonthDay.from(date)`, fill `highTemp = normal.first`, keep the real forecast `lowTemp`, keep `isClimateNormal = false`, and set `isClimateNormalHigh = true`. Non-terminal partial future days continue to replace both high and low with climate normals (`isClimateNormal = true`).
- Carry `val isClimateNormalHigh: Boolean = false` onto `DailyColumn` from `forecast?.isClimateNormalHigh == true`.

### 3. `:shared`, `:desktop`, `:app` — Green top half on the forecast bar
- **`AdaptiveBarStrokes.plan(...)`** (`:shared`):
  - Add `climateNormalTopHalf: Boolean = false`.
  - When `true`, allocate the top 50% of the bar to `WeatherColors.CLIMATE_NORMAL_GREEN` (`0xFF34C759`) and scale the forecast sky (`clear`/`cloud`/`rain`) into the bottom 50%, using the existing `BLEND_STEPS` edge blend at the midpoint.
- **Desktop (`DailyForecastGraph.kt`)**:
  - Pass `climateNormalTopHalf = day.isClimateNormalHigh` into `drawAdaptiveBar` → `AdaptiveBarStrokes.plan(...)`.
- **Android (`DailyViewHandler.kt`, `DailyViewLogic.kt`, `DailyFutureDayResolver.kt`, `DailyForecastGraphRenderer.kt`, `DailyBarRenderer.kt`)**:
  - In `DailyViewHandler.buildWeatherByDate`: do not replace a terminal low-only `displaySource` future row with `GENERIC_GAP`, so `PartialForecastDays.fillPartialFutureDays` can pair its real low with the climate-normal high.
  - Carry `isClimateNormalHigh` on `DailyForecastGraphRenderer.DayData` and pass `climateNormalTopHalf = day.isClimateNormalHigh` to `AdaptiveBarStrokes.plan(...)` in `DailyBarRenderer.kt`.

### 4. Tests & Verification
- Unit tests:
  - `OpenWeatherMapApiTest`: trailing day `< 05:00` omitted, `05:00..14:00` emits `highTemp = null, lowTemp = <min>`, `>= 15:00` emits both (across timezone alignments).
  - `PartialForecastDaysTest`: terminal low-only future day (OWM & NWS) fills `highTemp = normal.first` with `isClimateNormalHigh = true` and preserves real `lowTemp`; non-terminal low-only day still falls back to full climate normal.
  - `AdaptiveBarStrokesTest`: `climateNormalTopHalf = true` paints green over the top half and forecast sky over the bottom half.
  - Update `DailyViewLogicTest` for the new terminal low-only behavior (climate-normal high + real low + `isClimateNormalHigh = true`).
- Integration test:
  - End-to-end `OpenWeatherMapApi` → `ForecastPersistRun` → `DesktopWeatherDao` → `PartialForecastDays.fillPartialFutureDays` → `DailyColumnBuilder` verifying that a morning-only trailing OWM day persists `highTemp = NULL, lowTemp = 54.4` and builds a `DailyColumn` with climate-normal high, real forecast low, and `isClimateNormalHigh = true`.
- Runtime verification & scrub:
  - Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
  - After deploying the new desktop binary, delete the already-stored bad `2026-11-29` OWM row (`fetchedAt = 2026-11-24 08:35:06`) from `~/.local/share/weather-widget/weather.db` and trigger a refresh so desktop immediately displays Sunday with the climate-normal high (green top half) and OWM's real morning low (`54°`).
