# Unify Daily Forecast Bar Split & Rain Chance Resolution in `:shared`

## Goal
Ensure exact parity between Android and Linux Desktop when rendering daily weather-adaptive forecast bars (specifically when a column is colored Steel Blue for chance-of-rain vs Slate Gray for cloudy), by centralizing bar split resolution and cloud ratio fallback logic into `:shared`.

## Root Cause Summary
1. **Desktop Re-Resolves Raw Text Without Context**:
   - In `DailyForecastGraph.kt`, desktop draws the bar via `drawAdaptiveBar(..., iconCondition = day.iconCondition)`.
   - `drawAdaptiveBar` re-invokes `WeatherConditionResolver.resolveIconName(condition)` with no `precipProbability` and no `cloudCover`.
   - Without probability, worded rain conditions (e.g. `"Rain Showers"`) default to `ic_weather_rain` (full rain) instead of `ic_weather_cloudy_chance_rain` (chance rain).
   - Only canonical icons in `CHANCE_RAIN_ICONS` satisfy `isChanceOfRainIcon = true`. Because `ic_weather_rain` is not in that set, `isChanceOfRain` evaluated to `false`, causing `WeatherColors.mixedBarSplit` to paint the bottom segment in slate gray (`FORECAST_CLOUDY`) instead of steel blue (`FORECAST_RAINY`).
2. **Missing Cloud Cover Fallback**:
   - In `DesktopDailyForecastModel.kt`, missing noon cloud percent was defaulted to `0` (`noonCloudPercentForBar = measuredNoonCloudPercent ?: 0`), producing `cloudCoverRatio = 0.0f` and preventing fallback to the icon's natural cloud ratio (`cloudRatioFromIcon`).
3. **Android Handled It Correctly**:
   - Android resolves the icon once with full parameters in `DailyForecastIconResolver`, and passes that resolved icon to `WeatherConditionColors.resolveMixedBarSplit`.

## Implementation Plan

### 1. `:shared` Module (`shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherColors.kt`)
- Add a shared, platform-neutral `resolveMixedBarSplit`:
  ```kotlin
  fun resolveMixedBarSplit(iconName: String?, cloudCoverRatio: Float? = null): MixedBarSplit? {
      val ratio = cloudCoverRatio ?: iconName?.let(WeatherConditionResolver::cloudRatioFromIcon)
      val isChanceOfRain = iconName?.let(WeatherConditionResolver::isChanceOfRainIcon) ?: false
      return mixedBarSplit(ratio, isChanceOfRain)
  }

  fun shouldDrawAdaptiveBar(isMixed: Boolean, cloudCoverRatio: Float?): Boolean =
      isMixed || (cloudCoverRatio ?: 0f) > 0f
  ```

### 2. Desktop Module (`:desktop`)
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt`**:
  - Add `snapshotIconName: String?` to `DesktopDailyDay`, resolving today's snapshot icon via `WeatherConditionResolver.resolveIconName` (passing `displaySnapshot`'s condition, precip probability, and cloud cover), or mirroring `gatedIconName` for past snapshot comparison bars.
  - Set `cloudCoverRatio = measuredNoonCloudPercent?.let { it / 100f }` (nullable, rather than defaulting to `0`), allowing `resolveMixedBarSplit` to fall back to `cloudRatioFromIcon` when hourly cloud data is unavailable.
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`**:
  - Update `drawAdaptiveBar` parameter from `iconCondition: String?` to `iconName: String?`.
  - Delegate directly to `WeatherColors.resolveMixedBarSplit(iconName, cloudCoverRatio)`.
  - Update all 4 call sites in `DailyForecastGraph.kt` to pass `day.iconName` (or `day.snapshotIconName`).

### 3. Android Module (`:app`)
- **`app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`**:
  - Update `resolveMixedBarSplit(iconRes: Int, cloudRatioOverride: Float?)` to delegate directly to `WeatherColors.resolveMixedBarSplit(WeatherIconMapper.iconResToName(iconRes), cloudRatioOverride)`.
- **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`**:
  - Align `cloudCoverRatioOverride = measuredCloudCoverPercent?.let { it / 100f }`.

## Testing & Verification
1. **Unit Tests**:
   - Add unit tests in `:shared` verifying `WeatherColors.resolveMixedBarSplit` correctly resolves Steel Blue for chance-of-rain icons (e.g. `ic_weather_cloudy_chance_rain`, `ic_weather_partly_cloudy_chance_rain`) and falls back to `cloudRatioFromIcon` when `cloudCoverRatio` is null.
   - Run `./scripts/unit-tests.sh` to ensure all tests pass across `:shared`, `:desktop`, and `:app`.
2. **Visual Verification**:
   - Restart desktop app via `scripts/buildStart.sh` and capture a screenshot of the popup window.
   - Verify that Wednesday renders in Steel Blue, matching the Fold 4.
