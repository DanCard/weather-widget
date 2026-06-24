# Plan: Gate the graph-bitmap rebuild on real change (stop the 2-min full repaint)

## Context

Investigating "the Samsung widget redraws frequently," device `app_logs` showed UI-only repaints
every ~2 minutes whenever plugged in + screen on — **16–24 full repaints/hour** of all widgets in
waking hours (vs 2–3/hr screen-off). Cause: `UIUpdateIntervalStrategy.PLUGGED_IN_MAX_DELAY_MS = 2 min`
caps the UI cadence while charging, overriding the temp-based 15–60 min schedule. Each fired cycle
(`UIUpdateScheduler` → `UIUpdateReceiver` → `WeatherWidgetWorker` `KEY_UI_ONLY_REFRESH=true`)
rebuilds the **entire graph bitmap** (`TemperatureGraphRenderer.renderGraph` — IDW blend, actuals,
extrema; ~800 ms of a ~1.9 s cycle) and re-pushes full RemoteViews via `updateAppWidget()`.

What visibly changes in 2 min is negligible: the current temp (a `TextView`) rarely crosses its
displayed value, and the NOW line + current-temp dot (baked *inside* the bitmap) shift ~1–2 px.

**Platform constraint:** widgets are `RemoteViews` in the launcher process — the app cannot repaint
individual pixels. The smallest unit is a View + its RemoteViews actions. So "only update what
changed" = update the cheap **TextView** via `partiallyUpdateAppWidget()` (skips `setImageViewBitmap`)
and rebuild the **bitmap** only when it would visibly move.

**Key discovery (Phase-1 exploration):** this fast path *already exists* but only for **anchored**
views (NOW scrolled off-window). `TemperatureViewHandler.updateWidget` (`handlers/TemperatureViewHandler.kt:68-87`)
early-returns to `updateAnchoredHeaderCurrentTemp` (`:217-261`) which does a header-only
`partiallyUpdateAppWidget` (`:260`). The gap is the **common live case: `uiOnly=true` with NOW
in-window**, which falls through to the full `TemperatureStateResolver.resolve()` → `renderGraph` →
`updateAppWidget()` (`:90`, `:125`) every cycle.

**Outcome:** extend the existing partial-update path to the live case, gated so the bitmap is rebuilt
only when the NOW line would move ≥ a small pixel budget or the displayed temp changes; otherwise push
a cheap header-only partial update. Current temp stays live; expensive rebuilds drop from ~24/hr to
~12/hr + on-change, with no perceptible difference.

## Approach (Phase 1 — TEMPERATURE view only)

Scope per user direction: implement for the TEMPERATURE handler, measure, then extend to
daily/cloud/precip (Phase 2, below).

1. **Pure decision function — new `GraphRepaintGate` object** (mirrors `UIUpdateIntervalStrategy`
   style; no Android deps; unit-tested). `shouldRebuildBitmap(...)` returns true when **any** of:
   - displayed current-temp string changed vs last full render (covers dot/label movement), OR
   - NOW-line horizontal drift since last render ≥ `NOW_DRIFT_PX` budget, where
     `driftPx = elapsedMin * (bitmapWidthPx / windowSpanMinutes)`, OR
   - elapsed since last render ≥ `MAX_BITMAP_INTERVAL_MS` (safety net).
   Default `NOW_DRIFT_PX ≈ 4` (≈ 5 min on a 12 h window; self-tightens when zoomed in where px/min is
   higher); `MAX_BITMAP_INTERVAL_MS ≈ 15 min`. Both tunable after measurement.

2. **Wire the gate into the live branch** of `TemperatureViewHandler.updateWidget`. In the existing
   `if (uiOnly)` block, the current code only fast-paths when `!nowInWindow`. Add the in-window branch:
   resolve the displayed temp (cheap — `CurrentTemperatureResolver` over the already-loaded
   now-centered hourly window), call `GraphRepaintGate.shouldRebuildBitmap(...)`; if **false**, do a
   header-only partial update and `return`; if **true**, fall through to the full render.

3. **Reuse `updateAnchoredHeaderCurrentTemp` for the live header update**, generalized with a
   `showDelta: Boolean` param (live = keep `current_temp_delta` visible; anchored = hide). Same
   `partiallyUpdateAppWidget` call. Avoids a second near-duplicate method.

4. **Persist last-render metadata per widget** in `WidgetStateManager` (SharedPreferences), following
   the existing multi-field `setCurrentTempDeltaState` pattern (`WidgetStateManager.kt:795-820`):
   `lastGraphRenderMs`, `lastDisplayedTemp` (the formatted string). Written **after every full
   TEMPERATURE render** (both uiOnly-fellthrough and normal). Window span (from
   `stateManager.getZoomLevel`) and bitmap width (from `WidgetSizeCalculator`) are recomputed at
   decision time — they're identical to the last render on a uiOnly cycle (user interaction that
   changes zoom/offset/source triggers a non-uiOnly full render, which refreshes the metadata).
   Absent metadata ⇒ gate returns true (must render).

5. **Decision logging** for measurement: one log per uiOnly in-window cycle, e.g.
   `WIDGET_PAINT state=header_only_live` (skipped) vs `state=data` (full), plus a `BITMAP_GATE`
   line with the reason (`temp_changed|now_drift=<px>|max_interval`). Use DEBUG initially to count
   skips/rebuilds from `app_logs`; downgrade to `Log.v` after a few days per the established
   high-frequency-log convention ([[verbose_level_for_high_frequency_logs]]).

### Critical files
- `app/src/main/java/com/weatherwidget/widget/GraphRepaintGate.kt` — **new** pure gate object.
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt` — add in-window
  uiOnly branch (~`:68-90`); generalize `updateAnchoredHeaderCurrentTemp` with `showDelta`; record
  last-render metadata after full render (~`:125`).
- `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` — add
  `get/setLastGraphRender(widgetId, …)` following `CurrentTemperatureDeltaState` (`:770-820`).
- Reuse: `CurrentTemperatureResolver.resolve` / `formatDisplayTemperature`
  (`shared/.../widget/CurrentTemperatureResolver.kt:293-302`), `WidgetSizeCalculator` (bitmap width),
  `NowIndicatorGeometry` (shared) for NOW-x if a px-exact drift is preferred over the px/min estimate.

### Phase 2 (after measuring Phase 1) — daily / cloud / precip
`DailyViewHandler`, `CloudCoverViewHandler`, `PrecipViewHandler` each do a full `updateAppWidget`
every uiOnly cycle with no live current temp. Daily shows no sub-hourly moving element ⇒ **skip
uiOnly rebuilds entirely**. Cloud/Precip have only the in-bitmap NOW line ⇒ apply the same
`GraphRepaintGate` NOW-drift check (no header partial needed; a skipped cycle = no update). These 4
widgets are the bulk of the per-cycle cost, so Phase 2 is the larger absolute win — deferred only to
validate the gate on TEMPERATURE first.

### Optional complementary tweak
Raising `PLUGGED_IN_MAX_DELAY_MS` (2 → ~5 min) further cuts even the now-cheap cycles. Not required
once the gate lands (skipped cycles are inexpensive), but a safe one-liner if we want fewer wakeups.

## Testing
- `GraphRepaintGateTest` (plain JUnit, `@Category(ShortDuration::class)`, like
  `UIUpdateIntervalStrategyTest`): temp-changed ⇒ true; NOW-drift just below/above `NOW_DRIFT_PX`;
  elapsed just below/above `MAX_BITMAP_INTERVAL_MS`; absent/zero last-render ⇒ true.
- `WidgetStateManager` round-trip test for the new get/set (Robolectric, `setPrefsNameOverrideForTesting`
  pattern from `WidgetStateManagerTest.kt:24-31`).
- Run: `./gradlew :app:testDebugUnitTest --tests "*GraphRepaintGateTest" --tests "*WidgetStateManagerTest"
  --tests "*TemperatureViewHandler*"` and the existing temperature/UIUpdate suites.

## Verification (end-to-end)
1. `./gradlew installDebug`; add a TEMPERATURE-mode widget in the live (NOW-in-window) view; plug in
   the device; keep screen on.
2. After ~30 min, pull the DB (`run-as com.weatherwidget cat databases/weather_database`) and query
   `app_logs`: confirm `state=header_only_live` skips dominate and `state=data` full rebuilds drop to
   ~12/hr (+ temp-change events) vs the ~24/hr baseline. Cross-check `SYNC_PERF`: skipped cycles show
   no `renderMs`.
3. Visually confirm: current temp still updates each cycle; the NOW line/dot advances every few
   minutes (not frozen, not jumpy); anchored (past/future day) behavior unchanged.
4. Compare CPU/wake cost informally (logged cycle `total` ms) before/after.

## Risks
- **Bitmap drift**: gating on the formatted temp string means the dot's sub-degree vertical creep
  isn't caught until the string changes or `MAX_BITMAP_INTERVAL_MS` fires — bounded to a few px, then
  corrected; imperceptible.
- **Metadata staleness on window change**: mitigated because any zoom/offset/source/viewmode change is
  a non-uiOnly full render that rewrites the metadata; gate only ever skips when window is unchanged.
- **Log volume**: DEBUG decision logs add rows; downgrade to VERBOSE after the measurement window
  ([[desktop_app_logs_currenttemp_swamp]] / [[verbose_level_for_high_frequency_logs]]).
