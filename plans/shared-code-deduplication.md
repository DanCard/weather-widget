# Shared Code Deduplication Plan

## Problem
Significant code duplication exists between the Android `:app` and `:desktop` modules. The `:shared` module already hosts API clients, data models, and some utilities, but many pure-logic utilities remain duplicated. Per project convention, Android implementations are authoritative and desktop implementations contain bugs.

## Strategy
Extract pure-Kotlin logic from Android into `:shared`, then update desktop to consume it. Fix desktop bugs along the way by adopting Android's correct implementations.

---

## Phase 1: High-Value Extractions (biggest dedup bang-for-buck)

### 1.1 Weather Condition → Icon Name Resolution
**Duplication:** Android `WeatherIconMapper` (209 lines) and desktop `WeatherIcon` (83 lines) both implement the same ~12-branch `when` chain mapping condition strings to icon names. Android is a superset with night/twilight/probability handling.

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherConditionResolver.kt`
- Extract from Android:
  - `resolveIconName(condition: String?, isNight: Boolean, cloudCover: Int?, precipProbability: Int?, isTwilight: Boolean, isSunBoundary: Boolean): String` — returns icon name string (e.g. `"ic_weather_rain"`)
  - `normalizePatchyFogTransitionCondition(condition: String): String`
  - `getCloudRatio(condition: String): Float?` (currently desktop-only, useful for both)
  - `isRainIndicator(iconName: String): Boolean`
  - `isCloudForecastEligible(iconName: String): Boolean`
  - `isSunny(iconName: String): Boolean`
  - `isPrecipitation(iconName: String): Boolean`
  - `isMixed(iconName: String): Boolean`
  - `ConditionFlags` data class + `getConditionFlags(iconName: String): ConditionFlags`
  - `resolveIconHome(iconName: String): String` (PRECIPITATION/CLOUD_COVER/HOURLY)
- Update Android `WeatherIconMapper` to delegate to shared resolver, then map string → `R.drawable.*`
- Update desktop `WeatherIcon` to delegate to shared resolver, then map string → Compose resource path
- **Android wins:** desktop's substring-based `isRainIndicator`/`isCloudForecastEligible` replaced with Android's precise set-membership approach

### 1.2 Weather Condition Colors
**Duplication:** Android `WeatherConditionColors` and desktop `TemperatureGraph`, `PrecipitationGraph`, `CloudCoverGraph` all define identical color constants (`#F4C542`, `#8E99A4`, `#5A8FBF`, `#BBBBBB`, `#FFA726`, `#FF3366`) and identical `forecastColor()` priority chain.

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherColors.kt`
- Extract:
  - 6 color constants as hex `Int` values
  - `forecastColor(isSunny, isRainy, isMixed, isNight, isTwilight): Int` — pure priority-chain function
  - `cloudRatio(iconName: String): Float?` (alias for `WeatherConditionResolver.getCloudRatio`)
- Update Android `WeatherConditionColors` to use shared constants/function
- Update all 3 desktop graph files to use shared constants/function (remove their local copies)
- Platform-specific gradient/shader code stays where it is

### 1.3 RainAnalyzer → Shared
**Duplication:** Android `RainAnalyzer` (278 lines) has full rain-window analysis. Desktop has zero equivalent — its `PrecipitationGraph` reimplements a simpler day/night segment sum approach.

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/util/RainAnalyzer.kt`
- Move from Android (parameterized on `HourlyForecast` instead of `HourlyForecastEntity`):
  - `isRainHour(forecast: HourlyForecast): Boolean` (probability ≥ 50% OR condition text)
  - `isAnyRainHour(forecast: HourlyForecast): Boolean`
  - `buildRainWindows(rainHours: List<RainHour>): List<RainWindow>`
  - `hasDryGapBefore(allForecasts, source, windowStart): Boolean`
  - `analyzeDay(date, hourlyForecasts, source, now): RainForecast`
  - `hasRain(date, hourlyForecasts, source, now): Boolean`
  - `getRainSummary(date, hourlyForecasts, source, now): String?`
  - `RainWindow`, `RainForecast` data classes
  - `formatHour(dateTime): String`
- Update Android `RainAnalyzer` to delegate to shared
- Desktop can now use shared `RainAnalyzer` for daily graph rain summaries
- **Android wins:** desktop's `precipSignal = max(prob/100, amount/6)` formula is not adopted — it stays as a visual-only rendering detail

### 1.4 AccuracyCalculator → Shared
**Duplication:** Android `AccuracyCalculator` (178 lines) and shared `DesktopAccuracyCalculator` have near-identical `calculateScore()`, `calculateAccuracy()`, `getDailyAccuracyBreakdown()`, and data classes.

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/stats/AccuracyPure.kt`
- Extract:
  - `calculateScore(avgError: Double): Double`
  - `computeStatistics(errors: List<HighLowError>, sourceName: String, dayCount: Int): AccuracyStatistics`
  - `computeDailyBreakdown(forecasts, actuals, source): List<DailyAccuracy>`
  - `AccuracyStatistics` data class (canonical, replaces both `AccuracyStatistics` and `DesktopAccuracyStatistics`)
  - `DailyAccuracy` data class (canonical, replaces both versions)
- Update Android `AccuracyCalculator` to use shared pure functions + Room DAO queries
- Remove `DesktopAccuracyCalculator` from shared, replace with `AccuracyPure` usage
- **Android wins:** thresholds and formula are identical, but Android's data class is canonical

---

## Phase 2: Smaller Extractions

### 2.1 TempUtils.formatTemp()
**Duplication:** Android `TempUtils.formatTemp()` and desktop `DailyForecastGraph.formatTemp()` use identical 0.01-threshold logic.

**Plan:**
- Move `formatTemp(v: Float?): String?` to `shared/src/main/kotlin/com/weatherwidget/shared/util/TempUtils.kt`
- Replace the one `android.util.Log` call with shared `Log`
- Update Android `TempUtils` to delegate to shared
- Update desktop `DailyForecastGraph` and `TemperatureTrayPainter` to use shared

### 2.2 HeaderPrecipCalculator (interpolation version)
**Duplication:** Android `HeaderPrecipCalculator` does minute-level interpolation for next-8-hour precip. Desktop does trivial `maxOrNull()` — less accurate.

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/util/PrecipProbabilityCalculator.kt`
- Extract (parameterized on `HourlyForecast`):
  - `getNext8HourPrecipProbability(hourlyForecasts, displaySourceId, fallbackDaily, referenceTime): Int?`
  - `isNext8HourPrecipPredominantlyNight(hourlyForecasts, referenceTime): Boolean`
- Update Android `HeaderPrecipCalculator` to delegate to shared
- Update desktop header display to use shared (fixing accuracy bug)

### 2.3 Battery Tier Constants
**Duplication:** Android and desktop share identical battery thresholds (>70→4h, >50→8h, ≤50→suspend).

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/util/BatteryTier.kt`
- Extract:
  - Constants: `TIER_HIGH = 70`, `TIER_MED = 50`, `INTERVAL_CHARGING`, `INTERVAL_HIGH`, `INTERVAL_MED`
  - `computeForecastInterval(isCharging: Boolean, batteryLevel: Int): Long?`
- Both platforms use this as base, layering their own extras on top

### 2.4 Daily Day-Value Resolution
**Duplication:** Android `DailyActualsEstimator` and desktop `DesktopDailyForecastModel` both compute today's solid/forecast line values with subtly different (and both slightly wrong) formulas.

**Plan:**
- Create `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyDayValueResolver.kt`
- Extract:
  - `resolveTodayLineValues(actual, forecast, currentTemp): TodayLineValues` — using Android's correct formula
  - `resolvePastDayValues(actual, snapshot): PastDayValues`
  - `resolveFutureDayValues(forecast): FutureDayValues`
  - `TodayLineValues`, `PastDayValues`, `FutureDayValues` data classes
- Fix desktop formula: `solidHigh = current ?: actual?.highTemp` (not `max(actual, current)`)
- Fix desktop formula: `solidLow = min(actual.low, current)` (not `actual.low ?: current`)

---

## Phase 3: Desktop Internal Consolidation

### 3.1 Desktop Graph Utilities
**Duplication:** 5 functions and 10 constants are copy-pasted across `TemperatureGraph.kt`, `PrecipitationGraph.kt`, and `CloudCoverGraph.kt`.

**Plan:**
- Create `desktop/src/main/kotlin/com/weatherwidget/desktop/graph/DesktopGraphUtils.kt`
- Consolidate:
  - `computeTangents()` — Catmull-Rom tangent computation
  - `buildCurve()` — smooth curve point generation
  - `formatHourLabel()` — hour formatting
  - `drawDayLabels()` — day boundary labels (parameterize font size)
  - `indexOfByClosestTime()` — binary search for time match
  - `forecastColor()` — delegate to shared `WeatherColors.forecastColor()`
  - 5 color constants → delegate to shared `WeatherColors`
  - 5 numeric constants (`WIDE_BACK_HOURS`, etc.)
- Remove duplicates from all 3 graph files

---

## Implementation Order

| # | Task | Files Created | Files Modified | Risk |
|---|------|---------------|----------------|------|
| 1.1 | WeatherConditionResolver | 1 shared | 2 (Android mapper, desktop icon) | Low — pure extraction |
| 1.2 | WeatherColors | 1 shared | 4 (Android + 3 desktop graphs) | Low — constants only |
| 1.3 | RainAnalyzer | 1 shared | 2 (Android analyzer, desktop daily graph) | Medium — model swap |
| 1.4 | AccuracyPure | 1 shared | 2 (Android calc, shared desktop calc) | Low — pure math |
| 2.1 | TempUtils | 1 shared | 3 (Android + 2 desktop) | Low — trivial |
| 2.2 | PrecipProbabilityCalculator | 1 shared | 2 (Android + desktop header) | Medium — interpolation |
| 2.3 | BatteryTier | 1 shared | 2 (Android worker, desktop strategy) | Low — constants |
| 2.4 | DailyDayValueResolver | 1 shared | 2 (Android estimator, desktop model) | Low — formula fix |
| 3.1 | DesktopGraphUtils | 1 desktop | 3 desktop graphs | Low — internal refactor |

**Totals:** ~8 new shared files, ~20 modified files, ~3 new desktop files

---

## Testing Strategy

- Each extraction gets unit tests in `shared/src/test/` covering the pure logic
- Existing Android instrumented tests validate no regression on widget rendering
- Existing desktop tests validate no regression on desktop UI
- Run `./gradlew test` (shared + desktop JVM tests) after each phase
- Run `./gradlew connectedDebugAndroidTest` after Phase 1 & 2 to verify Android widget

## Risk Mitigation

- **Model compatibility:** `HourlyForecast` already has all needed fields (`precipProbability`, `condition`, `dateTime`, `source`, `cloudCover`, `precipAmountMm`). No model changes needed.
- **Desktop breakage:** Desktop is the buggy side. Extracting Android logic fixes bugs. Desktop tests will catch regressions.
- **Merge conflicts:** Each phase is independent — can be done in any order or in parallel.
