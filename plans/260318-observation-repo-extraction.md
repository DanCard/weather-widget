# Extract Observation logic into ObservationRepository

## Objective
Unify the handling of NWS observation data by extracting `fetchNwsCurrent` and `fetchDayObservations` from their respective repositories (`CurrentTempRepository` and `ForecastRepository`) into a dedicated `ObservationRepository`. This separates "actuals" (ground truth observations) from "forecasts", improving architectural clarity and reducing code duplication.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` (New File)
- `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`
- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`
- `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`
- Test files associated with `CurrentTempRepository` and `ForecastRepository`.

## Proposed Solution
1. **Create `ObservationRepository`:**
   - Inject `NwsApi`, `ObservationDao`, `DailyExtremeDao`, `AppLogDao`, and `@ApplicationContext Context` (for SharedPreferences caching).
   - Move `getSortedObservationStations(stationsUrl: String)` here, unifying the duplicated caching logic into a single `_v4_` cache key.
   - Move `fetchStationObservation` and `fetchNwsCurrent` from `CurrentTempRepository`.
   - Move `fetchDayObservations` from `ForecastRepository` (both overloads).
   - Define necessary data classes within or alongside `ObservationRepository` if needed (e.g., `ObservationResult` and `CurrentReadingPayload` may need to be accessible, though `CurrentReadingPayload` could stay in `CurrentTempRepository` with `ObservationRepository` returning a raw or slightly different type. Alternatively, we can move `CurrentReadingPayload` to a shared model package or keep it if it depends on generic types).

2. **Update `CurrentTempRepository`:**
   - Inject `ObservationRepository`.
   - Delegate the NWS branch of `fetchFromSource` to `observationRepository.fetchNwsCurrent(latitude, longitude)`.
   - Remove the old private observation methods.

3. **Update `ForecastRepository`:**
   - Inject `ObservationRepository`.
   - Replace internal `fetchDayObservations` calls with `observationRepository.fetchDayObservations(...)`.
   - Remove the old private observation methods.

4. **Update `WeatherRepository`:**
   - Update any `fetchDayObservations` test-visible delegates to point to `observationRepository`.

## Implementation Steps
1. Create `ObservationRepository.kt` and paste the unified methods.
2. Update `CurrentTempRepository` to consume `ObservationRepository`.
3. Update `ForecastRepository` to consume `ObservationRepository`.
4. Update Dagger/Hilt setups if necessary (none needed as `@Singleton class ObservationRepository @Inject constructor(...)` will work automatically).
5. Fix any broken test cases that were mocking these functions on the old repositories by mocking them on `ObservationRepository`.

## Verification & Testing
- Run unit tests: `./gradlew test`
- Build and run on an emulator: verify that the widget can still fetch current NWS temperature and still populates daily "Actual" values.
