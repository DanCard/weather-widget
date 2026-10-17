# Forecast History Refresh Registers Snapshot and Freshness — Summary

## Context & Root Cause

When refreshing a data source (e.g. Silurian) from Forecast History on Galaxy Z Fold 4:
1. The fetch ran and succeeded in ~1s, but upstream forecast predictions (high/low/condition) had not changed since the earlier fetch.
2. `ForecastSnapshotStore` deduplicated identical predictions via `SNAPSHOT_SKIP`, so no new daily snapshot row was added to `forecasts`. As a result, the evolution graph drew no new point and the snapshot count did not increment.
3. `ForecastHistoryActivity.updateFreshnessCard()` only queried `FetchMetadata.getLastFullFetchTime()`, ignoring the single-source success timestamp `last_forecast_source_success_<source>_<lat>_<lon>`. The card continued to show the older multi-source fetch time (e.g. "35min ago") instead of "just now".
4. To the user, it appeared as though the requested refresh had not happened.

## What Changed

- **Snapshot Recording on User Refresh** (`ForecastSnapshotStore.kt`):
  - Added `userRefresh: Boolean = false` to `saveForecastSnapshot`.
  - When `userRefresh == true`, bypasses `SNAPSHOT_SKIP` even if values are unchanged upstream, creating a new snapshot record with `fetchedAt = nowMs`. Debounces rapid duplicates within 60s.
  - Skips in-bucket deletion (`deleteForecastsInBucket`) for `userRefresh == true`, preserving earlier historical predictions in that bucket rather than deleting them.
  - Standardized `nowMs` usage across `ForecastSnapshotStore` so bucket windows (`timestampToGroupPredictions`) and default batch timestamps stay aligned with simulated/provided timestamps.
  - Piped `userRefresh` from `ForecastRepository.fetchSourceOnDemand` through `ForecastFetchCoordinator.fetchSingleSource` / `saveDailyBatch` into `snapshotStore.saveForecastSnapshot`.

- **Source-Specific Freshness Display** (`ForecastHistoryActivity.kt`):
  - Extracted helper `resolveForecastFetchTime(sourceSuccessMs, lastFullFetchMs)` in `Companion`.
  - In `updateFreshnessCard()`, query `FetchMetadata.getLastForecastSourceSuccessTime(this, source.id, targetLat, targetLon)` for the viewed source before falling back to `lastFullFetchTime`.
  - Refreshing a single source now immediately updates the card to "Forecast fetch: just now".

## Verification

- **Unit Tests**:
  - `ForecastSnapshotDeduplicationTest.kt`: Added `userRefresh creates a snapshot even when forecast values are identical` (verifying routine background sync skips unchanged data while `userRefresh = true` writes a new snapshot row without deleting earlier records).
  - `ForecastHistoryActivityTest.kt`: Added test cases for `resolveForecastFetchTime` precedence (source success time preferred over full fetch time; fallback when 0).
  - `ForecastSnapshotHindcastCutoffTest.kt`: Verified all 9 hindcast/cutoff tests pass with bucket time alignment.
  - Ran `./scripts/unit-tests.sh`: All 5,448 JVM unit tests passed across `:shared`, `:desktop`, and `:app` (Short, Medium, Long, and Localization buckets).
  - Ran `./gradlew :desktop:test`: Desktop suite verified.

- **On-Device**:
  - Debug APK built and installed via `adb -s RFCT70G352Y install -r app/build/outputs/apk/debug/app-debug.apk` to Galaxy Z Fold 4.
