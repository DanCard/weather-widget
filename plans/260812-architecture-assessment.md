# Architecture Assessment — Weather Widget

Date: 2026-08-12
Scope: Whole-project architecture review and complexity analysis. Companion to the rewritten
`arch/ARCHITECTURE.md` (committed as `054d4425`).

## 1. Overall Verdict

This is a large, genuinely complex, and unusually disciplined codebase — ~46k lines of Android
code, ~18k of shared pure-JVM code, ~14k of desktop code, backed by ~77k lines of tests (421 test
files). It has outgrown its "widget app" framing: it is now a multi-provider weather pipeline
(7 API sources + a climate-normals gap-fill source) with forecast-accuracy tracking, observation
blending, and a full desktop port.

The single best architectural decision is the `:shared` seam. All hard algorithmic logic — API
clients, graph geometry, label placement, actuals blending, accuracy math — lives in platform-free
Kotlin. This is why:

1. 112 pure-JVM test files run in under a second.
2. Android and desktop compute pixel-identical graph layouts (each platform only draws the shared
   plan).

Two other things stand out as unusually good:

1. **Observability discipline.** The tiered `app_logs` system (VERBOSE = ephemeral, DEBUG+ =
   persisted), `ProcessExitLogger` for native crashes, and `SYNC_PERF`/`SYNC_STAGE` timing
   breadcrumbs turn "the widget died" into queryable evidence. This reflects the evidence-first
   culture in `AGENTS.md`.
2. **Encoded hard-won lessons.** The WorkManager enqueue-policy rules (the ART-native-crash trap)
   and the race handling in `FullSyncPipeline` (hourly-source snapshot re-read) are documented in
   the code itself, not just in plans.

## 2. Strengths

1. Excellent pure-logic seam (`:shared`) — algorithmic core is platform-free and fast-tested; the
   foundation of Android/desktop parity.
2. Testable policy extraction — scheduling/fetch/staleness policies are small pure classes with
   unit tests, keeping the worker thin.
3. Deep observability — tiered app-log system and process-exit logging turn "dead widget"
   incidents into queryable evidence.
4. Disciplined concurrency — per-widget interaction locks and WorkManager enqueue-policy rules
   encode hard-won lessons directly in code comments.
5. Self-healing, idempotent data layer — conditional migrations, fragment dedupe, and
   retry-until-flag-consumed backfills.

## 3. Weaknesses / Risks

1. **Concentration of complexity** in graph label placement, actuals blending, and widget
   touch-routing — the three areas that dominate the bug history.
2. **God files persist** — `PrecipitationGraphRenderer` (951), `DailyViewHandler` (858),
   `DesktopWeatherDao` (1,127), `Main.kt` (1,825), `TemperatureLabelEngine` (1,202).
   Decomposition has been attempted repeatedly but large files keep re-accumulating.
3. **Two parallel persistence layers** (Android Room vs. desktop SQLite) plus duplicated utilities
   across `app/util` and `shared/util` (`RainAnalyzer`, `TempUtils`, `NavigationUtils`,
   `SunPositionUtils` exist in both). A "shared code deduplication" effort is ongoing.
4. **Documentation lag** — ~450 files in `plans/` but `ARCHITECTURE.md` had not been refreshed
   since May; the plan archive is the de-facto knowledge base and is hard to navigate.

## 4. Most Complex Parts (Ranked)

1. **Graph label placement engine** (`shared/graph/`)
   - `TemperatureLabelEngine` (1,202 lines) + `TemperatureLabelResolver` (1,031) +
     `TodayColumnOverlayPlanner` + a dozen role-specific label classes.
   - A continuous collision-avoidance layout problem: per-role curve-avoidance margins,
     leader-line displacement, overlap budgets, and a dual-platform pixel-parity requirement.
   - Dominates the bug history — dozens of `plans/` files are label-overlap/collision
     investigations.

2. **Actuals / observation blending** (`shared/actuals/` + `shared/observations/` +
   `ObservationRepository`/`DailyActualsStore`)
   - Reconciles NWS station readings (QC flags, Synoptic web fallback), Open-Meteo ERA5, and
     provider archives into one consistent "actual high/low" that must agree across daily view,
     hourly graph, and accuracy stats.
   - Edge cases: sentinel temps, personal-station discounting, cross-location leaks.

3. **Widget interaction & touch routing** (`widget/handlers/`)
   - RemoteViews cannot express real touch layout, so tap zones are derived from rendered geometry
     and routed through the per-widget-mutex `WidgetInteractionCoordinator` →
     `WidgetIntentActionHandler` → view handlers (`DailyViewHandler` 858, `DailyViewLogic` 784,
     `TemperatureStateResolver` 762).

4. **Data layer + migrations**
   - Schema v61 with 17 migrations, several doing data surgery (float-lat/lon fragment dedup,
     table renames with index rebuilds, sentinel-poisoning cleanup).
   - The `LocationMatch` quantization contract is the subtlest correctness issue.

5. **Desktop daemon/UI two-process split** (`DaemonProcess` 771 + `Main` 1,825)
   - Suspend/resume + network-restore detection via `gdbus`, trigger-file + socket IPC,
     single-instance tokens.

## 5. Recommendation for Code Review

Start with **#1 (label placement engine)** or **#2 (actuals blending)** — the highest-complexity,
highest-bug-density areas. Candidate files:

- `TemperatureLabelEngine` + `TemperatureLabelResolver` (+ `GraphLabelPlacementUtils`,
  `TemperatureExtrema`) for #1.
- `ActualTemperatureSeriesBuilder` + `DailyActualsStore` (+ `ActualsAggregator`,
  `ApiActualPicker`, `ObservationRepository`) for #2.
