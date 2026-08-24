# aviationweather.gov METAR transport — implementation plan

**Date:** 2026-08-23
**Status:** Plan. Not started.
**Summary:** `summaries/260823-aviationweather-metar-transport.md`
**Origin:** §4 (Cluster B) of `plans/260823-metar-data-incorporation-brainstorm-opus.md`, selected
after §2.5/§2.6 ruled out the Cluster A precision line and §7's elevation recommendation was
measured out (the three official stations span 11 m; lapse explains 0.13 °F of a 4.24 °F spread).

---

## 1. Goal

Two user-visible outcomes from one transport:

1. **International station actuals.** Today a non-US user has none — NWS discovery goes through
   `/points` → `observationStationsUrl` (`NwsObservationSource:98`), which fails outside the US, and
   every other source is model output with `supportsTemperatureActuals = false`.
2. **One multi-station call** replacing N per-station pulls per fetch cycle.

Non-goals: retiring the NWS observation path; TAFs; any UI beyond the Stations list.

## 2. Endpoints (verified 2026-08-23, no API key, HTTP 200)

**Discovery**

```
GET https://aviationweather.gov/api/data/stationinfo?bbox=<lat0>,<lon0>,<lat1>,<lon1>&format=json
```
```json
{"id":"KHWD","icaoId":"KHWD","site":"Hayward Exec","lat":37.65886,"lon":-122.12116,
 "elev":9,"state":"CA","country":"US","priority":6,"siteType":["METAR","TAF"]}
```

Returns non-METAR sites too (`AAMC1` has `siteType: []`) — **filter on `siteType` containing
`METAR`** or you will request ids that never return data.

**Data**

```
GET https://aviationweather.gov/api/data/metar?ids=A,B,C,D,E&format=json&hours=<n>
```
```json
{"icaoId":"KSJC","obsTime":1787503980,"reportTime":"2026-08-23T17:00:00.000Z",
 "temp":20,"dewp":14.4,"wdir":0,"wspd":0,"visib":"10+","altim":1014.6,"slp":1014.4,
 "metarType":"METAR","clouds":[{"cover":"SCT","base":8000},{"cover":"BKN","base":10000}],
 "lat":37.3594,"lon":-121.9244,"elev":13,"rawOb":"METAR KSJC 231653Z ... RMK AO2 SLP144 T02000144"}
```

Worldwide: LFPG/LFPO/LFPB/LFPV/LFPN/LFPT/LFPM returned for a Paris bbox; EGLL and RJTT by id.

## 3. Design decisions

### D0 — What METAR actuals are FOR (settled 2026-08-23 with the user)

METAR observations supply actuals to **real forecast providers whose product carries no observation
component**. The set composes from two lists that already exist:

```kotlin
WeatherSourceOrdering.ALL_CONFIGURABLE.filter { !it.supportsTemperatureActuals }
// => [OPEN_METEO, SILURIAN]
```

`ALL_CONFIGURABLE` means "sources the user can enable" — actual providers. It excludes `GENERIC_GAP`
because **GENERIC_GAP is not a forecast API**: it synthesizes climate-normal rows for *future* dates
beyond real forecast coverage, read-time only, never persisted (`ClimateGapFiller` KDoc,
`generic_gap_long_term_only`). It can never need actuals — a day it filled has real forecast rows
from real providers by the time it is in the past, and it is never a display source. Scanning the
enum for `supportsTemperatureActuals == false` alone would wrongly sweep it in.

Sources that DO ship an actuals product (NWS, WeatherAPI, Tomorrow.io, Visual Crossing,
OpenWeatherMap) keep grading against their own. The accuracy model changes only where it was absent.

**Why this is the right scoping:**

- Measured on the emulator 2026-08-23, `OPEN_METEO` has **zero** observation rows — no real product,
  and the synthetic backfill is now gated off (`supportsHistoricalActualsBackfill = false`). Its
  accuracy score is currently computed against nothing.
- International falls out for free with no geographic special-casing: outside the US, NWS is
  unavailable, so the user is on Open-Meteo — which is exactly a no-actuals source.
- It is the inverse of `HistoricalActualsBackfill`, which answers "no actuals" with the source's own
  forecast (circular; `synthetic_backfill_hijacks_blend` records it hijacking the blend).

**Out of scope here:** whether METAR should displace the forecast-as-actual backfill still running
for OPEN_WEATHER_MAP (`OPEN_WEATHER_MAP_MAIN` + `_1..4`, written 2026-08-23 12:24), and whether
sources that have actuals but not *at this location* (NWS in Paris) should also read METAR. Both are
per-location rules, materially bigger, and deliberately deferred.

### D1 — New `METAR` WeatherSource, not `api="NWS"`

```kotlin
METAR(
    id = "METAR",
    displayName = "METAR",
    shortDisplayName = "MTR",
    supportsHourly = false,                                   // no forecast product
    historicalDataKind = HistoricalDataKind.STATION_OBSERVATION,
    supportsTemperatureActuals = true,
    supportsHistoricalActualsBackfill = false,                // never re-file forecasts as obs
),
```

Rationale: a French station's report is not National Weather Service data; separate provenance lets
METAR and NWS rows coexist for comparison (the composite key already includes `api`), which is how
this app treats every other source pair. `GENERIC_GAP` is the precedent for a `supportsHourly =
false` enum entry that is not a forecast provider.

**Resolved 2026-08-23.** The mechanism is `WeatherSourceOrdering.ALL_CONFIGURABLE` — an explicit
opt-in list, documented as "every source the user can enable in Settings", excluding `GENERIC_GAP`
and deprecated providers (`VISUAL_CROSSING` is in the enum but not in the list). METAR goes in the
enum and **not** in `ALL_CONFIGURABLE`: it is an actuals feed, never a display source. Per D0 its
rows are read by the no-actuals providers, so it does not need to be selectable to be useful.

### D2 — Two calls, both cached

- Discovery: `stationinfo?bbox=` once per 24 h, cached under a new prefs key mirroring
  `observation_stations_v4_<hash>` / `observation_stations_time_v4_<hash>`.
- Data: one `metar?ids=` per fetch cycle, on the existing battery-aware cadence.

Not `metar?bbox=` for data: it returns every station in the box, unbounded (dense regions could
return 50). `ids=` preserves the "N nearest" shape the IDW blend already expects.

### D3 — bbox sizing

Longitude degrees shrink with latitude, so the box must be scaled by `cos(lat)` — at 60 °N a degree
of longitude is half a degree of latitude. Start at ±0.35 ° latitude and the cos-scaled longitude
equivalent; expand ×2 then ×4 until ≥5 METAR-capable stations are found or a ~150 km cap is hit.
Cache whichever box succeeded so the expansion is not re-walked daily.

### D4 — Timestamp must be `obsTime`, not `reportTime`

`reportTime` is pre-rounded to the hour. The `observations` PK is
`(stationId, timestamp, locationLat, locationLon)`, so two SPECIs inside one hour would collide and
one would be silently lost. `obsTime` is the actual observation instant. (`reportTime` may still be
worth keeping for the hour-bucketing that `CloudHourBucket` does by hand today, but it must not be
the key.)

### D5 — Supplements, never replaces

METAR rows are added alongside NWS rows; nothing falls back across sources
(`no_cross_source_fallback`). In the US the user gains a comparison; outside it, METAR is the only
actuals source.

### D6 — Reuse, do not reimplement

`MetarDecoder`, `MetarRawSkyParser`, `MetarSkyCover` (for `rawOb` remarks and any sky gap),
`LocationMatch.quantize` at the write boundary, `ObservationEntity` / `ObservationReading` unchanged
apart from `api`. `rawOb` → `rawMetar`, which the fixes in `4bc4a298` already normalize to NULL when
blank.

### D7 — Politeness

Send the app's existing User-Agent. Track calls in `api_usage_stats` like every other source. One
discovery call/day plus one data call/cycle is strictly fewer requests than today's N/cycle.

## 4. Components

| New | Module | Responsibility |
|---|---|---|
| `AviationWeatherApi` | `:shared` | HTTP + JSON for both endpoints |
| `AviationWeatherBbox` | `:shared` | pure: lat/lon + expansion → bbox string, cos(lat)-scaled |
| `AviationWeatherStationFilter` | `:shared` | pure: filter `siteType`, rank by distance, cap at N |
| `MetarObservationMapper` | `:shared` | pure: JSON row → `ObservationReading(api = "METAR")` |
| `MetarObservationSource` | `:app` | discovery cache + fetch orchestration, mirrors `NwsObservationSource` |

Wiring: `ObservationRepository` (`:148`) and `AppModule` (`:212`), alongside the existing source.

## 5. Testing

Pure functions, no mocking framework, per `testing-strategy`.

**`AviationWeatherBbox`**
- cos(lat) scaling: a box at 60 °N is ~2× wider in longitude degrees than at 0 °
- expansion ladder produces strictly growing boxes and stops at the cap
- antimeridian crossing (lon 179.8 → box spanning ±180)
- pole clamping (lat 89.9 does not produce lat > 90)
- southern/western hemisphere sign handling

**`AviationWeatherStationFilter`**
- `siteType: []` (AAMC1) is excluded; `["METAR"]` and `["METAR","TAF"]` are kept
- ranked by true distance, not bbox order
- capped at N; fewer than N available returns what exists rather than erroring
- ties broken deterministically (no flapping station sets between cycles)

**`MetarObservationMapper` — the live-observed type hazards**
- `wdir` as `340` (int) **and** `"VRB"` (string) — observed on adjacent KNUQ reports, same field
- `visib` as `"10+"` (string), not a number
- `dewp` as `14.4` (float) and `15` (int)
- `slp` absent on some rows
- `temp` null → observation dropped, not stored as 0
- `obsTime` used for the PK; a SPECI and a METAR in the same hour produce **two** rows
- `clouds[]` → `CloudLayer`, base in feet → metres
- `clouds: []` maps to "not reported" (null), never 0 — the invariant
  `NwsObservationMapperCloudTest` pins for the NWS path
- `metarType: "SPECI"` retained
- `api == "METAR"`, `rawMetar == rawOb`

**Integration** (2+ classes, per `feedback_integration_test_definition`)
- captured real payload → `AviationWeatherApi` parse → `MetarObservationMapper` → `MetarDecoder` →
  `MetarSkyCover`, asserting the end-to-end `ObservationReading` for a US and a non-US station
- discovery → filter → id list → data call shape, from a captured `stationinfo` payload

**Live verification**
- US location: METAR rows land alongside NWS rows, same stations, temperatures agree within
  quantization
- Non-US location (set via ConfigActivity manual coordinates, e.g. Paris): METAR rows appear where
  today there are none, and the widget renders actuals
- Row-count growth measured against the 1-month retention window

## 6. Phasing

1. Transport + parser + discovery, stored under `api="METAR"`, no UI. Verify rows land.
2. Surface in the Stations list and Settings sources list (gated on D1's open question).
3. *Optional, later:* retire per-station NWS pulls where METAR covers the same station — only after
   comparison shows parity. Not in scope here.

## 7. Risks

- **D0/D1 are settled** (2026-08-23). The remaining design risk is provenance labelling: nothing in
  the Stations or Blend UI may imply Open-Meteo *measured* a METAR reading. Needs a distinct station
  identity or origin marker before phase 2.
- **Row growth** roughly doubles US observations. Measure before phase 2.
- **`aviationweather.gov` has no SLA** for third-party use. It is additive, so an outage degrades to
  today's behaviour in the US; outside the US it degrades to no actuals, which is also today's
  behaviour.
- **Station churn**: if the discovered set flaps between cycles the blend sees inconsistent inputs.
  Mitigated by the 24 h cache and deterministic tie-breaking.
