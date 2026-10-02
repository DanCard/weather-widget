# Today column keeps its position across midnight (daily view)

## Problem

User: "The today column should stay in the same relative place in the widget. The days should
move so that the today column is in the same relative place."

## Root cause

`NavigationUtils.shouldSkipYesterday` (`:shared`) switches narrow widgets (≤ 8 columns — every
phone widget) between two windows on the wall clock, **not** at midnight:

| Local time | Window at offset 0 (5 cols) | Today column |
|---|---|---|
| 08:00 – 23:59 | `Today, +1, +2, +3, +4` | 1st |
| 00:00 – 07:59 | `Yesterday, Today, +1, +2, +3` | 2nd |

At midnight the **dates do not move** — the old today stays in column 1 and the "today"
emphasis (and the wide large-today-overlay column) jumps right to column 2. At 08:00 the dates
shift and today jumps back. Net: two jumps per day, the first in the wrong direction.

Navigated (offset ≠ 0) has the same defect: `getDisplayCenterDate` adds +1 only while
skipYesterday is on, so at midnight the +1 vanishes exactly as `today` advances — dates frozen,
highlight moves; at 08:00 the dates jump.

Wide widgets (> 8 cols) already behave as requested: always `Yesterday, Today, …`, so at midnight
every date shifts left one column and today stays in column 2.

## Fix

Make the narrow window time-independent: narrow widgets are today-first all day.

1. `NavigationUtils.shouldSkipYesterday`: return `numColumns <= NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD`
   (time no longer consulted); delete `NARROW_SKIP_YESTERDAY_HOUR`. Keep the `time` parameter out of
   the signature and update callers (Android `DailyViewHandler`, `DailyLoadWindowResolver`,
   `DailyInteractionRenderer`; desktop `DesktopDailyForecastModel`).
2. `UIUpdateScheduler`: the 08:00 clamp becomes a **midnight** clamp (`getTimeUntilMidnight`), since
   midnight is now the only moment the window changes; rename the strategy parameter
   (`timeUntilDayRolloverMillis`). Desktop: verify the popup/panel repaints at the date rollover.
3. Tests (`:shared` `NavigationUtilsTest`): for 5 and 10 columns, at offsets 0 / ±3, the index of
   today in the visible range is identical at 23:59 day D and 00:01 day D+1, and the visible
   dates shift by exactly one. Update existing time-of-day skip-yesterday tests and
   `UIUpdateIntervalStrategyTest`.

## Trade-off

Narrow widgets lose yesterday's column between midnight and 08:00 at offset 0 (one left-tap still
reaches it). That is the cost of a fixed today slot on a narrow widget.

## Midnight integration tests (added on request)

- `DailyViewHandlerMidnightRolloverIntegrationTest` (Robolectric): real `DailyViewHandler.updateWidget`
  at 23:59 and 00:01, 5 and 10 columns, offsets −3/0/+3; captures the `renderGraph` day list and
  asserts same Today index, every date +1, same large-Today overlay decision. Plus "today first at
  06:17" on a narrow widget.
- `DesktopDailyForecastModelTest` "today column keeps its position across midnight": same invariant
  through `build(now=…)`, four widths × offsets × zoom-out history 0/3.
- `UIUpdateIntervalStrategy.millisUntilNextMidnight(ZonedDateTime)` extracted (was a clock-reading
  private in `UIUpdateScheduler`); tests that a 60-min interval at 23:55 repaints exactly at 00:00.
- Proved failable: with the production changes stashed (old 08:00 rule restored), all three
  midnight tests fail (Android: "Today column index expected:<3> but was:<4>" at 5 cols, offset −3).
