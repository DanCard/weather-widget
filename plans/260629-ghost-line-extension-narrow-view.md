# Plan: Extend Ghost Line in Narrow Hourly Graph View When Now Dot Scrolls Off-Left (Future Scroll)

## Context
The user wants to know the difficulty of extending the "ghost line" (the faint dashed "expected" continuation of the temperature curve, which is the forecast shifted by the observed delta from the fetch/"now" dot) in the hourly temperature graph.

User clarification (this session):
- narrow view = zoomed in view = when only 4 or 5 hours are shown in temperature graph.
- This matches the NARROW zoom (backHours=2, forwardHours=2 → ~4h total span, typically rendering 4-5 hourly points depending on exact centering and current-hour inclusion).

Specifically:
- Only for the **narrow view** (when the temperature graph shows only 4 or 5 hours).
- When user navigates/scrolls the view **into the future**, causing the now dot (fetch dot / current time indicator) to scroll out of view to the left.
- Currently, the ghost line drawing, clipping, and labels are gated on the dot being visible (`nowIndicatorVisible`, `fetchDotX` in [0, width], compute returning non-null within list).
- Delta passed to renderer only when `isNowLineVisible` (current hour present in graphHours).
- Goal: continue drawing the ghost line from the left canvas edge across the visible future forecast, using the known delta/expected values.

This is a UX enhancement for zoomed hourly navigation consistency (ghost represents the "reality projection" even after dot leaves view).

Emulator available for verification (no code execution in this plan phase).

No major code changes should be made until question answered via this plan.

## Recommended Approach
Implement support for "off-screen left anchor" for ghost without altering core data flow or wide/three-day views.

High-level:
- Generalize time-to-x computation to extrapolate negative x when target time (fetch) precedes the rendered hours window.
- Always pass meaningful `appliedDelta` to `TemperatureGraphRenderer.renderGraph(...)` (remove or relax the `isNowLineVisible` guard; delta is already resolved independently). This enables the ghost in narrow zoomed views (4 or 5 hours shown) even after scrolling the center far enough that "now" is no longer in the window.
- Relax **only the ghost-specific gates** in renderer (not general now/dot visibility):
  - Draw expected/ghost path if delta present + fetchX available (use `clipRect(max(0f, fetchDotX), ...)` so line starts at left edge).
  - Place ghost labels using same relaxed condition; filter candidates with logical (possibly negative) fetchX.
- Guard the fetch **dot itself** (and its labels/hard bounds) so it does **not** clamp/stick to the left screen edge when off-view (return early in layout if raw `fetchDotX < 0 || > width`).
- Leave `nowIndicatorVisible` / vertical now line / header delta visibility unchanged (they stay "dot must be in view").
- Update GhostLineLabel callers minimally; the shared placer already handles right-half logic.
- Add light diagnostics if helpful.
- Update affected tests (path counts that assumed "no ghost when NOW hidden") to preserve old behavior by using explicit `appliedDelta=null`, or add new test cases for extrapolated off-left.
- Keep changes narrow/ localized; no new state or major refactors.

This keeps the ghost "anchored" to the (offscreen) observation conceptually while rendering continuation in the viewed slice.

Tradeoffs considered:
- Extrapolate in `computeXForTime` (general, reusable) vs. special-case ghost compute: prefer general for cleanliness and future use (e.g. other labels).
- Always pass delta vs. only for narrow zoom: always is simpler (renderer gates anyway); narrow ghost labels (when 4 or 5 hours shown) already self-gate on span <=12h.
- Draw ghost from left edge vs. also draw a "virtual dot" at edge: follow user request ("extend the ghost line"); do not force dot visibility.
- Desktop parity: Android-focused (per "emulator"), but shared `GhostLineLabel` + desktop `TemperatureGraph.kt` uses similar clip; note in plan for follow-up if needed. No desktop changes unless required for consistency.
- Side effects: negative `fetchDotX`/`transitionX` could affect actual anchoring, hard bounds for labels, or min computations. Mitigate with `.coerceAtLeast(0f)` guards in non-ghost paths where appropriate. Test thoroughly.

## Critical Files to Modify
- `app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt` (core x computation)
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt` (ghost draw gate, clip, label place, fetch dot guard)
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt` (pass delta to render unconditionally or when in narrow view where 4 or 5 hours are shown)
- Test files for updates:
  - `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererFetchDotTest.kt`
  - `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererActualsTest.kt`
  - Possibly `app/src/androidTest/java/com/weatherwidget/widget/TemperatureGhostLineTest.kt`

Supporting (read-only for understanding, minor doc if needed):
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt`
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/ZoomStage.kt`
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` (typealias + zoom)
- `app/src/main/java/com/weatherwidget/widget/handlers/GraphDataLoader.kt` (windowing that leads to off-now slices)
- Desktop (informational): `desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt`

No changes to data loading, HourDataAssembler, state for offsets, or wide-view paths.

## Existing Functions / Utilities to Reuse (with paths)
- `GraphRenderUtils.computeXForTime(...)` (app/src/main/java/com/weatherwidget/widget/GraphRenderUtils.kt): extend this (remove strict before/after null return; add extrapolation using `firstTime`/`firstX` - `hoursDiff * hourWidth`).
- `GraphRenderUtils.computeNowX(...)` (same file): leave as-is (relies on `isCurrentHour` index; not used for ghost anchor).
- `TemperatureGraphRenderer.renderGraph(...)` + internal `prepareRenderContext` / `RenderContext` (app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt): `fetchDotX`, `appliedDelta`, `expectedPoints`, `nowIndicatorVisible` (already computed; reuse `fetchDotX` field allowing <0).
- Ghost draw block (lines ~326-333): `clipRect(fetchDotX, ...)` + `drawPath(expectedPath, ghostPaint)`.
- `placeGhostLineLabel(...)` (same file, ~553+): reuses `GhostLineLabel.Candidate` + `placeAll`; already filters `x > fetchDotX`.
- `GhostLineLabel.placeAll(...)` + `tryPlaceAt` (shared/src/main/kotlin/com/weatherwidget/shared/graph/GhostLineLabel.kt): reuses as-is (right-half + clearance + running obstacles; already narrow-gated by caller via span).
- `resolveFetchDotLayout` / `drawFetchDot` / `computeFetchDotBounds` (renderer): reuse but add early return if `fetchDotX !in [0, width]` before clamping (prevents edge-stick).
- `buildGraphQueryWindow` + load logic (app/src/main/java/com/weatherwidget/widget/handlers/GraphDataLoader.kt): no change (already handles adding now rows for context when !overlap).
- `isNowLineVisible` / delta resolution in `TemperatureStateResolver.resolve(...)` (~222): keep for header; only relax for the `appliedDelta` arg to `renderGraph`.
- Path builders: `GraphRenderUtils.buildSmoothCurveAndFillPaths` (for expectedPath).
- Existing guards: `MIN_GHOST_LINE_DELTA`, `X_COORDINATE_MATCH_TOLERANCE`, span checks.

Pattern reuse: similar to how actual lines use `transitionX` (can be computed), clip is already used for ghost region.

## Implementation Outline (Concise Steps for Execution)
1. Update `computeXForTime` (GraphRenderUtils.kt):
   - Remove `if (target.isBefore(first) || after(last)) return null`.
   - If before first: compute `hoursBefore = Duration.between(target, first).toMinutes()/60f`; `return firstX - hoursBefore * hourWidth`.
   - Symmetric for after last (positive beyond).
   - Keep inside-bucket logic. Return null only for empty inputs.
   - Add unit tests? (existing callers expect null for out-of-range in some cases; make extrapolation opt-in or always since math is sound for uniform spacing).

2. In `TemperatureStateResolver.kt`:
   - Compute `val graphAppliedDelta = currentTempResolution.appliedDelta`
   - Pass `appliedDelta = graphAppliedDelta` to `TemperatureGraphRenderer.renderGraph` (remove `if (isNowLineVisible)` for this arg; header deltaVisible stays gated).
   - (Optional: only for `zoom.totalSpanHours <= 12` to match ghost intent, but unconditional is fine + simpler.)

3. In `TemperatureGraphRenderer.kt`:
   - In draw curves section: change ghost if-guard from `ctx.nowIndicatorVisible && ...` to `appliedDelta != null && abs(...) >= MIN_GHOST... && fetchDotX != null`.
     - Inside: `val clipStart = fetchDotX.coerceAtLeast(0f)`; `clipRect(clipStart, 0f, width, height)`; draw expected.
   - In `placeGhostLineLabel`: same relaxed guard `if (!(appliedDelta != null && abs(...) && fetchDotX != null)) return`.
     - Candidates filter: `if (x <= fetchDotX + TOL) skip` (works for negative fetchDotX).
   - In `resolveFetchDotLayout` / before `drawFetchDot`: `if (fetchDotX == null || fetchDotX < 0f || fetchDotX > widthPx) return null;` (after ctx.fetchDotX lookup; prevents clamped edge dot).
   - Update related comments (e.g. "drawn only right of fetch" → "drawn right of fetch, or full view if fetch off-left").
   - Ensure `expectedPoints` / `smoothedExpectedTemps` still built when delta present (they are, via `effectiveDelta = appliedDelta ?: 0f` at top of prep).

4. Update tests:
   - Tests asserting "no ghost when NOW hidden + delta passed" (e.g. `TemperatureGraphRendererFetchDotTest`, ActualsTest): change setup to `appliedDelta = null` to keep "no delta" semantics, or add `// now tests ghost extension via extrapolated fetch`.
   - Add/verify path-count or debug tests for "delta + fetch before window → ghost drawn from x=0".
   - Existing extrapolation-agnostic tests (alignment, parallel) should continue passing.

5. Minor:
   - If `transitionX` / anchoring uses negative fetch undesirably, coerce in non-ghost paths: `fetchDotX?.coerceAtLeast(0f)`.
   - Verify `computeXForTime` callers (now, fetch in various tests) – most inside-range; out-of-range now get extrapolated (desired for this feature).
   - Desktop: no change required for this (question is emulator); future parity would mirror the gate relax + use extrapolated x in its clip.

No changes to:
- Hour data building, offsets, ZoomStage, state persistence, actual line logic, wide views, or label engine.

## Verification Section (End-to-End)
1. **Code + unit tests**:
   - `./gradlew :shared:test --tests "*GhostLineLabel*"` (and related graph label tests).
   - `./gradlew :app:testDebugUnitTest --tests "*TemperatureGraphRenderer*FetchDot* --tests "*Actuals* --tests "*Ghost*"` (adjust as noted; all green).
   - `./gradlew :app:testDebugUnitTest --tests "*TemperatureGraph*"` for broader coverage.

2. **Emulator (as hinted)**:
   - `adb installDebug` (or `./gradlew installDebug`).
   - On emulator: long-press home → Widgets → Weather Widget (resize to show hourly graph).
   - Tap to switch to Temperature / hourly view if needed.
   - Set to narrow/zoomed-in view (tap graph or cycle zoom until only 4 or 5 hours are shown in the temperature graph).
   - Use nav arrows (left/right on widget) or equivalent to scroll **right/future** until the "Now" dot + current time indicator + any ghost start disappear off left of the graph area.
   - Observe:
     - Faint ghost (dashed expected) line **continues from left edge** of graph area, overlaid on the forecast curve for visible future hours.
     - Ghost labels (faint white temps) appear on it where space (right-half logic).
     - No "stuck" now/fetch dot at left edge.
     - Normal (dot visible) cases unchanged.
   - Check logcat (filter `TempGraphRenderer`): expect ghost decision logs, no crashes.
   - Scroll back to verify restoration.
   - Test edge: just at the boundary (dot half-visible), far future, with/without delta.

3. **Other**:
   - No visual regression on daily, wide hourly, past scrolls, zero-delta, no-obs cases.
   - Desktop (optional quick): if built, similar narrow zoom + "scroll" equivalent.
   - Follow project debug: screenshot + renderer logcat for evidence.

This is low-risk (gates relaxed only for ghost drawing in the narrow zoomed-in view; core data unchanged) and directly answers "how easy/hard": **straightforward (2-4 hours dev + test), low complexity, localized to x-compute + 3-4 guard sites**. No architecture overhaul needed. The narrow view (4 or 5 hours shown) keeps the visible data window small, making extrapolation and clipping adjustments simple.

## Files Summary (for Executor)
Modify (in order of impact):
1. GraphRenderUtils.kt (extrapolation)
2. TemperatureGraphRenderer.kt (gates + clip + dot guard)
3. TemperatureStateResolver.kt (delta pass)
4. Affected test files (behavior preservation)

All other actions read-only during planning.
