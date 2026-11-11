# Daily-history temperatures and provenance

`daily_history` combines several kinds of value in one daily row. They are related, but they are not
interchangeable measurements.

## Field meanings

| Fields | Meaning |
|---|---|
| `computedHighTemp` / `computedLowTemp` | Location actual: the shared observation/analysis blend for the site |
| `apiHighTemp` / `apiLowTemp` | Provider or station actual selected for the row, when available |
| `forecastHighTemp` / `forecastLowTemp` | Frozen forecast used for the past-day comparison |
| `priorForecastHighTemp` / `priorForecastLowTemp` | Previous day's forecast for this date, selected at the shared cutoff rules |
| `actualsSource`, `apiStationId`, `apiStationDistanceKm`, `lastWriter` | Provenance used for selection and auditing |
| `observedFogLevel` / `observedFogTopTemp` / `observedFogVisibilityMi` | The morning's observed fog from the source's actuals provider (`ObservedMorningFog`): level code (0 = none, 1 hint … 4 dense), burn-off °F, lowest visibility. Written by `DailyBlendRecomputeRun`; a recompute that cannot judge fog keeps the stored values |

A missing provider actual must remain null rather than being silently replaced with a forecast.
Display fallbacks and accuracy-baseline fallback are separate decisions with their own shared rules.

## Location blend

The blend combines retained observation/analysis rows with hourly context through shared actuals
code, including `ActualTemperatureSeriesBuilder` and `ActualsAggregator`. Station selection,
quality control, personal-station weighting, coverage, and gap handling all affect the result.
Hourly forecasts can provide change-over-gap context; this is why the blend is not automatically the
same thing as an independent thermometer measurement. The stored recompute takes that hourly by one
shared rule on both platforms (`ActualsAggregator.aggregateStored`): every source at the site over the
same window as the observations, so each source's row extrapolates against its own forecast.

The recompute itself is `DailyBlendRecomputeRun` on both platforms: one whole local day at a time
(observations over the day ± 24 h), reaching 9 days back, skipping a day whose observation signature
and resolved actuals providers are unchanged since it was last reduced (forced on the repair paths), and reading the day's existing
rows by their exact date key before `DailyBlendWrite` plans field-limited updates. Rows are filed
under the rounded site (`DailyHistoryKey`); the 2026-10-25 migration merged older raw-fix fragments,
newest first with gaps filled from the others.

`StoredBlendGuard` keeps a past day's stored high/low only when the provider's readings miss the
pre-dawn or afternoon window. `DailyBlendRecomputeRun.WRITE_RULES_VERSION` is part of the skip
signature; bump it when the write rules change so days with unchanged readings are rebuilt once.

The current observation table can include NWS, METAR, Synoptic, and provider analysis/history data.
Provenance matters: Synoptic web-fallback rows filed under NWS represent redistributed station
observations, while a forecast-only provider's elapsed forecast hours must not become measured
actuals.

History of Forecasts draws today's Location actual from the selected source's row by one shared rule
(`ForecastHistoryViewLogic.locationActuals`): the low once `TodayLowSettle` settles it, and the high
from 5pm (`DailyDayValueResolver.isHighSettled`, the today column's rule). Another source's row is
never used. Rows can differ between sources: an inactive source's row stops advancing during the day,
but the screen only shows visible sources. The footer card and error graph stay past-only
(`actualsDisplay`).

## Provider/station actuals

`WeatherSource.historicalDataKind` declares what a provider's historical product represents:
station observations, reanalysis, archived provider history, recent analysis, or none. It drives
baseline eligibility; it does not assert that every product is a direct observation.

NWS station actuals are maintained by `NwsStationActualsRun` on both platforms. It runs as a follow-up
of an NWS fetch that asked for the daily part (`SourceFetchTable.runFollowUps`), even when that forecast
fetch failed: the station endpoints are separate from the gridpoint ones. Prior-day cloud rides every
Open-Meteo fetch and WeatherAPI history a daily WeatherAPI fetch; those two need a stored reply, and an
on-demand single-day fetch runs none. The runner:

1. Finds NWS dates with missing values or provenance.
2. Reads nearby stations and requests one station/day at a time from
   `/stations/{id}/observations` when needed.
3. Uses `StationDailyExtremes` coverage rules for official-station API extrema and a wider station
   pool for the location blend, with personal-station weighting.
4. Falls back to suitable stored observations when an answered request has insufficient data;
   request failure remains distinct from an empty answer.
5. Writes station provenance with the values and updates all same-site NWS fragments that need it.
   The pull's blend lands only where the stored pool cannot build the day
   (`StoredBlendGuard.storedPoolCoversDay`) or the row has none yet. Otherwise the high/low stay the
   recompute's, built from the same pool the hourly graph draws, and the recompute replaces a pulled
   blend on its next run. The pull does not freeze a day: its pool is only what the endpoint
   returns, which can drop reports. On 2026-11-07 it missed KPAO's cooler 13:47 METAR and wrote
   73.4° over a stored 71.5°.

Other providers use their own documented history/analysis products according to `HistoricalDataKind`
and the shared ingestion runners. Google Weather and Silurian are forecast-only for temperature and
cloud actuals, so their actuals are borrowed from an independently configured observation feed.
METAR and Synoptic are actuals feeds, not user-selectable forecast providers.

## Forecast comparisons and upkeep

`DailyHistoryRun` owns cross-platform history maintenance:

- After a fetch, it performs the small time-sensitive rain-chance freeze and ensures yesterday's
  forecast-only history row exists.
- Broader month-wide maintenance runs only when due on a charger. It creates missing forecast-only
  rows, performs gated backfills, settles past forecast overlays, and freezes prior-day forecasts.
- Field-specific host writes protect independent actual and forecast columns from being clobbered by
  an unrelated maintenance pass.

`DailyHistoryFreeze`, `ForecastOverlaySettle`, and `PriorDayForecast` define which forecast values
are frozen. A display-only hindcast or hourly-range fallback is not rewritten into history as though
it had been captured before the day occurred.

## Accuracy baselines

`ActualsBaselineResolver` chooses whose actuals may grade a forecast and prefers measured station
observations over derived provider products. The selected temperature field (`api*` versus
`computed*`) is a separate choice from selecting the source that supplies the baseline. Forecast-only
sources must not be graded against their own elapsed forecasts.

For implementation history and the measurements that motivated these rules, see the dated
`plans/260808-nws-actuals-forecast-contamination.md` and the later shared-runner investigations.
Those documents explain prior bugs; the current rules are the shared code described above.

## Observed morning fog

The daily bar's fog ellipse on a past day reads the three `observedFog*` columns
(`plans/261111-daily-bar-morning-fog.md`). `ObservedMorningFog` reads the readings whose `api` is the
source's resolved actuals provider over 00:00–12:00 local, using the nearest station that reported in
each hour. A raw METAR is foggy when the station flags `FG`/`BR`, when its visibility is at or below
⅝ SM without precipitation, or (a faint hint only) when temperature equals dewpoint with wind ≤ 5 kt
and visibility under 10 SM or cloud at or below 500 ft. Other rows read their condition word. The top
is midway between the last report at the morning's height level and the next report below it: on a
morning with fog proper (`FG` or ≤ 1 SM) trailing mist does not raise it, and hints never prolong real
fog (`MorningFog.heightLevel`; `plans/261111-fog-height-dense-top-bottom-weighted.md`). Today is computed live from
the same rows (`DailyActualsAssembler`), like today's blend. Bumping
`DailyBlendRecomputeRun.WRITE_RULES_VERSION` to 3 backfilled the days still in reach; 5 rewrote them
under the height-level rule.
