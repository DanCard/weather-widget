# Session summary — Phase 1 of desktop/Android duplication review: shared daily-history + station-actuals maintenance

**Date:** 2026-09-09 · **Plan:**
[plans/260909-desktop-android-duplication-and-complexity-review.md](../plans/260909-desktop-android-duplication-and-complexity-review.md) ·
**Status:** implemented, all tests green, builds verified; committed with this summary

## User prompts

> Can you look through the code? Decide what is complicated, too long, and or duplicate code, and
> decide appropriate next step? desktop and android code should be shared not duplicated. If you
> have findings document them with a file in plans/ dir

> run all tests after each phase. For all tests considering doing something like
> scripts/staggered_tests.sh . proceed Consider updating agents.md with how to run all tests.

> write above phase 1 summary to summaries/ dir. After that commit and proceed to next phase

## Goal

Execute Phase 1 of the review plan: remove the largest remaining cross-platform duplication —
`DesktopWeatherRepository` (1,431 lines) holding desktop twins of five Android repository classes —
and fix the behavioural divergences the fork had already caused. All planning rules move to
`:shared`; each platform keeps its DAO reads/writes, one-time gate and log sink.

## What changed

### Phase 1a — daily-history maintenance

1. **New `shared/.../actuals/DailyHistoryMaintenance.kt`** plans all four passes:
   forecast-only rows, the live rain-chance/overlay/noon-cloud freeze, the one-time chance
   backfill, and the frozen-display backfill. It follows the existing
   `ForecastOnlyHistoryPlanner` "pure planner + platform writer" convention rather than a DAO port
   interface (documented deviation from the plan; avoids a new abstraction).
2. **`app/.../DailyHistorySnapshotter.kt`** is now a thin adapter (405 lines changed, ~230
   remaining): loads Room rows, maps via `toMaintenanceRow()`, calls the planner, writes
   `DailyHistory.toEntity()`, replays the planner's trace/chance-change log lines.
3. **`desktop/.../DesktopWeatherRepository.kt`**'s four methods are thin adapters over the same
   planner, with `DesktopForecastRow`/`DailyForecast` mappers and `runBlocking` for the suspend
   backfill planners (the JDBC DAO is blocking anyway).
4. **New `DailyHistoryMaintenanceTest`** pins the planner rules that had diverged.

### Phase 1b — NWS station actuals

5. **New `shared/.../actuals/NwsStationActualsMaintenance.kt`** owns the policy around the shared
   `NwsDailyExtremesFetch` core: split per-date outcomes into pulled vs. `Insufficient`, fall back
   to stored observations **only** for `Insufficient` (never retryable `Unavailable`), and count
   the rest for the outcome log.
6. **`NwsApiDailyActualsFetcher`** (Android) and
   **`DesktopWeatherRepository.fillNwsStationActualsIfNeeded`** are adapters over it.
7. **New `NwsStationActualsMaintenanceTest`** covers cached/unavailable/unresolved splitting.

### Phase 1c — Weather-API history backfill: deliberately not extracted

8. `ProviderHistoryPolicy` / `retryAtFromMessage` are already the single-sourced core. The
   remaining ~90 lines fetch **different payload shapes** (Android filters `history.hourly`,
   desktop filters `RawFetch.rawObservations`) and persist through different stores, so a shared
   abstraction would add indirection without removing meaningful duplication. Revisit only if the
   payloads converge.

## Divergences fixed (all now match Android's richer behaviour)

1. Frozen-display backfill now rejects degenerate `high == low` overlays on **both** platforms
   (desktop already did; Android was keeping placeholder rows).
2. Both platforms stamp `lastWriter = FORECAST_FREEZE` on the live snapshot (desktop was missing
   it).
3. Desktop chance backfill now stitches hourly history with `HourlyForecastStitcher` (Android
   already did).
4. Desktop frozen-display backfill now reads `getForecastsInRangeBySource`, so climate-normal and
   implausible-temperature rows are filtered like Android's `getAllForecastsInRange`.
5. Desktop now persists `FREEZE_RAIN_CHANCE` to `app_logs` (was an ephemeral `Log.d`).
6. Fixed a pre-existing desktop argument-order bug:
   `weatherDao.log("INFO", "FORECAST_ONLY_HISTORY", …)` wrote tag=`INFO` / level=`created=…`;
   now `log("FORECAST_ONLY_HISTORY", …, "INFO")`.
7. The desktop NWS outcome log now reports `insufficientUnresolved`, matching Android, so the two
   installs can be compared from `app_logs`.

## Documentation

8. **AGENTS.md** gained a "Running All Tests" subsection: `./scripts/staggered-tests.sh` as the
   full-suite gate (unit tests then instrumented, staggered), the individual
   `unit-tests.sh` / `emulator-tests.sh` layers, the `--install` hazard, and the log locations.

## Verification

- `./scripts/staggered-tests.sh`: **4057 unit tests + 95 instrumented (2 skipped) — all passed.**
  (1022 short + 25 localization + 66 medium + 1034 long app; 1534 shared; 376 desktop.)
- Focused: `DailyHistoryMaintenanceTest`, `NwsStationActualsMaintenanceTest`,
  `ForecastOnlyHistoryRowsTest`, `NwsStationActualsStoreTest`,
  `DesktopBackfillChanceSnapshotTest`, `DesktopSnapshotDisplayedRainChanceTest`,
  `DesktopForecastOnlyHistoryRowsTest`, `DesktopApiActualsMergeTest`, `HourlyProximityQueryAllowlistTest`.
- `ktlintCheck`, `assembleDebug`, `:desktop:createDistributable` — all pass.
- Net **−298 lines** of platform code replaced by two shared orchestrators (+tests); emulator
  `Medium_Phone_API_36` left running.

## Next phases (pending)

- Phase 2 — decompose the desktop god functions: `runDaemon` (914 lines, 6 nested functions) and
  `runDesktopUiApplication` (690 lines).
- Phase 3 — unify the desktop NWS fetch path (`fetchNwsForecast` + `fetchObservationBundles`) with
  Android's `NwsForecastMapper` / `NwsObservationSource`.
- Phase 4 — mechanical leftovers (TempUtils conversions, `Dp` object, residual age formatters,
  Tomorrow.io doc/code decision) and the `WeatherDatabase` migration plan.
