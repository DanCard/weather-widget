# Desktop/Android parity: today's daily rain-% label (2% → 15%)

## Context

On the daily forecast view, the **today** column shows a different rain-chance % on each
platform: Android shows **15%**, desktop shows **2%**. Both already share the formatter
(`DailyRainLabels.buildDailyRainLabel`) and the hourly window calc
(`DailyRainLabels.calculateDayNightPrecipProbabilities`, 8am–8pm / 8pm–8am max). The drift is
in the **input selection** — the ~6 lines that decide *which* number to format — which is
reimplemented per platform and has diverged. Goal: make that selection shared too, so both
platforms display the same value. (User: prefer shared code.)

## Root cause

- **Android** `app/.../widget/handlers/DailyViewLogic.kt:213-235`: computes
  `useDirectNwsPeriodPrecip = (row source == NWS && display == NWS)`. When true it uses NWS's
  **native 12-hour period** precip directly — `weather.daytimePrecipProbability ?: weather.precipProbability`
  (= **15%**) — and skips the hourly calc. Otherwise: `dayNightPrecip?.dayMax ?: weather.daytimePrecipProbability`.
  Night mirrors this. The resulting `dayPrecipForIcon`/`nightPrecipForIcon` feed BOTH the icon and
  the label text (`buildDailyRainLabel(dayPrecipProbability = dayPrecipForIcon)`).
- **Desktop** `desktop/.../DesktopDailyForecastModel.kt:240-265`: has **no** direct-NWS branch —
  always `dayNight?.dayMax ?: forecast?.precipProbability` (sparse hourly max → **2%**), and the
  fallback field differs (`precipProbability` vs `daytimePrecipProbability`). Night uses
  `dayNight?.nightMax` only (no period fallback).

Desktop already stores the needed fields (`DesktopWeatherDatabase.kt:44-45`
`daytimePrecipProbability`/`nighttimePrecipProbability`, populated by shared
`data/remote/NwsDailyMapper.kt`), so once it runs the same selection it can produce 15%.

## Approach — extract the selection into `:shared`

Add one pure function to `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`
that encapsulates the whole day/night precip selection (the part currently duplicated/divergent):

```kotlin
data class ResolvedDailyPrecip(val dayPrecip: Int?, val nightPrecip: Int?)

/** Day/night precip % for the daily label + icon, identical across platforms. For an NWS row
 *  shown as NWS, NWS's native 12h period precip is more representative than the sparse hourly
 *  max, so use it directly; otherwise the hourly 8am-8pm / 8pm-8am window max, falling back to
 *  the row's period fields. Past days return null (they show observed amounts, not probability). */
fun resolveDailyLabelPrecip(
    isPast: Boolean,
    rowSourceId: String?,
    displaySourceId: String,
    daytimePrecipProbability: Int?,
    nighttimePrecipProbability: Int?,
    precipProbability: Int?,
    hourly: List<HourlyForecast>,
    targetDate: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ResolvedDailyPrecip
```

Internally: past → `(null, null)`; direct-NWS → `(daytime ?: precipProbability, nighttime)`;
else run the existing `calculateDayNightPrecipProbabilities` and return
`(dayMax ?: daytime, nightMax ?: nighttime)`. This is a faithful lift of Android's existing logic,
so Android behavior (incl. the icon) is unchanged.

Then both platforms call it instead of their inline versions:
- **Android** `DailyViewLogic.kt`: replace the `useDirectNwsPeriodPrecip` / `dayNightPrecip` /
  `dayPrecipForIcon` / `nightPrecipForIcon` block (lines ~213-235) with a call to
  `resolveDailyLabelPrecip(...)`, feeding its `.dayPrecip`/`.nightPrecip` into the existing
  `resolveIcon`, `buildDailyRainLabel`, and `buildNightRainLabel` calls (behavior-preserving).
- **Desktop** `DesktopDailyForecastModel.kt:240-265`: replace the inline `dayNight` +
  `dayPrecipProbability = dayNight?.dayMax ?: forecast?.precipProbability` with the shared call,
  passing `forecast?.source`, `displaySourceId`, and the forecast row's
  `daytimePrecipProbability`/`nighttimePrecipProbability`/`precipProbability`. Use the result for
  both the label and desktop's daily icon for full parity.

## Critical files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt` — add
  `resolveDailyLabelPrecip` + `ResolvedDailyPrecip` (reuses `calculateDayNightPrecipProbabilities`).
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — delegate to it
  (and `app/.../util/DailyForecastIconResolver.kt` if the wrapper is no longer needed).
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt` — delegate to it.

## Verification

1. **Confirm desktop surfaces the period fields**: verify the `forecast` row read in
   `DesktopDailyForecastModel` exposes `daytimePrecipProbability`/`nighttimePrecipProbability`
   (columns exist; ensure the in-memory model/DAO maps them). If not populated, that mapping is the
   real gap and must be fixed for the directNws path to yield 15%.
2. **Unit test (`:shared`, plain JUnit)** for `resolveDailyLabelPrecip`: direct-NWS returns the
   period value (15%) over hourly max (2%); non-NWS returns hourly `dayMax` with period fallback;
   past returns null. Mirrors the project's pure-function test style.
3. **Android regression**: existing `DailyViewLogicTest` / `DailyViewHandlerTest` still pass
   (`./gradlew :app:testDebugUnitTest --tests "*DailyView*"`) — behavior unchanged.
4. **End-to-end**: `./gradlew :desktop:compileKotlin`; run desktop (`scripts/buildStart.sh`) and
   confirm the today column now shows 15% (matching Android on the same NWS data). Compare against
   the Android widget screenshot for the same location/source.
