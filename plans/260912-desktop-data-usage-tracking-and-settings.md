# Plan: Desktop Settings Data Usage Tracking & Parity

## 1. Context & Problem Statement
- **User Request**: "Can you add the data usage to bottom of desktop settings screen?"
- **Project Rule**: Dual-Platform Parity requires that settings sections added to Android (`activity_settings.xml`) must be mirrored in Desktop (`SettingsWindow.kt`), and vice versa.
- On Android, the Data Usage card displays Cellular and Wi-Fi network traffic (Foreground, Background, Total) for the past 24 hours, 7 days, 30 days, and 90 days.
- On Linux Desktop, the settings window currently ends at the "Support Development" card. `SettingsSection.DATA_USAGE` was temporarily restricted to `Platform.ANDROID`.

---

## 2. Platform Architecture Differences & Challenges
1. **OS Accounting Differences**:
   - Android provides `NetworkStatsManager`, an OS-level service backed by kernel/eBPF socket tracking per UID, retaining 90+ days of historical network usage categorized by interface (`TYPE_MOBILE` vs `TYPE_WIFI`).
   - Linux Desktop has no per-application historical socket accounting in the kernel. Applications running under user accounts (UID 1000) are not partitioned into separate system UIDs.
   - Therefore, desktop network traffic must be tracked and recorded by the application itself into the shared SQLite database (`weather.db`).
2. **Network Interfaces**:
   - Desktop computers typically run on Wi-Fi or wired Ethernet, with mobile data rarely present. To maintain visual parity with Android, the desktop card will display Wi-Fi/Network usage with Foreground and Background breakdowns, and display Cellular as 0 B (or track mobile hotspot if detected).
3. **Two-Process Architecture**:
   - Desktop runs two cooperating processes sharing `weather.db` (in SQLite WAL mode):
     - Background Daemon (`DaemonRuntime`): handles scheduled periodic weather & observation fetches (`isForeground = false`).
     - GUI Application (`DesktopUiApplication` / `SettingsWindow` / `LocationPicker`): handles user-initiated refreshes and location searches (`isForeground = true`).

---

## 3. Proposed Implementation Plan

### Step 1: Model Unification in `:shared`
- Extract pure data models and formatting from `app/src/main/java/com/weatherwidget/util/NetworkUsageTracker.kt` into `shared/src/main/kotlin/com/weatherwidget/shared/util/NetworkUsage.kt`:
  - `data class TrafficBucket(val foregroundBytes: Long, val backgroundBytes: Long)`
  - `data class NetworkUsageWindow(val cellular: TrafficBucket, val wifi: TrafficBucket)`
  - `data class NetworkUsageReport(val past24Hours: ..., val past7Days: ..., val past30Days: ..., val past90Days: ...)`
  - `fun formatNetworkBytes(bytes: Long): String`
- Refactor `app/.../NetworkUsageTracker.kt` to import these shared models.
- In `shared/src/main/kotlin/com/weatherwidget/shared/settings/SettingsSection.kt`:
  - Update `DATA_USAGE("Data Usage", platforms = Platform.entries.toSet())` so it is officially part of both Android and Desktop section catalogues.

### Step 2: Persistence in Desktop SQLite Database (`weather.db`)
- In `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`:
  - Add `network_usage` table:
    ```sql
    CREATE TABLE IF NOT EXISTS network_usage (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp INTEGER NOT NULL,
        bytes INTEGER NOT NULL,
        networkType TEXT NOT NULL DEFAULT 'WIFI',
        isForeground INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS idx_network_usage_timestamp ON network_usage(timestamp);
    ```
- In `DesktopWeatherDao.kt`:
  - `recordNetworkUsage(bytes: Long, isForeground: Boolean, networkType: String = "WIFI", timestamp: Long = System.currentTimeMillis())`
  - `queryNetworkUsageReport(nowMs: Long = System.currentTimeMillis()): NetworkUsageReport`:
    - Queries `network_usage WHERE timestamp >= :w90d` and aggregates byte totals into 24h, 7d, 30d, and 90d windows partitioned by `networkType` and `isForeground`.

### Step 3: Network Traffic Interception
- In `DesktopWeatherService.kt`:
  - Accept `isForeground: Boolean = false` in constructor (passed as `false` from `DaemonRuntime`, `true` from `DesktopUiApplication`).
  - Install Ktor's `ResponseObserver` (or response pipeline interceptor) on `httpClient`:
    - Reads incoming response size (`response.contentLength() ?: body.toByteArray().size`) + estimated request envelope (~500 B).
    - Calls `weatherDao?.recordNetworkUsage(totalBytes, isForeground)`.
- In `DesktopClients` (`DesktopProcess.kt`):
  - Location geocoding calls (Nominatim / IP Geolocation) pass `isForeground = true`.

### Step 4: UI Presentation in `SettingsWindow.kt`
- Add a new `SettingsCard(title = SettingsSection.DATA_USAGE.title)` positioned directly below `SettingsSection.SUPPORT` (at the bottom of the scroll view).
- Component details:
  - Header description: *"Network data used for forecast and observation updates."*
  - 4 time-window sections:
    - **Past 24 Hours**
    - **Past 7 Days (Week)**
    - **Past 30 Days (Month)**
    - **Past 90 Days**
  - Entry rows matching Android's layout:
    - `Wi-Fi: <total> (FG: <fg> • BG: <bg>)`
    - `Cellular: 0 B (FG: 0 B • BG: 0 B)`
  - Subdued horizontal dividers between windows.
  - Loads asynchronously on window composition using `LaunchedEffect`.
  - Wire `dataUsageProvider: suspend () -> NetworkUsageReport?` into `SettingsWindow` from `DesktopWindowHosts.kt` (using `weatherDao.queryNetworkUsageReport()`), defaulting to `null` in tests.

### Step 5: Verification & Parity Tests
1. **Automated Unit Tests**:
   - `SettingsSectionTest`: Update assertion so both Android and Desktop include `SettingsSection.DATA_USAGE`.
   - `SettingsWindowSectionsTest`: Verify `allSectionTitlesArePresentAsCards` and `sectionOrderMatchesAndroid` pass with `DATA_USAGE` at the bottom.
   - `DesktopWeatherDaoTest`: Test `recordNetworkUsage` and `queryNetworkUsageReport` aggregation across time windows.
   - Run `./scripts/unit-tests.sh` to confirm all 4,157+ unit tests pass.
2. **Desktop Visual Verification**:
   - Run the desktop app (`scripts/buildStart.sh` or `./gradlew :desktop:run`).
   - Open Settings window, scroll to bottom, and verify the Data Usage card displays properly formatted stats.

---

## 4. Question for User Alignment
- **Historical Backfill**: Since `network_usage` is a new table, live tracking begins upon installation of this update. We can either:
  1. **Accumulate live data naturally** (starts from 0 B and accumulates going forward).
  2. **Seed historical data** by synthesizing past fetch byte estimates from the existing `forecasts` and `observations` timestamps already in `weather.db` so the 7d/30d/90d windows immediately have estimated numbers.
  *(Recommended: Option 1 natural accumulation, as it records 100% genuine byte measurements).*
