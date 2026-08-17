# Desktop idle CPU — status summary (2026-08-17)

Implementation status for `plans/260817-desktop-idle-cpu-blend-memoization.md`. The plan holds the
design and rationale; this file is the working record of what was built and what was (and was not)
measured.

## Trigger

Observation that the desktop app was using too much CPU, based on
`~/misc/logs/sys-logging-2026-08-17.log`.

**That log overstates the case, in a specific and repeatable way**, and the correction matters more
than the original suspicion:

- It samples only the **top 3 processes**, so the many `1.5% weather-widget-` lines mean *the machine
  was idle*, not that the widget was a hog.
- Its alarming samples (`48.1%` at 07:08:37, `22.5%` at 03:10:34) are each within seconds of an app
  **launch** — JVM/JIT warmup, corroborated by the ~9.7s lifetime of the `C2 CompilerThre` thread.

There was still a real idle floor underneath, which is what got fixed.

## Measured baseline (pre-change, pid 3334, 14.9h uptime, popup closed)

| Metric | Value |
|---|---|
| Lifetime CPU | 220s (**0.41%** avg) |
| Idle steady state | **~1.95% of a core**, all on the minute boundary |
| Sum over *live* threads | 44s — so ~176s went to threads that had already exited |
| C2 JIT thread | 9.7s (one-time) |

A 120s idle profile localized it precisely — two `DefaultDispatcher` threads at **identical** tick
counts, plus a `process reaper`:

```
117 ticks  DefaultDispatch     ← 1.17s CPU / 2 min
117 ticks  DefaultDispatch     ← 1.17s CPU / 2 min
 11 ticks  process reaper      ← fork/exec every minute
```

Identical counts because both loops used `delay(60_000L - System.currentTimeMillis() % 60_000L)` —
phase-locked to the same wall-clock boundary, firing together (`DaemonProcess.kt:339` and
`Main.kt:728`).

## Root cause

Each tick ran `ActualTemperatureSeriesBuilder.blendObservationSeries` **twice** over the full
in-memory observation list (~4,368 rows / 6 days): once for the current-temp blend, once for the
24h-ago header delta. Between ticks the only changed input was `now`, while the observations behind
the blend change only when a fetch lands (~30 min).

The decisive detail: **both blend windows are already quantized**, so this was pure recomputation of
an identical result, not a staleness trade-off.

- `ActualsAggregator` snaps its window centre to a **30-minute** boundary.
- `YesterdayDeltaCalculator` windows on `observedAtMs` — an *observation* timestamp, not `now`.
- `blendObservationSeries` is pure (verified: no clock reads in its body).

Identical arguments were being handed to a pure ~350ms function ~30 times in a row.

## Changes

| File | Change |
|---|---|
| `shared/.../BlendSeriesCache.kt` | **New.** Bounded, identity-keyed, thread-safe memo. |
| `shared/.../ActualsAggregator.kt` | Extracted `alignedCenterMs()`; hot path through the cache. |
| `shared/.../YesterdayDeltaCalculator.kt` | Its blend through the same cache. |
| `desktop/.../PanelIpcServer.kt` | `triggerRefresh()` skips the `fork/exec` when markup is unchanged. |
| `desktop/.../DesktopWeatherRepository.kt` | Permanent `BLEND_CACHE` hit-rate log, once per fetch. |
| `desktop/.../DesktopProcess.kt` | New `STATUS_TICK_MS = 120_000L`. |
| `desktop/.../DaemonProcess.kt`, `Main.kt` | Both tickers use it; alignment + phase-lock preserved. |

**What is deliberately NOT cached** — the subtle half of the design:

- `displayTemp`, interpolated along the hourly curve at `now`. Freezing it would stall the very
  smoothness the tick exists to provide.
- The `pastBlended.filter { it.timestamp <= nowMs }` selection, which legitimately advances minute to
  minute *within* one cached series.
- The diagnostics path (`resolveCurrentObservationDetails`), whose
  `captureLatestDominantAtOrBeforeMs = nowMs` is not part of the cache key.

## Tests

**3,064 tests, 0 failures** — `:shared` 858 (114 classes), `:desktop` 268 (37), `:app` 1938 (265).
Three new test classes (19 tests).

The load-bearing test compares the cached path against a **cache-bypassed** computation across a
minute-by-minute sweep spanning a 30-minute boundary, *and* asserts how many blends actually ran.
Both halves are needed: matching results alone would also pass if the cache never hit.

One assertion was wrong on first run and corrected — the 14:05→15:05 sweep snaps to **two** aligned
centres (15:00..15:05 rounds back down), not three. Implementation was right; expectation was not.

## Verification status — incomplete

Functionally confirmed: panel serving (`73.4° → 73.9°` across ticks, so interpolation is live, not
frozen), resolve values sane, no errors in `app_logs`.

**A clean before/after CPU number was not obtained during this session.** Attempts:

| Window | Result | Why unusable |
|---|---|---|
| 360s | 1.34% | Caught a JVM restart + full JIT warmup |
| 600s | 0.60% | 113 of 360 ticks still JIT; an `OBS_REFRESH` fetch landed mid-window |
| 900s | *(was still running)* | — |

Subtracting JIT and the fetch from the 600s window puts idle near **0.32%** against a **~1.95%**
baseline, but that is an estimate with two subtractions in it, not a measurement.

**The fairest comparison is a long-run lifetime average**, matched against the pre-change figure of
220s over 14.9h (0.41%, fetches and JIT included). That requires the new build to accumulate several
hours. To finish the verification later:

```bash
ps -eo pid,etimes,times,pcpu,comm | grep 'weather-widget-'   # want << 0.41%
sqlite3 ~/.local/share/weather-widget/weather.db \
  "SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
   FROM app_logs WHERE tag='BLEND_CACHE' ORDER BY timestamp DESC LIMIT 10;"
```

Expected `BLEND_CACHE` hit rate at steady state: ~97% (roughly 1 miss per 30 ticks per window). A
collapsing hit rate is the first symptom of a key that stopped matching.

### Note on JIT contamination

It is a *consequence* of the fix, not just noise. C2 compiles hot methods; the blend previously ran
~1,440×/day so it compiled early and stayed compiled. It now runs ~48×/day, so a larger share of the
app's remaining CPU is one-time compilation that no longer amortizes as heavily. The long-run average
should improve more than any short window suggests — but short windows on a young process are
dominated by exactly the cost this change did not target.

## Incidental findings

- **The UI process is spawned lazily**, only on the `.show` trigger (`DaemonProcess.kt:588`). After a
  fresh launch where nobody has opened the popup, **1 process (daemon alone) is correct** — the
  previously recorded "healthy = 2 (daemon + UI)" was missing that caveat. Memory corrected.
- Lifetime CPU minus the sum over live threads reveals CPU spent by **exited** threads. A large gap
  means thread churn: `kotlinx` `CoroutineScheduler` terminates idle workers after ~60s, so a
  wake-every-minute workload constantly creates and destroys them (tell: 6-digit TIDs).

## Not done

Nothing committed — no commit or push was requested.
