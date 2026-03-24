# Plan: TemperatureGraphRenderer Cleanup & Refactor

## Context
Code review of `TemperatureGraphRenderer.kt` (977 lines) identified a monolithic 790-line `renderGraph()` method, per-render Paint allocation, redundant computation, dead code, and style inconsistencies. User approved fixing all findings.

## Critical Files
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` — primary target
- `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt` — shared utilities (read-only reference)
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt` — sibling pattern reference
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt` — sibling pattern reference

## Implementation Steps

### Step 1: Quick Wins (Low Risk)
1. **Remove dead aliases** — Delete `originalCurvePaint` (L258), `smoothedActualOrForecastTemps` (L398)
2. **Rename `smoothedTruthTemps`** → `truthTemps` (it's no longer smoothed)
3. **Rename `smoothedLabelTemps`** → `labelTemps` (also not smoothed)
4. **Fix redundant smoothing** — L373 re-computes `smoothValues(rawForecastTemps, 1)`. Reuse `smoothedForecastTemps` instead.
5. **Fix `LocalDate.now()`** — L854: Replace with `currentTime.toLocalDate()`
6. **Fix `Math.abs()`** — L599, L696, L709: Replace with `kotlin.math.abs()`
7. **Extract ghost line threshold** — Add `private const val MIN_GHOST_LINE_DELTA = 0.1f` and use at L474
8. **Add missing imports** — `java.time.Instant`, `java.time.Duration`, `java.time.ZoneOffset`, `java.time.ZoneId`, `java.time.LocalDate`, `java.time.format.TextStyle`, `java.util.Locale`, `kotlin.math.abs`
9. **Remove inline FQN references** — Replace all `java.time.Instant.ofEpochMilli(...)` etc. with short names

### Step 2: Extract a RenderContext Data Class
Create a lightweight internal data class to hold computed values that multiple phases share, replacing the 20+ local variables passed between phases:

```kotlin
private data class RenderContext(
    val canvas: Canvas,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val graphTop: Float,
    val graphBottom: Float,
    val graphHeight: Float,
    val footerTop: Float,
    val minTemp: Float,
    val maxTemp: Float,
    val tempRange: Float,
    val hourWidth: Float,
    val minTimeEpoch: Long,
    val transitionX: Float?,
    val effectiveActualEndIndex: Int,
    val nowX: Float?,
    val nowIndicatorVisible: Boolean,
    val labelScale: Float,
    val iconSize: Int,
    val iconTopPad: Float,
)
```

### Step 3: Cache Paints at Object Level
Move density-independent paint configurations to lazy object-level properties. For density-dependent values, use a density-keyed cache pattern:

```kotlin
private var cachedDensity: Float = 0f
private lateinit var actualLinePaint: Paint
private lateinit var forecastDashedPaint: Paint
// ... etc

private fun ensurePaints(context: Context, labelScale: Float) {
    val density = context.resources.displayMetrics.density
    if (density == cachedDensity && this::actualLinePaint.isInitialized) return
    cachedDensity = density
    // initialize all paints once
}
```

This avoids allocation on every render while still handling density changes (e.g., when moving between displays).

### Step 4: Extract Private Functions from renderGraph()
Break the monolith into these named phases, each as a private function:

| Function | Approx Lines | Responsibility |
|----------|-------------|----------------|
| `computeScaling(hours)` | L195–L207 | rawMin/Max, buffers, minTemp/maxTemp/tempRange |
| `computeLayout(context, heightPx, labelScale)` | L211–L224 | topPadding, footerTop, graphTop/Bottom/Height, iconSize |
| `computePoints(hours, ...)` | L346–L417 | All temperature series, point lists, paths |
| `drawFillAndCurves(ctx, paths, paints)` | L469–L499 | Ghost line, forecast line, actual line, fill |
| `drawHourLabelsAndIcons(ctx, hours, ...)` | L509–L557 | Hour labels + icon rendering via GraphRenderUtils |
| `placeTemperatureLabels(ctx, hours, ...)` | L559–L841 | Extrema detection, candidate building, collision-aware placement |
| `placeDayLabels(ctx, hours, ...)` | L843–L896 | Left/right day-of-week labels with collision avoidance |
| `drawFetchDot(ctx, ...)` | L907–L971 | Fetch dot, ring, staleness age text |

`renderGraph()` becomes an orchestrator that calls these in sequence (~50 lines).

### Step 5: Clean Up fetchTime Null Safety
Restructure the fetch dot section to avoid `fetchTime!!` by using a `let` block or early-return pattern that proves non-nullity to the compiler.

## Verification
1. **Unit tests**: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.TemperatureGraph*"`
2. **Also run truth curve test**: `--tests "com.weatherwidget.widget.TruthCurveLinearRenderingTest"`
3. **Visual check**: Build and install on emulator, verify the temperature graph renders identically (this is a pure refactor — zero visual changes expected)

## Risks
- **Paint caching**: Must handle `bitmapScale` changes (label paints depend on it). Will re-initialize when scale changes.
- **Method extraction**: Variable capture across phases is the main complexity. The RenderContext class mitigates this.
- **No behavior changes**: Every step is a pure refactor. If any test fails, it indicates a regression introduced by the refactor, not a pre-existing issue.
