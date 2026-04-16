# Fix Tomorrow.io Leak in Current Observations

## Objective
Prevent Tomorrow.io observations (e.g., `TOMORROW_IO_MAIN`) from appearing in the "Current Observations" activity when the National Weather Service (NWS) source is selected.

## Background & Motivation
When the Tomorrow.io API was integrated, it was added to various source enums and resolvers, but its prefix `TOMORROW_IO_` was omitted from the `sourcePrefixes` map in `WeatherObservationsActivity.WeatherObservationsSupport`. 

When the user selects the NWS source, the app attempts to exclude all non-NWS observations by checking the `sourcePrefixes` map. Because Tomorrow.io was missing from this map, its observations pass the exclusion filter and leak into the NWS view, causing Tomorrow.io observations to incorrectly display as NWS observations.

## Scope & Impact
- **Impacted Files**: 
  - `app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt`
  - `app/src/test/java/com/weatherwidget/ui/WeatherObservationsSupportTest.kt`
  - `app/src/test/java/com/weatherwidget/ui/WeatherObservationsActivityRobolectricTest.kt`

## Proposed Solution
1. **Update Prefix Map**: Add `WeatherSource.TOMORROW_IO to "TOMORROW_IO_"` to `WeatherObservationsSupport.sourcePrefixes` inside `WeatherObservationsActivity.kt`.
2. **Unit Test**: Add a test in `WeatherObservationsSupportTest.kt` asserting that `matchesObservationSource(stationId = "TOMORROW_IO_MAIN", source = WeatherSource.NWS)` returns `false`.
3. **Integration Test**: Add a new test case in `WeatherObservationsActivityRobolectricTest.kt` to explicitly verify that Tomorrow.io observations inserted into the database are not loaded into the UI when the activity is displaying NWS data.

## Verification
- Run `./gradlew testDebugUnitTest --tests "*WeatherObservationsSupportTest*"`
- Run `./gradlew testDebugUnitTest --tests "*WeatherObservationsActivityRobolectricTest*"`
- Verify manually on emulator: Launch "Current Observations" while NWS is the active source. Verify no Tomorrow.io stations are listed.