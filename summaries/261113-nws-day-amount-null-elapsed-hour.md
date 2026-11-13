# NWS "Amount" blank in History of Forecasts

Plan: `plans/261113-nws-day-amount-null-elapsed-hour.md`

## Result

The History of Forecasts window now shows `.38in` for NWS on Fri Nov 13 instead of `—`. Verified in
the rebuilt desktop app. Not verified on an Android device or emulator.

## Symptom and cause

1. Desktop History of Forecasts, "All sources for Fri Nov 13": NWS Amount showed `—`; the six other
   sources showed values.
2. The Amount cell sums a source's stored hourly rain over 8am–8pm and is blank unless every hour has
   an amount (`DaySourceComparison.rowFor`, `DailyRainLabels.windowForecastAmountMm`).
3. NWS rain amounts come from the gridpoint QPF, which only covers hours from now onward. For an hour
   that has already elapsed, the NWS fetch carries no amount.
4. `ElapsedForecastBackfill.backfill` filed elapsed hours verbatim, so each fetch wrote a null over the
   amount an earlier fetch had recorded while the hour was still ahead.
5. The desktop and Room newest-per-hour reads return only the newest row per hour
   (`HAVING fetchedAt = MAX(fetchedAt)`), so one null row blanked the whole day.
6. The damage was not one hour. Among the newest NWS rows for 8am–8pm, every hour from Nov 5 to Nov 12
   was null; Nov 13 had 1 of 12 null; Nov 14–19 had none. In every null case an older row for the same
   hour still had the amount, so every past NWS day was blank.

## Changes (all in `:shared` unless noted)

1. `ElapsedForecastBackfill.backfill(…, stored)` fills a null amount on an elapsed hour from the newest
   stored amount for that hour; `ForecastPersistRun` passes the stored rows in. Stops new nulls.
2. `HourlyForecastSelector.selectForecastsByTime` fills a null amount from the newest non-null candidate.
3. New `HourlyForecastSql.NEWEST_ROW_COLUMNS`: the table's columns with `precipAmountMm` coalesced to
   the newest non-null amount of the same hour, source and site. Used by all six newest-per-hour
   reads: Room `HourlyForecastDao` (3) and `DesktopWeatherDao` (3). Heals existing data on read.
4. No schema change and no stored data edited; the older rows were never deleted.

## Why the first two changes were not enough

Changes 1 and 2 passed their tests, but after a desktop restart NWS Amount was still `—`. The desktop
query returned only the newest (already null) row per hour, so neither change could see the older rows
that held the amounts. Counting only the newest row per hour (not "any fetch has an amount") exposed
the extent of the damage. The heal therefore moved into the SQL (change 3).

## Tests

1. `ElapsedForecastBackfillTest`: elapsed hour keeps the stored amount; never-stored hour stays null.
2. `HourlyForecastSelectorTest`: newest null amount takes the newest non-null; zero is not replaced.
   Fails on the old selector.
3. `HourlyForecastSqlTest` (real SQLite, desktop): heals on all three desktop reads; zero is a real
   amount; other sources never lend theirs; column list equals the table's columns. Fails on `SELECT *`.
4. `HourlyForecastDaoTest` (Robolectric, Room): the same heal on the three Room reads.
5. Full JVM gate (`./scripts/unit-tests.sh`): 6887 passed.

## Open / not done

1. Android widget and screen not checked on an emulator; the Room test and build pass.
2. NWS's last forecast day (11-20 on Nov 13) has no hourly data at all, so its Amount stays blank.
   Whether it should show a dash is a separate question.
3. Nothing committed.
