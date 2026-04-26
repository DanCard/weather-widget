# Daily Rain Label Missing For Tomorrow Despite 15% NWS Precip

## Summary

On the emulator, tomorrow in daily forecast view carries a `15%` daytime rain chance in runtime data, but the widget does not show a rain chance label for that day. The immediate cause is not the minimum day-threshold formula anymore. The label is being suppressed because the resolved daily icon is not a rain indicator.

## Runtime Evidence

- Emulator screenshot showed `Mon` with no rain label and a dry-looking icon.
- Logcat from the running emulator showed:

```text
DailyViewLogic: buildDailyRainLabel skipping non-rain icon: date=2026-04-27 iconRes=2131165322 dayPrecip=15 nightPrecip=0
DailyViewHandler: graphDay widget=25 col=3 date=2026-04-27 isToday=false iconRes=2131165322 iconName=ic_weather_mostly_clear isRainy=false isCloudEligible=true hasRainForecast=true
```

- After reinstalling, the emulator was running the updated APK (`lastUpdateTime=2026-04-26 09:47:00`), and the behavior remained the same.

## What Was Ruled Out

- The local `WeatherIconMapper` low-probability cutoff was changed from `<= 15` to `< 8`.
- That change was present in source, but it did not fix tomorrow's label because tomorrow's daily icon was still resolving to `ic_weather_mostly_clear`.
- The day-threshold formula change was also active. The remaining blocker was not rain-threshold suppression.

## Traced Root Cause

The mismatch appears to come from how NWS daily rows are assembled.

1. In [NwsForecastMapper.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt:112), the mapper seeds `conditionMap` from hourly conditions via `initConditionsFromHourly(...)`.
2. In `applyForecastPeriods(...)`, the mapper sets daytime and nighttime precip from NWS forecast periods:
   - `daytimePrecipProbabilityMap[dateString] = probability`
   - `nighttimePrecipProbabilityMap[dateString] = probability`
3. But the daily `condition` is only written if `acc.conditionMap[dateString] == null`:

```kotlin
if (acc.conditionMap[dateString] == null) {
    acc.conditionMap[dateString] = period.shortForecast
    acc.conditionSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
}
```

4. Later, the stored NWS `ForecastEntity` uses:
   - `condition = acc.conditionMap[dateString]`
   - `nativeDailyIconToken = acc.conditionMap[dateString]`
   - `daytimePrecipProbability = acc.daytimePrecipProbabilityMap[dateString]`

This means a future NWS day can end up with:
- precip sourced from the daytime forecast period, such as `15%`
- condition/icon token sourced earlier from hourly conditions, such as `Mostly Clear`

## Why The Label Is Suppressed

In daily view:

1. [DailyViewLogic.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt:412) passes NWS daytime/nighttime precip into icon resolution for future NWS days.
2. [DailyForecastIconResolver.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt:114) resolves the icon from the row's `nativeDailyIconToken` or `condition`.
3. For tomorrow on the emulator, that resolved to `ic_weather_mostly_clear`.
4. [DailyViewLogic.kt](/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt:599) refuses to show the daily rain label unless `WeatherIconMapper.isRainIndicator(iconRes)` is true.

So the actual failure mode is:
- `daytimePrecipProbability = 15`
- `nighttimePrecipProbability = 0`
- `condition/nativeDailyIconToken = "Mostly Clear"`-style text
- resolved icon is non-rain
- rain label is skipped

## Working Hypothesis

The missing tomorrow rain label is caused by a data-shape inconsistency in NWS future-day rows: precip values come from forecast periods, while icon-driving condition text can remain sourced from earlier hourly condition seeding. That mismatch produces a dry icon for a day that still carries a non-zero daytime rain probability.
