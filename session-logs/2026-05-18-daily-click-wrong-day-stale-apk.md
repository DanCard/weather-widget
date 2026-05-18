# 2026-05-18 — "Friday click shows Saturday" — Stale APK + Test Setup Order

## Summary

User reported that on the emulator, clicking a historical day (e.g. Friday) in the daily forecast view opened the hourly graph centered on a *different* day (Saturday). Clicking Thursday also landed on Saturday — every history-day click resolved to the same wrong target.

Investigation revealed **two cascading causes**, only one of which was a real production bug:

1. **The installed APK was 2 months stale.** The `MIN_HOURLY_OFFSET` clamp in `WidgetStateManager.kt` was already fixed in the working tree (`-24` → `-720`) but had never been rebuilt onto the emulator. The old binary silently clamped every history click's offset back to `-24`, which under WIDE zoom (±12h) centered the graph on Sunday/Saturday no matter what was clicked.
2. **A new regression test (`HistoryClampingRegressionRoboTest`) had an order-dependent `@Before`** that caused its 14-day variant to fail intermittently while the 7-day variant passed.

## Reported behavior

- Emulator widget 25 (9-column daily view), today = Mon 2026-05-18.
- Visible columns: Thu(14) Fri(15) Sat(16) Sun(17) **Today(18)** Tue(19) Wed(20) Thu(21) Fri(22).
- Click Friday (col 2 = May 15) → hourly graph shows Saturday's data.
- Click Thursday (col 1 = May 14) → also shows Saturday.

## Investigation

### Step 1 — Initial wrong hypothesis: click-zone misalignment

First suspicion: the `graph_dayN_zone` overlay FrameLayouts in `widget_weather.xml` were offset by one column relative to the rendered bitmap. Walked through:

- `DailyClickHandlerFactory.setupGraphZoneClickHandlers` — assigns each `DayData.columnIndex` to `zoneIds[colIndex]`. Looked correct.
- `DailyViewLogic.prepareGraphDays` — line 592: `columnIndex = days.size` set during build. Sequential 0,1,2…
- XML layout uses `LinearLayout` with `layout_weight="1"` per zone — visually evenly spaced.

Verified by pulling live logcat at the moment of click:
```
onReceive: ACTION_DAY_CLICK extras: date=2026-05-15 index=2 targetView=TEMPERATURE offset=-70 widget=25
                                  ↑ correct date          ↑ correct offset (Friday noon - now)
clickSource=graph_day:col=1:date=2026-05-15  ← clicked column carried Friday's date
```

So the click-zone wiring was fine. **The intent payload was correct**; the bug was downstream.

### Step 2 — Smoking gun in handleSetView

```
handleSetView: target=TEMPERATURE previousOffset=-24
handleSetView: RESET zoom to WIDE
handleSetView: set hourlyOffset=-70 (was -24)            ← write claimed to happen
handleSetView: FINISHED ... targetOffset=-70 finalStoredOffset=-24  ← read 0ms later: stale
refreshGraphView: ... offset=-24 centerTime=2026-05-17T09:49:19  ← Sunday morning
```

The router logged a successful write of `-70`, then immediately read back `-24`. Verified the on-disk file too:
```
$ adb shell run-as com.weatherwidget cat shared_prefs/widget_state_prefs.xml
<int name="widget_hourly_offset_25" value="-24" />
```

Disk also showed `-24`. The write either didn't happen or was reverted.

### Step 3 — Ruling out the obvious

- Searched all callers of `setHourlyOffset` and `clearWidgetState`. Only `handleCycleZoom` (line 291) and `handleSetView` (line 621) write, plus `clearWidgetState` (line 349). None ran between the set and read per the log.
- Checked Direct Boot (DE/CE storage) variants — only one `widget_state_prefs.xml` exists.
- `SharedPreferencesUtil.getPrefs` is a thin wrapper around `context.getSharedPreferences`, no caching layer.
- `WidgetStateManager` is `@Singleton` for Hilt but `WidgetIntentRouter` (a Kotlin `object`) constructs it directly per call. Each instance gets its own `prefs by lazy`, but Android caches `SharedPreferences` per name within the process — they should all point to the same backing object.
- The `apply()` contract guarantees in-memory map mutation is synchronous; the same-thread read should see the new value.

### Step 4 — Diagnostic logging

User offered: "Feel free to add logging if that is easier than tracing the code." Added temporary `Log.d("WSM_DIAG", ...)` calls inside `getHourlyOffset` and `setHourlyOffset` that capture:
- value written, before/after reads of the same key
- `prefsName` (override resolution)
- `System.identityHashCode(prefs)` and `System.identityHashCode(this)`
- thread name
- short stack trace

Built and installed. After `./gradlew installDebug` finished, the user reported "Seems to be working now". Logs from the freshly-installed APK:

```
setHourlyOffset widget=25 wrote=-94 before=-24 after=-94 prefsHash=56545360 ...
WidgetIntentRouter: handleSetView: FINISHED ... finalStoredOffset=-94
refreshGraphView: ... offset=-94 centerTime=2026-05-14T11:59  ← Thursday noon ✓

setHourlyOffset widget=25 wrote=-70 before=-94 after=-70 prefsHash=56545360 ...
refreshGraphView: ... offset=-70 centerTime=2026-05-15T11:59  ← Friday noon ✓
```

Two important things visible in the working logs:
- `prefsHash=56545360` was **identical** across every call. No multi-instance prefs problem.
- The before/after read inside `setHourlyOffset` proved `apply()` was synchronous as documented.

### Step 5 — The actual cause

`adb shell dumpsys package com.weatherwidget`:
```
versionCode=1 versionName=1.0.0
lastUpdateTime=2026-05-18 10:00:19  ← my installDebug
firstInstallTime=2026-03-20 20:56:49 ← the emulator's binary
```

The emulator had been running a 2-month-old build. `git log` showed many commits since March 20 touching click handling and the hourly graph (`7a59a38 Decompose handleDayClickAction`, `e99f82d Unify daily history actuals blending`, `5b4069a Extend hourly graph lookback to 72h`, …).

`git diff app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` revealed the **actual production fix** as an uncommitted edit:
```diff
- const val MIN_HOURLY_OFFSET = -24   // Allow scrolling to see history (12h default + 12h scroll)
- const val MAX_HOURLY_OFFSET = 336   // 14 days into the future
+ const val MIN_HOURLY_OFFSET = -720  // 30 days lookback
+ const val MAX_HOURLY_OFFSET = 720   // 30 days into the future
```

That's it. The old binary executed `setHourlyOffset(-70)` → `coerceIn(-24, 336)` → `-24`. The "set" log printed the *requested* value (`Log.d("... set hourlyOffset=$targetOffset", …)` interpolates `targetOffset`, not the clamped one written to prefs), so the log looked like the write took effect when it didn't.

After `installDebug` shipped the corrected clamp range, every click landed on the intended day.

Reverted the diagnostic logging in `WidgetStateManager.kt`; nothing else needed in production code.

## Secondary issue — `HistoryClampingRegressionRoboTest` setup ordering

User ran the unit test suite and one test failed:

```
HistoryClampingRegressionRoboTest > handleSetView preserves 14 day lookback offset
  expected:<-336> but was:<0>
HistoryClampingRegressionRoboTest > handleSetView preserves 7 day lookback offset PASSED
```

Test (new, untracked file added alongside the `MIN_HOURLY_OFFSET` fix):
```kotlin
@Before
fun setup() {
    stateManager = WidgetStateManager(context)
    stateManager.clearWidgetState(testWidgetId)
    WeatherDatabase.setIsTesting(true)
}
```

JUnit 4.11+ default `MethodSorter.NAME_ASCENDING` runs methods in lexical order. `"14"` precedes `"7"` because `'1'(49) < '7'(55)`. So the 14-day variant runs first.

When the 14-day test runs first, the order of operations is:
1. `stateManager = WidgetStateManager(context)` — instance constructed, `prefs` field is `by lazy` (not yet initialized).
2. `stateManager.clearWidgetState(testWidgetId)` — first prefs access → lazy init. At this point `WeatherDatabase.isTestingMode()` is still `false` (initial JVM state). `SharedPreferencesUtil.getPrefsName("widget_state_prefs")` returns `"widget_state_prefs"` (no `_test_default` suffix).
3. `WeatherDatabase.setIsTesting(true)` — flag flipped, but too late to influence the already-initialized outer `prefs`.
4. Test body calls `WidgetIntentRouter.handleSetView` → it constructs a **new** `WidgetStateManager(context)` internally → its `prefs` lazy-inits *now*, with `isTesting=true`, so it picks `"widget_state_prefs_test_default"`.
5. `setHourlyOffset(-336)` writes to `widget_state_prefs_test_default.xml`.
6. Test reads via the outer `stateManager` (still pointing at `widget_state_prefs.xml`) → returns the default `0` → assertion fails.

The 7-day test passes only because by then `isTesting=true` was already set from test 1, so its outer-stateManager prefs init also picks `_test_default`, matching the inner writer.

### Fix

Move the testing-mode flag **before** the `WidgetStateManager` construction so the lazy `prefs` field binds to the test file from the very first access:

```kotlin
@Before
fun setup() {
    WeatherDatabase.setIsTesting(true)
    stateManager = WidgetStateManager(context)
    stateManager.clearWidgetState(testWidgetId)
}
```

Both tests now pass:
```
HistoryClampingRegressionRoboTest > handleSetView preserves 14 day lookback offset PASSED
HistoryClampingRegressionRoboTest > handleSetView preserves 7 day lookback offset without clamping PASSED
```

## Lessons

- **When source-code reasoning doesn't match observed symptoms, check the installed binary's age.** `adb shell dumpsys package <pkg> | grep -E "lastUpdateTime|firstInstallTime"` is a one-liner that would have skipped most of the investigation. Two months is enough drift that "trace the code" was reasoning about a *different program* than the one running.
- **`coerceIn(min, max)` is silent.** A clamped write leaves no warning in logs unless you explicitly compare before/after. The "set hourlyOffset=$targetOffset" line interpolated the *requested* argument, not what landed in prefs — so the log lied. Worth logging clamped values (or asserting on the post-write read) in any setter with bounded ranges.
- **Lazy fields + statically-toggled flags = order-dependent tests.** `WidgetStateManager.prefs` is `by lazy`, and `SharedPreferencesUtil.getPrefsName` consults `WeatherDatabase.isTestingMode()` at lazy-init time. Any `@Before` that flips the flag *after* touching the lazy field will pick the wrong file. The safer pattern (and what `DailyHistoryClickIntegrationTest` already uses) is `WidgetStateManager.setPrefsNameOverrideForTesting(DEFAULT_TEST_PREFS_NAME)` — explicit override, no dependence on global flag init order.
- **JUnit default method order is lexical.** `"14"` < `"7"`. Tests that mutate static state in `@Before` and rely on test-order isolation are quietly order-dependent; if both tests had been pure they'd both have failed equally, making the bug easier to spot.

## Files touched this session

- `app/src/test/java/com/weatherwidget/widget/handlers/HistoryClampingRegressionRoboTest.kt` — reordered `@Before` to set testing mode before constructing `WidgetStateManager`.
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` — temporarily added `WSM_DIAG` log lines for the investigation; reverted before commit.

Already-uncommitted changes in working tree that constituted the actual production fix:
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` — `MIN_HOURLY_OFFSET = -720`, `MAX_HOURLY_OFFSET = 720`.
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` — `hasHourlyDataForDate` falls back to observations for past dates.
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` — logging-only enhancements to `handleSetView`.
- `app/src/test/java/com/weatherwidget/widget/handlers/DayClickHelperTest.kt`, `DailyHistoryClickIntentRoboTest.kt`, `DailyHistoryClickIntegrationTest.kt`, `HistoryClampingRegressionRoboTest.kt` — companion regression coverage.
