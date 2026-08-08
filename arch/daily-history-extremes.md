# How `daily_history` temperature extremes are populated

Last verified 2026-08-08 against the Pixel 7 Pro backup `20260808_000545`, the live desktop DB, and
`api.weather.gov`.

## The short answer

**No — the "API extreme" and the "blend extreme" do not share a data source.** They are two
independent pipelines that happen to land in the same row:

**Updated 2026-08-08:** for **past days** they now share a source — one per-day pull of
`/stations/{id}/observations` across the 5 nearest stations produces both. They remain different
*calculations*:

| Field | Calculation | Stations |
|---|---|---|
| `apiHighTemp` / `apiLowTemp` | raw min/max of one station | nearest **official** only, coverage guard |
| `computedHighTemp` / `computedLowTemp` | IDW blend interpolated to your coordinates | **all 5**, personal ones discounted by your preference |

**Today's row still differs.** The live blend reads the stored `observations` table — roughly half
Synoptic for NWS — because that is what keeps the current temperature fresh, and it is recomputed
on the widget render path where a network call does not belong. Once the day rolls into history the
pull replaces it, and a `apiStationId != null` marker freezes it against later recomputes.
See [Why they differ](#why-the-two-differ).

## The four temperature fields

`daily_history` carries three conceptually different temperatures per (date, source, location):

| Field | Meaning | Nullable |
|---|---|---|
| `computedHighTemp` / `computedLowTemp` | **"Location actual"** — IDW blend of all stations interpolated to the user's coordinates | no |
| `apiHighTemp` / `apiLowTemp` | **"API actual"** — what the provider itself reports happened | yes |
| `forecastHighTemp` / `forecastLowTemp` | frozen forecast overlay (the yellow accuracy bar), snapshotted while the day was current | yes |
| `apiStationId` / `apiStationDistanceKm` | which station produced the API actual, when it is station-derived | yes |

---

## Pipeline 1 — the blend (`computedHighTemp` / `computedLowTemp`)

```
observations table  ─┐
                     ├─→ ObservationResolver.computeDailyExtremes
hourly_forecasts    ─┘        └─→ ActualsAggregator.aggregate
                                    └─→ blendDailyExtremesViaSeries
                                          └─→ ActualTemperatureSeriesBuilder.blendObservationSeries
                                                └─→ max/min over the target calendar day
```

**Entry points:** `DailyActualsStore.recomputeDailyExtremesForDay` (Android),
`DesktopWeatherRepository.recomputeDailyExtremes` (desktop). Called from the widget data loader, the
observation backfiller, and `ForecastHistoryActivity`.

**Key properties:**

- Blends over a window widened by `ActualsAggregator.DAILY_BLEND_CONTEXT_MS` (±24 h) so stations
  whose feed lapsed near midnight still participate; extrema are still taken from the target day
  alone.
- A station with no reading at a given timestamp is **carried forward by the forecast's own change**
  over the gap (`extrapolateForward`, up to 3 h). This is deliberate — it stops a station's
  contribution to past hours depending on whether some later reading exists, which made high/low
  labels blink — but it means the blend has a path back to the forecast.
- Personal stations are discounted by `DEFAULT_PERSONAL_STATION_DISCOUNT`.

**What is in the observations table** (3-day sample, reference location):

| `api` | rows | Synoptic (`isWebFallback`) | station ids |
|---|---|---|---|
| `NWS` | 692 | 327 (47%) | AW020 (PWS), KNUQ, KPAO, KSJC (official), LOAC1 (PWS) |
| `OPEN_METEO` | 947 | 0 | `OPEN_METEO_MAIN` + `OPEN_METEO_1..4` — all synthetic |
| `SILURIAN` | 59 | 0 | `SILURIAN_MAIN` — synthetic |

Synoptic rows arrive via the prefer-newest latest-observation path
(`ObservationFallbackPolicy.FETCH_BOTH_ENABLED`, nearest 3 stations fetch both sources every cycle).
For KNUQ they redistribute the same ASOS METARs the NWS API serves, so they are not foreign data —
but they are not NWS API rows either.

`<SOURCE>_MAIN` rows are `HistoricalActualsBackfill` output: that source's own hourly **forecast**
re-filed as observations, so a forecast-only source's blend is derived from its own forecast. See
`historical_actuals_provenance`.

---

## Pipeline 2 — the API actual (`apiHighTemp` / `apiLowTemp`)

One writer per source. There is no shared path.

### NWS — dedicated `/stations/{id}/observations` pull

```
NwsApiDailyActualsFetcher.fillMissingIfNeeded          (Android)
DesktopWeatherRepository.fillNwsStationActualsIfNeeded (desktop)
   └─→ NwsDailyExtremesFetch.resolveForDates           (shared orchestration)
         ├─ for each missing calendar day:
         │    GET /stations/{id}/observations?start=&end=  per station, ONE DAY per request
         └─→ StationDailyExtremes.resolve
               └─→ nearest OFFICIAL station passing the coverage guard; raw min/max
```

**Two guards, not one.** `StationDailyExtremes`' per-station coverage guard governs the api actual.
A second, pool-level guard (`NwsDailyExtremesFetch.poolCoversDay`) governs the blend: unless the
pulled readings span both the 00:00–07:00 and 12:00–18:00 windows, the day writes nothing. Without
it the oldest day in range — truncated by the endpoint's rolling retention, see below — blended a
low 5.18 °F too warm over a correct stored value.

**Why per-day requests:** the endpoint caps a response at **500 features and returns the newest
ones**. Measured 2026-08-08: `KSJC?start=-7d` returned 500 rows spanning only the most recent
3 days — days 4-7 vanished silently; `KNUQ?start=-7d` hit the cap at 8 partial days. A truncated
day is worse than a missing one, because it can still satisfy the coverage guard while missing the
peak. A single day is far below the cap (busiest station observed ~300/day).

- **Triggered by** NWS rows in the last 7 days whose `apiHighTemp` or `apiLowTemp` is null.
  Idempotent and self-healing; a date drops out once filled. Dates older than
  `NwsDailyExtremesFetch.MAX_LOOKBACK_DAYS` cost zero requests.
- **API-only.** Goes through `NwsObservationSource.fetchApiObservationsOnly` /
  `DesktopWeatherService.fetchApiObservationSeries`, which skip the Synoptic substitution that
  `fetchHistorical` performs. The value must be NWS's own measurement.
- **Nothing is written to the `observations` table.** The fetched readings exist only to compute the
  extreme.
- **Coverage guard:** a station qualifies only with at least one reading in 12:00–18:00 local (for
  the high) and one in 00:00–07:00 (for the low); otherwise fall through to the next-nearest
  official station. If none qualifies the day is left null — never guessed.
- Personal weather stations are excluded, as are `NWS_BLEND` and `<SOURCE>_MAIN` rows.

### Open-Meteo — `past_days` ERA5

`DailyActualsStore.persistOpenMeteoPastDayActuals` (Android) /
`DesktopWeatherRepository.persistOpenMeteoApiActuals` (desktop). When the `/forecast` call is made
with `past_days > 0`, the response's daily array carries ERA5-based observed values for past dates.
Gated on the active display source being Open-Meteo, because `result.daily` belongs to whichever
source is active.

### Every other source — nothing

Silurian, WeatherAPI, Tomorrow.io, Visual Crossing and OpenWeatherMap have **no `apiHighTemp`
writer**. Their rows stay null. Confirmed on device: `SILURIAN` 0/7 rows populated, `WEATHER_API`
0/1. See [Known gaps](#known-gaps).

---

## Pipeline 3 — the frozen forecast overlay

`DailyHistorySnapshotter` writes `forecastHighTemp`/`forecastLowTemp` (and `noonCloudPercent`,
`forecastDayPrecipChance`, `forecastNightPrecipChance`) while the day is still current, so a past day
can be rendered from its `daily_history` row alone — the `forecasts` and `hourly_forecasts` tables
have shorter retention. Governed by `DailyHistoryFreeze`. High and low move as a unit.

---

## Why the two differ

At the reference location on 2026-08-05:

| Value | Reading |
|---|---|
| `apiHighTemp` — KNUQ raw, from the API pull | 75.2 |
| `computedHighTemp` — IDW blend at the user's coordinates | 75.0 |
| NWS forecast for that day | 82 |

They are close here but measure different things: one thermometer 3.8 km away versus an
interpolation to your address that leans on the forecast to bridge gaps. The gap widens wherever
station siting varies — inland San Jose (KSJC, 15.9 km) read 81 the same day.

**This is why the blend is not used as the accuracy baseline by default.** Scoring a forecast
against a partly forecast-extrapolated actual understates the error. The statistics screen's
baseline selector (`AccuracyBaselineField`, stored as `accuracy_baseline_field`) chooses between
them; `ActualsBaselineResolver` separately chooses *whose row* supplies the actual so a
forecast-only source is never graded against itself.

---

## Ordering and conflict rules

- `persistExtremes` builds the merged candidate row and compares it **whole** against the stored
  fragment rather than enumerating fields. Enumerating is how precip-only deltas were once silently
  dropped; a whole-row compare cannot go stale when a column is added.
- The blend recompute **never touches** `api*`. It carries the stored values through untouched, so
  the two pipelines cannot clobber each other regardless of ordering.
- The station pull writes to **every** same-date NWS fragment in the proximity box, not just the
  nearest. The value describes a station, not a coordinate, so it is equally true for each fragment
  of the same site — and leaving stale fragments unfilled is what let a partial row shadow a
  complete one (see `ApiActualPicker`).
- Reads use `ApiActualPicker.pickNearestComplete`: nearest-first ordering, but the first fragment
  with a **complete** pair wins. Both platforms use it.

---

## Known gaps

1. **Most sources have no API actual.** `HistoricalDataKind` declares Silurian and WeatherAPI as
   `ARCHIVED_PROVIDER_HISTORY` and Tomorrow.io as `RECENT_ANALYSIS`, but nothing writes their
   `apiHighTemp`. With the statistics baseline set to `NATIVE_ACTUAL` those days silently fall back
   to the blend — flagged per row via `ResolvedBaselineTemps.fellBackToBlend`, but the underlying
   product is simply not being ingested.
2. **`StationDailyExtremes` is calibrated for NWS.** Open-Meteo's pseudo-stations
   (`OPEN_METEO_1..4`, ~8 km, `stationType = "OFFICIAL"`) are not `_MAIN` and would therefore pass
   the synthetic filter if the resolver were ever invoked for a non-NWS source. Today
   `NwsDailyExtremesFetch` hardcodes `sourceId = "NWS"`, so this is latent, not live.
3. **`maxTemperatureLast24Hours` is always null here.** NWS parses into it
   (`NwsApi.kt:99-119`) but returned null in all 761 observations sampled across KNUQ/KSJC/KPAO over
   three days. Not a usable source at this location.
4. **CLI is unavailable nearby.** NWS's official Climatological Report (Daily),
   `/products/types/CLI/locations/{site}`, carries QC'd calendar-day extremes — but only for 628
   designated climate sites. Neither KNUQ (3.8 km) nor KPAO (6.0 km) is one; the nearest is KSJC at
   15.9 km, which runs ~6 °F warmer. Evaluated and rejected for this location; retention there is
   14 products ≈ 7 days.

---

## History

Two writers were removed on 2026-08-08 because both filed non-observations as observed values:

- **Gridpoint forecast as actual.** `persistNwsGridpointActuals` stored
  `/gridpoints/{office}/{x},{y}` `maxTemperature`/`minTemperature` — the raw NDFD **forecast** grid,
  the same map `NwsDailyMapper.mergeGridpointTemperatures` uses for future days — into `apiHighTemp`
  for past dates. Every past day's "actual" equalled that day's forecast (2026-08-05 stored 82.0
  against a real 75.0). Diagnostic tell: `apiHighTemp == forecastHighTemp` on the same row.
- **ERA5 as NWS's actual.** `backfillNwsApiActualsFromArchive` filled the remaining gaps from
  Open-Meteo's archive — another provider's data in NWS's row.

Room migration 58→59 and desktop schema v13 clear every pre-existing NWS `api*` value, since neither
writer ever produced a legitimate one.

A first replacement computed the extreme from **stored** observation rows. That was also wrong:
`/stations/KNUQ/observations` returns ~72 readings/day but only 17–24 survive in storage as API rows,
and the retained subset missed the 08-05 and 08-06 peaks by 1.8 °F. Filtering to
`isWebFallback = 0` would have made it worse, not better. Hence the dedicated pull.

Full rationale and measurements: `plans/260808-nws-actuals-forecast-contamination.md`.
