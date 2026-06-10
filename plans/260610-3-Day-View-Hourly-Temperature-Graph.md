# Plan: 3-Day View of the Hourly Temperature Graph

## Context

The hourly temperature graph currently offers two zoom spans — `WIDE` (±12h = 24h)
and `NARROW` (±2h = 4h) — selected per widget. The user wants a wider, **history-leaning
3-day view (48h back / 24h forward)** so the recent temperature trend is visible at a glance.

Decisions made with the user:
- **Window:** `backHours = 48`, `forwardHours = 24` (history-leaning). Within the data the
  app already fetches (`HOURLY_LOOKBACK_HOURS = 72`, `HOURLY_LOOKAHEAD_HOURS = 60`), so **no
  fetch/query changes**.
- **Scope:** the new level applies to **all hourly graphs** (temperature, precipitation,
  cloud-cover) via the existing shared per-widget zoom setting.
- **Labels:** ship the **simpler version first** — widen the window + thin the hour labels.
  Per-day high/low extrema labels are a deliberate **follow-up**, not in this change.
- **Desktop activation:** add **scroll-wheel zoom** over the temperature graph (the user's
  stated interest), stepping through the same discrete levels as Android.

Approach = **Option A** from `notes/3-day-hourly-temp-view-brainstorm.md`: add a third
`ZoomLevel`. Android is data-driven via the enum; desktop mirrors it with string literals.

---

## Android changes

### 1. Add the third zoom level — `app/.../widget/WidgetStateManager.kt`
Add to the `ZoomLevel` enum (after `NARROW`, ~line 36):
```kotlin
THREE_DAY(backHours = 48, forwardHours = 24, navJump = 12, labelInterval = 12, smoothIterations = 3),
```
- `labelInterval = 12` → hour labels at roughly day boundaries (noon/midnight), avoiding crowding
  across 72h.
- `navJump = 12` → nav arrows step a half-day at this span.

Extend `cycleZoomLevel` (~lines 578–586) to a 3-state cycle that **preserves today's
first-tap behavior** (WIDE→NARROW unchanged):
```kotlin
ZoomLevel.WIDE -> ZoomLevel.NARROW
ZoomLevel.NARROW -> ZoomLevel.THREE_DAY
ZoomLevel.THREE_DAY -> ZoomLevel.WIDE
```

### 2. Satisfy the three exhaustive `when (zoom)` blocks
The compiler will fail until each gains a `THREE_DAY` branch (this is the safety net):
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt` (~line 232, `showLabel`):
  add `ZoomLevel.THREE_DAY -> hourIndex % labelInterval == 0` (interval-thinned, same shape as WIDE).
- `app/.../widget/handlers/CloudCoverViewHandler.kt` (~line 50, `smoothIterations`):
  add `ZoomLevel.THREE_DAY -> zoom.smoothIterations` (same as WIDE).

### 3. No change needed elsewhere
All other consumers (`GraphDataLoader`, `GraphLayout`, `TemperatureTouchTargets`,
`HourlyBottomZoneHelper`, `PrecipViewHandler`, `WidgetRenderer`, `UIUpdateScheduler`,
`WidgetIntentRouter.handleCycleZoom`) read `zoom.backHours/forwardHours/navJump/labelInterval/
smoothIterations` as **properties** — data-driven, no edits. Re-grep
`when (zoom\|ZoomLevel\.WIDE ->` at implementation time to confirm only the two above exist.

Staleness age label already self-hides above a 12h span (`AGE_LABEL_MAX_HOURS_SPAN = 12`,
`TemperatureGraphStyle.kt`) — correct for 3-day, no change.

---

## Desktop changes

Desktop stores zoom as a `String` in `DesktopConfig.zoomLevel` (`"WIDE"`/`"NARROW"`) and maps
it inline with `if (zoomLevel == "NARROW") … else …`. The `else` silently catches everything,
so a new `"THREE_DAY"` must be handled **explicitly at every site** (no compiler help).

### 1. Centralize the mapping — `desktop/.../DesktopGraphUtils.kt`
Add constants + small helpers to kill drift across the three graphs:
```kotlin
const val THREE_DAY_BACK_HOURS = 48
const val THREE_DAY_FORWARD_HOURS = 24
const val THREE_DAY_LABEL_INTERVAL = 12
fun backHoursFor(zoom: String): Int       // NARROW=2, THREE_DAY=48, else WIDE
fun forwardHoursFor(zoom: String): Int     // NARROW=2, THREE_DAY=24, else WIDE
fun smoothIterationsFor(zoom: String): Int // NARROW=1, else 3
```

### 2. Use the helpers in the three graphs
`desktop/.../TemperatureGraph.kt`, `PrecipitationGraph.kt`, `CloudCoverGraph.kt`: replace the
inline `if (zoomLevel == "NARROW") …` for `backHours`, `forwardHours`, `smoothIterations`,
`labelInterval`, and `curveStroke` with the helpers / explicit `when` including `THREE_DAY`.
Note `CloudCoverGraph.kt` `expectedTotalPoints` (~line 339) → `THREE_DAY = 73` (48+24+1).

### 3. Scroll-wheel zoom on the temperature graph — `desktop/.../TemperatureGraph.kt`
On the graph `Canvas` modifier (alongside the existing `pointerInput` at ~line 164), add:
```kotlin
@OptIn(ExperimentalComposeUiApi::class)
modifier.onPointerEvent(PointerEventType.Scroll) { e ->
    val dy = e.changes.first().scrollDelta.y
    val pos = e.changes.first().position
    val centerOffset = ((start + (pos.x / size.width) * (cutoff - start) - now) / 3_600_000f).roundToInt()
    onScrollZoom(zoomIn = dy < 0, centerOffset = centerOffset)   // up = in, down = out
}
```
Add a new callback param `onScrollZoom: (zoomIn: Boolean, centerOffset: Int) -> Unit = { _, _ -> }`.
Stepped through the discrete levels (parity with Android), not continuous.

### 4. Wire zoom stepping in `desktop/.../Main.kt`
Add a desktop helper for the discrete order and reuse it for **both** the existing tap
(`onToggleZoom`, ~lines 635–646, currently a 2-state toggle) and the new `onScrollZoom`:
```
zoom-in order:  THREE_DAY -> WIDE -> NARROW   (re-center hourlyOffset on cursor/tap)
zoom-out order: NARROW -> WIDE -> THREE_DAY   (reset hourlyOffset = 0)
```
Apply via `onUpdateConfig(config.copy(zoomLevel = …, hourlyOffset = …))`, coercing to
`MIN/MAX_HOURLY_OFFSET`. Extending tap to 3 states gives desktop full parity with Android.

---

## Tests

- **Android — `WidgetStateManagerTest`** (extend, or add): assert `cycleZoomLevel` produces
  `WIDE → NARROW → THREE_DAY → WIDE`, and that `THREE_DAY.backHours/forwardHours == 48/24`.
- **Android — `TemperatureHourDataBuilder` test** (if present): a THREE_DAY case verifying the
  built window spans 48h back / 24h forward and label cadence thins to every 12h. Follow the
  existing renderer-test convention (assert counts/positions, not colors — stubbed to 0).
- **Desktop — `DesktopGraphUtils` unit test:** `backHoursFor/forwardHoursFor/smoothIterationsFor`
  return 48/24/3 for `"THREE_DAY"` and the legacy values for `"WIDE"`/`"NARROW"`.

---

## Verification (end-to-end)

**Android:**
1. `./gradlew installDebug`
2. Add/resize widget to ≥2 rows, tap current temp to enter the temperature graph.
3. Tap the graph body to cycle zoom: WIDE → NARROW → **THREE_DAY**. Confirm the 3-day view shows
   ~48h of history left-of-now and ~24h ahead, with thinned (≈day-boundary) hour labels.
4. Switch to precipitation/cloud-cover — confirm they honor the same 3-day span.
5. `adb logcat` should show `CYCLE_ZOOM … extraMetadata=zoom=THREE_DAY`.

**Desktop:**
1. After the change compiles, run `scripts/restart-desktop-distributable.sh` (per project
   convention — auto-restart, no need to ask).
2. Scroll-wheel **down** over the temperature graph to step out to the 3-day view; scroll **up**
   to step back in. Confirm cursor-centered re-centering on zoom-in.
3. Switch to precip/cloud graphs and confirm they render the 3-day span too.

**Unit tests:**
- `./gradlew testDebugUnitTest --tests "*WidgetStateManager*" --tests "*TemperatureHourDataBuilder*"`
- `./gradlew :desktop:test`

---

## Out of scope (tracked follow-ups)
- Per-day high/low extrema labels for the 3-day view (the renderer labels only the global
  min/max today) — the main UX enhancement, deliberately deferred. Touches the temperature
  label cascade in `TemperatureGraphRenderer.kt` and the **separate** desktop label code.
- Optional midnight/day-separator gridlines for legibility at 72h (`GraphRenderUtils`).
