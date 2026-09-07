# The daily path never got the api-scoped observation read, and runs a backfill probe inside the click

**Date:** 2026-09-07
**Status:** Done. Tiers 1, 2 and 4 implemented, tested and verified on device; Tier 3
deliberately deferred. See [Outcome](#outcome-2026-09-07-same-device-same-session) at the end.

Sixth in the Samsung tap-latency thread, after
[the read-cost investigation](260906-samsung-tap-latency-observation-read-cost.md),
[the api-scoped read](260906-scope-observation-read-by-api-and-bound-paint-concurrency.md),
[the settled-day skip](260906-thin-personal-stations-and-skip-settled-day-recomputes.md),
[dropping non-displayable history rows](260906-drop-provider-only-daily-history-rows.md) and
[the click-gap attribution](260906-attribute-the-click-gap-and-extend-the-deferred-actuals-fast-path.md).

All figures from SM-F936U1 (`RFCT71FR9NT`), build 26090601, 3 widgets (345, 349, 352), 89,489
`observations` rows. Captured live from logcat 05:40–05:57 while the user drove the widget by hand.
The `INTERACTION_E2E` instrumentation built yesterday is what made this readable, and it worked:
every reported symptom below has a number attached.

## What the user reported, and what the device recorded

| report | action | measured |
|---|---|---|
| "hourly graph → daily took like 3 seconds" | `ACTION_SET_VIEW` → DAILY | **1,343 / 1,355 / 1,621 / 1,766 ms** |
| "very slow clicking through different APIs" | `ACTION_TOGGLE_API` | **727–1,307 ms**, mean ~940 ms over 8 taps |
| "on Silurian, next took several seconds, maybe didn't register" | see [Was a click dropped?](#was-a-click-dropped) | no drop; **1.3 s of dead time**, then a double-advance |

The reverse direction is half the cost: `ACTION_DAY_CLICK` (daily → hourly) is **537 / 548 / 644 ms**.
That asymmetry is the whole finding — the hourly view got the api-scoped read on 2026-09-06 and the
daily view did not.

## Where a daily paint goes

`ACTION_SET_VIEW` at 05:49:40, `e2e=1343ms queue=1ms`:

```
DAILY_INTERACTION_PERF totalMs=1324
├─ dailyDataMs        302
├─ gapFill 3 · snapshots 20 · hourly 22 · observations 57 · currentHourly 5 · currentResolve 22
└─ renderMs           893   ← WIDGET_RENDER_PERF totalMs=893
   ├─ resolveMs         1
   ├─ prepareMs        87
   ├─ renderMs          7
   └─ UNTIMED         798   ← of which OBS_RANGE_READ = 512 ms
```

`WIDGET_RENDER_PERF`'s own timers cover **95 ms of the 893 ms it reports** — 11%. This is the
pattern the previous plan predicted in its verification section: *"a large residual is itself the
finding."* It has now held six times in a row.

## Finding 1 — a background-maintenance probe runs synchronously inside the click

`DailyViewHandler.maybeBackfillIncompleteHistory` (`DailyViewHandler.kt:583-610`) reads **72 hours of
observations, unscoped**, and its only product is a decision about whether to *enqueue a background
worker*. Nothing it returns is drawn.

```kotlin
val graphStart = now.minusHours(WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS)  // 72
val observations = repository.getObservationsInRange(minEpoch, maxEpoch, lat, lon)       // apis=ALL
maybeEnqueueHourlyObservationBackfill(..., observations = observations, ...)
```

Measured cost of that one call, same device, same location, same afternoon:

| time | span | total | sql | candidates | merged | apis |
|---|---|---:|---:|---:|---:|---|
| 05:49:41 | 72h | 512 ms | 455 | 37,529 | 7,231 | ALL |
| 05:55:34 | 72h | 486 ms | 435 | 37,511 | 7,218 | ALL |
| 05:55:18 | 72h | **3,185 ms** | 2,892 | 37,511 | 7,218 | ALL |
| 05:42:53 | 72h | **8,325 ms** | 7,699 | 37,551 | 7,241 | ALL |

The gate explains a symptom the user noticed without naming it:

```kotlin
displaySource == WeatherSource.NWS && !centerDate.isBefore(today.minusDays(visibleDays))
```

**It only fires on NWS.** And that is exactly what the toggle timings show — the four-source cycle,
twice around:

| source | `TOGGLE_API_SLOW` | 72h probe read? |
|---|---:|---|
| OPEN_METEO | 895 ms / 727 ms | no |
| SILURIAN | 908 ms / 909 ms | no |
| TOMORROW_IO | 843 ms / 736 ms | no |
| **NWS** | **1,273 ms / 1,010 ms** | **yes — 486 ms / 492 ms** |

NWS costs ~350 ms more than its neighbours, and the probe is the entire difference.

## Finding 2 — the daily path never got the api-scoped read

`ObservationDao.getObservationCandidatesInRangeForApis` exists, and its KDoc already carries this
measurement from 2026-09-06:

> one 132h window held 34,726 SYNOPTIC rows (70%, and 1.35 MB of text) against 7,862 NWS rows — all
> of the former read off a cold disk and dropped by the first filter in the blend.

Of the twelve `getObservationsInRange` call sites under `widget/`, **exactly one passes `apis`** —
`TemperatureStateResolver.kt:650`, the hourly path. Every daily-path site is unscoped:

| call site | window | scoped? |
|---|---|---|
| `DailyViewHandler.kt:341` header yesterday-delta | 36h | no |
| `DailyViewHandler.kt:595` backfill probe | 72h | no |
| `DailyGraphRenderer.kt:414` overlay fallback | 36h | no |
| `DailyGraphRenderer.kt:552` `loadTodaySourceObservations` | today | no — **then `.filter { it.api == displaySource.id }` in Kotlin** |
| `WidgetRenderer.kt:415`, `CurrentTempResolver.kt:34`, `TemperatureStateResolver.kt:599` | various | no |
| `TemperatureStateResolver.kt:650` | various | **yes** |

`loadTodaySourceObservations` is the starkest: it reads every API's rows across the CursorWindow and
then discards all but one source's in memory — the precise thing the scoped query was added to stop.

The A/B is already in the log, ninety seconds apart on the same database:

```
05:42:46  spanH=132  total=613ms   sql=568   candidates=4,911   apis=NWS|Generic
05:42:53  spanH=72   total=8,309ms sql=7,695 candidates=37,551  apis=ALL
```

**Nearly twice the window, 8× fewer candidate rows, 13× faster.** Row counts for the last 72 h on
this device say why:

| api | rows | |
|---|---:|---|
| SYNOPTIC | 27,218 | 73% |
| NWS | 4,847 | |
| OPEN_METEO | 3,572 | |
| METAR | 1,413 | |
| TOMORROW_IO | 461 | |

A widget displaying NWS reads 27,218 SYNOPTIC rows off disk to throw all of them away. (SYNOPTIC is
not dead weight in general — this device has `actuals_provider_SILURIAN = SYNOPTIC` — but it is dead
weight on every paint that is not showing Silurian.)

## Finding 3 — the same read runs concurrently, several times, and contends with itself

At 05:42:53, two threads finished **byte-identical reads 14 ms apart**:

```
05:42:53.775  10480 10651  spanH=72 total=8309ms candidates=37551 merged=7241 apis=ALL
05:42:53.789  10480 12099  spanH=72 total=8325ms candidates=37551 merged=7241 apis=ALL
```

and again three seconds later:

```
05:42:56.806  10480 10651  spanH=36 total=2628ms candidates=15758 merged=3148 apis=ALL
05:42:56.806  10480 12099  spanH=36 total=2644ms candidates=15758 merged=3148 apis=ALL
```

Same window, same location, same rows, same process — three widgets each resolving their own paint.
Uncontended that 72h read costs 486 ms; run twice at once against a concurrent sync it costs 8.3 s.
**A 17× inflation, and half the work was a duplicate of the other half.** Widget 352's paint that
round: `totalMs=12389`.

The user-visible version of this is at 05:55:16–05:55:19, straddling two of their taps: widgets 345,
349 and 352 repaint together (`totalMs=1633 / 1734 / 4713`) while four separate `apis=ALL` reads run
(3,185 + 1,013 + 993 + 661 ms).

## Was a click dropped?

No. Every tap in 05:55:06–05:55:42 reached `WidgetActionReceiver.onReceive`, started its coroutine in
**0–2 ms**, and advanced the source exactly one step through
`OPEN_METEO → SILURIAN → TOMORROW_IO → NWS`. Eight taps, eight advances, none lost. The dispatcher is
not the problem and `MAX_PARALLELISM` is not implicated.

What produced the impression is visible at 05:55:33:

```
05:55:33.282  onReceive TOGGLE_API      ← user taps
05:55:34.559  TOGGLE_API_SLOW total=1273ms source=NWS
05:55:34.589  INTERACTION_E2E done e2e=1307ms
05:55:34.593  onReceive TOGGLE_API      ← user taps again, 4 ms later
05:55:35.323  TOGGLE_API_SLOW total=727ms source=OPEN_METEO
```

The widget showed the *old* source for 1.3 s, the user re-tapped, and then two sources advanced
inside 750 ms. That double-advance — asking for one step and getting two — is what "it didn't
register" feels like from the outside, and it is caused entirely by the latency in Findings 1–3.

(Separately: the app has been idle since 05:55:42 — no `onReceive`, no paint, no read. Any tap after
that time did not reach the app at all, but nothing in the captured window shows one being sent.)

## Recommendation

Ordered by measured return per unit of risk.

### Tier 1 — take the probe off the click path

**1. Do not read observations to decide whether to enqueue a worker.** `maybeBackfillIncompleteHistory`
wants coverage, not rows. `ObservationDao` already has a cheap content signature query for exactly
this shape of question. Failing that, move the probe *after* `WidgetPushDispatcher.push` so it cannot
delay pixels, or behind the same cooldown key the graph uses so it runs once per window rather than
once per paint. Expected: −486 ms on every NWS daily paint, −3,185 ms under contention.

**2. Scope the probe read.** If it must read rows, pass
`ActualsProviderResolver.providerIdFor(displaySource)` — it is gated to NWS anyway, so the provider
set is `NWS|Generic` and the log already says that costs 613 ms for a 132h window against 8,309 ms
for 72h unscoped.

### Tier 2 — finish the scoping the hourly path already has

**3. Scope the four daily call sites** (`DailyViewHandler:341`, `DailyGraphRenderer:414`, `:552`,
`CurrentTempResolver:34`). `loadTodaySourceObservations` is free — it already filters to
`displaySource.id` immediately after, so pushing that predicate into SQL cannot change the result.
Heed the DAO's own warning: scope only reads whose every consumer filters by the same rule, and pass
the resolved provider set, never a literal — the daily recompute must stay unscoped.

**4. Make unscoped reads opt-in at the type level.** Twelve call sites, one scoped, is not something a
test will keep honest. Rename the unscoped entry point (`getObservationsInRangeAllApis`) so choosing
it is a visible decision at the call site rather than a defaulted `apis = null`.

### Tier 3 — stop paying for the same rows N times

**5. Single-flight the read** on `(startTs, endTs, lat, lon, apis)` for the duration of a fan-out, so
three widgets repainting together issue one query. Return the same immutable list to every caller —
row order leaks into `dominantStationByDay`'s tie-break, so a shared result is not merely an
optimisation but the safer option (see `ActualsRowOrderDeterminismTest`). The desktop precedent for
memoising a quantized window is in `blend_window_is_30min_quantized_memoizable`.

### Tier 4 — close the measurement gap permanently

**6. Emit `otherMs = totalMs − (resolveMs + prepareMs + renderMs)` in `WidgetPerfLogger.kv`.** Every
round of this thread has been found by noticing a residual by hand. A logged residual makes the next
one announce itself instead of waiting for a user to say "it feels slow."

## Verification

Automated, in preference to another hand-driven logcat session:

- **Unit:** `shouldProbeHistoryBackfill` already has `DailyHistoryBackfillGateTest`; add the
  signature-vs-rows equivalence case so Tier 1's cheap probe is proven to reach the same enqueue
  decision as the row read on the same fixtures.
- **Integration (2+ classes, per the standing definition):** drive `DailyViewHandler.render` against
  a counting fake `WeatherRepository` and assert (a) the number of `getObservationsInRange` calls per
  paint, and (b) that **no** call uses the 72h backfill window. Both are exact integers, so the test
  fails loudly if a future call site reappears.
- **Integration:** assert every daily-path read passes a non-null `apis`, by having the fake record
  the argument. This is the guard rail for Tier 2 that item 4 makes structural.
- **Determinism:** `ActualsRowOrderDeterminismTest` must stay green across Tier 2 and Tier 3 — it is
  the test that catches a scoped or shared read silently reordering rows into the blend.
- **On device, after:** re-run the same four-source toggle cycle and confirm NWS no longer costs
  ~350 ms more than its neighbours, and that `WIDGET_RENDER_PERF`'s new `otherMs` is small.

---

## Outcome (2026-09-07, same device, same session)

**Status: Tiers 1, 2 and 4 implemented, tested and verified on device. Tier 3 deliberately not
implemented — see below.**

### Measured, warm, eight toggle cycles on SM-F936U1

| | before | after |
|---|---:|---:|
| **Daily paint** (`WIDGET_RENDER_PERF totalMs`, view=DAILY) | 807 / 863 / 893 ms | **153 / 188 / 234 / 284 / 313 ms** |
| its unaccounted residual | 798 ms (untimed) | **102-245 ms** (now logged as `otherMs`) |
| `OBS_RANGE_READ` on the click path | 486-3,185 ms per NWS tap | **none above the 300 ms log threshold** |
| `TOGGLE_API_SLOW` NWS | 1,273 / 1,010 ms | 868 / 900 ms |
| NWS vs its neighbours | **+350 ms penalty** | **no penalty — NWS is mid-pack** |

The cleanest number is the paint: **893 ms → 234 ms on the same action, a 3.4× reduction**, and the
per-source penalty that made NWS feel like a slow provider is gone.

End to end the win is smaller — about 23% on an NWS toggle — because the cost has simply moved. Which
is itself the next finding, and it is now measured rather than guessed:

```
DAILY_INTERACTION_PERF totalMs=836  (NWS, warm)
├─ dailyDataMs   377   ← now the dominant term
├─ snapshots 65 · observations 68 · hourly 32 · currentResolve 14 · currentHourly 13 · gapFill 6
└─ renderMs      261   ← was 893
```

`dailyDataMs` was always ~300-440 ms; it was simply never the biggest thing in the room. It is now.

### What changed

- **`DailyViewHandler.maybeBackfillIncompleteHistory` consults the shared cooldown before reading.**
  The CLOUD probe has always done this; the daily probe did not. The repair still works — the device
  logs `OBS_HOURLY_BACKFILL_SKIP reason=coverage_ok latest_gap_min=18` exactly as before — it is just
  no longer bought once per paint.
- **`ActualsReadScope`** now holds the one definition of "which apis does this source's actuals need",
  and the header load, the current-temp resolve, the overlay fallback and the hourly path all take it
  from there. That co-location is load-bearing, not tidiness: the overlay compares its re-derived
  `observedAt` against `CurrentTempResolver`'s for **exact equality**, so two differently-scoped reads
  would drop the dominant-station rows.
- **Nine further call sites scoped or explicitly marked unscoped**, including two inside the daily
  recompute that filter to `api == NWS` on the very next line — that is the path the 8,325 ms read
  came from.
- **`apis` lost its default.** Eleven of twelve widget call sites were unscoped on 2026-09-07 and not
  one had chosen to be; `apis = null` was simply what they inherited. It is now a compile error to
  not say. Every remaining `apis = null` carries a one-line reason.
- **`WidgetPerfLogger.residualMs`**, surfaced as `otherMs` on the daily render line.

### Verification

- `:app:testDebugUnitTest` + `:shared:test` — **2,148 tests, all green**.
- New: `ActualsReadScopeTest` proves the scope is a superset of what
  `ObservationSourceMatcher.matchesActualSource` keeps, across every `WeatherSource` × api × station
  combination — so no scoped read can delete a row its consumer wanted.
- New: `DailyBackfillProbeCostTest` pins both defences (no read while cooling down; scoped read and
  72 h window when it does read). **Both assertions were mutation-tested**: removing the cooldown
  line and un-scoping the read each fail the suite.
- On device: render verified pixel-equivalent before and after, including the `-0.2 from yest` delta
  badge and the today-column overlay — the two things a wrong scope would have silently emptied.

### Tier 3 (single-flight) — not implemented, and why

The duplicate concurrent reads that justified it — two byte-identical 72 h `apis=ALL` reads finishing
14 ms apart, 8.3 s each — **were the per-widget backfill probe**. With Tier 1 that probe no longer
runs on the paint path, and the duplicates did not reproduce in any post-fix capture: the clean warm
cycle logs no `OBS_RANGE_READ` at all.

Single-flighting a shared read means one coroutine's cancellation can reach another's await, which is
a real hazard to add to a path whose measured problem has gone. The thread's own rule is that the
guesses which survive are the measured ones, so this stays on the shelf until a capture shows the
duplicates returning. **If they do, the fix is written up in Tier 3 above and unchanged.**

The natural next target is `dailyDataMs`, which the table above now shows is 45% of an NWS tap.
