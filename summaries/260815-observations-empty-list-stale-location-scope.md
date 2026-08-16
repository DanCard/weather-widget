# "No recent observations found" while five NWS stations sat in the DB

**Date:** 2026-08-15
**Device:** Samsung Fold (SM-F936U1, `RFCT71FR9NT`), Current Observations screen, widget id **345**
**Request:** "samsung: on current observations screen: says 'no recent observations found'. Instead
should fetch observations. What do you think?" → then "Maybe instead of fetching recent observations
phone should fetch older observations from db?"
**Plan:** [plans/260815-observations-empty-list-stale-location-scope-opus.md](../plans/260815-observations-empty-list-stale-location-scope-opus.md)
**Commit:** *(uncommitted — change set is in the working tree)*

Implemented, tested, and installed on both devices.

## Neither idea was the fix — the observations were already there

The screen showed `No recent observations found for NWS.` through eleven automatic reloads over
eleven minutes while the DB held five NWS stations at the user's actual site, freshly fetched:

```
AW020  KNUQ  KPAO  LOAC1  KSJC   @ 37.417,-122.089   newest obs 19:50, fetched 20:09
```

Back + relaunch, same DB, forty-five seconds later:

```
20:14:38  loadObservations: currentSource=NWS items=[]
20:15:23  loadObservations: currentSource=NWS items=[AW020, KNUQ, KPAO, LOAC1, KSJC]
```

So auto-fetching would have written rows at the real site that the screen was already discarding —
retrying forever. Reaching further back in the DB would have returned the same empty set, since
nothing NWS ever existed at the scoped-to fragment at *any* age.

### Root cause: three mechanisms, none a bug alone

| | Mechanism |
|---|---|
| A | `activeLocation` resolved **once** in `onCreate` and reused by every later reload |
| B | A transient GPS excursion minted a second fragment, `37.411,-122.095`, live 19:41–20:04 |
| C | `selectNearestSite` collapses the ±0.1° box onto the nearest fragment — with no notion of "…and that fragment has rows for the source being displayed" |

The activity was created at **20:04:11**, inside window B. It captured the excursion coordinate and
never re-read it. That fragment had only ever received the synthetic `<SOURCE>_MAIN` backfill rows
(`OPEN_METEO_MAIN`, `SILURIAN_MAIN`, `TOMORROW_IO_MAIN`) — no NWS station pull ever ran there — and
`ObservationSourceMatcher` correctly rejects `*_MAIN` under NWS, so the list emptied and stayed
empty for the activity's lifetime.

### The tell

A same-process disagreement between two readers of the same table at the same instant:

```
20:12:36  DailyActualsStore: … lat=37.416866 … todayObsRows=468   ← re-resolves per call
20:12:36  WeatherObservations: … items=[]                          ← cached at onCreate
```

## What changed

**Fix A — the actual bug.** `activeLocation` is gone as a field; `resolveLocation()` runs on every
load. That repairs all three consumers at once: the stations list, the Blend tab, and the Refresh
button — which had been *fetching* for a coordinate the user had already left. `onResume` would not
have been enough; every failing reload came from the DB flow with the activity foreground.

**Fix B — defence in depth.** New `LocationMatch.selectNearestSiteWith(...)` in `:shared`: same
distance ranking, but it skips sites where every row fails the caller's predicate, falling back to
plain `selectNearestSite` when none qualifies. A fragment of synthetic `*_MAIN` rows can no longer
win on distance and empty the list. Additive — existing callers untouched.

**Fix C — permanent diagnostics.** The load line now logs its inputs. From the Fold after install:

```
loadObservations: currentSource=NWS loc=37.416866,-122.089066 boxRows=1099
  sites=[37.417/-122.089, 37.489/-122.089, 37.345/-122.089, 37.417/-121.999, 37.417/-122.179, 37.411/-122.095]
  siteRows=692 staleAgeMs=none items=[AW020, KNUQ, KPAO, LOAC1, KSJC]
```

The excursion fragment is right there in `sites` — one grep would have identified this.

**Fix D — the "older rows from the DB" idea, as empty-state policy.** New shared
`StaleObservationFallback`. When the 24h window is genuinely empty for the displayed source, the
screen re-queries unbounded and renders what it has with an age: *"No recent NWS observations —
showing the latest, 3d ago."* No network, no cross-source substitution, and deliberately **not** a
fetch trigger.

## Testing

Three new Robolectric tests landed against unmodified `main` first and confirmed red; the excursion
one reproduced the incident exactly (`expected:<[AW020, KNUQ]> but was:<[]>`), with all 19
pre-existing tests in that class still green. Plus pure `:shared` coverage for both new helpers.

With the fix: **app 1933 tests, shared 826 tests, 0 failures.**

## Notes worth keeping

- **`LocaleResourceParityTest` catches new base strings with no translations.** The one new string
  (`obs_subtitle_stale`) needed adding to all 19 `values-*` files. Its age token (`3d`, `6h`,
  `45min`) stays untranslated, matching what `ForecastHistoryActivity.formatRelativeTime` already
  emits.
- **`WeatherObservationsActivity` is `android:exported="false"`**, so `adb shell am start` on it is
  refused by One UI (`Permission Denial: … not exported from uid`). To open it from a script, tap the
  thermometer icon in the widget header instead (`setupWeatherStationsShortcut`) — on the Fold's home
  screen that was `input tap 834 233`.
- **Screenshots from this device need the warning header stripped**, not just a JPG conversion:
  `screencap -p` prepends a multi-display warning, so `convert` fails outright. Seek to the `\x89PNG`
  magic first, then convert.
- **Desktop cannot hit this bug, for the wrong reason.** `ObservationsWindow.kt:255` calls
  `getRecentObservations(sinceMs)` with no location box and no site collapse — it does not scope at
  all, which is the opposite defect. Left alone deliberately; `selectNearestSiteWith` is in `:shared`
  so it can adopt scoping later without re-deriving the guard.
- **Deliberately not built:** the narrow once-per-open auto-fetch. The stale fallback covers the real
  cases; adding a fetch trigger can wait until something shows it is still needed.
