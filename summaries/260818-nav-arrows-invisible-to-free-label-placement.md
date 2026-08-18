# Free-floating graph labels drawn on the nav arrows

## Problem

The dominant-station label `knuq 64.4° @ 10:10 am` was drawn directly on top of the left navigation
chevron while most of the plot stood empty. Reproduced on the emulator (density 2.63) and the Samsung
Fold (density 3.03), so not a device quirk.

**The nav arrows are not on the canvas the label is placed on.** `nav_left`/`nav_right` are
`ImageButton`s in `widget_weather.xml:1244,1257` that the *launcher* composites over the graph
bitmap; the label is drawn *into* that bitmap by `TemperatureGraphAnnotationRenderer`. Two layers.

`GraphEmptySpaceFinder.find` can only avoid three things — the curves it samples (`curveYsAt`),
`drawnBounds` (other ink on the same canvas), and `vetoBounds`, which held exactly one entry: the NOW
line. The arrow layer appears in none of them, so to the finder that strip genuinely reads as empty.

And it was not bad luck. `DominantStationLabel.X_FRACTIONS` leads with `0.08f`, documented as
deliberately below the horizontal clamp so it resolves to a 2dp inset against the left edge, and
`find` is anchor-major first-fit. The engine's *first choice* was the strip the arrow occupies. The
emptier the left band, the more reliably it landed on the chevron.

Measured (emulator, `uiautomator dump`): `graph_view` `[136,67][1632,1054]`, `nav_left`
`[125,455][230,665]` — x: 0 to **6.3%** of plot width, y: **39% to 61%**. The dp model reproduces it
exactly: 40dp arrow minus `graph_view`'s 4dp margin = 36dp x 2.625 = 94.5px (measured 94px); 80dp
`minHeight` x 2.625 = 210px (measured 210px).

`0.92f` is the right-edge mirror anchor and `nav_right` sits exactly there, so both sides were
exposed. Desktop had the same shape — `TemperatureGraph.kt:769,851` passed only `nowLineVeto`.

## What changed

**New `shared/src/main/kotlin/com/weatherwidget/shared/graph/NavArrowGeometry.kt`** — the dp
constants (`ARROW_WIDTH_DP`, `GRAPH_INSET_DP`, `ARROW_HEIGHT_DP`) and `arrowBounds()`, returning
plot-local rects for the visible arrows. Pure geometry, modelled on `NowIndicatorGeometry`, so both
platforms read one source. The band is centred vertically and clamped to the plot, so a label in the
top or bottom third clears it at any x and a short widget cannot have every candidate vetoed.

Passed as **`vetoBounds`, never `drawnBounds`**. `drawnBounds` carry a distance gradient in pass 1
(`openClearanceFor` ~ one line height), which over a band only 6% of the plot wide would retire the
edge anchors that exist precisely so a NOW-split plot still has somewhere to go. Veto blocks overlap
without repelling, so the label keeps its anchor and steps out of the band instead.

**Android** (`TemperatureGraphAnnotationRenderer.kt`, `TemperatureGraphRenderer.kt`,
`TemperatureGraphModels.kt`):

- `Input` gained `navArrowVisibility` and `bitmapScale`.
- `nowLineVeto()` -> `labelVetoBounds()` = NOW line + `navArrowVeto()`. Both existing call sites
  (`ForecastDeltaLabel`, `DominantStationLabel`) pick it up unchanged.
- `renderGraph` gained `navArrowVisibility` (default `BOTH`) and `onDominantStationPlaced`.
  `BOTH` is the right default: `TemperatureTouchTargets.setupNavigationButtons` sets both arrows
  `VISIBLE` unconditionally on the hourly path — `canLeft`/`canRight` only choose between a real nav
  intent and a toast.
- New `DominantStationDebug` (reason, text, box, centerX, baselineY, `navArrowBounds`). That
  placement was previously observable **only** through a `Log.isLoggable(VERBOSE)` line, so no test
  could assert it — and none did.

**Desktop** (`DesktopGraphUtils.kt`, `TemperatureGraph.kt`) — `navArrowVetoBounds()` vetoes the
**24dp icon**, not the `fillMaxHeight()` 28dp button. A full-height veto column at both edges would
evict every edge anchor and be worse than the overlap it prevents. No visibility gate: desktop always
renders both arrows, drawing a disabled one at alpha 0.18 rather than removing it.

## Two things the plan did not anticipate

### `bitmapScale` — a real scaling bug

Converting the arrow's dp width using `density` alone is wrong whenever the widget renders a
downscaled bitmap. `WidgetSizeCalculator.kt:339` computes
`bitmapScale = min(widthPx/rawWidthPx, heightPx/rawHeightPx)` to cap bitmap memory, so bitmap px =
view px x bitmapScale, while the arrows are sized in dp against the **view**. The conversion needs
`density * bitmapScale` — the same correction the error watermark already applies at
`TemperatureGraphRenderer.kt:131,336`, and which the graph's own `density` (line 142) deliberately
does not. Without it a downscaled bitmap over-reserves the edge band.

### Robolectric's missing font engine made the first test pass vacuously

Written as planned, the renderer integration test passed **with the fix disabled**. Two causes, found
by dumping the real geometry rather than reasoning about it:

- Robolectric defaults to **mdpi (density 1.0)** — the arrow band was only 36px wide.
- `Paint.measureText` has no font engine and returns ~1px per character, so
  `knuq 64.4° @ 10:00 am` measured **21px** instead of the ~250px it occupies on a device.

A 21px label at anchor `0.08` of a 900px plot lands at x=72, clear of a 36px band by accident. Fixed
with `@Config(qualifiers = "xxhdpi")`: density 3.0 matches the real devices, the band widens to
108px, and the label's *position* (a fraction of plot width) reproduces faithfully. The box stays
21px wide, which is correct — the assertion is about where the box is anchored, never glyph extents.

## Tests

| # | Kind | Test |
|---|---|---|
| 1 | unit | `NavArrowGeometryTest` — 8 cases: edge hugging, centred band, short-plot clamp, hidden arrows, desktop overrides |
| 2 | unit | `GraphEmptySpaceFinderTest.aNavArrowVetoMovesTheLabelVerticallyNotToTheOppositeEdge` |
| 3 | unit | existing `aVetoBoundRepelsNothing` — guards against "fixing" this via `drawnBounds` |
| 4 | integration | `DominantStationNavArrowRoboTest` — renderer -> annotation renderer -> label -> finder -> geometry |
| 5 | unit | `NavTouchZoneRoboTest` +3 cases — constants vs the inflated layout |
| 7 | integration | `DominantStationNavArrowLayoutRoboTest` — inflate, measure, lay out `widget_weather`, render at `graph_view`'s size, compare against the real `nav_left`/`nav_right` bounds |

Every placement assertion requires `reason == "drawn"` first: a suppressed label trivially clears the
arrow, so without that guard these tests would pass green on the regression they exist to catch.

Test 7 renders at exactly `graph_view`'s measured size so `scaleType="fitCenter"` is the identity —
see the warning at `widget_weather.xml:1680`. Any other size needs the fitCenter transform applied
before the rectangles can be compared.

### Falsification

Each assertion was checked against a deliberately broken build, not merely observed green:

| Broken how | Test 4 (renderer) | Test 7 (layout) | Test 5 (constants) |
|---|---|---|---|
| arrows dropped from `labelVetoBounds()` | **FAILED** | **FAILED** | pass |
| `ARROW_WIDTH_DP = 6f`, fix present | **pass** | **FAILED** | **FAILED** |
| `ARROW_WIDTH_DP = 44f`, fix present | pass | pass | **FAILED** |

Row 2 is the one worth keeping. Test 4 is a genuine integration test — five classes — but its oracle
is `NavArrowGeometry`, part of the code under test, so a wrong constant sails through it. Only the
test that lays out the real widget catches both failure modes. Integration by class count and
independence of oracle are different properties, and the first does not imply the second.

## Verified

- `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` — **1331 tests, 0 failures**
  (192 classes across app + shared; desktop green separately).
- `./gradlew installDebug` — emulator and Samsung Fold both show the chevron fully clear. The label
  falls through to the `0.22f` anchor into open space, keeping its left-side preference rather than
  fleeing right, which is what confirms the veto is not repelling.
- `scripts/buildStart-desktop.sh` — label sits above the icon band; both arrows clear.

## Still open

`GhostLineLabel.placeAll` (`TemperatureGraphAnnotationRenderer.kt:492`) is the third free-floating
label. It takes `drawnBounds` but **no** `vetoBounds`, and anchors along the ghost line right of the
fetch dot — straight at `nav_right`. Same bug; fixing it needs a signature change plus the
single-answer-sampler cleanup noted in `free-label-collision-needs-all-curves`. Untouched here.

## Related

- `plans/260818-nav-arrows-invisible-to-free-label-placement.md` — diagnosis, plan, implementation notes
- `summaries/260816-two-pass-empty-space-search.md` — the same label, the previous escape
- commit `274918c6` "Place free-floating graph labels in open space, clear of NOW" — the NOW line was
  the first obstacle found to live outside `curveYsAt`; the arrows are the second, and live outside
  the canvas entirely
