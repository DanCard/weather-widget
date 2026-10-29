# Daily graph: no axis padding, ink-based bottom gap (2026-10-29)

Follows [plans/261029-shared-daily-bottom-stack.md](261029-shared-daily-bottom-stack.md).

## Evidence

Desktop (canvas 1078×790, scale 2.72), Wed column: `45.8°` ink ends at y=775 and `Wed` ink starts at
y=822, a **47 px** gap. The log shows `DailyBottomStack ... lowLabelH=52.0 dayLabelH=41.0 dayLabelTop=732.7 graphBottom=612.3`.

| Part | px | Cause |
|---|---|---|
| Axis padding | ~17 | desktop pads 4% (≥1°) below the min; Android pads nothing |
| Below the `45.8°` digits | ~19 | the stack reserves the 52 px line box, but the ink is 31 px |
| `LOW_DAY_GAP_DP` | ~3 | 1 dp |
| Above `Wed` | ~8 | the line box starts above the capitals |

## Change (approved: remove the padding at both ends)

1. Add `:shared` `DailyAxisRange.of(temps)`: min/max of the drawn temperatures, with **no padding**,
   implausible values dropped (Android's existing filter), defaults 0..100, and a range of at least 1.
   Used by Android `DailyGraphLayoutResolver` (unchanged behavior) and by both desktop passes, draw and
   tap. That removes desktop's 4% pad at both ends and also gives desktop the plausibility filter.
2. `DailyBottomStack` gets `lowLabelBlankBelowPx` and `dayLabelBlankAbovePx` (default 0): the blank
   line-box space under the low digits and above the day-name capitals. The stack keeps an ink gap of
   at least `LOW_DAY_INK_GAP_DP` (3 dp) and reclaims any blank space beyond that:
   `reclaim = max(0, blankBelow + blankAbove − (3 dp − LOW_DAY_GAP_DP))`.
   With zero blanks nothing is reclaimed, so Android stays pixel-identical.
3. Desktop measures both blanks from the glyph outlines
   (`TextLayoutResult.getPathForRange(...).getBounds()`) in both the draw and tap passes.

## Tests

- `DailyAxisRangeTest`: no padding, implausible values dropped, defaults.
- `DailyBottomStackTest`: zero blanks leave the stack unchanged; blanks are reclaimed down to a 3 dp
  ink gap; the icon clamp follows the reclaim.
- Desktop test: the tap layout's `graphBottom` moves down by the reclaim, and the draw/tap passes agree.
- The Android sizing pin test still passes (pixel parity).

## Verify

Restart desktop, take a screenshot, and measure the ink rows again (target: about 8 px). The Fold
widget must be unchanged.
