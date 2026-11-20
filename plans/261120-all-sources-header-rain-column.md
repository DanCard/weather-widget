# All sources: optional "Now" column = the header rain chance

**Request (user, 2026-11-20):** "add an optional column to 'forecast comparison' 'all sources', one
that matches the header rain chance. Only add the column if there is plenty of width for it. On
desktop, should be dynamic, if window expands or contracts width wise then column could appear or
disappear."

## Evidence (current code)

- The table is the shared `DaySourceComparison` runner (`shared/.../graph/DaySourceComparison.kt`);
  both platforms only measure text and call `DaySourceComparison.layout(...)`, which steps through
  `FitStep` (ALL → WHOLE_LOWS → NO_AMOUNT → NO_AMOUNT_WHOLE_LOWS) until every column gap is
  ≥ `MIN_COLUMN_GAP_DP` (5 dp).
- The header rain % is `HeaderContent.precipitation(...)` (private): `PrecipProbabilityCalculator.maxPrecipProbabilityWithin`
  over the next `headerRainLookaheadHours` h from now (display source, GENERIC_GAP fallback), hidden
  at 0 %. `HeaderContent.precipLabels` already exposes the same rule per source for width reservation.
- Desktop: `DaySourceComparisonTable` calls `layout(maxWidth, …)` inside `BoxWithConstraints`, so
  the layout already re-runs on every window-width change; a new fit step therefore appears and
  disappears with the window for free.
- Android: `renderSourceComparison` lays out against `displayMetrics.widthPixels − 48 dp` (re-runs
  on each render / rotation).

## Proposed change

1. **Shared value (one rule).** Make the header's rule callable:
   `HeaderContent.headerRainProbability(hourly, sourceId, now, lookaheadHours): Int?` (the computed
   value `precipitation()` already returns; `build`/`precipLabels` call it, so the header and the
   table cannot drift). `DaySourceComparison.Row` gains `headerRainPercent: Int?`, filled in
   `rowFor` from that source's sited hourly rows **only when the date is today** (the header's window
   is "from now"; other days leave it null).
   - `load(...)` takes `rainLookaheadHours` (Android: `WeatherDisplayPreferences.headerRainLookaheadHours()`,
     desktop: `config.settings.headerRainLookaheadHours`). For today, the hourly read starts at
     `min(startOfDay, now − 1 h)` so just after midnight the current hour's lookback is covered.
   - `DAY_SOURCE_COMPARE` log gains `headerRain=<src:%,…>` for today.
2. **Column.** New `SortColumn.HEADER_RAIN` (first tap descending), placed right before Rain:
   `Source High Low Now Rain Peak Night Amount Cloud Bias Age`. Header **"Now"**; legend
   "highest hourly chance in the next N h, as the widget header shows" (N = the setting).
   Sort encoding is by name, so saved sorts survive the ordinal shift.
   - *Claude proposal:* the cell prints `0%` when the computed chance is 0 (the header hides 0 %,
     but in a table a blank reads as "no data"). Blank only when the source has no hourly rows in
     the window. Say if you'd rather it be blank at 0 % to match the header exactly.
3. **"Plenty of width" rule.** New first fit step `WITH_HEADER_RAIN` (everything shown, decimals
   kept, plus Now). It is accepted only when every gap is ≥ `HEADER_RAIN_MIN_GAP_DP` — *Claude
   proposal: 10 dp, twice the normal 5 dp minimum* — otherwise the existing steps run unchanged
   (Now is always the first column to go, before lows lose decimals or Amount drops). Now is never
   offered when no row has a value (any non-today date), like Amount today.
4. **Platforms.** Both header lists / string resources get the new column (Android
   `source_compare_col_now` + `source_compare_legend_now` in all 19 locales; desktop `HEADERS` and
   `LEGEND_TEXT`). Legend line shown only while the column is shown. No other platform logic.
5. **Two-level header (user, 2026-11-20: "making the header two levels, and a rain text over all
   columns that are about rain").** A group row above the column headers carries **Rain** centred
   over the shown rain columns (Now, Day, Peak, Night, Total — contiguous in column order), its width
   the sum of their `Layout.widths`, so it shrinks when Now or Total drop. Shared
   `Layout.rainGroupSpan(): IntRange?` (indices into the shown columns) owns which columns it spans.
   The group label is not a sort target. With it, sub-headers shorten: Rain → **Day**, Amount →
   **Total** (both platforms, all locales); legend lines follow the new names. Temperature columns
   get no group (Claude proposal).
6. **Docs.** `arch/ARCHITECTURE.md` `DaySourceComparison` paragraph: new column, today-only, and
   the extra fit step / gap.

## Tests

- `DaySourceComparisonTest` (shared, pure JVM):
  - discriminating value case: hourly rows with 20 % in the next hour and 80 % at 3 pm → Now = 20 %,
    Rain = 80 % (fails if Now reuses the day max), and with lookahead 6 h → 80 %.
  - equals `HeaderContent.build(...).computedPrecipProbability` for the same rows.
  - non-today date → `headerRainPercent` null and Now never in `layout.columns`.
  - layout: wide width → `WITH_HEADER_RAIN`; width with 5–10 dp gaps → `ALL` (Now dropped, decimals
    and Amount kept); width growing back → Now returns.
  - sort: `HEADER_RAIN:desc` round-trips; old saved sorts still decode.
- Integration (real SQLite): `DesktopDaySourceComparisonHostContractTest` and
  `RoomDaySourceComparisonHostContractTest` — insert hourly rows for two sources, run `load(...)` for
  today, assert each row's `headerRainPercent`.
- Desktop UI: Compose test rendering `DaySourceComparisonTable` at a wide then narrow width,
  asserting the `source_compare_header_<HEADER_RAIN>` tag appears then disappears.
- Android: `ForecastComparisonSourcesRoboTest` — legend line present only when the column is.
- Runtime check: desktop window resized wide/narrow with screenshots; emulator screenshot of today.
- End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
