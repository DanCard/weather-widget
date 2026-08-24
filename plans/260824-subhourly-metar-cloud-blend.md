# Sub-Hourly Measured Cloud-Actual Curve (binless, native-timestamp blend)

Date: 2026-08-24
Status: Approved (binless variant); in progress

## Goal

Draw the **measured** cloud curve at native report cadence (~15–20 min at KNUQ) on both platforms
(Android widget + desktop), using METAR rows the app already fetches and stores — no new API, no
new fetch work, no schema change, and **no time bins**: points land on real report timestamps,
mirroring how `ActualTemperatureSeriesBuilder` emits the temperature actual curve
("blend once per distinct observation timestamp; no time-bucket thinning").

## Root-cause analysis (evidence collected 2026-08-24)

1. **Storage already holds sub-hourly measured cloud.** Desktop `weather.db` (`observations`):
   KNUQ reports at `:15/:35/:55` (~20-min cadence), KPAO/KSQL ~hourly at `:47/:50`, KSJC hourly at
   `:53`. Today's marine-layer burn-off is stored at 20-min resolution: `cloudCoverLow` 100 through
   16:15Z -> 44 at 16:53 -> 0 at 17:35.
2. **The fetch already captures it.** `MetarObservationFetcher.fetchObservations` pulls
   `hours=2` of *all* METAR reports per station from aviationweather.gov each cycle; verified KNUQ
   returns 9 reports / 3h.
3. **The renderers already accept arbitrary native timestamps.** `CloudActualSeries.points/segments`
   is keyed on native timestamps (doc cites "15-minute Meteo rows"), and both consumers pass
   `retroActual.hours` straight through:
   - Android: `CloudCoverViewHandler` -> `CloudActualSeries.points(...)` -> `CloudCoverGraphRenderer`
     (explicit comment: "timestamps may land on quarter hours").
   - Desktop: `DesktopWeatherRepository.loadCached` -> `CloudCoverGraph` -> `CloudActualSeries.points`
     (explicit comment: "The actual is independent and may be 15-minute").
4. **The only hour-keyed stage is `MetarCloudBlender.blend`.** It buckets every report with
   `CloudHourBucket.startMsOf` (round-to-nearest hour), picks one reading per station per hour,
   and IDW-blends into `Result.hours: Map<Long, Int>` keyed at hour marks.

## Proposed change

### A. `MetarCloudBlender.blend` — per-timestamp anchored blend (the core change)

Replaces `groupBy { CloudHourBucket.startMsOf(ts) }` hour binning with the temperature builder's
binless pattern:

1. **Candidate points** = the distinct timestamps of `usable` rows (site- and source-scoped,
   QC-passed). ~5 points/hour at a mixed multi-station site instead of 1.
2. **Per candidate timestamp, per station**: anchor to the station's nearest cloud-carrying
   reading within **±ANCHOR_TOLERANCE_MS = 30 min** (same reach the hour bucket had; same max age
   the temperature blend tolerates). No time-interpolation of cloud octas.
   - **METAR preference kept**, now per-timestamp: carriers flagged `isMetar` win over the
     instantaneous ASOS 5-minute rows when both are within tolerance (measured 2026-08-21: the
     5-min rows flip CLR<->SCT as the ceilometer beam passes in/out of scattered cloud).
   - **Carrier fallback kept**: a partial report (no sky group) never anchors; the nearest
     *carrying* report does.
3. **IDW across stations** at that timestamp via the existing `SpatialInterpolator`
   (1/d², near-zero snap) — unchanged arithmetic.
4. Emitted map keys are **native report timestamps**. Gaps stay gaps: no candidate exists where
   nothing reported; `CloudActualSeries.segments` splits drawn segments at >2x cadence.
5. Scope: only the `HistoricalDataKind.STATION_OBSERVATION` branch (NWS direct + every source that
   borrows METAR/NWS actuals). `TOMORROW_IO` realtime and `<SOURCE>_MAIN` synthetic backfill rows
   are hourly model history and stay hour-keyed.
6. `Result.hours` / `Stats.blendWidthByHour` property names kept (minimal diff); docs updated to
   say keys are native timestamps now. Read-range pad (`CloudHourBucket.readStartMs/readEndMs`)
   unchanged — its ±30 min over-read is exactly the anchor tolerance the new blend needs at the
   window edges, and the existing `it in startMs until endMs` filter still bounds emitted points.

### B. `CloudSeriesBuilder.build` — tolerant per-hour actual lookup

`retroActual[hour.dateTime]` exact-key lookup silently misses once keys stop being hour marks
(e.g., KPAO at `:47`). The lookup feeds only Android's fallback path (when `actualSeries` is
empty — production always passes it) and the `frozenCoverage` diagnostic, but a silent miss
regresses both. Change to: value of the entry whose key is nearest the hour mark within ±30 min —
identical output to today for hourly inputs.

### C. Callers — no changes required

- Android `CloudCoverViewHandler` and desktop `DesktopWeatherRepository` pass the map through
  unchanged; both render paths adapt automatically.
- The `CLOUD_SERIES` diagnostic log count (`actual=`) grows ~2-5x; expected, keep as-is.

### Non-goals / explicitly unchanged

- No fetch/frequency changes; no DB migrations; no settings UI.
- Forecast curve stays hourly (Open-Meteo Previous Runs is hourly-only).
- Tomorrow.io/synthetic hourly paths unchanged. `CloudHourBucket` untouched (still used by
  `TomorrowIoActuals`, `ObservationResolver`, `HourlyObservationBackfill`).
- Android text mode (1-row widgets) unchanged. No smoothing added to the measured curve.

## Tests

All new logic is pure JVM in `:shared`:

1. **Update `MetarCloudBlenderTest`** to the native-timestamp semantics:
   - ":47 rounds into the following hour" -> emits at the report's own timestamp `:47`.
   - "each station contributes one value per hour" (+5/+50 min) -> now two points; rewrite as
     one anchor per station per candidate timestamp.
   - METAR-preferred-over-ASOS and carrier-fallback: re-expressed per-timestamp (both carriers
     within tolerance of one candidate; METAR wins).
   - Multi-station IDW arithmetic: keep, with anchors chosen per candidate.
2. **New tests**:
   - KNUQ-shaped 20-min cadence + hourly `:53`/`：47` stations yield ~5 points/hour at native
     timestamps with correct IDW values where anchors overlap.
   - Anchor tolerance edge: a station's reading 31 min from the candidate does not anchor; 29 min
     does. Window-edge candidate at `startMs` can anchor a reading at `startMs - 1 min` (pad).
   - `CloudSeriesBuilder`: native-keyed actual map resolves per-hour values within ±30 min;
     beyond tolerance stays null.
   - Deterministic total order preserved for same-timestamp ties.
3. Duration categories: keep `@Category` on every touched/new class per the per-module
   enforcement.
4. Run: `./gradlew :shared:testByDurationShared` (+ `:app:testByDurationDebugUnitTest` if any
   app-side tests touch the blend).

## Verification (on-device, per Evidence-First Protocol)

1. Desktop (fastest loop): `./gradlew :desktop:run`, open the cloud graph: confirm the measured
   curve stair-steps through today's burn-off at real report times (100 -> 44 -> 0) instead of
   hourly steps.
2. `sqlite3 ~/.local/share/weather-widget/weather.db` spot-check + `CLOUD_SERIES` log: larger
   `actual=` count, keys off the hour marks.
3. Android: `./gradlew installDebug` to emulator, widget CLOUD view, screenshot; confirm
   sub-hourly measured curve, forecast curve/NOW marker/gap splitting unregressed.
4. Logcat watch for `METAR_BLEND_DROPPED` / `CLOUD_COVER_GAPS` anomalies.

## Risk notes

- A candidate point may be dominated by one station's fresh reading plus older anchors — same
  property the temperature blend has; ±30-min anchor age keeps it honest.
- Observed cloud is octa-quantized (0/19/44/75/100-class); the denser curve shows step noise vs.
  the smoothed forecast curve. Accepted by design — no smoothing on measured data.
