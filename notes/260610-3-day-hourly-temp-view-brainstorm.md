# 3-Day View of the Hourly Temperature Graph — Brainstorm

_Date: 2026-06-10_

## TL;DR

The data side is almost free: the widget already fetches `HOURLY_LOOKBACK_HOURS = 72`
back and `HOURLY_LOOKAHEAD_HOURS = 60` ahead (plus `HOURLY_GRAPH_LOOKAHEAD_HOURS = 168`
for day-clicks), so a ~72h window is already in the DB. The real questions are **how the
user triggers a 72h span** and **how labels behave** at that span.

## Key architecture facts

- The hourly view's span is **not hardcoded** in the renderer — it's driven by the
  `ZoomLevel` enum in `WidgetStateManager.kt`. Each level carries `backHours`/`forwardHours`
  plus per-level tuning (`navJump`, `labelInterval`, `smoothIterations`).
  - `WIDE(back=12, forward=12)` → 24h span
  - `NARROW(back=2, forward=2)` → 4h span
- The X-axis maps `hours.first()..hours.last()` → `[0, width]` in
  `TemperatureGraphRenderer.renderGraph()`, so the curve auto-stretches to fill whatever
  span you feed it. Widening "just works" geometrically.
- The renderer currently labels only the **global** min/max in the window. Over 3 days that
  labels one peak + one valley out of three — so a good 3-day view likely needs **per-day
  extrema**, which is the one genuinely new bit of rendering logic.
- View modes today: `DAILY / TEMPERATURE / PRECIPITATION / CLOUD_COVER`
  (`WidgetStateManager.ViewMode`).
- `hourlyOffset` range is -720..+720 hours; nav arrows jump 6h.
- Staleness age label already self-hides at `>12h` span
  (`AGE_LABEL_MAX_HOURS_SPAN = 12`, `TemperatureGraphStyle.kt`).

## Activation options

### A. Add a third `ZoomLevel` (lowest effort, most consistent)
- New entry e.g. `THREE_DAY(backHours = 24, forwardHours = 48, …)` or symmetric `36/36`.
  Asymmetric (less past, more future) is arguably more useful for a forecast.
- Cycle becomes `NARROW → WIDE → THREE_DAY → NARROW` on the existing graph-tap-to-zoom
  gesture (`toggleViewMode`/zoom cycle on Android, `onToggleZoom` on desktop). Zero new tap
  targets, zero new intents.
- Downside: a 3-state cycle is less discoverable than 2-state.

### B. Make 3-day a distinct view mode (`ViewMode.TEMPERATURE_3DAY`)
- Sits alongside `DAILY / TEMPERATURE / PRECIPITATION / CLOUD_COVER`; gets its own cycle slot
  and graph-selector chip.
- Cleaner mental model ("a different chart"); can carry its own label/smoothing config.
- More plumbing: new enum value, handler wiring, RemoteViews visibility resets (watch the
  sticky-visibility gotcha), desktop string branch in `Main.kt`.

### C. Settings preference for "default hourly span" (12h / 24h / 3-day)
- For users who always want the wide view. No user-facing display-mode setting exists today
  (span lives per-widget in prefs), so this would be the first "global default" of its kind.
- Pairs with A or B as the thing that sets the initial `ZoomLevel`/mode.

### D. Repurpose the day-click entry point
- Day-click already jumps the hourly graph to that day's noon at `WIDE` (±12h). Make it open
  a **3-day window centered on the clicked day** (yesterday-today-tomorrow context) instead.
  Costs the user no new gesture.

### E. Size-driven auto-span (responsive)
- Let the window scale with widget width: 5–6 column widget auto-shows 3 days, a 2-column one
  stays at 24h. `WidgetSizeCalculator` already computes `cols`; map `cols → forwardHours`.
  Activation = resizing the widget, in keeping with existing size-driven behavior.
- Mostly an Android idea (desktop panel is fixed-ish).

### F. Gesture-based, platform-specific
- Desktop only: scroll-wheel or pinch over the graph for continuous zoom. The widget can't
  pinch, so Android stays on discrete `ZoomLevel`s — A/B preserve cross-platform parity better.

## Rendering work needed regardless of trigger

- **Per-day extrema labels** — current single global min/max under-labels a 72h span. Want one
  high + one low per day. Lives in the `TemperatureGraphRenderer` label cascade/collision code.
  Note: desktop has a **separate, simpler** label engine, so the fix won't cross over for free.
- **Label thinning** — grow `labelInterval` (hour labels every 6–12h) or switch to day-boundary
  labels ("Mon / Tue / Wed") to avoid crowding.
- **Midnight gridlines / day separators** — near-mandatory for legibility at 3 days
  (`GraphRenderUtils` hosts hour labels + now-indicator).
- **Smoothing** — fewer `smoothIterations` at this span (already tunable per `ZoomLevel`).
- **Staleness age label** — already self-hides at `>12h`; no work needed.

## Recommendation

- **Option A** is the smallest, parity-preserving change (one enum row + cycle wiring, reuses the
  existing zoom tap).
- **Per-day extrema labels** is the feature that makes it genuinely useful rather than a squished
  line.
- **Option B** is the better long-term home if the 3-day chart is expected to diverge a lot from
  the 1-day one.

## Relevant files

| Purpose | File |
|---------|------|
| Zoom definition | `app/.../widget/WidgetStateManager.kt` |
| Zoom application | `app/.../widget/handlers/TemperatureHourDataBuilder.kt` |
| Graph rendering + label cascade | `app/.../widget/TemperatureGraphRenderer.kt` |
| Shared graph utils (hour labels, now line) | `app/.../widget/GraphRenderUtils.kt` |
| Age label gate | `app/.../widget/TemperatureGraphStyle.kt` |
| Data query window | `app/.../widget/handlers/GraphDataLoader.kt`, `WeatherWidgetProvider.kt` |
| Widget size → layout | `app/.../widget/handlers/WidgetSizeCalculator.kt`, `WidgetRenderer.kt` |
| Tap/intent routing | `app/.../widget/handlers/HeaderTapTargetHelper.kt`, `WidgetIntentRouter.kt` |
| Desktop view logic / graph | `desktop/.../Main.kt`, `desktop/.../TemperatureGraph.kt` |
