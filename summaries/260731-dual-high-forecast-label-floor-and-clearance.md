# Samsung history: Mon/Tue showed no forecast high — a 2° floor, then a label overlap

**Date:** 2026-07-31
**Plan:** none (live debug from a device report)

## Outcome

Reported as "Samsung: daily forecast view: history: Last mon and tues, does not show the forecasted
high. There is plenty of room above to show forecasted label above the actual temp." Both halves of
that sentence were correct and each pointed at a different bug.

Fixed and verified on the device (SM-F936U1). Mon now prints `77°` over `75.9°` and Tue `78°` over
`76.2°`, both with clear air between the labels. Desktop got the same change for parity and was
rebuilt/restarted.

A follow-up report mid-session — "I don't like the overlap that forecast label has on actual
temperature for mon and tues. There is lots of room above, so no reason for the overlap" — caught
the first attempt aiming at the wrong target. See the second bug below.

## Diagnosis

Both bugs share one root: **the vertical gap between the two high labels IS the forecast miss**, so
a small miss can never be fixed by tuning the fixed nudges in `DualHighLabel.bottomOffsetsDp`.

`daily_history` on the device (NWS, the displayed source) for the five history columns:

| Day | Actual high | Forecast high | Miss | Label before |
|---|---|---|---|---|
| Sun 26 | 72.4 | 77.0 | 4.63° | both |
| **Mon 27** | 75.9 | 77.0 | **1.08°** | **actual only** |
| **Tue 28** | 76.2 | 78.0 | **1.76°** | **actual only** |
| Wed 29 | 74.9 | 80.0 | 5.10° | both |
| Thu 30 | 75.9 | 81.0 | 5.10° | both |

Exactly the two sub-2° days were the two missing labels.

### Bug 1 — `MIN_DIFF_DEG = 2f` was silently the gate

The constant's own docstring claimed it was "deliberately low so the room test below is the real
gate." It was not. At the fold's ~7.75 px/° (bitmap space) a 1.1° miss clears the room test
comfortably, so the floor pre-empted the geometry for every miss under 2°, while the room test only
starts objecting below ~0.6°. Lowered to `0.5f` — the smallest gap that still prints as two distinct
numbers at one-decimal display precision.

### Bug 2 — the placement target was an admission tolerance

With the floor lowered, Tue rendered but **Mon still failed the room test by 0.31px of 13.84**.

`MAX_OVERLAP_FRACTION` (0.6) is an *admission* tolerance — "a squeezed pair still beats dropping a
label." It is not a placement target. The first fix pushed the upper label just far enough to
satisfy it, which parks the two labels at 60% box overlap. Cap height is ≈ **0.61 × measured box**
for the default font, so any gap under 0.61H prints genuinely overlapping digits — which is what the
user then rejected.

New `DUAL_TARGET_SEPARATION_FRACTION = 0.85f` targets the digits' 0.61 plus ~0.24 of a box of air.
Keep target > tolerance, or capped cases get admitted and then rejected.

## What changed

`shared/.../graph/DualHighLabel.kt`
- `MIN_DIFF_DEG` 2f → **0.5f**, docstring rewritten to record why the old value was wrong.
- New `DUAL_TARGET_SEPARATION_FRACTION = 0.85f` and `DUAL_UPPER_MAX_EXTRA_PUSH_FRACTION = 1f`.
- New `extraUpperPushPx(currentGapPx, labelHeightPx, maxExtraPushPx)`: raises the **higher-valued**
  label only by exactly the shortfall against the target. Raising only the upper one means the pair
  separates and can never cross, preserving `bottomOffsetsDp`'s ordering property.
- The cap is a **fraction of label height, not dp** — what the raise has to clear *is* a label, so a
  dp constant would over/under-shoot as the graph font scales with widget size.

`app/.../widget/DailyHighLabelPlanner.kt`
- Applies the push to whichever baseline is the upper one; `showBoth`, `forecastFontScale`,
  `actualBaseline`, `forecastBaseline` and `anchorBaseline` all use the pushed positions, so the
  room test measures what is drawn and the rain % still anchors above the topmost label.
- Clamps the push to `upperBaseline - labelHeight` so a raise can never push a label off the top of
  the bitmap.
- Room-test label height now `fullFontHeight * maxOf(drawnScale(actual), drawnScale(forecast))` —
  the DRAWN size, per the `label_redundancy_measured_where_drawn` lesson.
- **Permanent VERBOSE breadcrumb** (see Notes).

`desktop/.../DailyForecastGraph.kt`
- Same push, threaded through `dualTop(..., pushPx)` so the room test and the draw use identical
  positions. `dualTop`'s existing `-headerBleed` clamp is the hard ceiling, so no separate headroom
  cap is needed there.

## Verification

Device measurements from the new logging, before → after. The raise is surgical, not a blanket
loosening — every day that already had room is untouched:

| Day | Miss | Gap before | Gap after | Push |
|---|---|---|---|---|
| Sun 26 | 4.63° | 41.07 | 41.07 | 0.0 |
| **Mon 27** | 1.08° | 13.53 ✗ | **29.40** ✓ | 15.87 |
| **Tue 28** | 1.76° | 18.81 | **29.40** | 10.59 |
| Wed 29 | 5.10° | 44.72 | 44.72 | 0.0 |
| Thu 30 | 5.10° | 44.67 | 44.67 | 0.0 |

- Screenshot on SM-F936U1 confirms clean separation on both days.
- `./gradlew :app:testDebugUnitTest` and `:shared:test` pass; `:desktop:compileKotlin` clean.
- Desktop rebuilt and restarted via `scripts/buildStart-desktop.sh`.

## Regression coverage

Five new cases in `DualHighLabelTest`:
- `a one-degree miss on a real graph shows both` — the reported geometry (7.75 px/°, density 3.03,
  60px full label box) asserted through `bottomOffsetsDp` → `showBoth`. **Verified it genuinely
  fails at the old 2f floor** rather than passing vacuously.
- `pushing the upper label clears a borderline pair instead of dropping or overlapping it` — the
  device numbers, asserting both admission AND that the digits clear (`gapAfter >= 0.61 × labelH`).
- `the push aims past the admission tolerance, not at it` — encodes the bug-2 invariant
  (`DUAL_TARGET_SEPARATION_FRACTION > 1 - MAX_OVERLAP_FRACTION`, and > 0.61).
- No-push and cap cases, including that a capped push is not a `showBoth` bypass.
- `difference below floor does not show both even with room` now derives its input as
  `MIN_DIFF_DEG * 0.5f` instead of `- 1f`, which went negative and asserted the opposite of its name
  once the floor dropped below 1.

## Notes

**Daily dual-high now HAS logging.** It previously had none — the old memory explicitly said
"measure with a screenshot, not logcat" — so every "why is there no forecast label" report cost a
screenshot-measuring session. Added a permanent VERBOSE breadcrumb in
`DailyHighLabelPlanner.resolveHighLabelPlan`:

```
V DailyHighLabel: dualHigh date=2026-07-27 actual=75.92 forecast=77.0 gap=29.400833
  (prePush=13.527931 push=15.872902) labelH=34.589214 (full=35.295116)
  needGap=13.835685 showBoth=true
```

VERBOSE so it never reaches `app_logs`. Read with `adb logcat -d -s DailyHighLabel:V`. Force a
repaint by tapping the widget's nav arrows — `am broadcast APPWIDGET_UPDATE` is blocked on this
Samsung.

**A hypothesis the device disproved.** I first assumed the labels were being fit-shrunk to the
column and that the room test was over-measuring by ~25%. The logging showed `labelH=34.589` vs
`full=35.295` — a flat 0.98, no fit-shrink at all, because the `maxOf` is dominated by the 2-digit
forecast label (`77°` misses `isWideLabel`). The drawn-size measurement was kept anyway as the more
correct thing and to match desktop's existing `maxOf(aH, fH)`, but it is a no-op on this device.
Measure, don't estimate.

**Screenshots on the fold need a display id.** `adb exec-out screencap -p` prepends a
"Multiple displays were found" warning into the PNG stream and corrupts it. Use
`screencap -p -d <id>` with an id from `dumpsys SurfaceFlinger --display-id`.

## Watch for

- `MIN_DIFF_DEG = 0.5f` means a ~0.6° miss can now stack two near-identical numbers (e.g. `76.5°`
  over `77°`) whenever the room test allows. That is honest data, but if it reads as noise the floor
  is the dial, not the target fraction.
- The push is capped at one label height. A pair too compressed for even that is still rejected by
  `showBoth` — the cap is deliberately not a bypass.
