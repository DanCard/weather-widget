# Tonight's rain label: don't ease left off the blank top of the next low's digits

## Report (user, 2026-11-15, Fold 4, Open-Meteo, daily view)

"Fold-4: 88% label for tonight rain chance label is mostly under today column. Can we get it more
centered between today and tomorrow column?"

## Evidence

Screenshot `screencap -d 4630946474867211650` (07:0x): `88%` sits under Today's `51.4°`, its ink
ending above Mon's `46°` ink and left of Mon's cloud icon.

Logcat `DailyRainLabelLayout` 07:06:49 (canvas px, ~2.62 px/dp):

```
date=2026-11-15 text="88%" home=(210.3,329.8) size=30.0x19.6 resolved=(194.0,329.8)
placement=home easedDp=(-6.2,0.0)
obstacles: Today icon 166.6,261.3,197.6,292.3  Today "51.4" 146.6,292.0,205.6,328.9  ° 205.6,292.0,217.6,310.4
           Mon icon 223.1,309.9,254.1,340.9    Mon "46" 215.6,340.5,249.6,377.4    ° 249.6,340.5,261.6,359.0
```

- The search left it at home (gap centre 210.3, box 195.3–225.3, top 329.8, bottom 349.4).
- `easeOffNeighbors` then slid it 6.2 dp (16.3 px) left, nearly the 30 % column limit.

## Root cause

`easeOffNeighbors.gaps(toInk = true)` trims the right digits' blank **descent** (bottom 22 %) but
not their blank **ascent** (top 20 %, `LOW_BLANK_ASCENT_FRACTION`, already used by
`recenterOverBlankLineBox`). Mon's `46` box starts at 340.5; its ink at 347.9. The label overlaps
the box by 8.9 px (> 1.5 dp, so "level") but its ink by 1.5 px (a graze, not level). Counting the
box, the right gap is −9.7 px, and the ease slides by `roomy − gap` = 6.56 + 9.7 = 16.3 px. Left of
the label nothing is level (Today's `51.4` ends above it), so nothing stops it.

## Proposed fix (Claude proposal, `:shared` `DailyRainLabelPlanner.easeOffNeighbors`)

In `gaps(toInk = true)`, trim the right digits' top `LOW_BLANK_ASCENT_FRACTION` as well as the
bottom descent. Only the "does it hug the right" decision uses `toInk`; the drop rows and left side
keep line boxes, as now.

Expected here: `46` no longer level; the right neighbour is Mon's cloud icon (gap −2.2 px), so the
ease slides `6.56 + 2.2` = 8.8 px: centre 201.5, **3.3 dp left of the gap centre instead of 6.2**, a
2.5 dp gap to the icon box (~3.3 dp to the cloud's ink).

Possible second step if that still looks too far into Today (user's call, not proposed by default):
a smaller roomy gap against an icon box, e.g. 1 dp → centre ≈ 205.5, 1.8 dp left of centre.

## Risk

The ease has approved tunings (2026-10-30 emulator `4%`, `44%`/`Trace`, 2026-11-14 `87%`). A label
that today eases off the blank top of a right low's digits will ease less. Any approved
`DailyRainLabelLayoutTest` case that moves will be listed, not silently re-baselined.

## Tests

- `DailyRainLabelLayoutTest`: this trace as a fixture — fails on current code (194.0), passes at
  201.5 ± 0.1.
- Existing ease fixtures unchanged, or listed.
- Verify on the Fold, the emulator and desktop by screenshot pixels.

## Addendum (2026-11-15): smaller gap off an icon

User after the fix alone (−3.4 dp): "Still prefer it move to be more centered." Added the optional
step: easing off a right neighbour that is an icon stops at `NIGHT_ICON_SIDE_GAP_DP` (1 dp); lows
keep `NIGHT_ROOMY_SIDE_DP` (2.5 dp). An icon is a box that is neither digits nor ° and not inside
`rightLowLabel` (older fixtures carry unflagged low boxes). Fold `88%`: centre 205.5, 1.8 dp left of
the gap centre. Emulator `88%` eases 2.2 dp (was 3.7). No approved fixture moved.
