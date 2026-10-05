# Desktop files evening forecasts under tomorrow (UTC "today"); evening-failing tests

## 1. Production bug: desktop `upsertForecasts` uses the UTC date as "today"

`DesktopWeatherDao.upsertForecasts`:
`todayEpoch = LocalDate.now(ZoneOffset.UTC)…`. From 17:00 PDT (00:00 UTC) until midnight, that's
local **tomorrow**. It drives two things:

- **`dateOfPrediction`** (`minOf(todayEpoch, targetDate)`). Every evening fetch is filed as
  predicted a day later. Live DB: every fetch made 17:00–24:00 local on 10-02 and 10-03 has
  `dateOfPrediction` = 10-03 and 10-04, for all six sources.
  - Accuracy (`AccuracyBreakdown`) takes the 1-day-ahead forecast as the newest row with
    `dateOfPrediction == target − 1`. So desktop grades the last fetch before **17:00** the day
    before; Android grades the last fetch before **midnight**.
  - Forecast history (`ForecastHistoryWindow`) shows those rows one day nearer the target.
  - The `minOf(…, targetDate)` clamp from `1998bdf7` ("future prediction timestamps for past
    target dates") was this same bug's visible symptom.
- **Decimal retention** (`ForecastTempRounding.forStorage(…, isToday)`). Local today's high/low
  is rounded to whole degrees after 17:00. Android keeps decimals.

Android (`ForecastSnapshotStore`) uses the local date (`ZoneId.systemDefault()`). Only this one
`LocalDate.now(ZoneOffset.UTC)` exists in production code.

### Fix

- `:shared` helper for the prediction day: the local date of `nowMs` in the system zone.
  Android `ForecastSnapshotStore` and desktop `upsertForecasts` both call it (the shared-rule
  preference).
- Desktop's `todayEpoch` = that date at UTC midnight (the `targetDate` encoding), derived from
  `nowMs` and not a second clock read.
- Tests: DAO write with `nowMs` = 20:00 local → `dateOfPrediction` = local today and today's
  decimals kept; helper test across the 17:00 boundary.

### Existing rows (decision needed)

Evening rows already stored carry `dateOfPrediction` one day late. It is part of the primary key,
but `fetchedAt` is too, so rewriting it cannot collide. Options:

- **A.** One-time repair:
  `dateOfPrediction = min(localDate(fetchedAt), targetDate)` for rows where it equals
  `utcDate(fetchedAt)` and differs from the local date. The zone is the machine's current zone,
  so rows fetched while travelling (Warsaw/Kyiv, late Sep) would be judged in Pacific time.
  Limit the repair to the Mountain View site?
- **B.** Leave history as is; only new rows are correct. The accuracy stats self-correct as the
  30-day window rolls past.

## 2. Tests that fail every evening (16:00–24:00 local)

Run with the test JVM zone set to an evening zone (Pacific/Pago_Pago, 20:06), these fail; all
pass in the morning:

- `ForecastRoundingTest`, `ForecastSnapshotDeduplicationTest`, `OpenMeteoIntegrationTest`,
  `OpenMeteoDayNightPrecipIntegrationTest` (`:app`, long)
- `DesktopSnapshotDisplayedRainChanceTest` (`:desktop`)

Cause: the same-day cutoffs (`b9864d9d`). After 16:00, a first write stores no today row. The
tests write "today" at the real clock. Same class as the two `DesktopWeatherDaoTest` cases
already pinned to 05:00 in `14c4d223`.

The other 10 failures in that run are artifacts of the zone swap (SF sun times and day-click
offsets computed in a Samoa zone), not of the hour.

### Fix

Give each test a fixed morning clock through the existing `nowMs` / clock seams. If an
integration path has no seam, add an injectable clock rather than skip the test. Verify by
re-running the suite in an evening zone (temporary build-file edit, reverted) and in the normal
zone.

## Outcome (implemented 2026-10-05)

- Decision: repair **all** rows in the machine's zone (user's choice).
- `:shared` `PredictionDate` (`of` / `epochMs`, local date of `nowMs`). Desktop
  `upsertForecasts` derives `todayEpoch` from it and from `nowMs` (no second clock read);
  Android `ForecastSnapshotStore` uses it too.
- One-time repair: `DesktopWeatherDao.repairUtcDatedPredictions`, run from
  `runPostFetchBackfills` behind the permanent `UTC_PREDICTION_DATE_REPAIR_DONE` marker.
  - Live: `repaired=3791` of 16,344 rows, matching an independent Python prediction exactly.
  - Afterwards 0 UTC-dated rows remain, and evening fetches on 10-02..10-04 carry their local
    date.
  - Backup: `weather.db.bak-pre-utc-dop-repair-20261005-002558`.
- Rounding: no stored data was affected. From 17:00 both same-day cutoffs already keep today's
  temperatures out of writes; the wrong "today" only mattered for `dateOfPrediction`.
- Evening tests: Android `ForecastRepository.clock` (`@VisibleForTesting`, no DI change) feeds
  `ForecastSnapshotStore` and `ForecastFetchCoordinator`. `WeatherRepository`'s wrapper defers
  to it. The 4 app tests and the desktop rain-chance test pin 05:00.
  `DesktopWeatherDaoTest`'s rounding test had encoded the bug (`LocalDate.now(ZoneOffset.UTC)`);
  it now uses the local date.
- Verified twice:
  - Evening zone (Pago Pago 20:xx): none of the evening failures remain. The leftovers are
    zone-swap artifacts: SF sun/day-click maths, and `OpenMeteoDayNightPrecipIntegrationTest`'s
    mock writes UTC times under a local `timezone` label, so a −11 h zone moves 10:00 into the
    night. That mock bug is latent in LA and has not been fixed.
  - Normal zone: 4592 passed. Reverting the writer to the UTC date fails the new writer test.
- Deployed: desktop restarted; debug build on the phone and emulator-5556.
