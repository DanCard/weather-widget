# Today's low label: thermostat color whenever the printed value is the actual

## Rule (user, 2026-10-06)

If the low number printed under today's column **is** the observed actual, draw it in the
thermostat color (`WeatherColors.OBSERVED`, #FF88AA). Otherwise white (78% alpha on desktop).

## Current behavior and the gap

The color comes from `DailyDayValueResolver.isLowTrackingActual`, which is time-gated:
true only when `nowHour >= 9` and an actual low exists.

The *value* comes from `effectiveLowForLabel`:
- **≥ 9:00 with an actual:** the observed low. The color check says pink, which matches.
- **< 9:00:** `min(observed low, forecast low)`. When the night has already gone colder than
  forecast, the printed number **is** the observed actual, but it is drawn white.

So the color answers "is it past 9am?" when it should answer "is this number the actual?".

## Fix

Base the color on where the printed value came from, not on the time of day.

1. `:shared` `DailyDayValueResolver`: replace `isLowTrackingActual(isToday, solidLow, nowHour,
   actualLow)` with `isLowLabelActual(isToday, printedLow, actualLow)`, which returns
   `isToday && actualLow != null && printedLow == actualLow`. When there is an actual low,
   `effectiveLowForLabel` returns either `solidLow` (which is `actualLow`) or the min that
   includes it, so the exact `==` is safe. A tie with the forecast counts as actual.
2. Desktop `DailyForecastGraph.kt:520`: pass `lowForLabel` and `day.actual?.computedLowTemp`.
   The past-day branch (`isPast && !solidIsForecastFallback`) stays unchanged.
3. Android `DailyColumnRenderer.kt:104`: pass `displayLow` and the same `actualLow` expression.
4. Tests in `DailyDayValueResolverTest`:
   - before 9am, observed colder than forecast → actual
   - before 9am, forecast colder → not actual
   - after 9am, actual present → actual
   - no actual (forecast-only source) → never actual
   - a tie → actual
   - not today → false

The forecast-only safeguard stays: without `actualLow` the result is always false.

## Also fixed (user, same day): desktop/Android printed different lows before 9am

Desktop folded the 24h-prior snapshot into the forecast candidate
(`min(forecastLow, snapshotLow)`, a holdover from e2943551 "to preserve its pre-cutoff blend");
Android passes only the live forecast low. Resolved toward Android: the snapshot is a comparison
bar, not a headline candidate — the rule `effectiveHighForLabel` already documents, and desktop's
high already followed. Desktop's three call sites (label, label Y, tap layout) now go through one
`DesktopDailyDay.lowForLabel()` passing `forecastLow` only; the rule is documented on the shared
`effectiveLowForLabel`.

## Verification

- `./gradlew :shared:test --tests "*DailyDayValueResolverTest*"`, `testDebugUnitTest`,
  `:desktop:test`
- Desktop popup: restart via `scripts/buildStart-desktop.sh` and check today's low color.
