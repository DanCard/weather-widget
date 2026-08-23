# Today's low: backfill when missing, fall back to forecast when it can't be had

**Date:** 2026-08-22
**Status:** DECIDED (Danny, 2026-08-22). Supersedes the pooling candidate in
[260822-today-actual-low-discarded-by-site-collapse.md](260822-today-actual-low-discarded-by-site-collapse.md).

## The rule

> If the low is missing for the current location → **backfill**.
> If backfill doesn't work, isn't available, or is still in flight → **fall back to the forecast low**.

Two invariants fall out of that ordering, and both matter:

1. **Never fabricate an actual.** A truncated window's minimum is "lowest since we started
   watching," not the day's low. It must never render as a settled observed value.
2. **Never settle for a forecast when a measurement is retrievable.** The forecast is the fallback,
   not the answer.

## What's broken today

Samsung, 2026-08-22 ~19:08, Tomorrow.io. Today column bottomed at **66.52°** — the noon reading —
instead of **57.03°**. GPS promoted `37.424,-122.088` at 18:40:58; observations at that coordinate
start at 12:00; the read collapses to the nearest site, so the day's "low" became the earliest
afternoon row and rendered as a settled actual in observed-red.

Neither half of the rule above is currently implemented: nothing detects that the low is missing,
and nothing prevents the truncated minimum from being presented as real.

## Design

### Step 1 — detect "the low is missing for the current location"

A new predicate, evaluated **per source** (each source has its own observation rows), in `:shared`
as a pure function so Android and desktop share it and it unit-tests without Room or GPS.

The rule: today's earliest observation for this source at this site must reach back to local
midnight (within a small grace, ~60 min). If it does not, the day's low has not been observed.

Precedent for both the shape and the placement: `pastDayLacksAfternoonCoverage`
(`app/src/main/java/com/weatherwidget/data/repository/DailyActualsStore.kt:35`) with its own
`PastDayCoverageTest`.

**This is not the existing check.** The current self-heal measures *gaps between consecutive
observations* and declined during the whole incident:

```
18:59:12  OBS_HOURLY_BACKFILL_SKIP source=NWS reason=coverage_ok latest_gap_min=19 max_gap_min=10
19:26:22  OBS_HOURLY_BACKFILL_SKIP source=NWS reason=coverage_ok latest_gap_min=16 max_gap_min=15
```

The stub's 8 rows ran 12:00→19:26 evenly spaced — zero gaps, therefore "healthy." A window that
starts at noon is perfectly dense over the half-day it covers. Gap density cannot see a truncated
*start*; that is the entire blind spot.

### Step 2 — backfill

On detecting an uncovered day start, request today's history for the **current** coordinate.

**NWS — works today.** `NwsObservationBackfiller` already takes `lookbackHours`
(`app/src/main/java/com/weatherwidget/data/repository/NwsObservationBackfiller.kt:173`) and pulls
`/stations/{id}/observations`, which serves the full day from the same station (KNUQ here) whatever
coordinate you ask from. Only the trigger needs fixing.

**Tomorrow.io — RESOLVED, widen the window (probe below).**
`shared/src/main/kotlin/com/weatherwidget/data/remote/TomorrowIoApi.kt:43` sends
`startTime=nowMinus6h`, commented "core temperature/cloud fields are available six hours into the
past on the free plan." Verified against stored rows — every `TOMORROW_IO_RECENT_HISTORY` row lands
~6–7 h after its own timestamp (00:00 fetched 06:44; 11:00 fetched 17:00; 12:00 fetched 18:00).

**Home's full-day coverage is an artifact of ~12 fetches accumulating since midnight, not something
one fetch can reproduce.** A fresh site fetched at 18:41 reaches back only to ~12:00 → 66.52.

> **PROBE RUN 2026-08-22 — RESOLVED: widen to `nowMinus23h`.** Live GET at 37.4168,-122.0890,
> `fields=temperature`, `timesteps=1h`:
>
> | startTime | today's earliest interval | today rows | today min |
> |---|---|---|---|
> | `nowMinus6h` (current) | 14:00 | 10 (mostly future) | no overnight coverage |
> | `nowMinus23h` | **00:00** | 24 | **57.43 @ 07:00** |
>
> HTTP 200 both times, `temperature` populated throughout the 23 h window — no 403, no null fields.
> The 57.43 matches the stored 57.03 @ 06:47 (1 h timestep granularity accounts for the delta).
> **A single `nowMinus23h` fetch covers today's overnight low, so Tomorrow.io backfill works and
> the forecast fallback is not needed for this case.** The `TomorrowIoApi.kt:43` comment ("core
> temperature/cloud fields are available six hours into the past on the free plan") is wrong for
> temperature — correct it when widening the constant. Re-probe cloudCover separately before
> assuming the same holds for it.
>
> Original framing, retained: Memory `tomorrow_io_24h_history_limit` says the
> plan permits `startTime` up to 23 h back; the code comment says 6 h for core fields on the free
> plan. Both may be true (policy vs field availability). Ask for `nowMinus23h` at the real
> coordinates and check whether `temperature` comes back populated at the far end, null, or 403.
> - Populated → widen the constant; Tomorrow.io backfill works; this leg is done.
> - Capped at 6 h → Tomorrow.io can never recover today's overnight low, and it takes the
>   forecast-fallback path permanently for this case. That is a correct outcome under the rule, not
>   a failure.

**Fix the cooldown key while here.** The sparse-history self-heal keys on
`"${displaySource.id}_HOURLY_HISTORY"` with **no site component**, so a heal at the old site
suppresses the new site's for 30 min — which would defeat this backfill on exactly the
move-to-a-new-site case it exists for. Add the site to the key.

**Guard against jitter-driven fetch storms.** Two promotions happened 14 min apart during this
incident (18:27:08, 18:40:58). A per-site cooldown plus the existing `api_usage_stats` accounting
should bound this; do not let every `candidate_detected` trigger an unconditional pull.

### Step 3 — fall back to the forecast low

While backfill is pending, or when it fails or is unavailable for the source, pass
**`actualLow = null`**.

Everything downstream already exists as of commit `09701507`:

```kotlin
// shared/src/main/kotlin/com/weatherwidget/shared/util/DailyDayValueResolver.kt
val solidLow = actualLow ?: forecastLow                    // :106
fun isLowTrackingActual(actualLow: Float?) { if (actualLow == null) return false }   // :224
```

Result: the thermostat spans down to the forecast low, and the label renders white rather than
observed-red. No new rendering work, no new visual language — this is the same treatment
forecast-only sources (Open-Meteo) already get for today.

Because the fallback applies *immediately* on detecting missing coverage, there is **no window
where a wrong number is displayed** while the backfill is in flight. The column upgrades from
forecast-low to observed-low when the backfill lands.

## Prerequisite

Defect #1, the cross-site write clobber —
[260822-fix-cross-site-actuals-clobber.md](260822-fix-cross-site-actuals-clobber.md). A recompute
anchored at a stub currently writes the stub's truncated low onto the home row
(`DAILY_HISTORY_OVERWRITE … at=37.41682… low=57.03->66.52`, 18:41:58). Land that first, or a
successful backfill can still be overwritten by a stale-anchored recompute.

## Tests

**Unit — the coverage predicate** (`:shared`)
- Rows from 00:00 → covered.
- Rows from 12:00 (the incident's stub span) → not covered.
- Empty → not covered.
- First row at midnight + 45 min → covered (inside grace); + 90 min → not covered.
- Evaluated shortly after midnight, rows from 00:05 → covered (must not demand pre-midnight rows).

**Integration — `DailyActualsStore` + `DailyHistoryDao` + `ObservationDao` on in-memory Room**
- Seed the incident: home site full day, stub site 12:00-onward, anchor at the stub. Assert the
  today actual low surfaces as `null` (not 66.52) and that a backfill is requested for the stub.
- After a simulated successful backfill populating the stub from 00:00, assert the low resolves to
  the real value and no fallback is used.
- Assert the backfill is *not* re-requested inside the cooldown for the same site, and *is*
  requested for a different site (pins the cooldown-key fix).

**Renderer (Robolectric)** — a today column with `actualLow = null` spans to the forecast-low Y and
labels white, not observed-red. Assert dp geometry, not fonts (memory `robolectric_no_font_engine`),
and prove the test fails without the change.

## Explicitly out of scope

- **Pooling observations across nearby sites.** Superseded by this decision; the candidate write-up
  is retained for context only.
- **Promotion gating** (`LocationHandoffPolicy.evaluateCandidateUsability` is observation-blind).
  Still a real defect and still worth fixing, but it is now a quality issue about site thrashing
  rather than a correctness fix — this plan makes the UI correct whether or not a stub gets promoted.
- **The same treatment for today's high.** A truncated window under-reports the high rather than
  fabricating a low, so it is less harmful. Worth deciding separately.

## Related

- Memory: `location_move_collapses_today_actuals`, `feedback_recover_real_data_not_fallback`,
  `tomorrow_io_24h_history_limit`, `historical_actuals_provenance`
- Commit `09701507` — the forecast-low fallback machinery this plan reuses
