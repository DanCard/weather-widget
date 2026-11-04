# File every elapsed hour a fetch carries

**User, 2026-11-04:** "I ran a refresh, shouldn't they store the same thing?" → "yes" (write the plan).
Follow-up to plans/261104-remove-hourly-pre-hour-freeze.md (acbf4e40).

## Evidence

Desktop NWS, after a 04:00 user refresh (`REFRESH_ENTER reason=user_refresh:history`): the fetch
stored 155 hours starting 04:00. 12a–2a still read the 18:45 rows (5–7%); the Fold reads 25% from its
00:13/01:13 fetches. NWS's gridpoint payload still carried the 22:00–04:00 25% block
(`NwsForecastBundle.elapsedHourlyPeriods`).

## Root cause

`ElapsedForecastBackfill.select` files an elapsed hour only when the source has **no** row for it at
the site ("where a genuine snapshot exists, the elapsed value is a hindcast and freshest-wins would
promote it"). That guarded the pre-hour freeze, which is gone: newest fetch now wins everywhere. The
desktop had 18:45 rows, so the refresh's elapsed hours were dropped.

## Fix

1. `ElapsedForecastBackfill.select`: file **every** elapsed hour in `window(nowMs)` (72 h back to
   1 h back), not just uncovered ones. Rows are appended with `fetchedAt = now` (both stores already
   stamp this), so the stitcher's newest-wins picks them up.
2. Drop `Store.coveredHours` and its Room/desktop implementations if nothing else reads them;
   `Summary` keeps `offered`/`stored`.
3. Unchanged: `HourlyForecastWrite.plan` keeps writing only upcoming hours to the live path; elapsed
   hours arrive through the backfill path (`ForecastPersistRun` feeds it `hourly + elapsedHourly`).
   Pruning (newest per hour) bounds the extra rows.
4. Applies to every source's look-back (NWS grid since issuance, Open-Meteo `past_days`, Silurian
   `include_past`, Tomorrow.io 23 h): past hours show the source's latest revision, hindcasts
   included — consistent with the freeze removal.
5. Docs: `ElapsedForecastBackfill` KDoc, `HourlyForecastWrite` KDoc,
   `arch/hourly-forecast-update-cadence.md` started-hours section.

## Tests

1. `ElapsedForecastBackfill` unit: covered hours are filed too; window bounds unchanged.
2. Shared persist-run replay of this night (store at 18:45 with 12a–2a = 5%, then a 04:00 fetch whose
   elapsed grid hours say 25%) → stitched 12a–2a = 25%. Fails on the old code.
3. Update tests asserting "covered hours are not filed" (Room/desktop store and persist-run tests).
4. `./scripts/unit-tests.sh`.

## Verification

1. Install; restart desktop; Settings → Refresh data on desktop.
2. `HOURLY_HISTORY_BACKFILL` log shows `stored` > 0; desktop NWS rain graph 12a–3a = 25% and not
   dimmed, matching the Fold.
