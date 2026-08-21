# 15-minute sub-hourly cloud actuals (Open-Meteo)

Status: **step 1 shipped; steps 2-3 STOPPED on negative validation**, 2026-08-20
Follows: `260820-actual-cloud-cover-on-meteo-hourly-graph-opus.md` (commit `412985dd`)

## 1. Why

The hourly cloud value is an **instantaneous top-of-hour sample, not an hourly mean**, and it can
hide an entire hour of weather. Measured at 37.417/-122.089 on 2026-08-20:

| hour | stored hourly value | actual 15-min quarters |
|---|---|---|
| 13:00 | 64 | 64, 54, 39, **24** |
| 19:00 | 10 | 10, 27, 51, **74** |
| 10:00 | 99 | 99, 89, 74, **60** |

19:00 is the case that matters. The stored actual says 10% — clear — for an hour in which low cloud
climbed to 74% as the evening marine layer pushed in. The surface stations independently caught the
same arrival (KNUQ clear at 19:55 → 13% @ 900ft at 20:15 → 38% at 20:35). The truth curve currently
draws a straight line through that entire event.

This is not a smoothing artifact we introduced; it is what the hourly endpoint returns. Verified:
the 15-minute series' `:00` values equal the hourly series exactly at every hour, so hourly is a
**subsample** of the sub-hourly series rather than an average of it.

## 2. What the API actually offers

Probed 2026-08-20 against `api.open-meteo.com/v1/forecast`.

- `minutely_15` accepts `cloud_cover`, `cloud_cover_low`, `cloud_cover_mid`, `cloud_cover_high`,
  plus `temperature_2m`, `precipitation`, `precipitation_probability`, `weather_code`,
  `shortwave_radiation`, `visibility`. Same endpoint we already call — no new host.
- Over CONUS it is HRRR-backed: `models=gfs_hrrr` returns values byte-identical to `best_match`.
- **It is genuinely sub-hourly, not resampled.** At 12:00 the quarters run 54, 58, 62, **66** while
  the 13:00 hourly value is 64 — the sub-hourly series overshoots the hourly endpoint, which
  interpolation cannot do. At 14:00 the quarters dip (13, 9, 9, 10) across a monotone hourly pair.
- History: fully populated to **60 days back**; at `past_days=92`, 6,412 of 8,928 steps are non-null,
  so it degrades around day 67 rather than cutting off.
- Past values **are** retro-corrected: 2026-08-20 20:00 reads 86 in the 15-min series, which is the
  corrected value (it was 6 before the correction landed at ~21:38).

### 2.1 The blocking constraint

**The Previous Runs API does not serve sub-hourly.** `minutely_15=cloud_cover_low_previous_day1`
is accepted and returns 192 correctly-timestamped steps that are **all null**.

So the frozen day-ago forecast is hourly-only. The two curves will have different native
resolutions, permanently. This is a property of the upstream product, not something to engineer
around, and the render must not paper over it.

## 3. Scope

**In:** the cloud graph's *actual* curve at 15-minute resolution, both platforms.

**Out, deliberately:**
- The frozen forecast curve — §2.1 makes it impossible.
- The live/future forecast curve. `minutely_15` would serve it, but a forecast curve that is
  15-minute ahead of now and hourly behind it is worse than one that is hourly throughout.
- Temperature and precip at 15 minutes. Both become nearly free once §4 lands (the same fetch
  already carries them, and `observations` already has the columns) — but neither should ride this
  change.

## 4. Storage: `observations`, and retiring the parallel path

Cloud actuals belong in `observations`, not a new table. It is PK'd on `(stationId, timestamp)` —
timestamp-keyed and event-sampled, so 15-minute rows fit with no schema shape change — and
`HistoricalActualsBackfill` **already files Open-Meteo's past hours there** under the synthetic
station `OPEN_METEO_MAIN`, carrying temperature, condition and precipitation.

Cloud is the one field that path was missing. That absence is the entire reason the previous plan
invented `OPEN_METEO_RETRO` rows in `hourly_forecast_history`: a second mechanism for a job the
first already did, built because the column did not exist rather than because the pathway did not.

**Changes:**

1. `observations` gains `cloudCover` and `cloudCoverLow` (both nullable INTEGER).
   Room 62→63, desktop 16→17.
2. `ObservationReading` gains the same two fields.
3. `HistoricalActualsBackfill.build` carries them through, at 15-minute steps.
4. **Retire `RetroCloudActual.SOURCE_ID`.** The read path (`getRetroCloudActuals` on both platforms)
   moves to an observations query scoped to `api = OPEN_METEO` and the synthetic station id.
   `SETTLE_MS` and `hasSettled` survive unchanged — they are about *when a value is trustworthy*,
   which is orthogonal to where it is stored. Drop the `OPEN_METEO_RETRO` rows in the migration.

`PriorDayCloudForecast` stays exactly as it is. It is a *forecast* series, not an observation, and
`hourly_forecast_history` ("what each hour was predicted to be") is the correct home for it.

### 4.1 The hazard this inherits

`HistoricalActualsBackfill` stamps `distanceKm = 0f` on every synthetic `_MAIN` row. That has
already caused one incident: the near-zero-distance override let a synthetic row beat every real
station in the IDW temperature blend (`synthetic_backfill_hijacks_blend` — desktop-only, 3h cliff).

Today the cloud version is **latent**: nothing blends cloud, so a distance-0 cloud row competes with
nothing. It goes live the moment NWS METAR cloud actuals land (§6), at which point Open-Meteo's
model output would outrank every real ceilometer in a cloud blend — a model silently overriding
observations, which is the exact inversion the blend exists to prevent.

Decide this now, not after: either exclude synthetic `_MAIN` rows from any cloud blend, or give them
a distance that reflects what they are. This must be settled in the same change that adds the
columns, or the trap is armed and unlabelled.

### 4.2 Retention

Desktop cleans `observations` at `DB_RETENTION_DAYS` = 547. At 15-minute cadence that is ~52,500
rows per site per source, against a table already on the hot path. The widest zoom is
`MAX_BACK_HOURS` = 6 days, so sub-hourly resolution older than about a week is never drawn.

Proposal: thin sub-hourly rows to hourly beyond 7 days rather than deleting them, so long-range
history keeps the same row count it has today. Needs its own measurement before committing.

## 5. Fetch

Add `minutely_15=cloud_cover,cloud_cover_low` to the existing `getForecast` call — same request, one
more parameter, no extra round trip.

`SETTLE_MS` (2h) applies unchanged in principle, but **whether 15-minute values settle on the same
schedule as hourly ones is unmeasured.** The constant came from a single observed hourly correction
(~38 min, 2h chosen for headroom). Instrument first, tune second; do not assume it transfers.

## 6. What this unlocks (not in scope, but the reason the shape matters)

Putting cloud in `observations` is what makes the NWS METAR cloud actual tractable later. METAR sky
cover is coarse — a single station yields 4 distinct values across a day (`CLR/FEW/SCT/BKN/OVC`
mapped to 0/13/38/69/100) — but the six-station IDW blend of that same data yields **22 distinct
values across 22 hours**, measured 2026-08-20. Continuous, and made only of real observations.

If both sources' cloud actuals live in `observations`, that blend is the existing
`SpatialInterpolator` path rather than new machinery, and the coarse-bucket problem dissolves
without borrowing model values to fill it in. (Which remains the wrong fix: it would grade NWS
forecasts against a partly-Open-Meteo actual, and unlike `nws_api_actual_is_the_forecast` it would
leave no `apiHighTemp == forecastHighTemp`-style tell.)

## 7. Render

**The actual curve is already an independent overlay** — `actualPoints` on Android, `actualCoords`
on desktop — drawn as unsmoothed straight segments after the main path. That is what makes this
tractable: it can carry 4× the vertices *without touching the hour-indexed pipeline*
(`HourlyFooterRenderer`, `ValueLabelEngine`, footer hour labels, extrema indexing) that
`hourly_label_pipeline_index_keyed` warns about.

Both platforms already have a continuous time→x mapping — desktop `geo.xAtTime`, Android's discrete
hour bucket plus minute offset, per `NowIndicatorGeometry`'s contract — so a point at 19:45 places
correctly with no new geometry.

Value labels stay on **hourly** anchors: the engine is index-keyed to the hour list, and 4× the
candidates would fight for space on a widget-sized plot.

## 8. Validation — RESULT: FAILED, do not render

Run 2026-08-20 over 2026-08-15..21, 2,208 paired samples: every METAR/SPECI report from KNUQ,
KPAO, KSQL and KSJC scored against both model series, each linearly interpolated to the observation
timestamp so the only variable under test is sampling density.

| window | n | MAE hourly | MAE 15-min | better/worse |
|---|---|---|---|---|
| all stations | 2208 | 21.9 | 22.0 | 288 / 284 |
| nearest two (KNUQ/KPAO) | 328 | 12.6 | 12.6 | 59 / 37 |
| KNUQ only (4 km) | 274 | 11.7 | 11.7 | 46 / 28 |
| hours with >=20pt change | 391 | 39.7 | **40.1** | 133 / **167** |
| hours with >=40pt change | 238 | 42.9 | **43.2** | 84 / **105** |

Sub-hourly is not better anywhere, and in the fast-moving hours — the only place it was supposed to
help — it is slightly **worse** and loses more paired comparisons than it wins.

### Why §1 was wrong

§1 compared the *stored hourly value* against the quarters and concluded the truth curve "draws a
straight line through that entire event". It does not. The curve is drawn **between** points, so an
hour whose endpoints move is already rendered as a climb. Checked against the motivating case:

```
time     15-min actual   hourly interpolated   diff
19:00               10                  10.0    0.0
19:15               26                  29.0    3.0
19:30               50                  48.0    2.0
19:45               73                  67.0    6.0
20:00               86                  86.0    0.0
20:15               87                  78.8    8.2
```

Maximum divergence 8.2 points, against a series whose own MAE versus observations is ~12. The
sub-hourly detail sits well inside the existing error bar: it is model texture, not recoverable
truth. The premise never survived contact with how the curve is actually drawn.

### Original plan (retained for the record)

Gate the render on measurement, as §8 of the previous plan did.

Re-run the METAR comparison at 15-minute resolution against KNUQ/KPAO/KSJC/KSQL/KSFO SPECI reports,
which are themselves sub-hourly and event-sampled. The 19:00–21:00 marine-layer arrival is the case
to score: the hourly series went from MAE 63.7 (total column) to 14.0 (low layer) over 14:00–20:00.
The question is whether 15-minute sampling improves on 14.0 or merely adds visual detail.

If it does not measurably improve agreement with observations, keep the hourly series and stop.
Finer is not automatically truer.

## 9. Tests

- Parse: 15-minute alignment, nulls omitted not zeroed, clamping, response-timezone handling
  (mirrors `PreviousRunsCloudParseTest`).
- `HistoricalActualsBackfill` — cloud carried at 15-minute steps; `_MAIN` rows still recognised by
  `ObservationSourceMatcher`; whatever §4.1 decides about `distanceKm`, asserted directly.
- `RetroCloudActualTest` — settling at 15-minute steps, including three quarters settled and the
  fourth not.
- `CloudSeriesBuilderTest` — an actual series denser than the forecast series; assert the forecast
  keeps its hourly vertices and the two are not silently zipped by index.
- **Regression test from the measured 19:00 hour**: hourly = 10, quarters = 10/27/51/74; assert the
  rendered actual reaches ≥70 within that hour. This is the defect, so it gets a test that fails on
  today's code.
- Migration test: `OPEN_METEO_RETRO` rows dropped, cloud columns present, no observation row lost.
- Robolectric geometry in dp only (`robolectric_no_font_engine`).

## 10. Risks

| Risk | Mitigation |
|---|---|
| Synthetic `distanceKm=0` hijacks a future cloud blend | §4.1 — settle in the same change, not later |
| Curves at different resolutions read as a bug | Forecast is hourly by upstream constraint; consider saying so in the render |
| `observations` row count at 15-min over 547 days | §4.2 thinning, measured before commit |
| `SETTLE_MS` wrong for sub-hourly | Measure; do not assume the hourly constant transfers |
| Sub-hourly detail is model texture, not truth | §8 gates on agreement with observations, not appearance |
| HRRR is CONUS-only | Outside CONUS degrade to the hourly series, never to zeros |
| Retiring `OPEN_METEO_RETRO` regresses today's fix | Migration test; verify the actual curve survives on all three devices before the render change |

## 11. Rollout

1. **DONE.** Columns + backfill, `OPEN_METEO_RETRO` retired, no render change. Verified: desktop
   v17 / Android v63, retro rows purged, cloud actuals confirmed identical across desktop and the
   Fold (`14:00=13, 15:00=11, 16:00=13, 17:00=8, 18:00=8, 19:00=10`, matching METAR).
2. **DONE — FAILED.** See §8.
3. **NOT DONE, and should not be.** §8 is the gate and it did not pass.

### What step 1 was worth on its own

Independent of the sub-hourly question, step 1 removed a duplicate mechanism: cloud actuals had been
filed as a synthetic series in `hourly_forecast_history` purely because `observations` lacked a
column, while `HistoricalActualsBackfill` already carried temperature, condition and precip to the
right table. That consolidation stands and should be kept.

### Residue to decide

`minutely_15=cloud_cover,cloud_cover_low` is still requested, parsed into `SubHourlyCloud`, and
carried on `RawFetch.subHourlyCloud` — consumed by nothing. With §8 failed there is no consumer
coming. Either revert it, or keep the parse and drop the request parameter so no bytes are spent.
