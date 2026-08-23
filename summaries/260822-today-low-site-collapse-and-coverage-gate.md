# Today's low read 66.5° instead of 57.0° — site collapse, cross-site clobber, coverage gate

**Date:** 2026-08-22
**Device:** Samsung SM-F936U1 (`RFCT71FR9NT`), daily forecast view, source = Tomorrow.io
**Status:** implemented, 3,431 unit tests green, **not committed**

---

## Report

> "daily forecast view: tomorrow api: today column, the thermostat was up high. The low didn't
> register?"

Correct on both counts. Today's observed low rendered as **66.52°** — the *noon* reading — instead
of **57.03°**, and it rendered in observed-red as a settled actual.

## Diagnosis

`TODAY_BAR_DEBUG` brackets it exactly:

```
18:49:49  obsHigh=70.30  obsLow=66.52  fHigh=74.39 fLow=56.89  trueHigh=74.39
18:59:10  obsHigh=70.02  obsLow=66.52
19:26:26  obsHigh=69.25  obsLow=66.52                            ← still wrong when observed
19:26:30  obsHigh=69.25  obsLow=57.03  fLow=56.43 trueHigh=74.88 ← self-repaired
```

**Root cause: a GPS site collapse.** Two excursions promoted new sites inside the same
neighbourhood — 18:27:08 → `37.416,-122.087` ("Permanente Creek Trail"), 18:40:58 →
`37.424,-122.088` ("Amphitheatre Parkway"), both `candidate_detected … updated=true`.
`ObservationDao.getObservationsInRange` collapses the ±0.1° box to the *nearest* site, so the read
switched to a stub holding only afternoon rows:

| site | rows | span | min |
|---|---|---|---|
| `37.417,-122.089` (home) | 40 | 00:00 → 19:26 | **57.03** |
| `37.424,-122.088` | 8 | 12:00 → 19:26 | 66.52 |
| `37.416,-122.087` | 7 | 12:00 → 18:00 | 66.52 |

`66.52` is literally the 12:00 observation. Recovery at 19:26:21 happened only because plugging in
fired `trigger=power_connected`, resampling back home.

This is a recurrence of the documented, previously unfixed
`location_move_collapses_today_actuals`.

### Three independent defects, all confirmed from device data

1. **Cross-site write clobber.** A recompute anchored at the stub wrote its truncated low onto the
   *home* row: `DAILY_HISTORY_OVERWRITE date=2026-08-22 src=TOMORROW_IO at=37.41682…
   low=57.03->66.52` at 18:41:58. Yesterday's row was collateral at 18:41:31
   (`low=60.66->67.69`). So the damage outlived the excursion.
2. **The self-heal is blind to a truncated start.** It ran and declined —
   `18:59:12 OBS_HOURLY_BACKFILL_SKIP reason=coverage_ok latest_gap_min=19 max_gap_min=10` —
   because it measures *gaps between consecutive rows*. A window beginning at noon has no gaps at
   all. Gap density structurally cannot see a late start.
3. **The Tomorrow.io fetch window was 6 hours.** `startTime=nowMinus6h`, annotated "core
   temperature/cloud fields are available six hours into the past on the free plan." Every
   `RECENT_HISTORY` row lands ~6–7 h after its own timestamp (00:00 fetched 06:44; 12:00 fetched
   18:00). **Home's full-day coverage was an artifact of ~12 fetches accumulating since midnight,
   not something one fetch could reproduce** — so a fresh site could never be healed.

### The tell for next time

`EXTREMA_WINDOW_DIAG` held `lo=57.84@06:47` correctly throughout — it is the station IDW blend,
which is not source-scoped the same way. **Blend correct + per-source `computedLowTemp` wrong ⇒
site collapse**, not blend math and not label placement.

## Decision

> If the low is missing for the current location → **backfill**.
> If backfill doesn't work, isn't available, or is still in flight → **fall back to the forecast low**.

Two invariants: never fabricate an actual; never settle for a forecast when a measurement is
retrievable. An earlier proposal to fall back to the forecast *first* was rejected — the real
measurement existed 800 m away, so a forecast would have been strictly worse. A pooling proposal
(union nearby sites on read) was written up and **not adopted**.

## Live API probe

`nowMinus23h` at 37.4168,-122.0890, `timesteps=1h`:

| startTime | earliest interval today | today rows | today min |
|---|---|---|---|
| `nowMinus6h` (old) | 14:00 | 10 (mostly future) | no overnight coverage |
| `nowMinus23h` | **00:00** | 24 | **57.43 @ 07:00** |

HTTP 200 both times. A second probe with the full production field list confirmed **temperature,
cloudCover, weatherCode, precipitationProbability and precipitationAccumulation are all non-null in
all 24 elapsed intervals**. The "six hours" annotation was wrong for every field, not just
temperature. 23 h, not 24: the plan rejects `startTime` more than 24 h back (403/403003).

## Changes

| file | change |
|---|---|
| `shared/…/actuals/TodayActualsCoverage.kt` | **new** — `dayStartUncovered()`: the day's rows must reach local midnight within a 60-min grace |
| `data/repository/DailyActualsStore.kt` | `sameSite` filter on the write path; today-low gate nulling `computedLowTemp`; `TODAY_LOW_UNCOVERED` log |
| `widget/handlers/HourlyObservationBackfill.kt` | `day_start_uncovered` repair trigger; site-scoped cooldown key |
| `shared/…/remote/TomorrowIoApi.kt` | `nowMinus6h` → `nowMinus23h` |
| `widget/handlers/CloudCoverViewHandler.kt` | pass site into the cooldown pre-check |

### Why the UI fix is one line of wiring

Commit `09701507` had already shipped the machinery:

```kotlin
val solidLow = actualLow ?: forecastLow                                    // :106
fun isLowTrackingActual(actualLow: Float?) { if (actualLow == null) return false }  // :224
```

Nulling `computedLowTemp` is the whole change — `DailyViewLogic` passes it as `actualLow`, the
thermostat then spans to the forecast low and the label renders white instead of observed-red. Same
treatment forecast-only sources (Open-Meteo) already get for today.

### Widening the window *was* the Tomorrow.io backfill

A newly promoted site's first ordinary fetch now stores today's 00:00→now hours as observations, so
coverage is restored passively — no separate trigger, no latency window, no extra API call. The
explicit `day_start_uncovered` trigger is therefore only load-bearing for NWS.

### Site-scoped cooldown cuts both ways

`"${source.id}_HOURLY_HISTORY_${quantize(lat)}_${quantize(lon)}"` — quantized to the same 3-dp grid
the coordinate-keyed tables use. A promoted site gets its own bucket and can heal immediately; GPS
jitter around one spot keeps sharing a bucket, so the cooldown still bounds retries. That is the
fetch-storm guard, and it falls out of quantizing rather than needing separate throttling.

## Tests — 3,431 total, 0 failures

New: `TodayActualsCoverageTest` (11), `DailyActualsStoreCrossSiteTest` (6).
Updated: `HourlyObservationBackfillCooldownTest`, `HourlyObservationBackfillLocationTest`,
`TomorrowIoApiTest`, `TomorrowIoDesktopServiceTest`.

**Every new behaviour was verified by reverting it and watching the test fail**, with the device's
own numbers:

| reverted | failure |
|---|---|
| `sameSite` write filter | `expected:<57.0> but was:<66.5>` |
| today-low gate | `expected null, but was:<66.5>` |
| `day_start_uncovered` | `expected repair, got coverage_ok latest_gap_min=0 max_gap_min=55` |

That last one reproduces the device's real `coverage_ok latest_gap_min=19 max_gap_min=10`, so the
synthetic case exercises the blind spot faithfully.

Two pre-existing tests pinned the *bug* (`hourly request asks for the bounded six-hour lookback`,
and its desktop twin). Both were correct tests of intended behaviour when written — the six-hour
claim came from the API docs, not from a measurement. The desktop one failing was useful: it proved
both platforms share `:shared`'s `TomorrowIoApi`, so the fix landed on desktop for free.

## Deliberate scope limits

- **The stub still gets its own truncated `daily_history` row.** The `sameSite` filter stops the
  clobber; it does not stop the stub existing. Pinned by a test so it is not later misread as a
  regression — the display is handled by the coverage gate instead.
- **Only the low is gated.** A late start under-reports the high rather than fabricating it, so the
  high degrades gracefully. Worth deciding separately.
- **Promotion gating (defect #2) not done.** `LocationHandoffPolicy.evaluateCandidateUsability`
  still gates on forecast coverage only, and still returns before the `MOVING_GRACE_MS` check. With
  the UI now correct whether or not a stub is promoted, this drops from a correctness fix to a
  quality issue about site thrashing.

## Not done

- **No device verification.** Nothing installed on the Samsung. Watch for `TODAY_LOW_UNCOVERED` and
  `day_start_uncovered` in `app_logs`.
- **Not committed.**

## Gotcha worth keeping

`strftime('%s','2026-08-22 18:00:00')` parses that literal as **UTC**, so naive local-time filters
on `app_logs.timestamp` shift by the UTC offset (−7 h here) and silently return the wrong window.
Use `strftime('%s','…','utc')`, or just `ORDER BY timestamp DESC LIMIT n`.

## Plans

- [plans/260822-today-low-backfill-then-forecast-fallback.md](../plans/260822-today-low-backfill-then-forecast-fallback.md) — the decided design
- [plans/260822-fix-cross-site-actuals-clobber.md](../plans/260822-fix-cross-site-actuals-clobber.md) — defect #1
- [plans/260822-today-actual-low-discarded-by-site-collapse.md](../plans/260822-today-actual-low-discarded-by-site-collapse.md) — superseded pooling candidate, retained for the alternatives analysis
