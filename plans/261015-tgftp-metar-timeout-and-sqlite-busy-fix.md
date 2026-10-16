# Fast Refresh: Eliminate NOAA METAR Timeouts and Prevent SQLite Write Lock Contention

## Problem Statement

A manual data refresh on Desktop took **44 seconds** (17:01:15 – 17:01:59). In addition, an earlier background/source refresh at 17:00:50 failed with `[SQLITE_BUSY] The database file is locked` after 14.5 seconds, leaving data stale.

Forensic analysis of `autostart-20261015-160833.log` and SQLite `app_logs` revealed two root causes:

1. **NOAA METAR Text Timeout (30s stall):**
   - In `NwsMetarTextWeb.start`, HTTP requests to `https://tgftp.nws.noaa.gov/data/observations/metar/stations/{ID}.TXT` are dispatched for all top-5 observation stations.
   - Two of these stations are non-METAR: `AW020` (CWOP/MADIS personal station) and `LOAC1` (RAWS fire weather station). Neither station has a `.TXT` file on NOAA's METAR FTP server.
   - While single requests sometimes return 301/404, concurrent connections to `tgftp.nws.noaa.gov` frequently hang on non-existent files.
   - Because `NwsMetarTextApi` used the default 30-second Ktor request timeout, both requests stalled and threw `HttpRequestTimeoutException` at 30,000 ms.
   - `DesktopWeatherService` awaited all 5 station web results before completing the observation merge, blocking the entire refresh for over 33 seconds.

2. **SQLite Write Contention (`SQLITE_BUSY`):**
   - In `DesktopWeatherDao.kt`, write transactions used `conn.autoCommit = false`, which issues `BEGIN DEFERRED` in SQLite JDBC.
   - A deferred transaction begins with a shared read lock (during preliminary `SELECT` queries such as `latestForecast`).
   - When `stmt.executeBatch()` executes, SQLite attempts to escalate the read lock to an exclusive write lock. If another connection (e.g. the UI process or another coroutine) holds a read lock, SQLite immediately returns `SQLITE_BUSY` to prevent deadlocks, bypassing the 5-second `busy_timeout`.

---

## Proposed Changes

### 1. Gate NOAA METAR Web Leg to Official Airport Stations Only (`:shared`)
* File: `shared/src/main/kotlin/com/weatherwidget/shared/observations/NwsMetarTextWeb.kt`
* Filter the stations in `NwsMetarTextWeb.start`:
  - Only initiate network requests for stations where `station.type == StationInfo.Type.OFFICIAL` (or 4-letter ICAO identifiers).
  - Stations with type `PERSONAL` (e.g. `AW020`) or `RAWS` (e.g. `LOAC1`) are mapped immediately to `FetchOutcome.NoData` without launching network coroutines or opening sockets.
* File: `shared/src/main/kotlin/com/weatherwidget/data/remote/NwsMetarTextApi.kt`
  - Configure a dedicated per-request timeout on `httpClient.get(...)` (e.g., `requestTimeoutMillis = 6_000`, `connectTimeoutMillis = 4_000`) using Ktor's `timeout {}` builder block.
  - If NOAA's server lags or drops a connection, it aborts in 6 seconds instead of stalling the app for 30 seconds.

### 2. Prevent SQLite Write Lock Escalation (`:shared` / `:desktop`)
* File: `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`
  - In write methods (`upsertForecasts`, `upsertHourlyForecasts`, `saveObservations`, `deleteForecasts`, etc.):
    - Replace `conn.autoCommit = false` (`BEGIN DEFERRED`) with `conn.createStatement().execute("BEGIN IMMEDIATE")`.
    - `BEGIN IMMEDIATE` acquires a reserved write lock at the start of the transaction. If another writer is active, SQLite's configured `busy_timeout` (5000 ms) will cleanly wait for the active writer to finish rather than throwing an immediate lock-upgrade error during `executeBatch()`.
  - Add an in-process coroutine `Mutex` (`private val writeMutex = Mutex()`) around desktop write transactions so concurrent coroutines within the daemon queue their brief (~5–15 ms) disk writes cleanly in memory.

### 3. Verification & Dual-Platform Parity
* **Parity:**
  - `NwsMetarTextWeb` and `NwsMetarTextApi` live in `:shared` and are used by both Android (`NwsCurrentObservationUpdater`) and Desktop (`DesktopWeatherService`).
  - Android Room already uses `BEGIN IMMEDIATE` for transactions by default. The desktop DAO changes bring Desktop JDBC into full alignment with Room's transactional model.
* **Testing:**
  - Unit tests in `:shared` for `NwsMetarTextWebTest`: verify that `PERSONAL` and `RAWS` stations return `FetchOutcome.NoData` immediately without invoking `NwsMetarTextApi.fetchLatest`.
  - Unit tests for `NwsMetarTextApiTest`: verify short timeout handling.
  - Integration tests in `:desktop`: verify concurrent writes in `DesktopWeatherDaoTest` do not throw `SQLITE_BUSY`.
  - Manual live verification on Desktop: trigger a manual refresh in the History view and verify the refresh completes in < 8 seconds with zero timeouts in the log.
