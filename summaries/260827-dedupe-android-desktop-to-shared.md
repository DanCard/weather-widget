# Deduplicate Android ↔ Desktop code into `:shared` (2026-08-27)

## Problem

Duplicate weather-rendering and accuracy logic between the Android (`:app`) and desktop
(`:desktop`) modules. `:shared` already owns most pure logic and the desktop already consumed it;
Android kept private copies, and the two accuracy calculators carried one near-identical loop each.

Four concrete duplicates (verified element-for-element before editing):

1. **Icon classification sets/predicates** — `WeatherIconMapper` kept resource-ID sets
   (`PRECIPITATION_ICONS`, `RAIN_INDICATOR_ICONS`, `MIXED_ICONS`, `CLOUD_FORECAST_ELIGIBLE_ICONS`)
   and `isSunny/isPrecipitation/isRainIndicator/isMixed/isCloudForecastEligible`, all exact
   duplicates of the name-based sets/methods in `shared/.../WeatherConditionResolver.kt`.
2. **Cloud-ratio table** — `WeatherConditionColors.cloudRatio(iconRes)` was a verbatim copy of
   `WeatherConditionResolver.cloudRatioFromIcon(iconName)` (all 15 rows matched); its
   `CHANCE_RAIN_ICONS` duplicated the shared set.
3. **Forecast colors** — `WeatherConditionColors` re-declared the six hex constants and
   `forecastColor(...)` verbatim, duplicating `shared/.../WeatherColors`.
4. **Accuracy breakdown loop** — `AccuracyCalculator.getDailyAccuracyBreakdown` (Android) and
   `DesktopAccuracyCalculator.getDailyAccuracyBreakdown` (desktop) were ~90% identical (same date
   loop, epoch math, `maxBy fetchedAt` forecast pick, baseline resolution, scoring), differing only
   in DAO access and Android's 3 provenance fields.

## What changed

**Phase A — Android delegates to shared icon/color logic**

- `app/.../util/WeatherIconMapper.kt`: deleted the three resource-ID sets and dead
  `FULLY_CLOUDY_THRESHOLD`/`MOSTLY_CLOUDY_UPPER_THRESHOLD`. The five predicates now map
  `iconRes -> iconName` and delegate to `WeatherConditionResolver`. `MIXED_ICONS` is retained (for
  the test suite) but derived from the shared mixed set via the name dictionary. Kept Android-only:
  `NAME_TO_RES`/`RES_TO_NAME` and `resolveDailyTextIconTint`.
- `app/.../util/WeatherConditionColors.kt`: the six constants are now re-exports of
  `WeatherColors.*`; `forecastColor(...)` delegates to `WeatherColors.forecastColor(...)`;
  `cloudRatio(...)` delegates to `WeatherConditionResolver.cloudRatioFromIcon(...)`;
  `CHANCE_RAIN_ICONS` removed in favour of `WeatherConditionResolver.isChanceOfRainIcon(...)`.
  Kept Android-only: `forecastBarGradient`/`resolveMixedBarSplit`/`gradientStopPositions` (shader
  math) and the `MixedBarSplit` adapter type.

**Phase B — extract the shared accuracy breakdown**

- New `shared/.../shared/stats/AccuracyBreakdown.kt`: `ForecastRow` (slim forecast projection),
  `DailyResult` (scored day + baseline provenance), and `compute(...)` — the single date loop both
  calculators now call. Baseline selection, temp resolution and rounding still delegate to the
  existing shared primitives (`ActualsBaselineResolver`, `resolveBaselineTemps`, `AccuracyPure`).
- `app/.../stats/AccuracyCalculator.kt`: `getDailyAccuracyBreakdown` now converts Room entities to
  shared models (`DailyHistoryEntity.toDailyHistory()` + an inline `ForecastRow` mapping) and calls
  `AccuracyBreakdown.compute`, then maps to the UI `DailyAccuracy` DTO. Public API unchanged.
- `shared/.../stats/desktop/DesktopAccuracyCalculator.kt`: same shared loop; its public API is
  unchanged. `calculateAccuracy` now labels the statistics with `WeatherSource.displayName`
  (matching Android) instead of the raw id — invisible to the desktop UI, which never renders
  `AccuracyStatistics.source`.

Net effect: icon/color/ratio/accuracy-decision logic now lives once in `:shared`; each platform
keeps only its resource mapping, shader math, and DAO plumbing.

**Test fix (side effect of the dedupe).** Making `WeatherConditionColors.*` real constants in
plain-JUnit (instead of `Color.parseColor(...)` stubbed to 0 by `isReturnDefaultValues=true`)
exposed five colour assertions that were tautologies (`0 == 0`):

- `TemperatureGraphRendererFetchDotTest.fetch dot value color matches ... OBSERVED ...` removed —
  the same assertion is already covered meaningfully by the Robolectric `TemperatureFetchDotColorTest`.
- `DailyForecastGraphRendererRobolectricTest` (misnamed; it has no Robolectric runner) — its four
  colour-value assertions reworked to assert the observable split-vs-solid structure (2 vs 1
  `drawLine` calls). The exact segment colours remain covered by `WeatherConditionColorsTest`
  against the real shared constants.

## LOC changes

| File | Before | After | Δ |
|---|---|---|---|
| `app/.../util/WeatherIconMapper.kt` | 147 | 124 | −23 |
| `app/.../util/WeatherConditionColors.kt` | 115 | 82 | −33 |
| `app/.../stats/AccuracyCalculator.kt` | 172 | 125 | −47 |
| `shared/.../stats/desktop/DesktopAccuracyCalculator.kt` | 93 | 52 | −41 |
| `shared/.../shared/stats/AccuracyBreakdown.kt` (new) | — | 151 | +151 |
| **Subtotal source** | **527** | **534** | **+7** |
| `app/.../TemperatureGraphRendererFetchDotTest.kt` (test) | — | — | −28 |
| `app/.../DailyForecastGraphRendererRobolectricTest.kt` (test) | — | — | −7 |
| **Total incl. tests** | | | **−28** |

`git diff --numstat` (added/deleted) on the modified files: AccuracyCalculator +30/−77,
WeatherConditionColors +23/−56, WeatherIconMapper +27/−50, DesktopAccuracyCalculator +22/−63,
DailyForecastGraphRendererRobolectricTest +15/−22, TemperatureGraphRendererFetchDotTest +0/−28
(268 lines added, 296 deleted, plus the 151-line new shared file). Net source LOC is roughly
neutral — the win is that ~144 lines of duplicated per-platform logic collapsed into one shared
151-line implementation. Documentation: `plans/260827-dedupe-android-desktop-to-shared.md`
(+165) and this summary.

## Verification

- `:shared:compileKotlin :desktop:compileKotlin :app:compileDebugKotlin` — BUILD SUCCESSFUL.
- `:shared:testShortShared` — green, including `DesktopAccuracyTest` (exercises the new shared
  `AccuracyBreakdown` via the desktop calculator).
- `app` `WeatherConditionColorsTest` + `WeatherIconMapperTest` — all green (predicate and
  cloud-ratio behaviour unchanged).
- `app` `AccuracyCalculatorIntegrationTest` (Robolectric/Room, Long) — all 14 green, including the
  borrowed-baseline provenance assertions.
- `:app:testShortDebugUnitTest` + `:desktop:testShortDesktop` — green.
- `app` `WeatherConditionColorsRobolectricTest` — green.
- `app` `TemperatureGraphRendererFetchDotTest` (Medium) + `TemperatureFetchDotColorTest` (Long) —
  green after the tautology fix.
- `app` `DailyForecastGraphRendererRobolectricTest` + `DailyForecastGraphRendererRoboTest` +
  `TemperatureGraphDashContinuityTest` + `DailyGapFallbackGraphIntegrationTest` +
  `ForecastDaoPlausibilityTest` + `ForecastHistoryActualsVisibilityTest` +
  `HistoryActivitySyncRoboTest` + `DailyViewLogicTest` — green.
- Final sweep `:shared:testShortShared :desktop:testShortDesktop :app:testShortDebugUnitTest
  :app:testMediumDebugUnitTest` — BUILD SUCCESSFUL.

## Notes

- `AccuracyBreakdown` currently has no dedicated unit test; it is exercised through the existing
  `DesktopAccuracyTest` (shared) and `AccuracyCalculatorIntegrationTest` (app), both of which pin
  the loop's behaviour. A focused shared test can be added later if desired.
- The desktop's per-day `source` label now reads `displayName` ("Open-Meteo") instead of the raw id
  ("OPEN_METEO"), unifying it with Android. It is not rendered anywhere in the desktop UI.
- Five colour assertions across two plain-JUnit renderer tests were tautologies (`0 == 0`) under
  `unitTests.isReturnDefaultValues = true`. They were fixed (one removed, four rewritten to assert
  segment structure) as part of this change; colour-value coverage is unaffected because
  `WeatherConditionColorsTest` and the Robolectric `TemperatureFetchDotColorTest`/
  `DailyForecastGraphRendererRoboTest` assert real colour values.
