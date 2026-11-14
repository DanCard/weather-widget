# All sources Age: star a today row the source no longer sends

## Request

User, 2026-11-13: "The silurian update issue is irritating. Maybe we could put a star there or something
and in the footnotes explain."

## Evidence (desktop DB, 2026-11-13 17:11)

| SILURIAN row | lastConfirmedAt |
|---|---|
| 2026-11-14 | 16:59:13 (and 17:11:47) |
| 2026-11-13 (today) | 12:10:26 |

`SNAPSHOT_SAVE` at 16:59:13 starts at 2026-11-14: Silurian's days are UTC dates, so after 4 PM Pacific
its batch has no row for local today. History → All sources shows Silurian's Age as `5h` while every
other source shows `0m`. Known since 2026-11-12 (memory `today_row_missing_from_batch`); a "final" marker
was declined then.

## Root cause

`DaySourceComparison.cells` prints `now − lastConfirmedAt` of the day's row. Nothing tells a row the
source *stopped sending* apart from a row the source *failed to refresh*.

## Proposed fix (all in `:shared` except the legend wording)

1. Rule, `DaySourceComparison.load`: for **today only**, also read tomorrow's rows (the existing
   `Host.forecastRows`, no new SQL). A source whose tomorrow row (same nearest-site selection) was
   confirmed more than 1 minute after its today row is marked `Row.noLongerSent = true`: a later fetch
   succeeded but did not include today.
2. `cells`: Age becomes `5h*` for such a row. Sorting is unchanged (still by `lastConfirmedAt`).
3. Legend: when any row is starred, one extra line after the column notes:
   `* The source no longer sends today (its days run on UTC); the age is from its last forecast that did.`
   Desktop `DaySourceComparisonTable.legendText`; Android `source_compare_legend_star`, all 20 locales.
   `DaySourceComparison.showStarNote(cells)` decides, both platforms.
4. Log: `DAY_SOURCE_COMPARE … noLongerSent=SILURIAN` on the existing line.
5. Past days are unchanged (every source stops confirming a past day; a star on all of them is noise).
   Claude proposal: this is generic, not Silurian-specific: any source whose batch drops today gets it.

## Tests

1. `:shared` JVM: today row confirmed 12:10, tomorrow 16:59 → `noLongerSent`, cell `5h*`, star note on.
   Same-fetch rows (both 16:59) → no star. Past date with the same rows → no star. No tomorrow row → no
   star. Fails on old code.
2. Desktop Compose test: the legend shows the star line only when a row is starred.
3. Android Robolectric: the legend gets `source_compare_legend_star` only when starred; locale parity test
   covers the new string.
4. Runtime: desktop History → All sources after 4 PM shows `Silur … 5h*` and the footnote; emulator same.
