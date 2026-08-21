# NWS cloud-cover actuals from METAR sky condition, IDW-blended over the 5 nearest stations

Status: **shipped**, 2026-08-21. Steps 1–6 landed plus four follow-ups found by on-device evidence:
(1) `METAR_BLEND_DROPPED` diagnostic in `MetarCloudBlender` — fires when cloud-carrying readings
enter the blend but zero hours leave it; (2) cloud-carrier preference — within a station's hour
bucket, if the report nearest the hour omitted sky condition (partial METAR, ~25-30% of reports),
the nearest report that DID carry one supplies the value instead of dropping the hour
(`Stats.shadowedBuckets` counts rescues); (3) `metar_cloud_sparse` branch in
`evaluateHourlyBackfillNeed` — temperature-only `coverage_ok` could never see a broken cloud series
(pre-feature rows carry `cloudCoverLow=NULL` forever), so the existing 72h repair now also fires
when under half of official-station buckets carry cloud, re-parsing the same payload with REPLACE;
and (4) the probe is called from `CloudCoverViewHandler` too — the temperature/daily views owned
it, so a widget parked in CLOUD view could never heal itself. Verified on both emulators and both
phones: `OBS_HOURLY_BACKFILL_REQ reason=metar_cloud_sparse cloudBuckets=2 officialBuckets=72` →
5-station 72h re-fetch → solid actual curve over past hours.
Follows: `260820-actual-cloud-cover-on-meteo-hourly-graph-opus.md`, commits `f9a05d26` / `de0c2b27`

## 1. What ships

Under NWS, the hourly cloud graph currently draws one curve: the gridpoint `skyCover` forecast.
Open-Meteo grew a second, solid *actual* curve last night. This gives NWS the same second curve,
derived from what the surface stations actually reported.

The value is an IDW blend, at the user's coordinates, of METAR sky condition from the **5 nearest
observation stations — the same `MAX_NWS_STATIONS` set the temperature blend already uses**.
Stations with no sky condition in the report are skipped and the blend proceeds with however many
remain, down to one. The candidate set is never extended past 5 to make up the shortfall.

**No new HTTP calls.** Every byte this needs is already in responses we fetch and parse today —
`cloudLayers` sits in the same `/stations/{id}/observations` payload that
`NwsCurrentObservationUpdater` and `NwsObservationBackfiller` read for temperature, across exactly
the same 5 stations.

**No schema migration.** `observations.cloudCover` / `cloudCoverLow` landed in Room v63 / desktop
v17 (`de0c2b27`). They are simply null on every NWS row today.

---

## 2. Verified starting point

Probed live against `api.weather.gov` on 2026-08-20 from 37.417,-122.089.

### 2.1 The station list is distance-ordered, and half of the near ones are useless for cloud

`gridpoints/MTR/93,87/stations`, first five — the exact set `take(MAX_NWS_STATIONS)` yields:

| idx | station | km | reports / 3d | with `cloudLayers` |
|---|---|---|---|---|
| 0 | AW020 (PWS) | 2.24 | 421 | **0** |
| 1 | KNUQ | 3.82 | 191 | 134 |
| 2 | KPAO | 6.04 | 40 | 30 |
| 3 | LOAC1 (PWS) | 8.34 | 71 | **0** |
| 4 | KSJC | 15.92 | 500 | 487 |

Personal stations return `cloudLayers: []` on **every** report — they have no ceilometer. The two
closest stations to the reference location are both PWS, so the "use fewer stations" path is the
normal case here, not an edge case. Measured blend width over 3 days: 1 station for 21 hours,
2 for 23, 3 for 20.

### 2.2 An empty layer list means "not reported", never "clear"

Across 400 reports at 5 official stations, 23–29% carried `cloudLayers: []`. Those rows correlate
almost perfectly with a blank `textDescription` (0 of 128 had text, except two KHAF `Fog/Mist`
rows) — they are partial reports that omit sky condition, not observations of a clear sky.

A genuinely clear sky is reported **explicitly**, as `amount: "CLR"`. So the mapping is
unambiguous: `[]` → `null`, `CLR` → 0.

### 2.3 `CLR` carries a base, and it is the sensor's blind spot, not a cloud

```json
KSJC  {"base": {"unitCode": "wmoUnit:m", "value": 3810}, "amount": "CLR"}
KNUQ  {"base": {"unitCode": "wmoUnit:m", "value": null},  "amount": "CLR"}
```

3810 m = 12,501 ft: the ASOS ceilometer limit. `CLR` means "nothing below 12,000 ft", and the base
field encodes the ceiling of detection. Percent must therefore key on `amount` alone; `base` only
ever decides layer membership. Bases are always `wmoUnit:m`.

### 2.4 METAR sky cover is blind to high cloud — the decisive finding

Highest base ever reported, 3 days: KNUQ 1070 m, KRHV 2440 m, KSJC 3810 m, KPAO 6100 m. Nothing
above ~12,000 ft, ever.

Blending METAR as a *total column* and comparing against Open-Meteo's total `cloud_cover`:

```
hours=64  MAE=21.1  within20=67%

   hour(UTC)   METAR blend   Open-Meteo total
   08-20 22:00      15              88
   08-20 23:00       0              99
   08-21 00:00      21              98
   08-21 02:00       0              79
```

This is the same cirrus afternoon `f9a05d26` documented from the other direction ("the total column
ran 83-99% all afternoon on high cloud while the low layer read 6-13% and every surface station
reported clear"). It is now confirmed from the station side: **the stations were right, and the
METAR product is structurally a below-12,000 ft measurement.**

Against Open-Meteo's `cloud_cover_low` — the layer the graph actually draws — the same blend lands
where it should:

```
hours=64  MAE=12.3-14.2  within20=77-83%
```

### 2.5 The hour-bucketing rule is load-bearing

METAR issue minutes vary by station: KNUQ reports near the top and at :15/:35/:55, **KPAO reports
at :47**. Assigning each report to the hour it *floors* into drops KPAO almost entirely:

| bucketing | KPAO hours | 3-station hours | MAE vs OM low |
|---|---|---|---|
| floor to hour | **1** | 1 | 12.3 |
| round to nearest hour | **29** | 20 | 14.2 |

Round-to-nearest is also the physically correct rule: a 13:47 METAR is an *instantaneous* reading
13 minutes from 14:00 and 47 from 13:00, and the graph plots instantaneous values at hour marks.
The MAE moves the wrong way by 2 points, but that is measured against a **model**, not ground
truth — a blend that agrees with HRRR is not thereby more true. Tripling real station participation
is the change worth having; do not tune the bucketing to minimise disagreement with Open-Meteo.

### 2.6 Two limits inherited from the temperature path

- **`/observations` caps at 500 features** regardless of the requested span. A 7-day request to
  KSJC (5-minute cadence) returns 1.6 days; KNUQ (sparser) returns the full 7. Cloud history is
  therefore *accumulated* by repeated fetches, not backfilled deep on first run. Pre-existing, and
  it already limits temperature the same way — do not try to fix it here.
- **`parseObservationProperties` returns null when temperature is null**, and null-temperature
  reports *do* carry sky condition (KNUQ 2026-08-20T23:35, `CLR` / "Clear", temp null). Those
  clouds are dropped. Leave that alone: `ObservationEntity.temperature` is non-null, and relaxing
  the parser to admit temperature-less rows would ripple into every temperature blend for the sake
  of a handful of hours.

---

## 3. Decision required: which curve does the NWS actual get compared against

§2.4 leaves a real, unavoidable mismatch, and it should be settled before implementation.

- The NWS **forecast** curve is gridpoint `skyCover` — total column, `wmoUnit:percent`, cirrus
  included. It is the only cloud product NWS publishes (`gridpoints` has exactly one sky key).
- The NWS **actual** from METAR is a below-12,000 ft measurement, whatever we label it.

So under NWS the two curves answer different questions, and on a cirrus afternoon they will sit
60–80 points apart for reasons that are instrumental, not forecast error. Open-Meteo does not have
this problem because it publishes `cloud_cover_low` for both curves.

**Recommendation — ship it, and file the value honestly as the low layer.** Write the blend to
`cloudCoverLow` and leave `cloudCover` **null** on NWS observation rows. Rationale:

1. It is what the number is. Calling a 12,000-ft-limited measurement a total column would be the
   actual lie, and §2.4 shows it reads 0-21% against a real 79-99% sky.
2. It matches the quantity the graph has already decided it wants. `f9a05d26` moved the graph to
   the low layer precisely because "the low layer is the one that answers *is it cloudy out*".
3. The gap is informative rather than misleading, provided it is documented: forecast high +
   actual low on a clear-feeling day = high cloud only.

The alternative — suppressing the NWS actual curve entirely because no comparable forecast exists —
is defensible but throws away a genuinely good measurement (MAE 12-14 vs the best available
reference) to protect a forecast curve that is drawing the wrong quantity for this graph anyway.

**If you would rather the two curves be strictly comparable, say so and the plan drops to: store
the value, gate the render off under NWS, and revisit.** Everything through step 4 is unchanged
either way.

---

## 4. Design decisions and invariants

1. **A missing value stays missing.** `[]` → null. Not 0. Not carried forward from a neighbouring
   hour. Not inferred from `textDescription`. This is the invariant `ObservationReading.cloudCover`
   already documents: "a zero here would be an observation of a clear sky nobody made."
2. **Percent comes from `amount`, never from `base`.** §2.3.
3. **METAR amounts are cumulative**, so total sky cover is the **maximum** amount across the
   report's layers, not a sum. `FEW010 SCT020 BKN040` is BKN overall.
4. **The blend is read-time, from per-station rows** — same as temperature. Each station's own
   percent is stored on its own `observations` row under its real station id. Nothing is written to
   a synthetic `NWS_*` station: that is precisely the mistake `synthetic_backfill_hijacks_blend`
   records, where a `distanceKm=0` synthetic row won the near-zero override and hijacked the blend.
5. **Read the layer the forecast draws, with one rule and no source branching.**
   `CloudSeriesBuilder.visibleCloudCover()` is `cloudCoverLow ?: cloudCover`; the actual read
   becomes the same expression. Open-Meteo rows have `cloudCoverLow` → low layer. NWS rows have it
   null under the §3 alternative, or populated under the recommendation. Either way one expression
   governs both curves and they cannot drift apart.
6. **Site and source isolation are unchanged.** The existing `selectNearestObservationSite` /
   `LocationMatch` collapse stays the single authority; no new raw coordinate query.
7. **Synoptic web-fallback rows carry no cloud.** `SynopticApi` requests `air_temp` only. When the
   fallback wins a station (stale NWS feed), that station contributes temperature but not cloud, and
   the blend narrows by one. Acceptable and self-correcting; do not add Synoptic cloud vars here.
8. **QC-failed and stale readings are excluded**, reusing the temperature blend's existing rules
   rather than inventing cloud-specific ones.

---

## 5. Implementation

### Step 1 — parse `cloudLayers` (`:shared`)

`shared/.../data/remote/NwsApi.kt`:

- Add `cloudLayers: List<CloudLayer>` to `Observation`, defaulting to empty, with
  `data class CloudLayer(val amount: String, val baseMeters: Double?)`.
- Parse it in `parseObservationProperties` alongside `precipitationLastHour`. Convert `base` to
  metres by `unitCode` using the existing `parseQuantitativePrecipitationMm` pattern (`wmoUnit:m`
  is all that has ever been observed; handle `ft` defensively and log anything else once).
- `getLatestObservation` (the thin variant) is left alone — `getLatestObservationDetailedResult`
  routes through `selectValidObservation` → `parseObservationProperties` and so gets it for free.

### Step 2 — the okta mapper (`:shared`, pure)

New `shared/.../shared/observations/MetarSkyCover.kt`:

```kotlin
object MetarSkyCover {
    // WMO okta midpoints. Measured 2026-08-20: swapping to okta lower bounds (12/38/63) or to a
    // linear n/8 scale (25/50/75) moves MAE against Open-Meteo's low layer by <1.5 points over 64
    // hours. The choice is not load-bearing; the midpoints are the standard, so they win.
    private val PERCENT = mapOf(
        "CLR" to 0, "SKC" to 0, "NCD" to 0, "CAVOK" to 0,
        "FEW" to 19, "SCT" to 44, "BKN" to 75, "OVC" to 100,
        "VV" to 100,   // sky obscured — vertical visibility only
    )
    const val LOW_LAYER_CEILING_M = 2_000.0

    fun totalPercent(layers: List<NwsApi.CloudLayer>): Int?
    fun lowPercent(layers: List<NwsApi.CloudLayer>): Int?
}
```

- Empty list → null (§4.1). An unrecognised `amount` → null for the whole report, plus a one-shot
  `WARN` naming the code, so a new abbreviation surfaces instead of silently reading as clear.
- `totalPercent` = max over all layers. `lowPercent` = max over layers with
  `baseMeters < LOW_LAYER_CEILING_M`, and a clear-sky code contributes 0 to **both** (a `CLR` at
  base 3810 m must not leave the low layer "unknown" — §2.3).
- 2000 m vs 3000 m moved MAE by 0.2 points; 2000 m is the closer match to the ≈6,500 ft
  low-cloud convention and is what the constant should say.

### Step 3 — the hour blend (`:shared`, pure)

New `shared/.../shared/actuals/MetarCloudBlender.kt`. Deliberately **not** an addition to
`ActualTemperatureSeriesBuilder`: that machine carries forecast-driven carry-forward, per-day
dominance and the personal-station discount, none of which apply to a quantity no station can
extrapolate.

```kotlin
fun blend(
    readings: List<ObservationReading>,   // already source- and site-filtered
    userLat: Double, userLon: Double,
    startMs: Long, endMs: Long,
): Map<Long, Int>
```

- Bucket each reading to `round(timestamp / 1h)` (§2.5), dropping QC-failed rows.
- Within a bucket, each station contributes **one** value: the reading nearest the top of the hour.
- IDW across the contributing stations via `SpatialInterpolator.interpolateIDWValues`, which already
  implements the `1/d²` weighting and the `NEAR_ZERO_KM` snap. One station → that station's value.
- Emit nothing for an hour with no contributor. No interpolation across empty hours: a gap in the
  actual curve is honest, and both renderers already split the path at gaps.
- Return hour-start epoch ms → percent, the shape `CloudSeriesBuilder.build(retroActual = …)` takes.

### Step 4 — carry the value onto the stored rows

- `NwsObservationSource.toEntity` (Android): set `cloudCover` / `cloudCoverLow` from
  `MetarSkyCover`. This one function feeds `fetchLatest`, `fetchHistorical` and
  `fetchApiObservationsOnly`, so the current-observation path and the 7-day backfill both gain
  cloud from a single edit.
- `DesktopWeatherService.toReading` (desktop): the same two fields.
- Web-fallback rows keep both null (§4.7).
- Per §3, the recommended write is `cloudCoverLow = lowPercent(...)`, `cloudCover = null`, with the
  comment explaining that populating `cloudCover` from METAR would file a 12,000-ft-limited
  measurement as a total column.

### Step 5 — widen the read gate

- `ObservationDao.getCloudActuals` (Android) and `DesktopWeatherDao.getCloudActuals` currently pin
  `stationId == HistoricalActualsBackfill.syntheticStationId(sourceId)`. That pin was deliberate —
  its comment says "a future real-station cloud source cannot silently join this series without a
  deliberate change." This is that deliberate change. Replace the pin with a source-aware branch:
  synthetic-station lookup for sources whose cloud arrives via `HistoricalActualsBackfill`, and
  `MetarCloudBlender.blend` over the real-station rows for NWS. Keep the site collapse.
- Read `cloudCoverLow ?: cloudCover` rather than `cloudCoverLow` alone (§4.5).
- `CloudCoverViewHandler.cloudSeriesAvailable` drops
  `effectiveDisplaySource == WeatherSource.OPEN_METEO` in favour of a capability check that admits
  NWS. `priorCloud` stays Open-Meteo-only — NWS has no previous-runs product, so
  `CloudSeriesBuilder` falls back to the live value with `isFrozen = false`, which is already the
  handled path.
- **Neither renderer changes.** `CloudCoverGraphRenderer` and desktop `CloudCoverGraph` are
  source-agnostic: they draw whatever `actualCloudCover` / `actualCover` arrives, and both already
  require ≥2 points and split paths at gaps.

### Step 6 — diagnostics

Extend the two permanent lines rather than adding a third. `CLOUD_SERIES` (Android) and
`BACKFILL_CLOUD` (desktop) exist because this feature has now failed silently twice, each time
looking identical on screen. Add the fields that separate the new failure modes:
`stationsWithLayers`, `stationsSkipped`, and the per-hour blend width. "Every station is a PWS"
(§2.1) and "the write dropped it" must not look alike in a log.

---

## 6. Testing

`:shared` unit tests, each `@Category(ShortDuration::class)`:

- `MetarSkyCoverTest` — the whole amount table; `[]` → null; `CLR` at base 3810 m → 0 for both
  total and low; cumulative maximum (`FEW010 SCT020 BKN040` → BKN); an unknown amount → null;
  a layer above the ceiling excluded from low but counted in total.
- `MetarCloudBlenderTest` — 5 stations of which 2 report → blend of 2, never a reach to a 6th;
  1 station → its own value; 0 → hour absent; the KPAO `:47` case landing on the following hour;
  a QC-failed row excluded; determinism under shuffled input order (the trap
  `ActualsRowOrderDeterminismTest` exists for).
- A fixture-driven `NwsApi` parse test off a captured KSJC/KNUQ payload, covering the
  `base.value: null` CLR row and an empty-layers partial report.

Integration (2+ classes, per `feedback_integration_test_definition`):

- `NwsObservationSource.toEntity` → `ObservationDao` → `getCloudActuals` round-trip, asserting a
  PWS row contributes nothing and the blend width matches the stations that reported.

Manual, both platforms: switch the source to NWS on the cloud graph and confirm a solid actual curve
appears over past hours with gaps where no station reported, then pull `app_logs` for `CLOUD_SERIES`
and check `stationsWithLayers` against the station list.

---

## 7. Out of scope

- **Synoptic cloud variables.** Would close the web-fallback gap (§4.7) but is a second provider's
  cloud product with its own QC semantics.
- **A frozen day-ago NWS forecast curve.** NWS publishes no previous-runs product.
- **Backfilling deeper than the 500-feature cap** (§2.6).
- **Reconciling the total-column forecast with the below-12,000 ft actual** (§3). There is no NWS
  low-cloud product to reconcile it against; this is a property of the upstream data.
