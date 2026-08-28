# The observation blend reads at the *data's* coordinate, not the configured one

**Status:** ✅ Implemented 2026-08-28 (§3 + §4 landed; §5 still open, §7 untouched)
**Symptom:** hourly graph label read `knuq 66.2 @ 11:10 am` at 14:17 on the Samsung Fold, three hours
stale, while KNUQ's 13:35 reading (66.2°) sat in the database.
**Related:** [`260827-observation-site-merge-for-actual-series.md`](260827-observation-site-merge-for-actual-series.md) ·
[`260806-today-column-stale-fragment-delta-opus.md`](260806-today-column-stale-fragment-delta-opus.md)

**Goal:** the observation read that feeds the blend must be centred on **where the user is**, not on
whatever coordinate the first row of the hourly list happens to carry. Today those two diverge
whenever the device moves, and the divergence silently excludes every fresh observation.

---

## 1. Problem statement

The device moved Mountain View → Sunnyvale at 11:55
(`LOCATION_HANDOFF state=candidate_promoted location=37.4064271,-122.0206173`). Fetching followed the
move; rendering did not. The app logged the split itself, every paint:

```
14:17  configuredLoc=37.40643,-122.02061   dataLoc=37.41700,-122.08900
```

`dataLoc` is the coordinate the blend reads at. It was the *previous* site, frozen since 11:26.

| | old site `37.417,-122.089` | new site `37.406,-122.021` |
|---|---|---|
| newest NWS observation | **11:10** | **13:50** |
| newest row, any api | 11:15 | 13:50 |
| last fetch | 11:26 (frozen) | 14:05 |

The persisted diagnostic added for exactly this question said so outright:

```
DOMINANT_STATION  station=KNUQ rawTemp=66.2 weightShare=0.652
                  readingAgeMin=187 newestObsAgeMin=182 obsRows=7770
                  text=knuq 66.2° @ 11:10 am
```

`newestObsAgeMin=182` → 11:15, which is the frozen fragment's newest row *exactly*. Reproduced in
SQL against the pulled database: a read centred on the configured location returns **4175 rows ending
13:50**; this render got **7770 rows ending 11:15**.

The 66.2° coincidence is why this read as "plausible but stale" rather than as obvious corruption:
KNUQ happened to report 66.2° at both 11:10 and 13:35.

### 1a. Why a stale *centre* excludes fresh data rather than merely preferring old data

`ObservationDao.getObservationsInRange` reads the coarse ±0.1° box and hands it to
`ObservationSiteMerge.merge`, which keeps only rows within `MERGE_TOLERANCE_DEG = 0.01`. The two
fragments are **0.011° apart in latitude and 0.068° in longitude**. So centring on the stale fragment
does not weight fresh rows lower — it filters every one of them out before the blend runs.

That tolerance is correct and should not move: its KDoc sizes it against `distanceKm`'s error budget,
and a 6 km centre error would make the IDW weights meaningless (KNUQ is 2.4 km from the new site and
~5 km from the old one). **The centre is wrong, not the tolerance.**

### 1b. The defect

`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt:127-128`

```kotlin
val lat = hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
val lon = hourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN
```

That value flows into `getObservationsInRange(minEpoch, maxEpoch, lat, lon)`,
`computeDeltaFromYesterday`, `maybeEnqueueHourlyObservationBackfill(observationsLat = lat, …)` and
`SunPositionUtils.getSunInfoOrUnknown`.

The same widget paint already knows better **40 lines away**.
`TemperatureViewHandler.kt:124-129` resolves the current-temp location as:

```kotlin
currentLat = stateManager.getWidgetLocation(appWidgetId)?.first
    ?: currentTempHourlyForecasts.firstOrNull()?.locationLat
    ?: Double.NaN,
```

Prefs first, data row only as fallback. The resolver's blend location skips the prefs rung entirely.
One paint, two different answers to "where am I?".

`resolve()` already takes both `appWidgetId` and `stateManager` as parameters, so the missing rung
costs no signature change.

### 1c. Why it flapped instead of staying broken

`dataLoc` alternated between correct and stale within the same minute, on the same widget:

```
13:05:39  widget=345  dataLoc=37.40600   ← correct
13:06:42  widget=345  dataLoc=37.41700   ← stale
14:04:19  widget=349  dataLoc=37.40600   ← correct
14:16:16  widget=345  dataLoc=37.41700   ← stale
```

So the label oscillated between a fresh reading and a three-hour-old one depending on which paint
path ran last — the same signature as the `-13.7°` today-column bug in
`260806-today-column-stale-fragment-delta-opus.md`.

**This is currently masked, not fixed:** the device returned to `37.41682` around 14:25, so
`configuredLoc == dataLoc` again and the label reads `knuq 68° @ 1:55 pm`. It will return on the next
move.

---

## 2. Design decisions

### 2a. Prefer the configured location; keep the data row as fallback

The observation blend answers "what is the sky doing where the user is". A device site is fetch
provenance, not a weather location — the premise `ObservationSiteMerge` was built on. The configured
location is the app's canonical answer to "where is the user", maintained by
`ActiveLocationResolver` and mirrored into `widget_lat_<id>` by `syncCompatibilityCopies`.

The data-derived coordinate stays as the fallback for the case it was written for: no configured
location at all. `Double.NaN` stays as the final rung and keeps degrading honestly.

**Accepted consequence:** immediately after a move, the new site may hold fewer observations than the
frozen one, so the graph draws fewer actual points until the first fetch at the new location lands.
That is the correct outcome, and the window is short — here the handoff at 11:55:09 and the first
fetch at the new site at 11:55:20 were eleven seconds apart. Drawing another location's frozen
readings labelled with the current time is worse than drawing less: it is the "substitute a
plausible-looking stand-in" failure this project has ruled out for coordinates elsewhere.

### 2b. `Double.NaN` is a safe default for a scalar, not for a selector

The comment above line 127 defends the NaN correctly for its original consumers — sun shading falls
back to `UNKNOWN_LOCATION`, IDW distance weights drop out. That reasoning did not get revisited when
the value became the **centre of a ±0.01° filter**. A filter centred on the wrong point does not
degrade; it returns a confident, wrong, complete-looking answer.

Worth stating in the code so the next consumer added here inherits the constraint rather than the
original one.

### 2c. Do not widen `MERGE_TOLERANCE_DEG`, and do not touch `selectNearestSite` in this change

`LocationMatch.selectNearestSite` has **no distance ceiling** — it returns the nearest site *present
in the list*, however far away. A frozen fragment 6 km out wins whenever it is the only one there.
That is a real second defect (same shape as the one `selectNearestSiteWith` was written for), but it
governs every forecast read in the app. Changing it here would widen the blast radius of a fix whose
root cause is fully understood. Diagnose first — see §5.

---

## 3. The change

### 3.1 Centre the resolver on the configured location

`TemperatureStateResolver.kt:127-128`:

```kotlin
// The location the user is AT, not the location the first cached row was fetched at. This is not
// only drawn with — it is the CENTRE of ObservationSiteMerge's ±0.01 deg filter, so a stale value
// here does not down-weight fresh observations, it excludes every one of them (2026-08-28: a
// 6 km move left the blend reading a fragment frozen 3 h earlier and the graph naming an 11:10
// reading at 14:17). Mirrors TemperatureViewHandler's current-temp resolution; the two must not
// disagree within one paint.
val configuredLocation = stateManager.getWidgetLocation(appWidgetId)
val lat = configuredLocation?.first ?: hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
val lon = configuredLocation?.second ?: hourlyForecasts.firstOrNull()?.locationLon ?: Double.NaN
```

### 3.2 Make the divergence impossible to ship silently again

Extend the existing `DOMINANT_STATION` line (`TemperatureStateResolver.kt:374-382`) with the two
coordinates and the fallback rung actually used:

```
locSource=configured|data|none obsLat=… obsLon=…
```

`DOMINANT_STATION` is already the persisted line for "the label showed a stale temperature an hour
ago" and already pairs `readingAgeMin` with `newestObsAgeMin`. Adding the centre completes it: the
three fields together distinguish *stalled fetch* from *lagging station* from *wrong centre*, and
this incident was the third case with no field naming it.

### 3.3 Assert the two locations agree

Where a configured location exists and the data-derived coordinate is further than
`ObservationSiteMerge.MERGE_TOLERANCE_DEG` from it, log `WARN`:

```
BLEND_CENTRE_DIVERGENCE widget=… configured=… data=… deltaLat=… deltaLon=…
```

This does not change behaviour — §3.1 already picks the right centre. It surfaces the *upstream*
condition (an hourly list holding only far-site rows), which is the open question in §5.

---

## 4. Testing

Automated first; the device repro needs a 6 km drive, and the whole failure is reconstructable
offline from the fragments already in the pulled database.

### 4.1 Unit — the centre selection itself

New: `app/src/test/java/com/weatherwidget/widget/handlers/BlendCentreLocationTest.kt`

| Case | Configured | First hourly row | Expected centre |
|---|---|---|---|
| moved device | `37.4064, -122.0206` | `37.417, -122.089` | configured |
| no configured location | absent | `37.417, -122.089` | data row |
| no location at all | absent | empty list | `NaN` |
| agreement | `37.4064, -122.0206` | `37.406, -122.021` | configured (unchanged) |

The first row is the regression: today it returns the stale fragment's coordinate.

### 4.2 Integration — centre → merge → blend

New: `shared/src/test/kotlin/com/weatherwidget/shared/actuals/BlendCentreExcludesFreshRowsTest.kt`,
alongside the existing `ObservationSiteMergeBlendIntegrationTest`.

Two classes, real data shape, no mocks: build the two fragments from this incident — old site with
KNUQ rows ending 11:10, new site with KNUQ rows ending 13:35 — and assert:

1. `merge(rows, centre = old site)` returns **zero** rows from the new fragment. Pins §1a: the
   failure is exclusion, not down-weighting, and this is the fact that makes the tolerance the wrong
   knob.
2. `merge(rows, centre = new site)` yields a dominant contribution whose `lastReadingMs` is 13:35.
3. The resulting `DominantStationLabel` text differs between the two centres — the user-visible
   symptom, asserted end to end.

Assert on the *timestamp*, never on the temperature: both readings are 66.2°, so a value assertion
passes under the bug. Prove the test can fail by reverting §3.1.

### 4.3 Robolectric — the paint agrees with itself

Extend `app/src/test/java/com/weatherwidget/widget/handlers/CurrentTempUnificationIntegrationTest.kt`
(same package, same Robolectric + mockk pattern already in use): with a configured location and an
hourly list whose rows carry a *different* coordinate, assert the current-temp centre and the blend
centre are equal. That equality is the invariant §1b broke, and it is the cheapest guard against a
future consumer re-deriving the location from data.

### 4.4 Regression suite

```bash
./gradlew testDebugUnitTest --tests "*BlendCentre*" --tests "*ObservationSiteMerge*" \
                            --tests "*CurrentTempUnification*" --tests "*LocationMatch*"
```

---

## 5. Open question: why did the hourly list contain only far-site rows?

§3.1 fixes the observation read regardless of how the list is shaped, and is worth landing on its
own. But the upstream condition is not fully pinned and should not be written up as though it were.

For `dataLoc` to be `37.417` at all, `WidgetRenderer.kt:282`'s
`unifyToNearestSite(hourlyForecasts, configuredLat, configuredLon)` must have found **no** row at the
configured site — `selectNearestSite` picks the nearest coordinate present, and `37.406` rows would
have won. The database holds 314 NWS hourly rows at the new site (last fetched 13:05:39), so they
existed. That points at the *loader* having run with the old centre on those paints, not at the
unify step.

Cheapest way to settle it: `HourlyForecastLoader` already logs
`load: stitched=… center=$lat,$lon sites=…` — but at `Log.i`, so it is logcat-only and was gone by
the time the report came in. Promote that one line to `app_logs`, or capture logcat across the next
move. `OBS_HOURLY_BACKFILL_RUN` also carries the resolver's `lat`/`lon`, giving a second independent
signal to cross-check.

Do this **before** deciding whether `selectNearestSite` needs a distance ceiling (§2c).

---

## 6. Verification on device

The installed build is **26082701** (2026-08-27 17:02); HEAD is 26082702. The site-merge commit
`9d0010c8` (08-27 14:41) *is* in it, so this is a live defect and not a stale-build artifact.

After installing the fix, on the next real move:

1. `configuredLoc` and `dataLoc` in `TemperatureViewHandler headerState` must be equal.
2. `DOMINANT_STATION`'s `readingAgeMin` must track `newestObsAgeMin` (both small) rather than sitting
   ~180 while fresh rows exist.
3. `locSource=configured` on every paint that has a location.

```sql
SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
FROM app_logs WHERE tag='DOMINANT_STATION' ORDER BY timestamp DESC LIMIT 20;
```

---

## 7. Out of scope (noted, not fixed here)

- **Orphaned per-widget coordinates.** `weather_widget_prefs` still holds
  `widget_lat_346 = 37.416836` and `widget_lat_353 = 37.416836` for widgets that no longer exist —
  `syncCompatibilityCopies` only updates ids currently returned by `AppWidgetManager`. Harmless
  today (nothing reads a dead id), but it is a second stale-coordinate source in the same file, and
  `LegacyDefaultLocationMigration` *does* scan `widget_lat_*` by prefix.
- **`selectNearestSite`'s missing distance ceiling** — see §2c and §5.

---

## 8. Implementation notes (2026-08-28)

Landed as planned, with one thing the plan did not anticipate.

**New:** `app/.../handlers/BlendCentre.kt` — the rung order extracted as a pure object so §4.1 can
test it without Robolectric, and so the "this is a filter centre, not just a coordinate" constraint
has somewhere to live.

**Changed:** `TemperatureStateResolver.resolve` centres on `getWidgetLocation(appWidgetId)` first;
`DOMINANT_STATION` gained `locSource=` and `obsCentre=`; `BLEND_CENTRE_DIVERGENCE` added.

**Unanticipated:** `CurrentTempUnificationIntegrationTest` uses `mockk<WidgetStateManager>(relaxed =
true)`, and a relaxed mock *fabricates* a `Pair<Object, Object>` for the newly-called
`getWidgetLocation` — the `Double` cast then threw `ClassCastException` inside the resolver. Both
pre-existing tests now stub the location explicitly. Worth knowing for any future test that mocks
`WidgetStateManager` relaxed and calls `resolve`: this dependency is not optional any more, and the
failure mode is a cast error deep in the resolver rather than an obviously-missing stub.

The other three call sites of `resolve` in tests build a real `WidgetStateManager(context)`, which
returns null with no prefs and falls through to the data rung — so they were unaffected.

**Verified on device** (SM-F936U1, debug build installed 14:39):

```
DOMINANT_STATION  ... readingAgeMin=25 newestObsAgeMin=5 obsRows=8171
                      locSource=configured obsCentre=37.41682,-122.08902
                      text=knuq 68° @ 2:15 pm
headerState       ... configuredLoc=37.41682,-122.08902 dataLoc=37.41682,-122.08902
```

`dataLoc` now prints the *raw* configured coordinate rather than a row's 3 dp quantized one — the
clearest single tell that the centre no longer comes from the data. Before the fix the same field
read `dataLoc=37.41700,-122.08900` with `configuredLoc=37.40643,-122.02061`.

Regression proven by reverting §3.1 in place: only
`blend centre follows the configured location, not the coordinate on the rows` failed, and the
fallback test kept passing — the guard is specific to the rung that broke.

**Caveat:** the device had already returned to the original site by the time the fix was installed,
so this verification confirms the mechanism and the diagnostics, *not* the moved-device case on real
hardware. §4.2 covers that case offline against the measured fragments; the on-device confirmation
has to wait for the next move.
