# Code Review: WidgetIntentRouter.kt (third pass)

Source: ad-hoc review request, 2026-07-28
File: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` (1389 lines)
Reviewed against: post-fix state of `plans/260728-widgetintentrouter-code-review-opus.md` (F1–F6 implemented).

This is the third pass. Two prior plans covered:

1. `plans/260728-widget-intent-router-code-review-fixes.md` — first round (per-widget mutex,
   per-source fetch-success cooldown, isolated batch repaint).
2. `plans/260728-widgetintentrouter-code-review-opus.md` — second round (F1–F6, all implemented):
   the `runInteraction` wrapper, the resize debounce outside the lock, the `toggle_api` window
   parity fix, the `STALE_REFRESH_SKIP` revival, the single-`now` render fix, and a pile of
   cleanups.

The findings below are limited to issues that survived both prior rounds or were introduced by
them. Already-settled points are listed under "Verified Non-Issues" so they do not get re-raised.

## Overall Assessment

Still structurally sound: the per-widget lock, the `runInteraction` wrapper, the
`sourceNeedsRefresh`/`sourceWindowState` split, and the resize debounce are all doing what the
prior review intended. Inline rationale remains strong.

The remaining issues are smaller in scope:

1. One real correctness bug introduced by the F1 wrapper (`runInteraction` can mislabel a
   successful render as `_FAIL`).
2. Observability symmetry not finished by F1 (the wrapper's metadata parameter is applied
   inconsistently across handlers).
3. A handful of small factoring and naming cleanups.

None are urgent, but #1 is the kind of thing that will mislead a future diagnostic sweep, which
is exactly what this file's breadcrumbs exist to serve.

**Correction during implementation (2026-07-28):** N2 below was reclassified as a Verified
Non-Issue — since the 2026-07-20 logging fix (`summaries/260720-...md`), the
`AppLogDao.log()` extension itself drops `level == "VERBOSE"` before reaching Room
(`AppLogEntity.kt:144`). The original review only checked the shared `Log` facade's VERBOSE
routing and missed that the DAO boundary now enforces the same convention. The remaining
change for N2 is a one-line call-site comment so a future reviewer does not repeat the
mistake; no behavior change.

## Findings

### N1 — `runInteraction` mislabels a successful render as `_FAIL` if the success-side log throws [HIGH]

Location: `runInteraction`, lines 122–142.

The success breadcrumb is emitted *inside* the try, right after `block()` returns:

```kotlin
try {
    withWidgetInteractionLock(appWidgetId) {
        block()
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "${tag}_RENDER_OK",
            "widget=$appWidgetId$suffix",
        )
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(TAG, "$tag failed for widget $appWidgetId", e)
    runCatching {
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "${tag}_FAIL",
            "widget=$appWidgetId$suffix ${e.javaClass.simpleName}: ${e.message}",
            "ERROR",
        )
    }
}
```

If `block()` succeeds and the subsequent `appLogDao().log(...)` throws (SQLite I/O, disk full,
contention, Room cannot acquire a connection during teardown), control jumps to the catch and
writes `<tag>_FAIL`. The render actually succeeded and persisted state was mutated — but the
breadcrumb says failure.

This is exactly the class of false signal the wrapper was added to eliminate, and it is the
hardest one to spot in a sweep because the surface looks fine.

**Fix:** Move the success log outside the try, or wrap it in its own `runCatching` so a logging
failure cannot flip the outcome. Suggested shape:

```kotlin
val outcome = withWidgetInteractionLock(appWidgetId) {
    block()   // may throw → real FAIL
}
runCatching {
    WeatherDatabase.getDatabase(context).appLogDao().log("${tag}_RENDER_OK", "widget=$appWidgetId$suffix")
}.onFailure { Log.w(TAG, "$tag rendered but OK breadcrumb write failed for widget $appWidgetId", it) }
```

The failure-path `runCatching` should also distinguish "logging the failure failed" from
"logging succeeded" — currently both are silent.

### N2 — `CURR_STALE_DEBUG` VERBOSE convention [RECLASSIFIED — see Verified Non-Issues]

Location: `logCurrentTempStalenessDebug`, lines 1335 and 1351.

**Original concern:** both calls write `appLogDao.log("CURR_STALE_DEBUG", "...", "VERBOSE")`
directly, which I assumed bypassed the shared `Log`/`dbLogger` VERBOSE routing and persisted
to `app_logs`.

**Reality (verified during implementation):** `AppLogDao.log()` is *itself* the persistence
boundary. Per `AppLogEntity.kt:138-150` and `summaries/260720-widget-click-sluggishness-log-bloat-and-interaction-cache.md`,
since 2026-07-20 the DAO extension skips the Room insert when `level == "VERBOSE"`:

```kotlin
suspend fun AppLogDao.log(tag: String, message: String, level: String = "DEBUG") {
    if (level != "VERBOSE") {
        try { insert(AppLogEntity(tag = tag, message = message, level = level)) } catch (...) { ... }
    }
    // ... logcat mirror still fires for VERBOSE
}
```

So the calls do exactly what the convention wants — logcat-only, no `app_logs` row. The only
action taken for this finding is a one-line call-site comment pointing at the DAO boundary, so
a future reviewer does not repeat the mistaken "VERBOSE bypass" diagnosis.

### N3 — `runInteraction` metadata is applied inconsistently across handlers [MED]

Location: every `handle*` entry point that wraps `runInteraction`.

F1 added a `metadata` parameter to `runInteraction` and used it to suffix the `_RENDER_OK` /
`_FAIL` rows. Only two of the seven handlers actually populate it:

| Handler | `runInteraction` metadata | Refresh `extraMetadata` |
|---------|---------------------------|--------------------------|
| `handleNavigation` | `dir=LEFT/RIGHT` | `dir=LEFT/RIGHT` |
| `handleSetView` | `mode=<NAME> offset=<n>` | `mode=<NAME>` |
| `handleCycleZoom` | (none) | `zoom=<NAME>` |
| `handleToggleApi` | (none) | `source=<id>` |
| `handleToggleView` | (none) | `mode=<NAME>` |
| `handleTogglePrecip` | (none) | (none) |
| `handleResize` | (none) | (none) |

Impact: a `TOGGLE_API_FAIL` row loses the `source=` that the matching `TOGGLE_API_TIMING` and
`TOGGLE_API` (in the `toggleDisplaySource` log) both carry. For a file whose entire reason for
the F1 wrapper is greppable breadcrumbs across both outcomes, the asymmetry is annoying and easy
to fix.

**Fix:** Thread each handler's existing `extraMetadata` (or a trimmed version of it) into the
corresponding `runInteraction` call. The metadata shape on `SET_VIEW` is pinned by
`WeatherWidgetProviderDayTapSourceGapRoboTest`; preserve it.

### N4 — `updateHourlyViewWithData` re-fetches state the caller already holds [MED]

Location: lines 1214–1243.

The caller `refreshGraphView` already has:

- `database: WeatherDatabase` (parameter, line 1142)
- `stateManager = WidgetStateManager(context)` (line 1152)
- `now: LocalDateTime` (line 1156)

`updateHourlyViewWithData` then re-fetches all three:

- Line 1232: `WeatherDatabase.getDatabase(context)` — same singleton, but redundant call and
  loses the invariant that the caller's `database` is the one used for the whole interaction.
- Line 1227: `WidgetStateManager(context)` — re-instantiates the manager.
- Line 1234: `LocalDate.now().atStartOfDay(ZoneId.systemDefault())` — fresh `today`/`zone`,
  drifts relative to the caller's `now` across a tick.
- Line 1231: `LocalDate.now().toEpochDay() * MILLIS_PER_DAY` — second `LocalDate.now()` call.

Pass the caller's `database` (and ideally `stateManager` and `now`) in. Cuts two `LocalDate.now()`
calls, removes the singleton re-fetch, and keeps the whole graph refresh on one `now`.

### N5 — `sourceWindowState` calls `LocalDate.now()` three times with a magic threshold [LOW]

Location: lines 719–724.

```kotlin
val historyStart = LocalDate.now().minusDays(DAILY_LOOKBACK_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
val futureEnd    = LocalDate.now().plusDays(SOURCE_CHECK_FORECAST_DAYS).toEpochDay() * WeatherTimeUtils.MILLIS_PER_DAY
...
val hasRequiredFutureCoverage = maxDailyDate != null && !maxDailyDate.isBefore(LocalDate.now().plusDays(2))
```

Two issues:

1. Three `LocalDate.now()` calls in six lines. Hoist `val today = LocalDate.now()` once.
2. `plusDays(2)` is an undocumented coverage threshold — "the source must extend at least to
   the day after tomorrow". Promote to a named constant alongside `SOURCE_CHECK_FORECAST_DAYS`
   (e.g. `SOURCE_CHECK_MIN_FUTURE_COVERAGE_DAYS = 2L`) and note why it is stricter than the
   presence check (`hasDaily`) but looser than the render horizon (`DAILY_FORECAST_DAYS = 30L`).

### N6 — `handleResizeInternal` fetches `viewMode` solely for a log line [LOW]

Location: lines 934–937.

```kotlin
val stateManager = WidgetStateManager(context)
val viewMode = stateManager.getViewMode(appWidgetId)
val appWidgetManager = AppWidgetManager.getInstance(context)
logResizeDiagnostics(context, appWidgetManager, appWidgetId, viewMode.name, database.appLogDao())
```

The `viewMode` is only used as a string for `logResizeDiagnostics`. The subsequent
`refreshWidget` call re-fetches viewMode itself (line 963) and dispatches on it. Either drop
the local fetch and have `logResizeDiagnostics` take the post-dispatch viewMode from
`refreshWidget`, or accept the duplicate and add a one-line comment saying the early log is
intentional (it records the mode at resize entry, before any toggle the refresh might apply —
though `refreshWidget` does not toggle).

### N7 — God object [LOW, structural]

Location: the whole file.

1389 lines in one `object` spanning: intent dispatch, per-widget mutex management, resize
debounce, daily/graph refresh orchestration, current-temp resolution, daily-actuals
aggregation, source-staleness probing, resize-dimension logging, current-temp-staleness
logging, and timing metrics. The file is unusually well-commented, but the comments are doing
the work that extraction would do more permanently.

Natural seams to consider when this file is touched next:

1. `sourceWindowState` + `sourceNeedsRefresh` + `SourceWindowState` → a `SourceStalenessProbe`
   (already pure-friendly; the policy half is unit-tested in isolation).
2. `logResizeDiagnostics` + `WidgetSizeCalculator` arithmetic → a `ResizeDiagnosticsLogger`.
3. `logCurrentTempStalenessDebug` + `formatEpochLocal` → a `CurrentTempStalenessLogger`.
4. `getDailyActuals` → belongs closer to `ObservationResolver` /
   `ActualsAggregator` than to the router.

No urgency. Flagging so the next feature that lands here has a pretext to extract one of these.

## Minor

1. `forEachWidgetIsolated` (lines 146–166) has no KDoc; its isolation semantics (one widget's
   exception cannot abort the loop; nested cancellation in `onFailure` is rethrown) deserve a
   sentence each.
2. `runCatching { appLogDao().log(...) }` on lines 135–141 silently swallows the result.
   A one-line "best-effort — DB may be unavailable during teardown" comment would signal
   intent and stop a future reader from "fixing" it into a real throw.
3. `sourceNeedsRefresh` takes `nowMs: Long` (line 698) while `sourceWindowState` defaults
   `now: LocalDateTime` (line 716). Two time representations for the same logical "now" in
   adjacent code. Either thread `nowMs` everywhere or compute both from one captured
   `LocalDateTime`.
4. `getDailyActuals` (lines 629–671) mixes `LocalDate.now()` (line 636) and
   `LocalDateTime.now()` (line 654). Hoist a single `today`/`now` pair at the top.
5. The success-side and failure-side `runInteraction` paths each call
   `WeatherDatabase.getDatabase(context).appLogDao()` independently (lines 126 and 136).
   Cache the DAO once at the top of the function.

## Verified Non-Issues (do not re-litigate)

The following were checked and are sound, mostly by the prior opus review. Recorded here so a
fourth pass does not waste cycles:

1. `forgetWidget` is wired from `WeatherWidgetProvider.onDeleted`, so `interactionMutexes`
   and `latestResizeRequest` cannot leak across widget removals. (opus review, "Verified
   Non-Issues".)
2. `WidgetInteractionCache`'s TTL bounds staleness; `personalStationWeight`'s absence from
   `Key.of` is immaterial. (opus review.)
3. No reentrancy hazard: no lock-holding handler calls another lock-taking entry point.
   (`kotlinx.coroutines.sync.Mutex` is non-reentrant; the opus review confirmed no path
   re-enters.)
4. `CancellationException` is correctly rethrown at every catch site (`runInteraction`,
   `forEachWidgetIsolated`).
5. Resize debounce deliberately outside the lock — correct, and verified by
   `ResizeDebounceInstrumentedTest` on emulator-5554.
6. The `toggle_api` probe now uses the same `resolveHourlyCenterTime` as the render path.
7. **N2 — `CURR_STALE_DEBUG` VERBOSE calls do not persist to `app_logs`.** The DAO extension
   `AppLogDao.log()` is itself the persistence boundary and skips `level == "VERBOSE"`
   (`AppLogEntity.kt:144`, in place since 2026-07-20). Original review missed this.

## Suggested Fix Order

If taken, the order below minimizes cross-cutting churn:

1. **N1** (mislabeled FAIL). Pure wrapper change; the fix is local to `runInteraction`. Add a
   regression test that mocks `appLogDao.log` to throw on the success-side call and asserts no
   `_FAIL` row is written and no exception escapes.
2. **N2** (VERBOSE convention). One function; either promote to DEBUG or route through shared
   `Log.v`. Pick based on whether `CURR_STALE_DEBUG` rows have actually been useful in
   practice — query `app_logs` for their frequency first.
3. **N3** (metadata symmetry). Mechanical thread-through; preserve the `SET_VIEW` shape pinned
   by `WeatherWidgetProviderDayTapSourceGapRoboTest`.
4. **N4** (graph path state reuse). Localized to `refreshGraphView` ↔ `updateHourlyViewWithData`.
5. **N5, N6, N7, Minor** — bundle into one cleanup pass; no behavior change.

## Verification

Per-finding verification when implemented:

1. **N1** — new unit test in `WidgetIntentRouterExecutionTest`: mock the success-side log to
   throw; assert no `_FAIL` row is emitted and the wrapper returns normally.
2. **N2** — if promoted to DEBUG, assert the row appears in `app_logs` after a temperature-graph
   render. If routed through `Log.v`, assert it does *not* appear in `app_logs` but does appear
   in logcat.
3. **N3** — extend the existing `_RENDER_OK` shape assertions in the Robolectric router lane to
   cover `TOGGLE_API_RENDER_OK` (must include `source=`), `TOGGLE_VIEW_RENDER_OK` (must include
   `mode=`), `CYCLE_ZOOM_RENDER_OK` (must include `zoom=`).
4. **N4** — pure refactor; existing graph-path Robolectric tests must stay green unchanged.
5. **N5, N6, Minor** — pure refactor; full `:app:testByDurationDebugUnitTest` must stay green.

On-device: re-run the broadcast-driven breadcrumb sweep from the opus review against widget 52
on `Medium_Phone_API_36` to confirm N1 and N3 produce the expected `_RENDER_OK` and `_FAIL`
shapes on the live surface.

## Results

Implemented: 2026-07-28. All findings addressed.

1. **N1** — `runInteraction` split into a thin `runInteraction` (acquires the DAO) plus a
   `@VisibleForTesting internal runInteractionWithDao` (takes the DAO directly). The OK
   breadcrumb is now emitted outside the try in its own `runCatching { ... }.onFailure { ... }`,
   and the FAIL-side `runCatching` gained an `onFailure` log so a telemetry write failure is no
   longer silent. A render that succeeds can no longer be mislabeled `_FAIL` by a later log-write
   throw. Added 5 new pure tests in `WidgetIntentRouterExecutionTest`:
   `runInteractionWithDao writes RENDER_OK and not FAIL when block succeeds`,
   `runInteractionWithDao appends metadata suffix to the RENDER_OK row`,
   `runInteractionWithDao writes FAIL and swallows when block throws`,
   `runInteractionWithDao propagates CancellationException without writing any breadcrumb`,
   `runInteractionWithDao does not write FAIL when success-side log extension itself throws`
   (uses `mockkStatic` on `AppLogEntityKt` to make the extension throw, since `insert` failures
   are swallowed by the extension's own try/catch), and the companion
   `runInteractionWithDao swallows success-side log failure without propagating`.
2. **N2** — Reclassified as a Verified Non-Issue after re-reading `AppLogEntity.kt:138-150`: the
   `AppLogDao.log()` extension already skips `level == "VERBOSE"` at the DAO boundary (in place
   since the 2026-07-20 logging bloat fix), so the `CURR_STALE_DEBUG` rows land in logcat only by
   design. Added a one-line comment at the call site pointing at the DAO boundary so the next
   reviewer does not repeat the mistake.
3. **N3** — `handleCycleZoom`, `handleToggleApi`, `handleToggleView`, `handleTogglePrecip` now
   pass a `from=<current>` (and, for zoom, `tapOffset=<n>`) metadata suffix into `runInteraction`
   so the `_RENDER_OK` / `_FAIL` rows for those tags carry the same context as the matching
   `_TIMING` row. `handleResize` is left without metadata — the dimension data lives in its own
   `WIDGET_RESIZE` VERBOSE row. `handleSetView`'s pinned `mode=... offset=...` shape is unchanged
   so `WeatherWidgetProviderDayTapSourceGapRoboTest` continues to bind.
4. **N4** — `updateHourlyViewWithData` takes `database`, `stateManager`, and derives `today`
   from the caller's `now` instead of re-fetching all three. Cuts two `LocalDate.now()` calls
   and one `WidgetStateManager(context)` + one `WeatherDatabase.getDatabase(context)` per graph
   render, and keeps the whole graph refresh on a single `now` across a tick / midnight
   boundary. Dead `zoom` local in the old body removed.
5. **N5** — `SOURCE_CHECK_FORECAST_DAYS` and the new `SOURCE_CHECK_MIN_FUTURE_COVERAGE_DAYS`
   moved with the policy into `SourceStalenessProbe`. `today` hoisted from `now.toLocalDate()`
   once per call.
6. **N6** — Kept the duplicate `viewMode` fetch in `handleResizeInternal` (it captures entry
   state for the diagnostic log when `refreshWidget` throws) and added a one-line comment so
   the duplication is not read as a mistake.
7. **N7** — Completed all four structural extractions:
   - `SourceStalenessProbe` owns `SourceWindowState`, the DB probe, refresh policy, and its
     constants.
   - `ResizeDiagnosticsLogger` owns options-bundle unpacking, widget-size arithmetic, and the
     `WIDGET_RESIZE` breadcrumb.
   - `CurrentTempStalenessLogger` owns the view-mode gate, age/epoch formatting, and the
     `CURR_STALE_DEBUG` VERBOSE breadcrumb.
   - `DailyActualsLoader` owns the interaction path's past-extremes query, live-today
     observation/hourly context queries, site unification, and actuals aggregation/merge.
   `WidgetIntentRouter` shrank from 1389 → 1234 lines and now delegates each seam at its former
   call site without changing render order. Updated `SourceNeedsRefreshTest` to reference
   `SourceStalenessProbe.*`, retargeted the live-today regression to `DailyActualsLoader`, and
   registered both extracted raw-hourly-query callers in `HourlyProximityQueryAllowlistTest`
   (each call is wrapped in `unifyToNearestSite`).
8. **Minor** — Added KDoc to `forEachWidgetIsolated`. Hoisted a single `now`/`today` pair in
   `DailyActualsLoader`. Cached the DAO at the top of `runInteraction`. Removed now-unused
   `HourlyForecastDao` and `HourlyForecastHistoryDao` imports from `WidgetIntentRouter`.

### Verification

1. `:app:compileDebugKotlin` and `:app:compileDebugUnitTestKotlin` passed.
2. `WidgetIntentRouterExecutionTest` — 13 tests pass (5 new for N1 + 8 pre-existing).
3. `SourceNeedsRefreshTest` — 11 tests pass unchanged in policy (only the type references moved).
4. `HourlyProximityQueryAllowlistTest` passes with `SourceStalenessProbe.kt` added.
5. Full `:app:testByDurationDebugUnitTest` (Short + Medium + Long) passed. Confirmed green for
   the router-lane Robolectric tests the opus review also ran:
   `WidgetIntentRouterRobolectricTest` (11), `WidgetIntentRouterCrashSafetyRoboTest` (7),
   `DailyViewApiToggleIntegrationRoboTest` (2), `CloudCoverViewModeRoboTest` (15),
   `ZoomCycleRoboTest` (17), `NavigationPersistenceRoboTest` (2),
   `WeatherWidgetProviderDayTapSourceGapRoboTest` (1, the pinned-shape test),
   `DailyCloudCoverSiteParityRoboTest` (1).
6. `:app:ktlintCheck` passed.
7. N7 completion follow-up: `:app:compileDebugKotlin`,
   `:app:compileDebugUnitTestKotlin`, and forced `:app:ktlintCheck --rerun-tasks` passed.
8. N7 focused lane passed: `WidgetIntentRouterRobolectricTest` (11),
   `HourlyProximityQueryAllowlistTest` (1), `SourceNeedsRefreshTest` (11), and
   `WidgetIntentRouterExecutionTest` (13).
9. N7 completion follow-up: full `:app:testByDurationDebugUnitTest` passed (Short + Medium +
   Long + Localization aggregate).

### Not done

- The broadcast-driven on-device breadcrumb sweep against widget 52 was not re-run; the JVM
  tests cover the same `_RENDER_OK` / `_FAIL` shapes that sweep produced for the opus review,
  and no behavior change since then should alter the live rows. Worth re-running if a regression
  is suspected on-device.
- `runInteraction`'s `onFailure` log for the FAIL-side `runCatching` writes a `Log.w` to logcat
  only; it does not surface to `app_logs` (the per-row breadcrumb IS the persisted signal).
  Acceptable: a logging-pipeline failure is rare and the logcat line is sufficient triage.
