# Session log — Daily-view column density (P7→7, Fold→10) + temp-label shrink-to-fit

**Date:** 2026-06-20
**Branch:** main
**Status:** Four related pieces. **First two committed**, last two implemented + verified on-device,
**not committed** (user reviewing):
- ✅ committed `368e8355` — P7 Pro "extra day" made intentional (named bias constant + tests)
- ✅ committed `457027da` — Fold full-width → 9 days (bias 15→30) + navigated-widget stale-cap fix
- ⏳ uncommitted — temp-label shrink-to-fit + slight-overlap allowance
- ⏳ uncommitted — global density bump `CELL_WIDTH_DP` 70→60 (P7→7, Fold→10)
**Plan file:** `~/.claude/plans/pixel-7-pro-it-replicated-stardust.md` (P7 plan; later tasks done
directly / via short plan-mode passes)

---

## All prompts (verbatim, in order)

1. `Pixel 7 pro: it feels like we fit an extra day on the daily forecast view.  What do you think?`
2. *(AskUserQuestion — Goal)* → **"Keep it, make it intentional"**
3. `Was an extra day added on p7 pro?  I don't see it.`
4. *(AskUserQuestion — clarify)* → **"I'm probably confused, and don't remember previous number of days"**
5. `committed.  I'm wondering if we should try same exercise for fold 4, try to fit an extra column?`
6. *(AskUserQuestion — Fold goal)* → **"Push to nine days don't worry about api, can show generic or more history"**
7. `I still see 8 days on samsung.  Was expecting to see 9`
8. `There is slight overlap with the degree symbol and 3 digit temperatures, such as 77.7  Can we not use the degree symbol in such cases?  Check if there is overlap then remove the degree symbol in such cases.  Does that sound like a good or bad idea?`
9. *(AskUserQuestion — Approach)* → **"Shrink to fit (recommended)"**
10. `Plan mode exited`
11. `Can you make the shrink less?  Maybe allow for very slight overlap?`
12. `looks good.  Can we see what 7 columns would look like on pixel 7 pro?`
13. `I like it, lets keep it` → *(AskUserQuestion — Scope)* → **"Denser everywhere (global)"**
14. `write to session-log/`

---

## How the daily view sizing works (the shared mental model)

- `WidgetSizeCalculator.getWidgetSize()` → `cols = columnsForWidthDp(width) = round((width + BIAS)/CELL)`.
- **Days shown == cols** via `NavigationUtils.getDayOffsets(numColumns, skipHistory)`.
- At `>8` columns the narrow skip-yesterday rule is OFF
  (`NavigationUtils.NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD = 8`), so the window is `[-1 .. cols-2]`
  (yesterday .. +(cols-2)).
- Forecast data: `ForecastHorizon.BASELINE_DAYS = 8` = today+7. Beyond that (and NWS's ~today+6)
  the climate-normal `GENERIC_GAP` filler covers dates `> today+2`, plus the daily view's on-demand
  `extensionTarget` fetch.

Devices used: **Pixel 7 Pro = `2A191FDH300PPW`** (~373dp daily widget), **Fold 4 = `RFCT71FR9NT`**
(full-width 6-span = ~574dp).

---

## Piece 1 — P7 "extra day" made intentional (committed `368e8355`)

The P7 was already showing 6 days (yesterday + today + 4). Cause: the `round((w+15)/70)` formula
rounds *up* (the `+15` plus round-to-nearest), and the recent 8-day baseline finally gave the 6th
column data to fill. Not a bug — an emergent, desirable side-effect.

- Renamed the bare `+15`/`+25` literals to documented `COLUMN_FIT_BIAS_DP` / `ROW_FIT_BIAS_DP`.
- Extracted pure, testable `columnsForWidthDp(Int)` / `rowsForHeightDp(Int)` out of the
  Context-bound `getWidgetSize` (per the repo's "pure function extraction for testability" rule).
- Added `WidgetSizeCalculatorColumnsTest` (plain JUnit) pinning representative widths + boundaries.

## Piece 2 — Fold full-width → 9 days, then the "still 8" fix (committed `457027da`)

- Bumped `COLUMN_FIT_BIAS_DP` 15→30 so 574dp → 9 columns. Verified against **live device options**
  (`dumpsys appwidget`) that this flips *only* the 574dp Fold widget; P7 (373→6) and all other sizes
  unchanged. 9 cols window = `[-1..+7]`, +7 == baseline (Open-Meteo full; NWS +7 → climate-normal).
- **"I still see 8 days"** — the on-device log was the key: `prepared 9 days, displaying 8 ... (offset=-1)`.
  Cause: `DailyGraphRenderer` capped via `days.take(baseline)`, where `baseline` was the column count
  **stored at offset 0** (`setDailyColumnCount`). It had been saved as 8 *before* the bias change and
  only refreshes when a widget returns to "today" — so any *navigated* widget stayed pinned to the old
  count. Latent bug the bias change exposed.
  - Fix: cap against the **live** `ctx.numColumns` instead of the stale stored value
    (`prepareGraphDays` always returns exactly `numColumns` days, so the cap is a correct no-op now).
  - Removed the now-dead `get/setDailyColumnCount` from `WidgetStateManager` (kept the cleanup-on-
    removal `.remove(...)` line).
  - Verified: every offset now logs `displaying 9 for 9 columns`.

## Piece 3 — Temp-label shrink-to-fit + slight-overlap allowance (uncommitted)

User saw the `°` of wide decimal labels (e.g. `77.7°`) overlapping neighbours on dense columns. They
asked "drop the °?" — I recommended **shrink-to-fit** instead (keeps ° + tenths, consistent), they agreed.

- Added pure `DailyForecastGraphRenderer.fitScaleForWidth(measuredAtScale, maxWidthPx, currentScale,
  minScale)` — scales a label down only when it overflows, never below a legibility floor
  (`MIN_TEMP_LABEL_FIT_SCALE = 0.7`). Composes on top of the existing `WIDE_LABEL_FONT_SCALE` step.
- `drawTempLabel` gained `maxWidthPx`; all 4 call sites pass `layout.tempLabelMaxWidthPx`
  (new `LayoutInfo` field).
- Follow-up "make the shrink less": flipped the budget from a **gap** (`dayWidth − 4dp`, forces shrink)
  to an **overlap allowance** (`dayWidth + TEMP_LABEL_OVERLAP_ALLOWANCE_DP(6dp)`), so labels stay
  large and only truly oversized ones shrink — a sliver of overlap is preferred over tiny text.
- 4 new `fitScaleForWidth` unit tests.

## Piece 4 — Global density bump (uncommitted)

User previewed "7 columns on the P7" (I temporarily forced it via a width-range hack) and said keep it.
**Key constraint surfaced:** 7 cols on P7 = 53dp/col but 9 cols on Fold = 64dp/col → the narrower
widget would be *denser*, which a single width→cols function can't do. So P7→7 necessarily moves the
Fold. User chose **"Denser everywhere (global)"**.

- `CELL_WIDTH_DP` 70 → **60** (kept bias 30). Verified mapping: 373→7, 574→10, every other width +1
  (no +2 jumps). P7 per-col 53.3dp, Fold per-col 57.4dp — both legible with Piece 3's fitting.
- Removed the preview hack; cols come from the real formula.
- Updated `WidgetSizeCalculatorColumnsTest` to the new mapping/boundaries.

---

## Gotchas hit

- **Banker's vs half-up rounding.** Sanity-checked boundaries in Python (`round()` = half-to-even) but
  Kotlin `roundToInt()` is half-**up**. They disagree at exact `.5`: the 6→7 boundary is **360dp**
  (Kotlin), not 361 (Python). Recomputed with `floor(x+0.5)`; tests pin Kotlin-true values (300/360/540).
- **Fold inner screen can't be screenshotted while folded.** `screencap` returns the 904px *cover*
  screen and `-d <id>` errors; the 574dp widget lives on the inner display. Verified the Fold via
  **logs** (`cols=`, `prepared/displaying`) instead, and confirmed P7 visually.
- **adb screencap PNG corruption** — Fold prepends `[Warning] Multiple displays...` to the PNG stream;
  recovered by slicing from the `\x89PNG` magic. (Project norm: always convert PNG→JPG before reading.)
- **`am broadcast APPWIDGET_UPDATE` is permission-protected** — use the app's custom
  `com.weatherwidget.ACTION_REFRESH` to force a redraw.
- **Text mode is hard-capped at 8 days** (`prepareTextDays` fixed `-1..+6` list + only `day1..day8`
  views). A 1-row-tall full-width widget (e.g. Fold widget 352) shows 8, not the computed 10. Graph
  mode (normal-height widgets) shows the full count. Not fixed — needs an XML layout addition.

---

## Verification

- Unit tests green: `WidgetSizeCalculatorColumnsTest`, `DailyForecastGraphRendererTest`
  (incl. `fitScaleForWidth`), `NavigationUtilsTest`, `DailyViewLogic*`.
- **P7 Pro (live):** computes `cols=7`, renders Fri/Sat/Sun/Mon/Tue/Wed/Thu; decimal labels
  `77.6°`/`62.6°`/`57.4°` legible, no `°` overlap. Screenshots `/tmp/p7_keep.jpg`.
- **Fold (live, via logs):** computes `cols=10`; graph mode `prepared 10 days, displaying 10 for 10
  columns (offset=0)`.
- Temp diagnostic logging (`WidgetSizeCalc_TMP`) added then removed; clean build emits none.

---

## Files touched

**Committed (`368e8355`, `457027da`):**
- `app/.../widget/handlers/WidgetSizeCalculator.kt` — pure `columnsForWidthDp`/`rowsForHeightDp`,
  named bias constants, bias 15→30.
- `app/.../widget/handlers/WidgetSizeCalculatorColumnsTest.kt` — new pure column tests.
- `app/.../widget/handlers/DailyGraphRenderer.kt` — cap against live `numColumns`, not stored baseline.
- `app/.../widget/WidgetStateManager.kt` — removed dead `get/setDailyColumnCount`.

**Uncommitted (Pieces 3 + 4):**
- `app/.../widget/DailyForecastGraphRenderer.kt` — `fitScaleForWidth`, `tempLabelMaxWidthPx`,
  overlap-allowance budget, `maxWidthPx` on `drawTempLabel` + call sites.
- `app/.../widget/handlers/WidgetSizeCalculator.kt` — `CELL_WIDTH_DP` 70→60 + doc updates.
- `app/.../widget/DailyForecastGraphRendererTest.kt` — `fitScaleForWidth` tests.
- `app/.../widget/handlers/WidgetSizeCalculatorColumnsTest.kt` — updated mapping (P7→7, Fold→10).

---

## Open items / possible follow-ups

- **Commit Pieces 3 + 4** (user reviewing). Suggested split: (a) temp-label fitting, (b) density bump.
- **Text-mode 9th/10th day** — extend `prepareTextDays` window + add `day9`/`day10` views to
  `widget_weather.xml` if 1-row full-width widgets should match graph mode. (Currently capped at 8.)
- Density / overlap-allowance are single knobs (`CELL_WIDTH_DP`, `TEMP_LABEL_OVERLAP_ALLOWANCE_DP`,
  `MIN_TEMP_LABEL_FIT_SCALE`) — easy to retune to taste.
- Hourly graph has its own temp labels (separate renderer) — not touched; could want the same fit.
