# Fix the Observations screen's empty list: stale `activeLocation` + an unguarded site collapse

**Status:** 📋 Planned 2026-08-15
**Device evidence:** Samsung SM-F936U1 (`RFCT71FR9NT`), build `26081201`, 2026-08-15 20:04–20:15
**Supersedes the premise of:** [`260815-observations-screen-auto-fetch-on-empty-ds.md`](260815-observations-screen-auto-fetch-on-empty-ds.md)
— that plan assumes the observations table is empty when the screen says so. On the reported device
it was not: five NWS stations were in the DB, freshly fetched, the entire time the screen said
"No recent observations found for NWS." Auto-fetch is therefore demoted from *the fix* to §4, a
narrowly-scoped last resort.

**Goal:** the Observations screen must never claim it has no observations while the DB holds
observations for the displayed source, and must never get stuck in that claim for the lifetime of
the activity.

---

## 1. Problem statement

Opening **Current Observations** on the Fold showed `No recent observations found for NWS.` and kept
showing it across eleven automatic reloads over eleven minutes. The DB, pulled live during the
failure, held five NWS stations at the user's actual site:

```
AW020  KNUQ  KPAO  LOAC1  KSJC   @ locationLat=37.417, locationLon=-122.089
                                  newest observation 19:50, fetched 20:09
```

Pressing Back and relaunching the same activity, against the same database, forty-five seconds
later, fixed it outright:

```
20:14:38  loadObservations: currentSource=NWS items=[]
20:15:23  loadObservations: currentSource=NWS items=[AW020, KNUQ, KPAO, LOAC1, KSJC]
```

### 1a. Root cause

Three mechanisms compose into the failure. None is a bug on its own.

| # | Mechanism | Where |
|---|---|---|
| A | `activeLocation` is resolved **once**, in `onCreate`, and reused by every later reload | `WeatherObservationsActivity.kt:120` |
| B | A transient GPS excursion created a *second* location fragment, `37.411,-122.095`, live 19:41–20:04 | `observations`/`forecasts` rows at that coordinate |
| C | `selectNearestSite` collapses the ±0.1° box result onto the fragment nearest the passed coordinate — with no notion of "…and that fragment has rows for the source being displayed" | `LocationMatch.kt:79`, called at `WeatherObservationsActivity.kt:547` |

The activity was created at **20:04:11**, inside window B. It captured `37.411,-122.095` and never
re-read it. Every subsequent reload collapsed onto that fragment, which had only ever received the
synthetic historical-actuals backfill rows — `OPEN_METEO_MAIN`, `SILURIAN_MAIN`, `TOMORROW_IO_MAIN`.
No NWS station pull ever ran there. `ObservationSourceMatcher.matchesObservationSource` correctly
rejects `*_MAIN` rows under NWS, so the list emptied and stayed empty until the activity was
recreated.

### 1b. The tell, for next time

A **same-process disagreement** between two readers of the same table at the same instant:

```
20:12:36  DailyActualsStore: getDailyActualsWithLiveToday: lat=37.416866 … todayObsRows=468  ← re-resolves per call
20:12:36  WeatherObservations: loadObservations: currentSource=NWS items=[]                   ← cached at onCreate
```

`DailyActualsStore` re-resolves location on every call and found 468 rows / 5 stations. The screen
did not. Any component that caches a coordinate for its lifetime can outlive that coordinate.
Recorded in memory as `observations_screen_stale_activelocation`.

### 1c. What is *not* the cause

Stating these explicitly because both are plausible and both are wrong, and one of them is already
written up as a plan:

- **Not "nothing was fetched."** Fetching more would have written rows at the *real* site
  (`37.417,-122.089`) — which the stale scope was already discarding. The screen would have stayed
  empty and re-fetched forever.
- **Not "the 24h window is too short."** No NWS row for that fragment exists at *any* age. Reaching
  further back in the DB returns the same empty set.

Both remain worthwhile as empty-state *policy* (§3, §4). Neither is the defect.

### 1d. Desktop is not affected — for the wrong reason

`ObservationsWindow.kt:255` calls `weatherDao.getRecentObservations(sinceMs)` with **no** location
box and **no** `selectNearestSite`. It cannot exhibit this bug because it does not scope at all —
which is the opposite defect (a stale neighbouring site's rows are shown as if current). Out of
scope here; noted so the parity rule is not applied blindly. §2's shared helper is written so
desktop can adopt scoping later without re-deriving the guard.

---

## 2. Fix A — re-resolve the location on every load *(primary)*

`activeLocation` becomes a function, not a field.

- Delete the `onCreate` assignment at `:120`; keep `appWidgetId`.
- Add `private suspend fun resolveLocation(): Pair<Double, Double>?` that performs the existing
  `widgetStateManager.getWidgetLocation(appWidgetId) ?: weatherRepository.getLatestLocation()`
  chain, and call it from `loadObservations` (`:539`), `loadBlendTable` (`:225`), and `refreshData`
  (`:309`) — all three read the cached field, so the **Blend tab is scoped to the same stale
  coordinate** and the manual refresh button **fetches at it**. That last one matters: during the
  incident, pressing refresh would have fetched for a coordinate the user had already left.
- `getWidgetLocation` is a prefs read (`WidgetLocationStore.stored` → `SharedPreferences.getFloat`),
  so this is cheap and already off the main thread in all three call sites.

**Why this and not "refresh the field in `onResume`":** the failing reloads were driven by
`observeCurrentObservationUpdates`'s DB flow, not by resumes. The activity was foreground and
resumed the whole time. A resume-scoped refresh would not have fired once during the eleven-minute
failure.

## 3. Fix B — a site collapse must not hand back an empty set *(defence in depth)*

Fix A closes the window that was hit. It does not close the class: any caller passing a coordinate
that is genuinely nearer a data-poor fragment gets the same silent emptying. This is the same shape
as `location_move_collapses_today_actuals`, where a 5-row stub at a new site beat a full site on
distance alone.

Add to `LocationMatch` (`:shared`, so both platforms and every future caller inherit it):

```kotlin
/**
 * Like [selectNearestSite], but skips sites that hold nothing the caller can use. Ranks sites by
 * distance as before and returns the rows of the nearest one with at least one row satisfying
 * [isUsable]; falls back to plain [selectNearestSite] when no site qualifies, so a caller with
 * genuinely no data still sees an empty list rather than a distant site's rows.
 */
fun <T> selectNearestSiteWith(
    rows: List<T>,
    lat: Double,
    lon: Double,
    latOf: (T) -> Double,
    lonOf: (T) -> Double,
    isUsable: (T) -> Boolean,
): List<T>
```

`loadObservations` passes `isUsable = { matchesObservationSource(it.stationId, currentSource) }`.
A fragment holding only synthetic `*_MAIN` rows then loses to a fragment holding real stations,
which is the correct ordering: `*_MAIN` is re-filed hourly *forecast*, not a measurement
(see `synthetic_backfill_hijacks_blend`).

**Bounded, deliberately:** candidate sites are still only those inside the existing ±0.1° box, so
the worst case is a ~7-mile-away site of real stations instead of a blank screen — and only when the
nearer site has literally nothing for that source. The plain `selectNearestSite` keeps its current
behaviour and its current callers; this is an additional entry point, not a change to the old one.

## 4. Fix C — permanent diagnostics on the load path

The existing line logs only the outcome, which is why this took a device pull to explain. Extend it
(same tag, same call site, `WeatherObservationsActivity.kt:599`) to log the inputs:

```
loadObservations: source=NWS loc=37.411,-122.095 boxRows=612 sites=[37.417/-122.089, 37.411/-122.095, …] siteRows=55 matched=0 items=[]
```

`loc` + `sites` + `matched` would each independently have identified this in one grep. Keep it
permanently, per the project's standing preference for load-bearing debug logging.

## 5. Empty-state policy — show older rows before fetching anything

Only reachable once §2–§3 are in: a *genuinely* empty 24h window for the displayed source.

- **Preferred (the "older observations from the DB" option):** drop the hard 24h cutoff for the
  empty case. Re-query without a `sinceMs` bound, take the newest rows for the source, and render
  them with an explicit age in the subtitle — `Latest: 3 days ago` — rather than a blank list.
  No network, no battery, and honest about staleness. Implement as a pure function in `:shared`
  (`rows + nowMs → (rowsToShow, subtitleKind)`) so desktop can adopt it verbatim.
- **Auto-fetch, if kept at all:** at most **once per screen open**, only when the unbounded query is
  *also* empty for that source, with a visible in-progress state and a real error message on
  failure. It must not be triggered from the debounced DB-change reload — that path fires on every
  write and would have retried forever during this incident. The 5-minute
  `CURRENT_TEMP_FRESHNESS_MS` gate described in the `-ds` plan is a rate limiter, not a loop
  guard; the loop guard is the once-per-open flag.
- **No cross-source fallback.** If NWS has nothing, say so for NWS; do not silently show another
  source's rows (`no_cross_source_fallback`).

---

## 6. Testing

Pure-function first, per the project's testing strategy — no mocking framework, and Robolectric has
no font engine, so nothing here should depend on rendering.

| Test | Location | Asserts |
|---|---|---|
| `selectNearestSiteWith` prefers a farther site with usable rows | `shared/…/LocationMatchSelectNearestSiteWithTest.kt` (new) | Nearest site holds only `*_MAIN`; farther site holds `KSJC` → returns the `KSJC` site |
| `selectNearestSiteWith` keeps nearest when both are usable | same | No regression of the distance ordering |
| `selectNearestSiteWith` returns empty when no site qualifies | same | A caller with no data never gets a distant site's rows |
| `selectNearestSiteWith` is identical to `selectNearestSite` when `isUsable` is always true | same | Contract parity with the existing helper |
| Regression: the exact incident | `app/…/WeatherObservationsActivityRobolectricTest.kt` (extend) | Seed both fragments (`37.417,-122.089` with 5 NWS stations, `37.411,-122.095` with 3 `*_MAIN`), point the widget at the excursion coordinate, assert the list shows 5 stations and the subtitle is *not* `obs_subtitle_none_found` |
| Regression: location moves while the activity lives | same | Load once at coordinate A, rewrite the widget prefs to coordinate B, trigger the DB-change reload, assert the list re-scopes — this is the test that fails on today's code (§2) and would have caught the incident |
| Empty-state fallback labels the age | `shared/…` (new, §5) | 0 rows in 24h + rows at 3 days → returns those rows and the "3 days ago" subtitle kind |

**Prove the regression tests can fail:** land them against unmodified `main` first and confirm both
go red, then apply §2/§3. A green-on-arrival test here proves nothing — the seeding is elaborate
enough that a mis-seeded fixture passes trivially.

Run: `./gradlew testDebugUnitTest --tests "*LocationMatch*" --tests "*WeatherObservations*"` and
`./gradlew :shared:test`.

## 7. Manual verification on the Fold

The incident is reproducible without waiting for a GPS excursion:

1. Note the current site from `weather_widget_prefs.xml` (`widget_lat_345`/`widget_lon_345`).
2. Open the Observations screen; confirm the five stations render.
3. With the screen still open, write a nearby-but-distinct coordinate into the widget prefs
   (~0.006° away, inside the ±0.1° box, outside `SAME_SITE_TOLERANCE_DEG`) and let a fetch land
   there.
4. **Before the fix:** the list stays populated (stale scope, opposite direction) and a fresh open
   at the new coordinate goes empty. **After:** both re-scope within one reload.
5. Confirm the new log line reports `loc=` matching the live prefs on every reload.

## 8. Order of work

1. §4 diagnostics (lands first — it makes every later step verifiable on-device).
2. §6 regression tests against unmodified `main`; confirm red.
3. §2 Fix A.
4. §3 Fix B + its `:shared` tests.
5. §5 empty-state policy, and update
   [`260815-observations-screen-auto-fetch-on-empty-ds.md`](260815-observations-screen-auto-fetch-on-empty-ds.md)
   to point at this plan for its premise.
