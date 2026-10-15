# Settled hourly archive (up to 18 months) and regular retention pruning

## Context & Motivation

1. **Feature Capability**:
   The recently added **Months View** allows zooming out to browse 18 months of daily weather history.
   When tapping a day from several months or a year ago to inspect the daily forecast, having the **hourly curve** for that past day is a natural and powerful drill-down capability.
2. **Current Retention Breakdown & Root Cause**:
   Analysis of the Pixel 7 Pro database dump revealed that `hourly_forecast_history` has accumulated **119,397 rows** taking **28.1 MB** (pushing the database to 56 MB, blocking Google Cloud Backup):
   - **GPS Coordinate Fragmentation**: The phone accumulated **73 slightly different GPS coordinates** from driving/walking. Because `HistorySnapshotRetention` grouped by exact `(lat, lon)` down to 14 decimal places, transient locations had only 1 or 2 snapshots, preventing deduplication.
   - **`fetchedAt` vs `dateTime` Loophole**: Routine retention deleted `WHERE fetchedAt < 30 days ago`. Backfilled rows (e.g. `OPEN_METEO_PRIOR24`) with `dateTime` from August (60+ days ago) lingered because their `fetchedAt` was within 30 days.
   - **Missing Page Reclamation (VACUUM)**: SQLite does not shrink the `.db` file on `DELETE` without a `VACUUM`.
3. **Goal**:
   Instead of a manual one-time cleanup, establish a **permanent, automated, two-tier retention policy** that runs regularly on both Android and Desktop:
   - **Active Window (0–30 days)**: Retain necessary snapshot variants for accuracy and stitcher calculations.
   - **Settled Archive (30 days to 18 months / 547 days)**: Retain strictly **ONE settled row per hour** (24 rows/day) for the primary site, discarding all intermediate versions and transient coordinates.
   - **Purge (> 18 months)**: Complete deletion matching `daily_history`.
   - **Regular File Compaction**: Run `VACUUM` when freed space exceeds a safe threshold during idle/charging maintenance so the database stays under 25 MB.

---

## Storage & Capacity Budget

At ~235 bytes per row in SQLite (data + indexes):
* **1 year (365 days)** = 8,760 hours $\times$ 1 settled row $\approx$ **~2.0 MB** per source.
* **3 active sources for 18 months (547 days)** = 3 sources $\times$ 13,128 hours $\approx$ **~39,000 rows ($\approx$ 9.1 MB)**.
* **Total database footprint** (18-month daily history + 18-month settled hourly + 10-day observations + app logs) = **~12–16 MB**.
* **Android Google Cloud Backup compliance**: Safely under the 25 MB quota.

---

## Policy Specification

### 1. Two-Tier Hourly History Retention (`:shared` `RetentionPolicy`)

| Tier | Window | Retained Data | Purpose |
|---|---|---|---|
| **Tier 1: Active** | 0 to 30 days | Multi-snapshot versions per hour per active site (spec: `HistorySnapshotRetention`) | Near-term hourly graph, live stitcher, 1-day-ahead rain accuracy |
| **Tier 2: Settled Archive** | 30 to 547 days (18 months) | **Strictly 1 settled row per hour** for the primary site; all other drafts, snapshot variants, and transient GPS coordinates pruned | Drill-down from Months View to historical hourly graph |
| **Tier 3: Expired** | > 547 days | Deleted completely | Hard bounding of disk usage |

### 2. Settled Snapshot Selection Rule (`:shared` `HistorySnapshotRetention`) — Option B (Location Clustering)

For hours older than 30 days up to 18 months:
1. **City / Region Clustering via `LocationMatch.TOLERANCE_DEG = 0.1` (~7 miles)**:
   * Instead of discarding other cities you visited (e.g. Lviv, Kyiv, Mountain View), the pruner clusters coordinates by city area using `LocationMatch.TOLERANCE_DEG`.
   * For each city cluster, micro-variations and GPS jitter fragments (e.g. `49.841` vs `49.843`) are mapped to the cluster's canonical coordinate.
2. **Settled Winner**: Select exactly **one row per hour per source per city cluster**:
   * Pick the best available snapshot for that `(cluster, source, dateTime)`:
     * Prefer actual/observed rows (`isObservation == true` or latest `fetchedAt` after the hour elapsed).
     * If multiple snapshots exist, pick the newest bucket.
   * Discard all other redundant draft copies, interim forecast runs, and jitter fragments.
   * As a result, each visited city keeps its own clean, continuous 24-hour/day historical curve for the Months View, with zero duplicate snapshot overhead.

### 3. Timestamp Field Pruning Fix

* Change age-based pruning from `WHERE fetchedAt < cutoff` to:
  ```sql
  DELETE FROM hourly_forecast_history 
  WHERE dateTime < :expiredCutoff 
     OR (fetchedAt < :settledCutoff AND isSettledDraft);
  ```
  This prevents backfilled rows with ancient `dateTime` from persisting indefinitely based on a recent `fetchedAt`.

### 4. Automated Vacuum on Freelist Threshold

* SQLite `DELETE` moves pages to the freelist.
* During the daily background maintenance pass (`HistoryPruneWorker` on Android, daemon idle pass on Desktop):
  * Check `PRAGMA freelist_count * PRAGMA page_size`.
  * If freelist bytes $\ge$ **8 MB** and device is charging + screen off, execute `VACUUM`.
  * Log event: `DB_VACUUM freedBytes=... durationMs=...`.

---

## Dual-Platform Implementation Details

### A. Shared Module (`:shared`)
1. **`RetentionPolicy.kt`**:
   * Add `HOURLY_HISTORY_SETTLED_DAYS = 547L` (18 months, matching `DAILY_HISTORY_DAYS`).
   * Keep `DEFAULT_DAYS = 30L` for active multi-snapshot retention.
2. **`HistorySnapshotRetention.kt`**:
   * Add `settledKeepKeys(rows, activeSiteLat, activeSiteLon, cutoffMs)` to collapse past hours older than 30 days to a single winner and filter out transient drift coordinates.

### B. Android (`:app`)
1. **`HourlyForecastHistoryDao.kt`**:
   * Add query to delete records where `dateTime < :cutoffTime` (18-month ceiling).
   * Add query to delete non-settled duplicate keys for past days.
2. **`HistorySnapshotPruner.kt` / `HistoryPruneWorker.kt`**:
   * Update the daily pruning loop to execute the two-tier pruning.
   * Add `freelistBytes` threshold check and call `db.openHelper.writableDatabase.execSQL("VACUUM")` when `freelistBytes >= 8_000_000L`.
3. **`WeatherRetentionManager.kt`**:
   * Ensure `cleanOldData()` passes `RetentionPolicy.daysAgo(now, RetentionPolicy.HOURLY_HISTORY_SETTLED_DAYS)` for `dateTime`-based cleanup.

### C. Desktop (`:desktop`)
1. **`DesktopWeatherDao.kt`**:
   * Mirror two-tier retention in `applyRetention()` and `pruneHourlyHistorySnapshots()`.
   * Add `VACUUM` execution when freelist threshold is exceeded during housekeeping.

---

## Verification Plan

1. **Unit Tests**:
   * Test `HistorySnapshotRetention` with mock datasets spanning 60 days, verifying that days 0–30 keep the 4 standard snapshot variants, days 31–547 keep strictly 1 row per hour, and days > 547 are purged.
   * Test coordinate clustering so drifted GPS points at the same city don't duplicate.
2. **Integration Tests**:
   * Room migration / DAO tests verifying `deleteOldHistory` and batch deduplication.
   * Desktop SQLite integration tests in `DesktopRetentionPolicyTest.kt`.
3. **Runtime / Device Verification**:
   * Verify on connected emulator and physical phones (Pixel 7 Pro / Samsung) that the first run of the updated pruner reduces `hourly_forecast_history` from ~100k rows to under 15k rows.
   * Verify that running `VACUUM` shrinks the database file below 20 MB.
   * Open the Months View on device, zoom into past days, and verify historical graphs render cleanly.
