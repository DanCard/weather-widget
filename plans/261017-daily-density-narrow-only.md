# Daily density: keep the extra column on narrow widgets only

## User Prompt

> I made a change last night to increase density, so get more columns on front cover of Fold 4.
> 6 columns instead of 5. Feels right for the cover display, but now the unfolded display feels too
> dense. Is there a way to make both work, or should there be a setting for density preference?
> … I want the inner screen widget that is currently 12 columns to go to 11. … yes

## Evidence

- 7aea9eae lowered `DailyLayoutConstants.CELL_WIDTH_DP` 60 → 55 for every width. That scales the
  column count by width, so it added +1 slot on the cover (337 dp, 6 → 7) and also on the inner screen
  (662 dp, 12 → 13 slots = 11 → 12 days; `DAILY_RENDER widget=345 cols=11` on 10-16, `cols=12` on 10-17).
- A single larger bias at 60 dp cannot give both 337 → 7 and small widgets unchanged: 337 → 7 needs
  bias ≥ 53, while 40 dp → 1 column needs bias < 50 (bias 60 gives 40 → 2, 90 → 3, 210 → 5).

## Change

Cell width depends on widget width: **55 dp below 436 dp, 60 dp from 436 dp**.

- `DailyLayoutConstants`: replace `CELL_WIDTH_DP` with `NARROW_CELL_WIDTH_DP = 55`,
  `WIDE_CELL_WIDTH_DP = 60`, `WIDE_LAYOUT_MIN_WIDTH_DP = 436`, and `cellWidthDp(widthDp)`. KDoc: the
  why (one more column where fixed costs — Today's double slot, margins, arrows — weigh most) and why
  436 (see below).
- `WidgetSizeCalculator.columnsForWidthDp` and `DesktopDailyForecastModel` (bias 15) call
  `cellWidthDp(widthDp)`. Other readers of `CELL_WIDTH_DP` (grep) take the same function.
- **Why 436:** a switch is only safe where both cell widths give the same count, or a wider widget
  would get fewer columns. Safe ranges: Android (bias 30) 420–438, desktop (bias 15) 435–453; 436 is in
  both. It is also above any phone's width, so phones keep last night's density.
- Update the stale doc comments (WidgetSizeCalculator bias KDoc, DailyLayoutConstants).

Resulting columns (slots) on Android:

| Width (dp) | Before 7aea9eae | Now | After |
|---|---|---|---|
| 40 / 130 / 210 | 1 / 3 / 4 | 1 / 3 / 4 | 1 / 3 / 4 |
| 280 | 5 | 6 | 6 |
| 337 (Fold cover) | 6 | 7 | **7** |
| 373 (Pixel 7 Pro) | 7 | 7 | 7 |
| 574 (Fold inner, documented) | 10 | 11 | **10** |
| 662 (Fold inner, widget 345) | 12 | 13 | **12** → 11 days |

Not chosen: a density setting. Both screens land where the user said they feel right; a per-widget
column count can follow if taste differs later.

## Tests

- `WidgetSizeCalculatorColumnsTest`: the table above (cover 7, Pixel 7, 574 → 10, 662 → 12), both sides
  of the 436 switch, and the 5→6 / 6→7 boundaries under the narrow cell.
- **Monotonic** (new, both platforms): for every width 0..1000, columns(w + 1) ≥ columns(w). Catches a
  switch width or cell change that drops a column on a wider widget.
- `DailyLayoutConstantsTest`: `cellWidthDp` at 435 / 436.
- `DailyTapZoneCoverageTest` (capacity to 850 dp) keeps passing unchanged.
- Desktop: `DesktopDailyForecastModel` column test at a narrow and a wide popup width.
- On device: Fold inner widget 345 → `DAILY_RENDER cols=11`, cover 383 → `cols=6`; Pixel 7 Pro
  unchanged; screenshots of both Fold screens.
