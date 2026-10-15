# Months view — a line-graph zoom-out of the daily view (Android app screen + desktop)

## Context

The daily view can't zoom out on a home-screen widget: RemoteViews has taps only (no pinch,
scroll or long-press), and a small widget's header has no room for a zoom icon. The user chose an
**app screen** (2026-10-15) that:

1. shows **2 months** at once by default (user, 2026-10-15), with pinch to change the span (below);
2. draws it as a **line graph, like the hourly view** — daily high and daily low as lines over time,
   **not** the daily view's columns;
3. **zooms back in on a tap**: tapping a point opens the regular daily (column) view for that time
   range.

Small widgets have no header icon for this screen. That is acceptable (user, 2026-10-15) as long
as the screen has another way in (see "Entry points").

## What the data can support

| Range | Source | Notes |
|---|---|---|
| Past, up to 18 months | `daily_history` (`RetentionPolicy.DAILY_HISTORY_DAYS = 547`) | actual high/low; frozen forecast columns (`forecastHigh/LowTemp`, `priorForecastHigh/LowTemp`) since v72/v73 |
| Past 30 days | also `forecasts` | the widget's own daily view reads this (`WidgetDataBundleLoader.fetchForecastSnapshots`) |
| Future, ~7–16 days | `forecasts` rows for the displayed source | horizon depends on the source |
| Any date | `climate_normals` (current location, keyed by `monthDay`) | gives context even for months with no rows |

So the months view is almost entirely **history**, with a short forecast tail on the right. "Today" is
the anchor; the default scroll position puts today near the right edge.

## Design

### Months view (line graph)

- **Lines**:
  - **Actual high** and **actual low**: one point per day from `daily_history`, for the displayed
    source and the active site. Pick the actual the same way the past-day centre bar does, so the two
    views never disagree.
  - **Forecast high and low** from today forward: the same colour, dashed. This matches how the
    hourly view separates actual from forecast.
  - **Climate normal high and low** as a faint band behind everything. It is cheap to draw, and over
    months it is the most useful context.
- **Raw values, no smoothing** (memory: "no smoothing on the truth curve"). A missing day **breaks the
  line**. Never interpolate across gaps or fill them with normals (memory: "recover real data, don't
  substitute a fallback").
- **X axis**: month ticks and labels ("Jul", "Aug"…), with a year label where the year changes. A thin
  vertical "today" marker.
- **Y axis**: `NiceAxisScale` over the visible window's values plus the normals.
- **Labels**: only each visible month's extreme high and extreme low, through the existing
  label-placement engine (`index_label_placement` memory). No per-day labels: at 2 months each day
  is only about 6 px wide on a phone.
- **Span (how many months)**: pinch changes it in steps of **1 → 2 → 6 → 12 months**.
  - Default: **2 months** (user, 2026-10-15). The last-used span is remembered in prefs.
  - The span is clamped to the data that actually exists.
- **Pan**: horizontal drag or fling through the 18-month record. The view stops at the oldest
  `daily_history` row and at the end of the forecast.
- **Source**: the widget's displayed source, plus a source button that cycles sources the way
  `ForecastHistoryActivity.cycleApiSource` does.
- **Location**: the active site only, read through the `LocationMatch` proximity box. Months spent at
  another location show as gaps. They are not merged in, because a different city is not this
  location's history.

### Tap → zoom to the daily (column) view

- Tapping maps the tap's x position to a date. The screen then switches to the **regular daily
  column view centred on that date**, inside the same screen.
- The column view is drawn by the existing `DailyForecastGraphRenderer.renderGraph` into a
  full-width bitmap. Its columns are prepared by `DailyViewLogic.prepareGraphDayInputs`, so it looks
  exactly like the widget: triple bars on past days, rain labels, icons.
- The column count is derived from the screen width, the same way the widget derives it.
- Inside the column view:
  - Swipe left or right steps one column at a time.
  - **Back** returns to the months view at the same position.
  - A **zoom-out pinch** (fingers together) adds columns. Once they pass the shared density
    threshold (`DailyZoomDensity`, below), it switches to the months view centred on the same date.
    This is the same rule the desktop scroll wheel uses.
  - Tapping a day there does nothing in v1. It could open hourly in a later phase.
- **Every date behaves the same** (user, 2026-10-15): a tap opens the daily view for that date,
  whether or not it is in the last 30 days. Nothing hands off to the widget, and Back always returns
  to the months view.
- **A recent date must look exactly as it does on the widget.** For the last 30 days the loader reads
  the same `forecasts` rows the widget reads (`fetchForecastSnapshots`), and older dates use the
  frozen `daily_history` columns. `prepareGraphDayInputs` already does this split, so it only needs
  the right date range. Test: the same date inside 30 days renders the same `DayData` from the
  screen's loader as from the widget's loader.
- **Why inside the screen and not the widget's daily view?** The widget cannot reach far enough back.
  Its date offset is clamped to `MIN_DATE_OFFSET = -30` … `MAX_DATE_OFFSET = 14`, and its loader reads
  30 days of history. A months view whose tap only works for the last 30 days would feel broken.
- **Risk to verify first**: whether days older than 30 days still draw complete columns. `forecasts`
  rows for those days have been pruned, so the past-day forecast bars depend on the frozen
  `daily_history` columns (`DailyHistoryFreeze`, `priorForecast*`). Rows written before v72/v73 have
  no frozen columns. For those, the bars must draw as "absent", never fabricated from something else.

### Entry points

1. **Launcher icon**: `MainActivity` gets a prominent "Months view" entry. It works for every widget
   size, including those with no header room.
2. **Widget daily header icon**, shown only when `positionDailyIcons` has slack for it. Centre icons
   give up their space and are never allowed to truncate the left text (memory: header slack). It
   opens the screen centred on the widget's current centre date.
3. **Forecast History** gets a link, since both screens are about the past.

## Shared code (`:shared`), used by both platforms

**Principle (user, 2026-10-15: "lots of shared code")**: every *decision* lives in `:shared`. Each
platform keeps only drawing calls (Android `Canvas` / Compose `DrawScope`), input plumbing
(`ScaleGestureDetector` / `pointerInput`), and its own DAO. If a test needs a device or a window to
check behaviour, that logic is in the wrong module.

| Concern | `:shared` | Android | Desktop |
|---|---|---|---|
| Zoom state machine: DAILY ↔ MONTHS, span steps, scroll-in at 1 month → daily | `MonthsZoomController` | feeds pinch and tap events | feeds wheel, drag, click and arrow-key events |
| "Too dense" threshold | `DailyZoomDensity` | — | — |
| Window, pan clamp, tap→date, column window around a date | `MonthsViewModel` | — | — |
| Which rows to load (date range + 1-month margin, sources) | `MonthsLoadPlan` | Room query | `DesktopWeatherDao` query |
| Choosing the actual (same pick as the past-day centre bar) | existing `ApiActualPicker` + a `MonthsActualPick` wrapper | — | — |
| Series: segments broken at gaps, actual vs forecast split at today, normals band | `MonthsSeriesBuilder` | — | — |
| Month and year ticks, Y scale | `MonthsAxis` + `NiceAxisScale` | — | — |
| Which labels show (each month's extreme high/low) and their placement | `MonthsLabelPlanner` on the existing placement engine | measures text | measures text |
| Colours, stroke widths, dash pattern | `MonthsGraphStyle` (like `ForecastEvolutionStyle`) | converts to `Paint` | converts to `Color`/`Stroke` |
| Pixel geometry | `MonthsGraphGeometry` | draws it | draws it |

The pieces:

- **`MonthsViewModel`**: pure functions.
  - window (start, end) from the span, the pan position and the data bounds;
  - span step up and down, and clamping;
  - mapping a tap's x position to a `LocalDate`;
  - choosing the column-view window centred on a date.
- **`MonthsSeriesBuilder`**: turns per-day rows into high and low segments that break at gaps, and
  splits actual from forecast at today. It takes field extractors, like `ApiActualPicker`, because
  `:shared` can't see Room entities.
- **`MonthsAxis`**: positions for month and year ticks.
- **`DailyZoomDensity`**: the single rule for "the columns are too dense, switch to the months
  view": `tooDense(dayWidthDp)`, true below `MIN_DAY_WIDTH_DP`. The starting value is about 28 dp,
  where the triple bar and its labels stop fitting; tune it on screen. The desktop scroll wheel and
  the Android column view's zoom-out pinch both ask it.
- **`MonthsGraphGeometry`**: the pure geometry for the renderer, following the
  `ForecastEvolutionGeometry` / `ForecastEvolutionRenderer` split.

## Desktop (`:desktop`): scroll-wheel zoom-out hands off to the months view

Added by the user, 2026-10-15. Today, scrolling out on the desktop daily view adds history columns
on the left (`dailyExtraHistory`, up to `DAILY_MAX_EXTRA_HISTORY = 14`), and the columns narrow
until they are cramped.

- **Hand-off**: on scroll-out, if the next column count would make `DailyZoomDensity.tooDense(...)`
  true, switch to a new **`ViewMode.MONTHS`** instead of adding a column. The months view opens at
  the 1-month span, ending at the daily view's right edge, so the content the user was looking at
  stays in place. `DAILY_MAX_EXTRA_HISTORY` stays as a backstop, but the density threshold is now
  what normally ends daily zoom.
- **Months view on desktop**:
  - The scroll wheel steps the span (1 → 2 → 6 → 12 months).
  - Drag pans, and ←/→ pan by one week.
  - Click jumps to the daily view centred on that date, with `dailyExtraHistory = 0`.
  - Scrolling **in** at the 1-month span goes back to the dense daily view. That makes the wheel
    reversible end to end.
- **Rendering**: a Compose `Canvas` driven by the same `MonthsGraphGeometry` / `MonthsSeriesBuilder`
  as Android. Data comes from `DesktopWeatherDao` (`daily_history`, `forecasts`, normals).
- **Clicking into a date older than the desktop daily view can reach**: the daily view's
  `dateOffset` is bounded by `availableDates`. Check whether that includes all of `daily_history`,
  and widen it if it does not. Same risk as Android: past days need their frozen columns.
- **Persistence**: `viewMode = MONTHS`, plus `monthsSpan` and the pan position, go in
  `DesktopConfig`, as `dailyExtraHistory` already does.

**What is not shared**: only a thin `MonthsGraphRenderer` on Android and a `MonthsGraph`
composable on desktop. Each walks `MonthsGraphGeometry` and issues draw calls. Equivalence tests
assert that both platforms receive identical geometry for the same input. Drawing is not
duplicated logic.

## Android code (`:app`)

- **`MonthsViewActivity`**, using the `DataScreenStyler` look: a custom `View` that hosts both
  levels (months and columns), with a `ScaleGestureDetector` for pinch and a `GestureDetector` for
  pan, fling and tap.
- **`MonthsGraphRenderer`**: draws on a Canvas from `MonthsGraphGeometry`. Paints come from
  `TemperatureGraphStyle` so the lines match the hourly view.
- **Data loader**: one `daily_history` range query for the window plus one month on each side (the
  index on `date, locationLat, locationLon` already exists), the future `forecasts` rows, and
  `climate_normals`. The column view needs the same bundle shape that `prepareGraphDayInputs`
  expects, but for an arbitrary window. Extend `WidgetDataBundleLoader` with an explicit date range
  rather than copying it.
- **Manifest** entry. Strings go through the 19-locale parity check.

## Tests (automated first)

- `:shared` unit tests:
  - `MonthsViewModel`: window and clamp at the data edges, span steps, tap→date at the edges and
    across a month boundary, the column window centred on a tapped date.
  - `MonthsSeriesBuilder`: gaps break segments, the actual/forecast split at today, no interpolation,
    and a source that has no actuals.
  - `MonthsAxis`: month and year ticks across Dec→Jan.
- `DailyZoomDensity`: the threshold boundary.
- `MonthsZoomController`: every transition, including wheel and pinch sequences that go out and come
  back. The same event sequence must give the same state on both platforms, because they share the
  controller.
- `MonthsLoadPlan`, `MonthsLabelPlanner`, `MonthsGraphGeometry`: plain JVM tests in `:shared`.
- Desktop: `DesktopDailyForecastModel` reports a scroll-out at the density limit as a switch to
  months rather than another column; a click in the months view sets `dateOffset` to the clicked date
  with `dailyExtraHistory = 0`; scrolling in at 1 month returns to the daily view.
- **Robolectric integration test** (loader + model + renderer geometry): seed `daily_history` with
  120 days that include a gap and a move to a different site, then assert:
  - the segments break at the gap;
  - the other site's rows are excluded;
  - tapping on day 45 produces a column view whose days include day 45, with the frozen forecast
    bars present.
  - Assert dp geometry, not text pixels (Robolectric has no font engine).
- **Column view older than 30 days**: a day older than 30 days with frozen columns renders its
  triple bar, and a pre-v72 day renders "absent" sides.

## Verification on a device

1. `./gradlew testDebugUnitTest` plus the new `:shared` tests.
2. `./gradlew installDebug`, open the screen from the launcher, and take screenshots (converted to JPG
   as `CLAUDE.md` describes) at 1, 2, 6 and 12 months.
3. Pull the DB (`scripts/backup_databases.py`) and check one month's plotted extremes against
   `daily_history` with `sqlite3`.
4. Tap a point about 2 months back and check that the column view matches that range. Then use Back
   and check that the months view keeps its position.
5. Desktop: `scripts/buildStart-desktop.sh`, then scroll out on the daily view until it switches
   to the months view, scroll back in, and click into a date about 2 months back.

## Open questions

- **Tap a day in the column view → hourly**: deferred to a later phase.

## Implementation notes (2026-10-15)

What was built, and where it differs from the plan above:

- **`:shared` `com.weatherwidget.shared.months`**: `MonthsSpan`, `DailyZoomDensity` (28 dp),
  `MonthsZoomController`, `MonthsViewport`, `MonthsDailyJump`, `MonthsSeriesBuilder`, `MonthsAxis`,
  `MonthsGraphStyle`, `MonthsGraphGeometry`. Tests in `MonthsViewTest`.
  - The plan's `MonthsLoadPlan` was not needed. Both platforms already load through
    `DailyActualsAssembler`; the months view only widens its lookback, to
    `RetentionPolicy.DAILY_HISTORY_DAYS`.
  - Past days use the column view's own rule (`DailyDayValueResolver.resolvePastLineValues`): the
    actual, else the frozen forecast, drawn dashed.
  - Labels: each month's extreme high and low, placed greedily with overlaps dropped. This is not the
    hourly label-placement engine, which is built around hourly curves.
  - Lines are coloured by the app's temperature gradient (`TemperatureColorModel`), piece by piece.
  - `MonthsDailyJump` takes the column count per offset (`columnsAt`), because desktop draws one fewer
    column while its Today overlay is on.
- **Desktop**: not a new `ViewMode`. Android persists that shared enum, and `isGraphMode` treats every
  non-DAILY value as hourly. Instead the months view is `DesktopConfig.monthsView` over DAILY, with
  `monthsSpan` and `monthsEnd` alongside it.
  - The snapshot already held 547 days of actuals. Normals were added to `RawFetch.climateNormals`.
  - `DesktopDailyForecastModel.displayColumns` was extracted so the jump can ask it.
  - The home button's recentre also leaves the months view.
  - Tests: `DesktopMonthsModelTest` and `MonthsGraphUiTest`.
- **Android**: `MonthsViewActivity`, `MonthsScreenView`, `MonthsScreenLogic` and `MonthsGraphRenderer`.
  - Data comes from the widget's own `WidgetDataBundleLoader`, plus
    `fetchDailyActuals(lookbackDays = 547)` for the viewed source.
  - The column view is `DailyViewLogic.prepareGraphDayInputs` and `DailyForecastGraphRenderer.renderGraph`,
    the widget's own path. `DailyViewHandler.buildWeatherByDate` was extracted so the screen picks the
    same rows.
  - Entry points: a launcher-screen button (`MainActivity`) and a Forecast History button.
  - Tests: `MonthsScreenLogicTest` and `MonthsViewDataIntegrationTest`.
- **Column view looks like the widget** (user, 2026-10-15):
  - It is drawn at the widget's shape and column count: the requesting widget, else the largest
    graph-mode widget installed, else 5×2.
  - It uses the widget's background, its 12/8 dp padding and its in-bitmap header (source, the
    middle column's date, and the current temperature only while today is on screen).
  - It sits on the real wallpaper (`FLAG_SHOW_WALLPAPER`; the window is translucent from `onCreate`).
- **Widget header zoom-out icon** (user, 2026-10-15). Daily header, graph mode, between home and
  history, centred and inline (`months_view_*` zones, `ic_months_view`).
  - Seated by `HeaderWidthChecker.resolveDailyIconLayoutFromBounds(wantMonths)`, after home, and
    only when it keeps the row on the same rung. It never pushes the others from CENTER to INLINE,
    and it is absent where there is no room.
  - It is counted in `iconCount`, so the painted date yields to it.
  - Hidden explicitly in hourly (`positionCenterIcons`), text mode and the source-warning state,
    because RemoteViews visibility is sticky.
  - It opens `MonthsViewActivity` directly (`PendingIntent.getActivity`, `WidgetRequestCodes.monthsView`).
  - Desktop has the same button in its daily header (`open_months_view_daily`), hidden while the
    months view is open.
  - Tests: `DailyIconLayoutLadderTest` (6 new) and `DesktopUiTest.dailyHeaderMonthsButtonOpensTheMonthsLineGraph`.
