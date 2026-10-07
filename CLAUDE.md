
> **📖 For detailed architecture documentation, see [ARCHITECTURE.md](ARCHITECTURE.md)**
> - Complete system architecture and data flow
> - Two-tier update system design
> - Battery optimization strategies
> - Performance considerations

## Important Guidelines

- **Never clear app data** (`adb shell pm clear`) without explicit user consent. Cached data is valuable for testing and debugging.
- **Plan files**: one new file per task, named `YYMMDD-kebab-title.md`, never overwriting an existing
  one. Two directories, chosen by subject:
  - **`performance/`** — latency, battery, CPU, memory, DB/query cost, render time.
  - **`plans/`** — everything else.

  `performance/` is a sibling of `plans/`, not a subdirectory. It exists because `plans/` has grown
  past 650 files and a performance investigation is the kind that accumulates related-but-separate
  entries over months; interleaved with everything else, that thread is unfollowable.
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
- Additional key-based sources (Silurian, Tomorrow.io, WeatherAPI, Visual Crossing, OpenWeatherMap,
  Google Weather); users may enter their own keys in Settings. Release builds deliberately ship with
  keys baked from `local.properties` (decision 2026-07-08: out-of-the-box premium sources over
  quota-theft risk; usage is tracked in `api_usage_stats`) — **except Google Weather**, billed per
  request, whose key is baked into debug builds only (release `BuildConfig` field is `""`).
  Google is forecast-only (borrows actuals); one full fetch = 6 requests, current temp = 1.
  See `plans/261006-add-google-weather-source.md`.
- **Borrowed actuals default by location:** a forecast-only source (Google, Silurian) with no
  explicit provider uses **NWS inside `NwsCoverage`, METAR elsewhere**. The default is derived on
  every read and never stored (`ActualsProviderResolver.borrowerDefault`, location via
  `installLocationSource`); an explicit picker choice always wins. Which feed to *fetch* is
  `:shared` `ActualsFeedPolicy` on both platforms. See
  `plans/261007-borrowers-default-to-nws-actuals-inside-coverage.md`.
- **Enabling a source places it:** Google Weather goes first (becomes primary, and every widget /
  the desktop popup switches to it); any other source is appended. A starting position, not a pin —
  OWM is no longer forced last. Rule: `WeatherSourceOrdering.withEnabled` / `selectionAfterChange`.
- Settings → "Weather Data Sources" enables/disables and **orders** sources. "Primary" = the
  displayed source (`getActiveDisplaySourceIds()`); non-selected APIs are throttled. The old
  Alternate/NWS-Primary/Open-Meteo-Primary preference no longer exists.
- **Enabled vs usable sources.** The stored list is the user's choices only; a location never edits
  it. `SourceCoverage.effectiveSources` (`:shared`) derives what can serve the current location
  (today: NWS only inside `NwsCoverage.covers`) every time it is read —
  `WidgetStateManager.getVisibleSourcesOrder()` / desktop `DesktopConfig.effectiveSources` and
  `displaySource`. Settings reads the raw list (`getEnabledSourcesOrder()`) and marks a source
  "Not available at this location". A widget's stored source is never rewritten for coverage.
  The old model removed NWS abroad and needed an "app did it" flag to restore it; a lost flag left
  NWS off for good (removed 2026-09-29, see
  `plans/260929-nws-unavailable-outside-coverage-derived-not-removed.md`).

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
- **Location mode** (`location_mode` in `weather_prefs`, via `LocationMode`): `follow_device` (default; `GpsResampler` keeps widgets tracking the device) or `fixed` (search/coordinate choices pin the location; both sampling paths skip with `GPS_RESAMPLE outcome=skipped_pinned`). `fixed` is the one thing that stops a move being applied, because it is the user's own choice — not a policy overriding them.
- **A detected move is applied immediately. There is no candidate and no readiness gate.**
  `GpsResampler` compares the passive fix against the active location and, when it is a different
  site, writes it through `LocationUpdater.applyFollowDeviceLocation` there and then
  (`GPS_RESAMPLE outcome=location_moved`). "Different site" means more than
  `LocationMatch.WEATHER_SITE_RADIUS_KM` (1 km) from the active site — measured from the site, not the
  last fix; closer fixes log `outcome=same_weather_site distKm=…` and do not move (200 m moves had
  fragmented a week in Kyiv into 13 sites). "Use precise device location" within 1 km keeps the
  active site. 1 km, not 2: the observation blend is centred on the site, and 2 km shifted NWS
  actuals by up to ~6 °F (`plans/260929-follow-device-weather-site-radius.md`). If the user is
  looking at the phone they should see where they are; a briefly sparse graph for the right place beats a complete graph for a city they left.
  The handoff policy that used to hold a move pending (`LocationHandoffPolicy`,
  `LocationHandoffStore`, `MOVING_GRACE_MS`, `evaluateCandidateUsability`, `isAcquisition`) was
  **deleted 2026-08-28** — it was wrong in both directions: it held a fully-drawable San Francisco
  for 30 minutes while displaying Mountain View, and separately promoted zero-observation stubs half
  a mile away instantly, because its readiness test read forecast rows only and returned before the
  grace check. Acquisition and following are now one operation.
  See `plans/260828-remove-the-location-handoff-policy.md`.
- **A setup-screen location change shows "Getting weather for {place}…"; a GPS move does not.**
  Since 2026-09-28 (user's call) it is a **banner over whatever is on screen** — the previous
  site's graph included — until that change's forced sync ends (`LocationChangeBanner` on Android,
  `LocationBanner` in the desktop popup); the full-screen interstitial is only for a first-ever
  location with nothing to keep (`LocationChangePaintPolicy.feedback`). A toast was tried and is
  too short (~4 s) for a 10–50 s fetch. "Cached" means a forecast row for **today**
  (`hasTodayRow`), not any row — a two-week-old cache drew an empty graph.
  When the new site has a **drawable, fresh** cache (`LocationChangePaintPolicy.hasDrawableCache`:
  today's daily row AND hourly rows for today through the render's own loader, fetched within
  `MAX_ADOPTABLE_CACHE_AGE_MS` = 24 h), the forced sync repaints from it and *then* drops the banner
  (`action=banner_cleared reason=cache_adopted`) and fetches unforced; desktop shows no banner at all
  (`action=cache_adopted`). A daily row alone is not enough: 16-day forecasts leave rows dated today
  in caches weeks old, and the daily proximity box is wider than the hourly site match. The
  location-change sync is expedited on API 31+ (it waited 22 s in JobScheduler), and the cache
  decision also runs inside the startup-cooldown deferral
  (`plans/260929-location-change-adopts-cached-new-site-under-banner.md`).
  The axis is user-initiated vs background, not setup vs GPS: the user who just tapped Save is
  looking at the widget and the fetch bypasses the battery gate, so feedback is worth the flash;
  nobody is watching a follow-device move and its fetch may be hours away, so a placeholder there
  would be worse than the sparse-but-correct graph. `LocationChangePaintPolicy` (`:shared`) holds
  the three gates (user-initiated, site actually changed, no cached row for today at the new
  site); `WidgetStateManager.getPendingLocationFetch` is the Android mark, the forced sync
  (`KEY_LOCATION_CHANGE_PLACE`) owns the probe and the paint — never a thread launched from the
  activity, which outlived Robolectric tests — and a sync that fails or returns 0 rows swaps the
  interstitial for "Tap to refresh" — it is never a dead end.
  `WidgetPaintCoordinator.updateAllWidgets` never pushes with zero daily *and* zero hourly rows
  (`WIDGET_PAINT_SKIP reason=empty_data`): an empty day list renders as a blank bitmap, and that is
  never an improvement on what is already on screen.
  See `plans/260913-setup-location-change-interstitial.md`.
- **Fetch cost is bounded by the battery cadence, not by withholding the location.** A location
  change no longer forces a fetch past the budget: `ForecastFetchCoordinator.isStale` takes its
  last-fetch time from the *location-scoped* forecast rows, so a new site is due at once while a
  jittering fix inside one site coalesces. One budget bounds cost; nothing bounds what is displayed.
- **"Heal" is not the word.** Nothing is broken when a phone moves or has never been located, so
  neither acquisition (no location → any location) nor following (site A → site B) is repair. The
  repair metaphor is what once let acquisition inherit the driving case's 30-minute grace; both are
  now the same immediate operation and the distinction has no code left to live in. Genuine
  self-heal (`healCorruptDatabaseVersion`, the blank-widget render recovery,
  `syncCompatibilityCopies`) keeps the name: violated invariant, one-shot, defined correct state to
  return to.
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

**Display:** past days render today's **triple bar**, thinner (`TodayColumnHighlight.PAST_TRIPLE_WIDTH_SCALE`
= 0.8, no bulb, no panel): left = "yesterday's forecast", centre = actual, right = the settled
forecast (`ForecastOverlaySettle`). The left bar on every column (today included) is
`PriorDayForecast`: low from the newest fetch before 06:00 the previous day, high from the newest
before 16:00 the previous day; frozen for history into `daily_history.priorForecastHigh/LowTemp`
(Room v73 / desktop v26). See `plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md`. When a past day
has no real value for a side, the bar draws **dashed** from a fallback: left = the earliest row fetched
after the anchor; right = the source's post-cutoff value (`forecasts.hindcastHigh/LowTemp`, Room v74 /
desktop v27, never read as a forecast), then the day's hourly-forecast range. Display-only; not frozen
into `daily_history` (`plans/261007-keep-hindcast-extremes-and-dashed-past-forecast-fallback.md`). (The old configurable display modes — ACCURACY_DOT, SIDE_BY_SIDE,
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

One policy for Android and desktop, in `:shared` `RetentionPolicy` (user's decision 2026-09-30):

| Table | Kept |
|---|---|
| `daily_history` | 18 months (the long record: accuracy stats, history) |
| `network_usage` (desktop) | 90 days (the data-usage report's 90-day column; ~0.7 MB) |
| `forecasts`, `hourly_forecasts`, `hourly_forecast_history`, `api_usage_stats`, `current_status`, `station_cache` | 30 days |
| `observations` | 10 days |
| `app_logs` | 72 h (desktop keeps its permanent `*_BACKFILL_DONE` markers) |
| `climate_normals` | current location only |

- `hourly_forecast_history` is also pruned daily to the snapshots something reads (below).
- Widget navigation allows browsing up to 30 days of history.

## Database Schema

- **Version**: 55 (see `WeatherDatabase.kt` for the authoritative version and migration list —
  this file goes stale fast; trust the code)
- Main tables: `forecasts`, `hourly_forecasts`, `hourly_forecast_history`, `daily_history`,
  `observations`, `climate_normals`, `app_logs`, `api_usage_stats`
- `hourly_forecast_history` is pruned daily to the snapshots something reads
  (`HistorySnapshotRetention` in `:shared` is the rule; Android `HistoryPruneWorker`, never inside a
  fetch; desktop from the refresh, plus VACUUM). Any new reader of older snapshots must be added to
  that rule and its equivalence tests (`performance/260929-hourly-history-snapshot-retention.md`).
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
| Data Fetch | 60-1440 min (battery-aware) | Yes (controlled) | Fetch from APIs |
| Recent observations | Charging 10 min (screen off 16); **battery ≥70% + screen on ~20 min** (non-wakeup `setAndAllowWhileIdle` alarm ⇒ ~14–25 min + expedited fetch, so it runs under Pixel Adaptive Battery Saver, which is on whenever unplugged; screen-on fetches now if last ≥20 min; primary source only); else 45-min opportunistic job (>65%) | No (charging: WorkManager loop; battery: RTC alarm; 45-min: JobScheduler) | `CurrentTempFetchPolicy.loopIntervalMinutes` is the one rule; desktop mirrors it (`performance/261003-observations-every-20-min-on-battery-screen-on.md`) |
| User Interaction | Immediate | N/A | Instant UI + conditional fetch |
| Charger plug-in | Immediate | JobScheduler charging constraint | `PowerConnectedJobService`: refresh + location resample |
| Screen Unlock | **Never fires** | N/A | `ScreenOnReceiver` declares `USER_PRESENT` in the manifest and measured zero deliveries over three days on two devices. `ACTION_SCREEN_ON` is not registered at all, and cannot be from a manifest (`FLAG_RECEIVER_REGISTERED_ONLY`; see `android/content/Intent.java`). Do not "fix" this by adding `SCREEN_ON` to the manifest — it will silently do nothing. |

**Data Fetch Intervals** (battery-aware via WorkManager). Authoritative values are
`BatteryTier`/`ForecastFetchPolicy`; the thresholds are on **battery level**, not a 20/50 split:

| Condition | Interval | Constant |
|-----------|----------|----------|
| Charging | 60 min | `ForecastFetchPolicy.CHARGING_SCREEN_ON_ACTIVE_MINUTES` |
| Battery > 70% | 240 min | `BatteryTier.INTERVAL_HIGH_MINUTES` |
| Battery 50-70% | 480 min | `BatteryTier.INTERVAL_MEDIUM_MINUTES` |
| Battery ≤ 50% | 1440 min | `computeFetchInterval` returns null → `OFF_CHARGER_LOW_BATTERY_TICK_MINUTES` |

This table read 60/120/240/480 until 2026-08-28 — wrong in the flattering direction, and off by up
to 3× at the bottom. That mattered: it hid how long the app can go without noticing a location
change off-charger, which is the gap
`plans/260828-detect-the-move-when-the-user-is-looking.md` exists to close. **The periodic tick is
also the only thing that resamples location** on a device that is never plugged in and never has the
app opened, so these numbers are detection latency as well as data latency.

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
