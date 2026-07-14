# The actual-temperature series: how it works, issues, alternatives

Written 2026-07-13 after the KPAO QC incident and a design discussion about "hourly buckets."
**Correction up front:** during that discussion the series was described as *hourly-bucketed*.
It is not. The series is **event-sampled** — one blended point per distinct observation
timestamp — and has been since time-bucket thinning was deliberately removed (see
`daily_vs_hourly_actual_extrema_mismatch`). The only "hourly" element left is the resolver's
hour-aligned *context window*. This note documents the real design;
where it contradicts that conversation, this note wins.

## How it actually works

Everything lives in `:shared` `ActualTemperatureSeriesBuilder.blendObservationSeries`, used by
all four renderers and both platforms.

**1. Candidate times = every distinct reading timestamp.** All stations' readings in the
context window (source-matched, `!qcFailed`) are pooled; each unique timestamp becomes one
emitted point. No thinning, no bucket grid. ~500 candidate times/day locally (KSJC ~5 min,
AW020 ~10 min, KNUQ ~35 min, LOAC1/KPAO hourly). Fetch cadence (10–60 min) controls only how
fresh the tail is — each fetch backfills batches of station-stamped readings, so density is
set by station cadence, not fetch cadence.

**2. Per-station value at each timestamp** (`resolveStationValueAt`) — each station
contributes exactly ONE value per candidate time:
- exact reading at that ts → `observed`
- between two of its readings ≤3h apart → linear interpolation of temp *and* distance
  (per-reading distance; using first-reading distance once desynced daily bar vs hourly
  graph) → `interpolated`
- after its last reading, ≤3h → **forecast-slope extrapolation**:
  `lastTemp + (forecast(ts) − forecast(lastReadingTs))` → `forecast_extrapolated`
- otherwise the station abstains.

**3. Decay-weighted IDW blend** (`blendCandidateTemperature`): weight =
`typeWeight × (1 − age/3h) / distanceKm²`, where age = ts − the anchoring reading's ts and
typeWeight applies the personal-station discount. Near stations dominate hard: on
2026-07-13 the shares were AW020 ~74% (2.2 km), KNUQ ~25% (3.8 km), KSJC ~1.4% (15.9 km).

**4. Lone-station dominance guard**: a timestamp covered by exactly one station takes that
station's raw value (single-candidate IDW is the identity), so it is *skipped* unless the
station is that local day's dominant (best-coverage) station. This is the fix for the
hills-PWS pre-dawn daily-low bug — the guard exists precisely because the series is
event-sampled.

**5. Consumers** — one canonical series feeds: the hourly graph's actual line, the fetch-dot
value, per-day extrema labels, `daily_history` extremes, and the current-temp anchor
(`ActualsAggregator.resolveCurrentObservation` → latest `observed`-condition point ≤ now,
falling back to `interpolated`). Window independence (blend once per reading ts) is what
guarantees the daily bar and hourly graph derive identical extrema.

**Distinct from `NWS_BLEND`**: the header/current-temp fetch path separately computes a
latest-reading-per-station IDW (`SpatialInterpolator.interpolateIDW`, 1h cohort spread,
same 3h decay) and stores it as the synthetic `NWS_BLEND` row. The graph's dot and the
stored blend can therefore legitimately differ by fractions of a degree — they are two
evaluations of similar-but-not-identical estimators at different effective times.

## Issues (observed, not hypothetical)

- **Leading-edge drift below the stations list.** The dot (latest series point) can sit
  below every station's *latest* reading (2026-07-13 23:20: dot 68.95 vs list 69.0/69.8/69.8).
  Two mechanisms: (a) abstaining/older stations are carried to the latest timestamp by
  forecast-slope extrapolation — on a cooling evening their carried values slide *down* the
  forecast slope below their last real reading; (b) the freshest fetch may contain newer,
  cooler readings than the list was showing when the user looked. Both are by design; both
  read as "the dot is wrong" to a user comparing against the list.
- **Blend vs list is inherently confusing.** The list shows unweighted latest readings; the
  dot is distance²-weighted (74/25/1.4 that night) plus temporal interpolation. A 16 km
  airport reading dominates the list visually and is nearly irrelevant to the blend.
- **Decay collapse in sparse stretches.** When only one station reports for >3h (others'
  decay → 0), points degenerate toward that station; the dominance guard bounds this to the
  day's best-coverage station but can't create data that isn't there.
- **Bad upstream data.** A QC-passing outlier still enters at full weight (the 2026-07-13
  KPAO 50°F reading failed Synoptic's spatial check and is now filtered via `qcFailed`;
  NWS's own per-field `qualityControl` codes remain unused — a known gap).
- **Anchor window alignment.** `resolveCurrentObservation` aligns its context window to the
  hour (minute ≥30 rounds up) so the delta doesn't jump between view modes — the one place
  "hourly" still appears; it bounds which points exist, not where they're sampled.

## Alternatives considered (2026-07-13 discussion)

- **True hourly bucketing** — the misremembered status quo. Would add intra-station temporal
  averaging (noise ÷ ~√N before extrema) at the cost of window-dependent extrema; that
  trade was already made the other way when thinning was removed to converge daily/hourly
  extrema. Not going back.
- **Plot raw readings as the line** (no cross-station blend): sawtooths across the ~2.5°F
  micro-climate spread at station handoffs; rejected.
- **Latest-readings-only anchor for the dot** (use `NWS_BLEND` as the final vertex at
  `currentObservedAt`): maximally fresh leading edge, but the dot then floats off its own
  line — the exact divergence class fought repeatedly. Only worth it if leading-edge drift
  keeps bothering in practice.
- **Raw-readings scatter overlay behind the blended line** (recommended, not yet built):
  faint per-station dots at true reading times (QC-failed ghosted/excluded). Renderer-only,
  no series/schema change, and directly answers "why does the line disagree with the list"
  by showing the inputs. NWS's own timeseries pages use this pattern.

## Pointers

- `shared/.../shared/actuals/ActualTemperatureSeriesBuilder.kt` — series builder (blend loop
  ~L244–356, per-station resolve ~L455, decay ~L539)
- `shared/.../shared/actuals/ActualsAggregator.kt` — current anchor + daily extremes
- `shared/.../shared/util/SpatialInterpolator.kt` — latest-readings blend (`NWS_BLEND`)
- Memory: `daily_vs_hourly_actual_extrema_mismatch`, `daily_low_lone_station_sticky_ratchet`,
  `idw_weight_window_dependent_distance`, `synoptic_qc_flags_pipeline`
