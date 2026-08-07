# Plan: Desktop header rain-chance font size — parity with Android

Date: 2026-08-07

## Problem

1. The **desktop** header shows the rain chance at a **fixed 12sp** (`Main.kt` `WidgetHeader`,
   `fontSize = (12 * scale).sp`), regardless of how high the chance actually is. The user finds it
   too big, and it ignores everything Android learned about sizing this label.
2. **Android** does something smarter: the header rain chance is sized by probability via a
   **shared** step table, so a trace chance is tiny and a near-certain chance is full size. The
   user wants the desktop to "do the same what Android does" and to share the code, not copy it.

## Evidence collected

1. **Android sizing** (`app/.../util/HeaderPrecipCalculator.kt`):
   - `getPrecipTextSize(prob) = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * getPrecipScaleFactor(prob)`
   - `PRECIP_TEXT_BASE_SIZE_DP = 18f` — deliberately **equal to the header temp base size**
     (`CURRENT_TEMP_TEXT_SIZE_DP = 18f`), so a ≥65% chance renders at the same size as the temp.
   - `getPrecipScaleFactor` delegates to `shared/.../DailyRainLabels.precipProbabilityScaleFactor`
     (step table: 0.3 at ≤1%, 0.4 at ≤2%, 0.5 at ≤4%, 0.6 at ≤8%, 0.7 at ≤16%, 0.8 at ≤32%,
     0.9 at ≤64%, 1.0 above).
2. **Android night shrink** (`app/.../handlers/DailyHeaderResolver.kt` lines ~185–193):
   - In the **daily view only**, the header precip size is additionally multiplied by
     `HeaderPrecipCalculator.NIGHT_SCALE = 0.72f` when
     `isNext8HourPrecipPredominantlyNight(...)` is true (more than half of the probability-weighted
     minutes of the next 8h fall after sunset / before sunrise, using
     `SunPositionUtils.getSunTimes(...).sunriseHour/sunsetHour`).
   - The hourly/precip/cloud views (`TemperatureStateResolver`, `PrecipViewHandler`,
     `CloudCoverViewHandler`) do **not** apply the night factor.
3. **What is already shared** (`:shared`):
   - `shared.util.PrecipProbabilityCalculator.getNext8HourPrecipProbability` — used by both
     platforms (desktop `Main.kt` line ~1445).
   - `shared.util.DailyRainLabels.precipProbabilityScaleFactor` and `NIGHT_SCALE = 0.72f`.
   - `com.weatherwidget.util.SunPositionUtils.getSunTimes(dateTime, lat, lon)` (shared module,
     already used by desktop graph code in `TemperatureGraph.kt` / `DesktopGraphUtils.kt`).
4. **What is NOT shared**: `isNext8HourPrecipPredominantlyNight` lives only in
   `app/.../util/HeaderPrecipCalculator.kt` (pure JVM logic over shared `HourlyForecast` models —
   no Android dependencies). It is a natural candidate to move to `:shared`.
5. **Desktop context**: `WidgetHeader` has `config.lat`/`config.lon` (for sun times),
   `config.viewMode` (to distinguish daily vs hourly), `forecast.hourly`, and the header temp base
   size is `15sp * scale`.

## Proposed change

### Step 1 — `:shared`: move the night-predominance check into shared code

1. Add `isNext8HourPrecipPredominantlyNight(hourlyForecasts, displaySourceId, fallbackSourceId,
   referenceTime, sunriseHour, sunsetHour): Boolean` to
   `shared/.../util/PrecipProbabilityCalculator.kt`, ported from
   `HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight`:
   - Same minute-by-minute probability-weighted day/night accumulation over the 8h window.
   - Source filtering mirrors the shared `getNext8HourPrecipProbability` convention
     (`source == null || source == displaySourceId`, then fallback source).
   - Reuse the existing private `interpolatePrecipProbabilityAt` (hour-truncated lookup + linear
     interpolation) already in the shared file.
   - Keep the polar guards: `sunsetHour >= 24.0` → false; `sunriseHour <= 0.0` → true.

### Step 2 — `:app`: delegate (no behavior change)

1. `HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(...)` becomes a thin delegate to the
   shared function after `toHourlyForecast()` mapping (same pattern as its existing
   `getNext8HourPrecipProbability` delegate). Signature unchanged; existing app callers/tests keep
   working.
2. Unify the duplicated constant: `HeaderPrecipCalculator.NIGHT_SCALE` delegates to
   `DailyRainLabels.NIGHT_SCALE` (both are 0.72f today; removes a drift hazard).

### Step 3 — `:desktop`: size the header rain chance like Android

In `desktop/.../Main.kt` `WidgetHeader`:

1. Compute the rain-chance font size as
   `headerTempBaseSp * DailyRainLabels.precipProbabilityScaleFactor(precipProb) * nightFactor * scale`,
   where:
   - `headerTempBaseSp = 15f` (the desktop header temp size — the Android analog uses the temp base
     of 18dp there; both platforms size the rain chance *relative to their own header temp*).
   - `nightFactor = DailyRainLabels.NIGHT_SCALE` only when the view is **daily**
     (`!config.viewMode.isHourly`) **and** the new shared
     `PrecipProbabilityCalculator.isNext8HourPrecipPredominantlyNight(...)` returns true with
     `SunPositionUtils.getSunTimes(nowLocal, config.lat, config.lon)`; otherwise 1f — matching the
     Android daily-view-only behavior.
2. Extract the size computation into a small pure function (new
   `desktop/.../HeaderPrecipSizing.kt`, e.g.
   `fun headerPrecipFontScale(precipProb: Int, isDailyView: Boolean, isNightPrecip: Boolean): Float`)
   so it is unit-testable without Compose. `WidgetHeader` calls it and multiplies by
   `(15 * scale).sp`.
3. Resulting desktop sizes (before window `scale`): ~4.5sp at ≤1% · 7.5sp at 3–4% · 9sp at 5–8% ·
   10.5sp at 9–16% · 12sp at 17–32% · 13.5sp at 33–64% · 15sp at ≥65% — i.e. *smaller than today*
   for anything below ~32%, and equal to the temp size for near-certain rain (exactly Android's
   ratio, since Android's precip base == temp base).

## Testing

1. `:shared` — add tests in `shared/src/test/.../PrecipProbabilityCalculatorTest.kt` (or new file)
   for `isNext8HourPrecipPredominantlyNight`: day-only rain → false; night-only rain → true; mixed
   weighted majority; empty/no-probability data → false; polar guards. Category: Short.
2. `:app` — existing `HeaderPrecipCalculatorTest` keeps passing (delegation is behavior-preserving);
   add one assertion that `HeaderPrecipCalculator.NIGHT_SCALE == DailyRainLabels.NIGHT_SCALE` if not
   already covered.
3. `:desktop` — new pure unit tests for `HeaderPrecipSizing.headerPrecipFontScale`: step-table
   values pass through; night factor applies only when `isDailyView && isNightPrecip`; hourly view
   never shrinks. Category: Short.
4. Build/verify: `./gradlew :shared:testByDurationShared :desktop:testByDurationDesktop` and
   `./gradlew :app:testShortDebugUnitTest` (focused buckets) — plus a quick
   `./gradlew :desktop:run` visual check that the header rain chance scales with probability.

## Out of scope / notes

1. No change to Android runtime behavior — Step 2 is a pure refactor (delegation + constant
   unification).
2. Desktop hourly/precip/cloud views intentionally do **not** get the night factor, mirroring
   Android.
3. The desktop 12sp→variable change may make high chances (≥65%) *larger* than today's fixed 12sp
   (15sp, equal to the temp). That is the intended Android ratio, not a regression.
