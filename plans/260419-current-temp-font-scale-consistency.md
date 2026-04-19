# Current Temp Font Scale Consistency

## Goal
Make the daily widget header current temperature visually consistent with bitmap-rendered daily high labels across devices with different Android font scale settings.

## Evidence
1. Pixel 7 Pro reports `font_scale=1.15`, so the `TextView` current temperature renders larger.
2. Samsung SM-F936U1 reports `font_scale=0.8`, so the `TextView` current temperature renders smaller.
3. Daily high labels are drawn into a bitmap with `dp`-derived `Paint.textSize`, so they do not follow system font scale.

## Implementation
1. In daily view rendering, explicitly set `R.id.current_temp` with `COMPLEX_UNIT_DIP` instead of relying on the layout's `26sp`.
2. Use the same `dp`-based size for the header width calculation that decides whether the date can fit beside the current temperature cluster.
3. Add Robolectric coverage proving the daily current temp size calculation is independent of `scaledDensity`.

## Verification
1. Run focused tests for `DailyViewHeaderDatePlacementTest`.
2. If needed after install, verify screenshots/logcat on Pixel and Samsung to confirm the current temp no longer flips relative size against daily highs due only to font scale.
