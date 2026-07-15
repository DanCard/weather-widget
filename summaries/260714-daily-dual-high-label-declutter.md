# Daily view: declutter today's dual high labels (actual vs forecast)

## Problem

On the Samsung fold, today's column draws the actual high (pink `89.4°`) and the forecast high
(yellow `87°`) nearly on top of each other.

Measured from a device screenshot (fold main display, density 3.03):

- miss = 2.4°, graph scale ≈ 14px/° → the two labels are only ~33px apart vertically
- label box ≈ 70px tall → ~37px (~53%) overlap

`DualHighLabel.MAX_OVERLAP_FRACTION = 0.6` permits this, so the gate is behaving as written. Both
labels sit at a fixed 8dp above their own bar top (`HIGH_LABEL_OFFSET_DP`), and today's forecast
label draws at `centerX + tripleBarOffset` — only 8dp of horizontal separation, so the boxes are
also ~80% horizontally coincident. There is no horizontal room to spend: `89.4°` measures ~142px
against a ~125px column at 10 days.

### Finding: the size asymmetry is accidental

The forecast label draws *larger* than the actual, but nothing intends that. `89.4°` has 3 digits
so `DualHighLabel.isWideLabel` shrinks it 5% (`WIDE_LABEL_FONT_SCALE`); `87°` has 2 digits so it
shrinks not at all. The user's read ("forecast is already big compared to actual") is correct and
is a side effect of digit count, not a design choice.

### Finding: a naive nudge can flip label order

The forecast is not always the *lower* of the two — when a forecast runs hot, its label sits above
the actual. Lowering "the forecast" unconditionally would push the two labels *together*, and with
~11dp of nudge against a ~2° (27px) difference they could cross — printing the yellow number below
the pink one while the yellow bar sits above it. So the nudge must be **direction-aware**: each
label moves *away* from the other (lower-valued label goes down, higher-valued goes up). For the
reported case this reduces to exactly the requested behavior.

## What changed

User priority order (most aggressive first). New constants live in `shared/.../graph/DualHighLabel.kt`
so the geometry is one pure decision, applied by both renderers.

1. **Reposition the forecast label** — `DUAL_LOWER_BELOW_BAR_DP = -5f`. The big lever is giving up
   the whole normal 8dp gap (~27px of the ~37px overlap); the label's bottom ends up 5dp *above* its
   own bar top. Landed at `+1f` (1dp below the bar top), then retuned twice on device — see Retune.
2. **Shrink the forecast label** — `DUAL_FORECAST_FONT_SCALE = 0.82f`, stacking with the existing
   `TWO_LABEL_FONT_SCALE` (~0.80 net). Corrects the accidental asymmetry above, and doubles as the
   real declutter lever: the label's bottom is pinned and the text grows upward, so shrinking moves
   its top away from the actual. Applies to the forecast *role*, not to whichever label is lower.
3. **Raise the actual label a little** — `DUAL_UPPER_PUSH_UP_DP = 2f` (~6px) beyond its normal gap.
   Kept deliberately small; past raises read as exaggerated.

`bottomOffsetsDp(actualHigh, forecastHigh, normalGapDp)` returns each label's bottom edge relative
to its own bar top. Items 1 and 3 are stated as **positions, not roles**: the *lower-valued* label
takes the below-bar spot and the *higher-valued* one lifts. See the order-flip finding above — a
role-based version is what the falsification test rejects. Item 2 is genuinely role-based.

Item 1 is an absolute target rather than a delta because the renderers use different normal gaps
(Android `HIGH_LABEL_OFFSET_DP = 8`, desktop `DUAL_NORMAL_GAP = 3`); a shared delta would land them
in different places.

Applied only in the dual-label case; single-label days keep their true above-the-bar position. Net
≈ 41px of clearance against ~37px of overlap, so the labels just separate.

`MAX_OVERLAP_FRACTION` stays at `0.6`. The room test is fed the *adjusted* baselines so it measures
what is drawn; because the labels move apart, no day that shows two labels today loses one.

**Desktop reached full parity** (user decision): it now takes `TWO_LABEL_FONT_SCALE` (it previously
skipped it) and the forecast shrink, and `LOWER_DUAL_LABEL_FONT_BOOST = 1.08f` — which boosted the
*lower* label, the opposite of item 2 — is deleted.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/DualHighLabel.kt` — constants + `bottomOffsetsDp`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — `resolveHighLabelPlan` (baselines), `drawDayBars` (font)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` — same, via `dualTop`

## Retune (same session, on device)

Two rounds of user feedback against the fold, both on the forecast label:

1. "The 87 still looks big — reduce the font more and raise it up a bit."
   `DUAL_FORECAST_FONT_SCALE` 0.90 → **0.82** (gains ~5px of clearance);
   `DUAL_LOWER_BELOW_BAR_DP` +1f → -1f (costs ~6px) — roughly clearance-neutral.
2. "Still overlapping the forecast vertical bar significantly — try raising it 6dp."
   `DUAL_LOWER_BELOW_BAR_DP` -1f → -7f, settled at **-5f**. Confirmed good on device: the bars now
   start below the label instead of running through it.

I twice predicted this label was already clear of its own bar and was twice wrong on the device.
Worth knowing why, for the next tuner:

- The label's bottom edge does sit ~1dp above the *forecast* bar's top, as the math says. But today
  is a **triple bar**: the taller neighbours at ±`tripleBarOffset` (8dp) pass through a label that
  is far wider than 8dp, so they cross it wherever it sits.
- The dark shape behind the `8` is the faint **ghost bar** (`ghostLineHigh` → `solidLineHigh`,
  drawn at `GHOST_BAR_ALPHA`), darkened by the label's own black outline. Today's `89.4°` IS the
  ghost high — `effectiveHigh()` takes max(observed, forecast, ghost) — so the ghost bar reaches
  up past the forecast label by construction.

Lesson: on today's column, "does the label clear the bar" cannot be reasoned about from the
forecast bar alone. Measure on device.

## Verification

- `DualHighLabelTest` (12 tests, green): labels move apart never together in both orientations;
  order never flips even at `MIN_DIFF_DEG` on an absurdly compressed graph; forecast draws smaller
  than a wide actual. **Falsified** — swapping in the naive role-based nudge fails 3 of them.
  (The order-flip test initially passed vacuously under the naive version because it only covered
  the actual-warmer orientation; it now asserts both.)
- `:app:testDebugUnitTest` daily-renderer + label-placement suites: 71 tests, green.
- `:desktop:compileKotlin` green.
- Device: user installed on the Samsung fold and confirmed. Screenshot shows the two labels cleanly
  separated, `87°` visibly smaller and riding its bar tops.

## Follow-ups

- **`89.4°` now grazes the "Tue 14" day header** on the fold. It was already touching before the
  change; the 2dp raise closed the remaining gap. Up is the expensive direction (rain % and header
  live there) — if this bothers, the lever is `DUAL_UPPER_PUSH_UP_DP` back toward 0, since item 1
  is doing nearly all the work anyway.
