# Reduce Mocking in DailyViewHandlerTest

**Date:** 2026-04-10
**Scope:** Extract pure decision functions from DailyViewHandler; replace WorkManager/AdWidgetManager mocking with testable pure functions and shared helpers

---

## Prompt 1: Code Review — Should mocking be reduced?

> Code review `DailyViewHandlerTest.kt`. Should mocking be reduced?

### Investigation

- Read full test file (1450 lines) and production code `DailyViewHandler.kt` (1046 lines)
- Read `DailyViewLogic.kt` to understand purity of existing logic functions
- Read project testing strategy (`arch/testing-strategy.md`) and AGENTS.md guidance
- Surveyed all 116 test files to categorize mock usage patterns

### Findings

**Already well-structured tests:**
- ~60% of tests (lines 73-618, 1057-1218) test `DailyViewLogic` and pure helper functions with zero mocking
- `classifyBlockingSourceWarning` tests are pure function tests
- `resolveTodayHeaderForecast` tests are pure function tests
- `buildDayClickIntent` tests use Robolectric for `Context` only

**Over-mocking identified:**

1. **WorkManager static mocking** (2 tests, ~130 lines): `mockkStatic(WorkManager::class)` + `capture(requests)` + verifying `OneTimeWorkRequest` internals. Fragile: tied to WorkManager API, requires teardown cleanup, asserts on `workSpec.input` details.

2. **AppWidgetManager mock boilerplate** (~9 tests, ~90 lines duplicate): Each test repeats mock creation, options Bundle, `getAppWidgetOptions` stub, and `Slot<RemoteViews>` capture. Identical 10-line block copy-pasted across tests.

**Project pattern for this:** `RefreshScheduleDecision` in `WidgetIntentRouter.kt` and `HourlyBackfillDecision` in `HourlyObservationBackfill.kt` — both extract refresh-decision logic into pure functions with data class return types.

---

## Prompt 2: Write plan and implement

> Write plan to plans/ dir and implement

### Plan

Written to `plans/260410-reduce-mocking-daily-view-handler-test.md`. Key decisions:

1. Extract `computeMissingDataRefreshes()` pure function + `MissingDataRefreshDecision` data class into new `MissingDataRefreshHelper.kt`
2. Create `AppWidgetManagerTestHelper.kt` with `mockAppWidgetManager()` helper
3. Refactor `DailyViewHandler.kt` to call pure function then iterate over decisions
4. Replace 2 WorkManager mock tests with 4 pure function tests
5. Replace 9 AppWidgetManager mock boilerplate blocks with helper calls

### Implementation

#### 1. Created `MissingDataRefreshHelper.kt`

New file at `app/src/main/java/com/weatherwidget/widget/handlers/MissingDataRefreshHelper.kt`:

- `MissingDataRefreshDecision` data class with `refreshType`, `forceRefresh`, `reason` fields
- `computeMissingDataRefreshes()` pure function that checks:
  - `dailyActuals[today] == null` → `actuals_today` decision (forceRefresh=true)
  - `displayDays` has today with forecast but no snapshot → `today_snapshot` decision (forceRefresh=false)
  - `displayDays` has past day with forecast but no actuals → `actuals_history` decision (forceRefresh=true)
  - Returns empty list when all data present

Follows existing project pattern: `RefreshScheduleDecision`/`buildRefreshScheduleDecision()` in `WidgetIntentRouter.kt`.

#### 2. Created `AppWidgetManagerTestHelper.kt`

New file at `app/src/test/java/com/weatherwidget/testutil/AppWidgetManagerTestHelper.kt`:

- `CapturedWidgetViews` data class with `appWidgetManager` and `viewsSlot`
- `mockAppWidgetManager(widgetId, widthDp, heightDp)` function that creates mock, builds options bundle, stubs `getAppWidgetOptions`, and captures `RemoteViews` slot

Saves ~10 lines per test that creates AppWidgetManager mocks.

#### 3. Refactored `DailyViewHandler.kt`

Replaced 3 inline condition-check + refresh-call blocks with `computeMissingDataRefreshes()` + iteration:

- Lines 189-198 (early today-actuals check): Now calls `computeMissingDataRefreshes()` then iterates decisions
- Lines 428-467 (missing snapshot + missing history in graph mode): Same pattern
- Removed `requestMissingActualsRefresh()` private method (inlined into decision computation)

Thin `requestMissingDataRefresh()` method remains — it handles cooldown check via `stateManager.shouldRefreshMissingData()`, logging, and `triggerImmediateUpdate()`.

#### 4. Replaced WorkManager mock tests with pure function tests

**Removed 2 tests (~130 lines):**
- `updateWidget enqueues missing actuals fetch for visible past graph day`
- `updateWidget enqueues non-forced refresh when today snapshot is missing`

**Added 4 pure function tests (~65 lines):**
- `computeMissingDataRefreshes requests actuals today when daily actuals missing`
- `computeMissingDataRefreshes requests actuals history when past graph day lacks actuals`
- `computeMissingDataRefreshes requests today snapshot when forecast exists but no snapshot`
- `computeMissingDataRefreshes returns empty when all data present`
- `computeMissingDataRefreshes does not request actuals today when daily actuals present`

Benefits: No Robolectric, no WorkManager, no mocks, no teardown — just `DayData` construction + assertions on decision list.

#### 5. Replaced AppWidgetManager mock boilerplate

All 9 `updateWidget` tests that had:
```kotlin
val appWidgetManager = mockk<AppWidgetManager>()
val options = Bundle().apply { ... }
every { appWidgetManager.getAppWidgetOptions(N) } returns options
val viewsSlot = slot<android.widget.RemoteViews>()
every { appWidgetManager.updateAppWidget(N, capture(viewsSlot)) } just runs
```

Now use:
```kotlin
val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = N, widthDp = W, heightDp = H)
```

#### 6. Cleaned up imports

**Removed from `DailyViewHandlerTest.kt`:**
- `android.appwidget.AppWidgetManager`
- `android.os.Bundle`
- `androidx.work.ExistingWorkPolicy`
- `androidx.work.OneTimeWorkRequest`
- `androidx.work.WorkManager`
- `com.weatherwidget.widget.WeatherWidgetProvider`
- `com.weatherwidget.widget.WeatherWidgetWorker`
- `io.mockk.every`
- `io.mockk.just`
- `io.mockk.mockk`
- `io.mockk.runs`
- `io.mockk.mockkStatic`
- `io.mockk.unmockkStatic`
- `io.mockk.verify`
- `org.junit.After`

**Added:**
- `com.weatherwidget.testutil.mockAppWidgetManager`
- `com.weatherwidget.widget.DailyForecastGraphRenderer`
- `com.weatherwidget.widget.ObservationResolver`
- `io.mockk.CapturingSlot`

**Removed from test class:**
- `@After teardown()` method (was only `unmockkStatic(WorkManager::class)`)

### Verification

```
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewHandlerTest"
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest"
./gradlew :app:compileDebugKotlin
```

All 3 commands pass.

### Files Modified/Created

| File | Action |
|------|--------|
| `app/src/main/.../handlers/MissingDataRefreshHelper.kt` | **Created** — data class + pure function |
| `app/src/main/.../handlers/DailyViewHandler.kt` | **Modified** — use computeMissingDataRefreshes, remove requestMissingActualsRefresh |
| `app/src/test/.../testutil/AppWidgetManagerTestHelper.kt` | **Created** — mock helper |
| `app/src/test/.../handlers/DailyViewHandlerTest.kt` | **Modified** — replace WorkManager tests, use helper, clean imports |
| `plans/260410-reduce-mocking-daily-view-handler-test.md` | **Updated** — detailed plan |

### Metrics

- Net lines in test file: ~1450 → ~1340 (-110 lines)
- WorkManager mock tests: 2 → 0
- Pure function tests added: 5
- AppWidgetManager mock boilerplate blocks: 9 → 0 (all use helper)
- `mockkStatic`/`unmockkStatic` calls: 2 → 0
- MockK imports in test: 8 → 1 (`CapturingSlot`)