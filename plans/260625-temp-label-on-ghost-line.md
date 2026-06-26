# Plan: Label the ghost line on the zoomed-in hourly temperature graph

## Context

The hourly temperature graph draws a faint white dotted **ghost line** — the forecast curve
shifted by the currently-observed delta (`expected = forecast + appliedDelta`). It represents
"what we'd expect the real temperature to be" and is drawn only in the future region (right of
the fetch dot) when the now-indicator is visible and the delta is meaningful.

The user previously did not want this line labeled and has now changed their mind. They want a
single temperature value placed **on the right half of the ghost line**, **snapped to an hourly
mark**, so it reads against the footer hour labels as e.g. "at 6 PM it's projected to be 69°".
It must appear **only when there's free space nearby** (no collision with other labels/curve),
and **only in the narrow / zoomed-in view (≤12h span)**. When the right half is crowded, the
label is simply omitted.

Decisions confirmed with the user:
- **Anchor**: best clear spot among hour points on the right half (skips crowded spots).
- **Snap**: to an hourly mark, preferring hours whose footer label is shown.
- **View gate**: narrow only, ≤12h span (matches `FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN`).

## Approach

Mirror the existing **`YesterdayDeltaLabel`** pattern: a small, platform-free shared object owns
the gate, value, formatting, color, and empty-space placement; Android and desktop each measure
text, supply a curve sampler, and draw. This keeps the two platforms pixel-consistent (an
established norm in this codebase — see the many "share Android/desktop logic" learnings).

### 1. New shared object: `GhostLineLabel`

New file: `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt`

Model it directly on
`shared/src/main/kotlin/com/weatherwidget/shared/graph/YesterdayDeltaLabel.kt`.

- **Constants**
  - `MAX_HOURS_SPAN = FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN` (12L) — narrow-only gate.
  - `RIGHT_HALF_FRACTION = 0.5f` — only consider hour points whose x is in the right half of the plot.
  - A small `GAP` / `padPx` for clearance, and a label-above/below offset from the line.
- **`Metrics(width, ascent, descent)`** — same shape as `YesterdayDeltaLabel.Metrics`.
- **`Candidate`** input per hour the caller passes in: `(x, ghostY, expectedTemp, hasHourLabel)`,
  where `ghostY` is the y of the ghost line at that hour and `expectedTemp = forecast + delta`.
  The caller builds these from `expectedPoints` (already computed) + `hours`.
- **`Placement(text, centerX, baselineY, box, colorArgb)`** — same dual-convention shape as
  `YesterdayDeltaLabel.Placement` (centerX/baselineY for Android `drawText`, `box` for Compose).
- **`format(temp: Float): String`** — rounded integer + degree symbol, e.g. `"69°"`. Reuse the
  project's existing temperature formatting if one is already shared; otherwise `round().toInt()`.
- **`place(...)`** returns `Placement?`:
  1. Return `null` if `spanHours > MAX_HOURS_SPAN`, or no candidates, or metrics non-positive.
  2. Filter candidates to the right half (`x >= plot.left + RIGHT_HALF_FRACTION * plot.width`).
     The ghost line already only exists right of the fetch dot, so callers pass only ghost-region
     points; the right-half filter narrows further per the user's "right side" intent.
  3. Prefer candidates with `hasHourLabel == true`; fall back to any whole-hour point if none of
     the labeled hours are clear.
  4. For each candidate, build a centered label box hugging the line — try **above**
     (`top = ghostY - GAP - h`) then **below** (`top = ghostY + GAP`). Reject a box that
     intersects any `drawnBounds` or whose curve clearance is insufficient (reuse the
     `curveClearance` helper logic from `YesterdayDeltaLabel`, sampling the supplied `curveYAt`).
  5. Pick the candidate/side with the **best clearance** (emptiest), consistent with the
     "best clear spot" decision. Return `null` if nothing fits.
- **Color**: tie to the ghost line — white at a readable alpha (the ghost family). Expose via
  `colorArgb` so each platform applies it; final alpha tuned visually during implementation.

### 2. Android integration

File: `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`

- Add `placeGhostLineLabel(ctx, hours)`, modeled on the existing `placeYesterdayDeltaLabel`
  (`TemperatureGraphRenderer.kt:493`). Call it from the same place `placeYesterdayDeltaLabel` is
  invoked (`TemperatureGraphRenderer.kt:865`), **after** the main temperature labels and the
  yesterday-delta label so it yields to them as obstacles. Order vs. the delta label: place the
  ghost label first or last — pick so neither essential label is starved; default to after the
  main temp labels and before/after delta as looks best (both feed `ctx.drawnLabelBounds`).
- Gate identically to the ghost line itself (so the label only shows when the line is drawn):
  `ctx.nowIndicatorVisible && ctx.appliedDelta != null && abs(appliedDelta) >= MIN_GHOST_LINE_DELTA
  && ctx.fetchDotX != null` (same condition as `drawFillAndCurves`, line 320).
- Build candidates from `ctx.expectedPoints` + `hours`: for each index with
  `x > fetchDotX` use `(x, expectedY, smoothedExpectedTemps[i], hours[i].showLabel)`.
  `smoothedExpectedTemps`/`expectedPoints` are already on `RenderContext` (returned by
  `computePoints`, lines 204-205, 255).
- Measure text with a suitable paint (start from the forecast/ghost temp-label paint), supply
  `curveYAt = { sampleVisibleCurveY(ctx, it) }` (existing helper at line 528), compute span via
  `Duration.between(hours.first().dateTime, hours.last().dateTime).toHours()`, and draw + append
  the box to `ctx.drawnLabelBounds` exactly as `placeYesterdayDeltaLabel` does (lines 519-524).

### 3. Desktop integration (parity)

File: `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt`

- Add a block alongside the existing yesterday-delta block (lines 638-674), reusing
  `expectedCoords` (line 286, the ghost-line offsets), `getCurveYAtX` (line 288), `drawnLabels`,
  and the window span (`(windowEnd - windowStart) / 3_600_000L`). Same gate
  (`appliedDelta` meaningful + `fetchDotXVal != null`). Measure with `textMeasurer`, call
  `GhostLineLabel.place(...)`, draw via `drawText(topLeft = placement.box.topLeft)`, and add the
  rect to `drawnLabels`.

### 4. Tests

New file: `shared/src/test/kotlin/com/weatherwidget/shared/graph/GhostLineLabelTest.kt`, mirroring
`YesterdayDeltaLabelTest.kt` (plain JUnit, no android.graphics). Cover:
- `format` rounding (e.g. 68.6 → "69°").
- Span gate: returns `null` when `spanHours > 12`.
- Right-half filter: a clear left-half hour is **not** chosen; a clear right-half hour is.
- Hour-mark preference: a labeled-hour candidate wins over a non-labeled one when both clear.
- Collision: returns `null` when every right-half hour box intersects `drawnBounds` or the curve.
- Best-clearance selection among multiple clear candidates.

## Critical files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt` (new)
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/YesterdayDeltaLabel.kt` (template — reuse `curveClearance`/structure)
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` (`placeGhostLineLabel`, call site ~line 865; reuse `sampleVisibleCurveY`, `ctx.expectedPoints`/`smoothedExpectedTemps`)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt` (delta block ~lines 638-674; reuse `expectedCoords`, `getCurveYAtX`)
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/GhostLineLabelTest.kt` (new)

## Verification

- **Unit**: `./gradlew :shared:testDebugUnitTest --tests "*GhostLineLabelTest*"` (and the renderer
  module's unit tests if any assert label sets).
- **Android (emulator)**: `./gradlew installDebug`, place/zoom the widget into the narrow (≤12h)
  hourly view with a non-trivial current delta so the ghost line shows; confirm a single white
  temperature label sits above/below the ghost line near an hour mark on the right, reads against
  the footer hour (e.g. "6 PM" ↔ "69°"), and disappears when that area is crowded or the view is
  widened to 24h/3-day. Capture via:
  `adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg` then read the JPG.
- **Desktop**: rebuild + restart with `scripts/buildStart.sh`; open the popup, zoom into the
  narrow hourly view, and confirm the same label appears with matching placement/gating.
