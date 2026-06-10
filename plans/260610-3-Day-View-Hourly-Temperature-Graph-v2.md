# Plan: 3-Day View (Android) + Continuous Zoom (Desktop)

## Context

The hourly temperature graph offers two zoom spans — `WIDE` (±12h) and `NARROW` (±2h),
selected per widget. The user wants a wider, history-leaning view of the recent temperature
trend. Two related but distinct deliverables emerged:

1. **Android:** add a discrete **3-day view (48h back / 24h forward)** as a third zoom level.
   Android keeps its clean enum model (3 levels).
2. **Desktop:** go further — a **continuous, scroll-wheel zoom** where scrolling *in* shrinks
   toward a few hours and scrolling *out* grows toward **6 days back / 1 day forward**.

Decisions made with the user:
- Android window: `backHours = 48`, `forwardHours = 24` (history-leaning), within existing
  fetch (`HOURLY_LOOKBACK_HOURS = 72`, `HOURLY_LOOKAHEAD_HOURS = 60`) — no fetch changes.
- Zoom applies to **all hourly graphs** (temp/precip/cloud) via the shared per-widget setting.
- Per-day high/low extrema labels are a **follow-up**, not in this change.
- Desktop zoom = **continuous** (not a discrete ladder), max zoom-out **144h back / 24h forward**.

See `notes/3-day-hourly-temp-view-brainstorm.md` for the original option survey.

---

## Part 1 — Android: discrete `THREE_DAY` level

Android is data-driven via the `ZoomLevel` enum; adding a case force-fails every exhaustive
`when (zoom)` (the safety net), and all other consumers read enum properties unchanged.

### 1. `app/.../widget/WidgetStateManager.kt`
Add to the `ZoomLevel` enum (after `NARROW`):
```kotlin
THREE_DAY(backHours = 48, forwardHours = 24, navJump = 12, labelInterval = 12, smoothIterations = 3),
```
Extend `cycleZoomLevel` to a 3-state cycle preserving today's first-tap (WIDE→NARROW):
```kotlin
ZoomLevel.WIDE -> ZoomLevel.NARROW
ZoomLevel.NARROW -> ZoomLevel.THREE_DAY
ZoomLevel.THREE_DAY -> ZoomLevel.WIDE
```

### 2. Add `THREE_DAY` branches to the two exhaustive `when (zoom)` blocks
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt` (~line 232, `showLabel`):
  `ZoomLevel.THREE_DAY -> hourIndex % labelInterval == 0` (interval-thinned, like WIDE).
- `app/.../widget/handlers/CloudCoverViewHandler.kt` (~line 50, `smoothIterations`):
  `ZoomLevel.THREE_DAY -> zoom.smoothIterations`.

All other consumers read `zoom.*` properties — no edits. Re-grep `when (zoom\|ZoomLevel.WIDE ->`
at implementation time to confirm only these two. Staleness age label already self-hides above a
12h span (`AGE_LABEL_MAX_HOURS_SPAN`) — correct for 3-day, no change.

---

## Part 2 — Desktop: continuous scroll-wheel zoom (max 6d back / 1d fwd)

Desktop stores zoom as a `String` in `DesktopConfig.zoomLevel`. We move to a **continuous span
model**: a single zoom factor → `(backHours, forwardHours)`, with all rendering cadences derived
from the span. Range clamps from a tight zoomed-in view to **144h back / 24h forward**.

### 2a. Data plumbing — what makes 6 days of history visible
The back side is capped today by two hardcoded 72h reads + a shallow backfill. Retention (30 days)
is already sufficient.

| Change | File:line |
|--------|-----------|
| Graph hourly load window `now - 72h` → `now - 144h` (forward stays ≤ +168h already queried) | `desktop/.../DesktopWeatherRepository.kt:77` |
| One-time backfill `fetchHistory(3)` → `fetchHistory(7)`, widen its `historyStart` gate to `now - 144h` | `desktop/.../DesktopWeatherRepository.kt:123,128` |
| Actual-line context `ACTUALS_CONTEXT_LOOKBACK_HOURS = 72L` → `144L` so the actual series spans 6 days | `desktop/.../TemperatureGraph.kt:72` |

Prefer deriving the graph load bounds from the **max zoom span** (single source of truth) rather
than a second magic constant. Actual line over 6 days is sourced from Open-Meteo `past_days`
(stored as `GENERIC_GAP` fallback); NWS station observations remain best-effort on top.

### 2b. Continuous zoom model — `desktop/.../DesktopGraphUtils.kt`
Replace the named WIDE/NARROW string handling with a span-based model:
```kotlin
const val MIN_TOTAL_SPAN_HOURS = 4f      // most zoomed-in (~±2h)
const val MAX_BACK_HOURS = 144f          // 6 days history at max zoom-out
const val MAX_FORWARD_HOURS = 24f        // 1 day forward at max zoom-out
// zoom factor z in [0,1]: 0 = most-in, 1 = most-out
fun backHoursFor(z: Float): Int
fun forwardHoursFor(z: Float): Int       // history-leaning: forward grows slower than back
fun labelIntervalFor(spanHours: Int): Int    // e.g. ~span/6, snapped to {1,2,3,6,12,24}
fun smoothIterationsFor(spanHours: Int): Int // more smoothing as span grows
```
Persisted state: replace `DesktopConfig.zoomLevel: String` with a continuous
`zoomFactor: Float = <wide-equivalent>` (migrate old "NARROW"/"WIDE" string on read for back-compat,
or default-and-drop since DesktopConfig is local-only).

### 2c. Apply the span model in the three graphs
`TemperatureGraph.kt`, `PrecipitationGraph.kt`, `CloudCoverGraph.kt`: replace every
`if (zoomLevel == "NARROW") … else …` site (backHours, forwardHours, smoothIterations,
labelInterval, curveStroke, and CloudCover's `expectedTotalPoints`) with the span-derived helpers.
These become pure functions of the computed window, so they scale continuously.

### 2d. Scroll-wheel handler — `desktop/.../TemperatureGraph.kt`
On the graph `Canvas` modifier (next to the existing `pointerInput`):
```kotlin
@OptIn(ExperimentalComposeUiApi::class)
modifier.onPointerEvent(PointerEventType.Scroll) { e ->
    val dy = e.changes.first().scrollDelta.y          // up<0 = zoom in, down>0 = zoom out
    val pos = e.changes.first().position
    val cursorOffset = ((start + (pos.x / size.width) * (cutoff - start) - now) / 3_600_000f).roundToInt()
    onZoomScroll(deltaZoom = dy * ZOOM_SENSITIVITY, centerOffset = cursorOffset)
}
```
Add callback `onZoomScroll: (deltaZoom: Float, centerOffset: Int) -> Unit`. `Main.kt` clamps the
new `zoomFactor` to `[0,1]` and re-centers `hourlyOffset` on the cursor when zooming in. Keep the
existing graph-body tap (`onToggleZoom`) as a quick in/out toggle between two preset factors, or
retire it in favor of scroll — confirm during implementation.

### 2e. Wire in `Main.kt`
Pass `zoomFactor` into all three graphs (replacing `zoomLevel = config.zoomLevel`), and implement
`onZoomScroll` to update `config.zoomFactor`/`hourlyOffset`. Nav-arrow jump can scale with span.

---

## Tests
- **Android — `WidgetStateManagerTest`**: `cycleZoomLevel` = WIDE→NARROW→THREE_DAY→WIDE;
  `THREE_DAY.backHours/forwardHours == 48/24`.
- **Android — `TemperatureHourDataBuilder` test**: THREE_DAY window spans 48h/24h, labels thin to
  every 12h (assert counts/positions, not colors — stubbed to 0).
- **Desktop — `DesktopGraphUtils` unit test**: `backHoursFor/forwardHoursFor` clamp to 144/24 at
  `z=1` and to the ~±2h span at `z=0`; `labelIntervalFor`/`smoothIterationsFor` grow with span;
  monotonic across z.

---

## Verification (end-to-end)

**Android:**
1. `./gradlew installDebug`; widget ≥2 rows; tap temp to enter the graph.
2. Tap graph body to cycle WIDE → NARROW → **THREE_DAY**; confirm 48h history / 24h ahead, thinned
   labels; precip/cloud honor the same span. `adb logcat` shows `CYCLE_ZOOM … zoom=THREE_DAY`.

**Desktop:**
1. After it compiles, run `scripts/restart-desktop-distributable.sh` (project convention — no need
   to ask first).
2. Scroll **down** over the temperature graph to zoom continuously out toward **6 days back / 1 day
   forward**; scroll **up** to shrink toward a few hours. Confirm the curve, labels, smoothing, and
   icons rescale smoothly and the actual line is present across the full 6 days (not truncated at 3).
3. Switch to precip/cloud — confirm they render at the same span.
4. Sanity-check a fresh-ish DB: after a refresh, confirm ≥6 days of hourly history exists
   (`backup_databases.py` → `sqlite3`), proving the deepened backfill (2a-B) populated it.

**Unit tests:**
- `./gradlew testDebugUnitTest --tests "*WidgetStateManager*" --tests "*TemperatureHourDataBuilder*"`
- `./gradlew :desktop:test`

---

## Out of scope (tracked follow-ups)
- Per-day high/low extrema labels for wide spans (renderer labels only global min/max today) —
  touches `TemperatureGraphRenderer.kt` and the separate desktop label code.
- Midnight/day-separator gridlines for legibility at multi-day spans (`GraphRenderUtils`).
- Fetching hourly forecast >7 days forward (not needed: desktop max is +24h).
