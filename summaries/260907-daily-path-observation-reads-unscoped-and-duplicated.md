# Session summary — "phone feels slow at times", round six

**Date:** 2026-09-07 · **Plan:** [performance/260907-daily-path-observation-reads-unscoped-and-duplicated.md](../performance/260907-daily-path-observation-reads-unscoped-and-duplicated.md) ·
**Status:** Tiers 1, 2 and 4 landed, tested and measured on device. Tier 3 deliberately deferred.
**Not committed.**

Sixth round of the Samsung tap-latency thread, after
[the five of 2026-09-06](260906-samsung-tap-latency-five-rounds.md).

## What was reported

Three messages, all mid-session, all about the same widget:

1. *"going from hourly temperature graph to daily forecast view took like 3 seconds."*
2. *"Was very slow trying to click through different api's just now."*
3. *"When I was on silur trying to go to next, it seemed to take several seconds, not one. Maybe
   didn't register my click."*

Device: SM-F936U1, build 26090601, 3 widgets, 89,489 `observations` rows.

## Result

| | before | after |
|---|---:|---:|
| **Daily paint** (`WIDGET_RENDER_PERF totalMs`, view=DAILY) | 807 / 863 / 893 ms | **153 / 188 / 234 / 284 / 313 ms** |
| its unaccounted residual | 798 ms, untimed | **102–245 ms**, logged as `otherMs` |
| `OBS_RANGE_READ` on the click path | 486–3,185 ms per NWS tap | **none above the 300 ms threshold** |
| `TOGGLE_API_SLOW` NWS | 1,273 / 1,010 ms | 868 / 900 ms |
| NWS vs its three neighbours | **+350 ms penalty** | **no penalty — mid-pack** |

The paint the user tapped is **3.4× faster**. End to end an NWS toggle improved ~23%, because the
cost moved rather than vanished — see [What is now the biggest thing](#what-is-now-the-biggest-thing).

## The instrumentation paid for itself in one session

Yesterday's round built `INTERACTION_E2E` and shipped it the same evening. Today every reported
symptom had a number waiting for it:

```
05:49:40.315 INTERACTION_E2E action=ACTION_SET_VIEW widget=345 phase=start queue=1ms
05:49:41.657 INTERACTION_E2E action=ACTION_SET_VIEW widget=345 phase=done e2e=1343ms queue=1ms work=1342ms
```

Diagnosis took ten minutes and no guessing. Two operational notes for the next round:

- **`INTERACTION_E2E` is logcat-only, by design** — a diagnostic's own `app_logs` write must not land
  on the path it measures. So it is invisible to a database pull.
- **`scripts/backup_databases.py` captures `logcat -d -t 1000`**, which on this device is about *ten
  seconds*. It held nothing useful. The full buffer (`adb logcat -d`) reaches back ~15 minutes and is
  what actually contained the evidence. Worth raising the backup's line count.

## Diagnosis

### 1. A background-maintenance probe ran inside the click

`DailyViewHandler.maybeBackfillIncompleteHistory` read **72 hours of observations, unscoped**, and
drew none of them. Its only output is a decision about whether to enqueue a background worker.

| time | span | total | sql | candidates | merged | apis |
|---|---|---:|---:|---:|---:|---|
| 05:49:41 | 72h | 512 ms | 455 | 37,529 | 7,231 | ALL |
| 05:55:18 | 72h | **3,185 ms** | 2,892 | 37,511 | 7,218 | ALL |
| 05:42:53 | 72h | **8,325 ms** | 7,699 | 37,551 | 7,241 | ALL |

Its gate is `displaySource == WeatherSource.NWS`, which is why the cost looked like a slow *provider*
rather than a slow *code path*. The four-source cycle, twice around, makes it unmistakable:

| source | `TOGGLE_API_SLOW` | 72h probe read? |
|---|---:|---|
| OPEN_METEO | 895 / 727 ms | no |
| SILURIAN | 908 / 909 ms | no |
| TOMORROW_IO | 843 / 736 ms | no |
| **NWS** | **1,273 / 1,010 ms** | **yes — 486 / 492 ms** |

### 2. The daily path never got the api-scoped read the hourly path got

Scoping landed on 2026-09-06 — on `TemperatureStateResolver` only. Of twelve
`getObservationsInRange` call sites under `widget/`, exactly one passed `apis`. That is the direct
explanation for the asymmetry the user felt: hourly → daily cost 1,343 ms while daily → hourly cost
644 ms.

The A/B was already in the log, ninety seconds apart on the same database:

```
05:42:46  spanH=132  613ms    4,911 candidates   apis=NWS|Generic
05:42:53  spanH=72   8,309ms  37,551 candidates  apis=ALL
```

Nearly twice the window, 8× fewer rows, 13× faster. On this device SYNOPTIC is **73%** of the last
72 h of observations — read off disk on every NWS paint and dropped by the blend's first filter.
`DailyGraphRenderer.loadTodaySourceObservations` was the starkest: it read every provider, then did
`.filter { it.api == displaySource.id }` in Kotlin.

### 3. The same read ran concurrently and contended with itself

Two byte-identical reads finished **14 ms apart** on different threads:

```
05:42:53.775  10651  spanH=72 total=8309ms candidates=37551 merged=7241 apis=ALL
05:42:53.789  12099  spanH=72 total=8325ms candidates=37551 merged=7241 apis=ALL
```

Uncontended that read costs 486 ms. Run twice at once against a concurrent sync it cost 8.3 s — a
**17× inflation, where half the work was a duplicate of the other half.** Widget 352's paint that
round: `totalMs=12389`.

### 4. No click was ever dropped

Every tap in 05:55:06–05:55:42 reached `onReceive`, started in **0–2 ms**, and advanced the source
exactly one step through `OPEN_METEO → SILURIAN → TOMORROW_IO → NWS`. Eight taps, eight advances. The
dispatcher is not implicated and `MAX_PARALLELISM` is not the problem.

What produced the impression is visible in the log:

```
05:55:33.282  onReceive TOGGLE_API        ← user taps
05:55:34.589  done e2e=1307ms  → NWS
05:55:34.593  onReceive TOGGLE_API        ← user taps again, 4 ms later
05:55:35.323  done e2e=727ms   → OPEN_METEO
```

The widget showed the old source for 1.3 s, the user re-tapped, and **two** sources advanced in
750 ms. Asking for one step and getting two is what "it didn't register" feels like from outside —
caused by the latency, not by a lost broadcast.

## What changed

- **The probe consults the cooldown before reading.** The CLOUD probe has always done this and says
  why in a comment; the daily probe simply never got it. The repair still fires —
  `OBS_HOURLY_BACKFILL_SKIP reason=coverage_ok latest_gap_min=18` is unchanged — it is just no longer
  bought once per paint. *The fix for the largest item was applying a guard the codebase already had.*
- **`ActualsReadScope`** now holds the single definition of which apis a source's actuals need. The
  header load, the current-temp resolve, the overlay fallback and the hourly path all take it from
  there. **That co-location is load-bearing, not tidiness:** the today-column overlay compares its
  re-derived `observedAt` against `CurrentTempResolver`'s on *exact equality*, so two differently
  scoped reads would silently drop the dominant-station rows — a bug that has shipped here before.
- **Nine further call sites** scoped or explicitly marked unscoped, including two inside the daily
  recompute that filter to `api == NWS` on the very next line. That is the path the 8,325 ms read
  came from.
- **`apis` lost its default** on `ObservationDao` and both repository wrappers. Eleven of twelve
  widget call sites were unscoped and not one had *chosen* to be — `apis = null` was inherited, not
  decided. It is now a compile error to leave unsaid, and every surviving `apis = null` carries a
  one-line reason.
- **`WidgetPerfLogger.residualMs`**, surfaced as `otherMs` on the daily render line, so the next
  untimed span announces itself instead of waiting for a user to report that the widget feels slow.

## Verification

- `:app:testDebugUnitTest` + `:shared:test` — **2,148 tests, all green**.
- **`ActualsReadScopeTest`** (new) proves the scope is a superset of everything
  `ObservationSourceMatcher.matchesActualSource` keeps, across every `WeatherSource` × api × station
  combination. This is the correctness argument for the whole change: a scoped read cannot delete a
  row its consumer wanted.
- **`DailyBackfillProbeCostTest`** (new) pins both defences — no read while cooling down; scoped read
  and 72 h window when it does read. **Both assertions were mutation-tested:** removing the cooldown
  line and un-scoping the read each fail the suite.
- Eleven existing tests updated for the signature change. Three had stubbed
  `getObservationsInRange(any(), any(), any(), any())`, which silently stopped matching and turned
  the yesterday-delta badge invisible — caught by `DailyViewHandlerTest`, not by inspection.
- On device: render verified equivalent before and after, including the `-0.2 from yest` delta badge
  and the today-column overlay — precisely what a wrong scope would have silently emptied.

## What is now the biggest thing

```
DAILY_INTERACTION_PERF totalMs=836  (NWS, warm)
├─ dailyDataMs   377   ← 45% of the tap, now the dominant term
├─ snapshots 65 · observations 68 · hourly 32 · currentResolve 14 · currentHourly 13 · gapFill 6
└─ renderMs      261   ← was 893
```

`dailyDataMs` was always ~300–440 ms; it was simply never the biggest thing in the room.

## Tier 3 (single-flight) — deferred on purpose

The duplicate concurrent reads that justified it *were* the per-widget backfill probe. With Tier 1
that probe no longer runs on the paint path, and the duplicates did not reproduce in any post-fix
capture — the clean warm cycle logs no `OBS_RANGE_READ` at all.

Single-flighting a shared read lets one coroutine's cancellation reach another's await. Adding that
hazard to a path whose measured problem has gone is the wrong trade, and this thread's own rule is
that the guesses which survive are the measured ones. The design stays written up in the plan,
unchanged, for the capture that shows the duplicates returning.

## The through-line, again

Yesterday's summary ended: *"instrumentation that doesn't span what it reports is worse than none."*
Today made it six for six. `WIDGET_RENDER_PERF` reported `totalMs=893` while its own named fields —
`resolveMs=1 prepareMs=87 renderMs=7` — summed to 95. Every field read fast. The answer was the 798 ms
nobody printed.

`otherMs` exists so that round seven does not have to start with a user saying the phone feels slow.
