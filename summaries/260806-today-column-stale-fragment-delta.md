# Today-Column `fcst` Delta Read −13.7° From a 13-Day-Old Coordinate Fragment

**Date:** August 6, 2026
**Device:** Samsung Galaxy Z Fold (SM-F936U1), daily forecast view, today column, Meteo source
**Plan:** [plans/260806-today-column-stale-fragment-delta-opus.md](file:///home/dcar/projects/weather-widget/plans/260806-today-column-stale-fragment-delta-opus.md)
**Status:** Implemented, tested, and verified on-device

---

## 1. Problem

The today-column overlay rendered `−13.7 fcst` while the header rendered `+0.5 from yest`, and the
value **oscillated** between `−13.7` and `+0.5` across renders with no data change.

The overlay value is `appliedDelta = observedTemp − forecastAtObservationTime`. The observation was
correct (65.3 °F). The forecast it was differenced against was **79.0 °F**, interpolated from a curve
fetched **2026-07-24** — 13 days stale.

Captured live from the device:

```
19:41:30  isStaleHourlyData: source=OPEN_METEO scopedCount=16 latestFetchMs=1784948582262
          ageMs=1121908010 thresholdMs=7200000 stale=true          <- fetched 2026-07-24
19:41:30  CURR_TEMP_RESULT: display=64.42 estimate=78.12 obs=65.30 delta=-13.70 estAtObs=79.00
19:41:30  TODAY_OVERLAY: widget=345 delta=-13.7

19:39:50  isStaleHourlyData: source=OPEN_METEO scopedCount=32 latestFetchMs=1786069387738
          ageMs=1003003 stale=false                                 <- fetched that day 19:23
19:39:50  CURR_TEMP_RESULT: display=64.73 estimate=64.28 obs=65.30 delta=0.45  estAtObs=64.85
19:39:51  TODAY_OVERLAY: widget=345 delta=+0.5
```

Notably the resolver already knew the data was ancient (`stale=true`, 13 days against a 2-hour
threshold) and rendered the delta anyway.

---

## 2. Root Cause

Three faults compounding.

### 2.1 Eight coordinate fragments, one live

The phone had not moved for days, but GPS jitter had written **eight** distinct
`(locationLat, locationLon)` keys into `hourly_forecasts`. Each held a complete 16-row day for the
current date; only one was still being refreshed:

| site | last fetched | 19:00 | 20:00 |
|---|---|---|---|
| 37.419,-122.087 | **2026-07-24** | 81.3 | 76.7 |
| 37.481,-122.184 | 2026-07-27 | 71.2 | 63.8 |
| 37.42,-122.095  | 2026-07-29 | 79.9 | 75.7 |
| 37.377,-122.075 | 2026-07-30 | 80.6 | 76.8 |
| 37.422,-122.073 | 2026-08-03 | 80.3 | 75.6 |
| 37.422,-122.087 | 2026-08-04 | 81.4 | 76.6 |
| 37.424,-122.088 | 2026-08-04 | 81.5 | 76.7 |
| **37.417,-122.089** | **2026-08-06 19:23** | **66.6** | **63.1** |

Open-Meteo returns 14 days ahead, so a fetch on Jul-24 legitimately wrote rows for Aug-6. Those rows
then froze. `WRITE_QUANTIZE_DECIMALS = 3` (~111 m) assumes jitter lives in the 4th decimal; the real
jitter here spans 37.417→37.424 (~780 m) and −122.073→−122.095 (~1.9 km), so quantization never
collapsed it and every fetch at a new fix minted another fragment.

### 2.2 `sameSite` disagreed with itself on this exact boundary

`LocationMatch.sameSite` uses `SAME_SITE_TOLERANCE_DEG = 0.002` with `<=`. The two render loaders
passed **different centres**, and the stale fragment sat precisely on the boundary:

| caller | centre passed | Δlat to 37.419 | admitted? |
|---|---|---|---|
| `GraphDataLoader` | raw `37.41681671142578` | `0.0021832886` | **no** (> 0.002) |
| `HourlyForecastLoader` | quantized best-site `37.417` | `0.001999999999995339` | **yes** (≤ 0.002) |

A floating-point hair decided whether a 13-day-old forecast entered the render set.

### 2.3 The collapse that followed was `fetchedAt`-blind and last-wins

`HourlyForecastLoader.load` collapsed with:

```kotlin
.associateBy { Pair(it.dateTime, it.source) }   // last wins; fetchedAt ignored
```

The DAO orders `dateTime ASC`, and SQLite breaks ties using
`index_hourly_forecasts_locationLat_locationLon` — **ascending latitude**. Verified row order for the
19:00 hour:

```
37.417,-122.089  66.6   2026-08-06 19:23:07   <- fresh, arrives first
37.419,-122.087  81.3   2026-07-24 20:03:02   <- stale, arrives later -> WINS
```

So the stale fragment **deterministically** overwrote the fresh one in this path.

### 2.4 Result

Render set became the July-24 curve. Observation time 19:30 sits midway between the hours, so
`forecastAtObs = (81.3 + 76.7) / 2 = 79.00`, giving `65.3 − 79.00 = −13.70`. `GraphDataLoader`,
filtering against the raw centre, kept the fresh row and produced `+0.45`. The widget alternated
between the two depending on **which loader rendered last**.

---

## 3. What Changed

| File | Change |
|---|---|
| `shared/…/HourlyForecastStitcher.kt` | Added `stitchBySource` — multi-source entry point. Plain `stitch` collapses to one row *per hour*, so feeding it multi-source rows would silently drop every source but one. |
| `app/…/widget/HourlyForecastLoader.kt` | **The fix.** Replaced the bespoke nearest-site + `sameSite` + `associateBy` collapse with `HourlyForecastStitcher.stitchBySource` against the RAW centre. |
| `app/…/data/local/HourlyForecastEntity.kt` | `HourlyForecast.toEntity` promoted from a private copy in `GraphDataLoader` to a shared extension, so both loaders convert identically. |
| `app/…/handlers/GraphDataLoader.kt` | Dropped its private `toEntity` in favour of the shared one. |
| `app/…/architecture/HourlyProximityQueryAllowlistTest.kt` | Corrected the false `HourlyForecastLoader.kt` justification. |

The stitcher was **already correct** — `maxByOrNull { fetchedAt }` plus `sameSite` against the raw
centre. `GraphDataLoader` used it; `HourlyForecastLoader` didn't. That was the entire bug.

### Deliberately not done

- **Did not gate the delta on `isStaleEstimate`.** Tempting, but wrong: `displayTemp =
  estimatedTemp + appliedDelta`, so nulling the delta when stale would have displayed 78.1° instead
  of 64.4°. The delta anchoring is what rescues a garbage estimate. Freshness belongs in row
  selection, not delta suppression.

---

## 4. Tests

| Test | Kind | Pre-fix result |
|---|---|---|
| `shared/…/HourlyStaleFragmentCollapseTest` (7 cases) | pure JVM | **passes** |
| `app/…/HourlyLoaderStaleFragmentParityRoboTest` (4 cases) | Robolectric + real Room/SQLite | **fails** |
| `app/…/architecture/HourlyCollapseChokepointTest` | architecture (source scan) | **fails** |

### The pure tests are characterization, not regression protection

`HourlyStaleFragmentCollapseTest` exercises the stitcher, which was never wrong — every case except
the `stitchBySource` pair would have passed before the fix. This is precisely the trap documented in
`notes/260710-fragmentation-test-strategy-robolectric-vs-instrumented.md`: *"a unit test on the
helper cannot detect a path that bypasses the helper."* The file's KDoc says so explicitly so nobody
mistakes it for the guard. It is kept because it pins the arithmetic in the exact terms the bug was
reported in, using the real eight-fragment fixture.

### The Robolectric test is the real guard

It reproduced the production incident exactly:

```
loaders disagree on the same DB; HourlyForecastLoader=81.3 GraphDataLoader=66.6
forecast at 19:30 should be today's curve expected:<64.85> but was:<79.0>
```

79.0 is the precise value from the device log. It runs against real Room/SQLite deliberately — the
tie-break that selected the stale row is a *SQLite* behaviour, so a pure JVM test that hand-orders
the list would encode that observation as a premise instead of verifying it. One case asserts the DAO
row order directly, so if an index change ever alters it, the pure fixture's ordering premise fails
loudly rather than drifting silently.

### The chokepoint guard catches the next occurrence

Fails the build when a `dateTime`+`source`/coordinate collapse doesn't consult `fetchedAt` or
delegate to a sanctioned picker. Pre-fix it flagged the exact line:

```
HourlyForecastLoader.kt:93: .associateBy { Pair(it.dateTime, it.source) }
```

The first draft flagged 17 sites. Inspecting them showed `pickBestForecast` and
`HourlyForecastSelector` both already do `maxByOrNull { fetchedAt }` — which is *why* the delta was
correct whenever both fragments actually reached the resolver. The guard was narrowed to the defect's
real shape, with one allowlist entry (`GraphDataLoader`, whose dedupe key includes location).

---

## 5. Why the Existing Guard Missed It

`HourlyProximityQueryAllowlistTest` exists for exactly this bug family and **passed**, because
`HourlyForecastLoader.kt` sat on its allowlist with the justification:

> "extracted from WeatherWidgetWorker on 2026-08-04; same sameSite filter + stitcher logic"

That was false in all three respects that mattered:

| | `GraphDataLoader` | `HourlyForecastLoader` |
|---|---|---|
| `sameSite` centre | raw | quantized best-site |
| dedupe key | includes `locationLat/Lon` | drops them |
| freshness | fragments kept distinct | ignored — last wins |

The guard asked *"did you collapse to a site?"*, never *"is the collapse freshness-correct?"*.
**An allowlist entry is prose asserting equivalence that nothing executes.** That claim is now
enforced mechanically by `HourlyCollapseChokepointTest` and by the cross-loader parity test.

---

## 6. Verification

1. **Unit tests** — full `:shared:test` + `:app:testDebugUnitTest`: `BUILD SUCCESSFUL`.
2. **Proven-to-fail** — the loader fix was stashed and both new guards re-run; both failed with the
   diagnostics quoted above, then passed again with the fix restored.
3. **On-device** — `./gradlew installDebug`, then the Samsung Fold's today column read **`+0.3 fcst`**
   (was `−13.7`), confirmed by the user. Loader log confirms the collapse:

   ```
   HourlyForecastLoader: load: stitched=465 from current=2508 history=20048
                         center=37.41681671142578,-122.08899688720703 sites=8
   ```

   All eight fragments now collapse to a single series, centred on the raw coordinate.

---

## 7. Follow-Ups

- **Frozen fragments remain in the DB.** The seven superseded fragments are still present and will
  keep leaking into other coordinate-keyed reads. A cleanup pass that drops fragments superseded by a
  fresher same-site fragment is still worth doing.
- **`WRITE_QUANTIZE_DECIMALS = 3` does not collapse this device's real jitter.** The doc comment in
  `LocationMatch` assumes ~0.0001° jitter and treats 0.005° as "a genuinely different marker" — false
  for a phone sitting still on a desk. Worth revisiting the quantization grain.
- **Not committed.** Changes are staged in the working tree only.
