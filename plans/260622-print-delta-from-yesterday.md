# Plan: "+0.4 from yesterday" delta label on the zoomed hourly graph

## Context

The user wants to answer "what was the temperature at this time yesterday?" at a glance.
Rather than a full time-machine UI, the chosen, well-scoped first step is a single small
label rendered into empty space in the **zoomed-in hourly temperature graph** reading e.g.
`+0.4 from yesterday` (current observed temp minus the actual temp at the same clock time
24 h ago), in **thermostat color**, at the **same font size as the existing staleness/age
indicator**. It must appear on **both Android and desktop**, with the decision logic in
`:shared` and both platforms delegating.

This is feasible from data we already store: the actual ("pink line") series is built from
observations blended across stations and spans ~144 h of context, so yesterday-at-this-hour
is always within the 6-day observation retention window. The only real constraint is that the
24 h-ago value is **outside the window-filtered `HourData`** the renderer sees, so it must be
computed upstream where the full series exists and passed down.

## Approach

Three pieces: a shared calculator (the number), a shared placement/format engine (text +
color + where it goes), and a thin per-platform draw call (reusing each platform's existing
staleness paint for exact font-size parity).

### 1. Shared: compute the delta (the number)

New pure object `shared/src/main/kotlin/com/weatherwidget/shared/graph/YesterdayDeltaCalculator.kt`:

```kotlin
object YesterdayDeltaCalculator {
    // Returns currentObservedTemp - actualTempAt(observedAt - 24h), or null if either side
    // is missing / no actual observation within tolerance of that hour.
    fun computeDelta(
        seriesPoints: List<ActualTemperaturePoint>, // full ~144h context series
        observedAtMs: Long?,
        currentObservedTemp: Float?,
        zoneId: ZoneId,
        toleranceMs: Long = 60 * 60 * 1000L,        // accept obs within ±1h of the target hour
    ): Float?
}
```

- Find the actual point nearest `observedAtMs - 24h` among points with `isActual && actualTemp != null`
  (mirror the nearest-within-tolerance logic the second exploration sketched). Interpolate between
  the two bracketing actual points if both exist for accuracy; otherwise nearest within tolerance.
- Return `null` when there is no qualifying yesterday observation (genuine data gap) → label hidden.
- Pure, plain-JUnit testable (no Android types). Add `YesterdayDeltaCalculatorTest.kt`.

Data source for `seriesPoints`: the full `ActualTemperatureSeriesResult.points` produced by
`ActualTemperatureSeriesBuilder.build()`. This already exists at the call sites below — it is
just not currently threaded to the renderer.

### 2. Shared: format + place the label (text, color, position)

New pure object `shared/src/main/kotlin/com/weatherwidget/shared/graph/YesterdayDeltaLabel.kt`
(co-located with `FetchDotLabel`/`ValueLabelEngine`, same style):

```kotlin
data class DeltaLabelPlacement(
    val text: String,        // "+0.4 from yesterday" / "-1.2 from yesterday" / "+0 from yesterday"
    val xLeftPx: Float,
    val yBaselinePx: Float,  // Android baseline; desktop converts via ascent
    val bounds: GraphRect,
    val colorArgb: Int,      // thermostat color
)

object YesterdayDeltaLabel {
    fun format(delta: Float): String          // sign always shown, one decimal (matches "+0.4")
    fun colorArgb(currentTemp: Float): Int     // = TemperatureColorModel.tempToColorArgb(currentTemp)

    // Gate + placement. Returns null when suppressed (no delta, or span > maxSpanHours).
    fun place(
        delta: Float?,
        currentTemp: Float?,
        spanHours: Long,
        graphRect: GraphRect,                  // drawable plot area
        drawnLabelBounds: List<GraphRect>,     // already-placed labels/icons/fetch-dot
        curveSampler: (xPx: Float) -> Float?,  // curve y at x, to know where the line is
        metrics: LabelTextMetrics,             // measured width/height of the text
        maxSpanHours: Long = FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN, // 12
    ): DeltaLabelPlacement?
}
```

- **Gate**: reuse `FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN` (12 h) so the delta label shows under
  exactly the same zoom condition as the staleness/age label. Suppress when `spanHours > max` or
  `delta == null`.
- **Empty-space finding**: no reusable gap-finder exists today, so add a small one here using the
  existing `GraphRect.intersects()` and `GraphLabelPlacementUtils.maxVerticalOverlap()`. Scan a
  handful of candidate x-anchors across the visible width; at each, prefer the larger vertical gap
  between the curve (`curveSampler`) and the top/bottom plot edge; reject candidates that collide
  with `drawnLabelBounds`. Pick the first non-colliding candidate with the most clearance. Keep it
  simple and deterministic (no randomness — vary by index only, per repo conventions).
- **Color**: `TemperatureColorModel.tempToColorArgb(currentTemp)` — colored by the current observed
  temperature so it harmonizes with the curve color at "now". (Default decision; see Decisions.)
- Pure, plain-JUnit testable. Add `YesterdayDeltaLabelTest.kt` (format strings, sign, gate at the
  12 h boundary, collision avoidance, null-when-no-delta).

### 3. Per-platform: thread the value down and draw

**Android**
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt`: after building the series, call
  `YesterdayDeltaCalculator.computeDelta(...)` and carry the resulting `Float?` into the render
  inputs (extend the result/inputs struct already produced here).
- `app/.../widget/TemperatureGraphRenderer.kt` (`renderGraph`): accept `deltaFromYesterday: Float?`,
  call `YesterdayDeltaLabel.place(...)` after temperature labels are placed (so `drawnLabelBounds`
  is populated), then `drawText` using the **existing `stalenessTextPaint`** (set its color to
  `placement.colorArgb`). This guarantees identical font size/shadow to the age label
  (`STALENESS_LABEL_SIZE_DP = 12f`, `TemperatureGraphStyle.kt`).

**Desktop**
- `desktop/.../TemperatureGraph.kt`: compute the delta where the actual series is built
  (`fetchDotPoint?.actualTemp` is the current temp, `observedAt` the time), call the same shared
  `place(...)`, and draw with `drawText`/`textMeasurer` at the same `(9 * scale).sp` style used for
  the age label, overriding color to `placement.colorArgb`.

Both platforms pass their own measured `LabelTextMetrics` so the shared engine stays platform-free,
consistent with `TemperatureLabelEngine`/`ValueLabelEngine`.

## Critical files

- NEW `shared/src/main/kotlin/com/weatherwidget/shared/graph/YesterdayDeltaCalculator.kt` (+ test)
- NEW `shared/src/main/kotlin/com/weatherwidget/shared/graph/YesterdayDeltaLabel.kt` (+ test)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureHourDataBuilder.kt` — compute + thread delta
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — place + draw (reuse `stalenessTextPaint`)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` — compute + place + draw
- Reuse (no change): `TemperatureColorModel.kt`, `FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN`,
  `GraphRect.kt`, `GraphLabelPlacementUtils.maxVerticalOverlap`, `TemperatureGraphStyle.STALENESS_LABEL_SIZE_DP`.

## Decisions / defaults (adjustable on review)

- **Color source**: thermostat color of the **current observed temp** (not the delta sign). Rationale:
  "thermostat color" = the `TemperatureColorModel` gradient, and the label conceptually sits at "now".
- **Precision**: one decimal with explicit sign (`+0.4`, `-1.2`, `+0.0`), matching the user's example.
- **Anchor time**: `observedAt - 24h` (the fetch-dot observation time), so it tracks the same point
  the staleness label describes.
- **Visibility**: only when zoomed (`spanHours ≤ 12`) AND a yesterday actual exists within ±1h.

## Verification

1. `./gradlew testDebugUnitTest --tests "*YesterdayDelta*"` and `:shared` tests for both new engines.
2. Build + install Android: `./gradlew installDebug`. On the emulator, add the widget, zoom the hourly
   graph into the narrow/day view; confirm `±X.X from yesterday` appears in an empty region, in thermostat
   color, same size as the age label; confirm it disappears when zoomed out past 12 h. Pull a screenshot
   (PNG→JPG per CLAUDE.md) to read it.
3. Desktop: rebuild + restart via `scripts/buildStart.sh`; open the popup, zoom into the hourly graph,
   confirm parity (same text/placement/color behavior as Android).
4. Cross-check the number: query the DB (`python3 scripts/backup_databases.py` → `sqlite3`) for the actual
   observation ~24 h before the current observation time and confirm `current - yesterday` matches the label.
5. Sanity: induce a yesterday data gap (or pick a location/time with no obs) and confirm the label hides
   rather than showing a wrong/zero value.
