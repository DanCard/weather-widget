# Open-Meteo low-cloud view parity

## Evidence

1. On emulator widget 59, Open-Meteo Thursday 2026-08-27 rendered as 100% cloudy in
   the daily view but 0-2% in the hourly cloud graph.
2. The stored Open-Meteo rows contained `cloudCover=100` and `cloudCoverLow=0` at noon.
3. The daily view reads total `cloudCover`; the hourly graph preferred `cloudCoverLow`.
4. Open-Meteo is the only forecast client that populates both fields. Other forecast sources
   populate total cloud only and therefore do not exhibit this divergence.

## Decision

1. Keep the hourly graph on low cloud cover. The user confirmed that Open-Meteo's reported 100%
   total cloud often does not match visibly clear conditions near noon.
2. Change the shared daily noon selector to `cloudCoverLow ?: cloudCover`, making Android and
   desktop daily bars use the same near-surface cloud quantity as their hourly graphs.
3. Preserve the existing hourly `cloudCoverLow ?: cloudCover` behavior across shared, Android, desktop,
   and Open-Meteo Previous Runs. Other forecast sources provide total only and use the fallback.
4. Add integration coverage for the observed `total=100, low=0` divergence so either view changing
   away from low-cloud preference causes a regression failure.

## Tests

1. Retain the existing focused shared and Android low-first tests.
2. Add a Robolectric integration test spanning Android hourly entities, the Android handler, the
   shared series builder, the shared daily noon resolver, and the Android graph renderer. Reproduce
   `total=100, low=0` and assert both hourly and daily inputs remain 0%.
3. Run the integration test plus focused shared/Android coverage and desktop tests/compile.
