# Desktop "Today" triple-bar — share the 24h-prior snapshot selection

## Context

The daily view draws a **triple bar** for the *today* column: a left **snapshot** bar
(what was forecast ~24h ago), a center **observed** (red thermostat) bar, and a right
**live forecast** bar. The user reports the desktop left bar doesn't behave like Android
and asks specifically: "the left bar is supposed to be the forecast 24 hours prior — is
desktop doing that?"

**It is not.** The rendering (three bars at offset X positions) is present on both
platforms, but the *snapshot selection* diverges:

- **Android** — `app/.../widget/handlers/DailyViewLogic.kt:392-400`: from the
  source-matched snapshot candidates (both temps present), pick the most-recent one whose
  `fetchedAt` is **older than `now - 24h`**, falling back to the *earliest* available.
  This is genuinely "the forecast as of ~24 hours ago."
- **Desktop** — `desktop/.../DesktopDailyForecastModel.kt:137-142`: picks the
  **most-recent** snapshot, with no 24h cutoff. So today's left bar shows a near-live
  forecast (often visually identical to the right forecast bar), not a 24h-prior one.

Goal: extract the 24h-prior selection into one shared pure function and call it from both
platforms, so today's left bar matches and the logic is no longer duplicated/divergent.
(Scope confirmed with user: fix selection + share it; not touching icon/segmentation
visuals or the broader triple-line dedup in this change.)

## Important nuance

Desktop currently computes a **single** `snapshot` (most-recent) used for *both* the
past-day forecast overlay (`forecastHigh = snapshot?.highTemp`, which legitimately wants
most-recent and matches Android's past-day logic at `DailyViewLogic.kt:380-386`) **and**
today's left bar. The fix must **split** these: keep most-recent for past days, use the
new 24h-prior selector only for today's `snapshotHigh`/`snapshotLow`.

## Changes

### 1. New shared selector (the reused logic)

`shared/src/main/kotlin/com/weatherwidget/shared/util/DailySnapshotSelector.kt` — pure
Kotlin, no platform deps (sibling of `DailyDayValueResolver.kt`). Generic over the
candidate type via accessor lambdas so both `ForecastEntity` (Android) and
`DailyForecastSnapshot` (desktop, fields `fetchedAt`/`highTemp`/`lowTemp` already present)
can use it:

```kotlin
object DailySnapshotSelector {
    const val PRIOR_WINDOW_HOURS = 24L

    /** Forecast "as of ~24h ago": most-recent candidate older than the cutoff,
     *  else the earliest available. Mirrors Android DailyViewLogic today-snapshot logic. */
    fun <T> selectPriorDaySnapshot(
        candidates: List<T>,
        nowMillis: Long,
        fetchedAt: (T) -> Long,
    ): T? {
        if (candidates.isEmpty()) return null
        val cutoff = nowMillis - PRIOR_WINDOW_HOURS * 3_600_000L
        return candidates.filter { fetchedAt(it) < cutoff }.maxByOrNull(fetchedAt)
            ?: candidates.minByOrNull(fetchedAt)
    }
}
```

Callers do their own validity pre-filtering (both-temps / source) before calling, matching
how each side already filters.

### 2. Android — delegate to the shared selector

In `DailyViewLogic.kt` (today branch, ~392-400) replace the inline `yesterdaySameTime`
filter with:

```kotlin
val snapshotCandidates = forecasts
    .filter { it.source == displaySource.id }
    .filter { it.highTemp != null && it.lowTemp != null }
val nowMillis = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
val snapshot = DailySnapshotSelector.selectPriorDaySnapshot(
    snapshotCandidates, nowMillis, { it.fetchedAt },
)
```

Behavior is identical to today's code (this is the reference implementation), so it's a
pure refactor on the Android side — its purpose is to make the two platforms share one
definition.

### 3. Desktop — split selection, use the shared selector for today

In `DesktopDailyForecastModel.buildDay` (`DesktopDailyForecastModel.kt:124-231`):

- Keep the existing most-recent `snapshot` (lines 137-142) for the **past-day** overlay.
- Add a today-only prior-day snapshot:

```kotlin
val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
val todaySnapshot = com.weatherwidget.shared.util.DailySnapshotSelector.selectPriorDaySnapshot(
    snapshots.filter { it.highTemp != null && it.lowTemp != null },
    nowMillis, { it.fetchedAt },
)
val displaySnapshot = if (isToday) todaySnapshot else snapshot
```

- Drive the returned `snapshot`/`snapshotHigh`/`snapshotLow` (lines 215, 221-222) from
  `displaySnapshot` so today's left bar reflects the 24h-prior forecast while past days
  keep the most-recent snapshot overlay.

## Tests

- **Shared (primary):** `shared/src/test/.../DailySnapshotSelectorTest.kt` (plain JUnit).
  Cover: prefers most-recent-older-than-24h; falls back to earliest when none older;
  empty → null; single-candidate cases. This is the regression guard for the shared logic
  both platforms now depend on (same spirit as `LocationMatchContract`).
- **Android:** run existing daily-view tests (`DailyForecastGraphRendererRobolectricTest`,
  any `DailyViewLogic` test) to confirm the refactor is behavior-preserving:
  `./gradlew testDebugUnitTest --tests "*DailyForecastGraphRenderer*" --tests "*DailyViewLogic*"`.

## Verification (end-to-end)

1. Build/run desktop with the repo restart script (`scripts/buildStart.sh`), open the
   daily view, screenshot today's column.
2. Build Android (`./gradlew installDebug`) and view the daily widget on the emulator
   (per CLAUDE.md: `adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`,
   read the JPG). Compare today's left bar value against desktop and against the live
   forecast bar — the left bar should now reflect a ~24h-old forecast on both.
3. **Data caveat to check:** the left bar only *visibly* differs if the DB actually holds
   daily snapshot batches older than 24h. Confirm with `python3 scripts/backup_databases.py`
   then query `forecasts` for distinct `fetchedAt` batches on today's `targetDate`
   (remember `targetDate` is UTC midnight — query without `localtime`). If only fresh
   batches exist (e.g., daemon recently started), both platforms correctly fall back to
   the earliest available; note this rather than treating it as a failure.
