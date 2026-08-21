# Add actual cloud cover % to the Open-Meteo hourly cloud cover graph

**Status:** 📋 Planned 2026-08-20 · **Revised twice on 2026-08-20** — see the revision log at the end.
**Scope:** Open-Meteo only. NWS is out of scope: no `cloudLayers`/METAR parsing in the product, no
cross-source blending. (METAR is used in §8 as an offline yardstick, not as a data source.)

**Goal:** in the hourly **cloud cover** view under Open-Meteo, past hours show two curves that mean
different things:

| curve | is | comes from |
|---|---|---|
| **actual** | what Open-Meteo now says that hour *was*, after later runs assimilated observations | the `hourly_forecasts` rows the app **already stores** |
| **forecast** | what was predicted for that hour ~24 h beforehand | `cloud_cover_previous_day1`, Open-Meteo Previous Runs API |

Both platforms (Android widget + desktop), logic in `:shared`.

---

## 1. Why this is the right pair of curves

### 1a. The past curve is already a retro-corrected actual — it is just in the wrong role

`hourly_forecasts` is latest-only, REPLACE-overwritten, and Open-Meteo's `past_days` window rewrites
hours that have already elapsed. Measured at the user's site from `hourly_forecast_history`
(every run, one physical site):

| target hour | runs 08-19 11:47 → 08-20 05:20 | run at 08-20 12:28 | run at 08-20 14:50 |
|---|---|---|---|
| 11:00 local | **100** (4 consecutive runs) | 70 | **50** |
| 12:00 local | **100** (4 consecutive runs) | 81 | **54** |

Both downgrades landed **after** the hour had elapsed. These are not forecast updates — they are
Open-Meteo correcting its record toward what happened. So the app is already holding an actual; it
is simply painting it on the line labelled "forecast", and overwriting the real forecast to do it.

The whole feature is therefore a **re-assignment**, not an ingestion problem:

```
today:     forecast line ← retro-corrected value   (actual, mislabelled; changes under the user)
           actual line   ← does not exist

planned:   actual line   ← retro-corrected value   (already stored, no fetch)
           forecast line ← cloud_cover_previous_day1 (frozen, one new call)
```

### 1b. Ground truth: what the sky actually did

Checked against station sky cover for the same hours (`cloudLayers[].amount` → okta %):

| hour | KNUQ (3 km) | KPAO (7 km) | **retro-corrected** | ERA5 archive | `_previous_day1` |
|---|---|---|---|---|---|
| 11:00 | CLR–FEW (0–13) | SCT (38) | **50** | 83 | 100 |
| 12:00 | CLR (0) | SCT (38) | **54** | 86 | 100 |
| 13:00 | CLR (0) | FEW (13) | **64** | 95 | 100 |
| 14:00 | CLR (0) | SCT (38) | **35** | 100 | 100 |

The retro-corrected value is the closest of the three model products to what a person in Mountain
View would have seen. It is still too cloudy — see the caveat below — but it beats ERA5 at every
hour, and the forecast (100%) busted badly, which is exactly the story an accuracy view should tell.

**Caveat, and it is a real one.** At 11:00–13:00 the three nearest stations read **KNUQ 0, KPAO 38,
KSJC 69** — three ceilometers, 15 km apart, disagreeing by 69 points about the same hour. A
ceilometer measures the column directly overhead; a model's `cloud_cover` is an *area fraction* over
a grid cell. During marine-layer burn-off both can be right. "Actual cloud cover" over an area is
genuinely ambiguous, so §8 must not treat a single station as truth.

### 1c. Rejected alternatives

| candidate | why not |
|---|---|
| **ERA5** (`archive-api.open-meteo.com`) | Was the plan of record until §1b. Furthest from station reports at every hour tested (83–100% while Moffett reported clear); coarse grid resolved to a cell ~2 km away (`37.434, -122.074`, elev 6 m) for a request at `37.4168, -122.089`. **Dropped — not a fallback.** Nothing in this plan fetches `archive-api` |
| `historical-forecast-api.open-meteo.com` | Returns the archived model run — **identical to what is already stored** (one differing hour out of 16, at a run boundary). No new information |
| Re-filing past hourly as observations (`HistoricalActualsBackfill`) | Produces a curve exactly on top of the existing one. This is measurably what the *temperature* actual line already does under Open-Meteo: `delta = 0.00` for all 20 past hours checked, written in the same second |
| Meteosource `/time_machine` | Needs a key, 400 calls/day free with a mandatory backlink, one call **per day** of history (30 for a full pan). Its one differentiator, layered low/mid/high cloud, is available from Open-Meteo in the calls already being made |

---

## 2. Data sources and storage

### 2a. Actual — no fetch, no storage, no migration

The actual curve reads the **existing** `hourly_forecasts` rows for past hours, already loaded by
the cloud graph, already refreshed by the `past_days` window every fetch
(`OpenMeteoApi.kt:49`; `ACTUALS_HISTORY_DAYS` = 3 on Android, 7 on desktop).

Nothing to add. The entire cost of the actual curve is a rendering change.

One consequence to accept knowingly: the actual is a **moving target near the seam**. An hour that
just elapsed carries little correction; it firms up over the following runs. That is honest — the
value genuinely is provisional — but it means the newest hour or two of the actual curve will shift
between paints, and no `isFrozen`-style flag can hide it. Do not chase it.

### 2b. Frozen forecast — Open-Meteo Previous Runs API

```
GET https://previous-runs-api.open-meteo.com/v1/forecast
    ?latitude=…&longitude=…
    &hourly=cloud_cover_previous_day1
    &past_days=<pan window, ≤31>&forecast_days=1&timezone=auto
```

Verified at the user's location:

- `past_days=31` → **768 hours** (2026-07-20 → 2026-08-20), `cloud_cover_previous_day1`
  **768/768 non-null**. The 30-day pan is fully covered.
- The `_previous_dayN` suffix caps at **7** (`_day8` → 0/72). That ceiling is on *forecast lead
  time*, **not** lookback. Do not design anything that needs `_day8`.
- No API key; same free tier as the rest of Open-Meteo.
- Cross-checked against this device's own pre-hour snapshots over six morning hours: five exact
  (100 vs 100), one differing (13:00: device 90, API 100) because they answer different questions.

**Why the API rather than this device's `hourly_forecast_history` snapshots.** Freezing from local
snapshots works, but the frozen value then depends on device uptime — a phone asleep 05:00–12:00
holds no pre-hour snapshot for 11:00 and silently falls back. `_previous_day1` is server-side and
identical on every device, forever.

The trade, stated rather than glossed:

| | local snapshots (rejected) | `_previous_day1` (chosen) |
|---|---|---|
| the question | "last thing predicted before this hour" | "what was predicted ~24 h out" |
| forecast lead | ~0–4 h | ~24 h |
| accuracy of the frozen curve | better | **worse** — longer lead, worse forecast |
| identical across devices | no | **yes** |
| needs local history | yes | no |

**Parity bonus:** `DailySnapshotSelector.PRIOR_WINDOW_HOURS = 24` — the daily view's today-column
already compares against the forecast as of ~24 h ago. This puts the hourly graph on the convention
the daily bars already use instead of inventing a second one.

### 2c. Where the frozen forecast is stored

`hourly_forecast_history` — "historical snapshots of the hourly forecast: what each hour was
*predicted* to be, captured at a `timestampToGroupPredictions`". `_previous_day1` is precisely that.

| field | value |
|---|---|
| `source` | `"OPEN_METEO_PRIOR24"` — a distinct id, never a display source |
| `timestampToGroupPredictions` | `hourStart − 24 h` — the nominal time the prediction was made |
| `fetchedAt` | real fetch time (the column's documented meaning; also what retention keys on) |
| `cloudCover` | the `_previous_day1` value |
| coords | `LocationMatch.quantize(userLat, userLon)` |

**Zero schema change. No migration on either platform.** The table already has `cloudCover`,
quantized coordinates, `LocationMatch` reads and 30-day retention — matching the 30-day pan.

> **This supersedes the earlier decision to store in `observations`.** That call was made when the
> thing being stored was the ERA5 *actual*; with ERA5 gone, the only value left to persist is a
> *forecast*, and filing a forecast in a table named `observations` would have no remaining
> justification. Same instinct — reuse an existing table — applied to the value that now exists.
> Flagged explicitly so the change is a decision, not a drift.

### 2d. Consumer audit for the new source id

| consumer | how it filters | effect | action |
|---|---|---|---|
| `HourlyForecastStitcher.stitch` | caller supplies rows for one source | never asked for `OPEN_METEO_PRIOR24` | none |
| `stitchBySource` | groups by source, callers pass a source list | would carry an inert extra group **if** an all-sources reader feeds it | **verify** `getHistoryInRangeForBucketWindowAllSources` callers filter downstream; if any does not, add the exclusion there |
| `HourlyForecastHistoryDao.deleteOldHistory` | `fetchedAt < cutoff` | 30-day retention applies normally | none |
| Forecast History view (`ForecastHistoryViewLogic`) | source-scoped | invisible | none |

The one open item is the `AllSources` reader. It is a **verification step, not an assumption** —
resolve it before writing the fetch.

Why not write these under the real `OPEN_METEO` source id: `stitch`'s history fallback picks the row
with the greatest `fetchedAt`, so freshly-fetched rows carrying a 24-h-old prediction could win as
"the latest forecast" for a past hour that has aged out of the live table — silently changing the
temperature and precipitation graphs. The distinct id makes that impossible.

---

## 3. Fetch

| platform | where | cadence |
|---|---|---|
| Desktop | new `DesktopWeatherService.fetchPriorDayCloudForecast(days)`, from the same refresh path as `fetchOpenMeteoForecastWithActuals` | ≤ 1×/hour |
| Android | `ForecastFetchCoordinator`, beside the existing Open-Meteo fetch (`ForecastFetchCoordinator.kt:154`) | ≤ 1×/hour |

- **Gate on Open-Meteo being an active display source** (`getActiveDisplaySourceIds()`); non-selected
  sources are throttled and this must not spend a call for a graph nobody is looking at.
- Parse `hourly.time[i]` in the response's own `timezone` (as `getForecast` does) → epoch ms; keep
  nulls as null (never coerce to 0); clamp 0–100; drop hours > now.
- Rows are immutable once written; REPLACE on the existing primary key makes a refetch idempotent.
- **Deep pan:** widen `past_days` on demand when the user pans past the stored window, as
  `fetchObservationHistory` does for the temperature line.
- Best-effort: a failure leaves the forecast curve on the live value with `isFrozen = false` and
  never blocks a paint. Count the call in `api_usage_stats` under `OPEN_METEO`.

---

## 4. Shared read path

New `shared/src/main/kotlin/com/weatherwidget/shared/graph/CloudSeriesBuilder.kt`:

```kotlin
data class CloudPoint(
    val timeMs: Long,
    val forecastCover: Int?,   // frozen (_previous_day1) for past hours; live for now/future
    val actualCover: Int?,     // retro-corrected live row, past hours only
    val isFrozen: Boolean,     // false when the frozen forecast was unavailable for this hour
)
```

1. Past hours: `actualCover` = the live `hourly_forecasts` value; `forecastCover` = the
   `OPEN_METEO_PRIOR24` row for that hour, `isFrozen = true`.
2. No prior-24 row for an hour → `forecastCover` = the live value, `isFrozen = false`. Never present
   a hindcast as a frozen forecast.
3. Current and future hours: `forecastCover` = live, `actualCover` = null.
4. **Gaps stay gaps.** No carry-forward filler — the graph already renders an honest missing-data
   diagnostic rather than implying clear sky.
5. Both series read through the existing `LocationMatch` box + site selection. Skipping the
   site-selection half is the most repeated defect in this codebase.

**The actual curve is not smoothed.** `SeriesSmoothing.smoothValuesPreservingAllExtrema` stays on the
forecast curve only (`feedback_no_smoothing_truth_curve`). The actual draws as a straight-segment
polyline through its real hourly values.

---

## 5. Render — Android (`CloudCoverGraphRenderer.kt`)

1. `CloudHourData` gains `actualCloudCover: Int?` and `isFrozen: Boolean`.
   `CloudCoverViewHandler.buildCloudHourDataList` (`CloudCoverViewHandler.kt:400`) joins the shared
   series onto the hour list by epoch ms.
2. **Vertical scale:** `computeVerticalScale` must take the max over *both* series. It reads only
   `smoothedValues` today; an actual of 98 against a forecast max of 50 would draw off the top.
3. **Draw order:** now-line → forecast fill (gradient, unchanged) → forecast curve → actual curve on
   top. The gradient fill stays anchored to the forecast curve; two fills turn the past into mud.
4. **Style:** mirror the temperature graph's grammar — for hours ≤ now the forecast curve is
   **dashed** (and is now the frozen 24-h-prior prediction), the actual is **solid** and brighter.
   Cloud is a monochrome view, so the actual needs value separation rather than a new hue: start
   with near-white `#E8EEF5` over the existing `#AAAAAA`, and settle it from a screenshot against
   both a heavy-cloud and a clear day before committing the constant.
5. **Labels.** `ValueLabelEngine.computePlacements` takes one `labelSignal` + one point list today.
   Extend it with `additionalCurves: List<List<GraphPoint>>` and `occupiedBounds: List<GraphRect>`,
   then call it twice — forecast first, actual second with the forecast's boxes as occupied. Both
   calls must receive **both** polylines: a label that clears its own curve and lands on the other
   is a bug this project has already shipped once (`free_label_collision_needs_all_curves`).
6. The empty-space watermark search and `drawnIconBounds` collision must also see the actual curve.

---

## 6. Render — desktop (`CloudCoverGraph.kt`)

Same grammar, same order. `CloudCoverGraph` takes a new `series: List<CloudPoint>` parameter; the
call site is `Main.kt:1209`. Forecast keeps `buildCurve` + `SeriesSmoothing`; the actual is a plain
`Path` polyline. `visibleMax`/`topScale` widen to cover both series, matching §5.2 exactly — the two
platforms compute the same number or the graphs drift (`desktop_label_placement_divergence`).

---

## 7. Gating and future widening

Render both curves only when the display source is `OPEN_METEO`. Express that as a `WeatherSource`
property (e.g. `hasPriorRunForecast`) rather than an `if (source == OPEN_METEO)` scattered across two
renderers, so extending it later is a data change. Every other source keeps today's single curve.

**Cloud graph only.** The temperature graph has the identical retro-correction property and is
deliberately left alone in this plan. Do not generalise the change while implementing it.

---

## 8. Validation before shipping

§1b is four hours at one location during marine-layer burn-off — the single hardest case for any
gridded product, and not a verdict. Before the rendering work:

1. **Multi-station, multi-regime check.** Collect a week of `cloudLayers` from KNUQ, KPAO and KSJC
   and compare against the retro-corrected series across clear, overcast and transitional days.
   Report the spread *between stations* alongside the model error — §1b's 0/38/69 disagreement means
   a single-station error bar is meaningless on its own.
2. **Directional agreement is the bar**, not point accuracy. A model hour reading 50% when every
   nearby station reports OVC is a failure; reading 50% when stations spread 0–69 is not.
3. **Settling time.** Sample one elapsed hour every fetch cycle for 24 h and record how long the
   retro-corrected value takes to stop moving (§2a). That number sets how much of the newest actual
   is worth drawing at all.

---

## 9. Tests

| level | test | asserts |
|---|---|---|
| shared unit | `PreviousRunsCloudParseTest` | captured fixture: `cloud_cover_previous_day1` → epoch ms in the response timezone, nulls preserved as null, 0–100 clamp, hours > now dropped |
| shared unit | `CloudSeriesBuilderTest` | past hours get both curves; current/future get forecast only; **gaps stay gaps**; site collapse across two coordinate fragments |
| shared unit | `FrozenForecastFallbackTest` | a present prior-24 row → `isFrozen = true` and that value; absent → live value with `isFrozen = false`. Reproduces the measured 11:00 case: live 50, prior-24 100 → the forecast curve must draw **100** and the actual **50** |
| shared unit | `PriorForecastSourceIsolationTest` | `OPEN_METEO_PRIOR24` rows never surface as an `OPEN_METEO` forecast: `HourlyForecastStitcher.stitch` output is byte-identical with and without them, including for a past hour with **no** live row (the §2d hazard) |
| shared unit | `CloudActualNoSmoothingTest` | the actual series is byte-equal to its input values |
| Android Robolectric | `CloudCoverFrozenPastCurveRoboTest` | the past forecast segment does not move when live rows are mutated but prior-24 rows are not — the §1a regression |
| Android Robolectric | `CloudCoverActualCurveRoboTest` | actual drawn only for hours ≤ now; vertical scale covers both series; no label overlaps either curve. Assert **dp geometry**, not text (`robolectric_no_font_engine`), and prove the test fails when the second curve is removed from the collision input |
| integration (2+ classes) | `PriorCloudForecastIntegrationTest` | fixture response → history DAO write → series build → point list, end to end |

No migration tests: §2c adds no columns and no tables.

Run: `./gradlew :shared:test :app:testDebugUnitTest` and `./scripts/emulator-tests.sh -c …`
(never `connectedDebugAndroidTest` — it strips widgets off the physical device).

---

## 10. Rollout

1. Resolve the §2d verification item (`getHistoryInRangeForBucketWindowAllSources` callers).
2. Run §8.1–8.2. **If directional agreement fails, stop** — no rendering work until the actual is
   trustworthy.
3. `:shared`: previous-runs call + `CloudSeriesBuilder` + tests. No UI change yet.
4. Fetch wiring, desktop first — `scripts/buildStart-desktop.sh`, screenshot the cloud view.
5. Android renderer + `installDebug`; screenshot at 4×2 and at a narrow zoom. Confirm the frozen past
   segment holds still across two consecutive fetch cycles.

---

## 11. Risks

| risk | mitigation |
|---|---|
| The "actual" is a model field, not a measurement, and §1b rests on one morning | §8 widens it to a week and three stations before any rendering work. If it fails there, the feature stops — there is no second model to fall back to, by design |
| The newest hour or two of the actual keeps shifting between paints | Inherent (§2a); §8.4 measures the settling time so the render can decide how much of the newest edge to draw |
| Frozen curve sits further from reality than the line it replaces, and reads as a regression | Inherent to a 24-h lead (§2b) and the point of an accuracy view — confirm on a screenshot that the two curves read as *forecast vs actual*, not as a rendering bug |
| `OPEN_METEO_PRIOR24` rows surface as a real forecast | §2c's distinct source id plus `PriorForecastSourceIsolationTest`; the §2d audit item must be closed first |
| A reviewer reverts this on sight of `HourlyForecastStitcher`'s doc comment about "original forecast" | That revert removed an **earliest-snapshot, 6–7-day-out** value that varied by device history depth. This is a **fixed 24 h**, server-side, in the cloud graph's data prep only — neither named failure applies. Put those two sentences at the freeze call site |
| Two curves + two label passes crowd a small widget | Suppress the actual's labels first when the plot is short; the existing `tallGraph` band is the natural switch |
| One extra API call per fetch cycle, on a new host | ≤ 1/hour, gated on Open-Meteo being displayed, counted in `api_usage_stats`, best-effort; free tier, no key |

---

## Revision log

| # | change | why |
|---|---|---|
| 1 | Storage for the actual moved from a new `hourly_cloud_actuals` table into `observations` | Reuse the table that already has quantization, site selection, retention and DAOs |
| 2 | Past forecast curve frozen, sourced from `cloud_cover_previous_day1` rather than local snapshots | The drawn "forecast" was being retro-edited (§1a); the API's value is device-independent |
| 3 | **ERA5 dropped entirely**; actual is now the retro-corrected `hourly_forecasts` value | §5's freeze released the retro-corrected value from the forecast role, and §1b showed ERA5 furthest from station reports. Removes one API call, one table's worth of storage and both migrations. ERA5 is dropped outright — no fetch, no fallback |
