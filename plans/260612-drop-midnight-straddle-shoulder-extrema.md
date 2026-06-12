# Drop midnight-straddle "shoulder" actual-low/high labels on the hourly graph

## Context

On the hourly temperature graph (emulator, 4-day view Wed 10 → Sat 13), a stray
**66.9°** label appears on the Wednesday-evening *descending slope* of the actual
(pink) line. It is not a daily low — the real overnight valley bottoms out later at
**60.3°**. It is also not the current-temp marker (`ACTUAL_END`); that is already
suppressed by the fetch dot, and the on-screen **76.5° NOW** dot stays.

Logs confirm the offender is `ACTUAL_LOW idx=162 val=66.85957`, emitted from
`actualDailyLowIndices`:

```
LabelAccepted: role=ACTUAL_LOW idx=162 val=66.85957   <- the stray 66.9
LabelAccepted: role=ACTUAL_LOW idx=242 val=60.275845  <- real Wed/Thu valley
LabelAccepted: role=ACTUAL_LOW idx=459 val=60.39954   <- real Thu/Fri valley
LabelSuppressed: role=ACTUAL_END idx=485 reason=FETCH_DOT  <- already hidden (keep)
```

**Root cause — midnight straddle.** `TemperatureExtrema.compute` groups observed
points by calendar date (`dateTime.toLocalDate()`) and takes each date's min as that
day's actual low. Wednesday's true valley falls *after midnight* (owned by Thursday =
60.3), so Wednesday's own-date minimum is just its coolest pre-midnight point — a
shoulder on a monotonic descent, not a turning point. A `isActualLocalMin()`
turning-point guard already exists to drop these, but it only inspects the immediate
neighbours (`temp[i] <= temp[i±1]`). Real observations are jagged, so a 1-sample dip
at idx 162 passes it. (See memory `per_day_actual_extrema_labels.md`; the existing
"day-boundary slope" test only covers a *smooth* V, which is why this slipped through.)

**Intended outcome.** Stop labeling these midnight-straddle shoulders as daily
extrema, robustly against jagged data, without removing genuine per-day highs/lows or
the current-temp dot. Correctness fix — applies in every view, not gated on day count.

## Approach

Add a post-processing pass in `TemperatureExtrema.compute` (file:
`shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`), right
after `actualDailyHighIndices` / `actualDailyLowIndices` are computed (currently lines
~103–104), and feed the filtered lists into the returned `ExtremaIndices`.

**Rule:** a genuine diurnal cycle always separates two successive actual *lows* with
an actual *high* (and two highs with a low). Therefore, walking the per-day actual
extrema in index order, any two **same-type** extrema that are adjacent in that merged
sequence (no opposite-type extreme between them) are the same valley/peak split across
a calendar boundary — keep only the more-extreme one (cooler for lows, warmer for
highs) and drop the shoulder.

Representative implementation (keep the existing `isActualLocalMin/Max` and
`dayHighReached` filters in place — this pass is additive and runs after them):

```kotlin
// Merge per-day highs/lows in index order; drop same-type neighbours not separated by
// the opposite type (midnight-straddle shoulders of one valley/peak).
val merged = (actualDailyHighIndices.map { it to true } +
              actualDailyLowIndices.map { it to false }).sortedBy { it.first }
val shoulderDrops = mutableSetOf<Int>()
var kept: Pair<Int, Boolean>? = null
for (cur in merged) {
    val k = kept
    if (k != null && k.second == cur.second) {
        val keepCur = if (cur.second) actualLabelTemps[cur.first] >= actualLabelTemps[k.first]
                      else            actualLabelTemps[cur.first] <= actualLabelTemps[k.first]
        if (keepCur) { shoulderDrops.add(k.first); kept = cur }
        else          shoulderDrops.add(cur.first)   // kept unchanged
    } else kept = cur
}
val filteredDailyHighIndices = actualDailyHighIndices.filterNot { it in shoulderDrops }
val filteredDailyLowIndices  = actualDailyLowIndices.filterNot  { it in shoulderDrops }
```

Return `filteredDailyHighIndices` / `filteredDailyLowIndices` in `ExtremaIndices`
(replacing the raw lists at lines ~146–147). Add a `Log.d(TAG, "SHOULDER_DROPPED ...")`
line listing dropped indices/temps, mirroring the existing `ACTUAL_DAILY` log.

Why this is safe for the genuine lows in the emulator data: 60.3 (idx 242) and 60.4
(idx 459) are separated by the actual high at idx 332, so they are never adjacent
same-type → both kept. 66.9 (idx 162) and 60.3 (idx 242) are adjacent lows with no
high between → the warmer 66.9 is dropped.

No placement-engine or renderer changes; both Android and desktop consume the shared
`ExtremaIndices`, so the fix lands on both platforms at once.

## Tests

File: `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelSuppressionTest.kt`

- **Keep passing unchanged:**
  - `each day in a multi-day actual region gets its own actual low label` (idx 4, 28 —
    separated by the day-0 afternoon high, so neither is dropped).
  - `each day in a multi-day actual region gets its own actual high label` (idx 16, 40).
  - `per-day actual low on a day-boundary slope is not labeled` (smooth V; idx 23 still
    dropped — now by both the old guard and the new pass).
- **Add a new regression** (`midnight-straddle shoulder survives a jagged wiggle`): a
  two-day observed series with the real valley just after the midnight boundary, plus a
  deliberate 1-sample uptick on the pre-midnight descent (so `isActualLocalMin` passes
  on the shoulder). Assert the shoulder index gets **no** `ACTUAL_LOW`, the post-midnight
  valley **does**, and the next day's separated valley is retained. Build it from the
  existing `twoDayObservedHours()` helper pattern.

Run: `./gradlew testDebugUnitTest --tests "com.weatherwidget.shared.graph.TemperatureLabelSuppressionTest"`
(plus `TemperatureExtremaIncompleteDayTest`).

## Verification (emulator)

1. `./gradlew installDebug`
2. Refresh the widget: `adb -s emulator-5554 shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE` (or the app's `ACTION_REFRESH`).
3. `adb -s emulator-5554 exec-out screencap -p > /tmp/ww.png && convert /tmp/ww.png /tmp/ww.jpg` and read `/tmp/ww.jpg`.
   - **Gone:** the 66.9° label on the Wed-evening slope.
   - **Kept:** 93.1°/97.7° highs, 60.3°/60.4° valley lows, the 76.5° NOW dot, edge labels.
4. Confirm via logs: `adb -s emulator-5554 logcat -d | grep -E "ACTUAL_DAILY|SHOULDER_DROPPED"` shows idx 162 in the dropped set and absent from the accepted `ACTUAL_LOW` labels.

## Notes / memory

After landing, update memory `per_day_actual_extrema_labels.md`: the immediate-neighbour
turning-point guard is insufficient for jagged real data; the same-type-without-separator
pass is the robust backstop. (`ACTUAL_END` was a red herring — the stray label was a
mislabeled per-day `ACTUAL_LOW`, and `ACTUAL_END` is independently suppressed by the
fetch dot.)
