# Session Summary: Unify Fetch Location and Background GPS Auto-Healing

## Context & Objectives
Two co-located phones (Pixel, Samsung) charging all day reported different "yesterday high" temperatures due to fetching weather from different locations (Pixel used real GPS coordinates, while Samsung fell back to the hardcoded default coordinates). This difference resulted in inverse-distance-weighted station blending discrepancies.
The root causes identified:
1. **Split-brain fetch location**: The background fetch resolved location using the widget SharedPreferences first, but current-temperature, non-primary, cache-refresh, and intent-routing paths defaulted to the database location (S2/S3).
2. **Stale latch never re-heals**: GPS auto-healing was gated on `LocationUpdater.allWidgetsAtDefault`, meaning once any widget got a non-default/stale coordinate, the auto-healing never checked again.

The goal was to:
1. Unify the fetch location source via a consolidated resolver.
2. Periodically query the GPS (degrading to best-effort passive locations if permissions are missing) during background fetches when charging or battery > 70%, and auto-heal if coordinates diverge.
3. Relax the auto-heal gate to heal any wrong/stale widget coordinates.

## Implementation Details

1. **Unified Location Resolution**:
   - Created [ActiveLocationResolver](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/ActiveLocationResolver.kt) to resolve coordinates:
     - Checked configured widget SharedPreferences first (S6).
     - Fell back to the latest weather coordinate in the database (S2).
     - Defaulted to hardcoded Googleplex coordinates (S3).
   - Replaced all four `getLatestLocation()` calls in `WeatherWidgetWorker` with the new unified resolver.
   - Refactored `WidgetIntentRouter.resolveLocation` and `WidgetIntentRouter.resolveRefreshContext` to also resolve via `ActiveLocationResolver`, making both functions `suspend`.

2. **Background GPS Re-sampling**:
   - Injected `SharedLocationResolver` into `WeatherWidgetWorker` constructor.
   - Added `sampleGpsAndMaybeUpdateLocation()` inside `WeatherWidgetWorker.doWork()`, executed when the device is plugged/charging or battery > 70%.
   - Inside `sampleGpsAndMaybeUpdateLocation`, actively requests high-accuracy coordinates (falling back to passive `lastLocation` if `ACCESS_BACKGROUND_LOCATION` is missing). If the coordinates diverge from the widget's current location (not `sameSite`), calls `LocationUpdater.applyToAllWidgets(...)` to heal the widget coordinates.

3. **Heal Gate Relaxation**:
   - Added `shouldHealTo(context, freshLat, freshLon)` to `LocationUpdater` which returns true if any widget is not `sameSite` (within 200m tolerance) with the fresh coordinates.
   - Updated `MainActivity.maybeAutoHealLocationFromGps()` to check `shouldHealTo`, using `lastLocation` as a cheap, low-overhead pre-check before requesting high-accuracy GPS.

4. **DAILY_HISTORY_BLEND Logging**:
   - Appended `userLat` and `userLon` to the `DAILY_HISTORY_BLEND` log in `ObservationRepository.recomputeDailyExtremesForDay()` for easier diagnostics.

5. **Test Coverage**:
   - Created `ActiveLocationResolverTest` to test resolving precedence (configured vs database vs default).
   - Created `LocationUpdaterTest` to test `shouldHealTo` with matching, default, and stale coordinates.

## Verification Results

* **Local Unit Tests**:
  - Ran `./gradlew test` successfully. All unit tests, including the new Robolectric suites, passed.
* **Instrumented Tests**:
  - Ran `./scripts/emulator-tests.sh` successfully on the `Generic_Foldable_API36` emulator. All 64 tests passed.

## Recommended Commit Message

```git
Unify widget fetch location and implement background GPS auto-healing

Summary of Changes:
- Consolidated location lookup layers into ActiveLocationResolver, ensuring all background fetch and current-temp paths prioritize the widget's configured location, falling back to the database coordinates and then default coordinates.
- Added a background GPS re-sampling process in WeatherWidgetWorker.doWork. When plugged/charging or battery > 70%, actively queries GPS and auto-heals widget preferences if the fix is not same-site with the configured coordinates.
- Replaced the restrictive MainActivity.allWidgetsAtDefault gate with LocationUpdater.shouldHealTo to allow correcting stale-but-non-default coordinates, using lastLocation as a cheap pre-check.
- Added userLat and userLon parameters to the DAILY_HISTORY_BLEND app log message in ObservationRepository.
- Created ActiveLocationResolverTest and LocationUpdaterTest covering the resolver precedence and shouldHealTo logic under Robolectric.

Verification:
- Run all local unit tests (./gradlew test) -> BUILD SUCCESSFUL (all tests passed)
- Run all instrumented tests (./scripts/emulator-tests.sh) -> Test Summary: All 64 tests passed
```
