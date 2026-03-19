# NWS Observation Fallback Implementation Plan

## Background & Motivation
Currently, when the app fetches the latest current observation for a station from the NWS API using `/stations/{stationId}/observations/latest`, it fails if the latest reading contains a `null` temperature value (which happens frequently due to sensor intermittency, e.g., KNUQ station). When this occurs, the station is dropped entirely from the IDW (Inverse Distance Weighting) blend for that cycle. Instead of dropping the station, we can query recent historical observations and use the most recent valid reading as a fallback.

## Objective
Implement a fallback mechanism in `NwsApi.kt` that queries `/stations/{stationId}/observations?limit=10` when the `latest` endpoint returns a null temperature or fails. The fallback should find and return the most recent observation with a valid (non-null) temperature.

## Scope & Impact
- **`app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt`**:
  - Modify `getLatestObservationDetailed` to act as the entry point that handles the primary request and triggers the fallback if necessary.
  - Add a new private function `getRecentValidObservationDetailed(stationId: String, limit: Int = 10): Observation?` to handle the fallback query and parsing.
- **`app/src/test/java/com/weatherwidget/data/remote/NwsApiTest.kt`** (if exists, or similar test file):
  - Add tests to ensure the fallback mechanism correctly parses the `FeatureCollection` and selects the first valid temperature.

## Implementation Steps

### 1. Update `NwsApi.kt`
- Extract the JSON parsing logic for an individual observation's `properties` object into a reusable helper function `parseObservationProperties(props: JsonObject, stationId: String): Observation?`.
- Modify `getLatestObservationDetailed`:
  - Wrap the primary network call in a `runCatching` or `try-catch` block.
  - If the call succeeds but `temperature` is null, or if the call fails, log the event and trigger the fallback.
- Implement `getRecentValidObservationDetailed(stationId: String)`:
  - Make a GET request to `"$BASE_URL/stations/$stationId/observations?limit=10"`.
  - Parse the response as a `FeatureCollection`.
  - Iterate through the `features` array.
  - For each feature, extract the `properties` object and use the helper function to attempt parsing.
  - Return the first `Observation` that successfully parses (meaning it has a non-null temperature).
  - If the loop finishes without finding a valid temperature, return `null`.

### 2. Add Logging
- Add a log in `getLatestObservationDetailed` indicating when the fallback is triggered.
- Add a log in the fallback function indicating how old the selected data is (e.g., comparing the timestamp of the fallback observation to the current time, or just logging the timestamp).

### 3. Verification & Testing
- Unit test the JSON parsing logic with mocked NWS API responses containing `null` values followed by valid values.
- Verify through logcat or the emulator that when a station like `KNUQ` reports a `null` latest temperature, the fallback query is executed and successfully retrieves an older valid reading.

## Migration & Rollback
- This is an additive change with no database schema modifications.
- Rollback involves reverting the changes to `NwsApi.kt` to the previous state using Git.