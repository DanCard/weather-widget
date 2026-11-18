# All-sources table: content-sized columns so Amount fits on phones

## Evidence
- Pixel 7 Pro screenshot (2026-11-17, Forecast Comparison, Wed Nov 18): there is no Amount column, and
  the "Night" and "Cloud ▲" headers touch. The Source column has about 75 px of empty space after "Google".
- Pixel 7 Pro: 1080 px at 420 dpi = 411 dp. Table width = 411 − 48 = 363 dp, which is below
  `DaySourceComparison.AMOUNT_MIN_WIDTH_DP = 380`. Amount is dropped by the width gate alone.
- Columns are sized by fixed `COLUMN_WEIGHTS` (Source 1.25, Amount 1.1, …), not by content. Space
  goes where the weights put it, not where the text needs it.

## Root cause
A fixed dp threshold plus weight-based widths. Neither one looks at the real text widths.

## Proposed fix (shared rule, platform measures)
1. `:shared` `DaySourceComparison.layout(tableWidthPx, measure: (String) -> Float, cells, headers)` →
   `Layout(showAmount, wholeLows, columnWidthsPx)`. The rule is all in shared code; each platform only supplies text measurement
   (Android `Paint.measureText` at 12 sp, bold for the bold row; desktop `TextMeasurer`).
   - Each column's width is the widest of its header and cells, plus a minimum gap. Leftover space is split evenly
     across the gaps. Source no longer gets extra space.
   - Fallback order when the table is too narrow: (a) all columns; (b) Low rounded to whole degrees
     ("48°" instead of "48.2°"), as you suggested; (c) drop Amount. The order is fixed, and each step is logged.
2. Remove `AMOUNT_MIN_WIDTH_DP` and `COLUMN_WEIGHTS`. Android `comparisonRow` and desktop `TableRow` use the
   widths from the layout.
3. Logging: one `Log.v` per render with the table width, the chosen step, and the slack.
4. Docs: update the `DaySourceComparison` entry in `arch/ARCHITECTURE.md` (it says "dropped below 380 dp"
   today).

## Estimate on the Pixel 7 Pro
The widest text per column at 12 sp (2.625 px/dp) is about 740 px in total, against 953 px available. That leaves about 21 px of gap
per column with Amount shown and no rounding. Step (b) is a fallback for narrower phones.

## Tests
- Shared unit tests: all columns fit → (a); slightly narrow → (b) with Low cells rounded; very narrow → (c);
  no amounts → Amount never shown; widths add up to the table width.
- Robolectric/Android: render with 363 dp and real `Paint` → Amount shown.
- Desktop Compose test: the table still shows all columns.
- Device: screenshot the Pixel 7 Pro after `installDebug`.

## Outcome (2026-11-17)
- Pixel 7 Pro, Wed Nov 18: `step=ALL`, all ten columns with decimal lows (`DAY_SOURCE_LAYOUT gap=15.1`).
- `MIN_COLUMN_GAP_DP` is 5, not 6. At 6 dp, the "Cloud ▴" sort arrow (about 25 px) pushed this table into
  whole-degree lows, so the layout changed with the sort column.
