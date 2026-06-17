# Label the actual (pink) line's low at the left edge of the hourly temperature graph

## Context

On the desktop hourly ("24 hour") temperature graph, the **actual/observed (pink) line's low is not
labeled** when the coldest observed point sits at the **left edge** of the window. The actual HIGH
labels fine; only the low is missing.

### Root cause (confirmed from live data + logs, 2026-06-16)

With the new full-day window (clicking "today" frames midnight→midnight), index 0 of the graph is
**midnight**. Live diagnostics from the running desktop app:

```
ACTUAL_EXTREMA highIdx=13 lowIdx=0 lowTemp=60.998 actualIndicesRange=0..21
ACTUAL_DAILY  highIdxs=[13]  lowIdxs=[]          ← per-day actual LOW list is EMPTY
```

The displayed source is **NWS**; the NWS-blended observations were essentially flat overnight
(~60.8–62°F) and the coldest point landed at **index 0 (midnight)**. (The deeper ~58° pre-dawn dip
only exists in Silurian/Open-Meteo, which aren't the displayed source.) So the low genuinely belongs
at the left edge.

`isActualLocalMin` in `shared/.../graph/TemperatureExtrema.kt:82` hard-rejects index 0:

```kotlin
fun isActualLocalMin(i: Int): Boolean {
    if (i <= 0 || i > actualEndIndex) return false                       // ← left edge rejected
    val rightOk = i >= actualEndIndex || actualLabelTemps[i] <= actualLabelTemps[i + 1]  // ← right edge exempted
    return actualLabelTemps[i] <= actualLabelTemps[i - 1] && rightOk
}
```

Note the **asymmetry**: the right edge (observation cutoff / NOW) is already exempt via
`i >= actualEndIndex` (a recently committed fix, d9d64b04, handles a low still cooling into NOW). The
left edge — the *start of observed data*, which in the day view is midnight — has no equivalent
exemption, so a real boundary low there is dropped and never labeled.

## Approach

Make the turning-point test **symmetric**: exempt the **first actual index** (start of observed data)
the same way the last actual index is exempted — require only the neighbor that exists. The existing
midnight-straddle "shoulder-drop" walk (`TemperatureExtrema.kt:121-141`) remains the safety net that
removes genuine multi-day slope artifacts at interior day boundaries, so this does not re-introduce
the shoulder labels those tests guard against (interior indices are unaffected — they still require
both neighbors).

### 1. Symmetric edge exemption — `shared/.../graph/TemperatureExtrema.kt`

Define the first observed index and use it to bound + exempt both `isActualLocalMin` and
`isActualLocalMax` (mirror the change for the max so an observed high at the start is also kept):

```kotlin
val actualStartIndex = actualIndices.firstOrNull() ?: -1
...
fun isActualLocalMin(i: Int): Boolean {
    if (actualStartIndex < 0 || i < actualStartIndex || i > actualEndIndex) return false
    val leftOk  = i <= actualStartIndex || actualLabelTemps[i] <= actualLabelTemps[i - 1]
    val rightOk = i >= actualEndIndex   || actualLabelTemps[i] <= actualLabelTemps[i + 1]
    return leftOk && rightOk
}
```

(`isActualLocalMax` mirrored with `>=`.) `leftOk`/`rightOk` both default true at their respective
edges, so the start point needs only its right neighbor and the end point only its left — and the OOB
access at `i-1`/`i+1` is still guarded. Update the function comment to explain the start-of-data
exemption alongside the existing NOW exemption.

### 2. Exempt actual extrema from left-edge suppression — `shared/.../graph/TemperatureLabelResolver.kt`

`checkLeftEdgeSuppression` (`:438-448`) suppresses non-boundary labels at idx 0 when a nearby
candidate has a similar value. It does **not** currently exempt actual extrema, so a newly-allowed
ACTUAL_LOW at idx 0 could still be dropped. Mirror the exemption already present in
`checkFetchDotSuppression` (`:462`) and `checkEndpointSuppression` — return "not suppressed" for
`ACTUAL_HIGH`/`ACTUAL_LOW` before the idx-0 check:

```kotlin
if (role == TemperatureRole.ACTUAL_HIGH || role == TemperatureRole.ACTUAL_LOW) return SuppressionResult(false)
```

No other gate needs changing: `checkRedundantPairSuppression` already returns `false` for
`ACTUAL_LOW`/`ACTUAL_HIGH` (`:493,498`), `deduplicateAnchors` already ranks `ACTUAL_LOW` above
`START`/`END`, and `resolveExtremaRole` (already reordered) maps idx 0 → `ACTUAL_LOW` (not `START`)
when it is in `actualDailyLowIndices`.

### Shared engine → both platforms
This is the shared label engine used identically by Android and desktop, so the fix lands on both,
matching the philosophy of the committed right-edge fix.

## Critical files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureExtrema.kt` — `isActualLocalMin`/`isActualLocalMax` + `actualStartIndex`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt` — `checkLeftEdgeSuppression` actual-role exemption

## Tests

- `shared/.../graph/TemperatureLabelSuppressionTest.kt` — add a case: an actual series rising
  monotonically from the first observed index (coldest point at idx 0) yields an `ACTUAL_LOW`
  candidate at idx 0 with the observed value. Assert on role + value (renderer color stubs are 0 in
  plain JUnit). Confirm the existing `per-day actual low on a day-boundary slope is not labeled`
  (`:694`) and `jagged midnight-straddle shoulder…` cases still pass (interior boundaries unaffected).
- `shared/.../graph/TemperatureLeftEdgeStartOrderTest.kt` and `GraphLabelPlacementUtilsTest.kt` left-edge
  cases — run to confirm no regression in left-edge ordering/suppression.

## Verification

1. Unit: `./gradlew :shared:test --tests "*TemperatureLabelSuppression*" --tests "*TemperatureExtrema*" --tests "*LeftEdge*" --tests "*GraphLabelPlacementUtils*"`.
2. Restart desktop (`scripts/fast-desktop-restart.sh`), click **today** → 24h hourly view; confirm the
   actual (pink) line now shows a low label at/near the left edge.
3. Confirm in the running app's log that the per-day actual low is now populated:
   `grep -aE "ACTUAL_DAILY|LabelAccepted: role=ACTUAL_LOW" ~/.local/state/weather-widget/autostart-*.log | tail`
   — expect `lowIdxs=[0]` and `role=ACTUAL_LOW idx=0`.
4. Regression check: a day whose real low is an interior pre-dawn valley still labels that valley (not
   the left edge), and multi-day zoomed-out views don't sprout spurious left-edge lows (shoulder-drop
   intact).
