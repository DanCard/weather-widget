# Free-floating graph labels land on the nav arrows (2026-08-18)

> **Status: implemented and verified 2026-08-18.** Fixed on the emulator, the Samsung Fold and
> desktop; 1331 unit/Robolectric tests green. See *Implementation notes* at the end for the two
> things the plan did not anticipate.

## Symptom

On the emulator, the hourly graph's dominant-station label `knuq 64.4° @ 10:10 am` is drawn
directly on top of the left navigation chevron, while most of the plot stands empty.

Not intermittent, and not a near miss — the label is centred in the arrow's band.

## Root cause

**The nav arrows are not on the canvas the label is placed on.**

`nav_left` / `nav_right` are `ImageButton`s declared in the RemoteViews layout
(`app/src/main/res/layout/widget_weather.xml:1244` and `:1257`), which the *launcher* composites
over the graph bitmap. The label is drawn *into* that bitmap by
`TemperatureGraphAnnotationRenderer`. Two separate layers.

`GraphEmptySpaceFinder.find` (`shared/src/main/kotlin/com/weatherwidget/shared/graph/GraphEmptySpaceFinder.kt:91`)
can only avoid three things:

| input | what it models | source |
|---|---|---|
| `curveYsAt` | the temperature lines painted at a given x | `visibleCurveYs` |
| `drawnBounds` | other ink on the same canvas | `Input.graphObstacles()` (`TemperatureGraphAnnotationRenderer.kt:651`) |
| `vetoBounds` | draw-over-nothing obstacles | `Input.nowLineVeto()` (`:658`) — **the NOW line, and nothing else** |

The arrow layer appears in none of them. The finder is not ignoring the arrow; it has never heard
of it, so that strip genuinely reads as empty space.

### It is not bad luck — the search actively prefers that strip

`DominantStationLabel.X_FRACTIONS` (`DominantStationLabel.kt:60`) leads with `0.08f`, documented as
deliberately below the horizontal clamp so that "on a clear plot the label resolves to its minimum
2dp inset against the left edge". `find` is anchor-major first-fit: the first anchor yielding any
legal box returns immediately.

So the engine's *first choice* is a 2dp inset from the left edge — which is inside the arrow.

### Measured geometry (emulator, `uiautomator dump`, 2026-08-18)

| view | screen bounds | in plot-local terms |
|---|---|---|
| `graph_view` | `[136,67][1632,1054]` | 1496 x 987 px (the bitmap) |
| `nav_left` | `[125,455][230,665]` | x: 0 -> **6.3 %**, y: **39.3 % -> 60.6 %** |
| `nav_right` | `[1538,455][1643,665]` | x: **93.7 % -> 100 %**, same y band |

The dp model reproduces this exactly, which is what makes it safe to derive rather than measure:

- arrow width 40dp, `graph_view` inset `layout_marginStart/End="4dp"` -> **36dp** of arrow overlaps
  the bitmap. At the emulator's density 2.625: `36 * 2.625 = 94.5px`; measured 94px.
- `android:minHeight="80dp"` -> `80 * 2.625 = 210px`; measured `665 - 455 = 210px`.

The arrows sit in the vertical **middle**. On this screenshot the left half of the plot is the flat
~60 deg overnight line down at the bottom, so the whole upper-left reads as wide open, anchor `0.08`
wins on the first sweep, and the label lands on the chevron. **The emptier the left band, the more
reliably this happens** — which is why it looks absurd.

### Both sides are exposed

`0.92f` is the right-edge mirror anchor (added so a NOW-split plot still has somewhere to go), and
`nav_right` occupies exactly that strip. Same bug, other edge.

### Desktop has the same shape

`TemperatureGraph.kt:769` and `:851` pass `vetoBounds = nowLineVeto` and nothing else. `NavArrow`
(`desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt:1528`) is a Compose `IconButton`
overlaid on the graph, invisible to the shared finder for the same reason.

## Recommendation

Feed the arrow rectangles to the finder as **`vetoBounds`, not `drawnBounds`.**

`drawnBounds` carry a distance gradient in pass 1 (`openClearanceFor` ~ one line height), so
registering the arrow there would push the label a full line height off a strip only 6 % wide and
in practice retire both edge anchors — and `0.08f`/`0.92f` exist precisely so a NOW-split plot still
has somewhere to go. `vetoBounds` block overlap without repelling, which is exactly the distinction
`GraphEmptySpaceFinder`'s doc draws for the NOW line. The label then stays hugging the edge and just
steps up or down out of the arrow's band — `VERTICAL_STEPS = 6` gives it several clear tops.

**The shared engine needs no change.** `vetoBounds` is already a `List<GraphRect>` and already has
test coverage (`GraphEmptySpaceFinderTest.aVetoBoundBlocksTheBoxItOverlaps`,
`aVetoBoundRepelsNothing`, `aVetoBoundIsClearedVerticallyNotOnlyHorizontally`). This is purely a
matter of the callers supplying an obstacle they currently omit.

### Two things the fix must get right

1. **Don't create a second source of truth for the geometry.** The renderer would otherwise
   hardcode a copy of the layout's 40dp / 4dp / 80dp / `center_vertical`, and `DailyViewHandler.kt:753`
   already adjusts arrow padding at runtime. Put the constants in one shared object and guard them
   with a test that inflates the real layout.
2. **Gate on actual visibility.** The arrows are set `GONE` in several states
   (`DailyVisibilityManager.kt:60`, `ApiSourceWarningHelper.kt:131`, `DailyViewHandler.kt:742`).
   Reserving space for a hidden arrow loses slots for nothing.

   Worth knowing: in the **hourly** path `setupNavigationButtons`
   (`TemperatureTouchTargets.kt:81,109`) sets both arrows `VISIBLE` **unconditionally** —
   `canLeft`/`canRight` only choose between a real nav intent and a toast intent. So today the
   hourly graph always has both arrows. The flag is for correctness and for the daily/error paths,
   not because the hourly case varies.

## Implementation plan

### 1. Shared geometry object

New `shared/src/main/kotlin/com/weatherwidget/shared/graph/NavArrowGeometry.kt`, modelled on
`NowIndicatorGeometry` (pure, platform-free, both platforms read it):

```kotlin
object NavArrowGeometry {
    /** Arrow button width; matches widget_weather.xml nav_left/nav_right layout_width. */
    const val ARROW_WIDTH_DP = 40f
    /** graph_view's layout_marginStart/End — the part of the arrow that misses the bitmap. */
    const val GRAPH_INSET_DP = 4f
    /** Arrow button minHeight; matches nav_left/nav_right android:minHeight. */
    const val ARROW_HEIGHT_DP = 80f

    /** The left/right arrow as plot-local veto rects. Empty entries for arrows that are hidden. */
    fun arrowBounds(
        plot: GraphRect,
        density: Float,
        leftVisible: Boolean,
        rightVisible: Boolean,
        heightPx: Float = ARROW_HEIGHT_DP * density,
    ): List<GraphRect>
}
```

Overlap width is `(ARROW_WIDTH_DP - GRAPH_INSET_DP) * density`; the band is `heightPx` centred on
the plot vertically. Pass the result as `vetoBounds`, never `drawnBounds` — document why inline,
the way `NowIndicatorGeometry.nowLineBounds` does.

Deliberately *not* in `HourlyGraphDefaults`: these are RemoteViews-layout facts, not graph styling.

### 2. Android wiring

- `TemperatureGraphAnnotationRenderer.Input` gains `navArrowsVisible: NavArrowVisibility`
  (or a pair of booleans). Constructed at `TemperatureGraphRenderer.kt:230`, so the flag has to be
  threaded from whoever decides `setViewVisibility` — same decision, one place.
- `Input.nowLineVeto()` becomes `Input.labelVetoBounds()`: the NOW line rect **plus**
  `NavArrowGeometry.arrowBounds(...)`. Both existing call sites
  (`TemperatureGraphAnnotationRenderer.kt:329` ForecastDeltaLabel, `:407` DominantStationLabel)
  pick it up with no further change.
- `ForecastDeltaLabel` is less exposed (`X_FRACTIONS` bottoms out at `0.22f`, so a short label
  clears the 6.3 % band) but should take the same list — uniform, and free.

### 3. Desktop wiring

Same veto list at `TemperatureGraph.kt:769,851`. One difference to respect: `NavArrow` is
`Modifier.width(28.dp).fillMaxHeight()`, so the *button* spans the whole plot height while the
*icon* is ~24dp centred. Veto the **icon's** drawn rect, not the button's bounds — a full-height
veto column would evict every edge anchor and defeat the purpose.

### 4. Testability seam (prerequisite, not optional)

Add `onDominantStationPlaced: ((DominantStationDebug) -> Unit)? = null` to
`TemperatureGraphRenderer.renderGraph` and to `TemperatureGraphAnnotationRenderer.Input`, carrying
`box`, `centerX`, `baselineY` and the existing `reason`. Today `placeDominantStationLabel` reports
only through a VERBOSE log (`TemperatureGraphAnnotationRenderer.kt:438`), so **no test can currently
observe where this label lands** — and none does. Without the seam, tests 4 and 7 cannot be written.

### 5. Out of scope (follow-up)

`GhostLineLabel.placeAll` (`TemperatureGraphAnnotationRenderer.kt:492`) is the third free-floating
label and takes `drawnBounds` but **no** `vetoBounds` — and it anchors along the ghost line right of
the fetch dot, i.e. straight at `nav_right`. Fixing it means a signature change plus the
single-answer-sampler cleanup already noted in `free-label-collision-needs-all-curves`. Track
separately.

## Test plan

Two layers, and the distinction matters here:

- **Unit** (tests 1-3): one class, pure geometry.
- **Integration** (tests 4, 6, 7): several classes wired together. Test 4 spans
  `TemperatureGraphRenderer` -> `TemperatureGraphAnnotationRenderer` -> `DominantStationLabel` ->
  `GraphEmptySpaceFinder` -> `NavArrowGeometry`. Test 7 adds the *layout* to that chain, which is
  the part test 4 cannot reach.

All Robolectric or pure JVM — no device step is needed to prove the placement change.

| # | Kind | Test | Where | Asserts |
|---|---|---|---|---|
| 1 | unit | arrow rect geometry | new `NavArrowGeometryTest` (shared, pure JVM) | left rect is `x in [plot.left, plot.left + 36dp*density]`, height `80dp*density`, vertically centred; right rect mirrors; hidden arrow yields no rect |
| 2 | unit | label vacates the arrow band | new case in `GraphEmptySpaceFinderTest` via the existing `findWithVeto` helper | with an edge veto rect over the mid band, the returned `Slot.box` does **not** intersect it and `centerX` is still in the left third — it moved vertically, it did not flee to the far side |
| 3 | unit | veto still repels nothing | existing `aVetoBoundRepelsNothing` | unchanged — guards against someone "fixing" this by moving the arrows into `drawnBounds` |
| 4 | **integration** | renderer places the label clear of the arrow | new `DominantStationNavArrowRoboTest`, harness copied from `TemperatureGraphLabelPlacementRobolectricTest` | `renderGraph(...)` on an hourly graph with a wide-open left band; the reported dominant-station box does not intersect `NavArrowGeometry.arrowBounds(...)`. **Must fail before the fix** — print the pre-fix box in the failure message |
| 5 | unit | constants have not drifted from the layout | new cases in `NavTouchZoneRoboTest` (already inflates `widget_weather`, already asserts 40dp zone widths) | `nav_left.layoutParams.width == dpToPx(ARROW_WIDTH_DP)`, `nav_left.minimumHeight == dpToPx(ARROW_HEIGHT_DP)`, `graph_view` marginStart `== dpToPx(GRAPH_INSET_DP)`; same for `nav_right` |
| 6 | integration | desktop parity | `desktop` graph placement test, alongside the existing label-placement parity tests | dominant-station box clears the icon rect at both edges |
| 7 | **integration** | label clears the *real* laid-out arrow | new `DominantStationNavArrowLayoutRoboTest` | inflate `widget_weather`, measure + layout at a realistic widget size, take `nav_left`/`graph_view` bounds via `rectInRoot`, render the bitmap at `graph_view`'s measured size, and assert the label box does not intersect the arrow's bounds translated into bitmap coordinates |

### Why test 4 is not sufficient on its own

Test 4 is an integration test, but its **oracle is self-consistent**: it compares the label box
against `NavArrowGeometry.arrowBounds(...)`, which is part of the code under test. If
`ARROW_WIDTH_DP` were 30f instead of 40f, test 4 still passes — the label would dodge a rectangle
that does not match the real chevron. `renderGraph` also takes `widthPx`/`heightPx` directly and
never inflates the layout, so the actual `ImageButton` is nowhere in the picture.

Test 5 closes that hole by chaining (constants match layout) + (label avoids constants), but nothing
asserts the composition in one place. **Test 7 does**, and both halves of the harness already exist:

- `SettingsTouchZoneRoboTest.layOutTextModeWidget()` — inflate, `measure()` at a dp size,
  `layout()`, then `rectInRoot()` for any view's true bounds. Copy this.
- `TemperatureGraphLabelPlacementRobolectricTest` — `renderGraph` with a debug callback. Copy this.

Nothing today spans both. Rendering the bitmap at exactly `graph_view`'s measured size makes the
`scaleType="fitCenter"` mapping the identity, so the test stays a placement assertion rather than
becoming a scaling test. If a follow-up ever renders at a different size, the fitCenter transform
has to be applied explicitly — see the warning comment at `widget_weather.xml:1680`.

### Blocker: there is no seam to read the placement from

`placeDominantStationLabel` (`TemperatureGraphAnnotationRenderer.kt:355`) exposes its box **only**
through a `Log.isLoggable(VERBOSE)` line (`:438`). There is no callback, and `renderGraph` does not
return the obstacle registry. Confirmed by search: **no test anywhere passes `dominantStationLabel`
to `renderGraph`, and nothing references `TemperatureGraphObstacleType.DOMINANT_STATION` in test
code — Android-side placement of this label has zero coverage today.**

So step 4 of the implementation is a prerequisite, not an afterthought:

> Add `onDominantStationPlaced: ((DominantStationDebug) -> Unit)?` to `renderGraph` and to
> `TemperatureGraphAnnotationRenderer.Input`, carrying `box`, `centerX`, `baselineY` and the
> existing `reason` string. This mirrors the six `on*` debug callbacks `renderGraph` already
> exposes (`onLabelPlaced`, `onFetchDotResolved`, `onDayLabelPlaced`, `onGhostLineDebug`,
> `onPointsResolved`, `onActualLineResolved`) and folds the `reason` — `no_text`, `span_too_wide`,
> `no_empty_band`, `drawn` — into something assertable, so a suppressed label can no longer be
> mistaken for a well-placed one.

Carrying `reason` also matters for test 4's own honesty: a label that fails to place at all
trivially "does not intersect the arrow". Every placement assertion must first assert
`reason == "drawn"`.

Notes:
- Robolectric has no font engine — assert dp/px geometry, never rendered glyph extents.
- `@Category(LongDuration::class)` is required on new Robolectric classes here.
- Write test 4 first and watch it fail; then test 7, which is the one that would have caught a wrong
  constant as well as a missing obstacle.

## Verification

1. `./gradlew testDebugUnitTest --tests "*NavArrowGeometry*" --tests "*GraphEmptySpaceFinder*" --tests "*DominantStationNavArrow*" --tests "*NavTouchZone*"`
2. `./gradlew installDebug`, then screenshot the emulator widget in the hourly view with a clear
   left band and confirm the label sits above or below the chevron.
3. `scripts/buildStart-desktop.sh` and confirm the same at both desktop edges.

## Related

- `plans/260814-graph-placement-parity-findings.md`
- memory: `empty-space-finder-two-pass`, `free-label-collision-needs-all-curves`,
  `shared-value-label-engine`
- commit `274918c6` "Place free-floating graph labels in open space, clear of NOW" — the NOW line
  was the first obstacle discovered to live outside `curveYsAt`; the nav arrows are the second, and
  live outside the canvas entirely.

---

## Implementation notes (what the plan missed)

### 1. `bitmapScale` — a real scaling bug the plan did not foresee

The plan said to convert the arrow's dp width using `density`. That is wrong whenever the widget
renders a downscaled bitmap. `WidgetSizeCalculator.kt:339` computes
`bitmapScale = min(widthPx/rawWidthPx, heightPx/rawHeightPx)` to cap bitmap memory, so bitmap px =
view px x bitmapScale. The arrows are sized in dp against the **view**, so the conversion into
bitmap coordinates needs `density * bitmapScale` — exactly the correction the error watermark
already applies at `TemperatureGraphRenderer.kt:131,336`, and which the graph's own `density` (line
142) deliberately does not.

`Input` gained a `bitmapScale` field for this. Without it a downscaled bitmap over-reserves the edge
band by up to the scale factor, quietly costing the label slots it should have had.

### 2. Robolectric's missing font engine made the first version of test 4 pass vacuously

Written as planned, the renderer integration test passed **with the fix disabled**. Two causes,
found by dumping the actual geometry rather than guessing:

- Robolectric defaults to **mdpi (density 1.0)**, so the arrow band was only 36px wide.
- `Paint.measureText` has no font engine and returns roughly 1px per character, so
  `knuq 64.4° @ 10:00 am` measured **21px** instead of the ~250px it occupies on a device.

A 21px label at anchor `0.08` of a 900px plot lands at x=72 — clear of a 36px band by accident. The
fix is `@Config(qualifiers = "xxhdpi")`: density 3.0 widens the band to 108px, the label's *position*
(a fraction of plot width) still reproduces faithfully, and the overlap becomes real. Real devices
are 2.63 (emulator) and 3.03 (Fold), so this is a realistic density, not a tuned one.

The label box stays 21px wide in the test. That is fine and deliberate — the assertion is about
*where the box is anchored*, never about glyph extents, per the standing Robolectric rule.

### 3. Test 4's oracle was tightened

Rather than rebuilding the arrow rect from the bitmap size — which does **not** match the plot the
renderer vetoes against, `(0, graphTop, widthPx, graphBottom)` — `DominantStationDebug` now carries
`navArrowBounds`, the rectangles the render actually reserved, plus a test that those rects hug the
plot edges. The self-consistency limit still stands and is stated in the test's own KDoc; test 7 is
what closes it.

## Falsification results

Every assertion was checked against a deliberately broken build, not just observed green:

| Broken how | `DominantStationNavArrowRoboTest` | `DominantStationNavArrowLayoutRoboTest` | `NavTouchZoneRoboTest` |
|---|---|---|---|
| `labelVetoBounds()` drops the arrows | **FAILED** (label 61.5-82.5 inside band 0-108) | **FAILED** (label under `nav_left`) | pass |
| `ARROW_WIDTH_DP = 6f`, fix present | pass — *self-consistent oracle* | **FAILED** | **FAILED** |
| `ARROW_WIDTH_DP = 44f`, fix present | pass | pass | **FAILED** |

Row 2 is the one worth keeping: it is the concrete demonstration that the renderer-level integration
test cannot catch a wrong constant, and that tests 5 and 7 are not redundant with it.

## Verified

- `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` — **1331 tests, 0 failures**
  (192 classes across app + shared; desktop suite green separately).
- `./gradlew installDebug` -> emulator and Samsung Fold both show the chevron fully clear, with the
  label falling through to the `0.22f` anchor in open space.
- `scripts/buildStart-desktop.sh` -> label sits above the chevron band; both arrows clear.

## Still open (unchanged from the plan)

`GhostLineLabel.placeAll` takes no `vetoBounds` at all and anchors right of the fetch dot, straight
at `nav_right`. Untouched here; needs a signature change plus the single-answer-sampler cleanup.
