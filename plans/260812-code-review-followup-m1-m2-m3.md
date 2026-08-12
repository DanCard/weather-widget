# Code Review Follow-up — M1, M2, M3, and the desktop location fallback

**Source:** `plans/260812-code-review-refresh-coordination.md` (refresh/update-coordination subsystem review).
**Status:** H1 (silent "Mountain View" fallback) is already fixed on Android. This plan addresses the
remaining findings that still make sense after the H1 changes: M2, M3, M1, plus the same class of
leftover on desktop.

## What is in scope (and why)

| Finding | Verdict | Action |
|---|---|---|
| M2 — scattered battery thresholds | still valid | centralize thresholds in `BatteryTier` (shared) |
| M3 — periodic cadence not re-armed on battery drift | still valid | re-arm cadence at end of each full sync |
| M1 — dead location-aware overloads + deprecated constants | still valid | delete dead overloads & unused deprecated constants |
| Desktop `FALLBACK_LATITUDE/LON` = Google HQ | new (same class as H1) | remove the sentinel fallback |

Out of scope (still valid but lower impact): M4 (global cooldown), M5 (worker size), L1–L4.

## 1. M2 — centralize battery thresholds in `BatteryTier`

`BatteryTier` is the shared, pure owner of battery *percent* thresholds. Move the remaining
scattered numbers there and derive everything else from it.

- `BatteryTier` (shared) gains:
  - `TREAT_AS_CHARGING_THRESHOLD = 80` + `fun treatAsCharging(isCharging, batteryLevel)` — "battery
    high enough to use the aggressive charging cadence even unplugged".
  - `FULL_BATTERY_LEVEL = 100` — "a full battery counts as effectively charging".
  - `OPPORTUNISTIC_MIN_BATTERY_PERCENT = 65` — opportunistic (piggyback) network cutoff.
- `ForecastFetchPolicy` replaces its two inline `isCharging || batteryLevel >= 80` with
  `BatteryTier.treatAsCharging(...)`.
- `BatteryStatePolicy.isEffectivelyCharging` uses `BatteryTier.FULL_BATTERY_LEVEL` instead of the
  bare `100` literal. (The Android-only `BatteryManager` status/plugged constants stay here.)
- `CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT` becomes an alias to the shared value so
  existing call sites keep working but the value lives in one place.

Distinction preserved on purpose: `isEffectivelyCharging` (physically plugged/full) must NOT widen
to `>= 80`, because that would turn on the charging current-temp loops at 80% off-charger. Only the
forecast *cadence* uses `treatAsCharging`.

## 2. M3 — re-arm the periodic cadence each full sync

`ACTION_BATTERY_CHANGED` cannot be manifest-registered (sticky broadcast), so the fix is to let each
full-sync run re-pin the periodic interval to the battery state it just observed — the
self-correcting option the review suggested. In `WeatherWidgetWorker.handleFullSyncWork`, the
non-UI-only success branch already calls `UIUpdateScheduler.scheduleNextUpdate()`; add
`WidgetWorkScheduler.schedulePeriodicSync(context)` beside it. `schedulePeriodicSync` already uses
`ExistingPeriodicWorkPolicy.UPDATE` (safe, no cancellation — see AGENTS.md).

Net effect: the cadence no longer stays pinned to a stale startup/power value; it self-corrects
within one cycle of any battery-level drift.

## 3. M1 — delete dead overloads and unused deprecated constants

`WidgetStateManager` still carries `getEffectiveVisibleSourcesOrder(lat, lon)` and
`isSourceVisible(source, lat, lon)` (both `@Suppress("UNUSED_PARAMETER")`, silently delegating), and
six `@Deprecated` source-name constants that nothing references.

- Delete the two location-aware overloads; point the two production callers
  (`ForecastHistoryActivity`, `WeatherObservationsActivity`) at `getVisibleSourcesOrder()`.
- Delete the six unused `@Deprecated` constants (`SOURCE_NWS`, `SOURCE_OPEN_METEO`,
  `SOURCE_VISUAL_CROSSING`, `SOURCE_OPEN_WEATHER_MAP`, `SOURCE_WEATHER_API`, `SOURCE_GENERIC_GAP`).
- Update the two tests that touched the deleted overload.

## 4. Desktop — remove the Google-HQ sentinel fallback

`DesktopWeatherService` still has `FALLBACK_LATITUDE/LONGITUDE = 37.4220/-122.0841` used only by the
`constructor(config: DesktopConfig?)` fallback. On inspection this fallback is never used to *display*
weather (the repository is only built when config is non-null, and `lat`/`lon` are non-null fields),
but it is the same misleading sentinel. Remove it:

- Drop the `FALLBACK_LATITUDE/LONGITUDE` constants; make the secondary constructor take a non-null
  `DesktopConfig` and read `config.lat`/`config.lon` directly.
- `Main.kt` builds the service only from a non-null config (make `weatherService` nullable); the
  repository construction already guards on non-null config.

## Tests

- New `BatteryTierTest` (shared, `@Category(ShortDuration::class)`) covering `treatAsCharging` and
  the consolidated constants.
- Update `WidgetStateManagerTest`, `WeatherRepositoryTest` for the removed overload.
- Existing `ForecastFetchPolicyTest`, `BatteryStatePolicyTest`, `WeatherWidgetProviderEnqueuePolicyTest`
  must keep passing unchanged (behavior is preserved).

## Verification

1. `./gradlew :shared:test` (BatteryTier tests + unchanged shared suite)
2. `./gradlew :app:testShortDebugUnitTest` (ForecastFetchPolicy/BatteryStatePolicy/WidgetStateManager)
3. `./gradlew :desktop:testShortDesktop` (desktop compile + tests)
4. `./gradlew :app:assembleDebug :desktop:createDistributable` (full compile)
