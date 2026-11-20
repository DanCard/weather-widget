# Past-day rain for sources whose actuals feed carries no rain

## Request (user, 2026-11-20)

> desktop: History has 100% chance of rain for tomorrow.io. I thought it was suppose to show rain
> fall amounts instead of 100%

Approved 2026-11-20: "approved, include the hourly Act rain too".

Chosen option: "option 2" — a rain-only fallback. When a source's actuals feed has no rain, take
its past-day rain from the measured gauges, and keep its temperatures from its own feed.

## Evidence

Desktop, Tomorrow.io displayed, 2026-11-20 08:02: the past columns' day labels read "10am 100%"
(Wed 11-18) and "8am 99%" (Thu 11-19), where the other sources show amounts.

Desktop `daily_history`, measured rain (mm):

| Date | NWS / Google / OWM / Silurian (day, night) | Open-Meteo | WeatherAPI | Tomorrow.io |
|---|---|---|---|---|
| 11-17 | 0.0, 2.52 | 0.0, 3.70 | 0.18, 6.75 | NULL, NULL |
| 11-18 | 46.40, 8.66 | 30.10, 4.30 | 51.29, 11.92 | NULL, NULL |
| 11-19 | 6.62, 4.67 | 3.20, 1.10 | 6.80, 1.35 | NULL, NULL |

The Pixel 7 Pro backup (07:19) has the same NULL Tomorrow.io rows, so both platforms are affected.
It also has NULL OpenWeatherMap rows for 11-18 (Google, Open-Meteo and Silurian read 46.23 mm there):
OWM's own feed (`HistoricalDataKind.NONE`) has no rain product either, and the Pixel does not redirect
OWM's actuals (the desktop redirects OWM and Google to NWS).

## Root cause

1. A source's measured rain is reduced from its actuals provider's observation rows
   (`ActualsAggregator.aggregate` → `ObservedPrecip.daily(obsList …)`).
2. Tomorrow.io's provider is itself (`ActualsProviderResolver`: not a borrower). Its only actuals
   product is the five-minute history, and that request carries no rain field: Tomorrow.io rejects
   `precipitationAccumulation` at `timestep=5m` (error 400001; `TomorrowIoApi.getFiveMinuteHistory`).
3. Past days never fall back to forecast rain (`allowForecastFallback = !date.isBefore(today)`), so
   every past Tomorrow.io row stores NULL day, night and total rain.
4. The daily label then falls back to the forecast chance (`DailyRainLabels`, past branch: the
   measured amount wins only when one exists; added 2026-06-27 for days on which no rain fell).

Google and OpenWeatherMap show amounts only because this desktop sets their actuals provider to NWS.

## Proposed change (Claude proposals marked ◆)

1. **Which feeds carry rain:** a shared property of the feed. ◆ `WeatherSource.actualsCarryRain`,
   defaulting to `historicalDataKind.preservesHistoricalPrecipitation` and set false for `TOMORROW_IO`
   (its five-minute product). So Tomorrow.io, OpenWeatherMap and Visual Crossing (kind NONE) have no
   rain; NWS, METAR, Synoptic, Open-Meteo and WeatherAPI do. It is a static property, not "these rows happen to be empty", so a dry day
   measured as 0 is never confused with "no rain data".
2. **Rain provider:** `ActualsProviderResolver.rainProviderIdFor(source)` returns the source's actuals
   provider when that feed carries rain; otherwise the borrower default
   (`borrowerDefault`: NWS inside NWS coverage, METAR elsewhere). This covers Tomorrow.io as
   its own provider, and any source the user points at Tomorrow.io.
3. **Aggregation:** in `ActualsAggregator.aggregate`, a source group's observed rain reads
   `byApi[rainProviderIdFor(source)]` rather than its own temperature rows. The temperature blend is
   unchanged. The identity memo still reduces each list once per date, so Tomorrow.io shares the NWS
   reduction with Google, OWM and Silurian (no extra cost).
4. **Fetching:** `ActualsFeedPolicy.requiresFeed` also counts a source whose rain provider is the feed,
   so a device showing only Tomorrow.io still fetches NWS (or METAR) observations. Both platforms
   already ask this policy.
5. **Rebuilding the stored days:** add the rain provider to `DailyBlendRecomputeRun`'s per-day skip
   signature (it already holds the resolved actuals providers) and bump `WRITE_RULES_VERSION` 7 → 8, so
   the last 9 days are reduced again once. The 11-17…11-19 Tomorrow.io rows then get the NWS gauge
   amounts.
6. **Today:** Tomorrow.io's today amount comes from the gauges too (as for the other NWS-backed
   sources). It currently comes from its own forecast hours via the today-only fallback (desktop now:
   6.86 mm, while the gauges read 4.67 mm). The forecast fallback still applies when the gauges have
   no hour yet.
7. **Accuracy:** `RainAccuracyRunner` gains measured Tomorrow.io rain to grade against. No code
   change expected there; noted as a visible effect.

8. **Hourly "Act" rain (included, per approval):** `ObservedPrecip.rowsForActualsOf` — the one
   selector behind Android's `PrecipViewHandler`, desktop's `PrecipitationGraph` and the shared
   `HourlyActualsArchiveRun` — defaults to the rain provider instead of the actuals provider, so the
   precipitation graph's "Act" amounts and the archived hourly rain come from the same gauges as the
   daily amount.

## Tests

- `:shared` unit: `rainProviderIdFor` — Tomorrow.io → NWS inside coverage, METAR outside; Silurian
  pointed at Tomorrow.io → NWS; NWS / Open-Meteo / WeatherAPI → themselves.
- `:shared` unit: `ActualsAggregator.aggregate` with NWS gauge rows (11-18: 46.4 mm day) and
  Tomorrow.io five-minute rows (no rain) gives the Tomorrow.io row 46.4 mm day; its high/low still come
  from the five-minute rows. Fails on current code (NULL).
- `requiresFeed(NWS, [TOMORROW_IO])` is true inside coverage.
- Integration, real SQLite on both platforms: the shared `DailyBlendRecomputeRun` wired to the
  desktop host and to Android's Room host — store NWS and Tomorrow.io observations, run the
  recompute, and read the Tomorrow.io `daily_history` row's `precipDayMm`.
- `rowsForActualsOf(rows, TOMORROW_IO)` returns the NWS gauge rows (not the five-minute rows), so the
  hourly "Act" reads them on both platforms.
- Daily label: a past Tomorrow.io column reads the amount (".18in"-style), not the chance.

## Verification

- Desktop: restart, open the daily popup on Tomorrow.io. Wed 11-18 shows ~1.83in, not "100%", and the
  DB rows are filled.
- emulator-5556 (Tomorrow.io): the same past labels, after the recompute runs.
- End-of-task gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

## Docs

- `arch/daily-history-extremes.md`: a "Rain provider" note under the actuals read (a feed without rain
  borrows the gauges for rain only).
