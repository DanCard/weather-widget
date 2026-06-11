# Share the hourly-graph NOW indicator (prefer Android's version)

## Context

The hourly **temperature graph** draws a "NOW" indicator: a dashed vertical line at the
current time plus a "NOW" text label. Android and desktop currently implement this
**twice**, and the desktop version is cruder:

| | Android (`GraphRenderUtils`) | Desktop (inline in `TemperatureGraph.kt`) |
|---|---|---|
| Label size | full `NOW_LABEL_TEXT_SIZE_DP` (15.5dp) | `× 0.7` (shrunk) |
| Shadow | yes (light shadow) | none |
| Weight | NORMAL | Bold |
| Placement order | **below** line first, then above | above first, then below |
| Collision pad | none | inflate 4dp |
| On double-collision | **suppress** (draw nothing) | always draw (fallback) |
| Line stroke | 0.5dp | 1.0dp |

The user wants desktop to match Android's nicer version, and the logic shared so the two
can't drift again. This mirrors the project's existing `desktop_label_placement_divergence`
problem — the NOW label is one more instance of "desktop reimplemented it simpler."

**Key finding:** Android's `computeNowLabelBounds` is *already* the canonical algorithm,
shared by all three Android graph renderers (Temperature, Precip, CloudCover). It is pure
geometry whose only Android coupling is the `RectF` type. So this is a clean **lift-and-shift
into `:shared`**, swapping `RectF` for the existing `GraphRect`, following the established
"shared computes geometry, platform draws" pattern (`DailyRainLabels.kt`,
`TemperatureLabelEngine.kt`).

**Decisions (confirmed with user):**
- Unify *all* now-lines to **0.5dp** (one constant change; also thins Android precip/cloud).
- **Suppress** the NOW label on double-collision, matching Android exactly.
- Drop desktop's Bold and `×0.7` shrink; restore full size + light shadow (prefer Android).

## The cross-platform seam

Android `Canvas.drawText` is **baseline-anchored** with `Align.CENTER` (pass center-x +
baseline-y). Compose `drawText` is **top-left-anchored** (pass an `Offset` of the box corner).
The shared result carries **both** conventions, computed once, so neither platform recomputes:

```kotlin
data class NowLineGeometry(val lineTop: Float, val lineBottom: Float)

data class NowLabelPlacement(
    val centerX: Float,   // == input nowX; for Android Align.CENTER
    val baselineY: Float, // Android baseline (== box.top - fontAscent)
    val box: GraphRect,   // top-left screen-space box; also the collision rect to record
)
```

## New shared file

`shared/src/main/kotlin/com/weatherwidget/shared/graph/NowIndicatorGeometry.kt` — plain
kotlin-jvm object, no `android.graphics`, no Compose:

```kotlin
object NowIndicatorGeometry {
    fun computeNowLine(graphTop: Float, graphHeight: Float): NowLineGeometry
    // lineHeight = graphHeight * HourlyGraphDefaults.NOW_LINE_HEIGHT_FRACTION
    // lineTop = graphTop + (graphHeight - lineHeight) / 2; lineBottom = lineTop + lineHeight

    fun computeNowLabel(
        nowX: Float,
        graphTop: Float, graphHeight: Float,
        labelWidth: Float,
        fontAscent: Float,   // negative, font-metrics convention (Android fm.ascent)
        fontDescent: Float,  // positive
        drawnBounds: List<GraphRect> = emptyList(),
        dpToPx: (Float) -> Float,
    ): NowLabelPlacement?
}
```

`computeNowLabel` body is lifted verbatim from `GraphRenderUtils.computeNowLabelBounds`
(`GraphRenderUtils.kt:338-368`), `RectF` → `GraphRect`: tries `[labelYBottom, labelYTop]`
(**below first**); builds `GraphRect(nowX ± labelWidth/2, baseline+ascent .. baseline+descent)`;
returns first non-colliding placement (via `GraphRect.intersects`), else **null** (suppress).
The `±2dp` line/label gap becomes a shared constant (below).

The **X position stays platform-specific** — Android uses `computeNowX` (discrete hour bucket +
minute offset), desktop uses `xAtTime` (continuous zoom/drag). These are different coordinate
systems; the helper only takes `nowX: Float` and never reasons about time.

## Constant changes — `HourlyGraphDefaults.kt`

- **`CURRENT_TIME_STROKE_DP: 1.0f → 0.5f`** — single change that thins all five now-lines that
  read it (desktop temp/precip/cloud + Android precip/cloud). Android temperature already uses
  `STROKE_LEADER_LINE_DP = 0.5f`, the value everything converges to.
- **Add `NOW_LABEL_LINE_GAP_DP = 2f`** — the `±2dp` gap between line ends and the label,
  currently a magic `dpToPx(2f)` / `2.dp` duplicated in both platforms.

(Optional consistency: repoint `TemperatureGraphStyle.kt:148` now-line stroke from
`STROKE_LEADER_LINE_DP` to `CURRENT_TIME_STROKE_DP`, keeping now-line semantics distinct from
label leader lines at 223/229. Not required — value is identical.)

## Android — delegate, keep the public API

Keep `GraphRenderUtils.NowLabelResult`, `computeNowLabelBounds`, `drawNowLine`,
`drawNowIndicator` as the Android-facing API (Temp/Precip/Cloud renderers depend on them and on
`RectF`). Re-implement their bodies as thin adapters over the shared helper:

- `computeNowLabelBounds(...)`: call `NowIndicatorGeometry.computeNowLabel(...)`, map result to
  `NowLabelResult(labelY = placement.baselineY, bounds = placement.box.toRectF())`. Signature,
  `RectF` return type, below-first/suppress semantics unchanged → Precip
  (`PrecipitationGraphRenderer.kt:440`) and the `drawNowIndicator` path keep working untouched.
- `drawNowLine(...)`: replace inline line math with `NowIndicatorGeometry.computeNowLine(...)`,
  keep `canvas.drawLines` + Android `currentTimePaint`.
- Add a small `GraphRect.toRectF()` (or inline `RectF(...)`) for the one mapping; Android keeps
  `RectF` for its `drawnBounds` inputs and converts at the boundary (existing idiom).

Net Android behavior: byte-for-byte identical placement, sourced from `:shared`.

## Desktop — `TemperatureGraph.kt`

- **Line (~354-356):** replace inline `lineHeight/lineTop/lineBottom` with
  `NowIndicatorGeometry.computeNowLine(top, graphHeight)`. Stroke now 0.5dp via the constant.
- **Label (~639-677):** adopt Android's polish —
  - `fontSize = (NOW_LABEL_TEXT_SIZE_DP * scale).sp` (drop `× 0.7`); drop `FontWeight.Bold`.
  - add `shadow = Shadow(Color(COLOR_SHADOW_LIGHT), Offset(0f, 0f), SHADOW_RADIUS_LIGHT_DP.dp.toPx()*scale)`
    (Android's now-label shadow uses dy=0).
  - replace the hand-rolled candidate loop with:
    ```kotlin
    val placement = NowIndicatorGeometry.computeNowLabel(
        nowX = markerX, graphTop = top, graphHeight = graphHeight,
        labelWidth = nowLabelWidth, fontAscent = 0f, fontDescent = nowLabelHeight,
        drawnBounds = drawnLabels.map { GraphRect(it.left, it.top, it.right, it.bottom) },
        dpToPx = { it.dp.toPx() * scale },
    )
    placement?.let {
        drawText(nowLabelLayout, topLeft = Offset(it.box.left, it.box.top))
        drawnLabels.add(Rect(Offset(it.box.left, it.box.top), Size(nowLabelWidth, nowLabelHeight)))
    }
    ```
  Compose has no clean ascent/descent split, so desktop passes `fontAscent=0f,
  fontDescent=height`; then `box.top == baselineY` (top-left) and `box.bottom == baselineY+height`
  — exactly the top-left box Compose's `drawText` wants. Below-first + suppress now match Android;
  the old 4dp inflate is dropped. Visibility gate `if (now in windowStart..windowEnd)` stays.

## Tests

New `shared/src/test/kotlin/com/weatherwidget/shared/graph/NowIndicatorGeometryTest.kt`
(plain JUnit, `dpToPx = { it }`, uses `GraphRect.intersects`):
1. `computeNowLine`: graphTop=0,height=100 → lineTop=20, lineBottom=80.
2. Below-first, empty bounds → `box.top >= lineBottom`.
3. Collision below → flips above → `box.bottom <= lineTop`.
4. Double collision → returns `null` (suppress).
5. Box/baseline consistency: `box.top == baselineY+fontAscent`, `box.bottom == baselineY+fontDescent`,
   `centerX == nowX`, box symmetric about nowX with width `labelWidth`.
6. Desktop convention: `fontAscent=0f, fontDescent=height` → `box.top == baselineY`,
   `box.height == height`.

No existing `computeNowLabelBounds` test exists to migrate (searched app + shared) — this is
net-new coverage. Android's existing renderer tests still exercise the adapter path unchanged.

## Verify

1. `./gradlew :shared:test` — new geometry tests + existing shared graph tests.
2. `./gradlew :app:testDebugUnitTest` — Android adapter refactor (Temp/Precip/Cloud callers).
3. `./gradlew :app:assembleDebug` then `./gradlew :desktop:run` — visually confirm both platforms
   render an identical NOW indicator (full-size label, light shadow, below-first, suppress,
   0.5dp line) on the temperature hourly graph; spot-check precip/cloud now-lines are now thinner.
4. Daily-build desktop check (optional): `scripts/build-exe-and-restart.sh` per `CLAUDE.md`.

## Critical files

- `shared/.../graph/NowIndicatorGeometry.kt` *(new)* — shared geometry helper
- `shared/.../graph/HourlyGraphDefaults.kt` — `CURRENT_TIME_STROKE_DP→0.5f`, add `NOW_LABEL_LINE_GAP_DP`
- `app/.../widget/GraphRenderUtils.kt` — adapters delegate to shared
- `desktop/.../desktop/TemperatureGraph.kt` — delegate + full size + shadow + below-first + suppress
- `shared/src/test/.../graph/NowIndicatorGeometryTest.kt` *(new)* — placement tests
