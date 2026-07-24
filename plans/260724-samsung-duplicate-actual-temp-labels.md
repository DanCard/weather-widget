# Fix Samsung duplicate 62.5 degree actual labels

## Evidence

On SM-F936U1, widget 345 in narrow hourly-temperature mode rendered two pink `62.5°`
labels. Logcat recorded them as:

1. `ACTUAL_LOW` at 23:40, raw observed value `62.48984`.
2. `ACTUAL_HIGH` at 00:00, raw observed value `62.511925`.

The standard formatter rounds both values to `62.5`. The two points are 20 minutes apart in a
three-hour, 567px view, so their drawn anchors are within the resolver's 64px close-pair budget.
Current `checkRedundantPairSuppression` deliberately keeps every `ACTUAL_HIGH` and `ACTUAL_LOW`,
which is correct for distinct values but lets this visually redundant same-text pair through.

## Change

1. Promote the graph-left `actualHighIndex` and a right-edge `actualLowIndex` to actual-label
   candidates. They are the true maximum/minimum of the visible observed curve; this capture's
   values are 63.596783 at 23:00 and 62.43284 at 00:20.
2. For an observed slice that starts at the graph's left edge, crosses midnight, and spans at most
   three hours, omit per-day actual high/low candidates. The short slice is not a pair of meaningful
   daily extrema; its only actual labels are the true observed endpoints.
3. Include the endpoint extrema in the coincident forecast/actual-label path, so they are also drawn
   when the forecast curve owns their indexes.
4. Add/update shared regressions for the capture and right-edge actual lows, asserting the 63.6
   left-edge actual high, 62.4 right-edge actual low, and no incorrect 62.5 labels.
5. Run shared tests, install the debug build, and verify the Samsung screenshot and logcat.
