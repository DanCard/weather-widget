# Today-column "fcst" delta reads −13.7° from a 13-day-old coordinate fragment

Samsung Fold (SM-F936U1), daily forecast view, today column, Meteo source.
Observed 2026-08-06 ~19:31 and again ~19:41; briefly self-corrected to `+0.5` at 19:39.

## Problem

The today-column overlay renders `−13.7 fcst` while the header renders `+0.5 from yest`.
The value **oscillates** between `−13.7` and `+0.5` across renders with no data change.

Captured from the device (`adb logcat`):

```
19:41:30.382  isStaleHourlyData: source=OPEN_METEO scopedCount=16 latestFetchMs=1784948582262
              ageMs=1121908010 thresholdMs=7200000 stale=true          <- fetched 2026-07-24
19:41:30.382  CURR_TEMP_RESULT: display=64.42 estimate=78.12 obs=65.30 delta=-13.70 estAtObs=79.00
19:41:30.571  TODAY_OVERLAY: widget=345 delta=-13.7 ...

19:39:50.922  isStaleHourlyData: source=OPEN_METEO scopedCount=32 latestFetchMs=1786069387738
              ageMs=1003003 stale=false                                 <- fetched today 19:23
19:39:50.923  CURR_TEMP_RESULT: display=64.73 estimate=64.28 obs=65.30 delta=0.45 estAtObs=64.85
19:39:51.242  TODAY_OVERLAY: widget=345 delta=+0.5 ...
```

`appliedDelta = observedTemp − forecastAtObservationTime`. Observed is right (65.3°).
The forecast it is differenced against is wrong: `79.00` instead of `64.85`.

## Root cause

### 1. Eight coordinate fragments, one live

The phone has not moved for days, but GPS jitter has written eight distinct
`(locationLat, locationLon)` keys into `hourly_forecasts`. For OPEN_METEO in today's
08:00–23:00 window, each fragment holds a full 16-row day:

| site | rows | last fetched | 19:00 | 20:00 |
|---|---|---|---|---|
| 37.419,-122.087 | 16 | **2026-07-24** | 81.3 | 76.7 |
| 37.481,-122.184 | 16 | 2026-07-27 | 71.2 | 63.8 |
| 37.42,-122.095  | 16 | 2026-07-29 | 79.9 | 75.7 |
| 37.377,-122.075 | 16 | 2026-07-30 | 80.6 | 76.8 |
| 37.422,-122.073 | 16 | 2026-08-03 | 80.3 | 75.6 |
| 37.422,-122.087 | 16 | 2026-08-04 | 81.4 | 76.6 |
| 37.424,-122.088 | 16 | 2026-08-04 | 81.5 | 76.7 |
| **37.417,-122.089** | 16 | **2026-08-06 19:23** | **66.6** | **63.1** |

Only the last is still refreshed. The other seven are frozen long-range forecasts —
Open-Meteo happily returns 14 days ahead, so a fetch on Jul 24 wrote rows for today.

`WRITE_QUANTIZE_DECIMALS = 3` (~111 m) assumes jitter lives in the 4th decimal. Real
observed jitter here spans 37.417→37.424 (~780 m) and −122.073→−122.095 (~1.9 km), so
quantization does not collapse it and every fetch at a new fix mints a new fragment.

### 2. `sameSite` disagrees with itself on this exact boundary

`LocationMatch.sameSite` uses `SAME_SITE_TOLERANCE_DEG = 0.002` with `<=`. The two render
loaders pass **different centers**, and the stale `37.419,-122.087` fragment sits precisely
on the boundary:

| caller | center passed | Δlat to 37.419 | admitted? |
|---|---|---|---|
| `GraphDataLoader.loadGraphWindowHourlyForecasts` | raw `37.41681671142578` | `0.0021832886` | **no** (> 0.002) |
| `HourlyForecastLoader.load` | quantized best-site `37.417` | `0.001999999999995339` | **yes** (≤ 0.002) |

A floating-point hair decides whether a 13-day-old forecast enters the render set.

### 3. The collapse that follows is `fetchedAt`-blind and last-wins

`HourlyForecastLoader.load:92-95`:

```kotlin
val stitched = (history + filteredCurrent)
    .associateBy { Pair(it.dateTime, it.source) }   // last wins; fetchedAt ignored
```

Once step 2 admits two fragments, this picks one per `(dateTime, source)` with no
freshness preference. The DAO orders `dateTime ASC`, and SQLite breaks ties using
`index_hourly_forecasts_locationLat_locationLon` — i.e. **ascending latitude**. Verified
row order for the 19:00 hour:

```
37.417,-122.089  66.6   2026-08-06 19:23:07   <- fresh, arrives first
37.419,-122.087  81.3   2026-07-24 20:03:02   <- stale, arrives later -> WINS
```

So the stale fragment deterministically overwrites the fresh one in this path.

### 4. Result

Render set becomes the July-24 curve: 19:00 = 81.3, 20:00 = 76.7. Observation time is
19:30, so `forecastAtObs = (81.3 + 76.7) / 2 = 79.00` — matching the log exactly.

```
appliedDelta = 65.3 − 79.00 = −13.70
```

The widget alternates between `−13.7` and `+0.5` depending on **which loader last
refreshed it** — `HourlyForecastLoader` (stale fragment admitted) vs `GraphDataLoader`
(stale fragment excluded).

Note `isStaleEstimate` was already `true` here (age 13 days vs a 2 h threshold). The
resolver knew the data was ancient and displayed the delta anyway.

## What will change

1. **Freshness-aware collapse (the fix).** Replace the `fetchedAt`-blind `associateBy` in
   `HourlyForecastLoader.load` with a max-by-`fetchedAt` reduction per
   `(dateTime, source)`. A stale fragment can then never overwrite a fresh row regardless
   of row order or which fragments the box admits. This alone removes the −13.7 and the
   oscillation.

2. **Make fragment selection deterministic across loaders.** `LocationMatch` gains a
   shared helper that collapses a box result to one row per `(dateTime, source)` choosing
   the greatest `fetchedAt`, and the loaders route through it so `GraphDataLoader` and
   `HourlyForecastLoader` cannot disagree. Keeps the boundary case from mattering at all
   rather than re-tuning `SAME_SITE_TOLERANCE_DEG` (which would only move the boundary).

3. **Do not gate the delta on `isStaleEstimate`.** Tempting, but wrong: `displayTemp =
   estimatedTemp + appliedDelta`, so nulling the delta when stale would have shown 78.1°
   instead of 64.4° — the anchoring is what keeps the current temp sane. Freshness belongs
   in row selection, not in delta suppression.

## Why the existing guard missed this

`architecture/HourlyProximityQueryAllowlistTest` exists precisely for this bug family. It
passed, because `HourlyForecastLoader.kt` is **on its allowlist**:

```kotlin
"HourlyForecastLoader.kt" to "extracted from WeatherWidgetWorker on 2026-08-04; same sameSite filter + stitcher logic",
```

That justification is untrue. The two paths differ in all three respects that matter:

| | `GraphDataLoader` | `HourlyForecastLoader` |
|---|---|---|
| `sameSite` center | raw `37.41681671…` | quantized best-site `37.417` |
| dedupe key | includes `locationLat/Lon` | drops them |
| freshness | fragments kept distinct | ignored — last wins |

The guard asks only *"did you collapse to a site?"*, never *"is the collapse
freshness-correct?"*. An allowlist entry is a hand-written claim, and this one was wrong
the day it was added. The tests below close that gap.

## Testing

All pure-function unit tests unless noted — no mocking, per the project's
extract-and-test-pure approach. `@Category(ShortDuration::class)` is REQUIRED on app unit
tests or `validateUnitTestDurations` fails the build. Every test below must be proven to
fail against the current code before the fix lands. Diagnostic output stays in permanently.

**Fixture.** The eight real fragments captured from the device for `2026-08-06 19:00/20:00`
(table above) become a shared test fixture, in ascending-latitude order — the order SQLite
actually returns them via `index_hourly_forecasts_locationLat_locationLon`. Using the real
data means the test documents the incident, not a synthetic approximation.

### Core regression

1. **Freshest row wins the collapse.** Feed the eight-fragment 19:00 set; assert the
   surviving row is `37.417,-122.089 / 66.6° / fetchedAt=2026-08-06 19:23`, not
   `37.419,-122.087 / 81.3° / fetchedAt=2026-07-24`. Fails today (`associateBy` last-wins
   picks 81.3). Assertion message prints the winning row's `fetchedAt` and site.

2. **Order independence.** Same set, shuffled through several fixed seeds plus the explicit
   ascending- and descending-latitude orders; assert an identical result every time. This
   is the oscillation invariant: today's answer depends on row order, which is why the
   widget flipped between −13.7 and +0.5 without any data changing.

3. **The user-visible number.** Drive `CurrentTemperatureResolver.resolve` with the eight
   fragments plus the real 65.3° observation at 19:30; assert `appliedDelta ≈ +0.45` and
   `estimatedAtObservationTime ≈ 64.85`, and explicitly assert it is **not** ≈ −13.7 / 79.0
   with a message naming the July-24 fragment. Guards the whole chain end to end in the
   exact terms the bug was reported in.

### Cross-path agreement

4. **Loader parity.** Given identical fragment input and the same raw center,
   `HourlyForecastLoader`'s collapse and `GraphDataLoader`'s must return the same
   temperature for 19:00. This is the invariant the bug violated outright (−13.7 vs +0.5
   from two loaders reading one database).

5. **`sameSite` centre-form consistency.** Pin the floating-point boundary: assert
   `sameSite(raw, row)` and `sameSite(quantize(raw), row)` agree for the
   `37.419,-122.087` fragment. They do not today — `0.0021832886 > 0.002` but
   `0.001999999999995339 <= 0.002`. Either the fix makes them agree, or the test documents
   the discrepancy as accepted and proves the collapse no longer depends on it.

### Guards against the next occurrence

6. **Ban bare `(dateTime, source)` collapse.** New architecture test, sibling to the
   existing allowlist guard: fail the build when any file collapses hourly rows with
   `associateBy`/`distinctBy` on a `dateTime`+`source` key outside the shared
   freshness-aware helper. This is the check that would have caught the present bug, and
   it catches call sites that do not exist yet — which per-call-site tests cannot.

7. **Tighten the existing allowlist guard.** Correct the false `HourlyForecastLoader.kt`
   justification, and require each entry to name the collapse helper it routes through so
   a future entry cannot assert parity that isn't there.

### Robolectric (real Room/SQLite)

Tests 1–7 are pure JVM and share a blind spot: they hand-build the fragment list in
ascending-latitude order, which is a **SQLite/Room** behaviour (the `ORDER BY dateTime ASC`
tie-break falling through to `index_hourly_forecasts_locationLat_locationLon`), not a
Kotlin one. A pure test encodes that observation as a premise instead of verifying it — an
index change or Room upgrade would flip production while the pure tests stay green.

Following `notes/260710-fragmentation-test-strategy-robolectric-vs-instrumented.md` and the
`DailyCloudCoverSiteParityRoboTest` / `CurrentTempUnificationIntegrationTest` precedent.
These assert *data*, never rendered pixels, so the Robolectric no-font-engine limitation
does not apply.

8. **Cross-loader parity against a seeded DB.** Seed an in-memory Room DB with all eight
   real fragments for today's window. Drive `HourlyForecastLoader.load` and
   `GraphDataLoader.loadGraphWindowHourlyForecasts` against the same DB with the raw center
   `37.41681671142578,-122.08899688720703`; assert both yield 66.6° for 19:00. This is the
   invariant that actually broke, tested through real SQLite rather than an assumed list
   order. Proven-to-fail: today the two disagree (81.3 vs 66.6).

9. **Tie-break order is verified, not assumed.** Against the same seeded DB, assert the
   DAO's raw return order for the 19:00 hour, with the observed order printed in the
   assertion message. If SQLite ever stops returning ascending-latitude ties this test says
   so loudly instead of letting tests 1–2 quietly drift out of correspondence with
   production. Also assert the *fixed* collapse yields the fresh row regardless — i.e. the
   fix makes the ordering irrelevant, which is the property worth having.

10. **End-to-end delta through the DB.** Seeded DB + the real 65.3° @ 19:30 observation
    through the daily-view resolve path; assert the resulting `appliedDelta` is ≈ +0.45 and
    the today-column overlay text is not `−13.7`. The user-facing assertion, end to end.

### Not unit-testable

11. **Device confirmation.** Rebuild, install, then confirm across repeated renders that
    `TODAY_OVERLAY delta=` is stable and `CURR_TEMP_RESULT estAtObs` tracks the latest fetch
    rather than a July row.

**Instrumented tests: deliberately none.** Nothing here is device-specific — the divergence
lives entirely in JVM-land data selection (DAO → list → collapse), fully reproducible under
Robolectric with real SQLite. Instrumented runs earn their cost for RemoteViews stickiness,
launcher behaviour, WorkManager scheduling and real GPS; none apply. Same call as the
2026-07-10 recurrence. Note the widget-removal hazard on physical devices: emulator-only via
`./scripts/emulator-tests.sh`, never `connectedDebugAndroidTest`.

## Follow-ups (not in this change)

- Seven frozen fragments remain in the DB and will keep leaking into other coordinate-keyed
  reads. Worth a cleanup pass that drops fragments superseded by a fresher same-site
  fragment.
- `WRITE_QUANTIZE_DECIMALS = 3` does not collapse this device's real jitter; the doc comment
  in `LocationMatch` assumes ~0.0001° jitter and treats 0.005° as "a genuinely different
  marker", which is false for a phone sitting still on a desk.

## Status

**Implemented and verified on-device 2026-08-06.**

Changed:
- `HourlyForecastStitcher.stitchBySource` (shared) — new multi-source entry point; `stitch`
  collapses to one row per hour, so multi-source callers needed per-source stitching.
- `HourlyForecastLoader.load` — replaced the bespoke nearest-site + `sameSite` +
  `associateBy` collapse with `HourlyForecastStitcher.stitchBySource` against the RAW centre.
- `HourlyForecast.toEntity` moved to `HourlyForecastEntity.kt` and shared by both loaders
  (was a private copy in `GraphDataLoader`).
- `HourlyProximityQueryAllowlistTest` — corrected the false `HourlyForecastLoader.kt`
  justification.

Tests added:
- `shared/…/HourlyStaleFragmentCollapseTest` — 7 characterization tests on the real
  eight-fragment fixture. Note these would have passed pre-fix (the stitcher was already
  correct); they pin arithmetic, not the regression.
- `app/…/HourlyLoaderStaleFragmentParityRoboTest` — the real guard, Robolectric + Room.
  Proven-to-fail: pre-fix it reported `HourlyForecastLoader=81.3 GraphDataLoader=66.6` and
  `forecast at 19:30 was 79.0`, reproducing the device incident exactly.
- `app/…/architecture/HourlyCollapseChokepointTest` — proven-to-fail, flags
  `HourlyForecastLoader.kt:93: .associateBy { Pair(it.dateTime, it.source) }` pre-fix.

Verification: full `:shared:test` + `:app:testDebugUnitTest` green. On the Samsung Fold the
today column now reads `+0.3 fcst` (was `−13.7`), with
`load: stitched=465 from current=2508 … sites=8` confirming all eight fragments collapse to
one series.
