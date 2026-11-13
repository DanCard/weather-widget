# NWS "Amount" blank in History of Forecasts on a fully covered day

## Evidence (2026-11-13, desktop DB + screenshot)

1. History of Forecasts, "All sources for Fri Nov 13": NWS Amount shows `—`; Silurian, OWM, Google,
   Tomorrow.io, Open-Meteo, WeatherAPI show `.51in`, `.74in`, `.41in`, `.47in`, `.3in`, `.72in`.
2. `hourly_forecasts`, source NWS, 2026-11-13: every 8am–8pm hour exists in every fetch.
3. The newest fetch (09:57:04) stored `precipAmountMm = NULL` for the 07:00 and 08:00 hours
   (already elapsed). The 08:10 fetch still had 08:00 = 0.0847 mm.
4. `DailyRainLabels.windowForecastAmountMm` takes the newest row per hour
   (`HourlyForecastSelector.selectForecastsByTime`, `maxByOrNull { fetchedAt }`) and returns null if
   any hour in the window has a null amount. The null 08:00 hour voids the whole day total.

## Root cause

`NwsHourlyGridMerge.applySkyCoverAndQpf` fills amounts only from gridpoint QPF intervals that overlap
the hour. Once an hour has elapsed, the grid no longer returns an interval for it, so the hour's
amount stays null. A newer fetch therefore overwrites a good amount with null for elapsed hours
(same class as `started_hour_overwrite_loses_prehour_forecast`).

Other sources return amounts for elapsed hours, so only NWS is affected. The same null feeds any
reader of the newest-per-hour row (widget day/night rain labels, graph rain periods), not only the
table; to be confirmed in step 1.

## Proposed fix (shared, both platforms)

Choose where the null is repaired, in order of preference:

A. **Persistence (preferred):** when storing hourly rows, an incoming NWS row whose
   `precipAmountMm` is null does not replace an existing same-hour, same-site, same-source row that
   has an amount (carry the stored amount forward). Fixes every reader at once, matches the
   "newest fetch wins, but never lose real data" rule.
B. Selector: `selectForecastsByTime` returns the newest row but backfills a null `precipAmountMm`
   from the newest non-null row of the same hour. Fixes existing data too; touches a hot path with 8
   callers.

Recommendation: A plus B's backfill limited to `precipAmountMm` only if existing rows need healing
(the DB already holds the nulls, which A would not repair retroactively). Decide after step 1.

## Steps

1. Targeted check: log/inspect which readers see the null (day label on widget, graph) so the fix
   scope is known. Also confirm the 09:57 fetch's raw QPF drops elapsed intervals.
2. Implement the chosen fix in `:shared`; no Android/desktop-specific glue.
3. Tests: a pure JVM test with two fetches (older has amount, newer null for an elapsed hour) that
   fails on current code and passes after; one for a genuinely never-filled hour staying null.
4. Verify on the desktop app: restart, reopen History of Forecasts, NWS Amount for today is a value;
   check the Android widget day label via emulator logcat/screenshot for the same case.

## Out of scope

- The last day of each NWS fetch (e.g. 11-20) has no hourly rows at all; a blank Amount there is
  expected. Separate question whether it should show a dash.

## Addendum 2026-11-13 (after implementing fix 1 + selector backfill)

Implemented in `:shared`: `ElapsedForecastBackfill.backfill(…, stored)` carries a stored amount into an
elapsed hour whose incoming amount is null; `ForecastPersistRun` passes the stored rows;
`HourlyForecastSelector` backfills a null amount from the newest non-null candidate. JVM tests pass
(6881), new selector test fails on the old code.

Desktop check after restart: NWS Amount for Fri Nov 13 is still `—`. Why:

1. `DesktopWeatherDao.getHourlyForecasts` / `getHourlyForecastsAllSources` return **only the newest row per
   hour** (`HAVING fetchedAt = MAX(fetchedAt)`), so the carry-forward reads the already-null newest row
   and the selector never sees an older row with an amount. Both pieces only help where the newest
   stored row still has the amount (i.e. prospectively, from the next elapsed hour on).
2. The damage is not one hour. Newest NWS row per hour, 8am–8pm, by day (desktop DB):
   Nov 5–12 → every hour null (9/9, 12/12 ×7), Nov 13 → 1 of 12, Nov 14–19 → 0. In every null case an
   older row for the same hour has the amount. So every past NWS day has been blank in the table
   (and likely any past-day amount reader on hourly rows), since each elapsed-hour backfill wrote a null.

## Resolution 2026-11-13 (read-side heal, option 1)

- New `HourlyForecastSql.NEWEST_ROW_COLUMNS` (`:shared`): the table's columns with `precipAmountMm`
  coalesced to the newest non-null amount of the same hour, source and site. Used by all six
  newest-per-hour reads: Room `HourlyForecastDao` (3) and `DesktopWeatherDao` (3). No schema change, no
  stored data edited; the older rows were never deleted, so every past NWS day heals.
- Tests: `HourlyForecastSqlTest` (real SQLite; fails on `SELECT *`; also guards the column list against
  the table), `HourlyForecastDaoTest` (Robolectric Room twin), plus the earlier backfill and selector tests.
  Full JVM gate: 6887 passed.
- Desktop verified: History of Forecasts, Fri Nov 13, NWS Amount `—` → `.38in`.
- Not verified on a device/emulator: Android shares the same SQL fragment and passes the Room test, but
  its widget/screen was not looked at.
