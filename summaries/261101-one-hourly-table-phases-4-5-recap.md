# One hourly table: value comparison and phases 4-5 recap

Plan: `plans/261101-one-hourly-forecast-table.md`. Earlier recaps: `summaries/261101-one-hourly-table-phase-1-recap.md`,
`summaries/261101-one-hourly-table-phase-3-recap.md`.

## Outcome
The one-table plan is complete apart from the checks listed under Not verified. The old and merged tables give the same
answers on the desktop and Pixel. The Samsung differs only where the old stitch borrowed another site's row.

## Value comparison
Old tables against the merged table on real copies, through the real stitcher and rain resolver.

| Database | Hours changed | Day/night rain values changed |
|---|---|---|
| Desktop | 0 of 2,660 | 0 of 161 |
| Pixel | 0 of 2,586 | 0 of 161 |
| Samsung | 150 of 2,651 | 4 of 161 |

The Samsung changes are all past hours, up to 4.0 F, with 70 over 1 F. `groupSameSite` applied its nearby-site fallback
to the live and history rows separately, so the old code could borrow a row from about 700 m away while a same-site row
existed. In all 150 hours the merged table uses the same-site row, as the stitcher's documented rule says.

Accepted. For an hour with no earlier forecast at the new site, the line now shows a later revised value from that site.
Keeping the borrowed forecast there would be a small separate rule.

Prior-day reads changed for 18 to 57 hours per database, because fetches the old skip rule never saved now count.

## Phase 4
Removed `HourlyForecastHistoryEntity` and `HourlyRowKey`; Room deletes by primary key directly.

Kept, against the plan:
- `ElapsedForecastBackfill`: appending elapsed hours would make revised past hours the newest rows, the problem this
  work set out to stop.
- `HourlyForecastWrite.merge`: newest-row readers, including daily summaries, have no read-time fill.

## Phase 5
Updated `arch/ARCHITECTURE.md`, `arch/data-pruning.md`, `arch/hourly-forecast-update-cadence.md` and the code comments that
described two tables or the removed classes.

## Verification
- `./scripts/staggered-tests.sh`: exit 0, 6,535 JVM tests and 114 emulator tests passed. One doc line changed afterward.
- The build is on the desktop, Pixel, Samsung and emulator. Widgets paint after a refresh on both phones, with no crashes
  or database errors.
- First real prune, desktop, 10:09: 32,782 of 53,116 rows deleted in 383 ms, 20,334 kept.

## Not verified
- Screenshots of the daily, temperature, rain and cloud views.
- Widget speed.
- The observations table on a device.
- The first prune on the phones.
