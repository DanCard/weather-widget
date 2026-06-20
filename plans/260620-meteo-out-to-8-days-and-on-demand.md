# Open-Meteo forecast horizon: baseline 8 days + on-demand extension to 16

## Context

On the desktop daily view, today (Sat 2026-06-20) shows **no Open-Meteo forecast for next Saturday (06-27)**.
Root cause: `OpenMeteoApi.getForecast(days = 7)` returns **today + 6 days** (through 06-26). Next Saturday
is exactly 7 days out — the one day that falls off a 7-day window. Both platforms have always passed `7`
(Android explicitly at `ForecastRepository.kt:326`, desktop via the default), so the bug surfaces only when
"today" is the same weekday as the rightmost day the user looks at. NWS reaches 06-27 (it returns ~8 calendar
days), which is why only the Open-Meteo bar is missing.

**Verified live:** Open-Meteo's free `forecast` endpoint allows `forecast_days` **0–16** (17 is rejected:
`"Allowed range 0 to 16"`). So the maximum reach is **16 days** (today + 15).

**Decisions (from the user):**
- Baseline fetch = **8 days** (fixes the immediate edge; keeps routine payload small).
- **On-demand extension** when navigating into the future past the real-forecast edge → fetch the **16-day
  max** once, which unlocks all further forward navigation.
- Apply to **both desktop and Android**.
- Add **integration test(s)**.

NWS and other sources cap near 7 days, so days 8–16 will show Open-Meteo real data (and climate-normal green
bars for sources without that reach — existing `appendClimateNormalGaps`, `GAP_HORIZON_DAYS = 16`). That's
acceptable and matches the user's Open-Meteo-centric ask.

## Approach

### 1. Shared horizon constants + helper (single source of truth)
New `shared/src/main/kotlin/com/weatherwidget/shared/config/ForecastHorizon.kt`:
- `BASELINE_DAYS = 8`, `MAX_DAYS = 16`.
- `fun daysToCover(today: LocalDate, target: LocalDate): Int` = `(target - today) + 1`, coerced to
  `BASELINE_DAYS..MAX_DAYS`. Used by both platforms' on-demand triggers so the math can't drift
  (consistent with the shared-logic preference; mirror the `LocationMatch`/`ContractCases` pattern with a
  small `ForecastHorizonContract` tested on both sides if cheap).

`OpenMeteoApi.getForecast` keeps its `days` parameter but the **call sites** pass `ForecastHorizon.BASELINE_DAYS`
instead of `7`. (Leave the param default as-is or set it to `BASELINE_DAYS`; callers are explicit.)

### 2. Thread `forecastDays` through both fetch pipelines

**Desktop** (`DesktopWeatherService.kt`):
- Add `forecastDays: Int = ForecastHorizon.BASELINE_DAYS` to `fetchForecast(...)` and
  `fetchOpenMeteoForecastWithActuals(...)` (~line 143), passing it as `openMeteo.getForecast(..., days = forecastDays)`.
- `ACTUALS_HISTORY_DAYS` (history/`past_days`) is unrelated — leave it.

**Android** (`ForecastRepository.kt`):
- Replace the literal `7` at line 326 with a `forecastDays` value carried into `getWeatherData(...)` /
  `fetchFromAllApis(...)`, defaulting to `ForecastHorizon.BASELINE_DAYS`.
- Carry the requested days from the worker: add a `KEY_FORECAST_DAYS` input to `WeatherWidgetWorker` /
  `RefreshScheduler.enqueueForcedRefresh(...)`, default baseline.

### 3. On-demand extension — desktop (mirror `ensureHistory`)
- Add `DesktopWeatherRepository.ensureForecastDays(neededDays: Int): Boolean`, **mutex-guarded** like
  `ensureHistory` (`DesktopWeatherRepository.kt:160`, `historyFetchMutex`). It no-ops if the current persisted
  real-forecast horizon already covers `neededDays`; otherwise calls
  `weatherService.fetchForecast(forecastDays = neededDays)`, persists, returns whether new data landed.
- Wire a UI callback in `Main.kt` daily-nav handlers (~786–839, beside the existing hourly `onNeedHistory`
  at ~240): when a right pan/arrow reaches the real-forecast edge, launch `ensureForecastDays(MAX_DAYS)` in
  `uiScope`, then the existing forecast-state recompute repaints (`DesktopDailyForecastModel.build` →
  `canNavigateRight` opens up). Reuse the in-flight toast pattern (`historyFetchToast`).

### 4. On-demand extension — Android (reuse `enqueueForcedRefresh`)
- In `WidgetIntentRouter.handleDailyNavigation` (~177–226), after computing the new rightmost visible date
  (`NavigationUtils.getVisibleDateRange`), if it exceeds the cached real-forecast `maxDate`, call
  `RefreshScheduler.enqueueForcedRefresh(context, forecastDays = ForecastHorizon.daysToCover(today, newRightmost))`.
  This reuses the exact mechanism `handleToggleApi` (~373) already uses for missing-source data.
- The worker passes `forecastDays` into `ForecastRepository.getWeatherData`. Guard against redundant
  re-fetch (skip if the latest batch already covers the requested horizon).

## Critical files
- `shared/.../shared/config/ForecastHorizon.kt` (new) — constants + `daysToCover`.
- `shared/.../data/remote/OpenMeteoApi.kt` — `days` param (already exists; adjust default).
- Desktop: `DesktopWeatherService.kt` (fetch signatures), `DesktopWeatherRepository.kt` (`ensureForecastDays`),
  `Main.kt` (daily-nav hook).
- Android: `ForecastRepository.kt:321-328` (thread days), `WidgetIntentRouter.kt` (nav hook),
  `RefreshScheduler.kt` / `WeatherWidgetWorker.kt` (carry `KEY_FORECAST_DAYS`), `WeatherConfig.kt` (reference
  shared constants).

## Tests
1. **Request-param regression (the headline guard)** — `OpenMeteoApiTest` (shared + the app copy): assert the
   outgoing `forecast_days` equals the requested value. Model after `SilurianApiTest.kt:77-137` /
   `TomorrowIoApiTest.kt:34-47` (capture `request.url.parameters["forecast_days"]` via `MockEngine`).
   Cases: default/baseline sends `8`; explicit `days = 16` sends `16`.
2. **Desktop integration** — model after `DesktopBackfillIntegrationTest.kt` (temp-file DB +
   `DesktopWeatherDao` + mocked/`MockEngine` service): baseline fetch persists 8 days; `ensureForecastDays(16)`
   issues a `forecast_days=16` request and persists rows out to day 16; calling it again when already covered
   is a no-op (mutex/idempotence).
3. **Android integration** — model after `OpenMeteoIntegrationTest.kt` (in-memory Room + `MockEngine`):
   baseline `getWeatherData` sends `forecast_days=8`; a forced refresh carrying `forecastDays=16` (the nav-past
   path) sends `16` and the DB then holds forecasts through day 16. `ForecastHorizon.daysToCover` unit cases
   (today, +6, +7, +20 → 8, 8, 8?→ verify, 16) — confirm baseline floor + max ceiling.

   > Note: `daysToCover(today, today+7)` = 8, so the original 06-27 bug is covered by baseline alone; the
   > extension only matters for navigating beyond day 8.

## Verification (end-to-end)
- Unit/integration: `./gradlew :shared:test`, `./gradlew testDebugUnitTest --tests "*OpenMeteo*"`,
  `./gradlew :desktop:test --tests "*Forecast*"`. (Never `connectedDebugAndroidTest`.)
- Desktop manual: rebuild + restart via `scripts/buildStart.sh`; open daily view, confirm next Saturday now
  has an Open-Meteo bar; pan right past day 8 and confirm real bars (not just climate-normal green) appear
  after the on-demand fetch. Cross-check the DB:
  `sqlite3 ~/.local/share/weather-widget/weather.db "SELECT date(targetDate/1000,'unixepoch'), highTemp FROM forecasts WHERE source='OPEN_METEO' AND batchFetchedAt=(SELECT MAX(batchFetchedAt) FROM forecasts WHERE source='OPEN_METEO') ORDER BY targetDate;"`
  — baseline shows 8 future days; after navigating forward, up to 16.
- Android manual (emulator): install, add widget wide enough to show forecast days, navigate the forecast
  arrow forward to the edge, confirm an extension fetch fires (logcat) and farther days populate.
