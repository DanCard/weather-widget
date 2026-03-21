# Analysis of Click Zone Bug

The bug happens because the touch zones in `setupGraphDayClickHandlers` are set based on the *length* of the `days` list, not their logical columns on the screen.

When `WEATHER_API` doesn't have data for yesterday, `prepareGraphDays` skips it (`return@forEachIndexed`). This means instead of returning 9 items, it returns 8 items.
Inside the layout XML, the graph day touch zones (`R.id.graph_dayX_zone`) are `FrameLayout`s inside a `LinearLayout` with `layout_weight=1`.

If we only iterate `days.size` (8) and set 8 zones to `VISIBLE` (and the remaining 2 to `GONE`), the `LinearLayout` expands those 8 zones to fill the *entire width* of the screen. 
But visually, the graph renders them in columns 1 through 8 (skipping column 0) because `renderGraph` uses `day.columnIndex` to position the bar on the X axis.

So the physical touch zones (8 evenly spaced blocks) are misaligned with the visual bars (which are drawn as if there are 9 blocks, with the first block empty).
Furthermore, when attaching the click intent, `setupGraphDayClickHandlers` does:
`val intent = buildDayClickIntent(context, appWidgetId, index + 1, ...)`
Where `index` is `0..7` instead of the actual `columnIndex`.

## Fix Plan
1. Add `numColumns: Int` parameter to `setupGraphDayClickHandlers`.
2. Pass `numColumns` when calling `setupGraphDayClickHandlers` in `updateWidget`.
3. Update the logic inside `setupGraphDayClickHandlers` to:
   - Make exactly `numColumns` touch zones `VISIBLE` and the rest `GONE`.
   - Clear click intents for all zones by default by passing an empty `PendingIntent.getBroadcast` (since Kotlin might not allow null for `setOnClickPendingIntent` if the parameter is strictly non-null, although Android docs say it's nullable, in Kotlin it's safer to just provide an empty intent or a dummy intent). Actually, passing `null` is allowed: `public void setOnClickPendingIntent (int viewId, PendingIntent pendingIntent)` in Java means it's `PendingIntent?` in Kotlin if platform type. Let's just try `null` or a dummy. Actually `null` is fully supported by `RemoteViews.setOnClickPendingIntent` to remove the listener.
   - Iterate over `days`, and for each day, get `colIndex = dayData.columnIndex ?: index`.
   - Attach the click intent to `zoneIds[colIndex]`.
   - When building the intent, pass `colIndex + 1` instead of `index + 1` for the day index. And pass `colIndex` for the graph click intent request code.

Let's do this!
