# Reject fallback actual turns the observed line has since gone past

**Date:** 2026-09-10
**Status:** implemented and runtime-verified on the emulator
**Scope:** hourly graph value labels, `:shared` (Android + desktop)

## Problem

Emulator, 2026-09-10 17:56, hourly view for today. The observed (pink) line descends from
88.1° at 14:00 to 78.2° at Now (17:56), and the graph draws a pink `81.8°` label at 16:15 —
a value the line dipped to, rebounded 0.8° from, and then fell straight through. Nothing about
81.8° is a low: the line is 3.6° colder at its labelled end.

Logs for the render:

```
TempExtrema:       ACTUAL_DAILY highIdxs=[20] highTemps=[88.1] lowIdxs=[] lowTemps=[]
TempExtrema:       ACTUAL_LOCAL_EXTREMA: lows=[idx=18 t=13:30 temp=85.6, idx=21 t=14:15 temp=85.7,
                                                idx=29 t=16:15 temp=81.8] reversal=0.75
TempLabelResolver: ActualTurnThinning: kept=29 of 3
TempLabelResolver: LabelAccepted: displayed="81.8" t=16:15 role=ACTUAL_LOW reason=PROMINENT_ACTUAL_TURN
```

## Root cause

`LabelCandidateCollector.collect` has a fallback: when a day has no confirmed daily actual low
(here because the day's coldest observed samples are at the window edge and at Now, both
edge-gated), it labels the coldest *prominent turn* from
`TemperatureExtrema.findProminentActualTurningPoints`. That detector is a zig-zag walk with a
0.75°F reversal hysteresis and no notion of trend: on a steep monotone descent every ≥0.75° wiggle
registers as a "low". `mostExtremeTurn` then keeps the coldest wiggle, but never asks whether the
line went colder *afterwards*. It did — 78.2° at Now — so the label describes a bottoming-out that
never happened.

The daily path already encodes the right principle ("an extreme is not an extreme if the edge is
more extreme", `TemperatureExtrema.kt` ~L163); the fallback path does not.

## Change

`shared/src/main/kotlin/com/weatherwidget/shared/graph/LabelCandidateCollector.kt`, in the
fallback branch before `mostExtremeTurn`:

- Drop a prominent low whose temperature is warmer than any later observed sample up to
  `extrema.actualEndIndex`; symmetrically drop a prominent high colder than any later observed
  sample.
- Gate: only when the observed line **terminates on screen** (`extrema.actualEndIndex <
  hours.lastIndex` — a forecast region follows, so the last actual sample is the line's terminal
  reading, not a window cut). A historical slice whose observed line runs off the right edge keeps
  today's behaviour: its interior peak is still the peak the reader can see, and the edge value
  is arbitrary (`TemperatureActualTurningPointLabelTest` test 1 asserts exactly that).
- Log each rejection (`Log.v`, tag `TempLabelResolver`, `ActualTurnSurpassed: …`) so the next
  emulator question can be answered from logcat.

No change to `TemperatureExtrema`, the daily path, or the reversal threshold.

## Expected outcome on the emulator series

All three prominent lows (85.6, 85.7, 81.8) are surpassed by the 78.2° terminal reading → no
`ACTUAL_LOW` fallback label; `ACTUAL_END` 78.2° remains. `ACTUAL_HIGH` 88.1° is unaffected (daily
path, not fallback).

## Tests

| # | Kind | File | Asserts |
|---|------|------|---------|
| 1 | Unit | `TemperatureActualTurningPointLabelTest` (new case) | Emulator series reduced (descent 88.1→78.2 with a 81.8/82.6 wiggle, forecast after NOW): no `ACTUAL_LOW` candidate at the wiggle; `ACTUAL_END` present. |
| 2 | Unit | same, new case | Same series but the line rebounds after the dip and Now is *warmer* than the dip: the dip IS labelled (proves the gate is about later samples, not about the fallback in general). |
| 3 | Unit | same, existing `historical slice…` | Unchanged: observed line runs to the window edge; interior peak 71.79 still labelled although edge 73.81 is higher. Guards the on-screen-termination gate. |
| 4 | Unit | same, existing `plateau…` | Unchanged: 75.84 low / 77.35 high survive because nothing later is more extreme. |
| 5 | Runtime | emulator | Install, force a repaint, grep logcat for `ActualTurnSurpassed` and confirm no `LabelAccepted … 81.8`; screenshot shows no 81.8° label. |

## Verification

- `./gradlew :shared:test` — 1562 tests, 0 failures. `TemperatureActualTurningPointLabelTest`:
  7 tests (4 existing + 3 new) green. While writing test 1 an assertion that `ACTUAL_END` survives
  as a candidate failed — the Now reading's label comes from the fetch-dot path, not from this
  candidate list (pre-existing; the emulator's before-render also had no `ACTUAL_END` in
  `LabelPlacementDebug`). Assertion dropped; not in scope.
- Emulator (`installDebug`, right-arrow then left-arrow repaint at 18:03:58):
  ```
  ActualTurnSurpassed: dropped low idx=18 t=13:30 temp=85.6 by idx=25 t=15:15 temp=85.5
  ActualTurnSurpassed: dropped low idx=21 t=14:15 temp=85.7 by idx=25 t=15:15 temp=85.5
  ActualTurnSurpassed: dropped low idx=29 t=16:15 temp=81.8 by idx=32 t=17:00 temp=80.9
  ```
  No `LabelAccepted … ACTUAL_LOW`; `ACTUAL_HIGH 88.1` still placed at idx 20. Screenshot: no
  81.8° label; 88.1° and the Now dot's 78.2° unchanged.
