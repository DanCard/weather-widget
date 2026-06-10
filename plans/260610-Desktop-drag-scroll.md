# Plan: Desktop drag-to-pan + scroll-zoom parity across all hourly graphs

## Context

The desktop temperature graph has scroll-wheel zoom and tap-zoom, but no way to *pan* through time
except the left/right nav arrows. The user wants **click-and-drag panning** (hold on the graph, move
the mouse left/right to scroll through history/forecast), and wants **all three hourly graphs**
(temperature, precipitation, cloud-cover) brought to parity: **drag-pan + scroll-zoom on each**
(precip/cloud currently have neither).

Decisions made with the user:
- **Pixel-smooth** drag (curve slides continuously under the cursor at every zoom), not whole-hour stepping.
- **All three** hourly graphs get drag-pan **and** scroll-zoom.

### Key constraints found
- `onUpdateConfig` → `saveConfigAndNotify` (`Main.kt:166`) writes `config.json` **and** a daemon
  trigger file on *every* call. So drag must update local state during the gesture and persist
  exactly **once on release** (commit-on-release), like window-move already does (debounced, `Main.kt:381`).
- X-mapping differs per graph: temperature maps the **data span**
  (`xAtTime(t)=(t-dataStart)/dataSpan*w`, `TemperatureGraph.kt:271-274`); precip/cloud map the
  **window** (`(t-windowStart)/windowSpan*w`, `PrecipitationGraph.kt:129`, `CloudCoverGraph.kt:123`).
  Both are made smooth by the same residual-pixel trick (below), differing only in which span feeds
  `pixelsPerHour`.
- Pan reuses the existing `hourlyOffset` Int (hours) machinery and `MIN/MAX_HOURLY_OFFSET` clamps in `Main.kt`.

## Approach

### 1. Shared gesture modifier — new file `desktop/.../HourlyGraphInput.kt`
A single `@Composable @OptIn(ExperimentalComposeUiApi::class) Modifier.hourlyPanZoomInput(...)` used by
all three graphs, so scroll+drag live in one place:
```kotlin
fun Modifier.hourlyPanZoomInput(
    start: Long, cutoff: Long, nowMs: Long, spanHours: Int,
    dragHours: MutableState<Float>,                       // live, uncommitted pan (shared with renderer)
    onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit,
    onPanCommit: (deltaHours: Int) -> Unit,
): Modifier
```
- `onPointerEvent(PointerEventType.Scroll)` → existing zoom logic (move the inline temperature handler here).
- `pointerInput { detectHorizontalDragGestures(onDragEnd = { commit round(dragHours); dragHours=0 },
  onDragCancel = { dragHours=0 }) { change, dx -> change.consume(); dragHours -= dx * (spanHours/width) } }`.
  Drag right → reveal earlier time → offset decreases. Coexists with each graph's existing
  `detectTapGestures` (separate `pointerInput`; consumed horizontal drag cancels the tap, so view-change
  taps still work).

Move `ZOOM_SENSITIVITY` out of `TemperatureGraph.kt` into this file / `DesktopGraphUtils`.

### 2. Pure, testable math — `DesktopGraphUtils.kt`
- `fun panDeltaHours(dragAmountPx: Float, widthPx: Float, spanHours: Int): Float`
- `fun dragResidualPx(dragHours: Float, pixelsPerHour: Float): Float = -(dragHours - dragHours.roundToInt()) * pixelsPerHour`

### 3. Per-graph integration (all three)
For `TemperatureGraph.kt`, `PrecipitationGraph.kt`, `CloudCoverGraph.kt`:
- `val dragHours = remember { mutableStateOf(0f) }`.
- Effective offset on integer hours: `val effInt = centerOffsetHours + dragHours.value.roundToInt()`;
  use `effInt` where `center` is computed (so window/points recompute only at hour boundaries).
- In the renderer's `xAtTime`, add `DesktopGraphUtils.dragResidualPx(dragHours.value, pixelsPerHour)` and
  relax the clamp `coerceIn(0f, w)` → `coerceIn(-w, 2*w)` (harmless when residual is 0). `pixelsPerHour`
  = `w * 3_600_000f / dataSpan` (temperature) or `/ windowSpan` (precip/cloud).
- Add the shared `.hourlyPanZoomInput(...)` to the Canvas modifier (replacing temperature's inline
  scroll handler); keep each graph's existing `detectTapGestures`.
- New callback params: `onPan: (Int) -> Unit` on all three; `onZoomScroll` added to precip/cloud
  (temperature already has it).

### 4. Wire `Main.kt`
- Hoist two shared lambdas near the snapshot render: `handleZoomScroll(deltaZoom, cursorOffset)`
  (the existing temperature logic) and `handlePan(deltaHours) = onUpdateConfig(config.copy(
  hourlyOffset = (config.hourlyOffset + deltaHours).coerceIn(MIN_HOURLY_OFFSET, MAX_HOURLY_OFFSET)))`.
- Pass `onZoomScroll = handleZoomScroll` and `onPan = handlePan` to **all three** graph call sites
  (temperature, precipitation, cloud-cover).

Out of scope (noted): precip/cloud keep their bottom-strip-only tap (no body tap-zoom toggle); panning
beyond the loaded data window (~144h back / 168h forward) shows empty edges, same as today's nav arrows.

## Tests
- `DesktopGraphZoomTest` (extend): `panDeltaHours` sign + magnitude (drag right → negative hours;
  full-width drag ≈ −spanHours); `dragResidualPx` is 0 at integer `dragHours`, continuous across the
  0.5 boundary (value just below +x at 0.49 ≈ value just above at 0.51 after the ±1h data step cancels).
- Compile + full `:desktop:test` (gesture wiring itself is not unit-tested, consistent with existing UI code).

## Verification (end-to-end)
1. `scripts/build-exe-and-restart.sh` (rebuild + restart; project convention).
2. Temperature graph: click-hold and drag left/right — the curve slides **smoothly** (no per-hour
   chunking) and the view stays put on release (persists across restart). Tap still toggles zoom; the
   bottom-strip tap still changes view.
3. Precipitation and cloud-cover: confirm **scroll-wheel zoom** now works and **drag-pan** slides smoothly.
4. Drag far back and confirm the actual line / data is present across the 6-day range (ties in with the
   prior observation-window fix).
