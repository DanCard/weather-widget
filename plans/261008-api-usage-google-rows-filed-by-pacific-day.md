# api_usage_stats: file Google rows by the Pacific day

## Reported issue

"The app tallies Google calls per UTC day, but Cloud Console counts per Pacific day, so the two
'today' numbers cover different hours."

## What the code actually does

Not UTC. Both interceptors key the row by the **device's local calendar day**:

- Android `AppModule.provideHttpClient`: `LocalDate.now().toEpochDay() * MS_IN_A_DAY`
- Desktop `DesktopWeatherService` `ResponseObserver`: `LocalDate.now().toEpochDay() * 86_400_000L`

The stored value *looks* like UTC (it is that date's UTC-midnight epoch ms), but the boundary
is local midnight. Every machine here runs in `America/Los_Angeles`, so today's rows already
cover the console's hours. Checked 2026-10-08: the Pixel's 10 and the Samsung's 18 Google calls
under today's key equal their PT-window fetches exactly.

The real defect is that the boundary depends on the device zone, not on the provider. A phone in
Kyiv (or a desktop elsewhere) files a Google call made at 23:30 PT under the *next* day, and its
"today" stops lining up with the console.

## Fix

The row's day is the **provider's quota day**: Pacific for Google, the device's local day for
everyone else. Other providers don't publish a reset zone, and local is what the table has always
meant.

- `:shared` `ApiUsageClassifier.usageDayMs(source: String, now: Instant, localZone: ZoneId): Long`
  plus `quotaZone(source): ZoneId?` (`GOOGLE_WEATHER` → `America/Los_Angeles`, otherwise null).
  The 429 handling already treats Google's reset as PT midnight in `GoogleQuota.ZONE`
  (`:shared`, currently private). Expose that one constant and reuse it; no second copy.
- Android interceptor and desktop `ResponseObserver` both call it; neither computes a day itself.
- KDoc: `ApiUsageEntity`, `DesktopWeatherDao.logApiCall` ("local day" → "provider quota day").
- CLAUDE.md Google bullet: `api_usage_stats` Google rows are filed by Pacific day.
- No migration: rows already stored were all written in a PT zone, so they are already correct.

## Tests

- `:shared` unit: `usageDayMs` for Google at 23:30 PT with local zone `Europe/Kyiv` → the PT date;
  the same instant for NWS → the Kyiv date; Google across the PDT→PST change; non-Google in PT
  is unchanged.
- Desktop integration: `DesktopApiUsageAndRefreshGateTest` style, the classifier + DAO through
  `logApiCall` with a fixed instant, the Google row lands on the PT key.
- Android: existing `ApiUsageDaoTest` still passes (DAO unchanged).

## Script

`scripts/google_usage_today.py` (added with this plan) counts `GOOGLE_REQUEST` by timestamp in
the PT window, so it is zone-proof today. Its table column becomes zone-proof after this fix.
