# Desktop: extend hourly-graph zoom-out to 30 days back / 7 days forward (on-demand fetch)

## Context

The desktop hourly graphs (Temperature / Cloud Cover / Precipitation) can only zoom out to
**6 days back + 1 day forward**. That ceiling is set by two constants in `DesktopGraphUtils.kt`
(`MAX_BACK_HOURS = 144`, `MAX_FORWARD_HOURS = 24`) and is deliberately coupled to how much data is
fetched/queried — zoom is a continuous `zoomFactor` in `[0,1]`, and the same `MAX_BACK_HOURS` drives
the cache/observation query window in `DesktopWeatherRepository.loadCached()`.

The user wants the zoom-out to reach **30 days back and 7 days forward**. The forward side is
essentially free (Open-Meteo already fetches `forecast_days=7`). The history side is the work: we
should **not** eagerly fetch 30 days on every launch. Instead, fetch deep history **on demand** when
the user actually zooms/pans past what's cached, and show a transient **toast** ("Fetching older
data…") while it loads. For the pink **actual** line past 7 days, the user chose **authoritative NWS
station observations** (widen the obs query window, not just Open-Meteo reanalysis).

### Key architecture facts (verified)
- Desktop runs as **two processes**: a headless daemon (scheduled fetch loops) and an ephemeral
  popup UI. Crucially, the **UI process builds its own full `DesktopWeatherService` +
  `DesktopWeatherRepository`** (`Main.kt:187-196`), so the on-demand fetch can run directly in the UI
  process — no daemon IPC. Fetched rows persist to the shared DB and survive process exit.
- The graph is **passive**: `WidgetPopup` passes `snapshot.hourly` / `snapshot.rawObservations` into
  the graph composables. A fetch reaches the graph by updating `Main.kt`'s `forecast` state via
  `repo.loadCached()`, which triggers recomposition.
- 30 days is inside the DB retention ceiling (cleanup at 30 days, `DesktopWeatherRepository.kt:170`).
- Open-Meteo `past_days` supports ~92 days in one call. NWS `getObservations(station, start, end)`
  accepts an arbitrary window; widening it from 7→30 days widens each station's query (≤5 stations),
  it does **not** multiply the number of calls.

## Changes

### 1. Raise the zoom ceiling — `desktop/.../DesktopGraphUtils.kt`
- `MAX_BACK_HOURS = 144` → `720` (30 days)
- `MAX_FORWARD_HOURS = 24` → `168` (7 days)
- The geometric interpolation (`backHoursFor`/`forwardHoursFor`/`geomInterp`), `navJumpHours`, and
  `totalSpanHoursFor` all scale automatically — no other math changes.
- Note on discrete stages: the click-to-toggle `ZoomStage` cycle (WIDE→NARROW→THREE_DAY) still tops
  out around 3 days; the new deep range is reached via scroll-wheel continuous zoom. Acceptable —
  optionally add a wider stage later, out of scope here.

### 2. Update zoom tests
- `desktop/.../DesktopGraphZoomTest.kt`: the "max zoom-out" test asserts `backHoursFor(1f) == 144`
  and `forwardHoursFor(1f) == 24` — update to `720` / `168`. Scan for any other references to the
  old constants.

### 3. On-demand history fetch — `desktop/.../DesktopWeatherService.kt`
- Open-Meteo curve: `fetchHistory(historyDays)` already exists (`days=1`, `past_days=historyDays`) —
  reuse as-is for deep history.
- NWS authoritative actuals: parameterize the observation window. `fetchObservationBundles()`
  currently hardcodes `start = end.minus(HISTORY_DAYS, DAYS)` (line 283-284). Add an overload/param
  `historyDays: Long = HISTORY_DAYS`, and add a public method, e.g.:
  ```kotlin
  suspend fun fetchObservationHistory(historyDays: Long): List<ObservationReading>
  ```
  that resolves stations (reuse `getCachedOrFetchStations` / the existing station-resolution path)
  and calls `fetchObservationBundles(stations, historyDays)`, returning the flattened
  `rawObservations`. Only meaningful when `weatherSource` is NWS.

### 4. Repository: `ensureHistory()` — `desktop/.../DesktopWeatherRepository.kt`
Add a guarded, idempotent deep-fetch method:
```kotlin
@Volatile private var deepestHistoryDaysFetched = 7   // baseline from backfill/refresh
private val historyFetchMutex = Mutex()               // prevents concurrent deep fetches

suspend fun ensureHistory(neededBackHours: Int): Boolean   // returns true if new data fetched
```
- Compute `neededDays = ceil(neededBackHours / 24) + 1` (margin), clamp to 30.
- If `neededDays <= deepestHistoryDaysFetched` → return false (already covered).
- Under the mutex: fetch Open-Meteo `fetchHistory(neededDays)` → persist via
  `upsertHourlyForecastHistory(lat, lon, GENERIC_GAP.id, 0L, hourly)` (same pattern as the existing
  one-time backfill at lines 122-145 — GENERIC_GAP/bucket 0 keeps it as a non-masking fallback curve).
- If `weatherSource == NWS`: also `fetchObservationHistory(neededDays)` and persist into the
  `observations` table (same upsert path `refresh()` uses for `rawObservations`).
- Set `deepestHistoryDaysFetched = neededDays`. Best-effort: catch/log exceptions, return false on
  failure. Reset is automatic — `repository` is re-`remember`ed on location/source change (`Main.kt:192`).

### 5. Trigger + toast — `desktop/.../Main.kt` and `WidgetPopup`
- In `runApp()` add state: `var historyFetchToast by remember { mutableStateOf<String?>(null) }`.
- Add a fetch callback owned by `Main` (it owns `repository` + `forecast`):
  ```kotlin
  val onNeedHistory: (Int) -> Unit = { neededBackHours ->
      scope.launch {
          historyFetchToast = "Fetching older data…"
          val fetched = repository?.ensureHistory(neededBackHours) == true
          if (fetched) repository?.loadCached()?.let { forecast = it }
          historyFetchToast = if (fetched) null else "Couldn't load older data"
          // brief auto-dismiss of the failure message
      }
  }
  ```
- Detect the need reactively in `WidgetPopup` (it already has `config.zoomFactor` + `config.hourlyOffset`):
  ```kotlin
  LaunchedEffect(config.zoomFactor, config.hourlyOffset) {
      val backHours = DesktopGraphUtils.backHoursFor(config.zoomFactor)
      val earliestVisibleHoursBack = backHours - config.hourlyOffset   // offset<0 when panned to past
      onNeedHistory(earliestVisibleHoursBack)
  }
  ```
  `ensureHistory`'s own `deepestHistoryDaysFetched` guard makes this a no-op when already covered, so
  rapid wheel events don't spam fetches.
- Render the toast as an overlay inside the existing hourly `Box` (`Main.kt:664`), e.g. a small
  rounded `Surface`/`Box` at `Alignment.TopCenter` over the graph, shown when `historyFetchToast != null`.
  This is the first transient-message UI on desktop — keep it a tiny self-contained composable
  (`FetchToast(text)`); no Android `Toast` equivalent exists to reuse.
- Thread `onNeedHistory` + `historyFetchToast` from `runApp()` into `WidgetPopup(...)` as new params.

### 6. (Verify, likely no change) downstream windows already key off `MAX_BACK_HOURS`
- `loadCached()` uses `stitchedStart = now - MAX_BACK_HOURS*3600*1000` for both hourly and
  observation queries, and `obsEnd`/forward already covers 168h — so raising the constant
  automatically widens the load window to 30 days. Confirm `getHourlyWithHistory` /
  `getObservationsInRange` have no separate hardcoded caps.

## Verification

1. Build + restart desktop per the project workflow:
   `scripts/buildStart.sh` (rebuilds distributable + restarts). Do not ask first per repo convention.
2. Unit tests: `./gradlew :desktop:test` — confirm updated `DesktopGraphZoomTest` passes
   (`720`/`168`) and nothing else references the old constants.
3. Manual, Open-Meteo source: open the popup, scroll-wheel to zoom all the way out on the
   Temperature graph. Expect the toast "Fetching older data…", then the forecast curve filling back
   ~30 days. Pan left with ←/arrows past the cached edge and confirm the same on-demand pull.
4. Manual, NWS source: switch source to NWS, deep-zoom, and confirm the **pink actual line** extends
   with real station observations (not just the Open-Meteo fallback curve). Watch logs for
   `historical observations: station=… count=…` over the wider window.
5. Failure path: deep-zoom while offline → expect "Couldn't load older data" toast, existing data
   still rendered (no crash, best-effort).
6. Inspect the DB to confirm deep rows persisted and survive a UI-process restart:
   `python3 scripts/backup_databases.py` then query `hourly_forecast_history` (GENERIC_GAP) and
   `observations` for timestamps older than 7 days.
