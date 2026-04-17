# Plan: Fix Source-Aware Staleness Bug

The current staleness logic in `DataFreshness` has a "cross-source" bug where it considers all data fresh if *any* source (e.g., Open-Meteo) was fetched recently, even if the primary displayed source (NWS) is stale. This plan refactors the staleness check to be source-aware, ensuring that if any visible source is stale according to its specific policy, a network fetch is triggered.

## Objective
Fix the `DataFreshness.isDataStale` logic to check the staleness of all currently visible sources individually, using thresholds from `ForecastStalenessPolicy`.

## Key Files & Context
- `app/src/main/java/com/weatherwidget/widget/DataFreshness.kt`: Main logic for staleness checking.
- `app/src/main/java/com/weatherwidget/widget/ForecastStalenessPolicy.kt`: Defines per-source staleness thresholds (60/90/120 min).
- `app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`: Provides `getLatestWeatherBySource`.
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`: Provides `getVisibleSourcesOrder`.

## Testing Strategy

### 1. Robolectric Test (`DataFreshnessRoboTest.kt`)
We will add a Robolectric test to verify the source-aware staleness logic in a controlled environment.

**Test Scenarios:**
- **Single Source fresh**: If only NWS is visible and it was fetched 10 minutes ago, `isDataStale` returns `false`.
- **Single Source stale**: If only NWS is visible and it was fetched 70 minutes ago, `isDataStale` returns `true`.
- **Mixed Sources (One stale)**: If NWS and Open-Meteo are both visible, and Open-Meteo was fetched 5 minutes ago but NWS was fetched 70 minutes ago, `isDataStale` returns `true` (this is the scenario that currently fails).
- **Mixed Sources (All fresh)**: If both are visible and both were fetched < 30 minutes ago, `isDataStale` returns `false`.
- **No Visible Sources**: If no sources are currently visible (e.g. no widgets), `isDataStale` should return `false` to avoid unnecessary background work.

### 2. Implementation Plan

#### Step 1: Update `DataFreshness.isDataStale`
Refactor `isDataStale` to iterate over all visible sources and check their individual staleness.

```kotlin
// DataFreshness.kt
suspend fun isDataStale(context: Context): Boolean {
    return try {
        val database = WeatherDatabase.getDatabase(context)
        val forecastDao = database.forecastDao()
        val stateManager = WidgetStateManager(context)
        
        // Get the list of sources currently displayed on active widgets
        val visibleSources = stateManager.getVisibleSourcesOrder()
        if (visibleSources.isEmpty()) {
            Log.d(TAG, "No visible sources found, skipping stale check")
            return false
        }

        val nowMs = System.currentTimeMillis()
        
        for (source in visibleSources) {
            val latestForSource = forecastDao.getLatestWeatherBySource(source.id)
            if (latestForSource == null) {
                Log.d(TAG, "Source ${source.id} has no data, considering stale")
                return true
            }

            // Use batchFetchedAt to represent the age of the forecast set
            val ageMs = nowMs - latestForSource.batchFetchedAt
            val position = visibleSources.indexOf(source)
            val thresholdMs = ForecastStalenessPolicy.getStalenessThresholdMs(position)
            
            val isSourceStale = ageMs > thresholdMs
            if (isSourceStale) {
                Log.d(TAG, "Source ${source.id} is stale (age=${ageMs/60000}m, threshold=${thresholdMs/60000}m)")
                return true
            }
        }

        Log.d(TAG, "All visible sources are fresh")
        false
    } catch (e: Exception) {
        Log.e(TAG, "Error checking data staleness", e)
        true // Safer to assume stale on error
    }
}
```

#### Step 2: Create `DataFreshnessRoboTest.kt`
Create the Robolectric test in `app/src/test/java/com/weatherwidget/widget/DataFreshnessRoboTest.kt`.

#### Step 3: Verification
- Run the new Robolectric tests: `./gradlew testDebugUnitTest --tests DataFreshnessRoboTest`
- Verify that NWS fetches are triggered on screen-on even if Open-Meteo was recently fetched on a physical device.
- Verify that logs correctly identify which source triggered the staleness.
- Check that the `STALENESS_THRESHOLD_MINUTES` constant can be removed from `DataFreshness.kt`.

