# Night rain label: tuck under its own day's ° instead of the next day's low

## Report (user, 2026-11-14, Fold 4, NWS, daily view)

Tue night `.3in` sits too far right, under Wed's `47°`, and reads as a Wednesday label. Preferred:
tucked under the degree symbol of Tue's low `42°`.

## Evidence

Screenshot `screencap -d 4630946474867211650` at 11:54 confirms it: `.3in` is directly below `47°`;
the pocket under the ° of `42°` is empty.

Logcat `DailyRainLabelLayout` (canvas px, 2.62 px/dp):

```
date=2026-11-17 text=".3in" home=(364.1,335.1) size=27.0x19.9 resolved=(377.2,340.3)
placement=down_right offsetDp=(5.0,2.0) maxBottom=377.6 dayLabelTop=377.6
obstacles: Tue digits 316.0,344.8,350.0,381.8  Tue ° 350.0,344.8,362.0,363.3
           Wed digits 366.2,306.1,400.2,343.0  Wed ° 400.2,306.1,412.2,324.5
```

- Resolved box is x 363.7–390.7: 24.5 of its 27 px lie under Wed's digits (366.2–400.2).
- Tue's `42°` is the week's coldest low, so it sits on the day-name row: its line box ends at 381.8,
  4.2 px below the label floor (`dayLabelTop` 377.6).

Emulator (user: "same issue on emulator"; `emulator-5554` logcat 11:56, NWS):

```
date=2026-11-17 text=".3in" home=(304.9,384.2) size=27.0x19.9 resolved=(310.1,384.2)
placement=right recentered=true offsetDp=(5.0,0.0) maxBottom=430.7
obstacles: Tue digits 257.5,398.0,291.5,434.9  Tue ° 291.5,398.0,303.5,416.5
           Wed digits 306.3,348.6,340.3,385.5
```

Same shape: Tue's low ends 4.2 px below the floor; the pocket needs a bottom of 432.5, 1.8 px past
`maxBottom`; the label ends up 17.3 of 27 px under Wed's digits, 1.3 px below them.

## Root cause

Two things in `DailyRainLabelPlanner.placeNightLabel` (`:shared`, so Android and desktop alike):

1. **The pocket under the ° is below the search's floor.** To clear the Tue ° (bottom 363.3, 1.5 dp
   graze allowed) the label top must be ≥ 359.4, so its line-box bottom is ≥ 379.3 — 1.7 px (0.65 dp)
   past `maxBottom`. The search stops at top 356.1, where it is still side-on against the °. So no
   candidate straight down is clear.
2. **The search only minimises movement.** Right 5 dp + down 2 dp is clear (a 2.7 px graze under
   Wed's digits is "slight"), costs 18.3 px, and wins. Nothing in the score says "this spot is under
   the other day's number". Even with the floor fixed, the pocket costs 26.2 px and would lose.

## Proposed fix (Claude proposals, both in `:shared`)

1. **Pocket floor.** A candidate in the left low's ° pocket (overlapping that ° sideways, top below
   the ° box) may pass `dayLabelTop` by at most the label's blank descent (`height − baseline`) and
   `NIGHT_FLOOR_DEGREE_DROP_DP` (3 dp), and never below the left low's own line-box bottom. Same
   allowance `dropOffDegreeAtFloor` already uses; ink stays above the day name. Other candidates keep
   the floor as is.
2. **Own day before neighbour's.** In the search order (least overlap → least ° overlap → least
   movement), insert before "least movement": prefer a candidate that does not lie mostly under the
   right low's digits (more than half the label's width inside the right digits' box, and below it).
   Only decides between candidates that are already clear, so it cannot add overlap.

Expected for this case: centre x stays at home 364.1 (5 dp left of now), top ≈ 361.3 (8 dp lower):
left edge 0.6 px right of the `2`, top 2 px inside the ° box's blank lower part, line-box bottom
level with the `42` (381.2 vs 381.8). Trace gains `pocketFloorDp=` and `underRightLow=`.

Later steps (`easeOffNeighbors`, re-centre, ink/° drops, floor nudges) are unchanged; I will check
from the trace and pixels that none of them pulls the label back out.

## Risk

Rule 2 can move other labels that today rest under the right neighbour's digits when a clear spot
exists elsewhere within the existing limits (±30 % column, 12 dp down). The 2026-10-31 approved
placements are covered by `DailyRainLabelLayoutTest`; any that change will be listed for you rather
than silently re-baselined. If rule 2 moves approved cases, fallback is to apply it only when the
left low sits on the day-name row (this case).

## Tests

- `DailyRainLabelLayoutTest`: this trace as a fixture — fails on current code (resolved x 377.2),
  passes when centre is 364.1 and the top is below the Tue ° ink; plus a case where the pocket is too
  short for the label's ink, which must keep today's placement.
- `DailyRainLabelHostContract` (Android + desktop) unchanged; run `./scripts/unit-tests.sh`.
- Real fonts: extend `NightRainLabelSeparationInstrumentedTest` or a sibling with the Tue/Wed
  geometry; `./scripts/emulator-tests.sh -c` for that class.

## Verification

`installDebug` on the Fold, Pixel and emulator; screenshot each daily view and read the
`nightRainLabel position` lines for all night labels (before/after table). Rebuild and restart the
desktop app and check the same week there.

## Docs

Update the night-label section of the living arch doc that describes the daily rain labels.

## Outcome (2026-11-14)

- Approved by the user, who added: "Consider reducing font size to fit under degree symbol if that
  helps." Implemented as a fallback: a label still mostly under the next day's low is laid out again
  at `NIGHT_REDUCED_SCALE` and the smaller one is used only if that gets it out (`shrunkForOwnDay=`).
  Not needed on the Fold or emulator today: Android's 4.1 px blank descent already fits the pocket.
- Change from the proposal: pocket rows are tried only when everything above the floor left the label
  under the next day's low. Open to every search they moved the approved desktop Silurian "15%"
  (2026-10-31) from top 723 to 757.7.
- A pocket placement past the floor is final; `slideOffDegree` would otherwise push it 3 dp right.
- Fold: resolved (377.2,340.3) → (364.1,361.3); emulator (310.1,384.2) → (304.9,413.1); screenshots
  checked. Pixel and desktop do not hit the case and are unchanged. 7020 unit tests pass.
- Not done: no instrumented real-font test was added; no arch document describes night labels, so
  the rule is documented in the `layoutNight` / `placeNightLabel` KDoc only.

## Follow-up (2026-11-14): emulator Wed night "87%" in Wed's column

User: "87% night time rain label too much in wednesday column. Prefer centered between wed and
thur." Not caused by the change above (trace identical before and after: `resolved=(339.0,389.5)
easedDp=(-5.6,0.0)`). The search left it at the gap centre, 2 dp down; `easeOffNeighbors` then slid
it the full 30 % shift left because it "hugged" Thu "46°" — measured to Thu's digit line box (bottom
395.4) although the label's top (389.5) was already below the digit ink (387.3).

Fix: `easeOffNeighbors` decides whether the label hugs the right by the right digits' ink (bottom
`DIGIT_BLANK_DESCENT_FRACTION` trimmed). Left-side obstacles and the drop rows keep the line box:
trimming those too moved Thu night "44%" on the Fold and emulator and emulator "Trace" by 2–3 dp.
Result: emulator "87%" (339.0,389.5) → (353.7,389.5).

Labels that differ from the original build, all devices: Fold ".3in" (both widget sizes), emulator
".3in", emulator "87%", and emulator Fri-night "Trace" (98.0 → 92.7, 2 dp left, from the own-day
rule: it was mostly under Today's low). Pixel: none.
