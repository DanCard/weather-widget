# Daily column: top half always opens the temperature graph

**Date:** 2026-09-03
**Status:** done — verified on the emulator widget (see Verification)

## Problem

Tapping a day column in the daily forecast view routes by *condition*, not by *where you
tapped*. `DayClickResolver.resolveView(MAIN_COLUMN, …)` opens the precipitation graph whenever the
day carries a rain-indicator icon and its precip probability clears
`DAILY_CLICK_PRECIP_THRESHOLD` (16%).

That set is wide — `partly_cloudy_slight_chance_rain` and `cloudy_slight_chance_rain` are rain
indicators — so an ordinary day with a 16% chance sends a temperature-seeking tap to a rain graph.

Today's column is the worst offender for two independent reasons:

- It is the only column gated on a **rolling 6-hour max** (`routingPrecipProbability`), not the
  whole-day figure, so the same pixel routes differently at 06:00 and at 14:00.
- With the large-today overlay on, `DailyLargeTodayOverlayPolicy.TODAY_SLOT_SPAN = 2` makes it
  twice as wide as any other column, so it absorbs the most taps.

There is no way to say "just show me the temperatures".

## Decision

Split the main column band horizontally at the widget's vertical midpoint — the line the
`nav_left` / `nav_right` chevron glyphs are centred on:

- **Above the chevrons** → temperature graph, unconditionally, on **every** day column.
- **Below the chevrons** → today's conditional routing, unchanged.
- **Bottom icon band** → icon-home routing, unchanged.
- **Night-rain cells** → night precip, unchanged.

Every column, not only today: a rule that applies to one column is a rule nobody can remember.

## Implementation

### 1. `:shared` — the rule

`DayClickResolver.DayTapZone` gains `MAIN_COLUMN_UPPER`. `resolveView` returns `TEMPERATURE` for
it without consulting the icon or the probability. Both platforms inherit the rule.

### 2. Android — the geometry

`RemoteViews` cannot hit-test, so the split has to be layout the launcher resolves itself.
`graph_day_zones` becomes a **vertical** `LinearLayout` spanning the full widget height, holding two
horizontal rows at `layout_height="0dp"` + `layout_weight="1"`. Equal weights put the boundary at
exactly `H/2`, which is where the chevrons centre (`layout_gravity="…|center_vertical"`).

The existing 44dp/140dp exclusions move from container margins onto the rows as padding, so the
tappable extent is unchanged outside the new split:

| | span | children |
|---|---|---|
| `graph_day_top_zones` | `0 … H/2` | `paddingTop=44dp` → `44 … H/2` |
| `graph_day_zones` (existing row) | `H/2 … H` | `paddingBottom=140dp` → `H/2 … H-140` |

10 new ids (`graph_day1_top_zone` … `graph_day10_top_zone`) mirroring the existing row.
`setupGraphDayClickHandlers` binds the new row through the existing
`setupGraphZoneClickHandlers` with `resolveTargetMode = { TEMPERATURE }` and
`requestCodeOffset = 200` (0 = main row, 100 = bottom row; `BASE_GRAPH_CLICK + 200 = 2200`, clear
of `BASE_BOTTOM_HOUR_CLICK = 3000`).

**Known consequence:** on a widget shorter than ~280dp the lower row's children compute to zero
height (`H/2 > H-140`), so the whole main column becomes "upper" and column-body precip routing
disappears there. The bottom icon band and night-rain cells still route to precip. Acceptable —
the band was only ~16dp tall on such widgets to begin with.

### 3. Desktop — the geometry

`classifyDailyGraphTapZone` returns `MAIN_COLUMN_UPPER` for `tapY < canvasHeight / 2`, after the
icon-rect hit test (a direct hit on a rendered icon stays `BOTTOM_ICON` — it is an aimed tap on a
glyph, not a body tap) and after the bottom-strip check.

## Testing

- `:shared` `DayClickResolverTest` — `MAIN_COLUMN_UPPER` returns `TEMPERATURE` for a rain icon at
  100% (the case that would otherwise route to precip).
- Desktop `DailyForecastGraphTapZoneTest` — a tap above the midpoint classifies `MAIN_COLUMN_UPPER`;
  one below stays `MAIN_COLUMN`; an icon hit above the midpoint stays `BOTTOM_ICON`.
- Android `DailyViewGraphClickAlignmentTest` (Robolectric) — the new row is visible for the same
  slot count as the existing row, and its columns share the existing row's x-geometry.
- Android: a rain day whose lower-zone intent carries `PRECIPITATION` carries `TEMPERATURE` on the
  upper zone.

## Verification

Live widget on `emulator-5554`, Today's column carrying a rain icon at `precipGate=31(rolling6h)`
— the exact input that used to force precipitation. Both taps landed on the same column:

```
14:35:19  targetView=TEMPERATURE    clickSource=graph_day_upper:col=2   precipGate=31(rolling6h)
14:36:20  targetView=PRECIPITATION  clickSource=graph_day:col=2         precipGate=31(rolling6h)
```

The widget re-renders normally after the layout restructure (no blank/partial paint).

Both new Android assertions were shown to fail before passing: forcing `resolveTargetMode = null`
turned the upper tap back into `PRECIPITATION`, and a `layout_weight="2"` on the top row moved the
boundary to 264 against an expected 200.
