# Android ↔ desktop: what could still move to `:shared` — audit 2026-10-21

User, 2026-10-21: "I get a lot of bugs because desktop and android code are different. I've asked
many times to use shared code. Can you conduct an audit of what could move to shared code?"

Audit only — no code changed. Earlier rounds: `plans/260608-shared-code-deduplication.md`,
`260827-dedupe-android-desktop-to-shared.md`, `260903-shared-code-consolidation-review.md`,
`260909-desktop-android-duplication-and-complexity-review.md`. Since 2026-08-01, 49 of 597 commits
are parity fixes.

## 0. Execution status (2026-10-21, applied to `main`, uncommitted)

Each step was followed by `scripts/staggered-tests.sh`. Baseline: 5,719 unit + 105 emulator (2 skipped).

| Step | What moved to `:shared` | Divergences fixed | Tests after |
|---|---|---|---|
| 1. A | `DailyHistoryRun` + Host (Room / JDBC); desktop DAO gained all-source fetch & hourly reads and field-limited `daily_history` updates | #1 source scope, #2 UTC vs local bounds (mutation-checked), #3 full-row settle; desktop one-time backfill lookback 547 → 30 days like Android | 5,724 + 105 |
| 2. C+D | `QuotaState` + `QuotaState.record` (Android prefs adapter, desktop `DesktopQuotaState` over the contract rows; the popup, `HistoryRefreshOutcome` read through it); `HistoryPruneSchedule` | #4 re-record every fetch vs on change, log formats now identical (`PRODUCT_QUOTA`, `FETCH_RATE_LIMITED`); desktop history-outcome rate limit now shows only while bannered, like Android; one prune skip message | 5,727 + 105 |
| 3. B (part) | `GoogleHistoryRun`, `ForecastHorizonMarkers.mark`, `ElapsedForecastBackfill.backfill/Store` + shared log line | desktop never logged `FORECAST_HORIZON`; `HOURLY_HISTORY_BACKFILL` line now carries `site=` on desktop | 5,730 + 105 |
| Upkeep | `DailyHistoryRun.afterFetch` (freeze + yesterday's forecast-only rows, every fetch) / `upkeepIfDue` (month-wide passes, once per 24 h **on a charger**, user 2026-10-21) | desktop no longer repeats the month-wide passes once per source per round | 5,732 + 105 |
| 4. G (part) | `HourlyZoomRules.footerLabelInterval` replaces Android's three copies | — | 5,733 + 105 |
| Guardrails | CLAUDE.md rule; `:desktop` `CrossPlatformDuplicateNamesTest` (34 allowlisted names, each with a reason; mutation-checked) | — | 5,733 + 105 |

Not done, needs a decision or a closer read:
- **G, the zoom model.** Android draws discrete stages (smoothing 1 or 3, its label table); desktop a
  continuous factor (`DesktopGraphUtils.labelIntervalFor*`, `smoothIterationsFor` up to 4). One table
  means picking one model.
- **B, the rest of the post-fetch path** (per-source fetch/persist): payload shapes and stores differ
  legitimately; revisit after the zoom work.
- **E** current-temp resolution, **F** daily column builder, **H–K**: next round. The five `PENDING`
  entries in `CrossPlatformDuplicateNamesTest` are the small K items.

## 1. Why the drift keeps coming back

The algorithms are mostly shared already: API clients, cadences, label engines, colour models,
`DailyHistoryMaintenance.plan…`, `DailyRainLabels`, `PriorDayForecast`. What each platform still
writes for itself is the **glue around them**:

- the date window (UTC-midnight keys vs local midnight)
- which query is called, and which sources it covers
- where state is kept (Android prefs vs desktop `app_logs` markers)
- when to log
- whether a write replaces the whole row or only some fields

The 260909 round did this on purpose. Its plan called for a DAO port, but it shipped
"pure planner + platform DAO lambda", and the reads, gates and log sinks stayed on each platform.
Every divergence below lives in that leftover glue.

The pattern that has **not** drifted is `CurrentConditionsRun` (2026-10-13). `:shared` owns the
whole run (what to fetch, throttles, freshness, logging), and each platform implements only a small
`Host` interface (fetch, store, read state). **Recommendation: make that the rule. New
cross-platform behaviour, and each item in §3, lands as a shared Runner + `Host` port, not as a
shared planner fed by platform glue.**

## 2. Divergences found in today's glue

| # | Where | Android | Desktop | Effect |
|---|---|---|---|---|
| 1 | `ensureForecastOnlyHistoryRows` | all sources (`getForecastsInRange`) | only the repository's `weatherSource` (`getForecastsInRangeBySource`) | forecast-only history rows for other sources: Android creates them, desktop doesn't |
| 2 | `snapshotDisplayedRainChance`, `backfill*IfNeeded` | `daily_history` range in UTC-midnight ms (matches the key) | range in **local** `atStartOfDay` | PDT: the window starts 07:00Z, missing yesterday's row; Kyiv (+3): it ends 21:00Z the day before, missing today's row. Latent: the 2026-10-21 desktop DB still has both days frozen because the planner falls back to creating rows. Wrong window all the same |
| 3 | `settlePastForecastOverlays` | field-limited `UPDATE` ("so a concurrent blend write is never clobbered") | full-row `upsertDailyHistory` of settled + prior rows | desktop can overwrite blend columns written by a concurrent loop |
| 4 | `recordProductQuotas` | prefs via `WidgetStateManager.recordProductQuota/clear…`, logs every time | `ProductQuotaLog` rows in `app_logs`, logs only on change | "is Google blocked?" is answered by two different stores and readers |
| 5 | one-time gates (chance / frozen-display backfill, prune prerequisites) | prefs booleans | `*_DONE` rows in `app_logs` | Android's frozen backfill sets done when nothing needs it; desktop logs a marker. Same intent, different code |
| 6 | Hourly zoom → label interval / smoothing | shared discrete `ZoomStage` (smoothing 1 or 3) + narrow-widget `when` copy-pasted in 3 handlers | own continuous `zoomFactor`; `labelIntervalFor`, `labelIntervalForWidth`, `smoothIterationsFor` (up to 4) | the same span draws with different label cadence and smoothing |
| 7 | Daily column assembly | app-only `DailyTodayResolver` / `DailyPastDayResolver` / `DailyFutureDayResolver` / `DailyForecastIconResolver` (~510 lines) + `DailyViewLogic.prepareGraphDayInputs` | `DesktopDailyForecastModel.buildDay` (~330 lines) calling `DailyDayValueResolver`, `TodayLowSettle`, `PartialForecastDays` directly | both call shared pieces but **combine** them differently (today/past/future branching, low choice, icon). This is where daily-view parity bugs come from |

## 3. Candidates, ranked by bug risk

### Tier 1: data pipeline glue (correctness, do first)

**A. `DailyHistoryRun` + `Host`** (replaces Android `DailyHistorySnapshotter` 430 lines and
`DesktopWeatherRepository.kt:1482–1757` ≈ 275 lines).
- Owns: the windows (UTC keys, one helper), source scope, run order (forecast-only → snapshot → settle
  → prior), the one-time gates and every log tag.
- Host: `extremesInRange`, `forecastFetchesInRange(sources)`, `newestForecastsInRange`, `hourly`,
  `hourlyHistory`, `insert`, `updateOverlay`, `updatePriorForecast`, `marker(get/set)`, `log`.
- Fixes divergences #1, #2, #3 and #5. Decide #3 as field-limited updates on both platforms.

**B. `ForecastFetchRun` + `Host`**: what happens after a source answers.
- Android: `ForecastFetchCoordinator` (904 lines) + `FullSyncPipeline` (437 lines).
- Desktop: `DesktopWeatherRepository` `refreshWithOutcome` / `persistForecastResult` /
  `runPostFetchBackfills` (`:581–1060`) + `DesktopWeatherService` `googleNeedsHistory` /
  `googleLiveHours`.
- Same-named twins: `recordProductQuotas`, `markHorizon`, `googleNeedsHistory`, `googleLiveHours`,
  `refillStaleGoogleHours`, `backfillElapsedHistory`, `withHourlySummaries`,
  `fetchPriorDayCloudForecast`.
- The shared run decides; the Host stores rows and quota state.

**C. One `QuotaStateStore` port** (divergence #4). A shared recorder decides record / clear / log-on-change.
Android implements it on prefs, desktop on `app_logs`, until one store is chosen. All banner readers
(`QuotaNotice`, `RateLimitNotice`) go through it.

**D. `MaintenanceMarkers` port** for one-time gates and "last ran" times (#5). This also covers the
prune due-check, which `HistorySnapshotPruner.shouldPrune` and desktop's own `HISTORY_PRUNE_TAG`
logic both implement.

**E. Current-temp resolution.**
- Android: `ObservationResolver.resolveObservedCurrentTemp` (app, 0 shared refs) +
  `TemperatureStateResolver` + `CurrentTempRepository`.
- Desktop: `DesktopWeatherRepository.resolveForForecastResult` / `resolveCurrentTempInMemory`.
- Both feed the header number, so a mismatch shows up as a visible bug. Needs a closer read before
  extracting. `CurrentStatusResolver`'s 2026-10-13 note (73.4 vs 71.8) is a desktop-internal
  instance of the same class of problem.

### Tier 2: view models (what is drawn, before drawing)

**F. Shared `DailyColumnBuilder`** (#7). One function from (daily rows, history, hourly summaries,
now, zone) → neutral `DailyDay` list: values, line kinds, icon name, rain labels, overlay. Desktop's
`DesktopDailyDay` is close to that shape already. Android's four resolvers and `buildDay` collapse
into it. Renderers keep only geometry and paint.

**G. Shared `HourlyAxisPlan`** (#6). `(spanHours, widthPx, narrowWidget)` →
`labelInterval`, `smoothIterations`, `dateMode`, footer labels.
- Moves the pure parts of `DesktopGraphUtils`: `labelIntervalFor*`, `smoothIterationsFor`,
  `representativeIndicesByDay`, `footerLabels`, `backHoursFor` / `forwardHoursFor` / `panDelta*`.
- Removes Android's triplicated `when` block (Cloud, Precip, TemperatureHourDataBuilder).
- Needs a decision: one zoom model (continuous with stages as presets, or stages everywhere).

**H. Rain-loop player state machine.** `RainForecastActivity` (518) and `RainForecastWindow` (626)
each carry only one shared type. Both hand-roll manifest → filter by layer → prefetch → position →
frame → `RainLoopLoadError`. Share the state and transitions; keep bitmap decoding and UI.
`rainLoopErrorText` (both sides) becomes a shared message key, with strings resolved per platform.

**I. Hourly precip/cloud series inputs.** Desktop `buildRainHours` and `actualPrecipRowsForSource`
(`PrecipitationGraph.kt:408–466`), `cloudActualPlotRange`; Android equivalents in
`PrecipitationGraphRenderer` / `PrecipViewHandler`. Placement is shared already
(`RainAmountLabelPlacer`); the series feeding it is not.

**J. Tap zones.** Desktop `computeDailyGraphTapLayout` / `classifyDailyGraphTapZone` vs Android
`DailyClickHandlerFactory` / `TemperatureTouchTargets`. "What a tap at x,y means" is a rule; only
hit-testing input differs. Verify the overlap before moving.

**K. Small twins.** `formatRelativeTime` (`ForecastHistoryActivity` / `PanelIpcServer`),
`columnsForWidthDp` (`WidgetSizeCalculator` 43 lines / `DesktopDailyForecastModel` 17 lines; the
widths should agree), the Settings `labelFor`, and the icon mapping
(`WeatherIconMapper` / `WeatherIcon`; the name is shared, the drawable lookup stays local).

### Tier 3: strategic (the two DAOs)

`DesktopWeatherDao` (2,340 lines of JDBC, already in `:shared`) and the Room DAOs write every query
twice. Same-named queries mean different things: Android `getForecastsInRange` returns the newest
batch, while desktop `getForecastsInRangeBySource` returns every fetch. Two options:
- (a) SQLDelight on both: one `.sq` schema and one set of queries. Large migration.
- (b) **Recommended:** the Tier 1 Host ports name each read by *intent* (`forecastFetchesInRange`,
  `newestForecastsInRange`). One shared contract test suite runs against Room in-memory (`:app`
  Robolectric) and desktop SQLite (temp file). Divergent queries then fail a test, not a user.

## 4. Leave platform-specific

Canvas vs Compose `DrawScope` drawing; RemoteViews binding; WorkManager / JobScheduler / AlarmManager;
tray, genmon, `PanelIpcServer`; UPower / `xset` / `loginctl` detection; Activity / Window lifecycles;
string resources. Settings UI already has a shared catalog (`260912-shared-settings-section-catalog.md`).

## 5. Guardrails so this audit is the last one

1. **Runner + Host rule in CLAUDE.md**: a cross-platform rule is a shared runner with a Host port, and
   it owns windows, scope, gates and logs. A planner fed by platform glue doesn't count as shared.
2. **Duplicate-name detector test** (same shape as the hardcoded-string detector, dd0dd97e). It fails
   when a non-`@Composable`, non-`DrawScope` function name is defined in both `app/src/main` and
   `desktop/src/main` but not in `shared/src/main`, against an allowlist. Today's sweep found 42
   such names (`fun [a-z]\w{5,}(`). Seed the allowlist with them and shrink it as items move.
3. **Host contract tests**: each shared runner gets one fixture-driven test suite run against both
   Hosts (Room + desktop SQLite). Parity is checked by CI, not by spotting a bug on the device.

## 6. Suggested order

1. A (daily history), with guardrail 3. It has the most confirmed divergences and the narrowest port.
2. C + D (quota and marker ports). Small, and B needs them.
3. B (post-fetch run).
4. G (hourly axis plan). Quick and visible; needs the zoom-model decision.
5. F (daily column builder). Biggest view-side win; do it after G so the renderers are already thin.
6. H, I, J, K as time allows. E after a closer read.
7. Guardrails 1 and 2 right away, independent of the above.

## 7. Verification for each extraction

- Shared runner tests against a fake Host, ported from the Android behaviour tests.
- Contract tests on both real DBs.
- `./gradlew testDebugUnitTest :shared:test :desktop:test` and `./scripts/emulator-tests.sh` for
  touched Android paths.
- Restart desktop (`scripts/buildStart-desktop.sh`) and compare `app_logs` tags before and after
  (`FORECAST_ONLY_HISTORY`, `FREEZE_RAIN_CHANCE`, `FORECAST_OVERLAY_SETTLED`, `FETCH_PRODUCT_QUOTA`).
