# Sortable columns in "All sources for <day>"

## Request

User (2026-11-12): "History of forecasts, All sources. Can we click on a column heading to sort by
that column? For example on desktop I'm seeing tomorrow and would like to see it sorted by day rain
chance."

## Current behavior

- `DaySourceComparison.build` (`:shared`) returns rows already sorted by high, descending; no-high
  last; ties keep the user's source order.
- Desktop `DaySourceComparisonTable` and Android `ForecastHistoryActivity.renderSourceComparison`
  draw the header as plain text. Neither handles a tap.

## Change

### Shared (`DaySourceComparison`), owns the whole rule

1. `enum class SortColumn { SOURCE, HIGH, LOW, DAY_RAIN, NIGHT_RAIN, AMOUNT, CLOUD, BIAS, AGE }`, in the
   same order as the columns (index = column index, so `AMOUNT_COLUMN` stays valid).
2. `data class Sort(val column: SortColumn, val descending: Boolean)`, with `DEFAULT = Sort(HIGH, true)`
   (today's order).
3. `fun Sort.tapped(column)`: tapping the sorted column reverses its direction. Tapping another column
   sorts by that column in its natural first direction:
   - descending: High, Low, Rain, Night, Amount, Cloud (biggest or wettest first)
   - ascending: Source (A–Z by short name), Age (freshest first)
   - Bias: descending by signed value (warmest-running first)
4. `fun sort(rows, sort): List<Row>`. Rows with no value for the column always go last, whichever
   direction is chosen. Ties keep the user's source order (stable sort over the `build` output order,
   which is the visible-source order before the high sort, so `build` will keep the source order and
   `load` will apply `sort(…, DEFAULT)`).
5. `fun headerLabel(base, column, sort)` appends `▾`/`▴` to the sorted column's header. Both
   platforms print the same text.
6. Log: `DAY_SOURCE_SORT date=… column=DAY_RAIN desc=true order=nws,google,…` at `Log.d`, only on a
   user tap, so it stays sparse.

### Desktop

- `ForecastHistoryWindow` reads the stored sort, holds it in Compose state, and writes it back on every tap.
- `DaySourceComparisonTable` takes `sort` and `onSortTap`. Header cells are `clickable`, rows are drawn
  from `DaySourceComparison.sort(rows, sort)`, and the arrow label marks the sorted column.

### Android

- `ForecastHistoryActivity` reads the stored sort, writes it on every tap, and caches the last loaded rows. A header `TextView` click calls `tapped`, then re-renders from the
  cached rows without reloading.
- Header cells get a ripple background and a content description ("Sort by Rain").

### Persistence

User (2026-11-12): "Yes, remember the sort." The sort is stored in prefs on each platform. One
string key, `source_compare_sort`, holds `<COLUMN>:<asc|desc>`. Parsing and formatting live in
`DaySourceComparison.Sort.encode/decode`. A missing or unknown value decodes to `DEFAULT`.

### Width check

A header with an arrow (`Rain ▾`, `Night ▴`) must still fit on one line at the narrowest phone width.
Verify with a Fold outer-screen or emulator screenshot. If it clips, mark the sorted header by its
colour (primary versus secondary), keep the arrow only on desktop-width tables, and record which one
shipped.

## Tests

1. `DaySourceComparisonTest` (JVM):
   - each column sorts correctly in both directions, with nulls last both ways
   - ties keep the source order
   - `tapped` toggles direction on the same column and picks the natural direction on a new one
   - `DEFAULT` reproduces today's order
   - header label arrows
   Each test must fail against the current code.
2. Desktop Compose test: click the "Rain" header and assert row order by test tag, then click again
   and assert the order reverses.
3. `ForecastHistorySourceComparisonRoboTest`: perform a click on the Rain header and assert the
   table's row order. Run the same check after recreating the activity to confirm the stored sort is applied.
4. Live: on desktop, open tomorrow, sort by Rain, and take a screenshot. On the emulator, do the same
   and check the header fits.

## Docs

Update `plans/261105-all-sources-day-comparison.md`? No, it is historical. Add the sort rule to the
living doc that covers the Forecast History screen, if one describes this table. Otherwise, note it
in the KDoc of `DaySourceComparison`.

## Outcome (2026-11-12)

- Shipped as planned. The arrow fits at phone width: on the 1080 px emulator, "Rain ▾" sits in a
  164 px cell, so no colour fallback was needed.
- The "Sort by …" content description was not added. A new string would need all 19 locales, and
  TalkBack already reads the header text with its click action.
- `build`/`load` still return the default high order. Rows carry `sourceOrder`, so `sort()` breaks
  ties by the user's source order whatever order its input arrives in.
- Verified on desktop (the user's own Rain click was logged as `DAY_SOURCE_SORT` and saved to config)
  and on the emulator. The user confirmed both.

## Follow-up: separate Peak column (2026-11-12)

User: "What do you think of separating start time and rain chance column?" The time is the peak hour,
not the start. User chose "peak only".

- Rain now shows the % alone. A new **Peak** column after it shows the widget's peak hour (blank at
  ≤10% or with no stored peak). Peak sorts by the peak instant (`Row.dayPeakAt`, from the new
  `DailyColumn.dailyRainPeakAt`, set only when the label is), earliest first on the first tap.
- Column weights: Rain 1.3 → 0.85, Peak 0.85. `AMOUNT_COLUMN` now derives from `SortColumn.AMOUNT`.
  `Cells.values()` gives both platforms the same column order.
- "Peak" header string added in all 20 locale files.
- The saved sort text uses column names, so stored sorts such as `DAY_RAIN:desc` stay valid.
- Emulator, 1080 px: "Peak ▴" and "12pm" fit with Amount dropped.
