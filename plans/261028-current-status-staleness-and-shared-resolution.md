# Current status staleness and shared resolution

## Problem & evidence

At 11:28:39, the desktop user cycled display source from NWS to SILURIAN. The widget header immediately displayed 53.0° (with delta -4.3), and then at 11:30:00 jumped to 63.2° (delta -0.5):

```text
11:28:31.846 I/HEADER_CONTENT: source=NWS ... temp=62.6° delta=-0.4
11:28:39.502 W/Main: CONFIG_SAVE source=popup settings-fields-changed: weatherSource: NWS -> SILURIAN
11:28:39.527 I/HEADER_CONTENT: source=SILURIAN ... temp=53.0° delta=-4.3
11:28:40.031 I/HEADER_CONTENT: source=SILURIAN ... temp=53.0° delta=-4.3
11:30:00.006 I/HEADER_CONTENT: source=SILURIAN ... temp=63.2° delta=-0.5
```

### Database & runtime traces

1. **Stale daemon row left behind:**
   - At 09:18:28 (2+ hours earlier), SILURIAN was active when the daemon published `current_status`:
     `display=53.02173 appliedDelta=-1.7360115 deltaFromYesterday=-4.3 updatedAt=1793204308000`
   - Between 09:18 and 11:28, NWS was the active source. The daemon's 2-minute status tick (`STATUS_TICK_MS = 120_000L`) only re-resolves and updates `current_status` for the *currently active source* (`forecastState.value`). SILURIAN's row remained frozen with its 09:18 data.
2. **Fresh in-memory resolve overridden:**
   - At 11:28:39.502, the user switched to SILURIAN. `DesktopWeatherRepository.loadCached()` ran `:shared` `CurrentTempRun.run` in memory and resolved `forecast.resolved.currentTemp = 63.19°`.
   - But `DesktopUiApplication.kt` queried `weatherDao.getCurrentStatus(cfg.lat, cfg.lon, "SILURIAN")`.
   - `getCurrentStatus` has no staleness check. It returned the 2-hour-old row (53.0°).
   - `DesktopUiApplication` preferred `publishedStatus.displayTempF ?: forecast.resolved.currentTemp`, overriding the fresh 63.19° with the stale 53.0°.
3. **UI missed the daemon's update until the 2-minute tick:**
   - At 11:28:40.020, the daemon processed the config change, computed 63.19°, and wrote it to `current_status`.
   - But `runLaunchRefresh` in `DaemonRuntime.kt` did not notify the UI socket/file watch because its `launchRefreshAction` was `NONE` (cached data was already present).
   - `publishedStatus` in `DesktopUiApplication` is keyed on `remember(forecast, currentConfig, nowMs)`. `nowMs` only updates on 2-minute boundaries (`STATUS_TICK_MS`).
   - Consequently, the UI stayed stuck on 53.0° until exactly 11:30:00.000, when `nowMs` ticked and re-queried `current_status`.

---

## Architecture & maximizing shared code

Per `AGENTS.md`:
> Put shared weather behavior in `:shared`, not in parallel Android and desktop implementations. A shared runner owns the complete rule—windows, source scope, ordering, gates, and logging—while each platform supplies a `Host` for storage, system state, and I/O.

### Shared components (`:shared`)

1. **`CurrentStatusPolicy` (`shared/src/main/kotlin/com/weatherwidget/widget/CurrentStatusPolicy.kt`):**
   A pure JVM component owning the staleness and fallback rules for `CurrentStatus`:
   - `const val MAX_AGE_MS = 300_000L` (5 minutes; 2.5× the 2-minute status tick).
   - `fun isFresh(status: CurrentStatus, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean`:
     Checks `nowMs - status.updatedAt in 0..maxAgeMs`.
   - `data class CurrentDisplayValues(val currentTemp: Float?, val deltaFromYesterday: Float?, val appliedDelta: Float?, val isFromPublishedStatus: Boolean)`
   - `fun resolve(status: CurrentStatus?, snapshot: ForecastSnapshot?, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): CurrentDisplayValues`:
     If `status` is fresh, returns values from `status`.
     If `status` is null or stale, cleanly falls back to `snapshot.resolved` (which `:shared` `CurrentTempRun` computes freshly on cache load).
2. **Move `CurrentStatusResolver` from `:desktop` to `:shared`:**
   - `CurrentStatusResolver` currently lives in `desktop/src/main/kotlin/com/weatherwidget/desktop/CurrentStatusResolver.kt`.
   - It only depends on `:shared` models (`CurrentStatus`, `ForecastSnapshot`, `RawFetch`) and a temperature resolution function `(RawFetch, Long) -> CurrentTempRun.Result`.
   - By moving it to `:shared` (`shared/src/main/kotlin/com/weatherwidget/widget/CurrentStatusResolver.kt`), both packaging and resolution of `CurrentStatus` belong to `:shared`.
   - The desktop wrapper `DesktopWeatherRepository.ResolvedCurrentTemp` can be typealiased or replaced directly by `:shared` `CurrentTempRun.Result`.

---

## Implementation steps

### Step 1: Add `CurrentStatusPolicy` in `:shared`
- Create `shared/src/main/kotlin/com/weatherwidget/widget/CurrentStatusPolicy.kt`.
- Implement `isFresh` and `resolve(status, snapshot, nowMs, maxAgeMs)`.
- Add comprehensive pure JVM unit tests in `shared/src/test/kotlin/com/weatherwidget/widget/CurrentStatusPolicyTest.kt` verifying:
  - Fresh status is preferred over snapshot.
  - Stale status (> 5 min) falls back to snapshot resolved view.
  - Negative age / future clock skew edge cases.
  - Null status falls back to snapshot.
  - Null status and null snapshot return nulls gracefully.

### Step 2: Move `CurrentStatusResolver` to `:shared`
- Move `desktop/src/main/kotlin/com/weatherwidget/desktop/CurrentStatusResolver.kt` to `shared/src/main/kotlin/com/weatherwidget/widget/CurrentStatusResolver.kt`.
- Use `:shared` `CurrentTempRun.Result` instead of desktop-local `ResolvedCurrentTemp`.
- Move / update its unit tests in `shared/src/test/kotlin/com/weatherwidget/widget/CurrentStatusResolverTest.kt`.

### Step 3: Integrate `CurrentStatusPolicy` in `:desktop`
- In `DesktopUiApplication.kt`:
  - Use `CurrentStatusPolicy.resolve(publishedStatus, forecast, nowMs)` to derive `resolvedCurrentTemp` and `resolvedDeltaFromYesterday`.
  - Include `dataUpdateCount` in `publishedStatus`'s `remember` keys:
    `remember(forecast, currentConfig, nowMs, dataUpdateCount)` so that any data notification immediately re-evaluates the published status.
- In `PanelPublisher.kt`:
  - Use `CurrentStatusPolicy.resolve` in the `PanelIpcServer` markup generator so that the XFCE panel generator also rejects stale `current_status` and falls back to in-memory `forecastState.value?.resolved`.
- In `DaemonRuntime.kt`:
  - In `runLaunchRefresh`, when `cached != null` and `panelPublisher.publishForecastState(cached)` runs on source change / startup / wake:
    Trigger `panelPublisher.triggerPanelRefresh()` and `notifyDataUpdated()` so both the panel and UI are notified immediately when a new source status is published.

### Step 4: Integration Tests
- **`DesktopCurrentStatusIntegrationTest`** (in `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopCurrentStatusIntegrationTest.kt`):
  - Uses real SQLite (`DesktopWeatherDatabase`).
  - Sets up an active source (NWS) with a fresh `current_status` row and an inactive source (SILURIAN) with a 2-hour-old stale row (53.0°).
  - Verifies that when switching to SILURIAN:
    - `weatherDao.getCurrentStatus` returns the stale row.
    - `CurrentStatusPolicy.resolve` detects staleness (> 5 min) and falls back to `forecast.resolved.currentTemp` (63.2°), asserting 53.0° is never displayed.
    - When the daemon publishes fresh `current_status` for SILURIAN, `CurrentStatusPolicy.resolve` switches to using the published value.
- **`PanelPublisherIntegrationTest`**:
  - Verifies that when `current_status` in SQLite is stale, `PanelPublisher`'s markup generation falls back to `forecastState.value?.resolved` rather than serving stale temperatures to the XFCE panel.
- **`DesktopSourceCycleTempIntegrationTest`**:
  - Simulates cycling display sources in Compose UI with real SQLite database backing:
  - Asserts the header renders the fresh forecast temperature immediately on cycle and never flashes the old source's stale status.

---

## Verification

1. **JVM Tests:**
   Run `./scripts/unit-tests.sh` to ensure all `:shared`, `:desktop`, and `:app` unit tests pass.
2. **Desktop Integration & UI Tests:**
   Run `./gradlew :desktop:test --tests "com.weatherwidget.desktop.DesktopCurrentStatusIntegrationTest"` and `./gradlew :desktop:test --tests "com.weatherwidget.desktop.*IntegrationTest*"`.
3. **Runtime Verification:**
   - Launch the desktop app or inspect the running process.
   - Cycle sources in the UI (e.g. NWS -> SILURIAN -> OPEN_METEO -> TOMORROW_IO).
   - Verify via `~/.local/state/weather-widget/autostart-*.log` and `app_logs` that `I/HEADER_CONTENT` immediately displays the freshly resolved temperature on source switch, without flashing any hours-old temperature.
