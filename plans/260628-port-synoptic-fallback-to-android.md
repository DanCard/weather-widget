# Plan: Port Synoptic Web Fallback to Android & Centralize in Shared Module

This plan outlines the architecture and steps required to move the Synoptic API observations fallback from a desktop-only feature to a unified `:shared` component and integrate it into the Android `:app` observations retrieval flow.

---

## 1. Architectural Changes

To maximize code sharing, the Synoptic client logic will be moved from `:desktop` to `:shared`. 

```mermaid
graph TD
    subgraph Shared Module (:shared)
        SynopticApi[SynopticApi.kt]
        NwsApi[NwsApi.kt]
    end
    subgraph Desktop App (:desktop)
        DesktopWeatherService[DesktopWeatherService.kt]
    end
    subgraph Android App (:app)
        ObservationRepository[ObservationRepository.kt]
    end
    
    SynopticApi -->|Uses Ktor / kotlinx.serialization| HTTP
    DesktopWeatherService -->|Calls shared| SynopticApi
    ObservationRepository -->|Calls shared| SynopticApi
```

---

## 2. Implementation Checklist

### Step 1: Create Shared `SynopticApi`
Create `shared/src/main/kotlin/com/weatherwidget/data/remote/SynopticApi.kt`:
* Implement `fetchSynopticObservations` taking `stationId`, `recentMinutes`, and a `HttpClient` instance.
* Return a list of `NwsApi.Observation` or a dedicated shared model.
* Implement the timezone offset parsing fallback logic (supporting `-0700` and `-07:00` format parsing) natively in the shared package.

### Step 2: Refactor Desktop App to Use Shared API
Modify `DesktopWeatherService.kt` in `:desktop`:
* Delete `fetchSynopticObservations` and `parseTimestamp`.
* Inject or instantiate `SynopticApi` using the desktop's `httpClient`.
* Update `fetchObservationBundles` to query the shared `SynopticApi`.

### Step 3: Integrate with Android `ObservationRepository`
Modify `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`:
* In `fetchDayObservations` and backfill methods:
  * When `nwsApi.getObservations(...)` returns empty or the latest returned observation is older than 1 hour, trigger the shared `SynopticApi.fetchSynopticObservations`.
  * Map the returned observations to `ObservationEntity` and write them to the local Room database (`observationDao`).

### Step 4: Update Dependency Injection (DI)
In Android module `:app`:
* Update `AppModule.kt` to provide `SynopticApi` as a `@Singleton` dependency injected with `@ApplicationContext` or the shared Ktor `HttpClient`.

---

## 3. Testing & Verification

### Unit Tests
* Move the unit test `parseTimestamp handles timezone offsets with and without colons` to a shared unit test in `:shared`.
* Add test cases in `:shared` verifying JSON parsing of Synoptic payloads with MockEngine.
* Ensure all tests compile and pass:
  ```bash
  ./gradlew :shared:test
  ./gradlew :desktop:test
  ```

### Android Instrumented Verification
* Run the Android test suite using the emulator runner to verify that database observations are correctly written:
  ```bash
  ./scripts/emulator-tests.sh
  ```
* Inspect emulator runtime logs using `adb logcat` during a manual refresh to ensure Synoptic requests succeed.
