# Samsung Widget Frozen — Null PendingResult Crash (260418)

## Session Date
2026-04-18

## Device
Samsung SM-F936U1 (serial RFCT71FR9NT) — identified via `getprop` per AGENTS.md guidance

## Problem
Widget on Samsung device was completely unresponsive to touches. Tapping produced no visible change — appeared frozen.

## Investigation

### Runtime Evidence Collection

1. **Process check**: `pidof com.weatherwidget` returned `NOT_RUNNING` — app process was dead.

2. **Input dispatch**: Samsung HoneySpace launcher WAS detecting taps and firing `ACTION_DAY_CLICK` broadcast intents (~20 broadcasts in 9 seconds between 18:33:18–18:33:27). ActivityManager received and enqueued all of them. But the app couldn't process them because the process was dead.

3. **Crash history**: Found 4 crashes throughout the day:
   - `16:01:18` — crash #3 (the one with a full stack trace in logcat)
   - `17:06:31` — crash #4, system response: `Process com.weatherwidget has crashed too many times, killing! Reason: over process crash limit`
   - After this, Android refused to restart the process for any incoming broadcast.

4. **Memory management**: Samsung nandswapped the dead process at 18:28 (`action:14 adj:999`).

### Root Cause

The crash stack trace at 16:01:18:

```
FATAL EXCEPTION: DefaultDispatcher-worker-2
Process: com.weatherwidget, PID: 2222
java.lang.NullPointerException: Attempt to invoke virtual method
  'void android.content.BroadcastReceiver$PendingResult.finish()'
  on a null object reference
at com.weatherwidget.widget.WeatherWidgetProvider$launchAsync$1
  .invokeSuspend(WeatherWidgetProvider.kt:648)
```

The chain of events:

1. `onUpdate()` calls `goAsync()` on the main thread (line 96) — valid.
2. Starts a coroutine on `Dispatchers.IO` to do DB queries and widget rendering.
3. Inside that coroutine, `schedulePeriodicUpdate()` is called (line 241).
4. `schedulePeriodicUpdate()` calls `launchAsync()` (line 683), which calls `goAsync()` (line 645).
5. But now we're on `Dispatchers.IO`, not the main thread during `onReceive`. `goAsync()` returns null — there is no active `PendingResult` outside the `onReceive` lifecycle.
6. The coroutine's `finally` block calls `pendingResult.finish()` on null → NPE → crash.
7. After 4 such crashes, Android's crash protection kicks in and refuses to restart the process.

The installed APK (`lastUpdateTime=2026-04-18 04:59:59`) had the old code calling `pendingResult.finish()` directly without null safety.

## Fixes Applied

### Fix 1: `onUpdate` finally block — null safety (WeatherWidgetProvider.kt:269)

Changed:
```kotlin
pendingResult.finish()
```
To:
```kotlin
finishPendingResultSafely(pendingResult, "onUpdate")
```

The `onUpdate` method had its own `goAsync()` call at line 96 and called `.finish()` directly in the `finally` block. While `goAsync()` during `onReceive` should never be null, defensive handling matches the pattern used everywhere else.

### Fix 2: `schedulePeriodicUpdate` — remove `goAsync()` from background thread (WeatherWidgetProvider.kt:683)

Changed:
```kotlin
launchAsync {
    val nextWindowStartMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
    WeatherDatabase.getDatabase(context).appLogDao().log(
        "PERIODIC_REFRESH_SCHEDULE",
        "name=$WORK_NAME intervalMinutes=60 policy=keep nextWindowStartMs=$nextWindowStartMs",
        "INFO",
    )
}
```
To:
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    val nextWindowStartMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)
    WeatherDatabase.getDatabase(context).appLogDao().log(
        "PERIODIC_REFRESH_SCHEDULE",
        "name=$WORK_NAME intervalMinutes=60 policy=keep nextWindowStartMs=$nextWindowStartMs",
        "INFO",
    )
}
```

This was the actual root cause. `schedulePeriodicUpdate` is called from `onUpdate`'s IO coroutine, so `goAsync()` (via `launchAsync`) was being called outside the `onReceive` lifecycle. The block only does a DB log insert — it doesn't need `goAsync()` at all. A plain `CoroutineScope(Dispatchers.IO).launch` is sufficient.

### Prior fix by another agent (already in working tree)

The other agent had already added `finishPendingResultSafely` to `launchAsync`'s `finally` block, preventing the crash when `goAsync()` returns null. This was the correct defensive patch but didn't address the root cause of calling `goAsync()` from a background thread.

## Tests Added

Added 2 new test cases to `WeatherWidgetProviderPendingResultTest`:

1. `finishPendingResultSafely finishes non-null pending result` — verifies `finish()` is called on a valid result using mockk.
2. `finishPendingResultSafely swallows exception from finish` — verifies the `try/catch` prevents a crashing `finish()` from propagating.

(Existing test `finishPendingResultSafely ignores null pending result` already covered the null case.)

All 3 tests pass. mockk was used since `PendingResult` is an Android framework class that can't be instantiated without mocking.

## Device Revival Sequence

The Samsung device required three steps to revive:

1. `./gradlew installDebug` — install the fixed APK
2. `adb shell am force-stop com.weatherwidget` — clear the crash counter
3. `adb shell am start-activity -n com.weatherwidget/.ui.SettingsActivity` — bring app out of `stoppedPkg=true` state

Steps 1 and 2 alone were insufficient because `am force-stop` puts the app in Android's stopped-package state (`stoppedPkg=true`), which blocks ALL broadcast delivery — even explicit component-targeted broadcasts. Launching any activity from the app was needed to clear that flag.

Verification after revival:
- Process alive (pid 28613)
- `ACTION_REFRESH` broadcast processed successfully
- Widget update fired, DNS resolved, widget rendered
- `ACTION_SET_VIEW` processed without errors
- No crashes, no exceptions, no ANRs in logs

## Files Changed

1. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
   - Line 269: `pendingResult.finish()` → `finishPendingResultSafely(pendingResult, "onUpdate")`
   - Line 683: `launchAsync { ... }` → `CoroutineScope(Dispatchers.IO).launch { ... }`
2. `app/src/test/java/com/weatherwidget/widget/WeatherWidgetProviderPendingResultTest.kt`
   - Added 2 new test cases (finish non-null, swallow exception)
