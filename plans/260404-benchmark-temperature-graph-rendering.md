# Reduce Startup Graph Refresh Delay + Add Logging

## Context
Benchmarking showed the full pipeline (obs query + IDW blend + render) completes in ~104ms. The
`STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 900L` constant in `TemperatureViewHandler` was set
conservatively. With the pipeline measured at ~104ms, 200ms gives Phase 1 ample time to deliver
its RemoteViews before Phase 2 fires. The perceived "several seconds" before the actual temperature
line appears is this delay.

### How to know if 200ms is causing an issue
Two failure modes, each with a distinct log signal:

| Failure | Symptom | Log signal |
|---|---|---|
| Phase 2 cancelled | Actuals never appear on that startup — widget stays forecast-only | `STARTUP_PHASE2 status=cancelled` in DB |
| Visible flicker | Widget blinks between forecast and actual line rapidly | `phase1ToPhase2Ms` < ~150ms in logcat — Phase 2 fired before Phase 1 could settle on screen |

At 900ms neither is common because the window is wide. At 200ms, any user tap or competing
update within that 200ms window will trigger a cancellation. Monitor `cancelled` rate over a
few days. If you see it frequently, bump the delay up to 400ms.

## Changes

### 1. Reduce delay constant
**File:** `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`, line 156

```kotlin
// Before
private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 900L
// After
private const val STARTUP_FULL_GRAPH_REFRESH_DELAY_MS = 200L
```

### 2. Pass `handlerStartMs` to `scheduleStartupFullGraphRefresh`
Currently `scheduleStartupFullGraphRefresh` is called at line 692-693 after Phase 1 rendering
completes. Pass `handlerStartMs` (already in scope at line 695) so Phase 2 can log how much
time elapsed since Phase 1 began.

Call site change (line 692-693):
```kotlin
if (deferStartupGraphActuals) {
    scheduleStartupFullGraphRefresh(context, appWidgetId, handlerStartMs)
}
```

### 3. Update `scheduleStartupFullGraphRefresh` with logging
**Lines 802–819** — add DB + logcat logging:

```kotlin
private fun scheduleStartupFullGraphRefresh(
    context: Context,
    appWidgetId: Int,
    phase1StartMs: Long,
) {
    val token = SystemClock.elapsedRealtimeNanos()
    val scheduledAt = SystemClock.elapsedRealtime()
    fullGraphRefreshTokens[appWidgetId] = token
    val phase1TotalMs = scheduledAt - phase1StartMs   // time from Phase 1 start to when Phase 2 is queued
    asyncScope.launch {
        WeatherDatabase.getDatabase(context).appLogDao()
            .log("STARTUP_PHASE2", "widget=$appWidgetId status=scheduled delayMs=$STARTUP_FULL_GRAPH_REFRESH_DELAY_MS phase1TotalMs=$phase1TotalMs")
        delay(STARTUP_FULL_GRAPH_REFRESH_DELAY_MS)
        val phase1ToPhase2Ms = SystemClock.elapsedRealtime() - phase1StartMs
        if (fullGraphRefreshTokens[appWidgetId] != token) {
            Log.d(TAG, "STARTUP_PHASE2 widget=$appWidgetId status=cancelled phase1ToPhase2Ms=${phase1ToPhase2Ms}ms")
            WeatherDatabase.getDatabase(context).appLogDao()
                .log("STARTUP_PHASE2", "widget=$appWidgetId status=cancelled phase1ToPhase2Ms=${phase1ToPhase2Ms}ms")
            // Toast so the developer sees it immediately without querying the DB
            context.sendBroadcast(Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_SHOW_TOAST
                putExtra(WeatherWidgetProvider.EXTRA_TOAST_MESSAGE, "⚠️ Phase2 cancelled (${phase1ToPhase2Ms}ms)")
            })
            return@launch
        }
        Log.d(TAG, "STARTUP_PHASE2 widget=$appWidgetId status=fired phase1ToPhase2Ms=${phase1ToPhase2Ms}ms")
        WeatherDatabase.getDatabase(context).appLogDao()
            .log("STARTUP_PHASE2", "widget=$appWidgetId status=fired phase1ToPhase2Ms=${phase1ToPhase2Ms}ms")
        val refreshIntent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WeatherWidgetProvider.EXTRA_UI_ONLY, true)
        }
        context.sendBroadcast(refreshIntent)
    }
}
```

`phase1ToPhase2Ms` captures the total wall-clock gap from Phase 1 start to Phase 2 firing. If this
is less than ~150ms, Phase 2 may have fired before Phase 1's RemoteViews reached the screen —
that's a signal to increase the delay slightly.

## Key File
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`
  - Line 156: constant 900L → 200L
  - Line 692-693: pass `handlerStartMs` to `scheduleStartupFullGraphRefresh`
  - Lines 802–819: add `phase1StartMs` param + logging

## Verification
1. Build + install: `./gradlew installDebug`
2. Force a cold start: kill the app process, then lock/unlock the screen
3. Logcat: `adb logcat -s TemperatureViewHandler:D` — confirm Phase 1 has `startupFastPath=true`,
   then see `STARTUP_PHASE2 status=fired` ~200ms later
4. Confirm actual temperature line appears noticeably sooner on screen
5. To check for cancellations after a few days of use, query DB:
   ```sql
   SELECT message, datetime(timestamp/1000, 'unixepoch', 'localtime') 
   FROM app_logs WHERE tag='STARTUP_PHASE2' ORDER BY timestamp DESC LIMIT 20;
   ```
