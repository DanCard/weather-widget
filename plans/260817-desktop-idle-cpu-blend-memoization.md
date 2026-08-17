# Desktop idle CPU: memoize the blend, skip no-op panel forks, halve the status tick (2026-08-17)

## Goal

Cut the desktop app's **idle** CPU floor. Measured on pid 3334 (14.9 h uptime, popup closed,
nothing else happening):

| Metric | Value |
|---|---|
| Lifetime CPU | 220 s (0.41 % avg) |
| Idle steady state | **~1.95 % of a core**, entirely on the minute boundary |
| Live threads' cumulative CPU | 44 s — so ~176 s went to threads that already exited |
| C2 JIT thread | 9.7 s (one-time startup) |

A 120 s idle profile isolates it exactly — two `DefaultDispatcher` threads at **identical** tick
counts plus a `process reaper`:

```
117 ticks  DefaultDispatch     ← 1.17 s CPU / 2 min
117 ticks  DefaultDispatch     ← 1.17 s CPU / 2 min
 11 ticks  process reaper      ← fork/exec every minute
```

The counts match because both minute loops are scheduled as
`delay(60_000L - System.currentTimeMillis() % 60_000L)`, so they are phase-locked to the same
wall-clock boundary and fire together:

- `DaemonProcess.kt:339` → `panelPublisher.refreshCurrentStatus()` + `triggerPanelRefresh()`
- `Main.kt:728` → bumps `nowMs`, a `remember` key at `Main.kt:740` that re-queries `getCurrentStatus`

Note for anyone reading `~/misc/logs/sys-logging-*.log`: the alarming samples there (`48.1 %` at
07:08:37, `22.5 %` at 03:10:34) are both within seconds of a **launch** — JVM/JIT warmup, corroborated
by the 9.7 s C2 thread. And because that logger only records the top 3 processes, the many
`1.5 % weather-widget-` lines mean "the machine was idle", not "the widget is a hog". The steady-state
floor below is the real target.

## Root cause

`refreshCurrentStatus()` → `CurrentStatusResolver.resolve()` → `DesktopWeatherRepository
.resolveCurrentTempInMemory()` → `resolveForForecastResult()`, which calls
`ActualTemperatureSeriesBuilder.blendObservationSeries()` **twice** per tick, each over the full
in-memory observation list (~4 368 rows over 6 days here):

1. via `ActualsAggregator.resolveCurrentObservation()` — the current-temp blend
2. via `YesterdayDeltaCalculator.computeDelta()` — the 24 h-ago blend for the header delta

Between ticks the only input that changed is `now`. The observations behind the blend change only
when a fetch lands (`FRESH_OBSERVATION_MS = 30 min`, and the observation loop is battery-aware on
top of that). So the blend is recomputed ~30× for every one time its inputs change.

**The decisive detail — the blend windows are already quantized**, so this is not an approximation
we are choosing to accept, it is pure recomputation of an identical result:

- `ActualsAggregator.resolveCurrentObservationInternal` (`ActualsAggregator.kt:118-124`) snaps the
  window centre to a **30-minute** boundary before deriving `contextStartMs`/`contextEndMs`:

  ```kotlin
  val truncatedMs = (nowMs / 3600_000L) * 3600_000L
  val minute = (nowMs % 3600_000L) / 60_000L
  val alignedCenterMs = if (minute >= 30) truncatedMs + 3600_000L else truncatedMs
  ```

- `YesterdayDeltaCalculator.computeDelta` (`YesterdayDeltaCalculator.kt:48-60`) windows on
  `targetMs = observedAtMs - 24 h ± tolerance`. `observedAtMs` is an **observation timestamp**, not
  `now` — it only moves when a new observation lands.

And `blendObservationSeries` (`ActualTemperatureSeriesBuilder.kt:296`) is a pure function of its
arguments — verified: no `currentTimeMillis` / `Instant.now` / `LocalDate*.now` in its body (the one
`LocalDateTime.now()` at line 153 is a different function's default parameter). Its own KDoc at
line 310 already notes "this runs on each minute tick".

So identical arguments are being handed to a pure function ~30 times in a row.

## Design decisions

1. **Memoize `blendObservationSeries`, not the resolved result.** Caching the final
   `ResolvedCurrentTemp` for 30 min would be wrong twice over:
   - `displayTemp` is interpolated along the hourly curve at `now` — freezing it is precisely the
     smoothness the minute loop exists to provide.
   - Inside `resolveCurrentObservationInternal` the *selection*
     `pastBlended.filter { it.timestamp <= nowMs }.maxByOrNull { it.timestamp }` genuinely advances
     minute to minute as `now` crosses emitted points.

   Caching the **series** and re-running the cheap selection each tick preserves both exactly.
   Behaviour is bit-identical; only the redundant recomputation disappears.

2. **Cache lives in `:shared`, keyed on argument identity.** A new
   `BlendSeriesCache` sits behind the two call sites. The key holds the `observations` and
   `hourlyForecasts` **list references** compared with `===` (not `identityHashCode`, which can
   collide after GC reuse) plus every scalar argument that shapes the series. Holding the refs
   pins nothing new — `forecastState` already holds them.

   Shared rather than desktop-local because the Android widget calls the same path on its own
   cadence and gets the same battery win, and because keying the cache correctly requires the
   30-minute quantization that lives in `ActualsAggregator`. Duplicating that constant desktop-side
   would be the fragile option.

3. **Do not cache the diagnostics path.** `resolveCurrentObservationDetails` passes
   `captureLatestDominantAtOrBeforeMs = nowMs`, which varies every minute and changes the retained
   contribution table. Only the hot path (`includeDominantContribution == false`) consults the cache.

4. **Bounded, thread-safe, no eviction policy worth the name.** A 4-entry ring under a lock covers
   the two live windows with slack. This is a memo for a single location's current view, not a
   general cache; unbounded growth would be a leak and an LRU would be over-engineering.

5. **Skip the panel fork when the markup is unchanged.** `PanelIpcServer.triggerRefresh()`
   unconditionally `renderMarkup()` then forks `xfce4-panel --plugin-event=...`
   (`PanelIpcServer.kt:87`), which makes xfce4-panel exec `genmon-weather-bin`, which opens the
   socket and reads. On most ticks the rendered string is byte-identical to the last one. Compare
   against the previously cached markup and return early when equal — that is the `process reaper`
   thread. First render still pokes, because `cachedMarkup` starts null.

   Accepted trade-off: if xfce4-panel restarts and loses its displayed value while our markup
   happens to be unchanged, the panel catches up on genmon's own poll period rather than instantly.

6. **Then halve the cadence: 60 s → 120 s.** Third in importance, and only 2×, but free. The panel
   shows a rounded interpolated temp that essentially never moves a whole degree in two minutes.
   Two constraints:
   - **Keep the boundary alignment** (`delay(TICK - now % TICK)`), or the panel drifts off
     wall-clock minutes.
   - **Change both loops together.** They are phase-locked today; changing one would de-phase them
     into two separate wakeups per period and partly defeat the point.

   `ObservationsWindow.AGE_TICK_MS` stays at 60 s — it drives a visible "age" label that needs
   minute resolution, and it only ticks while that window is open.

## Changes

| File | Change |
|---|---|
| `shared/.../actuals/BlendSeriesCache.kt` | **New.** Bounded identity-keyed memo for `BlendObservationResult`. |
| `shared/.../actuals/ActualTemperatureSeriesBuilder.kt` | No change (kept pure); cache wraps it. |
| `shared/.../actuals/ActualsAggregator.kt` | Extract `alignedCenterMs(nowMs)` as the single definition of the 30-min quantization; route the non-diagnostic blend through the cache. |
| `shared/.../actuals/YesterdayDeltaCalculator.kt` | Route its blend through the cache. |
| `desktop/.../PanelIpcServer.kt` | `triggerRefresh()` skips the fork when markup is unchanged. |
| `desktop/.../DesktopProcess.kt` | New `STATUS_TICK_MS = 120_000L`. |
| `desktop/.../DaemonProcess.kt` | Minute loop → `STATUS_TICK_MS`, alignment preserved. |
| `desktop/.../Main.kt` | UI ticker → `STATUS_TICK_MS`, alignment preserved. |

## Testing

Automated (`:shared` unit tests, no device needed):

1. `BlendSeriesCacheTest` — hit/miss on identity, key discrimination on each scalar, ring bound,
   concurrent access.
2. `ActualsAggregatorCacheTest` — **the load-bearing one**: for a fixed observation/hourly pair,
   `resolveCurrentObservation` returns results equal to a cache-bypassed computation across a
   sweep of `nowMs` spanning a 30-minute boundary, and the underlying builder is invoked once per
   distinct aligned centre rather than once per call. This is what proves "bit-identical, just
   fewer computations".
3. `YesterdayDeltaCalculatorCacheTest` — same shape, keyed on `observedAtMs`.
4. A regression assertion that the diagnostics path (`resolveCurrentObservationDetails`) still
   recomputes, so its `dominantContribution` tracks `nowMs`.

Manual verification: rebuild, restart via `scripts/buildStart-desktop.sh`, then re-run the same
per-thread profile that produced the numbers above and compare the idle floor:

```bash
cd /proc/<pid>/task
# snapshot $14+$15 per task, sleep 120, diff
```

Expected: the two 117-tick threads drop to near zero, and `process reaper` disappears from most
intervals.

## Implementation notes (as built)

- `BlendSeriesCache` keys the list arguments by `===` and the scalars by a `data class`, promotes on
  hit (so the current-temp and yesterday windows cannot evict each other under a capacity of 4), and
  computes **outside** the lock. A concurrent duplicate compute is possible and benign — the blend is
  pure, the results are equal, and the second insert dedupes — whereas holding the lock across the
  compute would serialize the calls the cache exists to make cheap.
- `ActualsAggregator.blendCache` is the single shared instance; `YesterdayDeltaCalculator` uses it
  rather than owning a second one, so one bounded memo covers both windows of a resolve.
- The `hits`/`misses` counters exist so the tests can assert *how many times the blend actually ran*
  rather than merely that results match — that is what distinguishes "the cache works" from "the
  cache is silently always missing".
- **Permanent `BLEND_CACHE` diagnostic.** `BlendSeriesCache.stats()` is logged at DEBUG once per
  observation fetch cycle (~30 min) beside `OBS_REFRESH` in `DesktopWeatherRepository`. Rationale:
  an always-missing cache is *observationally identical* to the code it replaced — same results,
  same CPU — so without this the optimization is unfalsifiable in production. A collapsing hit rate
  is the first symptom of a key that stopped matching. Per-fetch rather than per-tick keeps it out
  of the app_logs swamp.

  ```sql
  SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
  FROM app_logs WHERE tag='BLEND_CACHE' ORDER BY timestamp DESC LIMIT 10;
  ```

  Expect a hit rate approaching ~97% at steady state (roughly 1 miss per 30 ticks per window).

### Test results

`3064` tests, `0` failures: `:shared` 858 (114 classes), `:desktop` 268 (37), `:app` 1938 (265).

One assertion was wrong on first run and was corrected: the 14:05→15:05 sweep snaps to **two**
aligned centres (14:00, then 15:00 — because 15:00..15:05 rounds back down), not three. The
implementation was right; the expectation was not.

## Risks

- **Stale current temp if the cache key is wrong.** Mitigated by keying on list identity — any
  fetch rebuilds the lists and misses the cache — and by test 2 above, which compares against a
  bypassed computation rather than asserting a hardcoded value.
- **Cross-platform blast radius.** The cache is in `:shared`, so an error would affect the Android
  widget too. Kept narrow: the wrapped function is pure, the cache is transparent, and the
  diagnostics path is deliberately excluded.
- **Retention.** Holding list references keeps the *previous* fetch's observation list alive until
  its entries are evicted. Bounded and short-lived: a resolve inserts at most two entries, so the
  prior generation is pushed out within ~2 resolves at capacity 4. Worst case is a few hundred KB
  for a few minutes, and the current generation was already live via `forecastState`.
- **Multi-source thrash.** A caller resolving across more than two sources in a tight loop could
  evict entries before reuse. The failure mode is a cache miss — i.e. exactly today's behaviour —
  not a wrong value.
