# Deduplicate Android ↔ Desktop code into `:shared`

**Date:** 2026-08-27
**Status:** Implemented (2026-08-27)

## Summary

`:shared` already owns a large amount of pure logic (icon resolution, colors, accuracy math,
rain labels, dominant-temp watch, etc.). The desktop app consumes that shared code directly; the
Android app still keeps a handful of private, resource-ID-based copies of the same logic, and there
is one genuine cross-platform copy-paste between the two accuracy calculators. This plan removes
those duplicates by (a) making Android delegate to already-shared functions, and (b) extracting the
one shared orchestration loop that both platforms copied.

## Findings (evidence)

### 1. Icon classification sets/predicates — duplicated between `WeatherIconMapper` (Android) and `WeatherConditionResolver` (shared)

File: `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`

The Android mapper keeps private `Set<Int>` copies built from drawable resource IDs:

- `PRECIPITATION_ICONS`, `RAIN_INDICATOR_ICONS`, `MIXED_ICONS`, `CLOUD_FORECAST_ELIGIBLE_ICONS`
- methods `isSunny / isPrecipitation / isRainIndicator / isMixed / isCloudForecastEligible`

These are **exact duplicates** of the name-based sets and methods already in
`shared/.../shared/util/WeatherConditionResolver.kt` (verified element-for-element):

| Android (res ID) | shared (icon name) |
|---|---|
| `PRECIPITATION_ICONS` | `WeatherConditionResolver.PRECIPITATION_ICONS` (rain/storm/snow) |
| `RAIN_INDICATOR_ICONS` | `WeatherConditionResolver.RAIN_INDICATOR_ICONS` |
| `MIXED_ICONS` | `WeatherConditionResolver.MIXED_ICONS` |
| `CLOUD_FORECAST_ELIGIBLE_ICONS` | `WeatherConditionResolver.CLOUD_FORECAST_ELIGIBLE_ICONS` |
| `isSunny` | `WeatherConditionResolver.isSunny` |
| `isPrecipitation` | `WeatherConditionResolver.isPrecipitation` |
| `isRainIndicator` | `WeatherConditionResolver.isRainIndicator` |
| `isMixed` | `WeatherConditionResolver.isMixed` |
| `isCloudForecastEligible` | `WeatherConditionResolver.isCloudForecastEligible` |

Desktop already delegates these to shared (see `desktop/.../WeatherIcon.kt`). Android is the outlier.

**Keep Android-only:** `NAME_TO_RES` / `RES_TO_NAME` maps (drawable res IDs) and
`resolveDailyTextIconTint` (maps predicates → Android `R.color.*`).

### 2. Cloud ratio table + chance-of-rain set — duplicated between `WeatherConditionColors` (Android) and shared

File: `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`

- `cloudRatio(iconRes)` is a **verbatim copy** of
  `shared/.../WeatherConditionResolver.cloudRatioFromIcon(iconName)` — all 15 rows match exactly.
- `CHANCE_RAIN_ICONS` (res IDs) is a verbatim copy of
  `shared/.../WeatherConditionResolver.CHANCE_RAIN_ICONS` / `isChanceOfRainIcon(iconName)`.

### 3. Forecast color constants + `forecastColor()` — duplicated between `WeatherConditionColors` (Android) and `WeatherColors` (shared)

File: `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt`

- `FORECAST_SUNNY / FORECAST_CLOUDY / FORECAST_RAINY / FORECAST_NIGHT / FORECAST_TWILIGHT / OBSERVED`
  are `Color.parseColor("#…")` re-declarations of `shared/.../WeatherColors` ARGB int constants
  (identical values).
- `forecastColor(isSunny, isRainy, isMixed, isNight, isTwilight)` is a **verbatim copy** of
  `WeatherColors.forecastColor(...)`.

Desktop already calls `WeatherColors.forecastColor(...)` and reads `WeatherColors.*` constants
directly (see `desktop/.../TemperatureGraph.kt`, `DailyForecastGraph.kt`, `CloudCoverGraph.kt`).
Android is the outlier.

### 4. Per-day accuracy breakdown loop — copied between `AccuracyCalculator` (Android) and `DesktopAccuracyCalculator` (desktop)

Files:
- `app/src/main/java/com/weatherwidget/stats/AccuracyCalculator.kt` → `getDailyAccuracyBreakdown`
- `shared/src/main/kotlin/com/weatherwidget/stats/desktop/DesktopAccuracyCalculator.kt` → `getDailyAccuracyBreakdown`

The two methods are ~90% identical (same date loop, same epoch math, same forecast pick
`maxBy fetchedAt`, same baseline resolution, same `resolveBaselineTemps` + `AccuracyPure.buildDailyAccuracy`).
Everything they *call* is already shared (`ActualsBaselineResolver`, `resolveBaselineTemps`,
`AccuracyPure`). The differences are only:

1. DAO access — Android: `ForecastDao`/`DailyHistoryDao` (Room, `suspend`);
   desktop: `DesktopWeatherDao` (`List<DailyHistory>`, `List<DesktopForecastRow>`).
2. `hasRowForDate` predicate — Android: `computedHighTemp != null && computedLowTemp != null`;
   desktop: `hasActuals` (same thing, already exposed on shared `DailyHistory`).
3. Android's result DTO carries 3 extra provenance fields: `baselineSourceId`,
   `baselineStationId`, `baselineFellBackToBlend` (all derivable from shared data).

## Root cause

The project has been actively moving logic into `:shared` (there are already thin Android wrappers
for `TempUtils`, `RainAnalyzer`, `WeatherTimeUtils`, `HeaderPrecipCalculator`, and the whole
`shared/graph`, `shared/actuals`, `shared/observations`, `shared/stats` packages). Two islands were
missed:

1. **Android's icon/color layer was migrated only partially.** `WeatherIconMapper` and
   `WeatherConditionColors` predate the shared `WeatherConditionResolver`/`WeatherColors` and were
   left with their own resource-ID sets and hex constants. Desktop, written later, skipped straight
   to the shared source of truth.
2. **The accuracy breakdown was never given a shared home.** Both platforms independently
   re-derived the same loop around the (already shared) scoring primitives.

## Proposed changes

### Phase A — Android delegates to shared icon/color logic (low risk)

1. `WeatherIconMapper`:
   - Delete `PRECIPITATION_ICONS`, `RAIN_INDICATOR_ICONS`, `MIXED_ICONS`,
     `CLOUD_FORECAST_ELIGIBLE_ICONS`.
   - Rewrite `isSunny/isPrecipitation/isRainIndicator/isMixed/isCloudForecastEligible` to map
     `iconRes -> iconName` via `RES_TO_NAME` then delegate to `WeatherConditionResolver`.
   - Keep `NAME_TO_RES`, `RES_TO_NAME`, `resolveDailyTextIconTint` (Android-only).
2. `WeatherConditionColors`:
   - Delete `CHANCE_RAIN_ICONS`; use `WeatherConditionResolver.isChanceOfRainIcon(resToName(iconRes))`.
   - Rewrite `cloudRatio(iconRes)` to delegate to
     `WeatherConditionResolver.cloudRatioFromIcon(resToName(iconRes))`.
   - Rewrite `forecastColor(...)` to delegate to `WeatherColors.forecastColor(...)`.
   - Replace the six hex constants with `WeatherColors.*` (same ARGB ints).

Net effect: the "predicate/color/ratio source of truth" lives only in `:shared`; Android keeps
only its drawable-ID ↔ icon-name dictionary.

### Phase B — Extract shared accuracy breakdown (medium risk)

1. Add a small shared contract (in `:shared`, e.g. `shared/stats/AccuracyBreakdown.kt`):
   - a `data class` forecast projection (targetDate, dateOfPrediction, highTemp, lowTemp, fetchedAt);
   - a `data class` day row projection (or reuse `DailyHistory` + `hasActuals`);
   - `fun computeBreakdown(...)` that performs the shared loop and returns
     `List<AccuracyPure.DailyAccuracy>` **plus** optional provenance fields
     (`baselineSourceId`, `baselineStationId`, `baselineFellBackToBlend`).
2. Android `AccuracyCalculator`:
   - convert `DailyHistoryEntity.toDailyHistory()` (exists) and add a tiny
     `ForecastEntity -> shared forecast row` mapper;
   - call the shared `computeBreakdown`, then map to the UI `DailyAccuracy` DTO.
3. Desktop `DesktopAccuracyCalculator`:
   - call the same shared `computeBreakdown` (its DAO already returns shared `DailyHistory` and
     a `DesktopForecastRow` projection).
4. `DesktopForecastRow` naming/relocation: reuse it (or a neutral alias) so both platforms share
   one forecast projection type.

## Tests / risk

- Pinned by existing tests: `WeatherConditionColorsTest`, `WeatherConditionColorsRobolectricTest`,
  `WeatherIconMapperTest`, `AccuracyCalculatorIntegrationTest`,
  `shared/.../DesktopAccuracyTest`, plus the large shared `WeatherConditionResolver`/`WeatherColors`
  suites.
- Phase A is behavior-preserving (values verified identical); risk is mechanical-edit mistakes only.
- Phase B changes the Android DAO query path (suspend → shared projection) and the desktop type
  aliases; it needs the accuracy integration tests to stay green on both modules.
- `@Category` duration buckets must be preserved/added for any new test classes (per AGENTS.md).

## Verification

1. `./gradlew :shared:test` (or `./gradlew :shared:testShortShared`).
2. `./gradlew :app:testShortDebugUnitTest` + `:app:testByDurationDebugUnitTest`.
3. `./gradlew :desktop:test` / `:desktop:testByDurationDesktop`.
4. Manual/emulator spot-check: widget daily/hourly/precip/cloud rendering colors and icons
   unchanged (screenshot + renderer logcat per the evidence-first protocol).

## Out of scope (already deduplicated — noted, not touched)

- `TempUtils`, `RainAnalyzer`, `WeatherTimeUtils` — already thin delegating wrappers (residual
  logging only).
- `HeaderPrecipCalculator` / desktop `HeaderPrecipSizing` — already share `DailyRainLabels` /
  `PrecipProbabilityCalculator`.
- Dominant-temp watch — decision logic already in `shared/.../notify/DominantTempWatch`; both
  platforms keep thin platform persistence adapters.
