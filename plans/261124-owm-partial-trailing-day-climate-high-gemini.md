# OWM Partial Trailing Day & Climate-Normal-High Split Bar (Merged Plan)

Supersedes the earlier drafts ([261124-owm-partial-trailing-day-climate-high-opus.md](261124-owm-partial-trailing-day-climate-high-opus.md) and [261124-partial-high-days-climate-normal-top-half-neon.md](261124-partial-high-days-climate-normal-top-half-neon.md)) by combining the best parts of both and maximizing shared `:shared` logic.

## Evidence

- Desktop DB (`~/.local/share/weather-widget/weather.db`), Scottsdale AZ (`33.645, -111.911`), latest `OPEN_WEATHER_MAP` fetch (`2026-11-24 08:35:06`):
  - `forecasts` for `2026-11-29` (Sunday, D+5): `highTemp = 57.5`, `lowTemp = 54.4` (compared to NWS `73°/54°`, Silurian `71.5°/46.4°`, Open-Meteo `67.8°/60.9°`).
  - `hourly_forecasts` for `OPEN_WEATHER_MAP` on `2026-11-29` only cover `00:00`–`07:00` local (3-hour slots `02:00 = 57.5°`, `05:00 = 54.4°`, `08:00 = 55.6°`).
  - Same pattern occurred at `37.417, -122.089` (OWM `56.2°` high and `5%` rain vs. `60–65°` and `68–81%` rain) and the previous day for `2026-11-28` (`59.2° / 55.3°` at `05:12` and `09:34` before jumping to `70.2°` at `12:04` and `72.0°` at `14:44`).

## Root Cause

1. OpenWeatherMap `/data/2.5/forecast` returns 40 three-hour slots (120 hours). Starting mid-day, 120 hours spans 6 calendar days, so the trailing day (D+5) only contains overnight/early-morning slots until afternoon.
2. `OpenWeatherMapApi.buildDailyForecasts` unconditionally computes `highTemp = dayPeriods.maxOf { it.temp }` and whole-day `precipProbability` / `precipAmountMm` over those truncated morning slots.
3. For a future day missing its high (`highTemp == null && lowTemp != null`), `PartialForecastDays.fillPartialFutureDays` currently either replaces *both* high and low with climate normals (all non-NWS sources) or leaves `highTemp = null` (NWS terminal day), instead of pairing the real forecast low with the climate-normal high and drawing the top half of the bar green.

## User Decision (2026-11-24)

A future day with a real forecast low and no real forecast high keeps its real low, takes the **climate-normal high**, and renders the **top half of its forecast bar green**.

## Proposed Changes

### 1. `:shared` — OWM trailing-day cutoff (`OpenWeatherMapApi.kt`)
- In `buildDailyForecasts(periods)`:
  - Keep the first day (`date == firstDate`, today) unchanged (`SameDayExtremeCutoff` + `PartialForecastDays.todayRow` govern today).
  - For any subsequent day (`date != firstDate`):
    - `maxHour = dayPeriods.maxOf { it.hourOfDay }`.
    - `maxHour < 5`: neither the morning low (`05:00`/`06:00`/`07:00` across all three 3-hour UTC alignments) nor the afternoon high is covered — omit the day so climate normals fill both ends.
    - `maxHour in 5..14`: morning low is covered, afternoon high (`15:00`/`16:00`/`17:00`) and afternoon precip are not — emit `DailyForecast` with `highTemp = null`, `lowTemp = lowTemp`, `precipProbability = null`, `precipAmountMm = null`, and log `OWM_TRUNCATED_LAST_DAY date=… lastSlotHour=…`.
    - `maxHour >= 15`: both morning low and afternoon high are covered — emit the complete row as before.

### 2. `:shared` — Shared partial-high rule, color constant, and column model
- **`ForecastTypes.kt`**: add in-memory `val isClimateNormalHigh: Boolean = false` to `DailyForecast` (no DB schema change; SQLite stores `highTemp = NULL, lowTemp = <forecast>`).
- **`WeatherColors.kt`**: move the climate-normal bar green (`0xFF34C759.toInt()`) to a single shared constant `CLIMATE_NORMAL_GREEN` used by both desktop (`DailyForecastGraph`) and Android (`DailyBarRenderer` / `DailyGraphPaintCache`).
- **`PartialForecastDays.kt`**:
  - Replace the NWS-only `isTerminalLowOnlyNwsFutureDay` exemption with the uniform partial-high rule: for any future day (`date.isAfter(today)`) with `highTemp == null && lowTemp != null`:
    - When a climate normal exists for `MonthDay.from(date)`, fill `highTemp = normal.first`, keep the real forecast `lowTemp`, keep `isClimateNormal = false`, and set `isClimateNormalHigh = true`.
    - When no climate normal exists, keep `highTemp = null, lowTemp = day.lowTemp`.
  - Future days missing their low (`highTemp != null && lowTemp == null`) keep the existing full climate-normal fallback (`isClimateNormal = true`).
- **`DailyColumnBuilder.kt` (`DailyColumn`)**:
  - Carry `val isClimateNormalHigh: Boolean = forecast?.isClimateNormalHigh == true`.
  - Expose shared label-rounding rules on `DailyColumn` so both platforms agree:
    - `printsWholeHighDegrees`: `printsWholeDegrees && !isClimateNormalHigh` (climate-normal high prints tenths, matching full climate-normal columns).
    - `printsWholeLowDegrees`: `printsWholeDegrees` (real forecast low prints whole degrees).

### 3. `:shared` — Blended green top half in `AdaptiveBarStrokes.kt`
- Add `climateNormalTopHalf: Boolean = false` to `AdaptiveBarStrokes.plan(...)`.
- When `true`, allocate the top 50% of the bar to `WeatherColors.CLIMATE_NORMAL_GREEN` and scale the forecast sky (`clear`, `cloud`, `rain`) into the bottom 50%. The existing `segments` + `BLEND_STEPS` loop produces a smooth transition at the midpoint and preserves partly-cloudy gold/grey/rain proportions in the bottom half.
- Both Android (`DailyBarRenderer`) and desktop (`DailyForecastGraph`) already stroke `plan.strokes` in order, so neither duplicates any bar geometry.

### 4. Minimal platform wiring (`:desktop` and `:app`)
- **Desktop (`DailyForecastGraph.kt`)**:
  - Pass `climateNormalTopHalf = day.isClimateNormalHigh` into `drawAdaptiveBar` → `AdaptiveBarStrokes.plan(...)`.
  - Format high/low labels with `day.printsWholeHighDegrees` / `day.printsWholeLowDegrees`.
  - Reference `WeatherColors.CLIMATE_NORMAL_GREEN`.
- **Android (`DailyViewHandler.kt`, `DailyViewLogic.kt`, `DailyForecastGraphRenderer.kt`, `DailyBarRenderer.kt`, remove `DailyFutureDayResolver.kt`)**:
  - In `DailyViewHandler.buildWeatherByDate`: do not replace a `displaySource` future row that has `highTemp == null && lowTemp != null` with `GENERIC_GAP`, so `PartialForecastDays.fillPartialFutureDays` can pair its real low with the climate-normal high.
  - Remove `DailyFutureDayResolver.kt` (no longer needed once `PartialForecastDays` handles all partial-high future days uniformly); update `DailyViewLogic` `hasData` to accept `highTemp == null && lowTemp != null` future rows.
  - Carry `isClimateNormalHigh` (and `printsWholeHighDegrees` / `printsWholeLowDegrees`) on `DailyForecastGraphRenderer.DayData`.
  - Pass `climateNormalTopHalf = day.isClimateNormalHigh` to `AdaptiveBarStrokes.plan(...)` in `DailyBarRenderer.kt`, and reference `WeatherColors.CLIMATE_NORMAL_GREEN`.

### 5. Living Docs
- Update `arch/forecast-and-observation-cadence.md` to document the OWM truncated trailing-day cutoff (`< 05:00` omitted, `05:00–14:00` low-only, `>= 15:00` complete) and the shared partial-high future-day rule (climate-normal high + green top half).

### 6. Tests & Verification
- **Unit tests**:
  - `OpenWeatherMapApiTest`: trailing day `< 05:00` omitted; `05:00..14:00` emits `highTemp = null`, real `lowTemp`, `precipProbability = null`, `precipAmountMm = null`; `>= 15:00` emits complete row (tested across timezone alignments).
  - `PartialForecastDaysTest`: partial-high future day (OWM, NWS, etc.) fills `highTemp = normal.first` with `isClimateNormalHigh = true` and preserves real `lowTemp`; low-missing future day still falls back to full climate normal; missing climate normal preserves `highTemp = null, lowTemp = real`.
  - `AdaptiveBarStrokesTest`: `climateNormalTopHalf = true` allocates the top 50% to `WeatherColors.CLIMATE_NORMAL_GREEN` and scales the sky into the bottom 50% with blended midpoint edges.
  - `DailyViewLogicTest` & `DailyViewHandler` tests: partial-high future rows survive `buildWeatherByDate`, carry `isClimateNormalHigh = true`, and format high with tenths / low in whole degrees.
- **Parity & integration tests**:
  - Extend `:shared` `DailyParity` fixture to cover a partial-high future day across both platforms.
  - End-to-end integration test: `OpenWeatherMapApi` → `ForecastPersistRun` → `DesktopWeatherDao` → `PartialForecastDays.fillPartialFutureDays` → `DailyColumnBuilder` verifying `highTemp = NULL, lowTemp = 54.4` is persisted and renders with climate-normal high, real low, and `isClimateNormalHigh = true`.
- **Gate, deploy & runtime check**:
  - Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
  - After deploying the new desktop binary, scrub the already-stored bad `2026-11-29` OWM row (`fetchedAt = 2026-11-24 08:35:06`) from `~/.local/share/weather-widget/weather.db` and refresh so desktop immediately displays Sunday with the climate-normal high (green top half) and OWM's real morning low.
  - Verify visually on desktop (OWM Sunday) and emulator (NWS terminal day).
