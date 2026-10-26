# Hourly forecast settle at hour and daily-hourly parity for temperature, cloud cover, and rain

**User, 2026-10-26:**
> "Shouldn't use a day ago, for noon today. Maybe last update should be 11 am today. In other words should continue to update until hour is in the past."
> "Write the plan to plans/ dir. This should be the same for temperature, cloud cover and rain %, hourly data."
> "Agreed on proposed solution. Make sure the code is shared between desktop and android. Not duplicated."

## 1. Problem and Root Cause

### 1a. Observed Discrepancy
On desktop under `OPEN_METEO`:
- **Hourly Cloud Cover Graph (`CloudCoverGraph`)**: For 12:00 PM today, the dashed forecast curve displayed **15%** (peak was 34% at 1:00 PM, actual plunged to 1% at 1:00 PM). The afternoon appeared mostly sunny / clear.
- **Daily View Today Column (`DailyForecastGraph`)**: The Today column showed an adaptive bar that was **94% grey (cloud)** and only 6% yellow (clear), with a cloudy icon (`ic_weather_cloudy`).

### 1b. Root Cause
1. **Post-noon fetch overwrote live forecast in database**:
   At 12:27 PM today (27 minutes after 12:00 PM had already passed), Open-Meteo returned `cloudCover = 94` for the 12:00 PM slot. Because `DesktopWeatherDao.upsertHourlyForecasts` and `HourlyForecastStitcher` performed blind `maxByOrNull { it.fetchedAt }`, this 12:27 PM post-noon hindcast overwrote the pre-noon forecast in `hourly_forecasts` and `hourly_forecast_history`. `DailyHourlySummaries` and `DailyNoonCloudCover` read this 94% row for Today's column.
2. **Hourly graph substituted day-ago forecast for today's hours**:
   In `CloudSeriesBuilder`, any hour with `dateTime < currentHourStart` was marked `isFrozen` and replaced with `priorForecast[hour.dateTime]` (`OPEN_METEO_PRIOR24` / `cloud_cover_previous_day1`), which was Open-Meteo's prediction from 24 hours ago (15%). This threw away the updated forecast that was active when noon arrived.
3. **Views did not share the same data**:
   The hourly cloud graph displayed the 24h-prior prediction (15%), while the daily view displayed the post-noon hindcast (94%).

---

## 2. Core Rule

A forecast for any hour $T$ (temperature, cloud cover, and rain % / amount) follows one shared rule:
1. **While $T$ is in the future ($nowMs \le T$)**:
   The forecast continues to update with every new fetch.
2. **Once $T$ is in the past ($nowMs > T$)**:
   The forecast freezes at the **last forecast fetched at or before that hour** ($fetchedAt \le T$).
   Fetches arriving after $T$ ($fetchedAt > T$) are post-hoc hindcasts / model assimilations and must **not** overwrite the forecast for $T$.
   If no fetch exists with $fetchedAt \le T$ (e.g. app first installed after noon), it falls back to the earliest available fetch for that hour.
3. **No day-ago swaps for today**:
   For elapsed hours of **today**, the hourly graph does not swap in 24h-prior day-ago predictions (`priorDayCloudForecast`). It displays the pre-hour settled forecast from rule #2.
4. **Daily Today column and hourly graph consume the identical data**:
   Both views consume the identical `HourlyForecast` list produced by `:shared` `HourlyForecastStitcher`. Today's noon cloud cover in the daily view is identical to the 12:00 PM forecast point in the hourly graph.

---

## 3. Architecture and Shared Implementation

All logic lives in `:shared`. Android (`:app`) and `:desktop` only supply storage / display glue.

### 3a. Single Hourly Stitch Chokepoint (`:shared` `HourlyForecastStitcher.kt`)
Both Android (`HourlyForecastLoader`, `GraphDataLoader`) and Desktop (`DesktopWeatherDao.getHourlyWithHistory`) already funnel all hourly reads through `HourlyForecastStitcher.stitch` / `HourlyForecastStitcher.stitchBySource`.
- In `HourlyForecastStitcher.collapse`:
  When collapsing candidate rows for hour $T$:
  - If $T < nowMs$: filter candidates for those fetched before the hour passed (`it.fetchedAt <= T`). If none qualify, fall back to earliest available.
  - If $T \ge nowMs$: take the newest fetch (`maxByOrNull { it.fetchedAt }`).
- Because `HourlyForecastStitcher` returns `HourlyForecast` objects holding `temperature`, `cloudCover` (plus low/mid/high bands), `precipProbability`, and `precipAmountMm`, all three variables are settled identically across both platforms.

### 3b. Cloud Series Builder (`:shared` `CloudSeriesBuilder.kt`)
- `priorForecast` (24h-prior day-ago prediction) is only substituted for hours on **past days** (`hour.dateTime < todayStartMs`), where 24h-prior verification is meaningful.
- For elapsed hours of **today** (`hour.dateTime >= todayStartMs && hour.dateTime < currentHourStart`), `CloudPoint.forecastCover` keeps the settled forecast produced by `HourlyForecastStitcher`, rather than reverting to `_previous_day1`.

### 3c. Daily Noon Cloud Cover (`:shared` `DailyNoonCloudCover.kt` & `DailyHourlySummaries.kt`)
- `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent`:
  When resolving noon on `date` with `hourly` rows:
  - Takes the row at 12:00 PM. Since `hourly` comes from `HourlyForecastStitcher`, it already carries the settled pre-noon forecast when noon has elapsed.
- `DailyHourlySummaries.forDate`:
  - When evaluating an incoming fetch batch for date $D$: if noon on date $D$ is already in the past relative to the fetch (`fetchTime > noonMs`), do not overwrite an existing pre-noon `noonCloudPercent` with a post-noon hindcast.

---

## 4. Verification and Parity

1. **`:shared` Unit Tests**:
   - `HourlyForecastStitcherTest`:
     - Test future hour updates with newer fetches.
     - Test past hour freezes at last fetch with `fetchedAt <= hour.dateTime`.
     - Test post-hour fetch (`fetchedAt > hour.dateTime`) does not overwrite temperature, cloudCover, or precipProbability for an elapsed hour.
     - Test fallback to earliest fetch when no pre-hour fetch exists.
   - `CloudSeriesBuilderTest`:
     - Verify today's elapsed hours do not swap in `priorForecast`.
     - Verify past-day elapsed hours continue to use `priorForecast`.
   - `DailyNoonCloudCoverTest` / `DailyHourlySummariesTest`:
     - Verify noon cloud cover ignores post-noon hindcasts.
2. **Parity Harness**:
   - Run `:shared` parity tests (`DailyParity`) to guarantee Android and desktop produce identical outputs for Today's column and hourly points.
3. **Runtime Verification**:
   - Run `./scripts/unit-tests.sh`.
   - Verify on running desktop application: check Today column adaptive bar and icon match hourly graph at 12:00 PM.
