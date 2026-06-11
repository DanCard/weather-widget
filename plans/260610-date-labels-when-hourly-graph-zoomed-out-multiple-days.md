# Hourly graph: date labels when zoomed out to multiple days

## Context

The hourly temperature graph draws bottom x-axis labels as **time-of-day** ("9a", "12p",
"12a"…) at every zoom level. When the view is zoomed out to span multiple days — Android's
THREE_DAY zoom (72h) and the desktop's wide continuous zoom (up to ~7 days) — these time
labels lose all day context: you see "12a … 12p … 12a … 12p" and can't tell *which* day each
region belongs to. The user wants the labels to **switch to dates** once multiple days are
visible.

**Decided behavior** (confirmed with user):
- When zoomed out to multiple days, **replace** the time-of-day labels with **one date label
  per visible day, centered under that day**. Weather icons stay (now one per day).
- Date format: **weekday + day-of-month**, e.g. `"Wed 11"`.
- Near/narrow zooms are unchanged (still time-of-day).

## Shared "centered date per day" rule

For every visible local date, choose the **in-window hour closest to local noon** and place
the date label there. This centers the label under each day and gracefully handles partial
edge-days (e.g. the oldest day whose noon falls before the window start still gets one label
at the nearest available hour). Same rule on both platforms.

---

## Android changes

The label string is baked into `HourData.label` and visibility is gated by `HourData.showLabel`
(built in `TemperatureHourDataBuilder.buildHourDataResult`). `GraphRenderUtils.drawHourLabels`
draws the inline `<label><icon>` group only for hours where `showLabel == true`, so flipping
labels to one-per-day also yields one icon per day — no renderer change needed (already verified
the inline branch reassembles and draws the full label string, so `"Wed 11"` renders correctly
next to its icon).

**1. New date-format helper** — `app/.../widget/handlers/WidgetFormatUtils.kt`
Add alongside `formatHourLabel`:
```kotlin
internal fun formatDateLabel(date: java.time.LocalDate): String =
    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) +
        " " + date.dayOfMonth   // e.g. "Wed 11"
```

**2. Date mode in the builder** — `app/.../widget/handlers/TemperatureHourDataBuilder.kt`
(top-of-hour loop, ~lines 218-280):
- `val dateMode = zoom == ZoomLevel.THREE_DAY` (the only multi-day Android zoom; WIDE/NARROW
  unchanged).
- When `dateMode`, precompute the **representative epoch-millis per visible date**: for each
  local date in `[startHour, endHour]`, take that date's local noon clamped into the window,
  snap to the nearest top-of-hour, collect into a `Set<Long>`.
- In the loop:
  - `showLabel` = `dateMode → (hourMs in representativeMillis)` else the existing
    `hourIndex % labelInterval == 0` logic.
  - `label` = `dateMode → formatDateLabel(currentHour.toLocalDate())` else
    `formatHourLabel(currentHour)`.
- The sub-hourly injection block (~lines 329-347) keeps `showLabel = false`; its `label` is
  unused for display, leave as `formatHourLabel(time)`.

No change to `GraphRenderUtils.drawHourLabels` or `TemperatureGraphRenderer`.

**Side effect (intended):** THREE_DAY icons go from every-12h (6 icons: midnight+noon) to one
per day at noon (3 icons). Noon icons are the more informative (daytime) ones. Flag if denser
icons are wanted — that would require decoupling icon cadence from label cadence in
`drawHourLabels`.

---

## Desktop changes

Desktop draws its own bottom strip in `TemperatureGraph.kt` (~lines 550-610), gating on
`localZdt.hour % labelIntervalFor(totalSpanHours)`, and already formats edge day-of-week via
`drawDayLabels` (~lines 672-702). Continuous zoom → `totalSpanHours` is available at the loop.

**1. New helpers** — `desktop/.../DesktopGraphUtils.kt`
- `const val DATE_LABEL_SPAN_THRESHOLD_HOURS = 48` (switch to dates once >2 days visible;
  tunable).
- `fun formatDateLabel(date: LocalDate): String` mirroring Android:
  `date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()) + " " + date.dayOfMonth`.
- A pure helper `fun representativeIndicesByDay(points): Set<Int>` implementing the
  closest-to-noon-per-day rule over the point timestamps (kept pure for unit testing).

**2. Bottom-strip loop** — `TemperatureGraph.kt`
- `val dateMode = totalSpanHours > DesktopGraphUtils.DATE_LABEL_SPAN_THRESHOLD_HOURS`.
- Gate: `dateMode → (i in representativeIndices)` else the existing `hour % labelInterval`.
- Label text: `dateMode → formatDateLabel(localZdt.toLocalDate())` else
  `formatHourLabel(time.hour)`. The existing text+icon group layout (clamping, `<text><icon>`)
  is reused as-is.
- Skip the separate `drawDayLabels(...)` call when `dateMode` (the bottom-strip dates now
  supply day context; keep it for non-date-mode so narrow zoom still shows the edge weekday).

Apply the **same gate/text swap** to the Cloud-cover and Precipitation bottom strips if they
share the `labelIntervalFor`/`formatHourLabel` pattern, for visual parity across the three
desktop graphs (confirm during implementation).

---

## Verification

**Android (unit, pure):**
- New `WidgetFormatUtilsTest` case: `formatDateLabel(LocalDate.of(2026,6,11))` → `"Wed 11"`.
- `TemperatureHourDataBuilder` test: with `zoom = THREE_DAY`, assert exactly one labeled
  (`showLabel`) HourData per visible date and its `label` matches `formatDateLabel`; with
  `zoom = WIDE`, labels are still time-of-day. Run:
  `./gradlew testDebugUnitTest --tests "*WidgetFormatUtils*" --tests "*TemperatureHourDataBuilder*"`.

**Android (visual):** `./gradlew installDebug`, add/resize a tall widget, zoom to THREE_DAY,
confirm one centered "Wed 11"-style label + icon per day; verify WIDE/NARROW still show times.
Per CLAUDE.md, capture via `adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png
/tmp/s.jpg` and read the JPG.

**Desktop (unit, pure):** test `DesktopGraphUtils.formatDateLabel` and
`representativeIndicesByDay` (one index per day, nearest noon).

**Desktop (visual):** rebuild + restart via `scripts/restart-desktop-distributable.sh`
(auto-restart per project convention); zoom the hourly graph out past ~2 days and to full
7-day, confirm per-day "Wed 11" labels replace times and the redundant interior edge
day-labels are gone; zoom back in to confirm times return.

## Notes / open knobs
- Threshold: Android keys off the discrete THREE_DAY zoom; desktop uses a 48h (>2 day)
  span threshold — easy to retune (e.g. 36h or 56h) after seeing it live.
- Icon density at wide zoom drops to one/day (intended). Decoupling icon vs label cadence is a
  follow-up if denser icons are desired.
- Format/“representative day” logic is duplicated per module (consistent with the existing
  desktop-vs-Android label divergence); a future shared `TemperatureLabelEngine` extraction
  could unify it.
