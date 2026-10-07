# Daily day/night precip: one shared storage rule for Android and desktop

## Problem
`forecasts.daytimePrecipProbability` / `nighttimePrecipProbability` held different things per platform:
- **Android** (`ForecastSnapshotStore.mapDailyForecast`): 8am–8pm / 8pm–8am max over the *fetch
  payload's* hourly rows; the provider's own values were ignored. The window code was a hand copy of
  shared `DailyRainLabels.calculateDayNightPrecipProbabilities`.
- **Desktop**: the provider's own values (Google, NWS); null for sources without them.

The display already resolves through shared code, hourly first
(`DailyRainLabels.resolveLiveDayNightChance`). The stored values matter where read directly:
- the fallback when hourly rows are missing;
- the `daily_history` freeze (past-day labels);
- Android-only readers (`DailyTodayResolver`, `DailyForecastIconResolver`).

The Google one-page `forecast/hours` fetch (plans/261007-google-hours-one-page-and-slower-charger-cadence.md)
made payloads stop at 24 h. Android got a private stored-tail patch for that; desktop never needed it.
The user asked (2026-10-07) whether this should be shared and the same on both platforms; it should.

## Fix
- `:shared` `DailyPrecipPeriods`. Stored value = **the provider's own value when it supplies one**,
  else the 8am–8pm / 8pm–8am max over the source's hourly rows **as stored after this fetch's hourly
  save**. Only rows under the site's exact write key count. The read range is the first day's 08:00
  to 08:00 after the last day.
- Provider first, not hourly first (user, 2026-10-07). Hourly-first broke a stated decision:
  - commit 3fa341b6 (April) stores NWS 12-hour period chances as-is, and hourly-first stored
    "Tomorrow 30%" as 95% from one hour (`NwsPrecipAmountIntegrationTest`);
  - Google supplies daytime/nighttime for all 10 days, so with provider first a one-page fetch
    cannot blank days 2–3 at all.
  The display keeps its own hourly-first order; this rule is what is stored.
- `DailyRainLabels.periodMaxima` is the one window definition. The display calculator and the
  storage rule both use it.
- **Android:**
  - `mapDailyForecast` carries the provider's values only.
  - `ForecastFetchCoordinator.withStoredPrecipPeriods` applies the rule to every source's daily rows
    just before `saveForecastSnapshot`, NWS included.
  - The Google-only stored-tail plumbing is removed.
  - `ForecastRepository.mapDailyForecast` (test-only wrapper) is removed.
- **Desktop:** `DesktopWeatherRepository.withStoredPrecipPeriods` applies the same rule in
  `persistForecastResult` after the hourly upsert.

Behaviour changes:
- Desktop: Open-Meteo, Silurian and similar store hourly-derived values instead of null.
- Android: Google stores Google's own day/night values (as desktop already did) instead of the
  payload's hourly max. NWS is unchanged on both platforms.

## Tests
- `DailyPrecipPeriodsTest` (shared). It covers:
  - the windows (moved from Android's `ForecastRepositoryDayNightPrecipTest`);
  - provider-over-hourly precedence;
  - the hourly fallback;
  - the read range;
  - the site-key filter.
- `GoogleWeatherFetchIntegrationTest`: after a one-page fetch, days 2–3 keep the stored values the
  full fetch gave them.
