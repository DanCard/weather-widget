# Samsung Daily Header Temperature Scale

## Problem

The current-temperature text in the Daily forecast header is visibly too small on the wide Samsung
SM-F936U1 widget. A prior patch focused on launcher-option widths and a 450dp scale threshold, but
the live widget already reports 574x401dp and already selects the existing 1.35 wide-header scale.
Changing the width threshold therefore produced a pixel-identical header.

## Runtime Evidence

1. The affected visible widget is widget 345 in Daily/NWS mode, with 10 columns and a live size of
   574x401dp.
2. The renderer downscales that large widget bitmap from approximately 1740x1216px to about
   567x396px to remain under the 225,000-pixel RemoteViews bitmap budget. Its bitmap scale is about
   0.326.
3. `DailyForecastHeaderRenderer` multiplies the 1.35 wide-header scale by the full 0.326 bitmap
   scale, producing a 0.440 render scale. Launcher enlargement cancels the bitmap reduction, so the
   displayed current temperature remains only 18dp x 1.35 = 24.3dp.
4. Daily forecast temperature labels already use a 0.5 minimum bitmap scale specifically to remain
   legible after aggressive bitmap downscaling. On the same widget they therefore receive visibly
   more compensation than the header.
5. The current Samsung screenshot confirms the Daily header is already using the wide layout; the
   issue is bitmap text compensation, not missing widget-width state.

## Implementation

1. Resolve the Daily bitmap current-temperature scale in one helper used by both drawing and
   date-bound measurement.
2. When the header has qualified for wide scaling (`headerScale > 1`), floor the bitmap component at
   0.5 before applying the existing 1.35 factor. For widget 345 this changes the bitmap header scale
   from about 0.440 to 0.675.
3. Leave standard-width headers unchanged so tall, narrow widgets do not gain new crowding risk.
4. Apply the compensation only to the current-temperature paint. Keep the delta, icons, date,
   source label, margins, and placements at their existing scale.
5. Retain one VERBOSE render breadcrumb with bitmap, wide-header, and effective label scales for
   device diagnosis without adding persistent DB-log traffic.

## Verification

1. A focused unit test must prove that a 0.326 bitmap scale plus the 1.35 wide-header scale resolves
   to 0.675, while a standard-width header remains at 0.326.
2. Daily renderer tests, the full Android duration suite, and debug APK assembly must pass.
3. Install on the Samsung, repaint widget 345 in its existing Daily state, and verify the log reports
   `currentTempLabelScale=0.675` and the screenshot shows the larger temperature without overlap or
   unrelated header enlargement.
4. Confirm widget 345 remains Daily/NWS with date offset -1, hourly offset 0, and Wide zoom.
