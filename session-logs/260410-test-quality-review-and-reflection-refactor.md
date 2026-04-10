# Test Quality Review & Reflection Refactor

**Date:** 2026-04-10
**Scope:** Review of test suite patterns (mocks, sleeps, reflection) and one refactoring change

---

## Prompt 1: Mock usage review

> Many tests use mocks. Is that good or bad? Should tests be refactored not to use mocks?

### Investigation

- Searched all `app/src/test/` files for MockK imports
- Counted total test files: **114**
- Files using MockK: **35** (~31%)
- Files using Robolectric: **51**
- Pure tests (no mocks, no Robolectric): **~79** (69%)

### Findings

Three categories of mock usage:

1. **Android platform mocks (Canvas, Paint, Bitmap, Context, Resources, DisplayMetrics)** — in graph renderer tests. No alternative for JVM tests. Justified.

2. **DAO/API mocks in repository tests** — `WeatherRepositoryTest` mocks 13+ dependencies (DAOs, APIs, SharedPreferences). Tests verify repository logic (rate limiting, fallback, deduplication), not SQL correctness. DAO tests separately test SQL with real Room via Robolectric. Justified.

3. **System service mocks (WorkManager, PowerManager)** — in `UIUpdateReceiverTest`. Unavoidable for scheduling logic. Justified.

### Conclusion

No refactoring needed. The project's "prefer pure function extraction" philosophy is working well — 69% of tests are pure. Mocks are concentrated at Android platform boundaries where they belong.

Noted that `WeatherRepositoryTest` setup with 13+ mocked dependencies could become a maintenance burden if it grows much further, but it's already being split into focused test classes (`WeatherRepositoryRateLimitIntegrationTest`, `WeatherRepositoryPoiTest`, etc.).

---

## Prompt 2: Sleep usage review

> Some tests use sleep. Should they be refactored?

### Investigation (first pass — missed androidTest)

- Searched `app/src/test/` for `Thread.sleep`, `delay(`, `sleep(`
- Found zero matches in active `.kt` files (only a `.patch` artifact)
- Also searched for coroutine test timing APIs (`advanceTimeBy`, `advanceUntilIdle`) — none found
- Concluded tests are sleep-free

### User correction

User ran `rgrep Thread.sleep` from `app/src/` and found 4 hits in `androidTest/`:

```
androidTest/.../PrecipTouchRoutingInstrumentedTest.kt:            Thread.sleep(50)
androidTest/.../CloudCoverTouchRoutingInstrumentedTest.kt:         Thread.sleep(50)
androidTest/.../DailyMainColumnVsBottomIconClickTargetIntegrationTest.kt: Thread.sleep(100)
androidTest/.../TemperatureTouchRoutingInstrumentedTest.kt:        Thread.sleep(50)
```

### Findings

All 4 uses follow the same pattern — a **bounded poll loop**:

```kotlin
private fun waitForViewMode(expected: ViewMode) {
    val deadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < deadline) {
        if (stateManager.getViewMode(appWidgetId) == expected) return
        Thread.sleep(50)
    }
    assertEquals(expected, stateManager.getViewMode(appWidgetId))
}
```

### Conclusion

No refactoring needed. These are bounded polls (not blind sleeps), with 50-100ms intervals inside a 5-second deadline. The test exits as soon as the condition is met. Alternatives like `IdlingResource` don't apply since these tests aren't using Espresso's lifecycle. Adding callbacks/flows to `WidgetStateManager` just for tests would add prod complexity for marginal improvement.

---

## Prompt 3: Reflection usage review

> Should tests that use reflection be refactored?

### Investigation

- Searched `app/src/test/` and `app/src/androidTest/` for reflection patterns (`getDeclaredField`, `getDeclaredMethod`, `setAccessible`, `java.lang.reflect.`)
- Found **1 file**: `WeatherObservationsActivityRobolectricTest.kt`
- Two call sites, both in `onActivity` blocks, calling private methods `loadObservations()` and `loadFetchLogs()`

### Code before refactor

```kotlin
// In test (two locations):
scenario.onActivity {
    it.ioDispatcher = testDispatcher
    val methodObs = it.javaClass.getDeclaredMethod("loadObservations")
    methodObs.isAccessible = true
    methodObs.invoke(it)

    val methodLogs = it.javaClass.getDeclaredMethod("loadFetchLogs")
    methodLogs.isAccessible = true
    methodLogs.invoke(it)
}
```

### Problems

1. **Fragile** — method rename compiles fine but fails at runtime with `NoSuchMethodException`
2. **Opaque** — string-based method names obscure what's tested
3. **Unnecessary** — the test already reaches into the activity to swap `ioDispatcher`

### Recommendation & User Approval

Recommended changing `private fun` to `@VisibleForTesting internal fun` on both methods. User approved.

### Changes Made

**`WeatherObservationsActivity.kt`** (production):
- Added `import androidx.annotation.VisibleForTesting`
- `private fun loadObservations()` → `@VisibleForTesting internal fun loadObservations()`
- `private fun loadFetchLogs()` → `@VisibleForTesting internal fun loadFetchLogs()`

**`WeatherObservationsActivityRobolectricTest.kt`** (test):
- Replaced both reflection blocks with direct calls:
  ```kotlin
  scenario.onActivity {
      it.ioDispatcher = testDispatcher
      it.loadObservations()
      it.loadFetchLogs()
  }
  ```
- Removed ~14 lines of reflection boilerplate

### Verification

- `./gradlew compileDebugKotlin` — **BUILD SUCCESSFUL** (production code compiles)
- `./gradlew testDebugUnitTest --tests "...WeatherObservationsActivityRobolectricTest"` — build failed on **unrelated** pre-existing error in `TemperatureGraphStyleTest.kt` (missing `override` modifiers), not caused by this change
- Confirmed zero reflection patterns remain in the test file
