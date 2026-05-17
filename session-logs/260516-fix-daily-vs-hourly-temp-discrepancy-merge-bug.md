# Session Log: Fix Daily View vs Hourly Graph Temperature Discrepancy (Merge Bug)

## Date: Saturday, May 16, 2026 (evening)

## Objective
The earlier "unify temperature blending" fix (commits `910dfba`, `9a6a895`, `13619cc`) was reported as not actually fixing the discrepancy on emulator-5556 — Daily View still showed `73.5°` for today's high while Hourly Graph showed `73.1°`. Find why and fix it. Gemini drafted a second-attempt plan (`plans/260516-Investigate-Temp-Discrepancy.md`) but it had not been implemented.

---

## User Prompts
1. "On emulator: daily forecast view: it says high temp for today 73.5. On hourly graph for actuals temp, the high temp says 73.1. Why? Review logs and logging if that helps. Gemini tried to fix it, fix has been checked in, but the fix didn't fix. Gemini's second attempt to fix which hasn't been implemented is at: 260516-Investigate-Temp-Discrepancy.md"
2. "What is meant by persisted?"
3. "Should always be blended, never merge. Always prefer primary. Never use secondary."
4. "Should we add to do the plan an integration test that initially fails?"
5. "What do you think about a robolectric test or emulator test?" (chose Robolectric)
6. "write a detailed session log to session-logs/ dir"

---

## Investigation Findings

The previous "fix" *did* successfully wire `ObservationRepository.getDailyActualsWithLiveToday` to call `ObservationBlender.blendObservationSeries` — and that call was correctly producing `73.12499°` (matching the Hourly Graph). The bug was in a **downstream merge step that silently undid the fix**.

### Evidence from live emulator logs

```
ObservationRepository: getDailyActualsWithLiveToday: ...
  live=[NWS[blendedHigh=73.12499, blendedLow=58.067535, rows=291]]
  persistedToday=[NWS[high=73.48656, low=58.686295, updatedAt=...]]
ObservationRepository: getDailyActualsWithLiveToday:
  mergedToday=[NWS[mergedHigh=73.48656, mergedLow=58.067535]]    ← stale persisted won
DailyViewHandler:    dailyTodayInputs: ... dailyActual.high=73.48656 ...
TODAY_BAR_DEBUG:     ... trueHigh=73.48656 ...                   ← reaches the renderer

TempExtrema:         ACTUAL_EXTREMA highIdx=61 highTemp=73.12499 ...   ← Hourly Graph's value
```

So `getDailyActualsWithLiveToday` was internally computing two different highs and merging them via `ObservationResolver.mergeDailyActualsBySource(primary=blended, secondary=persisted)`. The merge function `mergeDailyActual` (`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:318-332`) uses `maxOf(primary.highTemp, secondary.highTemp)` — **widest-bounds semantics**, not prefer-primary semantics. So `max(73.12499, 73.48656)` returned the stale persisted value.

### Why the two pipelines produce different highs

The persisted `daily_extremes` row is computed by a structurally different algorithm than the live IDW-by-hour blender used by the Hourly Graph:

| Pipeline | Algorithm | Today's high |
|---|---|---|
| **Live blended** (`ObservationBlender.blendObservationSeries`) | For each observation timestamp, IDW-blend all 5 stations → take `maxOf{ blended hourly temps }` | **73.12499°** |
| **Persisted** (`ObservationResolver.computeDailyExtremes` → `blendExtremes`) | For each station, compute its own daily max → IDW-blend those 5 daily-max numbers | **73.48656°** |

Both are "IDW-blended," but they apply IDW to different inputs and so are not interchangeable. The Hourly Graph uses the first; the Daily View was being forced through the second by the merge.

### Why the existing regression test didn't catch this

`TemperatureUnificationRegressionTest.kt:36-109` (added in the prior attempt) constructs `dailyActuals` directly from the blender output at line 72-74 and feeds it into `DailyActualsEstimator`. It never exercises the merge path that contains the bug, so it passes despite the bug existing.

---

## Implementation Details

### 1. Production change
**File:** `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` — `getDailyActualsWithLiveToday`

- Removed the `dailyExtremeDao.getExtremesInRange(todayDateMillis, ..., ...)` query for today's persisted extremes.
- Removed `persistedTodayActuals = extremesToDailyActualsBySource(persistedTodayExtremes, ...)`.
- Removed the inner `mergedTodayActuals = mergeDailyActualsBySource(primary=todayBlendedActuals, secondary=persistedTodayActuals)` call and its `mergedTodaySummary` log line.
- Removed the now-orphaned `todayDateMillis` local variable.
- Removed the `persistedToday=` clause from the existing `live=...` log line.
- Final return now uses `todayBlendedActuals` directly:
  ```kotlin
  return ObservationResolver.mergeDailyActualsBySource(
      primary = pastActuals,
      secondary = todayBlendedActuals,
  )
  ```
  This outer merge is over disjoint date sets (past 30 days vs today), so `maxOf` is harmless. Behavior for past days is unchanged.
- Added one short inline comment explaining *why* the persisted row must not be merged (algorithmic divergence).

`mergeDailyActualsBySource` / `mergeDailyActual` are **not** modified — their widest-bounds semantics may still be useful elsewhere.

### 2. Red-then-green Robolectric test
**File (new):** `app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryDailyMergeTest.kt`

- Uses `RobolectricTestRunner` + `TestDatabase.create()` (project's existing in-memory Room helper).
- Seeds 2 NWS station observations at the same timestamp: KNEAR (1km, 73.1°) and KFAR (10km, 73.5°). IDW-blended at that hour is dominated by KNEAR → ~73.1°.
- Seeds a stale `daily_extremes` row at 73.5° (simulates persisted IDW-of-per-station-max).
- Calls `repository.getDailyActualsWithLiveToday(...)`.
- Asserts `result[NWS][today].highTemp == 73.1f` (within 0.1).
- Verified RED on current `main` (before fix): `expected:<73.1> but was:<73.5>`.
- Verified GREEN after the fix.

The bug becomes structurally unreachable: there is no longer a code path that consults `daily_extremes` for today.

---

## Verification Results

### Unit Tests
- **New test:** `ObservationRepositoryDailyMergeTest` — RED on `main`, GREEN after fix.
- **Full suite:** 1,197 tests (1,196 prior + 1 new). All passing. No regressions.

### On-device verification (`emulator-5556`)
Before fix (logged in the investigation phase):
```
TODAY_BAR_DEBUG: ... trueHigh=73.48656 ...
TempExtrema:     ACTUAL_EXTREMA ... highTemp=73.12499 ...   ← mismatch
```

After fix:
```
TODAY_BAR_DEBUG: widget=31 mode=GRAPH ... trueHigh=73.12499 ...   ← matches Hourly Graph
```

Screenshot confirms the today bar label now reads `73.1°` matching the Hourly Graph's actual peak label.

### Build
- `./gradlew installDebug` succeeded on all 3 connected devices (SM-F936U1, emulator-5556, Pixel 7 Pro).

---

## Files Changed

```
app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt           (modified)
app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryDailyMergeTest.kt  (new)
```

Plan file (kept for reference, not part of the repo workflow):
```
~/.claude/plans/on-emulator-daily-forecast-memoized-stroustrup.md
```

---

## Key Takeaway

When a "fix" passes tests but doesn't fix the bug in production, suspect a downstream consumer of the fix output that silently overrides it. Here, the blender produced the right value but a merge step reapplied the wrong-but-higher value via `maxOf`. The give-away in the logs was the `live=[...73.12499...]` line immediately preceding the `mergedToday=[...73.48656...]` line — same source, two different highs, one transformation between them. That's where to look.

Equally instructive: the prior regression test passed because it bypassed the buggy step. A test that doesn't exercise the actual production code path is not a regression test, only a unit test of the helper. The new Robolectric test goes through `getDailyActualsWithLiveToday` end-to-end with real Room, so it would catch any future regression of this kind.
