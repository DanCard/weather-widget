# NWS "API actual" is the forecast; accuracy grades each source against itself

Date: 2026-08-08
Reported by user: "for several days the high temp matches the forecast exactly."
Evidence: Pixel 7 Pro `2A191FDH300PPW` + Samsung fold `RFCT71FR9NT` backups `20260808_000545`,
and the live desktop DB `~/.local/share/weather-widget/weather.db` (2026-08-08 00:03).

## Problem 1 — the NWS "API actual" is that day's NWS forecast

`NwsApi.getGridpointsBundle` parses `maxTemperature`/`minTemperature` from
`/gridpoints/{office}/{x},{y}` — the raw NDFD **forecast** grid. One parsed
`DailyTemperatureExtremes` then feeds two consumers, split only by a date comparison:

| Consumer | Dates | Writes |
|---|---|---|
| `NwsDailyMapper.mergeGridpointTemperatures` (`NwsDailyMapper.kt:113-115`) | `>= today` | the **forecast** high/low |
| `DailyActualsStore.persistNwsGridpointActuals` (`DailyActualsStore.kt:398-399`) | `< today` | `apiHighTemp`/`apiLowTemp`, documented as "API-reported **observed** high" |

The gridpoint response carries yesterday's already-issued forecast window until it rolls off
mid-morning. That leftover forecast is filed as the actual. NWS publishes no observed daily
extremes on `/gridpoints`; observations live only under `/stations/{id}/observations`.

### Measured

| Date | `computedHighTemp` (station blend) | stored "API actual" | NWS forecast high | Open-Meteo ERA5 |
|---|---|---|---|---|
| 2026-08-05 | 75.0 | **82.0** | **82.0** | 75.5 |
| 2026-08-06 | 75.0 | **81.0** | **81.0** | 74.0 |
| 2026-08-07 (Samsung) | 72.3 | **82.0** | **82.0** | — |

`app_logs` confirms the timing: `NWS_GRIDPOINT_ACTUALS dates=1 min=2026-08-06 max=2026-08-06`
fires five times between 00:56 and 10:09 on 08-07, then flips to `skipped (no past dates)`. An
"actual" that is rewritten five times by successive forecast issuances is not an observation.

### Three follow-on defects

1. **The bad value permanently blocks the good one.** `backfillNwsApiActualsFromArchive`
   (`DailyActualsStore.kt:583-590`) fills only nulls, and runs *after* the gridpoint write
   (`NwsForecastMapper.kt:93` inside the fetch vs `ForecastFetchCoordinator.kt:270` at the end).
   Fixing the writer alone leaves ten days of poisoned rows.
2. **Read-across-box, write-at-quantized clones the row.** `existing` is read through
   `getExtremesInRange`'s ~7 mi `ROOM_WHERE` box (`:406-412`) but the upsert is keyed at
   `LocationMatch.quantize(...)` (`:392-393`). Both phones carry two rows for 08-05/08-06 — one at
   `37.4168…` holding ERA5 (77.2), one at `37.417` holding the forecast (82.0). Which displays is a
   distance tiebreak. See [[shared_location_match_predicate]].
3. **Manufactured rows leak forecast into the blend field.** `:440-441` sets
   `computedHighTemp = maxTemp` and `computedLowTemp = minTemp ?: maxTemp` when no row exists in the
   box — a forecast in the field that drives the widget's actual bar, and a day's low potentially set
   to its forecast high. Masked today because the box read almost always finds the blend row;
   reachable on fresh install or after a location move ([[location_move_collapses_today_actuals]]).

Desktop has the same writer at `DesktopWeatherRepository.kt:886-931`, and its history view picks the
fragment with a bare `find` (`ForecastHistoryWindow.kt:523`) — no distance ordering, despite
`ApiActualPicker`'s KDoc claiming desktop mirrors it.

## Problem 2 — accuracy grades every source against its own actuals

`AccuracyCalculator.kt:88-89`:

```kotlin
val extremes = dailyHistoryDao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
    .filter { it.source == source.id }
```

Each source gets a different yardstick, so the cross-source table in `StatisticsActivity` is not
apples-to-apples:

| Source | 1-day-ahead high error, 08-04..08-07 | Scored against |
|---|---|---|
| NWS | −6.0, −10.8, −11.0, −11.7 | real station blend |
| Open-Meteo | −1.3, +1.5, +2.1, −2.7 | its own ERA5 `past_days` |
| Silurian | −3.2, −5.5 | its own `/history/hourly` |

Worse, for `HistoricalDataKind.NONE` sources (Visual Crossing, OpenWeatherMap — not currently
enabled here) `computedHighTemp` blends nothing but that source's own `<SOURCE>_MAIN` backfill rows,
i.e. its own hourly forecast re-filed as observations ([[historical_actuals_provenance]]). Those
sources would score near-perfect by construction — the same disease as Problem 1, one layer up.

## What changes

### 1. Stop writing forecast into actual fields

- Delete `DailyActualsStore.persistNwsGridpointActuals` and its call site `NwsForecastMapper.kt:93`.
- Delete `DesktopWeatherRepository.persistNwsApiActuals` and its call site.
- Keep `NwsApi.parseDailyExtremes` — the forecast merge still needs it. Its `"GRID:max"` provenance
  tagging stays as-is.

### 2. Remove the ERA5 backfill into the NWS row

User decision 2026-08-08: no Open-Meteo data in NWS's row, per [[no_cross_source_fallback]].

- Delete `NwsApiActualsBackfill`, `DailyActualsStore.findNwsDatesMissingApiActuals`,
  `backfillNwsApiActualsFromArchive`, `ForecastFetchCoordinator.backfillNwsApiActualsIfNeeded`, and
  the desktop equivalent at `DesktopWeatherRepository.kt:982-1010`.
- `OpenMeteoApi.getHistoricalDailyTemps` stays — Open-Meteo's *own* row legitimately uses it.

### 3. NWS `apiHighTemp`/`apiLowTemp` = nearest official station, from a dedicated API pull

**Revised 2026-08-08 after user review** ("for knuq should be using /stations/{stationId}/observations
via api.weather.gov"). The first cut computed the extreme from *stored* observation rows. Measured
against the live endpoint, that is wrong: `/stations/KNUQ/observations` returns ~72 readings/day but
only 17-24 survive in storage as API rows (the rest are Synoptic, written by the prefer-newest
latest path), and the retained subset missed the 08-05 and 08-06 peaks by 1.8 °F. Filtering to
`isWebFallback = 0` would therefore have made it *worse*, not better.

NWS daily extremes now come from a **dedicated complete pull** — one
`/stations/{id}/observations?start=&end=` request per station spanning every missing date, resolved
per calendar day, with nothing written to the observations table. `NwsDailyExtremesFetch` (shared)
orchestrates; `NwsApiDailyActualsFetcher` (Android) and `fillNwsStationActualsIfNeeded` (desktop)
supply the platform fetch. The recompute no longer touches `api*` at all.

NWS also publishes CLI (Climatological Report, Daily) with official calendar-day extremes, but only
at 628 designated climate sites; neither KNUQ (3.8 km) nor KPAO (6.0 km) is one, and the nearest is
KSJC at 15.9 km reading ~6 °F warmer. Rejected as a source for this location. Original design:

New shared pure function, `shared/.../actuals/StationDailyExtremes.kt`, so Android and desktop
cannot drift ([[feedback_share_android_desktop_logic]]):

```kotlin
data class StationDailyExtreme(
    val stationId: String, val distanceKm: Float, val high: Float, val low: Float,
)

fun resolve(
    dayObservations: List<ObservationReading>,  // one calendar day, one source, all stations
    zone: ZoneId,
): StationDailyExtreme?
```

- Candidates: `stationType == "OFFICIAL"` only. Excludes PWS, `NWS_BLEND`, and
  `ObservationSourceMatcher.isSyntheticBackfillStation` rows.
- **Coverage guard** (mandatory — KPAO logs 13-15 readings/day against KNUQ's 47-79): a station
  qualifies only with at least one reading in 12:00-18:00 local (for the high) and one in
  00:00-07:00 local (for the low). Fall through to the next-nearest official station; return null
  when none qualifies, so the day is excluded rather than guessed.
- Extremes are the raw min/max of that one station's readings. No IDW, no interpolation, no
  extrapolation — the point is a baseline with no path back to the forecast
  ([[observed_dot_is_forecast_extrapolated]]).

Wire it into the existing `recomputeDailyExtremesForDay` pass, which already loads the day's
observations. Persist alongside the blend, never instead of it.

### 4. Schema: record which station (DB v59)

`daily_history` gains `apiStationId TEXT` and `apiStationDistanceKm REAL`. Real Room migration plus
`DesktopWeatherDatabase.addColumnIfMissing`; bump `WeatherDatabase.version` 58 → 59 and export the
schema in the same commit ([[feedback_room_schema_export_rename_order]]).

Without this the history view shows an anonymous number and we cannot detect the chosen station
changing mid-history.

### 5. Accuracy baseline: source-specific with a fallback chain

New shared pure resolver, `shared/.../stats/ActualsBaselineResolver.kt`:

```kotlin
fun resolveBaselineSource(
    gradedSource: WeatherSource,
    orderedVisibleSources: List<WeatherSource>,   // WeatherSourcePreferences.visibleSources()
    hasRowForDate: (WeatherSource) -> Boolean,
): WeatherSource?
```

- If `gradedSource.historicalDataKind != NONE` and it has a row for that date → use its own row.
- Otherwise walk candidates ranked by **kind quality**, `STATION_OBSERVATION` >
  `REANALYSIS_ARCHIVE` > `ARCHIVED_PROVIDER_HISTORY` > `RECENT_ANALYSIS`, using
  `orderedVisibleSources` position as the tiebreak within a kind. First one with a row for that date
  wins.
  - **Decision recorded 2026-08-08:** kind-ranked, not raw priority order. Priority order expresses
    display preference, not data quality; a user whose list starts with WeatherAPI should still be
    graded against NWS station observations when those exist. Swapping to plain priority order is a
    one-line change to the comparator.
- No candidate → return null and **exclude the day** from stats. Never silently fall back to the
  graded source's own forecast. Sample size shrinks; that is correct.

Only Visual Crossing and OpenWeatherMap ever take the fallback path today.

`AccuracyCalculator.getDailyAccuracyBreakdown` stops filtering to `source.id` and instead resolves a
baseline per (source, date). `DailyAccuracy` gains `baselineSource: WeatherSource` and
`baselineKind: HistoricalDataKind`.

### 6. The setting, in `StatisticsActivity`

New pref `accuracy_baseline_field` in `weather_prefs`: `NATIVE_ACTUAL` (default) | `BLENDED_LOCATION`.

- `NATIVE_ACTUAL` → `apiHighTemp`/`apiLowTemp` on the resolved baseline row (for NWS: the official
  station; for Open-Meteo: ERA5; etc.). Rows where those are null fall back to the blend for that
  day, and the row is marked.
- `BLENDED_LOCATION` → `computedHighTemp`/`computedLowTemp`, i.e. today's behaviour.

The chain (#5) picks *whose row*; this setting picks *which field* on it. Expect the toggle to move
NWS noticeably and other sources barely — Open-Meteo's blend is built from `OPEN_METEO_MAIN` rows
backfilled from the same ERA5 that fills its `apiHighTemp`.

Segmented control at the **bottom** of `activity_statistics.xml`, below the daily lists (user
decision 2026-08-08 — the stats are the content, the baseline choice is a footer control). Changing
it re-runs `loadStatistics()`; no refetch, both values are already stored.

### 7. Provenance must be visible

`DailyAccuracyAdapter` shows the baseline per row when it differs from the graded source
(`"vs NWS KNUQ 3.8 km"`), and `stats_summary_text` names the active baseline mode. A number that
does not say what it was measured against misleads — the through-line of this whole investigation.

### 8. Data repair (one-time)

Ten days of NWS rows on both phones and the desktop hold forecast values in `api*`.

- Migration v59 nulls `apiHighTemp`/`apiLowTemp` on **every** NWS row.

  Originally drafted as a value-matching heuristic (null where the api actual equals the row's own
  frozen forecast, plus the quantized clones). Running that against a copy of the real Pixel
  database showed it was half a fix: it cleared the two gridpoint clones (81.0, 82.0) but left the
  ERA5-backfilled values (77.3, 76.9, 77.2, 81.1, 86.1, …) standing — Open-Meteo's data in NWS's
  row, the very thing the no-Open-Meteo decision rules out. Since `persistNwsGridpointActuals` and
  `backfillNwsApiActualsFromArchive` were the only writers that ever populated the field for NWS,
  no stored value is a genuine NWS measurement, so the unconditional clear is both simpler and
  more correct.
- The new station writer refills whatever the retained observations cover (~10 days). Older rows stay
  null and their days drop out of stats.
- Log the repair as `NWS_API_ACTUAL_REPAIR` with counts so it is auditable after the fact.

## Testing

Pure-function first, per [[testing-strategy]] — no mocking framework in this project.

**Shared unit tests (`shared/src/test`), the bulk of the coverage:**

- `StationDailyExtremesTest`
  - picks the nearest OFFICIAL station, not the nearer PERSONAL one — fixture from the real
    2026-08-05 rows (AW020 2.22 km PERSONAL vs KNUQ 3.83 km OFFICIAL → expects KNUQ 75.2, not 77.0).
  - sparse station fails the afternoon guard and falls through to the next-nearest (KPAO fixture).
  - all candidates fail the guard → null, day excluded.
  - synthetic `<SOURCE>_MAIN` and `NWS_BLEND` rows never qualify.
  - a station present only 08:00-11:00 does not set the day's high.
- `ActualsBaselineResolverTest`
  - `NONE`-kind source falls back; `STATION_OBSERVATION` outranks `ARCHIVED_PROVIDER_HISTORY` even
    when the latter is earlier in the user's order (the decision in #5 — must fail if the comparator
    is swapped).
  - tiebreak within a kind follows `orderedVisibleSources`.
  - missing row for the date skips that candidate.
  - empty chain → null.
- `AccuracyPureTest` additions: baseline-field selection honours the setting; null `api*` falls back
  to blend and flags the row.

**Android unit tests (`app/src/test`):**

- `NwsGridpointActualsStoreTest` — repurpose from asserting the gridpoint write to asserting it no
  longer happens, and that `api*` stays null until the station writer runs. Prove the test fails
  against current `main`.
- New `NwsApiActualRepairMigrationTest` — Robolectric, seeded with the real poisoned rows
  (`apiHighTemp = 82.0`, `forecastHighTemp = 82.0`, quantized clone at `37.417`): asserts both are
  nulled and the un-quantized ERA5-era row is left alone.
- `DailyActualsStoreTest` — a past date with no row in the box no longer manufactures one with
  forecast-derived `computedHighTemp` (defect 3).

**Migration test:** extend the existing v58→v59 migration coverage in `migration_test_db`.

**Desktop:** `DesktopApiActualsMergeTest` updated for the removed writers; a parity test asserting
Android and desktop produce identical `StationDailyExtreme` for one shared fixture.

**Manual verification after install:**

1. `python3 scripts/backup_databases.py`, then confirm no NWS row has
   `apiHighTemp == forecastHighTemp` for any date.
2. Confirm `apiStationId` is populated and plausible (expect `KNUQ` at this location).
3. Forecast History for 08-05: expect NWS actual ≈ 75.2 from KNUQ, blend 75.0, forecast 82.0.
4. Toggle the stats setting; confirm NWS's 30-day numbers move and Open-Meteo's barely do.

## Found during implementation

**Desktop `persistOpenMeteoApiActuals` was ungated** (`DesktopWeatherRepository.kt:263`). It wrote
`result.daily` — the *active* source's forecast — into `OPEN_METEO` rows' `apiHighTemp`/`apiLowTemp`
unconditionally. Latent, not firing: the desktop's active source is currently Open-Meteo, and its
stored values (74.0 / 75.5 / 77.3 for 08-06/05/04) are genuine ERA5, not the NWS forecast
(81 / 82 / 88). But with the source set to NWS it would have filed NWS's forecast as Open-Meteo's
actual — the same defect in a third place. Now guarded on `displaySource == OPEN_METEO`.

**Desktop accuracy has no baseline setting.** `DesktopAccuracyCalculator` takes the baseline field
as a constructor parameter and both desktop call sites leave it at
`AccuracyBaselineField.DEFAULT` (`NATIVE_ACTUAL`). The chain and the station actual are fully live
on desktop; only the user-facing toggle is Android-only, matching the request that it live in the
accuracy tracker activity. Adding it to `DesktopConfig` later is a two-line change.

## Out of scope

- Changing what the widget's daily bars or the today-column thermostat display. They keep using
  `computedHighTemp`. Only the accuracy stats and the history view's API-actual row change.
- The blend's forward-extrapolation behaviour ([[observed_dot_is_forecast_extrapolated]]) — it is
  deliberate and load-bearing for label stability.
- Non-NWS coverage. Outside NWS coverage there are no station observations, so NWS's `api*` stays
  null and the chain resolves elsewhere; no new handling needed.
