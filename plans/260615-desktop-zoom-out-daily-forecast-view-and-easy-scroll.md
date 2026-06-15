# Daily Desktop View — Mouse & Keyboard Navigation Parity

## Context

The desktop popup's **hourly** view already has rich mouse interaction (`Modifier.hourlyPanZoomInput`):
horizontal click-drag panning + scroll-wheel zoom. The **daily** view has none of this — it only
supports day navigation via the left/right `NavArrow` buttons and `onDayClick`. This change brings the
daily view's interaction up to parity by adding three things the user asked for:

1. **Horizontal drag-scroll** to pan through days (snap-step: each `dayWidth` dragged = one day).
2. **←/→ arrow keys** to pan the active view (daily by a day, hourly by its existing nav-jump).
3. **Scroll-wheel zoom** that changes how many **history** days are shown.

Key design decisions from the user:
- Drag is **snap-step** (steps one column per `dayWidth`, redrawing with real data) — not a smooth glide.
  The daily graph renders exactly its visible columns with no off-screen margin, so a smooth glide would
  reveal blank edges; snap-step avoids that and reuses the existing discrete `dateOffset` nav.
- Arrow keys cover **both** daily and hourly.
- Zoom = **day count / column density**, and is **history-biased**: zoom-out **prepends history days on the
  left** (today + future stay anchored on the right); zoom-in trims history back to the default (~1 day).
  Zoom-in is rarely useful because the default view already shows today + all forecast days. No cursor
  re-centering — growth/shrink is anchored to the right edge.

## Current architecture (for reference)

- Daily day window built by `DesktopDailyForecastModel.build()` →
  `NavigationUtils.getDayOffsets(cols, skipHistory)` (shared/util/NavigationUtils.kt:88). Default window is
  `[-1 (yesterday), 0 (today), …future]`. Column count `cols` comes purely from window width via
  `dimensions()` (DesktopDailyForecastModel.kt:67), capped at `MAX_DESKTOP_DAILY_COLUMNS = 9`.
- Daily nav state: `config.dateOffset` (Int days). Host + arrows in `Main.kt:745–802`
  (`daily_forecast_surface` `BoxWithConstraints`). Out-of-range offsets are reset by `clampOffset`, and
  `canNavigate(...)` already gates against `availableDates` (DesktopDailyForecastModel.kt:259).
- Hourly mouse input pattern to mirror: `HourlyGraphInput.kt` `Modifier.hourlyPanZoomInput`
  (scroll→`onZoomScroll`, drag→accumulate→`onPanCommit` on release).
- Popup `Window.onKeyEvent` already exists for Escape (Main.kt:436). Hourly nav-jump =
  `DesktopGraphUtils.navJumpHours(zoomFactor)`; offset bounds `MIN/MAX_HOURLY_OFFSET` (Main.kt:74).

## Changes

### 1. New config field — `DesktopConfig.kt`
Add `val dailyExtraHistory: Int = 0` (extra history days shown by zoom-out; 0 = default view).
Note: `encodeDefaults=false` means a `0` value is omitted from `config.json` and re-reads as default — fine.

### 2. History-biased zoom in the model — `DesktopDailyForecastModel.kt`
- Add `dailyExtraHistory` param to `build()`.
- After computing the base offsets, **prepend** history days, anchoring the right edge:
  ```
  val baseOffsets = NavigationUtils.getDayOffsets(dimensions.cols, skipHistory)
  val extra = clampExtraHistory(dailyExtraHistory, centerDate, baseOffsets.first(), availableDates)
  val historyOffsets = (1..extra).map { baseOffsets.first() - it }.reversed()
  val allOffsets = historyOffsets + baseOffsets
  ```
  Build `days` from `allOffsets`. When `extra > 0`, history is shown regardless of `skipHistory` (zoom-out
  intent wins). Total rendered columns become `cols + extra`, so columns narrow → the "more days" zoom feel.
- `clampExtraHistory`: clamp so the leftmost prepended date stays `>= availableDates.min` (reuse the
  existing `availableDates`/`canNavigate` logic), and `<= DAILY_MAX_EXTRA_HISTORY` (new const; propose ~14 to
  avoid extreme cramping — tunable; user prioritizes history so err generous).
- Extend `DesktopDailyViewState` with `clampedExtraHistory: Int`, `canZoomOut: Boolean`, `canZoomIn: Boolean`
  (mirrors `clampedDateOffset` / `canNavigateLeft/Right`). Host syncs `clampedExtraHistory` → config via a
  `LaunchedEffect` like the existing `clampedDateOffset` one (Main.kt:756).

### 3. Pure geometry helper — `DesktopGraphUtils.kt`
Add `fun panDeltaDays(dragPx: Float, dayWidthPx: Float): Int` (and any snap accumulation helper) so the
snap-step math is unit-testable without Compose — matching the repo's no-mock / pure-function testing
strategy (cf. existing `panDeltaHours`).

### 4. Shared daily input modifier — new `DailyPanZoomInput.kt`
`Modifier.dailyPanZoomInput(columnCount, onPanDays, onZoomScroll)`, structured like `hourlyPanZoomInput`:
- **Scroll wheel** → `onZoomScroll(deltaY)` (sign-based ±1 day step; zoom-out = add history, zoom-in = remove).
- **Horizontal drag** → accumulate pixels; each time `abs(accum) >= dayWidth` (`= size.width/columnCount`),
  emit `onPanDays(∓1)` and subtract a `dayWidth`. Consume drag moves so the graph's `detectTapGestures` tap
  is cancelled but plain taps still fall through (same coexistence as hourly).

### 5. Wire into the host — `Main.kt`
- Pass `config.dailyExtraHistory` into `DesktopDailyForecastModel.build(...)`.
- Apply `dailyPanZoomInput(columnCount = dailyState.days.size, …)` to the `daily_forecast_surface`
  `BoxWithConstraints` (Main.kt:745) so it covers both `DailyForecastGraph` and `DailyForecastTextMode`.
- `handleDailyPan: (Int) -> Unit` — clamp against `dailyState.canNavigateLeft/Right` before
  `onUpdateConfig(config.copy(dateOffset = …))` (same clamping the arrow buttons use).
- `handleDailyZoom: (Int) -> Unit` — clamp against `canZoomOut/canZoomIn`, update `dailyExtraHistory`.
- **Arrow keys:** hoist an `arrowKeyHandler: ((left: Boolean) -> Boolean)?` state next to the popup
  `Window`. `WidgetPopup` registers a handler for the **active** view (it knows `viewMode` + bounds):
  - daily → `dateOffset ∓ 1`, clamped via `canNavigateLeft/Right`.
  - hourly → `hourlyOffset ∓ navJumpHours(zoomFactor)`, clamped to `MIN/MAX_HOURLY_OFFSET`.
  Extend `Window.onKeyEvent` (Main.kt:436) so `KeyDown` `DirectionLeft`/`DirectionRight` invoke the handler
  (return `true` when consumed), leaving Escape intact.

## Files touched
- `desktop/.../DesktopConfig.kt` — new field.
- `desktop/.../DesktopDailyForecastModel.kt` — history-biased zoom, clamp, new state fields.
- `desktop/.../DesktopGraphUtils.kt` — `panDeltaDays` pure helper.
- `desktop/.../DailyPanZoomInput.kt` — **new** drag + scroll modifier.
- `desktop/.../Main.kt` — model wiring, daily pan/zoom handlers, surface modifier, arrow-key handler + Window.onKeyEvent.

## Tests
- `DesktopDailyForecastModelTest.kt` — zoom-out prepends history days, right edge stays anchored, clamps at
  `availableDates.min` and at `DAILY_MAX_EXTRA_HISTORY`, `clampedExtraHistory`/`canZoomOut`/`canZoomIn`.
- `DesktopGraphZoomTest.kt` (or a new util test) — `panDeltaDays` snap-step math across drag distances/dayWidths.

## Verification
1. `./gradlew :desktop:test` — unit suite green.
2. Rebuild + restart the running app: `scripts/buildStart.sh` (per project convention, after a compiling
   desktop change).
3. Manual, in the popup **daily** view:
   - Click-drag left/right → days step one column per `dayWidth` dragged, real data each step.
   - Press ←/→ → days shift by one (stops at history/forecast data bounds).
   - Scroll **down** → history days appear on the left, columns narrow; **up** → back to the default view.
     Today + future stay anchored on the right throughout.
4. Switch to **hourly** view → ←/→ pans by the nav-jump; existing drag/scroll still work.
