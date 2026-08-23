# Incorporating METAR data into the widget — brainstorm

**Date:** 2026-08-23
**Status:** Brainstorm / options menu. **Not an implementation plan** — nothing here is committed to.
Each cluster would need its own plan file before code.
**Grounding:** live probes of `api.weather.gov/stations/{id}/observations` and
`aviationweather.gov/api/data/metar` run 2026-08-23 17:20Z; code read of
`shared/src/main/kotlin/com/weatherwidget/shared/observations/`, `NwsApi.kt`, `SynopticApi.kt`,
`ObservationEntity.kt`, `arch/daily-history-extremes.md`.

---

## 1. Where METAR already is in the app

METAR is not new here — it is **under-consumed**.

| Path | How METAR arrives | What is extracted today |
|---|---|---|
| `NwsApi` → `/stations/{id}/observations` | JSON; a populated `rawMessage` sets `isMetar = true` | temp, dewpoint, condition, precip, `cloudLayers`, 24 h max/min |
| `SynopticApi` (web fallback) | the raw report string in `metar_set_1` | `MetarRawSkyParser` → **sky layers only** |

Raw METAR is parsed for exactly one thing: cloud. `MetarSkyCover`, `MetarCloudBlender`,
`CloudHourBucket`, and the `observations.isMetar` column are all cloud infrastructure sitting on
what is really a METAR decoder stub.

Two structural observations:

- `MetarRawSkyParser`'s own doc comment states the problem: *"Synoptic returns the report itself…
  the parser that reads its timeseries used to take only `air_temp_set_1`."* Every provider hands
  over the same standard report and the app re-maps it per provider. **Raw METAR is the natural
  common denominator — one decoder, several transports.**
- `observations` stores **decoded** fields only. When the parser improves, historical rows cannot
  benefit. A `rawMetar` TEXT column would make re-decoding the past possible.

---

## 2. The finding that reframes most of this

**Corrected 2026-08-23** after a follow-up probe. The first version of this section claimed
`api.weather.gov` discards the METAR tenths. **It does not** — NWS decodes the remarks T-group and
serves it. The real issue is a *mix* problem, not a parsing problem.

### 2a. What the observations feed actually contains

`/stations/KSJC/observations`, 2026-08-23, consecutive rows:

```
timestamp   tempC   metar?  raw-tail
22:25:00       31     .
22:20:00       32     .
21:55:00       31     .
21:53:00     30.6     Y      RMK AO2 SLP118 T03060111   <- precise, decoded by NWS
21:50:00       31     .
20:53:00     29.4     Y      RMK AO2 SLP123 T02940111   <- precise, decoded by NWS
20:50:00       29     .
```

- **1 row in ~12** is the hourly METAR, carrying 0.1 °C.
- **~11 rows in 12** are the interleaved 5-minute ASOS samples, whole °C only.

This is the temperature-side face of `nws_observations_two_feeds_metar_vs_5min`, already documented
from the cloud side. The `observations.isMetar` column added for cloud is exactly the discriminator
temperature needs.

### 2b. Why the mix costs accuracy

The IDW blend **averages across stations**, so independent rounding errors shrink by sqrt(n). But
`computedHighTemp` / `computedLowTemp` are a **max/min over time** — a selector, not an average. A
max picks a single sample and inherits that sample's full quantization error (whole °C = 1.8 °F
buckets, worst case ±0.9 °F). At 11:1 odds the sample it picks is a whole-degree 5-minute row, even
when a 0.1 °C reading exists for that same hour.

Secondary effect: whole-degree series produce **ties and plateaus** at the extreme, which the
turning-point and label machinery (`per_day_actual_extrema_labels`,
`forecast_midpoint_plateau_duplicate`, `daily_low_lone_station_sticky_ratchet`) then has to
disambiguate. Tenths break those ties naturally.

### 2c. Station availability — measured, and unfavourable here

T-group presence over 12 h, via `aviationweather.gov`:

| station | reports | with T-group |
|---|---:|---:|
| KSJC | 12 | 12 |
| KSFO | 12 | 12 |
| KHWD | 14 | 13 |
| **KNUQ** | **36** | **0** |
| **KPAO** | **9** | **0** |

KNUQ reports `AUTO … RMK AO2` with no T-group; KPAO emits no `RMK` section at all. **These are the
two stations nearest the reference location**, and IDW weights by distance — so the benefit at this
location is materially smaller than a 3-of-5 station hit rate suggests. Measure before building.

### 2d. Loose thread

`SynopticApi.kt:78` parses `air_temp_set_1` via `doubleOrNull`, so that path already supports
decimals. If MesoWest serves KNUQ at 0.1 °C, Synoptic rows may be **more precise than the NWS rows
for the same station**, and the prefer-newest policy currently chooses between them blind to
precision. Worth a measurement.

---

## 2.5. Phase 0 results — measured 2026-08-23, and they kill A1 + A2

Both Phase 0 measurements were run. **Both came back negative, and a third measurement shows the
whole precision line is aimed at the wrong term.**

### (i) Do the near stations emit the 6-hour extreme groups? — **No**

24 h of reports via `aviationweather.gov`, remark groups counted inside the `RMK` section only:

| group | KHWD | **KNUQ** | **KPAO** | KSFO | KSJC |
|---|---:|---:|---:|---:|---:|
| reports | 26 | **72** | **11** | 24 | 24 |
| …with any `RMK` | 26 | **72** | **0** | 24 | 24 |
| `T` 0.1 °C temp | 25 | **0** | **0** | 24 | 24 |
| `1xxxx` 6 h MAX | 4 | **0** | **0** | 4 | 4 |
| `2xxxx` 6 h MIN | 4 | **0** | **0** | 4 | 4 |
| `4………` 24 h MAX/MIN | 1 | **0** | **0** | 1 | 1 |
| `SLPxxx` | 24 | **0** | **0** | 24 | 24 |
| `5xxxx` pressure tendency | 8 | **0** | **0** | 8 | 8 |

KNUQ emits `RMK AO2` and nothing further, on all 72 reports. KPAO emits no `RMK` section at all.
The stations that do carry the groups carry them completely — KSJC at the synoptic hours:

```
00:00Z  RMK AO2 SLP151 T02560133 10261 20200 58016
06:00Z  RMK AO2 SLP153 T01890139 10256 20183 50003
12:00Z  RMK AO2 SLP138 T01670139 10189 20156 56005
18:00Z  RMK AO2 SLP141 T02280139 10228 20156 58004
```

**A2 inherits A1's station problem exactly.** The two nearest stations — the ones IDW weights most —
contribute nothing.

### (ii) Does Synoptic serve the near stations at 0.1 °C? — **No**

Existing `observations` rows in `pixel_weather.db` / `samsung_weather.db`, converted back to °C and
tested for a sub-degree fractional part (the `web` column is `isWebFallback`):

| station | web | rows | sub-°C | % |
|---|---:|---:|---:|---:|
| KNUQ | 0 | 166 | 0 | **0.0** |
| KNUQ | 1 | 510 | 0 | **0.0** |
| KPAO | 0 | 77 | 0 | **0.0** |
| KPAO | 1 | 57 | 0 | **0.0** |
| KSJC | 0 | 1508 | 76 | 5.0 |
| AW020 (PWS) | 0 | 650 | 573 | 88.2 |
| LOAC1 (PWS) | 0 | 213 | 192 | 90.1 |

**§2d is refuted.** Synoptic redistributes the same whole-degree METAR — no second path to
precision. KSJC's 5 % matches the ~1-in-12 METAR share of its rows.

**Unexpected inversion:** the personal stations have the *finest* resolution here. AW020 and LOAC1
report whole **°F** (≈0.56 °C steps), better than the whole-°C official stations — and they are
precisely the ones `DEFAULT_PERSONAL_STATION_DISCOUNT` downweights. That discount is still right
(PWS siting and calibration error dwarfs 0.4 °F of quantization), but it is worth naming.

### (iii) The measurement that settles it — quantization is not the dominant error

Cross-station spread, `pixel_weather.db`, 30-minute buckets with ≥3 reporting stations (n = 481):

| | °F |
|---|---:|
| mean spread | **4.24** |
| max spread | 11.59 |

| spread band | buckets |
|---|---:|
| < 1 °F | 57 |
| 1–2 °F | 88 |
| 2–4 °F | 105 |
| 4–7 °F | 130 |
| > 7 °F | 101 |

**Whole-°C quantization is ±0.9 °F worst case. Real station disagreement averages 4.24 °F, and
exceeds 4 °F in 48 % of buckets.** The precision line was optimizing a term roughly five times
smaller than the dominant one.

### Verdict

**A1 and A2 are killed.** Not merely deprioritized — the near stations cannot supply the data, the
fallback path offers no alternative, and the error being chased is well below the noise floor of the
actual problem.

**What survives, and why it is unaffected:** none of Cluster B's value was ever about precision. If
anything (iii) *strengthens* it — a mean 4.24 °F spread across stations a few km apart is partly
**elevation**, which the IDW ignores today and which `aviationweather.gov` supplies for free
(§4). Lapse-rate correction attacks the 4.24 °F term, not the 0.9 °F one.

### Side finding — KPAO goes dark overnight

KPAO reported 11 times in 24 h: `04:00Z`, then nothing until `14:00Z`, then hourly to `23:00Z` —
i.e. roughly 07:00–21:00 local, **silent through the entire overnight low window**. A station that
vanishes exactly when the daily low forms is the case `extrapolateForward` and
`DAILY_BLEND_CONTEXT_MS` exist to paper over. Worth its own look, independent of METAR; see
`daily_low_lone_station_sticky_ratchet`.

## 3. Cluster A — mine the METARs already being fetched (zero new network)

| # | Idea | Notes |
|---|---|---|
| **A1** | **Recover tenths at the daily extreme** | Section 2. *Demoted* — the precise value is already in the DB; the extreme-picker rarely lands on it. |
| **A2** | **6-hour / 24-hour extreme groups** | `1sTTTT`/`2sTTTT` at 00/06/12/18Z, `4sTTTTsTTTT` at 00Z. |
| **A3** | **Measured precip groups** | Hourly `Pxxxx`, 6-hour `6xxxx`, 24-hour `7xxxx`, in hundredths of an inch. |
| **A4** | **Present-weather codes** | `-RA`, `+TSRA`, `FG`, `BR`, `HZ`, `SN`. |
| **A5** | **Station QC metadata** | `AO1` vs `AO2`, and the `$` maintenance flag. |
| **A6** | **`METAR` vs `SPECI`, and `COR`** | Report-type discrimination. |

**A1 detail.** Not "parse the T-group" — NWS already does. The problem is that the extreme is taken
over a series that is ~92 % whole-degree samples. Four options:

| | Approach | Cost | Trade-off |
|---|---|---|---|
| a | Prefer the `isMetar` row's temperature when a METAR and 5-min rows share a bucket | Very low — mirrors what `MetarCloudBlender` already does for cloud | Drops to 1 sample/hr; a sharp peak between `:53` marks is missed |
| b | Use the METAR as a sub-degree calibration offset for surrounding 5-min rows | Medium, speculative | Keeps 12/hr resolution; needs validation that the offset is stable |
| c | Leave the series alone; take the extreme from the 6-hour remark groups (**A2**) | Medium | Cleanest — authoritative extreme, blend still owns curve shape |
| d | Accept no gain at KNUQ/KPAO-class stations | — | No T-group exists there; unrecoverable |

**A2 detail.** The 6/24-hour groups are the *official* max/min — precisely the quantity the whole
IDW + `extrapolateForward` + `DAILY_BLEND_CONTEXT_MS` machinery exists to reconstruct. They would
give a third, independent column to grade the blend against. This also cuts at the
`nws_api_actual_is_the_forecast` problem: gridpoint max/min filed as observations means NWS grades
itself against itself, whereas a METAR remark extreme is a genuine actual.

**A3 detail.** Upgrades the `nws_rain_actuals_hybrid` path from inferred to measured.

**A4 detail.** Better than `textDescription`, and available on Synoptic rows that currently yield
nothing but sky. Opens a capability the app does not have at all: **precip forecast accuracy** — a
binary "did it actually rain this hour" truth series to set against the forecast chance %.

**A5 detail.** `AO1` means no precipitation discriminator, so that station's precip is unreliable.
Feeds the existing `qcFailed` column and the IDW personal-station discount — the weighting
machinery already exists; this is more signal for it.

**A6 detail.** `isMetar` currently means only "has a rawMessage". A SPECI is issued *because
something changed*, which is a different signal from a routine :53 report and is relevant to
`CloudHourBucket`'s nearest-to-the-hour resolution. A `COR` report should supersede the earlier
report at the same timestamp.

---

## 4. Cluster B — aviationweather.gov as a third transport

Probed 2026-08-23: no API key, HTTP 200, multi-station, with history.

```
GET https://aviationweather.gov/api/data/metar?ids=KNUQ,KPAO,KSJC&format=json&hours=24
```

```json
{"icaoId":"KSJC","obsTime":1787503980,"reportTime":"2026-08-23T17:00:00.000Z",
 "temp":20,"dewp":14.4,"wdir":0,"wspd":0,"visib":"10+","altim":1014.6,"slp":1014.4,
 "qcField":4,"metarType":"METAR",
 "clouds":[{"cover":"SCT","base":8000},{"cover":"BKN","base":10000}],
 "lat":37.3594,"lon":-121.9244,"elev":13,"name":"San Jose Intl Arpt, CA, US",
 "rawOb":"METAR KSJC 231653Z 00000KT 10SM SCT080 BKN100 20/14 A2996 RMK AO2 SLP144 T02000144"}
```

Note `dewp: 14.4` — **it already decodes the T-group.**

What it offers that the current pipeline cannot:

- **All 5 IDW stations in one request, with history.** Today that is N per-station pulls. Fewer
  wakeups; directly relevant to the battery-aware fetch tiers.
- **`reportTime` is pre-rounded to the hour** — the API does what `CloudHourBucket` does by hand.
- **`elev` in the payload.** The IDW weights horizontal distance only. A station 300 m higher runs
  roughly 2 °C cooler; lapse-rate correction is a real accuracy win and this supplies the input for
  free. (See `idw_weight_window_dependent_distance`.)
- **Independent of `api.weather.gov` uptime.** `ObservationFallbackPolicy` already models
  fetch-both / prefer-newest; this slots in as a third leg.
- **Global coverage.** Verified against EGLL, LFPG, RJTT — all returned with `temp`, `elev`, and
  `rawOb`. Today every non-US user has only forecast-model sources with
  `supportsTemperatureActuals = false`. METAR would be **the app's only source of true station
  actuals outside the US** — arguably the single biggest capability unlock in this document.

**B-bonus — TAFs** from the same endpoint (`&taf=true`): a point forecast for the exact airport with
explicit `TEMPO` / `BECMG` / `FM` change groups, structurally unlike every gridded source in the app.
Interesting as an accuracy-comparison entrant, but a large parse. Parked.

**Unverified, worth a probe:** a bounding-box query form (`&bbox=…`) for station discovery, which
would replace some of the existing station-list caching.

---

## 5. Cluster C — new display surfaces (data present, never shown)

Wind and gusts (`27015G25KT`, `PK WND` in remarks) · pressure and 3-hour tendency (`A2992`,
`SLP176`, `5appp`) → the classic rising/falling arrow · dewpoint and heat index · visibility and fog
· snow depth (`4/xxx`).

Lower priority than A and B — but the only cluster that puts something *visibly new* on the widget.

---

## 6. Decisions to settle before any code

1. **Store `rawMetar` as a TEXT column and decode on read?**
   Cheaper migration than eight new typed columns; makes rows self-describing; allows re-decoding
   history when the parser improves; and would let the Observations screen show the actual report
   for debugging. Costs storage and per-read CPU — and the `PanelIpcServer` lesson says decode work
   must never sit on a hot path, so it would need the same caching treatment.

2. **Is METAR a `WeatherSource` or a transport?**
   Leaning **transport**. That enum drives display / primary selection; METAR is an observation
   feed. Synoptic already sets the precedent — stored as `api = "NWS"` with `isWebFallback = true`.
   A sibling discriminator fits, and keeps `no_cross_source_fallback` intact.

3. **Where the decoder lives.**
   `shared/…/observations/`, growing `MetarRawSkyParser` into a full `MetarDecoder`, per the
   Android/desktop sharing rule. It is pure-function, so it tests without a mocking framework —
   matching the established testing strategy.

4. **Cadence is a downgrade, not an upgrade.**
   METARs land hourly (KNUQ actually reports :15/:35/:55); the 5-minute ASOS rows already ingested
   are more frequent. The existing finding — *":00 row always beats the :53 METAR"* — stands.
   METAR's value is **precision, extremes, remarks, and independence**, never freshness. **Any
   design that swaps ASOS *for* METAR is a regression.**

5. **Coverage.** Airports only. Rural users gain nothing new; the 5-station fallback already covers
   that case.

6. **Schema changes need a DB version bump** and attention to the Room schema-export rename ordering
   (`feedback_room_schema_export_rename_order`).

---

## 7. Suggested starting point — revised after Phase 0

**Phase 0 is complete (§2.5) and returned negative on the precision line.**

1. **Do not build A1 or A2.** Killed by measurement, not by judgement. §2.5 records why so the idea
   is not re-formed later.
2. **Cluster B is now the head of the queue** — and specifically **elevation-aware IDW**, because
   §2.5(iii) shows the term worth attacking is the 4.24 °F cross-station spread, not quantization.
   `aviationweather.gov` supplies `elev` in the same payload that already replaces N per-station
   pulls with one call.
3. **International actuals** (§4) remains the largest single capability unlock and is untouched by
   these findings.
4. **A4** (present weather → binary precip truth for forecast accuracy) survives — it is a new
   capability, not a precision play.
5. **A3 is unmeasured, not disproven.** No `Pxxxx` / `6xxxx` / `7xxxx` groups appeared in the 24 h
   sample because it did not rain. Re-run the §2.5(i) scan during a wet spell before judging it.
6. **Unrelated but surfaced:** the KPAO overnight gap (§2.5 side finding).

## Related

- `arch/daily-history-extremes.md` — the two independent actuals pipelines A2 would join
- `plans/260820-nws-metar-cloud-cover-idw-blend.md` — the existing cloud-only METAR work
- `plans/260821-synoptic-cloud-parse-and-fallback-reason-mislabel.md` — how the Synoptic raw-report
  path came to be parsed at all
