# Limit Non-Active Charging Forecast Cadence

## Goal

Reduce API usage for enabled forecast sources that are not displayed by any widget while retaining
the existing active-source cadence.

## Policy Change

1. While charging (or at the existing charging-equivalent battery level of at least 80%), a
   non-active source becomes due after 6 hours when the screen is interactive.
2. Under the same power condition, a non-active source becomes due after 8 hours when the screen is
   off.
3. Active-source, off-charger, forced-refresh, and non-primary observation policies remain unchanged.

## Implementation

1. Change `ForecastFetchPolicy.CHARGING_SCREEN_ON_NONACTIVE_MINUTES` from 120 to 360.
2. Change `ForecastFetchPolicy.CHARGING_SCREEN_OFF_NONACTIVE_MINUTES` from 240 to 480.
3. Update focused unit tests and cover the existing battery-at-least-80 charging-equivalent path.

## Verification

1. Run `ForecastFetchPolicyTest`.
2. Run the short-duration app test bucket to catch policy-call-site regressions.
3. Assemble the debug APK and inspect the compiled policy constants.
4. Run `git diff --check`.

The existing worker constructs `ForecastFetchContext`, and `ForecastFetchCoordinator` applies the
returned interval to each enabled source's persisted `batchFetchedAt` timestamp. Waiting for a live
6- or 8-hour boundary is outside proportional verification for this constant-only policy change;
the pure policy tests cover both boundaries directly and compiled-artifact inspection confirms the
values packaged by the Android build.
