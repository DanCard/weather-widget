# Plan: Two-Phase No-Hourly Day-Click Flow with Desktop Integration Tests

## Context

Clicking a far-future daily column with no hourly data currently shows a single "no hourly" banner
that auto-dismisses, with no follow-up after the refresh. The user wants parity with Android's
two-phase flow:

1. **Phase 1 (on click):** Message "Hourly data missing for Tue Jul 7 — a refresh will be triggered"
2. **Refresh triggered** for that day's horizon
3. **Phase 2 (after refresh):** Message "Results of refresh: Hourly data now available for Tue Jul 7"
   OR "Results of refresh: No hourly data for Tue Jul 7 — data ends Mon Jul 6 at 4 PM"

## Implementation

### 1. `shared/…/NoHourlyChecker.kt` — two new pure message builders

```kotlin
fun buildPendingMessage(dayLabel: String): String =
    "Hourly data missing for $dayLabel\nA refresh will be triggered"

fun buildResultMessage(dayLabel: String, hasHourly: Boolean, endLabel: String?): String =
    if (hasHourly) "Results of refresh:\nHourly data now available for $dayLabel"
    else if (endLabel != null) "Results of refresh:\nNo hourly data for $dayLabel — data ends $endLabel"
    else "Results of refresh:\nNo hourly data for $dayLabel"
```

Also add tests for both in `NoHourlyCheckerTest.kt`.

### 2. `desktop/…/Main.kt` — new `onNeedHourlyRefresh` parameter + wiring

**Add to `WidgetPopup` signature** (default `{ _, _ -> }` keeps all existing callers and tests working):
```kotlin
onNeedHourlyRefresh: (days: Int, onComplete: (List<HourlyForecast>) -> Unit) -> Unit = { _, _ -> },
```

**Update `handleDayClick` no-data branch** (replaces current single-message + `onNeedForecastExtension` call):
```kotlin
val dayLabel = NoHourlyChecker.formatDayLabel(clickedDate)
noHourlyMessage = NoHourlyChecker.buildPendingMessage(dayLabel)
val targetDays = ForecastHorizon.daysToCover(LocalDate.now(), clickedDate)
onNeedHourlyRefresh(targetDays) { newHourly ->
    val srcIds = config.visibleSources.toSet()
    val hasData = NoHourlyChecker.hasHourlyForDay(newHourly, clickedDate, srcIds)
    val endLabel = if (!hasData) NoHourlyChecker.lastHourlyEndLabel(newHourly, srcIds) else null
    noHourlyMessage = NoHourlyChecker.buildResultMessage(dayLabel, hasData, endLabel)
}
```

**Add `onNeedHourlyRefresh` lambda in `runApp()`** (alongside existing `onNeedForecastExtension`):
```kotlin
val onNeedHourlyRefresh: (Int, (List<HourlyForecast>) -> Unit) -> Unit = remember(repository) {
    fn@{ targetDays, onComplete ->
        val repo = repository ?: run { onComplete(forecast?.hourly ?: emptyList()); return@fn }
        uiScope.launch {
            try {
                if (repo.ensureForecastDays(targetDays)) {
                    val newForecast = repo.loadCached()
                    forecast = newForecast
                    onComplete(newForecast?.hourly ?: emptyList())
                } else {
                    onComplete(forecast?.hourly ?: emptyList())  // no new data
                }
            } catch (e: Exception) {
                Log.e(TAG, "No-hourly refresh failed: ${e.message}")
                onComplete(forecast?.hourly ?: emptyList())
            }
        }
    }
}
```

**Wire into `WidgetPopup` call** (line ~697): add `onNeedHourlyRefresh = onNeedHourlyRefresh`.

### 3. `DailyForecastTextMode` in `Main.kt` — add testTag per day column

Add `Modifier.testTag("day_tab_${day.date}")` to each day's `Column` clickable (line ~1384).
This lets tests find and click a specific date's column without fragile coordinate clicks.

### 4. New test: `DesktopNoHourlyDayClickTest.kt`

Uses `createComposeRule()` matching `DesktopUiTest.kt` pattern. Forces text mode by wrapping
`WidgetPopup` in `Modifier.size(300.dp, 80.dp)` (small height → `DesktopDailyForecastModel.dimensions`
returns `useGraph=false` → `DailyForecastTextMode` renders → day columns are semantic nodes).

Stub forecast: 8 daily entries for today+0…today+7, but NO hourly entries for Jul 7 specifically
(only near-term hourly for today). `stubConfig.visibleSources = listOf("NWS")`.

**Test 1: `pendingMessageShownOnNoHourlyDayClick`**
- `var capturedComplete: ((List<HourlyForecast>) -> Unit)? = null`
- `onNeedHourlyRefresh = { _, cb -> capturedComplete = cb }`
- `performClick()` on `day_tab_2026-07-07`
- `waitForIdle()`
- Assert `no_hourly_message` exists with text containing "Hourly data missing" and "Tue Jul 7"
- Assert `onUpdateConfig` NOT called (stayed on DAILY)
- Assert `capturedComplete != null` (callback was registered)

**Test 2: `resultMessageShownAfterRefreshDataArrived`**
- Same setup; after click and pending message verified:
- `composeTestRule.runOnIdle { capturedComplete!!(hourlyForTuesdayList) }`
- `waitForIdle()`
- Assert `no_hourly_message` now contains "Results of refresh" and "now available"

**Test 3: `resultMessageShownAfterRefreshStillNoData`**
- Same setup; after click:
- `composeTestRule.runOnIdle { capturedComplete!!(emptyList()) }`
- `waitForIdle()`
- Assert `no_hourly_message` contains "Results of refresh" and "No hourly data"

## Files to modify

| File | Change |
|------|--------|
| `shared/…/NoHourlyChecker.kt` | Add `buildPendingMessage`, `buildResultMessage` |
| `shared/.../NoHourlyCheckerTest.kt` | Add tests for both new functions |
| `desktop/…/Main.kt` | New `onNeedHourlyRefresh` param + lambda in runApp + testTag on day columns + updated handleDayClick |
| `desktop/…/DesktopNoHourlyDayClickTest.kt` | New file: 3 Compose tests |

## Verification

1. `./gradlew :shared:test --tests "*.NoHourlyCheckerTest"`
2. `./gradlew :desktop:test --tests "*.DesktopNoHourlyDayClickTest"`
3. `scripts/buildStart-desktop.sh` → click Tue Jul 7 → see pending banner → wait for refresh → see result banner
