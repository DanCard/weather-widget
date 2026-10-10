# Fetch only the sources likely to be viewed

Status: **approved** (2026-10-10, "Don't fetch API sources that are unlikely to be used"). Uses `SourceViewProbability`
(`plans/261010-source-view-tracking-table.md`).

## Request (user, 2026-10-10)

> I don't see the point of fetching other API data if it is not going to be used. If the user does
> toggle API it can be fetched on demand.
>
> I'm o.k. losing accuracy tracking for APIs that are not viewed.

## What is fetched in the background today

| Path | Android | Desktop |
|---|---|---|
| Forecast of non-displayed sources | `ForecastFetchCoordinator.isStale` → `visibleSourcesToFetch`, `ForecastCadence` "other" (×2 off charger) | `DaemonRuntime` 3c "slower forecast fetch for other APIs" |
| Non-displayed sources' current / actuals | `NonPrimaryObservationScheduler` (30 min, charging + screen on) | `DaemonRuntime` 3d, same policy |
| Untargeted forced sync (Refresh, location change) | every enabled source | displayed source only |

## Rule (one shared function, `:shared` `SourceFetchGate`)

A source is fetched in the background when **any** of:

1. it is displayed (any widget / the desktop popup) or is the primary source;
2. the user switched to it (any trigger) **within the last 7 days, today included** —
   `SourceFetchGate.RECENT_VIEW_DAYS = 8`: not viewed in 8 days → not fetched (user, 2026-10-10);
3. tracking started fewer than 8 days ago (a new install or a fresh upgrade keeps today's behavior
   until there are 8 days to judge by).

Otherwise it is **on demand only**: fetched when the user switches to it (`SourceToggleRefreshPolicy`,
> 4 h old or no data), when a day tap needs its hourly (`HourlyOnDemand`), or by a history refill. No
targeted path is gated.

| History | Fetched? |
|---|---|
| Viewed today | yes |
| Last viewed 7 days ago | yes |
| Last viewed 8 days ago | no |
| Viewed every day until 8 days ago, nothing since | no |
| Never viewed, tracking ≥ 8 days | no |
| Never viewed, tracking started 5 days ago | yes |

**A recency rule, not a probability threshold.** A threshold of 13.3 % separates "once, 7 days ago"
(13.5 %) from "once, 8 days ago" (13.1 %), but a user who viewed a source daily until 8 days ago still
reads 47.8 % and would keep it fetched for weeks. The rule the user stated is about the last view, so
it is implemented as that. `SourceViewProbability` stays built and logged daily
(`SOURCE_VIEW_PROBABILITY`) for a later, finer policy; nothing here reads it.

No flapping: the decision changes only when a day passes or the user switches sources.

## Changes

**`:shared`:** `SourceFetchGate.backgroundFetch(enabled, displayed, primary, rows, trackingSince,
today, lat, lon, actualsPreference) : BackgroundFetch` (forecasts + actuals feeds) and
`RECENT_VIEW_DAYS`. Pure; both platforms call it.

**Android:**
- `ForecastFetchCoordinator`: `isStale`, `requiresNetworkFetch` and `visibleSourcesToFetch` consider
  only gated-in sources unless the sync targets that source (`targetSourceId`). An **untargeted
  forced** sync (Refresh, location change) also skips gated-out sources — see question 2.
- `WeatherWidgetWorker`'s non-primary branch (the work `NonPrimaryObservationScheduler` schedules):
  refresh current temp only for gated-in non-displayed sources.
- `MetarObservationRefresher` / `SynopticObservationRefresher.currentTier()`: consumers are taken
  from the gated-in sources, not every enabled source.
- The gate is computed once per run from `SourceViewDao` (≤ a few dozen rows). If it cannot be
  computed (DB error), it **fails open**: everything is fetched, as before.

**Desktop:**
- `DaemonRuntime` 3c and 3d loop over gated-in sources only (the daemon reads `source_view_days`
  from the same `weather.db`).

**Logging:** `SOURCE_FETCH_GATE on=GOOGLE_WEATHER,NWS off=SILURIAN,OPEN_METEO feeds=NWS` once a
local day and whenever the set changes (both platforms).

## Data that rides another source's forecast fetch

The gate is on **forecasts**. Some other work only runs inside a source's forecast fetch, so gating
that forecast also stops it. Each case, checked in code (2026-10-10, prompted by a side-agent note):

| Rider | Rides | Who reads it | Verdict |
|---|---|---|---|
| NWS daily actuals — `NwsApiDailyActualsFetcher.fillMissingIfNeeded`, the station pull (`fetchFromAllApis`, `if NWS in servable`) | the NWS forecast | **NWS's own rows only** (`NwsStationActualsStore` filters `source == NWS`). Borrowers read the provider's **stored observations** (`providerIdFor` in `DailyActualsAssembler`, `ActualsAggregator`, `ObservationResolver`), never the provider's `daily_history` row | Safe to gate with the NWS forecast: unviewed NWS rows have gaps (accepted); a switch to NWS runs a targeted fetch whose station pull refills the endpoint's lookback. Desktop runs its sibling (`fillNwsStationActualsIfNeeded`) on every displayed refresh, unchanged. *Corrected 2026-10-10 during implementation — the earlier verdict "must not depend on the NWS forecast" assumed borrowers read NWS's rows; they don't.* |
| Open-Meteo prior-day cloud (`fetchPriorDayCloudForecast`, Previous Runs API) | the Open-Meteo forecast | the cloud graph **only when Open-Meteo is displayed** (`CloudSeriesLoader.kt:38`) | Safe: no other source reads it, and the first on-demand Open-Meteo fetch refills 31 past days. Desktop already runs it on every refresh (`maybeFetchPriorDayCloudForecast`) |
| WeatherAPI history backfill | the WeatherAPI forecast | WeatherAPI only | Safe: refilled on its next (on-demand) fetch |
| Tomorrow.io 5-min history | the Tomorrow.io forecast | Tomorrow.io only | Accepted loss: the API keeps 24 h, so days not fetched have no Tomorrow.io actuals |

## Actuals feeds follow the sources that use them (user, 2026-10-10)

Nobody views actuals on their own; they are always seen through a source. So no new tracking:
which feeds are needed is **derived** from the forecast set, through the existing provider mapping
(`ActualsProviderResolver` / `ActualsFeedPolicy.feedFor`), on every read. A provider-picker change
therefore takes effect at once and no stored record can disagree with it.

`SourceFetchGate` returns both sets:

```kotlin
data class BackgroundFetch(
    val forecasts: Set<WeatherSource>,        // the rule above
    val actualsFeeds: Set<WeatherSource>,     // forecasts.mapNotNull { feedFor(it, lat, lon) }
)
```

- `actualsFeeds` = every feed a background-fetched source uses — itself when it files its own
  actuals, its provider when it borrows (`ActualsFeedPolicy.currentTempFeeds` already encodes which
  sources count as their own feed: forecast-only sources never do).
- Observations-screen views are already recorded against the source (`OBSERVATIONS`); for a
  borrower that screen shows its provider's stations, so the same mapping covers them.

| Sources | NWS feed | METAR feed |
|---|---|---|
| Google displayed (inside coverage), NWS forecast never viewed | **on** (Google borrows NWS) | off |
| NWS viewed often | on | off |
| Open-Meteo displayed abroad, Google rarely viewed | off | **off** (Google is gated out, so its METAR borrowing is too) |
| Google displayed abroad | off | **on** |

Users of `actualsFeeds`, replacing their current "all visible sources" input:
- **Non-primary observation loops** (Android `NonPrimaryObservationScheduler`, desktop 3d): fetch
  `actualsFeeds` minus the displayed source's own feed (that one has its own loop).
- **METAR / Synoptic refreshers**: `MetarFetchPolicy` / `SynopticFetchPolicy` ask
  `ActualsFeedPolicy.borrowers(feed, visibleSources)`; they get the gated forecast set instead of
  all visible sources.
- **Displayed source's own feed** (current-temp loop, `ActualsFeedPolicy.fullRefreshFetch`):
  unchanged — the displayed source is always in `forecasts`, so its feed is always in `actualsFeeds`.

What borrowers need is the provider's **observations**, and those come from the observation paths
above (the displayed source's own loop is never gated; the non-primary and METAR/Synoptic paths use
the gated set). `actualsFeeds` is reported in the `SOURCE_FETCH_GATE` line so the derivation is
visible.

**Accepted losses (user):** Statistics accuracy and the past-day "yesterday's forecast" bar for a
gated-out source have gaps for the days it wasn't fetched.

## Tests

| # | Kind | Test |
|---|---|---|
| 1 | Unit (:shared) | gate table above, row by row (7 vs 8 days at the boundary, either side of midnight) |
| 2 | Unit (:shared) | displayed / primary always in, at any p |
| 3 | Unit (:shared) | HOME and OBSERVATIONS switches count as views; a 31-day-old row (pruned) doesn't |
| 4 | Unit (:shared) | no tracking row, or tracking < 8 days → everything in |
| 5 | Robolectric | scheduled sync with a gated-out source: `visibleSourcesToFetch` excludes it, `requiresNetworkFetch` false when only it is stale |
| 6 | Robolectric | targeted forced sync (toggle) of a gated-out source still fetches it |
| 7 | Robolectric | API toggle to a gated-out source → row today → next scheduled sync includes it |
| 8 | Robolectric | `NonPrimaryObservationScheduler` skips gated-out sources |
| 9 | Integration (Robolectric) | Google displayed with NWS actuals, NWS forecast gated out: the METAR/Synoptic/non-primary paths are unaffected for Google, and Google's provider observations path (displayed) is not gated |
| 12 | Unit (:shared) | `actualsFeeds` table above, row by row (inside / outside NWS coverage, explicit picker choice vs. borrower default) |
| 13 | Unit (:shared) | changing the actuals-provider preference changes `actualsFeeds` with no stored state |
| 14 | Unit (:shared) | a forecast-only source never appears as its own feed |
| 15 | Robolectric | `MetarFetchPolicy` / `SynopticFetchPolicy`: a gated-out borrower no longer keeps its station network fetched; a displayed one does |
| 10 | Unit (desktop) | 3c / 3d source selection uses the gate (extract the selection into a testable function) |
| 11 | Integration (desktop) | rows in `weather.db` → daemon selection matches the shared gate (parity with #5) |

On-device: after implementation, the Pixel has one day of data → everything stays on (prior), which
the `SOURCE_FETCH_GATE` line will show. Verify the gated-out path with a test DB seeded with 30
days of no switches, pulled from the emulator.

## Decisions (user, 2026-10-10)

1. Not viewed in 8 days → not fetched (recency rule, above).
2. An untargeted Refresh / location change **skips** gated-out sources.
3. No Settings line.

## Verification (2026-10-10)

- `:shared` `SourceFetchGateTest` (10): the 7-vs-8-day table, grace (< 8 tracked days, no tracking
  row), displayed/primary always on, HOME/OBSERVATIONS count, the actuals-feeds table (inside/outside
  NWS coverage, provider choice), forecast-only never its own feed, METAR tier over the gated set.
- Android `SourceFetchGateRoboTest` (7): scheduled and untargeted-forced selection skip gated-out
  sources, a targeted force fetches its target, `requiresNetworkFetch` ignores gated-out staleness,
  fail-open with no gate; the loader from Room rows (7 days on / 8 off, a switch today brings a
  source back, grace, no location → every source).
- Desktop `DesktopSourceFetchGateTest` (5): same fixture as Android → same set (parity), never
  viewed → no other source, a switch today, one log line per decision.
- Full suites green: `:shared`, `:desktop`, `:app` (2455 tests, 0 failures).
- Emulator (seeded: tracking started 2026-09-10, no switches): `SOURCE_FETCH_GATE
  on=GOOGLE_WEATHER off=OPEN_METEO,SILURIAN,NWS feeds=NWS` — Google displayed, its borrowed NWS
  feed kept. Tapped the API button → `OPEN_METEO|HOURLY|TOGGLE` row; Open-Meteo data was < 4 h so
  no refetch (`STALE_REFRESH_SKIP fresh_data`, as designed); next gate: `on=GOOGLE_WEATHER,OPEN_METEO
  off=SILURIAN,NWS feeds=NWS,SYNOPTIC`. (Forcing the app's jobs also ran the daily history prune
  once on the emulator.)
- Pixel 7 Pro (installed, tracking started today → grace): first natural run logged
  `SOURCE_FETCH_GATE on=GOOGLE_WEATHER,OPEN_METEO,SILURIAN,NWS off= feeds=NWS,SYNOPTIC` — nothing
  gated until 2026-10-18, as designed.
- Desktop rebuilt and restarted; its tracking started today, so it is in grace (everything fetched)
  until 2026-10-18. No gate line yet: the other-source loops run on their own cadence.
- Not observed live: a gated-out source with > 4 h-old data being fetched on a switch (covered by
  the targeted-force test); a scheduled full sync skipping a source (covered by the selection tests).

Correction made during implementation: the NWS station pull writes NWS's own rows only; borrowers
read the provider's observations. It stays with the NWS forecast fetch (see the riders table).
