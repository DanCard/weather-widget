# Daily column: top half always opens the temperature graph

**Date:** 2026-09-03
**Plan:** [plans/260903-daily-column-top-half-always-opens-temperature.md](../plans/260903-daily-column-top-half-always-opens-temperature.md)

## Problem

Tapping a day column in the daily forecast view routed by *condition*, not by *where you tapped*.
`DayClickResolver.resolveView(MAIN_COLUMN, …)` opened the precipitation graph whenever the day
carried a rain-indicator icon and its precip probability cleared `DAILY_CLICK_PRECIP_THRESHOLD`
(16%) — and that icon set is wide, including `partly_cloudy_slight_chance_rain`. An ordinary day at
16% sent a temperature-seeking tap to a rain graph, with no way to say "just show me the temperatures".

Today's column was the worst offender twice over: it is the only column gated on a rolling 6-hour
max rather than the whole-day figure, so the same pixel routes differently at 06:00 and 14:00; and
with the large-today overlay on it is twice as wide as any other column, absorbing the most taps.

## Change

Split the main column band at the widget's vertical midpoint — the line the `nav_left`/`nav_right`
chevron glyphs are centred on:

- **Above the chevrons** → temperature graph, unconditionally, on **every** day column.
- **Below the chevrons** → the existing conditional routing, unchanged.
- **Bottom icon band** → icon-home routing, unchanged.
- **Night-rain cells** → night precip, unchanged.

Every column rather than today alone: a rule that applies to one column is a rule nobody can remember.

### `:shared` — the rule

`DayClickResolver.DayTapZone` gains `MAIN_COLUMN_UPPER`; `resolveView` returns `TEMPERATURE` for it
without reading the icon or the probability. The check sits *before* the `iconName == null` early
return — both return `TEMPERATURE` today, but that return is a heuristic that could grow a
condition, and the upper-half rule must never be downstream of one.

Putting the rule in `:shared` as a third enum case rather than as an Android `if` made the compiler
find the one consumer that needed updating: `DesktopWidgetPopup`'s `when (zone)` failed as
non-exhaustive.

### Android — the geometry

`RemoteViews` cannot hit-test, so the split had to be layout the launcher resolves itself.
`graph_day_zones` is now a **vertical** `LinearLayout` spanning the full widget height with two rows
at `layout_height="0dp"` + `layout_weight="1"`, putting the boundary on exactly `H/2`. The 44dp/140dp
exclusions moved from container margins onto the rows as padding, so the tappable extent is unchanged
outside the split:

| row | spans | children |
|---|---|---|
| `graph_day_top_zones` (new, 10 ids) | `0 … H/2` | `44 … H/2` |
| `graph_day_bottom_zones` (existing ids) | `H/2 … H` | `H/2 … H-140` |

Keeping the old margins would have put the band's own midpoint 48dp above the chevrons at every
widget height — the discrepancy is a fixed dp offset, not a fixed fraction, so no choice of weights
fixes it. Hence the full-height container.

The upper row binds through the same `setupGraphZoneClickHandlers` as the other two, so column
spans, Today's double-width slot and visibility stay in lockstep; only the resolved destination
differs. Request-code offset 200 (`BASE_GRAPH_CLICK + 200 = 2200`), clear of the bottom row's 100
and of `BASE_BOTTOM_HOUR_CLICK = 3000`.

### Desktop — the geometry

`classifyDailyGraphTapZone` returns `MAIN_COLUMN_UPPER` above `canvasHeight / 2`, checked *after*
the icon-rect hit test: an icon floated into the upper half by a cold low is still an aimed tap on a
glyph, so it keeps `BOTTOM_ICON`.

## Verification

Live widget on `emulator-5554`, Today's column carrying a rain icon at `precipGate=31(rolling6h)` —
the exact input that used to force precipitation. Both taps landed on the same column:

```
14:35:19  targetView=TEMPERATURE    clickSource=graph_day_upper:col=2   precipGate=31(rolling6h)
14:36:20  targetView=PRECIPITATION  clickSource=graph_day:col=2         precipGate=31(rolling6h)
```

The widget re-renders normally after the layout restructure (no blank or partial paint).

Tests: 3 new in `DayClickResolverTest`, 3 new in `DailyForecastGraphTapZoneTest`, and a new
`DailyUpperColumnTapZoneTest` covering routing, that the split lands on the chevron centre, and that
a column's two halves share an x-span. Both new Android assertions were shown to fail before
passing — forcing `resolveTargetMode = null` turned the upper tap back into `PRECIPITATION`, and a
`layout_weight="2"` on the top row moved the boundary to 264 against an expected 200. Full
`:shared:test`, `:desktop:test` and the app's `*Daily*`/`*Widget*` suites are green.

## Known consequence

On a widget shorter than ~280dp the lower row's children compute to zero height (`H/2 > H-140`), so
the whole column body becomes "upper" and column-body precip routing disappears there. The bottom
icon band and the night-rain cells still reach precipitation. Accepted: that band was only ~16dp
tall on such widgets to begin with.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/DayClickResolver.kt`
- `app/src/main/res/layout/widget_weather.xml`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyClickHandlerFactory.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DayClickHelper.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWidgetPopup.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyUpperColumnTapZoneTest.kt` (new)
- `shared/src/test/kotlin/com/weatherwidget/shared/util/DayClickResolverTest.kt`
- `desktop/src/test/kotlin/com/weatherwidget/desktop/DailyForecastGraphTapZoneTest.kt`
