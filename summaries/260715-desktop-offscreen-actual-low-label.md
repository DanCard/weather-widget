# Desktop: pink `66.3°` label floating below the actual line's visible end

## Problem

Desktop popup, panned to a **Tue 10p → Wed 1a** window (reported ~9am Wed): the pink actual line
visibly terminates at the right edge around **~68°**, but a pink **`66.3°`** label is drawn
flush-right, well below the line — anchored to nothing. The label neither touches the line nor
matches what the line reads at that x.

Desktop-only. Android's dense label list never extends past its window.

## Diagnosis

Straight from the permanent VERBOSE label logging plus the two `TEMP DIAGNOSTIC` log lines in
`TemperatureGraph.kt` (no code-reading guesswork needed until the mechanism was already pinned):

```
TempExtrema:  PERDAY_RAW date=2026-07-15 ... min(idx=49 t=01:50 temp=66.33591 localMin=true)
TempLabelResolver: LabelAccepted: displayed="66.3" t=01:50 role=ACTUAL_LOW reason=EXTREMA idx=49
GapLabelDiag: actualEndIdx=52/3 ... ACTUAL_LOW:66.3?@96% LOW:67?@97%
ActualLineDiag: lastActualHrsBack=6 actualPts=53 totalSeriesPts=53
```

`actualEndIdx=52/3` is the tell: the label pipeline saw a **53-point dense list** while the visible
forecast list (`points`, which defines the x-mapping `dataStart..dataEnd`) had only **4 hourly
points** (10p/11p/12a/1a). The series' last actual sample was ~3am — **two hours past the visible
right edge**.

Three mechanisms combined:

1. **The actual series is deliberately context-padded past the window edges.**
   `ActualTemperatureSeriesBuilder.build()` is called with
   `contextLookahead/LookbackHours` (`ACTUALS_CONTEXT_EDGE_PAD_HOURS = 12`, lookahead floor 60h) so
   the pink line interpolates cleanly to the canvas corners. In this panned view the series ran
   through the real overnight trough — **66.34° at 01:50** — beyond the visible end.
2. **`TemperatureExtrema` gates edge samples by the *series* end, not the *visible* end.** Its
   comments promise exactly this protection ("in a zoomed/panned view the real overnight valley
   often lies off-screen… the leftmost/rightmost sample is merely where the window was cut"), but
   "boundary" means index 0 / lastIndex of the dense list. The trough at idx 49 of 52 is interior
   to the *series* while off-screen in the *window*, so `isActualLocalMin` accepted it.
3. **The label engine clamps every label's x into the canvas** (`geometry.clampedX` throughout
   `TemperatureLabelEngine.kt`). The trough's raw x was ~1.28× canvas width; clamping parked it
   flush-right (96%) at `y = yAt(66.34)`. The line, drawn from `actualLinePoints`, is cropped the
   *other* way — geometrically clipped by the canvas — so its visible end is ~68° at ≈1a and the
   descent to 66.3 happens off-canvas.

The core asymmetry worth remembering: **line and labels draw from the same series but are cropped
differently — the line is clipped by the canvas, labels are clamped into it.** Any label anchored
past the edge silently detaches from its curve.

## What changed

`desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` (~line 527), one filter:

```kotlin
val hourDataList = HourDataAssembler.assembleHourData(actualSeries, zoneId)
    .filter { msOf(it.dateTime) in dataStart..dataEnd }
```

The dense list feeding the label engine is truncated to the visible data span (the span `xAtTime`
maps to canvas width). The off-window trough becomes the list's **boundary sample**, and
TemperatureExtrema's existing edge gate drops it — the fix routes through the gate that was already
designed for this case rather than adding a new rule. The three engine inputs
(`hours`/`originalPoints`/`forecastPoints`) stay index-aligned since all derive from the filtered
list; `effectiveActualEndIndex` already indexes `hourDataList` so it follows automatically.

**Deliberately untouched:** `actualLinePoints` (line drawing) keeps the full padded series, so the
pink line still interpolates cleanly to the corners. Bonus: the filter also removes any NaN
forecast tail beyond the loaded horizon from the label path (the NaN hazard TemperatureExtrema
defends against at its top).

The default (un-panned) view is unaffected: the same 01:50 trough is on-screen there, interior to
the filtered list, and still gets its label — on the line.

## Files

- `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` — the fix
- `plans/260715-desktop-offscreen-actual-low-label.md` — assessment + proposal (status updated)

## Verification

- Built and deployed via `scripts/buildStart-desktop.sh`; app restarted on the fixed build.
- **User is verifying on the live app** (pan so a known overnight trough sits just past the right
  edge; the flush-right pink label should be gone, and the default view's on-screen low label
  should survive). `GapLabelDiag` in the new autostart log will expose any label at a suspicious
  x% if it recurs.
- No new unit test: the filter is inline in a `Canvas {}` composable with no seam. If regression
  coverage is wanted, the pure-function-extraction route (per testing strategy) would be extracting
  the truncation + engine-input assembly into `DesktopGraphUtils` and asserting no label anchor
  maps outside `[0, w]` when the series overruns `dataEnd`.

## Follow-ups

- Consider the same guard for other desktop graphs if any feed context-padded series into the
  shared label engine (precip/cloud paths were not audited here).
- The clamp-vs-clip asymmetry is generic: any future engine caller must either truncate its dense
  list to the drawable span or accept that off-window anchors get dragged on-screen.
- Not committed.
