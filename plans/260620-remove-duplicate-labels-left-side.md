# Fix: Duplicate "63.9°" labels at the left edge of the hourly temperature graph

## Context

On the emulator's hourly temperature graph (WIDE view), **two identical pink `63.9°`
labels** are stacked at the far-left edge. Confirmed by screenshot + logcat.

Root cause (from live `adb logcat`, widget 52):

```
HOURLY_DAY_EXTREMA: perDay=[2026-06-19 hi=63.91@22:00 lo=63.88@23:15 n=26; 2026-06-20 hi=74.35 lo=60.79 n=298]
TempExtrema: ACTUAL_DAILY highIdxs=[0, 259] lowIdxs=[16, 322]
LabelAccepted: role=ACTUAL_HIGH idx=0  val=63.912216
LabelAccepted: role=ACTUAL_LOW  idx=16 val=63.8794
```

The leftmost day (Fri 6-19) is only **partially in the window** — 26 observation points
spanning ~22:00–23:15 (~1.3h). Over that tiny slice the day's actual high (63.91°) and
actual low (63.88°) differ by 0.03°, so **both round to "63.9°"**. The per-day actual
extrema emit one `ACTUAL_HIGH` (idx 0) and one `ACTUAL_LOW` (idx 16), producing two
labels with identical displayed text stacked at the edge.

This is NOT the `computeLeftEdgeStartOrdering` START/forecast pairing path (an earlier
hypothesis): the START anchor at idx 0 was deduped into `ACTUAL_HIGH`, and the two
labels are 16 indices apart (beyond that function's window of 8). Both are ACTUAL roles.

**User intent (confirmed in conversation):** a single label for this degenerate edge day
is the desired outcome — the user explicitly said the separate left-edge low "is
acceptable" to omit (the right-edge low is still labeled normally).

## Fix

Collapse a single day's actual high/low to one label **when both round to the same
displayed value**. Keep the high (drawn above the curve), drop the redundant low.

### File: `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt`

At lines 153–154, where the parallel per-day lists are finalized after the existing
`shoulderDrops` filter:

```kotlin
val actualDailyHighIndices = rawDailyHighIndices.filterNot { it in shoulderDrops }
val actualDailyLowIndices = rawDailyLowIndices.filterNot { it in shoulderDrops }
```

Add a degenerate-day collapse between them. Map each surviving daily-high to its date,
then drop any same-day low whose display value equals that day's high:

```kotlin
val actualDailyHighIndices = rawDailyHighIndices.filterNot { it in shoulderDrops }
// A partial edge day spanning ~1h can have an actual high and low that round to the
// same displayed value (e.g. 63.91 / 63.88 -> both "63.9°"), which stacks two identical
// labels at the graph edge. When a day's high and low render identically, keep the high
// and drop the redundant low. See per_day_actual_extrema_labels memory.
val highIdxByDay = actualDailyHighIndices.associateBy { hours[it].dateTime.toLocalDate() }
val degenerateLowDrops = rawDailyLowIndices.filter { lowIdx ->
    val hiIdx = highIdxByDay[hours[lowIdx].dateTime.toLocalDate()] ?: return@filter false
    TemperatureLabelResolver.formatTemp(actualLabelTemps[hiIdx]) ==
        TemperatureLabelResolver.formatTemp(actualLabelTemps[lowIdx])
}.toSet()
val actualDailyLowIndices = rawDailyLowIndices.filterNot { it in shoulderDrops || it in degenerateLowDrops }
if (degenerateLowDrops.isNotEmpty()) {
    Log.d(TAG, "DEGENERATE_DAY_LOW_DROPPED idxs=${degenerateLowDrops.sorted()} " +
        "temps=${degenerateLowDrops.sorted().map { actualLabelTemps[it] }}")
}
```

Notes:
- `TemperatureLabelResolver.formatTemp` (TemperatureLabelResolver.kt:78) is a public
  function on a same-package `object` — directly callable, no new import. It is the same
  rounding the renderer uses for label text, so equality here exactly matches "renders
  identically."
- Dropping at this single seam removes the low from every downstream consumer:
  `buildPotentialAnchors` (lines 400–401), the role mapping (lines 381–382), and
  `addCoincidentActuals` (lines 247–248). No resolver/engine/renderer change needed.
- Display-equality (not a raw epsilon) is deliberate: it suppresses only the visible
  duplicate and never collapses two distinct-looking labels (e.g. "63.9°" vs "64.0°").

## Tests

### File: `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureExtremaIncompleteDayTest.kt`

Add a regression test reusing the existing `hour(...)` / `build(...)` / `TemperatureExtrema.compute(...)`
harness in that file. Construct a degenerate partial edge day (e.g. a short run of
observations where actual high ≈ low both rounding to the same 0.1°, plus a normal
complete day) and assert:
- the degenerate day's high index **is** in `actualDailyHighIndices`
- the degenerate day's low index **is NOT** in `actualDailyLowIndices`
- a control day with a genuinely separated high/low keeps BOTH (no over-suppression).

(Alternatively a small dedicated `TemperatureExtremaDegenerateDayTest.kt` mirroring the
existing test style — either is fine; the existing file is the natural home.)

## Verification

1. Unit test:
   `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.TemperatureExtrema*"`
   (and the broader `:shared` graph suite to confirm no regression in left-edge / per-day
   extrema tests).
2. Build + install: `./gradlew installDebug`.
3. On the emulator, view widget 52's hourly graph in WIDE view (the same partial-left-day
   condition). Confirm a **single** `63.9°` label at the left edge.
4. Confirm via logcat that `DEGENERATE_DAY_LOW_DROPPED` fires for the edge day and that the
   right-edge low (`ACTUAL_LOW idx=322`) is still labeled normally:
   `adb -s emulator-5554 logcat -d | grep -E "DEGENERATE_DAY_LOW_DROPPED|ACTUAL_DAILY|LabelAccepted"`

## Out of scope / accepted

- The left-edge day shows only one label (high). Per the user, omitting the separate
  left low is acceptable; the right-edge low remains labeled.
