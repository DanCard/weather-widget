# Night rain label: open a small gap when the own-day pocket presses it against its low

## Report (user, 2026-11-15, Fold 4, Open-Meteo, daily view)

"There are several cases where night label is up against text at left and lots of room to the
right. In this case can we move the night rain chance label a few pixels to the left? Example:
fold-4 97% label looks adjoined to next Tuesday low of 45 degrees."

The example's crowding is on the label's left (`45°`) and the room is on its right, so this plan
moves it **right**. Direction to confirm with the user.

## Evidence

Screenshot `screencap -d 4630946474867211650` at 06:51: Tue→Wed night `97%` reads `45°97%`.
Ink column scan of the crop: the `5` and the `9` touch (~2 screen px apart). Wed's `50°` ends well
above the label; nothing is level with it on the right.

Logcat `DailyRainLabelLayout` 06:47:30 (canvas px, 2.62 px/dp):

```
date=2026-11-17 text="97%" home=(313.9,334.0) size=30.0x19.8 resolved=(313.9,360.3)
pocket=true placement=down offsetDp=(0.0,10.0) overlapDp=1.2 underRightLow=false
obstacles: Tue digits 265.8,344.8,299.8,381.8  Tue ° 299.8,344.8,311.8,363.3
           Wed digits 316.0,303.2,350.0,340.1  Wed ° 350.0,303.2,362.0,321.7
```

- Label box x 298.9–328.9: its left edge is 0.9 px inside the `45` box, which ends at the `5` ink.
- Wed's digits end at 340.1; the label top is 360.3, 7.7 dp lower.

## Root cause

Yesterday's own-day pocket (`730b42e55`, `plans/261114-night-rain-label-own-degree-pocket.md`) put
the label under Tue's `°`. A pocket placement is **final**, so `easeOffNeighbors` and the ink-slide
steps never run, and nothing opens a gap to the `45`. The search only moved it straight down.

## Proposed fix (Claude proposal, `:shared` `DailyRainLabelLayout` / `DailyRainLabelPlanner`)

After a pocket placement, if the label is level with the left low's digits and the gap between
them is under `NIGHT_POCKET_SIDE_GAP_DP` (1.5 dp), slide right until the gap is 1.5 dp:

- at most `NIGHT_POCKET_SIDE_SLIDE_MAX_DP` (2 dp);
- only if the slid box adds no overlap with any obstacle and stays inside the canvas edge margin;
- the "mostly under the next day's low" rule does not block it while the label's top is at least
  `NIGHT_ROOMY_SIDE_DP` (2.5 dp) below the right digits' bottom: then it is not "under" that number.
  Yesterday's case (label 1.3 px below Wed's digits) still can't slide under them.
- trace gains `pocketSlideDp=`.

Expected here: +4.8 canvas px (1.8 dp, ~4 screen px); left edge 1.5 dp clear of the `5`, still
under Tue's `°`, 7.7 dp below Wed's `50`.

Non-pocket placements are unchanged (they already go through `easeOffNeighbors`).

## Risk

The 2026-11-14 pocket fixtures must keep their placement (there the right digits are directly
above, or the gap is already ≥ 1.5 dp). Any approved `DailyRainLabelLayoutTest` case that moves will
be listed, not silently re-baselined.

## Tests

- `DailyRainLabelLayoutTest`: this trace as a fixture — fails on current code (centre 313.9), passes
  at ≈ 318.7 with a 1.5 dp left gap.
- Boundaries: gap already ≥ 1.5 dp → no slide; right digits within 2.5 dp above → no slide; slide
  that would hit an obstacle → no slide.
- Verify on the Fold, the emulator and desktop by screenshot pixels, not box contact.

## Addendum (2026-11-15, after approval): left icons, and the shrink check

User, same session: "Same issue on emulator: 97% night time rain label, is right next to cloud
icon. Can move a few pixels to the right."

- emulator-5554 trace: `pocket=false placement=down`, shrunk 26.0x16.9 at (256.1, 384.0), 4.1 px into
  Tue's cloud icon box (slight by area, so the search accepted it); `easeOffNeighbors` slides right
  only with something level on the right, and Wed's `50` ends 5.5 dp above.
- The slide became `DailyRainLabelPlanner.slideOffLeftNeighbor`: any left neighbour level with the
  label (icon or low digits, not a °) on every non-sunk placement, pocket included. Sunk labels keep
  `slideRightAfterSinking` (the approved 2026-10-31 Fold "40%" did not move). Trace: `sideSlideDp=`.
- `halfUnderRight` (which decides the 0.85 shrink retry) is judged before the slide: counting the
  slide flipped the emulator label back to full size on the device.

Results (screenshot pixels): Fold `97%` +1.8 dp, clear of `45`; emulator `97%` +2.0 dp, shrunk, clear
of the cloud; desktop history `20%` +2.0 dp. Desktop NWS Tue `.45in` +2.0 dp but still touches the
sun's right ray: it needed 4.7 dp, which would put it under Wed's `47°` 2.5 dp above. Left for the user.

Moved approved fixtures: the three 2026-11-14 `.3in` pocket cases now sit 1.5 dp right of Tue's
digits (+0.5 to +1.5 dp), still under Tue's °.
