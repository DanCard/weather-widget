# Reduce Mocking in DailyViewHandlerTest.kt

## Problem

`DailyViewHandlerTest.kt` (1450 lines) has two categories of over-mocking:

1. **WorkManager static mocking** (2 tests, ~130 lines): `mockkStatic(WorkManager::class)` + `capture(requests)` + verifying `OneTimeWorkRequest` internals. Tests decision logic through integration wiring rather than directly. Fragile: tied to WorkManager API, requires teardown cleanup, asserts on internal `workSpec.input` details.

2. **AppWidgetManager mock boilerplate** (~9 tests, ~90 lines duplicated): Each test repeats mock creation, options Bundle construction, `getAppWidgetOptions` stub, and `Slot<RemoteViews>` capture. Identical 10-line block copy-pasted across tests.

## Strategy

Follow the project's existing "pure function extraction" pattern (see `RefreshScheduleDecision` in `WidgetIntentRouter.kt`, `HourlyBackfillDecision` in `HourlyObservationBackfill.kt`):

1. Extract the refresh decision logic into a pure function + data class.
2. Replace mock-heavy WorkManager tests with pure function tests.
3. Extract common AppWidgetManager mock setup into a test helper.

## Change 1: Create `MissingDataRefreshHelper.kt`

**File**: `app/src/main/java/com/weatherwidget/widget/handlers/MissingDataRefreshHelper.kt`

New file containing:
- `MissingDataRefreshDecision` data class (refreshType, forceRefresh, reason)
- `computeMissingDataRefreshes()` pure function

Logic extracted from `DailyViewHandler.updateWidget`:
- If `dailyActuals[today] == null` → `actuals_today` with `forceRefresh=true`
- If any display day is today with forecast but no snapshot → `today_snapshot` with `forceRefresh=false`
- If any visible past day has forecast but no actuals → `actuals_history` with `forceRefresh=true`
- Returns empty list when all data is present

## Change 2: Modify `DailyViewHandler.kt`

- Import and call `computeMissingDataRefreshes()` in `updateWidget`
- Replace inline condition checks at lines 189-198, 428-448, 450-467 with iteration over decisions
- Thin wrapper `requestMissingDataRefresh` unchanged (still handles cooldown + WorkManager)
- Remove `requestMissingActualsRefresh` (inlined into the decision computation)

## Change 3: Create `AppWidgetManagerTestHelper.kt`

**File**: `app/src/test/java/com/weatherwidget/testutil/AppWidgetManagerTestHelper.kt`

```kotlin
data class CapturedWidgetViews(
    val appWidgetManager: AppWidgetManager,
    val viewsSlot: Slot<RemoteViews>,
)

fun mockAppWidgetManager(
    widgetId: Int,
    widthDp: Int = 200,
    heightDp: Int = 90,
): CapturedWidgetViews
```

## Change 4: Modify `DailyViewHandlerTest.kt`

- **Delete** the 2 WorkManager mock tests (lines 990-1054, 1117-1183). Replace with 4 pure function tests on `computeMissingDataRefreshes`.
- **Replace** AppWidgetManager mock boilerplate in ~9 tests with `mockAppWidgetManager()` calls.
- **Remove** `mockkStatic(WorkManager::class)` from `@After` teardown (no longer needed).
- **Clean up** unused imports: `WorkManager`, `OneTimeWorkRequest`, `ExistingWorkPolicy`, `mockkStatic`, `unmockkStatic`.

## New pure function tests (replacing WorkManager tests)

```kotlin
@Test fun `computeMissingDataRefreshes requests actuals today when daily actuals missing`()
@Test fun `computeMissingDataRefreshes requests actuals history when past graph day lacks actuals`()
@Test fun `computeMissingDataRefreshes requests today snapshot when forecast exists but no snapshot`()
@Test fun `computeMissingDataRefreshes returns empty when all data present`()
```

No Robolectric, no mocks, no WorkManager — just `DayData` construction + assertions on decision list.

## Net result

- 2 WorkManager mock tests (~130 lines) → 4 pure function tests (~60 lines)
- ~90 lines of duplicated AppWidgetManager mock setup → 1 helper function
- `mockkStatic(WorkManager::class)` + teardown eliminated
- `OneTimeWorkRequest`, `ExistingWorkPolicy` imports eliminated
- Remaining mocks (`AppWidgetManager`) are justified framework boundaries for RemoteViews testing

## Verification

```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewHandlerTest"
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest"
```