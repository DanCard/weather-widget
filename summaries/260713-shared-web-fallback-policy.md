# Shared web-fallback policy (top 3 stations, all platforms)

## Symptom

Desktop showed a stale KPAO reading (17:47, 71.6°F) while Samsung showed 18:47 / 73.4°F.

## Root cause

The "NWS API observation is stale → fall back to the Synoptic web source" rule existed on **both**
platforms, but as duplicated logic with **divergent station limits**:

| Platform | Gate | Source |
|---|---|---|
| Android | `index < MAX_WEB_FALLBACK_STATIONS` (= 3) | `ObservationRepository.kt:324`, `:457` |
| Desktop | `index < 2` (hardcoded) | `DesktopWeatherService.kt:357` |

The NWS station list for this location is ordered by distance:

| index | station |
|---|---|
| 0 | AW020 |
| 1 | KNUQ |
| **2** | **KPAO** |
| 3 | LOAC1 |
| 4 | KSJC |

KPAO sits at index 2 — inside Android's window (`< 3`), outside desktop's (`< 2`). So when the NWS
API's newest KPAO ob went >1h stale, Android fell back to Synoptic and got the fresh reading;
desktop never even attempted the fallback and kept serving the stale API value.

Evidence: Samsung `app_logs` had `NWS_STATION_SYNOPTIC_FALLBACK station=KPAO reason=stale` 81 times
in 24h (including 19:11:32, one second before it stored the 18:47 row). Desktop logged zero — its
fallback path is `Log.i` only, never persisted, which is why the silence was invisible.

Note this was *not* a poll-timing race: both platforms poll ~10 min, and the live API did have the
18:47 ob. Samsung got it from the **web fallback**, not from a luckier poll.

## Change

1. **New `:shared` policy** — `shared/src/main/kotlin/com/weatherwidget/shared/observations/ObservationFallbackPolicy.kt`:
   single `MAX_WEB_FALLBACK_STATIONS = 3`, the 1-hour staleness predicate, and the `empty`/`stale`
   reason string. Pure functions (epoch millis in, boolean out) — no platform deps, unit-testable.
2. **Three call sites delegate to it**: Android daily backfill + hourly, desktop. The hardcoded `2`
   is gone; the limit is now one constant for all platforms.
3. **Desktop gains a durable `NWS_STATION_SYNOPTIC_FALLBACK` app_logs row** (matching Android's tag)
   so the fallback is verifiable in the DB instead of vanishing into stdout.

## Verification

- Unit tests for the policy (boundary: index 2 allowed, index 3 not; exactly-1h staleness edge).
- After restart, desktop `app_logs` should show `NWS_STATION_SYNOPTIC_FALLBACK station=KPAO`, and
  desktop's newest KPAO ob should track Samsung's.
