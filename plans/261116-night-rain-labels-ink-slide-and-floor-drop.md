# Night rain labels: ink slide uses the label's ink; floor drop beside the next low

Fold 4, NWS, 2026-11-16. User: "Wed-thur night rain label of 94%: would like it more centered between
columns. 33% night rain label between Thur and Fri. Would like it more centered, can be done by moving
it down a little."

## Evidence (Fold logcat `DailyRainLabelLayout`, 10:27; screenshot 10:42)

1. **Wed→Thu "94%"**: `home=(344.5,316.6) resolved=(331.4,316.6) offsetDp=(-1.0,0.0) inkSlideDp=-4.0`.
   On screen it sits under Wed's column, pressed against ".51in", well left of the gap.
   `slideOffDigitInk` judged it level with Thu's "44" ink: label box 316.6–336.3 vs digit ink top
   325.3 + 20 % × 36.9 = 332.7 → 3.6 px overlap → slide 4 dp left. The label's box bottom is its
   line-box descent; "94%" has no descenders, its ink ends at the baseline (~4 px higher). On screen
   the "94%" ink clearly ends above the top of "44".
2. **Thu→Fri "33%"**: `home=(399.6,349.1) resolved=(397.0,361.9) easedDp=(-0.5,4.0) floorEaseDp=(1.5,0.9)`,
   bottom 376.7 vs floor (`dayLabelTop`) 375.4. On screen it reads "33%42°": level with Fri's digits,
   with clear space between it and the "Thu"/"Fri" day-name ink below.

## Root cause

1. `slideOffDigitInk.levelWithInk` measures the label from `top` to `top + height`. Every other ink
   test trims the *low's* blank line box, but none trims the *label's* blank descent.
2. Every drop step stops at the floor (or 0.5 dp past it via `easeOffLeftLowAtFloor`), so a label
   wedged beside the next day's low on the floor cannot use its own blank descent.

## Proposed fix (all in `:shared`; both platforms pick it up)

1. `slideOffDigitInk`: take `inkBottom = top + baseline` (new `baseline` param) for the label's level
   test. Expected: "94%" `inkSlideDp` −4.0 → 0, centre ≈ home − 1 dp.
2. New last-floor step `dropBesideRightLowAtFloor` (Claude proposal): a label on the floor that is
   level with the RIGHT low's digit ink drops until its ink bottom (baseline) reaches the floor,
   i.e. by up to its own blank descent (`height − baseline`), never past
   `NIGHT_FLOOR_DEGREE_DROP_DP`. Trace `floorRightDropDp=`. Does not change the 0.5 dp
   `easeOffLeftLowAtFloor` value the user picked for the left-low case.

## Verification

1. Unit tests in `:shared` with the two logged obstacle sets (fail on old code): "94%" no ink slide;
   "33%" drops by blank descent. `./scripts/unit-tests.sh`, read the per-module summary.
2. `installDebug` per serial (Fold, Pixel, emulator); before/after diff of every `resolved=` per
   device (old pid vs new pid) to catch moved approved labels; Fold screenshot crop.
3. Restart desktop app and check its traces for any moved label.
4. Update `night_rain_label_degree_collision` memory.
