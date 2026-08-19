# Today-column station row drops, and the opportunistic loop that stopped running (2026-08-19)

## Report

Samsung (SM-F936U1, widget 345):

1. "dominate station not reporting on daily forecast view, today column" — the Today column shows
   only the `-5.2 fcst` delta row; the station temperature row is absent.
2. "hourly forecast view updated, probably because I plugged in. Shouldn't require a plugin."
3. "stale temperature display for dominate temperature on hourly view. Was reporting 73.4 temp when
   temp was 71.6. This was around 3 pm to past 3:30 pm." — and the *correct* reading was visible on
   the Observations activity at the same time.

## Findings

### 1. Today column: `observed_at_skew`

`observedAt` is derived TWICE by different code over different inputs, and the consumer requires
exact equality:

- **Producer** — `WidgetRenderer.kt:302` resolves it via `CurrentTempResolver` over the current-temp
  resolution window (`roundedNow-12h … roundedNow+3h`, `lookaheadHours = 3`).
- **Consumer** — `DailyGraphRenderer.buildTodayOverlayData` hands that timestamp to
  `TodayColumnOverlayContentResolver.resolveAt`, which **recomputes the entire blend** from a
  36-hour observation query (`OVERLAY_OBSERVATION_LOOKBACK_MS`) with `lookaheadHours = 2`, then:

  ```kotlin
  val dominant = details?.takeIf { it.observedAt == observedAt }?.dominantContribution
  ```

Caught live on-device with the new `dominantNullReason` breadcrumb:

```
16:10:54  observedAt=1787179620000 (15:47)  dominantNullReason=observed_at_skew(derived=1787175600000 = 14:40)  obsRows=1581
16:11:01  observedAt=1787175600000 (14:40)  dominantNullReason=null  stationId=KNUQ  obsRows=1583
```

The delta row survives because the caller passes it in; only the station rows depend on the
recompute. So the failure is silent and looks like "the station stopped reporting".

**Why the two windows disagree**: `candidateTimes` is the set of distinct raw observation
timestamps, and the lone-station rule refuses to emit a timestamp covered by a single
non-dominant station. Feeding 36 h instead of 12 h changes `dominantStationByDay`, which changes
which tail timestamps are emitted at all. On 2026-08-19 the newest row was KPAO 15:47 (web
fallback): emitted under the narrow window, skipped under the wide one, which fell back to 14:40.

Frequency (`TODAY_OVERLAY`): Aug 17 → 0/63 dropped, Aug 18 → 1/88, Aug 19 → 8/34 (24%).

### 2 & 3. The opportunistic loop has stopped running

`CURR_FETCH_START` by reason:

| Date | charging_loop | opportunistic_job | user-initiated |
|---|---|---|---|
| Aug 17 | 100 | 5 | 0 |
| Aug 18 | 97 | 2 | 3 |
| **Aug 19** | **68** | **0** | 2 |

On battery the charging loop does not slow down, it **stops**
(`CurrentTempFetchPolicy.shouldFetchNow` ends in `return isCharging`; `postRunLoopAction` then
returns `NO_RESCHEDULE`):

```
14:29:20  CURR_FETCH_SKIP       reason=charging_loop policy_blocked charging=false battery=77 cutoff=65
14:29:20  CURR_FETCH_LOOP_STOP  reason=policy_blocked action=no_reschedule
          ── 1h42m, no observation fetch ──
16:11:02  CURR_FETCH_START      reason=charging_loop   ← plugged in
```

The battery-side replacement is `OpportunisticUpdateJobService` (periodic 45 min, battery > 65%).
It ran zero times. **Suspected cause** — `onStartJob` skips its entire body when the process is
younger than `STARTUP_GRACE_PERIOD_MS = 15_000`:

```kotlin
val processAgeMs = WeatherWidgetApp.processAgeMs(...)
if (processAgeMs < STARTUP_GRACE_PERIOD_MS) { jobFinished(params, false); return false }
```

`processStartElapsedRealtime` is set in `Application.onCreate`. When JobScheduler **cold-starts the
process in order to run this job** — the normal case on a Samsung that aggressively kills it —
`onStartJob` runs a second or two after `onCreate`, so the guard fires every time and the job
defeats its own purpose. That matches the decline 5 → 2 → 0 as the process is killed more often.

"Suspected" because **the entire class logs only to `android.util.Log`, never to `app_logs`**, so
there is no persisted evidence of whether it fires, is skipped, or was never scheduled. That is the
gap the user asked to close first.

### 3 (cont). The stale `knuq 73.4°` label

`observations.fetchedAt` is **last completed fetch attempt**, not when a row arrived — `INSERT OR
REPLACE` refreshes it on every re-fetch (see `observations_fetchedat_attempt_semantics`). So it
cannot be used to reconstruct what the app held at a past instant, and the first reading of this
("the 71.6 rows didn't exist yet") was unfounded. The user saw the correct value on the
Observations activity at the time, so the data was present and the widget's hourly label was
genuinely stale.

Two contributing facts, both worth keeping in mind:

- KNUQ reports whole **Celsius**: 71.6 °F = 22.0 °C, 73.4 °F = 23.0 °C exactly. The station
  alternated 22/23 all afternoon, so this is a one-tick change, not drift.
- The blended observed curve extrapolates forward in time with no new data (`obs` moved
  73.65 → 72.29 with zero new rows), while the dominant-station label reports that station's **raw**
  last reading. During a fetch drought the two visibly diverge — by design, but indistinguishable
  from a bug without a breadcrumb saying which rows the render actually saw.

## Changes

### A. DB logging (do first — the user asked for this explicitly)

1. `OpportunisticUpdateJobService` — persist to `app_logs` under `OPPORTUNISTIC_JOB`: schedule
   attempts (success/failure/cancel + battery), job start, and every early-return reason
   (`battery_cutoff`, `startup_grace`, `no_recent_hourly`). Without this the loop's death is
   invisible.
2. Dominant-station render breadcrumb — persist what the blend actually saw at render time
   (dominant station, its raw reading + reading time + age, and the newest observation timestamp
   per station), so "widget stale vs Observations screen fresh" is answerable from the DB.

### B. Today-column station drop

Resolve the overlay over the **same** window that produced the `observedAt` it is handed, rather
than a second, wider one. The overlay annotates exactly the current observation the header shows,
so the current-temp resolution window is the correct one; the equality check then stays as a
genuine safety net instead of a coin flip.

### C. Opportunistic loop

Stop the startup-grace guard from discarding the run. A job that cold-started the process is the
normal case, not churn.

## Status

- [x] `dominantNullReason` breadcrumb (landed during diagnosis)
- [x] A1 opportunistic job DB logging (`OPPORTUNISTIC_JOB`)
- [x] A2 dominant-station render breadcrumb (`DOMINANT_STATION`)
- [x] B today-column window alignment
- [x] C startup-grace guard

## Verification (on device, SM-F936U1)

The startup-grace hypothesis is confirmed outright by the new breadcrumb — the job fires ~0.1 s
after process start, which the old guard rejected outright:

```
OPPORTUNISTIC_JOB  outcome=scheduled intervalMin=45 charging=true battery=73
OPPORTUNISTIC_JOB  outcome=fetch_enqueued charging=true battery=73 cutoff=65
                   processAgeMs=104 hasRecentHourly=true uiRepaint=skipped startupGrace=true
```

Today column, four consecutive renders after the fix (was `observed_at_skew` before):

```
TODAY_OVERLAY  observedAt=1787180400000 delta=-4.9 dominantTemp=73.4°
               dominantNullReason=null stationId=KNUQ
```

The new hourly breadcrumb immediately earned its keep, showing the staleness shape the user
reported — the named station's reading is older than the freshest row on hand:

```
DOMINANT_STATION  station=KNUQ rawTemp=71.6 weightShare=0.63
                  readingAgeMin=34 newestObsAgeMin=19 obsRows=3351 text=knuq 71.6° @ 4:05 pm
```

Tests: `:shared` and full `:app` unit suites green; new
`TodayColumnOverlayObservedAtSkewTest` (3 cases) proven to fail when the windows are realigned.

## Not done

- The `readingAgeMin` vs `newestObsAgeMin` gap above is real and unaddressed: the dominant-station
  label can name a station whose raw reading is materially older than the curve it annotates, with
  no visual staleness treatment below the blend's 3 h zero-weight cutoff. Deliberately left alone —
  it is a display-policy question, not a defect.
- `personal_station_discount=95` means a PERSONAL station 1.5 km closer and 15 min fresher (AW020)
  loses to KNUQ at 3.8 km. Working as configured; noted because it is why the named station lags.
