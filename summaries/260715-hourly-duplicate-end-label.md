# Hourly graph: duplicate `67°` / `67°` forecast labels at the right edge

## Problem

Reported on the emulator, then confirmed on both emulators, Pixel, Samsung **and desktop**.

The hourly temperature graph drew **two identical labels stacked at the right edge**. The debug
logging named them immediately:

```
idx=39, role=LOW,  temp=67.0, series=forecast, #BBBBBB, y=375, placedAbove=false
idx=52, role=END,  temp=67.0, series=forecast, #BBBBBB, y=298, placedAbove=true,
                                        reason=above+curveFit(30.3px), displacementSteps=1
```

Three symptoms, worst first:

1. **The pair is redundant.** The forecast flatlines at 67° from ~00:00 onward. `LOW` opens that
   plateau (idx 39), `END` closes it (idx 52) — same value, same series, same grey.
2. **The upper label lies about its series.** Curve-fit displaced it 30.3px up off the dashed
   forecast line and parked it on the pink *actual* curve, so it reads as an actual-series label
   while being forecast grey. The most misleading thing on screen.
3. **The pink actual line has no end label at all.** Its only labels (`ACTUAL_HIGH 70.9°`,
   `ACTUAL_LOW 69.3°`) are on the left half; nothing marks where it terminates (~66–67°).

Sequel to `260611`-era work — see memory `end-label-redundancy-suppression`, which introduced the
`REDUNDANT_PAIR_PX = 64f` budget this change corrects.

## Finding: it was never a device or geometry quirk

Two renders of the same widget, one second apart, disagreed:

```
08:27:37.441  LabelSuppressed: role=END idx=52 reason=REDUNDANT     ← offset=-8, correct
08:27:38.478  LabelAccepted:   role=END idx=52 ... val=67.0         ← offset=-9, the bug
```

The user independently reported the desktop copy **"went away after resizing the window"**. Both
facts say the same thing: a threshold being straddled. Resizing changes `widthPx`; panning changes
the time fractions. A rule whose outcome flips on a one-step pan is a knife-edge, not a glitch.

`:shared` ownership explains the five-surface spread: `TemperatureLabelResolver` is common code.

## Finding 1: redundancy was measured where the label is not drawn

Run-centered roles (`LOW`/`HIGH`/`FORECAST_*`/`PAST_FORECAST_*`/`LOCAL`) are **not drawn at their own
index**. `centerOfRun` walks the equal-value run and draws the label at its **midpoint** — a flat 67°
plateau gets one label in the middle, not one at its left edge.

`pixelGapByTime`, however, measured from the label's **own index timestamp**.

With `visualWindow = 21:00 → 01:00` (240 min) and `widthPx=470`:

| | `LOW` idx 39 | `END` idx 52 |
|---|---|---|
| Timestamp | 00:00 → x = **352.5** | 01:00 → x = 470 |
| Actually drawn (`centerOfRun` over the idx 39–52 plateau) | (352.5+470)/2 = **411.25** | 470 |

The logged `x=411.25` matches `centerOfRun` exactly. So the gate compared `470 − 352.5 = 117.5px`
against the 64px budget and said "far apart, keep both" — while the label was really drawn 58.75px
away, well inside it.

Every non-plateau label is unaffected, which is why `ACTUAL_LOW` (idx 14) and `ACTUAL_HIGH` (idx 27)
land at *exactly* their time-linear predicted x. Only flat runs diverge.

The irony: the plateau the `LOW` is centered on **ends at the `END` label's own anchor**, so a longer
flat run drags `LOW` *toward* `END` while the gate keeps reporting the uncentered distance.

## Finding 2: distance is the wrong criterion (the fix above is insufficient)

Fixing the measurement alone left it width-dependent. Live log from a **fixed build still showing the
bug** on emulator-5556 (`widthPx=584`, window 20:00→00:00 = 240min):

```
LabelAccepted: displayed="68" t=23:00 role=LOW  idx=40
LabelAccepted: displayed="68" t=00:00 role=END  idx=53
```

Run-centered anchor = (180+240)/2 = 210min → **73px** from `END`. Just over the 64px budget, so it
survived — where the Pixel's 58.75px fell inside and suppressed correctly.

A `LOW` and `END` on the **same flat run of the same series with the same value** are *one plateau
labeled twice*. That is redundant at **any** distance, and a pixel budget cannot express it. Chasing
it with a bigger budget would only move the knife-edge.

## What changed

`shared/.../graph/TemperatureLabelResolver.kt`:

1. **`runBounds(temps, idx)`** — the equal-value run walk, extracted. Now shared by `centerOfRun` and
   the new `anchorMinutes`, so the drawn position and the measured position cannot drift apart again.
   (`centerOfRun` previously inlined the walk with a bare `0.01f`; now `RUN_EQUAL_EPSILON`.)
2. **`anchorMinutes(hours, idx, role, temps)`** — minutes to where the label is actually **drawn**:
   the run midpoint for `RUN_CENTERED_ROLES`, the index otherwise. The renderer maps x linearly in
   time, so a time midpoint is exactly the x midpoint `centerOfRun` averages.
3. **`pixelGapByTime`** now takes both roles + both series and measures anchor-to-anchor.
4. **Same-run rule** in the `START`/`END` branch — the real fix:
   ```kotlin
   if (tRole !in ACTUAL_DISPLAY_ROLES && tIdx in runBounds(labelTemps, idx)) return true
   ```
   Index-based, so it also holds for geometry-less unit-test callers. The 64px budget still governs
   pairs on *different* runs.

`nearEnough` resolves each target's role via `resolveExtremaRole` rather than assuming it from the
list it came from, so it applies the renderer's own precedence.

Symptoms 2 and 3 from the Problem section are **not addressed** — see Follow-ups.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelSuppressionTest.kt`

## Verification

- `:shared` — **495 tests, 0 failures**, including the pre-existing `samsungFlatCurveHours` A/B tests
  that guard the original pixel-budget behavior (END retained at widthPx=567, decluttered at 120).
  The same-run rule leaves them alone because their pair is not on one run.
- Two new tests, both **falsified before being trusted**, each covering a distinct mechanism:
  - `END is suppressed when the daily LOW is drawn centered in a flat run reaching the right edge`
    (widthPx=470, the Pixel geometry) — fails when `anchorMinutes` ignores run-centering.
  - `END on a flat run is suppressed even when the run-centered LOW is beyond the pixel budget`
    (widthPx=584, the 5556 geometry, 73px) — fails when the same-run rule is disabled, while the
    470px test still passes. Exactly one failure each way.
- Device: installed on all 4; user confirmed fixed on Pixel, then on emulator and Samsung.

## Two process notes worth keeping

**My first hypothesis was wrong.** I proposed that `role=END reason=EXTREMA` meant the candidate had
"arrived through the EXTREMA door" and skipped the gate. `reason` is just a log string; the gate runs
for every candidate and had returned false on its merits. Reading the code beat reading the log line.

**My first regression test passed for the wrong reason.** It went green *with the fix disabled*: the
fixture's `END` was being dropped far upstream at `deduplicateAnchors` and never reached the gate,
because `isActual` stopped at idx 30 and `transitionX` was null. Its `dailyLowIndex == 39` guard
passed, which is exactly what made it look trustworthy.

Dumping `extrema` against production's logged values exposed it — the fixture had
`forecastLow=39, pastForecast=-1/-1, actualDailyLows=[]` where production had
`forecastLow=52, pastForecastLow=39`. It only reproduced once the fixture matched production's
*extrema signature*, not merely its shape: `isActual` true throughout and `transitionX=1312`
(deliberately beyond `widthPx=470` — the whole window is in the past, observations continue past the
right edge).

Lesson: a graph fixture that looks right can still be wrong upstream of the code under test. Assert
the extrema, and make the test fail before believing it.

## Follow-ups

- **The actual line still has no end label** (symptom 3). The right edge is all forecast labels;
  nothing marks where the pink line terminates. `ACTUAL_END` exists as a role and resolved to idx 52
  here, but lost to `END` in `resolveExtremaRole`'s precedence. Now that the duplicate forecast label
  is gone there is room for it — likely the right follow-up, and probably what the user wanted the
  upper `67°` to be.
- **Curve-fit can still park a forecast label on the actual curve** (symptom 2). Displacement is not
  series-aware, so a displaced grey label can land on the pink line and misread. Latent whenever the
  two lines converge.
- **Desktop needs a rebuild** to pick this up (`./gradlew :desktop:createDistributable`).
- Not committed.
