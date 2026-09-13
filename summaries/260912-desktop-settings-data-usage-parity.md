# Desktop Settings Data Usage Tracking & Parity

*2026-09-12* — plan: `plans/260912-desktop-data-usage-tracking-and-settings.md`

## Outcome

Achieved complete cross-platform parity between Android and Linux Desktop for network data usage tracking and display in settings. Extracted unified data usage models to `:shared`, implemented persistent network traffic accounting in the desktop SQLite database (`weather.db`), instrumented the Ktor HTTP client to log request/response bytes across background and foreground operations, and added the Data Usage card to the bottom of the Desktop Settings window.

1. **Shared Network Usage Domain Models**:
   - Extracted `TrafficBucket`, `NetworkUsageWindow`, `NetworkUsageReport`, and `formatNetworkBytes` from `:app` into `shared/src/main/kotlin/com/weatherwidget/shared/util/NetworkUsage.kt`.
   - Refactored Android's `NetworkUsageTracker` and `SettingsActivity` to share these unified types.
   - Updated `SettingsSection.DATA_USAGE` in `:shared` to support both `Platform.ANDROID` and `Platform.DESKTOP`.

2. **Desktop SQLite Persistence (`weather.db`)**:
   - Added `network_usage (id, timestamp, bytes, networkType, isForeground)` table with index on `timestamp` in `DesktopWeatherDatabase`.
   - Implemented `recordNetworkUsage` and `queryNetworkUsageReport` in `DesktopWeatherDao`, aggregating data into 24-hour, 7-day, 30-day, and 90-day windows partitioned by `isForeground` and `networkType`.

3. **Ktor Client Network Interception**:
   - Added `isForeground: Boolean = false` parameter to `DesktopWeatherService` (set to `false` in `DaemonRuntime` and `true` in `DesktopUiApplication`).
   - Configured Ktor `ResponseObserver` on HTTP clients in `DesktopWeatherService` and `DesktopClients` to record response body byte counts + estimated request headers (~500 B) into `weather.db`.

4. **Desktop Settings UI (`SettingsWindow.kt`)**:
   - Added `SettingsCard(title = SettingsSection.DATA_USAGE.title)` positioned below `SettingsSection.SUPPORT` at the bottom of the scroll view.
   - Displayed 4 time windows ("Past 24 Hours", "Past 7 Days (Week)", "Past 30 Days (Month)", "Past 90 Days") with Cellular and Wi-Fi breakdowns (Total, FG, BG).
   - Wired asynchronous provider `dataUsageProvider = { weatherDao.queryNetworkUsageReport() }` in `DesktopWindowHosts.kt`.

---

## What Changed

### Core Implementation
- **`shared/src/main/kotlin/com/weatherwidget/shared/util/NetworkUsage.kt`** *(new)*: Pure JVM data models (`TrafficBucket`, `NetworkUsageWindow`, `NetworkUsageReport`) and `formatNetworkBytes(bytes)` utility.
- **`shared/src/main/kotlin/com/weatherwidget/shared/settings/SettingsSection.kt`**: Updated `DATA_USAGE` to support both Android and Desktop platforms.
- **`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`**: Added `network_usage` table schema and timestamp index.
- **`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`**: Implemented `recordNetworkUsage` and windowed `queryNetworkUsageReport`.
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`**: Added `isForeground` flag and `ResponseObserver` to record Ktor weather/observation network bytes.
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt`**: Added `weatherDao` to `DesktopClients` and instrumented foreground geocoding client.
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt`**: Passed `weatherDao` and `isForeground = true` for interactive UI instances.
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWindowHosts.kt`**: Wired `weatherDao.queryNetworkUsageReport()` to `SettingsWindowHost`.
- **`desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt`**: Added `dataUsageProvider` parameter, `DataUsageSectionContent`, and `DataUsageWindowBlock` components.
- **`app/src/main/java/com/weatherwidget/util/NetworkUsageTracker.kt`**: Updated to import and return shared `NetworkUsageReport` models.
- **`app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`**: Updated to import shared `NetworkUsageReport` and `formatNetworkBytes`.

### Tests
- **`shared/src/test/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDaoTest.kt`**: Added `test network usage recording and window reporting` covering 1h, 3d, 15d, 45d, and 100d intervals with foreground/background and Wi-Fi/Cellular breakdowns.
- **`shared/src/test/kotlin/com/weatherwidget/shared/settings/SettingsSectionTest.kt`**: Updated platform assertions (`LANGUAGE` is the only Android-only section).
- **`desktop/src/test/kotlin/com/weatherwidget/desktop/SettingsWindowSectionsTest.kt`**: Verified `"Past 24 Hours"` text node exists in `SettingsWindow`.

---

## Verification

- **Automated Unit Tests**:
  - Ran `./scripts/unit-tests.sh`: all **4,158 / 4,158 unit tests passed** in 55s across `:app` (Short, Medium, Long, Localization), `:desktop`, and `:shared` test suites.
- **Database Logic**:
  - Verified SQLite aggregation across 24h, 7d, 30d, and 90d intervals, foreground/background flags, and boundary filtering in `DesktopWeatherDaoTest`.
