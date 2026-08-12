
> **📖 For detailed architecture documentation, see [ARCHITECTURE.md](ARCHITECTURE.md)**
> - Complete system architecture and data flow
> - Two-tier update system design
> - Battery optimization strategies
> - Performance considerations

## Important Guidelines

- **Never clear app data** (`adb shell pm clear`) without explicit user consent. Cached data is valuable for testing and debugging.
- **Debugging workflow**: When investigating widget bugs, proactively pull device logs (`adb logcat`), grab the database from the device (`adb pull`), query the DB, and capture screenshots — don't just read source code.
- **Screenshots**: `adb` can prepend warning text to PNG output, making the file unreadable. Always convert to JPG before reading:
  ```bash
  adb exec-out screencap -p > /tmp/screenshot.png && convert /tmp/screenshot.png /tmp/screenshot.jpg
  ```
  Then read `/tmp/screenshot.jpg` (not the PNG).

## Project Overview

Android weather widget app with resizable widget support and forecast accuracy tracking.
Also desktop Linux app that is intended to be the same as Android weather widget.

## Weather Data APIs

- **NWS** (National Weather Service) API
- **Open-Meteo** API (free, no API key required)
- Both APIs fetched and stored equally (composite keys allow comparison)
- Widget toggles between sources via tap on API indicator
- Additional key-based sources (Silurian, Tomorrow.io, WeatherAPI, Visual Crossing, OpenWeatherMap);
  users may enter their own keys in Settings. Release builds deliberately ship with keys baked from
  `local.properties` (decision 2026-07-08: out-of-the-box premium sources over quota-theft risk;
  usage is tracked in `api_usage_stats`).
- Settings → "Weather Data Sources" enables/disables and **orders** sources. "Primary" = the
  displayed source (`getActiveDisplaySourceIds()`); non-selected APIs are throttled. The old
  Alternate/NWS-Primary/Open-Meteo-Primary preference no longer exists.

## Widget Sizing Behavior

| Size | Display |
|------|---------|
| 1x1 | Forecast high for today (+ current temp if space allows) |
| 1x3 | Yesterday, today, tomorrow (text only - skip graphs at 1 row height) |
| 2x3 | Same as 1x3 but graphical |
| 4+ cols | Add forecast days (4 cols = 2 forecast days, 5 cols = 3 forecast days, etc.) |

**Graphical display**: Bar showing high/low temperature range for each day. Past days can show forecast overlay (yellow bar) for accuracy comparison.

## Key Requirements

- Display yesterday's actual data alongside predictions
- Graphical display when widget size permits
- Location via the setup screen (`ConfigActivity`, also reachable from Settings → "Set Location…"): precise device location, city/address/ZIP search (Nominatim), or manual coordinates.
- **There is no default location.** `WeatherWidgetWorker.DEFAULT_LAT/LON` (Google HQ) was deleted
  2026-08-12; it used to fetch and label Mountain View's weather for anyone whose GPS never
  resolved. "No location" is now the *absence* of coordinates: `ActiveLocationResolver.resolve()`
  returns null, the widget paints "No location — tap to set" (tapping opens `ConfigActivity`), and
  nothing is fetched. Coordinate **proximity** never means "unset" in steady state — the only
  surviving comparison against the retired coordinates is the one-time
  `LegacyDefaultLocationMigration`, which also purges the `forecasts` rows filed at them (prefs alone
  left `resolve()`'s cached-weather fallback free to resurrect the sentinel).
  See `plans/260812-remove-default-location-and-show-error-when-unavailable.md` and
  `plans/260812-fix-gps-heal-findings-acquisition-vs-following.md`.
- **Never request an active GPS fix from background/automatic paths** (`getCurrentLocation`/`PRIORITY_HIGH_ACCURACY`) — it triggers Samsung's "app got your precise location" warning; background paths use only passive `lastLocation` reads. The ONE exception: the user-initiated "Use precise device location" button in `ConfigActivity` (foreground, explicit tap).
- **Location mode** (`location_mode` in `weather_prefs`, via `LocationMode`): `follow_device` (default; `GpsResampler` keeps widgets tracking the device) or `fixed` (search/coordinate choices pin the location; both sampling paths skip with `GPS_RESAMPLE outcome=skipped_pinned`).
- **"Heal" is not the word.** Two distinct operations share `GpsResampler`: **acquisition** (no
  location → any location; promote as soon as anything is drawable) and **following** (site A → site
  B; be conservative, `MOVING_GRACE_MS`, don't flap between towns). Neither is repair — nothing is
  broken when a phone moves or has never been located — and merging them under a repair metaphor is
  why acquisition once inherited the driving case's 30-minute grace. `evaluateCandidateUsability`
  takes `isAcquisition` to keep them apart. Genuine self-heal (`healCorruptDatabaseVersion`, the
  blank-widget render recovery, `syncCompatibilityCopies`) keeps the name: violated invariant,
  one-shot, defined correct state to return to.
- Visual style: Apple glass aesthetic

## Widget UI Layout

- **Current temperature**: Top-left corner, large font (30sp)
- **API source indicator**: Top-right corner, clickable to toggle between NWS/Meteo
- **Navigation arrows**: Left/right sides for browsing history (30 days back) and forecast
- **Content area**: Maximized with minimal margins; arrows overlap slightly for more space
- Touch priority: API indicator rendered last (on top) with `clipChildren="false"` for reliable touch handling

## Temperature Display

- **Current temp**: Interpolated from hourly forecasts when not available from API
- **Hourly interpolation**: Smooth temperature transitions between hourly data points
- Update frequency scales with temperature change rate (1-4 updates/hour)

## Forecast Accuracy Tracking

The app tracks forecast accuracy by comparing 1-day-ahead predictions against actual weather:

**Data Collection:**
- Fetches 7 days of actual historical observations from NWS observation stations
  - **Multi-station fallback**: Tries up to 5 nearby stations when nearest station has missing data
  - **Station caching**: Station lists cached for 24 hours to reduce API calls
  - **Station tracking**: `stationId` stored in database for transparency and debugging
- Saves 1-day-ahead forecast snapshots daily (before 8pm cutoff)
- Stores forecasts from both NWS and Open-Meteo for comparison

**Important**: Forecast history requires continuous operation:
- Day 1: App saves forecast for Day 2
- Day 2: Can display Day 1's forecast vs actual (yesterday's history)
- Clearing app data destroys historical forecast snapshots

**Accuracy Metrics (30-day lookback):**
- Separate high/low temperature error tracking
- Directional bias (e.g., "forecasts run 2° high on average")
- Maximum error
- Percent of days within ±3°F
- Accuracy score (0-5 scale, 5 = perfect)

**Display:** past days always render the forecast overlay (yellow bar) alongside the actual range
for accuracy comparison. (The old configurable display modes — ACCURACY_DOT, SIDE_BY_SIDE,
DIFFERENCE, NONE — were removed; there is no display-mode setting.)

**Key Files:**
- `AccuracyCalculator.kt` - Calculates accuracy statistics with separate high/low and bias
- `ForecastSnapshotEntity.kt` - Database entity for forecast snapshots
- `HourlyForecastEntity.kt` - Database entity for hourly temperature data
- `TemperatureInterpolator.kt` - Interpolates current temp between hourly data points
- `TemperatureGraphRenderer.kt` - Renders graphical temperature bars with scaling fonts
- `TemperatureGraphRenderer.kt` - Renders hourly temperature curve with min/max/start/end labels
- `GraphRenderUtils.kt` - Shared graph utilities (smoothing, bezier curves, hour labels, now indicator)
- `StatisticsActivity.kt` - Detailed accuracy breakdown UI

## Data Retention

- Retain historical weather data for 1 month (automatic cleanup)
- Forecast snapshots also retained for 1 month
- Widget navigation allows browsing up to 30 days of history

## Database Schema

- **Version**: 55 (see `WeatherDatabase.kt` for the authoritative version and migration list —
  this file goes stale fast; trust the code)
- Main tables: `forecasts`, `hourly_forecasts`, `hourly_forecast_history`, `daily_history`,
  `observations`, `climate_normals`, `app_logs`, `api_usage_stats`
- `forecasts.targetDate` is UTC midnight (query WITHOUT `'localtime'`);
  `app_logs.timestamp` is epoch millis (use `'localtime'`)
- Coordinate-keyed tables quantize lat/lon on write and select via the shared `LocationMatch`
  proximity box to avoid GPS-jitter fragmentation
- `daily_history` carries **two independent** actuals per row — `apiHighTemp`/`apiLowTemp` (the
  provider's own product; for NWS a dedicated `/stations/{id}/observations` pull) and
  `computedHighTemp`/`computedLowTemp` (the IDW blend). They do **not** share a data source. See
  [arch/daily-history-extremes.md](arch/daily-history-extremes.md).

## Update Strategy

**Two-Tier System**: Separates UI updates (current temp) from data fetches (API calls) for optimal battery efficiency.

**Quick Reference:**

| Update Type | Frequency | Wakeup | Purpose |
|-------------|-----------|--------|---------|
| Current Temp UI | 15-60 min (temp-based) | No (opportunistic) | Update interpolated temp from cache |
| Data Fetch | 60-480 min (battery-aware) | Yes (controlled) | Fetch from APIs |
| User Interaction | Immediate | N/A | Instant UI + conditional fetch |
| Screen Unlock | Immediate | N/A | UI update + fetch if charging & stale |

**Data Fetch Intervals** (battery-aware via WorkManager):

| Condition | Interval |
|-----------|----------|
| Plugged in | 60 min |
| Battery > 50% | 120 min |
| Battery 20-50% | 240 min |
| Battery < 20% | 480 min |

**Key Points:**
- Zero independent wakeups for UI updates (opportunistic only)
- User interactions always provide instant feedback from cache
- Background fetches only when data is stale (>30 min old)
- Current temp interpolated from hourly forecasts (no network required)

See [ARCHITECTURE.md](ARCHITECTURE.md) for complete update system design.

## Error Handling

| Scenario | Behavior |
|----------|----------|
| No network | Show cached data with "offline" indicator and last update timestamp |
| GPS unavailable | Fall back to last known location; if nothing resolves, show "No location — tap to set" and fetch nothing (never a stand-in coordinate) |
| API failure | Try other API; if both fail, show cached data with error indicator |
| No data available | Display "Tap to configure" message |

## Build Requirements

- **Java**: Requires Java 21
- **Gradle**: Currently using Gradle 8.13
- Build with: `./gradlew installDebug`
- Available emulators: `Generic_Foldable_API36`, `Medium_Phone_API_36`

## Desktop App (Linux port)

The `:desktop` module is a Compose-for-Desktop tray app sharing `:shared` with Android.

- **For daily use: run the repo-local distributable from autostart** — build with
  `./gradlew :desktop:createDistributable`; login autostart should point at
  `scripts/desktop-app-launcher-and-autostart.sh`. The script launches
  `desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop`, rebuilding
  the distributable once if it is missing. This keeps daily use tied to the repo rather than the `.deb`.
- **To test a new daily build now:** run `scripts/buildStart-desktop.sh`. It builds first,
  then stops any running desktop app, then starts the same repo autostart launcher used at login.
- **For development only: `./gradlew :desktop:run`** (fast iteration, no distributable step). Not for
  daily autostart.
- **Last-launch-wins single instance**: a new launch touches a `.quit` trigger file
  (`~/.local/share/weather-widget/.quit`) that any running instance's `WatchService` is watching, so
  the incumbent exits and the new launch takes over. This mirrors the `.show` trigger and is
  best-effort/fire-and-forget — the new instance does not wait (brief tray overlap is fine; the new
  `PanelIpcServer` rebinds `weather.sock`). The toucher never quits itself because it writes `.quit`
  in `main()` before its own watcher registers. `quit()` ends with `exitProcess(0)` after
  `application {}` returns, since AWT's non-daemon EDT otherwise keeps the JVM alive after the UI is
  disposed. There is also an **"Exit app"** button on the Settings screen (the only quit affordance
  under `WEATHER_DESKTOP_NO_TRAY`). The old `.lock` file is no longer used.
- **Packaging needs a full JDK with `jpackage`** (Android Studio's JBR lacks it). Build config points
  at `/usr/lib/jvm/java-21-openjdk-amd64` if present, overridable via `JPACKAGE_HOME` /
  `-Djpackage.home`. The jlink'd runtime must include `java.sql` (sqlite-jdbc), the crypto modules
  (NWS TLS), and `jdk.unsupported` (JNA) — declared in `nativeDistributions { modules(...) }`.
- **genmon panel temperature**: the panel runs the C client `genmon/genmon-weather-bin` (built with
  `make -C genmon`). The binary is gitignored, so the autostart launcher runs `make -C genmon` on
  every launch — a no-op when current, and it also picks up a stale binary after a pull that touched
  the `.c`. The build is deliberately non-fatal: a machine without gcc still gets the app, and the
  panel falls back to a grey `--`. It connects to the running daemon's
  Unix socket `~/.local/share/weather-widget/weather.sock`, prints the Pango markup that
  `PanelIpcServer` serves, and clicking it opens the popup. The xfconf key
  `/plugins/plugin-<id>/command` must point at that binary.
  The legacy `genmon/genmon-weather.py` (which polled `weather.db` directly) is no longer wired up.
  `PanelIpcServer` serves a **cached** markup string: rendering it runs a full multi-day observation
  blend (~350ms), so it must never happen on the accept path — the client bounds its read and a slow
  serve blanks the panel. Empty client output renders as the literal text `(genmon)`.

## Testing the Widget

To test:

1. Build and install: `./gradlew installDebug`
2. On the emulator/device, long-press the home screen and select "Widgets"
3. Find "Weather Widget" and drag it to the home screen
4. Resize the widget to test different layouts (1x1, 1x3, 2x3, etc.)

Alternatively, use ADB to open the widget picker:
```bash
adb shell am start -a android.appwidget.action.APPWIDGET_PICK
```

## Running Instrumented Tests

The `leaveApksInstalledAfterRun` flag in `gradle.properties` prevents post-test APK uninstall (which would remove all widget instances from the home screen). Do not remove this property.

```bash
# Run on all connected devices (emulator + physical)
./gradlew connectedDebugAndroidTest

# Emulator-only
./scripts/emulator-tests.sh                                        # all tests
./scripts/emulator-tests.sh -c com.weatherwidget.util.RainAnalyzerIntegrationTest  # specific class
```
