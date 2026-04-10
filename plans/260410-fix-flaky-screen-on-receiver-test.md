# Fix Flaky `ScreenOnReceiverTest` — POWER_CONNECTED App Log Assertion

## Context

The test `onReceive with POWER_CONNECTED writes enqueued app log` fails intermittently when run via `staggered-tests.sh` (which runs unit + emulator tests in parallel), but passes when run individually or via `unit-tests.sh`.

**Root cause:** `logPowerConnectedEvent()` launches a fire-and-forget coroutine that calls Room's suspend `insert()`. Room redispatches suspend DAO calls to its internal background thread pool, regardless of the coroutine's dispatcher. Under heavy CPU load from staggered parallel tests, Room's background executor is delayed, and the test assertion fires before the insert completes.

`allowMainThreadQueries()` only affects synchronous DAO methods — it has no effect on suspend functions.

## Fix

**File:** `app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt`

Add direct (same-thread) executors to `TestDatabase.create()` so Room's suspend functions execute synchronously:

```kotlin
val directExecutor = Executor { it.run() }
return Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
    .allowMainThreadQueries()
    .setTransactionExecutor(directExecutor)
    .setQueryExecutor(directExecutor)
    .build()
```

This ensures all Room operations — inserts, queries, transactions — run on the calling thread. No changes needed to any test files.

## Files Modified

- `app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt` — add direct executors

## Verification

```bash
# Run the full ScreenOnReceiverTest suite
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.ScreenOnReceiverTest"

# Run via staggered-tests to confirm fix under load
./scripts/staggered-tests.sh

# Run all unit tests to check for regressions (TestDatabase is shared)
./scripts/unit-tests.sh
```
