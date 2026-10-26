# Shared NWS observation gap-fill

User, 2026-10-25: "move to gap-fill plan" — the next duplicate after the source fetch table
(plans/261025-shared-source-fetch-table.md): Android's `NwsObservationBackfiller` vs desktop's
`DesktopWeatherRepository.refreshObservationWindow`.

Decisions (user, 2026-10-25):
1. **Need-gated, 3 days** on both platforms (Android's rule).
2. **Both passes, phased**: phase 1 the daily-need pass, phase 2 the recent-window (hourly) pass.

## Problem

Both fill the last days of NWS station observations so daily extremes and the graph's actual line
are complete, but by different rules:

| | Android `NwsObservationBackfiller` | Desktop `refreshObservationWindow` |
|---|---|---|
| Trigger | `FullSyncPipeline` (daily need); worker `OBSERVATION_BACKFILL_ONLY` (render gap) | `DaemonRuntime` after every deferred launch/wake refresh |
| Gate | NWS `daily_history` missing for day−2 / yesterday / today (≥ 02:00), or a past day lacking afternoon coverage (`pastDayLacksAfternoonCoverage`) | only `ActualsFeedPolicy.hasDeferredHistoryWindow` |
| Window | `WeatherConfig.NWS_BACKFILL_DAYS` = 3 d; hourly pass `lookbackHours` (72) | `HISTORY_DAYS` = 7 d |
| Stations | ≤ 5, sequential; daily pass stops once the needed dates are covered | all ≤ 5 via `NwsStationBundles` (latest + history; Synoptic web leg) |
| Failure | `RecentBackfillResult.unreachable` vs answered-empty; retry 10 s → 1 m → 4 m | `OBS_WINDOW_REFRESH … failed`, returns −1, no retry |
| Recompute | forced per affected day | `recomputeDailyExtremes(now)` over the whole window |
| Log tags | `NWS_DAILY_BACKFILL_*`, `OBS_HOURLY_BACKFILL_*` | `OBS_WINDOW_REFRESH` |

Desktop has no need check, so every wake pays the ~30 s 7-day pull
(performance/261004-desktop-wake-refresh-stalls-on-7day-obs-window.md) even when every day is already
complete. AGENTS.md requires the runner to own windows, gates, ordering and logging, so the window
cannot be a per-platform `Host` parameter.

## Design

`:shared` `com.weatherwidget.shared.observations.NwsGapFillRun` — the complete rule — over a `Host`:

```kotlin
interface Host {
    suspend fun stations(lat: Double, lon: Double): FetchOutcome<List<NwsApi.StationInfo>>
    /** One station's window through the shared NwsStationHistoryFetcher (with its web leg). */
    suspend fun fetchStation(station: StationInfo, index: Int, lat: Double, lon: Double,
                             startIso: String, endIso: String, webWindowMinutes: Long,
                             fallbackLogTag: String): NwsStationHistoryFetcher.Result
    suspend fun store(rows: List<ObservationReading>, lat: Double, lon: Double)
    suspend fun nwsDailyHistoryDates(startEpoch: Long, endEpoch: Long, lat: Double, lon: Double): Set<Long>
    suspend fun nwsObservationTimestamps(startMs: Long, endMs: Long, lat: Double, lon: Double): List<Long>
    suspend fun recomputeExtremes(lat: Double, lon: Double, days: Set<LocalDate>)  // forced
    suspend fun log(tag: String, message: String, level: String)
}
```

The runner owns:
- **Need** (`dailyNeed`): required = {day−2, yesterday, today if hour ≥ 2}; need = (required − existing)
  ∪ incompletely covered past days. `pastDayLacksAfternoonCoverage` and `DAYTIME_COVERAGE_HOUR` move
  from `DailyActualsStore.kt` to `:shared`; `DailyActualsStore.incompletelyCoveredPastDates` calls it.
- **Window**: `BACKFILL_DAYS = 3` (replaces `WeatherConfig.NWS_BACKFILL_DAYS`), `MAX_STATIONS` reused
  from `NwsStationBundles` (both 5 today).
- **Ordering**: stations in list order; daily pass stops when the need is met; hourly pass tries all.
- **Outcome**: `Result(stationsTried, rows, affectedDates, unreachable)` — today's `RecentBackfillResult`,
  moved; `unreachable` = station lookup `Failed` or every station failed.
- **Logging**: Android's tags unchanged (`NWS_DAILY_BACKFILL_STATION|_FAIL|_INCOMPLETE`,
  `NWS_DAILY_WEB_FALLBACK`, `OBS_HOURLY_BACKFILL_START|STATION|FAIL|DONE`), now also written by desktop.
  `OBS_WINDOW_REFRESH` stays as desktop's outer summary line (it carries `ms=`) with `need=` added.

Hosts:
- **Android** `NwsObservationBackfiller` becomes the Host plus a thin wrapper keeping its two entry
  points (`backfillNwsObservationsIfNeeded`, `backfillRecentNwsObservations`) so `FullSyncPipeline`,
  the worker and `ObservationRepository` do not change. Station list: `NwsObservationSource` (prefs cache).
- **Desktop** `DesktopNwsGapFillHost` in `:desktop` over `DesktopWeatherDao`
  (`getExtremesInRange`, `getObservationsInRange`, `persistObservations`) and `DesktopWeatherService`'s
  station cache (`getCachedOrFetchStations`) and history fetcher. `WeatherApiClient` gains
  `nwsStations()` and `fetchNwsStationWindow(...)`; `fetchObservationHistory` stays for deep zoom.

Not changed: `ActualsFeedPolicy.fullRefreshFetch` (the recovery pull that bridges to the newest stored
row), `fetchObservationHistory` (deep zoom past the window), `HourlyBackfillGate` and the render-side
gap detection (`HourlyObservationBackfill`) — Android-only callers of phase 2.

## Phase 1 — daily-need pass

1. Move `pastDayLacksAfternoonCoverage` + `DAYTIME_COVERAGE_HOUR` to `:shared`; Android imports it.
2. Add `NwsGapFillRun.daily(host, lat, lon, now, zone)` with the need, window, early-stop loop and logs.
3. Android: `backfillNwsObservationsIfNeeded` → `NwsGapFillRun.daily` over the Android Host.
4. Desktop: `refreshObservationWindow` → `NwsGapFillRun.daily` over `DesktopNwsGapFillHost`. Return value
   keeps its contract (rows stored, 0 = not NWS-fed or nothing needed, −1 = failure) so `DaemonRuntime`
   is unchanged. **Behaviour change:** the wake pull becomes 3 days and is skipped when complete.
5. Docs: `arch/hourly-forecast-update-cadence.md` (observations section) and `arch/daily-history-extremes.md`
   — one need-gated 3-day rule on both platforms; drop desktop's "7-day window on wake".

## Phase 2 — recent-window (hourly) pass

1. Add `NwsGapFillRun.recent(host, lat, lon, lookbackHours, now)`: all stations, forced recompute of the
   affected range, `Result.unreachable`.
2. Move `ObservationBackfillRetryPolicy` (10 s → 1 m → 4 m on `unreachable`) to `:shared`.
3. Android: `backfillRecentNwsObservations` → `NwsGapFillRun.recent`; worker retry reads the shared policy.
4. Desktop: when the phase-1 daily pass returns `unreachable`, retry on the shared schedule from the
   daemon's scope (a coroutine `delay`, cancelled with the repository, not a new loop), logging
   `OBS_HOURLY_BACKFILL_RETRY` as Android does.

## Tests

Pure JVM in `:shared` against a fake Host (each must fail on the code it replaces where noted):
- `NwsGapFillRunTest`: need — complete days skip with zero station calls (fails on desktop's old
  always-pull); today joins only at ≥ 02:00; a past day with only morning rows is needed. Early stop
  after the station that covers the need. `unreachable` on lookup failure and on all-stations-failed;
  answered-empty is not unreachable. Window is 3 days on the request (fails on desktop's 7).
- `ObservationBackfillRetryPolicyTest` moves with the policy.

Platform:
- Android `NwsObservationBackfillerTest` keeps passing unchanged (entry points and tags preserved).
- Desktop `DeferredObservationWindowTest`: the 7-day test becomes "needed days → 3-day per-station pull,
  stored, extremes recomputed"; new "complete days → no request, returns 0, `OBS_WINDOW_REFRESH need=none`".
- Desktop integration (real SQLite, fake network): wake with yesterday incomplete → rows land at the
  `LocationMatch` key and `daily_history` gains the NWS row.

## Verification

1. `./scripts/unit-tests.sh`.
2. Desktop: `scripts/buildStart-desktop.sh`; on the launch `OBS_WINDOW_REFRESH` shows `need=` and a
   complete week makes no NWS observation requests. The refill case is proven by the desktop
   integration test, not by editing the live DB; a real gap (next wake after a night offline) is
   checked opportunistically.
3. Android: `./scripts/staggered-tests.sh --install`; next full sync logs `NWS_DAILY_BACKFILL_*` as before
   (from the backed-up DB via `scripts/backup_databases.py`).

## Risks

- Desktop's graph depth: days 4–7 back now come only from earlier pulls and the per-refresh recovery
  pull, not a fresh 7-day pull each wake. Deep zoom still fetches on demand (`fetchObservationHistory`).
- Desktop web leg changes from `NwsStationBundles` (Synoptic + METAR text) to the history fetcher's
  METAR-text leg for this pass — the same leg Android's backfill uses. The current-reading path keeps
  `NwsStationBundles`.

## Progress

- 2026-10-25 — Phase 1 (`c401aa63`): `:shared` `NwsGapFillRun.daily` on both platforms;
  `NwsGapFillHostContract` on Room and desktop SQLite. Desktop, 17:55: `OBS_WINDOW_REFRESH source=NWS
  need=none rows=0 stations=0 … ms=23` (the old 7-day pull took 4–14 s per wake).
- 2026-10-25 — Phase 2 (`d85ba408`): `NwsGapFillRun.recent` + `:shared` `ObservationBackfillRetryPolicy`;
  desktop `ObservationWindowRetry` retries `UNREACHABLE` on the shared schedule.
- 2026-10-25 — Device check of the phase 2 build (installed 19:42 on both phones, desktop relaunched
  19:42):
  - Daily pass, both phones (logcat; the shared `Log.d` need line is not persisted on Android):
    `NwsGapFillRun: History check required=[10-23, 10-24, 10-25] … missing=[] incomplete=[]` — complete
    days, no station calls. No `NWS_DAILY_BACKFILL_*` rows on the Pixel since 10-22, consistent with
    days already complete.
  - Recent pass, Pixel 19:43: `OBS_HOURLY_BACKFILL_START lookbackHours=72` → five `_STATION` lines →
    `_DONE stations=5 failedStations=0 rows=658 affectedDates=[10-22..10-25]`; same tags and shape as
    the old build (Samsung 19:15). Recomputed the same 4 days as before; one outlier
    (`DAILY_RECOMPUTE_PERF date=2026-10-22 total=41360ms`, 13.8 s at 18:27) one minute after install with
    a parallel sync painting widgets — contention, not added work.
  - Desktop: launch `action=NONE`, so no wake pass yet; the `UNREACHABLE` retry is covered by
    `ObservationWindowRetryTest` and has not occurred live.
- Asymmetry left: desktop logs a skipped daily pass at INFO (`need=none`); Android's skip shows only
  in logcat. Not changed.
