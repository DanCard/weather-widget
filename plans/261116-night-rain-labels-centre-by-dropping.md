# Night rain labels: centre between columns by dropping lower

Follow-up to `plans/261116-night-rain-labels-ink-slide-and-floor-drop.md`. Fold 4, NWS, 2026-11-16.
User: "What do you think of moving .51in night rain label further down so that it can be better
centered between columns? 33% night time rain label not centered between columns, yes I would prefer
it centered."

## Evidence (Fold screenshot 10:5x, screen px; logcat `DailyRainLabelLayout` 10:48)

Column midpoints are taken from the day-name ink ("Tue"/"Wed", "Thu"/"Fri").

1. **Tue→Wed ".51in"**: ink x 764–849 (centre 806.5), y 1012–1041; midpoint ≈ 787.5 → **19 px right**.
   Trace: `home=234.3 resolved=242.1 placement=right offsetDp=(1.0,0) sideSlideDp=2.0`. It sits level
   with Tue's sun icon (ink right edge 750), so the search and `slideOffLeftNeighbor` pushed it right.
   Below the icon, the "43°" ink is 1062–1117 with its ° in the top half only; under the °
   (y ≳ 1090) the space is clear to x ≈ 742 (right edge of the "3"). The day names start at y 1160, so
   there is about 40 px of free height.
2. **Thu→Fri "33%"**: ink x 1025–1078 (centre 1051.5), y 1126–1147; midpoint ≈ 1057.5 → 6 px left,
   and its right edge touches the "4" of Fri's "42" (ink bottom 1139). The day-name ink starts at
   1160, 13 px below the label. The "4" is blank at bottom-left below its crossbar.

## Root cause

1. The search takes the least move. For ".51in", a 1 dp step right plus a 2 dp side slide costs less
   than dropping ~15 dp to the pocket under Tue's °, so it never gets there. A pocket is tried only
   when the label is under the right low (`underRightLow`), which is not the case here.
2. For "33%", every drop stops at the day-name **line box** top (`dayLabelTop`), whose top part is
   blank. Centring it would push it into the "42" at its current height.

## Proposed fix (Claude proposal, `:shared`)

1. **Centred-lower alternative**: after the existing steps, if the label ends more than
   `NIGHT_CENTRE_PREFER_DP` (proposed 2 dp) off the gap centre, try rows lower down (to the floor and
   the pocket floor) with the label at the gap centre, ±1 dp. Take the highest row whose ink clears
   every obstacle's ink by at least 1 dp. Keep the current spot when no such row exists.
   Trace `centredDropDp=(x,y)`.
   - ".51in": expected to land under Tue's °, about 3 px right of the "3".
2. **Day-name blank ascent**: let a night label's ink go down to 1 dp above the day-name **ink** (the
   host passes the day-name ink top, i.e. the line top plus its blank ascent), instead of stopping at
   the line top.
   - "33%": its ink would sit just below the "4"'s crossbar and above "Thu"/"Fri", centred.
3. Gates against moving approved labels: rule 1 only when the move off-centre came from an icon or
   digits, not a °. Neither rule applies to `NIGHT_CENTERED` or sunk labels.

## Risks

- Earlier the user rejected labels "way too low" (2026-06) and liked Sun-Mon's vertical spot
  (2026-10-31). Rule 1 only fires for labels more than 2 dp off centre; check the full
  before/after `resolved=` diff for unintended moves.

## Verification

1. Unit tests with today's two traces (fail on old code).
2. Install per serial; Fold screenshot crop; before/after `resolved=` diff on Fold and emulator.
3. Desktop restart and check its daily view.
