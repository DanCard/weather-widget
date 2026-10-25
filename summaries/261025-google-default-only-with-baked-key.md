# Google defaults on in debug builds only when a key is baked

## Problem

Android debug builds put `GOOGLE_WEATHER` first (as primary) in the fresh-install visible-source
list based on `BuildConfig.DEBUG` alone. The key comes from the builder's gitignored
`local.properties` or `GOOGLE_WEATHER_API_KEY` env var, and is `""` when neither is set. So a debug
build made on another machine defaulted to a primary source it could not fetch, and the one-time
Google-primary migration switched existing widgets to it.

## Changes

1. **`DefaultVisibleSources.kt` (new):** pure `forBuild(debug, hasBakedGoogleKey)` holding the rule.
   Google is first only when the build is debug *and* baked a key. Release defaults and the rest of
   the debug list are unchanged.
2. **`WidgetStateManager.kt`:** `DEFAULT_VISIBLE_SOURCES` is built from that function, using
   `BuiltInApiKeys.baked(GOOGLE_WEATHER) != null`.
3. **Migration covered too:** `migrateGoogleWeatherEnabledIfNeeded()` is gated on Google being in
   `defaultVisibleSources`, so a keyless debug build no longer switches existing widgets to Google.
   Its comment in `WeatherSourcePreferences.kt` now says so.
4. **Tests:**
   - `DefaultVisibleSourcesTest` (new, Short) checks every combination of debug/release and
     key/no key.
   - `GoogleWeatherPrimaryIntegrationTest` now gets its defaults from the real function and has a
     new keyless-debug case. On a fresh install NWS is primary; on an existing install the stored
     list and both widgets' sources stay as they were.
   - `WidgetStateManagerTest`'s fresh-install test expects Google only if this machine baked a key,
     so it passes with or without a key.
5. **`arch/operations.md`:** source-defaults paragraph updated to the new behavior.

## Verification

- `DefaultVisibleSourcesTest` 3/3, `GoogleWeatherPrimaryIntegrationTest` 7/7, `WidgetStateManagerTest`
  57/57 passed.
- Not installed on a device: the developer's builds bake a key, so their behavior is unchanged.

## Scope notes

- **Desktop:** nothing to mirror. `WeatherSourceOrdering.DEFAULT_VISIBLE_IDS` never included Google.
- **Tomorrow.io:** still in the debug defaults regardless of key. Its key also comes from
  `local.properties`, so a keyless debug build lists it without being able to fetch. Left unchanged
  because only Google was requested; the same `takeIf` would fix it.

## Insights

- The old test asserted "Google first in debug". It only passed because the build machine had a key,
  so it could never catch this bug. The new pure test passes the key condition in explicitly.
- Because the migration's guard reads `defaultVisibleSources`, fixing the defaults in one place also
  fixed the second symptom.
