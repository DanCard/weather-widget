# Fix Flaky ScreenOnReceiverTest

## Objective
Eliminate flakiness in `ScreenOnReceiverTest` caused by race conditions between Robolectric's virtual main thread and the hardcoded `Dispatchers.IO` used by the background operations in `ScreenOnReceiver`.

## Root Cause Analysis
The flakiness stems from two issues:
1. `ScreenOnReceiver` uses a hardcoded `CoroutineScope(Dispatchers.IO)` for database logging.
2. `ScreenOnReceiverTest` uses `Thread.sleep(20)` in a spin-wait loop (`waitForCondition`) to wait for this background execution. Under CI or high load, this wait loop fails randomly.

## Implementation Steps
1. **`WeatherDatabase.kt`**:
   - Add a `setDatabaseForTesting(db: WeatherDatabase)` function in the companion object. This avoids dirty reflection while allowing tests to inject in-memory instances (which use `.allowMainThreadQueries()`).

2. **`ScreenOnReceiver.kt`**:
   - Introduce an `internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO` property.
   - Use this property when launching the coroutine: `CoroutineScope(ioDispatcher).launch { ... }`.

3. **`ScreenOnReceiverTest.kt`**:
   - In `setUp()`, inject `UnconfinedTestDispatcher()` into the receiver.
   - Inject the `TestDatabase.create()` instance using the new setter from step 1. This ensures Room queries execute synchronously without offloading to a background thread.
   - Replace all `runBlocking` calls with `runTest`.
   - Remove the `waitForCondition` loop and its `Thread.sleep` anti-pattern entirely.
   - Adjust tests to execute and assert sequentially, knowing the coroutines and DB operations will now resolve deterministically.

## Verification
- Run `./gradlew test --tests "*ScreenOnReceiverTest"` repeatedly to ensure tests pass consistently without the 1000ms spin-wait timeout.
