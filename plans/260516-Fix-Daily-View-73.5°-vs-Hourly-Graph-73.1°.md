# Fix: Daily View 73.5° vs Hourly Graph 73.1° — stop merging persisted into live blended

## Context

The Daily View shows `73.5°` for today's high while the Hourly Graph shows `73.1°` for the same day on the emulator. A prior fix (commits `910dfba`, `9a6a895`) was supposed to unify them by making `getDailyActualsWithLiveToday` use `ObservationBlender.blendObservationSeries` — the same blender the Hourly Graph uses. The blender call is in fact producing the correct value, but a downstream merge step replaces it with the legacy persisted value.

Proof from live emulator logs (`adb -s emulator-5556 logcat`):

```
ObservationRepository: getDailyActualsWithLiveToday: ...
  live=[NWS[blendedHigh=73.12499, blendedLow=58.067535, rows=291]]
  persistedToday=[NWS[high=73.48656, low=58.686295, ...]]
ObservationRepository: getDailyActualsWithLiveToday:
  mergedToday=[NWS[mergedHigh=73.48656, mergedLow=58.067535]]
```

And what reaches the Daily View:
```
DailyViewHandler: dailyTodayInputs: ... dailyActual.high=73.48656 ...
TODAY_BAR_DEBUG: ... trueHigh=73.48656 ...
```

The blender produced `73.12499°` (matches Hourly Graph). The merge step `mergeDailyActualsBySource(primary=blended, secondary=persisted)` returned `73.48656°` — the persisted value — because `ObservationResolver.mergeDailyActual` (`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:318-332`) uses `maxOf(primary.highTemp, secondary.highTemp)` / `minOf(lows)` "preserving the widest known bounds." That is the *pre-fix* semantic; it does not match the inline comment `// Prefer live blended results`.

The two values disagree because the persisted row is computed by a different algorithm:
- **Live blended** (`ObservationBlender.blendObservationSeries`): IDW-blend the 5 stations *at every observation timestamp*, then take `maxOf{ blended hourly temps }` → `73.12499°`. Same call the Hourly Graph makes.
- **Persisted** (`ObservationResolver.computeDailyExtremes` → `blendExtremes`): For each station compute its own daily max, then IDW-blend those 5 daily-max numbers → `73.48656°`. Written to `daily_extremes` table by `recomputeDailyExtremesForDay` every time a new observation arrives.

`maxOf(73.12499, 73.48656) = 73.48656` ⇒ persisted wins ⇒ Daily View shows `73.5°`.

The existing regression test `TemperatureUnificationRegressionTest.kt:36-109` passes because it constructs `dailyActuals` directly from the blender output (line 72-74) and never exercises the merge path that contains the bug.

User direction: **always blended, never merge, no fallback to persisted**. If the blender returns empty for a source, show nothing for that source's today actual — do not consult `daily_extremes`.

## Change

**File:** `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` — `getDailyActualsWithLiveToday` (lines 325-426)

1. Delete the `persistedTodayExtremes` query (line 367-372) and the `persistedTodayActuals` derivation (line 406).
2. Delete the `mergedTodayActuals = ObservationResolver.mergeDailyActualsBySource(primary=todayBlendedActuals, secondary=persistedTodayActuals)` call (lines 407-410) and the related `mergedTodaySummary` logging (lines 411-420).
3. Replace the final return (lines 422-425) so today comes directly from `todayBlendedActuals`:
   ```kotlin
   return ObservationResolver.mergeDailyActualsBySource(
       primary = pastActuals,
       secondary = todayBlendedActuals,
   )
   ```
   (This merge is over disjoint date sets — past 30 days vs today — so `maxOf` is harmless. No semantic change to past-day behavior.)
4. Update the existing live/persisted log to drop the `persistedToday=` clause, or keep it as `persistedToday=ignored` only if useful for forensic comparison. Recommend dropping to reduce noise.

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureUnificationRegressionTest.kt`

The test currently does not exercise the merge path. Either:
- (a) Leave as is — it still validates the blender→DailyActualsEstimator path, and the merge is gone so it can't reintroduce the bug. OR
- (b) Add a second assertion that simulates having a stale `daily_extremes` row with a higher value and confirms the Daily View still shows the blended value. Requires constructing a `DailyExtremeEntity` and either calling `getDailyActualsWithLiveToday` (needs a fake DAO) or asserting at a smaller seam.

Recommend (a) — the bug becomes structurally unreachable after the change, so an extra test is low value.

## Why this is safe

- The persisted `daily_extremes` table is still maintained for past days (called by `recomputeDailyExtremesForDay` on every backfill). Past-day reads through `pastActuals` are unchanged.
- The `mergeDailyActualsBySource` function itself is unchanged and continues to work for past-day callers (`extremesToDailyActualsBySource` consumers elsewhere).
- If `todayBlendedActuals` is empty for a source (e.g., zero observations today), that source simply has no today entry — `DailyActualsEstimator` already handles a missing `dailyActuals[today]` by falling back to `dashedLineHigh` (the forecast). User has confirmed this is desired.

## Critical files

- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt:325-426` — the only file to edit for the fix
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:298-332` — `mergeDailyActualsBySource` / `mergeDailyActual` (read-only; semantics preserved)
- `app/src/main/java/com/weatherwidget/util/ObservationBlender.kt:86-231` — `blendObservationSeries` (read-only; produces the correct value already)
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureUnificationRegressionTest.kt` — leave as is, or extend per (b) above

## Verification

1. Build and unit-test:
   ```bash
   ./gradlew testDebugUnitTest
   ```

2. Install on the emulator that exhibits the bug (`emulator-5556`):
   ```bash
   ./gradlew installDebug
   adb -s emulator-5556 shell am broadcast -a com.weatherwidget.REFRESH_WIDGET
   ```

3. Pull logs and confirm the live blended value reaches the Daily View unchanged:
   ```bash
   adb -s emulator-5556 logcat -d | grep -E "TODAY_BAR_DEBUG|getDailyActualsWithLiveToday|TempExtrema.*ACTUAL_EXTREMA"
   ```
   Expected: `TODAY_BAR_DEBUG ... trueHigh=73.12...` matches `TempExtrema ACTUAL_EXTREMA highTemp=73.12...` (both should round to `73.1°`).

4. Visual check: take a screenshot and confirm the Daily View today-bar label matches the Hourly Graph actual-peak label.
   ```bash
   adb -s emulator-5556 exec-out screencap -p > /tmp/widget.png && convert /tmp/widget.png /tmp/widget.jpg
   ```
