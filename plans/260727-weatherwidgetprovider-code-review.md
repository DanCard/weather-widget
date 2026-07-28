# Code Review: WeatherWidgetProvider.kt (Priority 1, file 2)

Source: `plans/260725-code-review-queue.md` (score 11/12)
Reviewed: 2026-07-27
File: `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` (1256 lines)

## Findings

### F1 — Parallel current-temp-only + full-fetch on refresh (redundant work) [HIGH]

`handleRefreshAction` (`:846-864`) enqueues a `KEY_CURRENT_TEMP_ONLY` worker
under `WORK_NAME_ONE_TIME + "_current_temp"` with `KEEP`, then immediately
enqueues the full fetch via `triggerImmediateUpdate(... forceRefresh=true ...)`
under `WORK_NAME_ONE_TIME` with `KEEP`. Both run in parallel.

`WeatherWidgetWorker.doWork` shows `currentTempOnly` is a distinct path
(`handleCurrentTempOnlyWork`, `:576`) that hits the network for current obs
only. The full fetch also fetches current temp. So on a stale refresh the
same current-temp network call fires twice.

**Fix:** Drop the redundant `tempWorkRequest`. The full fetch covers it.
If a deliberate latency optimization was intended (current-temp first),
that should be a chained `beginWith(temp).then(full)` with annotation,
not parallel fire-and-forget — but no comment documents that intent, so
treat as bug.

### F2 — `stateManager(context)` re-instantiated throughout interaction paths [HIGH]

`:1002` `private fun stateManager(context: Context) = WidgetStateManager(context)`

`handleDayClickAction` calls `stateManager(context)` multiple times within
the same `launchAsync { ... }` body (`:678`, `:693`, `:709`). Each is a
hashmap lookup + allocation in `Context.getSharedPreferences` (cached, but
still wasteful) and risks reading slightly inconsistent state if a write
lands between reads.

**Fix:** Cache into a local `val stateManager = stateManager(context)` at
the top of each interaction handler's `launchAsync` body.

### F3 — `ViewMode.valueOf(name)` try/catch duplicated with different defaults [MED]

`handleDayClickAction` (`:661-666`) defaults to `PRECIPITATION`.
`handleSetViewAction` (`:978-983`) defaults to `DAILY`.

**Fix:** Extract `ViewMode.parseOrDefault(name, default)` helper. Centralizes
future parsing changes (case-insensitive, aliasing).

### F4 — `ACTION_LOCALE_CHANGED` does not refresh `DailyViewHandler.headerDateFormatter` [MED]

`WeatherWidgetProvider.onReceive` handles `ACTION_LOCALE_CHANGED` by
re-rendering from cache (`:561-566`). But
`DailyViewHandler.headerDateFormatter` (`:75`) is `internal val ...
Locale.getDefault()` — captured at class-load. The process is NOT
restarted on locale change (just a config update), so the formatter stays
in the old locale. This is the F6 "acknowledged tradeoff" from file 1
biting in practice.

**Fix:** Convert `headerDateFormatter` to a function or computed property
that resolves `Locale.getDefault()` per call. `DateTimeFormatter.ofPattern`
allocation is microseconds; cost is negligible per render.

### F5 — `onDeleted` does not cancel `WidgetUpdateTracker` jobs for the widget [MED]

`:525-536` clears state and `WidgetPushDispatcher.forgetWidget(id)` but
leaves the widget's in-flight `WidgetUpdateTracker` jobs running. They
complete into a deleted `appWidgetId` (silently no-op via
`updateAppWidget(deletedId, ...)`), burning CPU/DB.

`WidgetUpdateTracker.trackJob` exists; a `cancelJobs(appWidgetId)` API
needs adding.

Note: AGENTS.md's "don't cancel running WeatherWidgetWorker" applies to
*WorkManager* work, not in-process coroutines — safe to cancel here.

**Fix:** Add `WidgetUpdateTracker.cancelJobs(appWidgetId)` and call it in
`onDeleted`.

### F6 — `handleRefreshAction` does not register its job with `WidgetUpdateTracker` [DEFERRED]

Every other handler (`onUpdate`, `onAppWidgetOptionsChanged`,
`handleNavigationAction`, `handleToggleApiAction`, etc.) wraps its
`launchAsync` block via `WidgetUpdateTracker.trackJob(...)`.
`handleRefreshAction` (`:815`) does not — its launch runs untracked.

**Decision: Defer.** The existing `trackJob` policy explicitly does NOT
cancel UI paints when a background sync arrives (UI paints are fast and
should always complete). `renderAllWidgetsFromCache` is a UI paint, so
even if tracked, the policy would not change behavior materially. F5
(cancelJob in onDeleted) handles the wasteful-completion case for
tracked jobs; the untracked refresh paints complete into deleted widgets
silently (no-op). Marginal value, defer until trackJob semantics evolve.

### F7 — `HOUR_ZONE_COUNT = 13` declared but `zoneIndexToOffset` uses literal `12`/`12f` [LOW]

`:1133` companion const is referenced by a test
(`ZoomCycleRoboTest:228`). The function body uses literal `12f` and `12`.

**Fix:** Replace literals with `HOUR_ZONE_COUNT - 1`. Constant stays
test-visible; arithmetic now self-documents.

### F8 — `formatNoHourlyDayLabel` companion function is a dead forwarder [LOW]

`:1080-1082` is a one-line alias to
`NoHourlyDayClickCoordinator.formatDayLabel`. No callers (verified via
grep).

**Fix:** Remove.

### F9 — `WeatherDatabase.getDatabase(context)` re-opened in nested scopes [LOW]

`onUpdate` opens `database = WeatherDatabase.getDatabase(context)` (`:141`).
`renderStartupWidgets` calls it again at `:410`. Same pattern at `:581`,
`:827`, `:1025`. Room singleton accessor is idempotent, so this is purely
a readability smell.

**Fix:** Thread `appLogDao` (or `database`) into the helpers via params
where the outer scope already has one.

### F10 — Top-of-file doc comment uses tabs; rest of file uses spaces [LOW]

Cosmetic. Skip.

## Implementation Order

1. F1 (drop redundant parallel work) — small, isolated
2. F2 (cache stateManager in interaction handlers) — pattern, mechanical
3. F4 (locale-aware formatter) — touches DailyViewHandler + 2 callers
4. F5 (WidgetUpdateTracker.cancelJobs + onDeleted) — new API
5. F6 (track refresh job) — small extension to F5
6. F3 (ViewMode parser helper) — extract
7. F7 (HOUR_ZONE_COUNT in zoneIndexToOffset) — literal swap
8. F8 (remove formatNoHourlyDayLabel) — delete
9. F9 (thread appLogDao) — multiple sites

## Verification

* `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin`
* `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.WeatherWidgetProvider*"`
* `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.ZoomCycleRoboTest"`
* `:app:testLongDebugUnitTest --tests "com.weatherwidget.widget.handlers.*"`
* `:app:testShortDebugUnitTest`
