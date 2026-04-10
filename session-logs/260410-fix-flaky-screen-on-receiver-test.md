# Session Log: Fix Flaky ScreenOnReceiverTest

## Objective
Fix flaky test `onReceive with POWER_CONNECTED writes enqueued app log` in `ScreenOnReceiverTest` that fails intermittently when run via `scripts/staggered-tests.sh` but passes individually or via `scripts/unit-tests.sh`.

## Initial Problem
The test report showed:
```
java.lang.AssertionError: Expected POWER_CONNECTED_EVENT log entry
    at ScreenOnReceiverTest$onReceive with POWER_CONNECTED writes enqueued app log$1.invokeSuspend(ScreenOnReceiverTest.kt:137)
```

Robolectric suppressed exception hinted:
```
Main looper has queued unexecuted runnables. This might be the cause of the test failure.
You might need a shadowOf(Looper.getMainLooper()).idle() call.
```

## Prompts & Tasks
1. **Prompt:** "This test seems flaky: file:///...ScreenOnReceiverTest/onReceive-with-POWER_CONNECTED-writes-enqueued-app-log.html"
2. **Action:** HTML report file didn't exist on disk. Found the test file (`ScreenOnReceiverTest.kt`) and production code (`ScreenOnReceiver.kt`). Read both. Launched Explore agent to investigate `TestDatabase.create()`, `AppLogDao`, and `WeatherDatabase.setDatabaseForTesting()`.
3. **Prompt:** User pasted full stack trace from the test report (shown above).
4. **Analysis:** Identified that Room suspend DAO methods dispatch to an internal background thread pool regardless of the coroutine's dispatcher. `allowMainThreadQueries()` only affects synchronous (non-suspend) DAO methods. The `UnconfinedTestDispatcher` injected for `ioDispatcher` controlled the coroutine launch but not Room's internal executor. Initial plan proposed adding direct executors to `TestDatabase.kt`.
5. **Action:** Applied fix to `TestDatabase.kt` — added `setTransactionExecutor(directExecutor)` and `setQueryExecutor(directExecutor)`. Ran `ScreenOnReceiverTest` — all 7 tests passed. Attempted to loop the specific flaky test 4 more times for stability check.
6. **Prompt:** "It is flaky when run as a part of scripts/staggered-tests.sh. It is fine when run individually or as part of scripts/unit-tests.sh"
7. **Action:** Read both `staggered-tests.sh` and `unit-tests.sh` to understand the difference. `staggered-tests.sh` runs unit tests AND emulator tests in parallel, creating heavy CPU load. This explained why the race was only exposed under `staggered-tests.sh` — CPU contention slowed Room's background thread pool just enough to flip the race from "passing by accident" to failing.
8. **Action:** Updated plan with the load-dependent analysis. Ran full unit test suite via `scripts/unit-tests.sh` — all 699 tests passed (391 short, 191 medium, 117 long), zero regressions.

## Investigation Details

### Execution Flow in Production Code
`ScreenOnReceiver.handlePowerConnected()` calls `logPowerConnectedEvent()`, which:
1. Calls `goAsync()` to get a `PendingResult`
2. Launches a fire-and-forget coroutine on `ioDispatcher`
3. Inside the coroutine: `WeatherDatabase.getDatabase(context).appLogDao().log(tag, message)` — a Room suspend `insert()` call
4. Calls `pendingResult?.finish()` in the finally block

### Test Setup
- `ioDispatcher` overridden with `UnconfinedTestDispatcher` — makes coroutine launch eagerly
- `TestDatabase.create()` used `allowMainThreadQueries()` but **no custom executor**
- Test immediately asserts `powerConnectedLogCount() > beforeCount` after `onReceive()` returns

### Root Cause: Room Suspend Function Dispatch
Room has two execution paths for DAO methods:
- **Synchronous (non-suspend):** Respects `allowMainThreadQueries()`, runs inline on calling thread
- **Suspend:** Always dispatches to Room's internal executor pool (`queryExecutor`/`transactionExecutor`), **bypassing** the main-thread check

So the chain was:
1. `UnconfinedTestDispatcher` starts coroutine eagerly on current thread
2. Coroutine calls `appLogDao().log()` → `insert()` (suspend)
3. Room's generated `AppLogDao_Impl.insert()` calls `performSuspending(__db, ...)` which dispatches to Room's **default background thread pool**
4. Current thread returns from `launch` — coroutine is now suspended waiting for Room's executor
5. `onReceive()` returns to the test
6. Test asserts log count — **insert hasn't completed yet**

### Why Only `staggered-tests.sh` Exposed It
`staggered-tests.sh` runs unit tests AND emulator tests in parallel (`"$UNIT_SCRIPT" --single-invocation` in background + `"$EMULATOR_SCRIPT"` streaming). Under normal load (individual run, `unit-tests.sh`), Room's background thread pool executed fast enough that the insert completed before the assertion — a classic "passing by accident" race condition. The CPU contention from parallel processes slowed Room's executor just enough to flip the race.

## Fix

### `app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt`
Added direct (same-thread) executors to force Room suspend functions to execute synchronously:

```kotlin
val directExecutor = Executor { it.run() }
return Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
    .allowMainThreadQueries()
    .setTransactionExecutor(directExecutor)   // NEW
    .setQueryExecutor(directExecutor)          // NEW
    .build()
```

`Executor { it.run() }` is a direct executor — it runs the `Runnable` immediately on the calling thread instead of dispatching to a thread pool. This makes Room's `performSuspending` execute inline, so the coroutine completes fully before `launch` returns.

No changes were needed to `ScreenOnReceiverTest.kt` or any other test file.

## Verification
- `ScreenOnReceiverTest`: All 7 tests pass
- Full unit test suite (`scripts/unit-tests.sh`): All 699 tests pass (391 short, 191 medium, 117 long), zero regressions

## Key Takeaway
`allowMainThreadQueries()` is **not sufficient** for deterministic Room behavior in tests that use suspend DAO methods. You must also set `setTransactionExecutor` and `setQueryExecutor` to a direct executor. Without this, suspend DAO calls dispatch to an uncontrolled background thread pool that races against test assertions — a race that only surfaces under CPU contention.

## Files Changed
- `app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt` — added direct executors
- `plans/fix-flaky-screen-on-receiver-test.md` — implementation plan
