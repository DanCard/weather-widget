# Session summary — "samsung: very slow on taps", five rounds

**Date:** 2026-09-06 · **Plans:** five, all in the new `performance/` directory ·
**Status:** four fixes landed and measured on device; committed to `main`, **not pushed**

## What was reported

User: *"samsung: very slow on taps. Please review logs, add logging if that helps."*

Device: SM-F936U1 (Z Fold 4), 3 widgets, 73 MB database — `observations` 79,906 rows across **71
distinct device locations**, `hourly_forecast_history` 164,888, `app_logs` 56,407.

The app had already recorded the slowness. Over three days: `REFRESH_SLOW` 402, `TOGGLE_API_SLOW`
119, `SET_VIEW_SLOW` 102, plus resize/zoom/nav. Worst single `REFRESH_SLOW`: **68,615 ms**.

## Result

| | before | after |
|---|---:|---:|
| paint (`TEMP_PIPELINE_PERF totalMs`, warm) | 7,975 ms | **144–473 ms** |
| `obsQueryMs` | 5,462 ms | **71–138 ms** |
| actuals `recompute` | 26,780 ms | **273 ms** settled / 1,907 ms changed |
| full sync total | 52,162 ms | **5,365 ms** settled |
| observation read, one call | 4,456 ms | under the 300 ms log threshold |

## Commits

| | |
|---|---|
| `696d03bf` | Stop paying for discarded diagnostics on the observation read path |
| `49b5280d` | Scope the observation read by api, and bound widget interaction concurrency |
| `71b2e03c` | Skip settled-day recomputes and thin personal Synoptic stations |
| `f5e7aeec` | Stop writing daily_history rows for feeds that cannot be displayed |
| `3607c048` | Attribute the click path: queue, lock and prepare are all negligible |

## The through-line: timers that start after the expensive part

Four of the five rounds found a large **unmeasured** span rather than a slow algorithm. That is the
session's real finding.

1. `WIDGET_RENDER_PERF widget=352 … resolveMs=4 prepareMs=0 renderMs=0 totalMs=7819` — reads like a
   fast render. `DailyViewHandler`'s TEXT branch never assigns `prepareMs`/`renderMs`, so ~8 s sat in
   code no timer covered.
2. `SYNC_PERF actuals=27509ms` lumped seven distinct operations behind one label.
3. `DAILY_HISTORY_STABLE` is logged **after** a full recompute proves nothing changed — 91% of the
   time.
4. `TEMP_PIPELINE_PERF` reports ~400 ms while a click takes 629–1,581 ms, because its timer starts
   after the data load.

Instrumentation that doesn't span what it reports is worse than none: it converts "I don't know" into
a confident wrong answer.

## Round 1 — diagnostics nobody read (`696d03bf`)

Split `SYNC_PERF`'s `backfill=`/`actuals=` into named sub-stages and added `DAILY_RECOMPUTE_PERF` and
`OBS_RANGE_READ`. One refresh cycle then showed **18 observation reads over 300 ms summing 50.4 s**,
of which:

- **`diag` ≈ 20.0 s** — `ObservationPoolDiagnostics.summarize` ran unconditionally inside every read.
  **Exactly one caller consumes it**; the other 23 take `.rows` and discard it. Made `by lazy`.
- **`logExtremaWindowDiagnostic` was ungated** — a DEBUG diagnostic issuing its own ±24 h read and
  running `blendObservationSeries` **twice**, 4,977 ms for one day. Gated on
  `Log.isLoggable(…, VERBOSE)`, guarding the *work*: the existing `AppLogDao` VERBOSE gate only skips
  the insert, which is the cheapest part. Kept, not deleted.

**Also established the cost is disk, not CPU**: at identical row counts, a cold read costs
`sql=2438 ms` and a warm one `353 ms`. And a control — the same database copied to an emulator ran
9.4× faster, every sub-stage scaling alike, so ~9× is hardware and the rest is data shape.

## Round 2 — the api-scoped read (`49b5280d`)

The user's question decided this one: *"the API was on NWS, shouldn't Synoptic data be ignored?"*

It already is — `matchesActualSource` rejects any row whose `api` is not the display source's
resolved provider — but `ObservationDao` took no source, so that filter ran *after* the rows crossed
the CursorWindow. One 132 h window: **34,726 SYNOPTIC rows (70%, 1.35 MB of text) against 7,862 NWS**,
all of the former read off a cold disk and dropped.

**The correction that made it non-trivial:** `actuals_provider_SILURIAN = SYNOPTIC` is stored on this
device, so Silurian's curve is built entirely from Synoptic rows. The filter can never be a literal —
it must be `ActualsProviderResolver.providerIdFor(source)`.

Per-source reads after: NWS 1,601 · SILURIAN 6,049 · OPEN_METEO 290 · TOMORROW_IO 131, where every
one previously read the same ~33k candidates.

Two design changes came from measurement, not the plan: the worker's fan-out was *already* sequential,
so the herd was entirely receiver-side, and sharing one bounded pool with the worker would have let a
30 s sync starve the taps it exists to protect.

## Round 3 — settled-day skip and station thinning (`71b2e03c`)

**6,805 `DAILY_HISTORY_STABLE` against 663 `DAILY_HISTORY_OVERWRITE`** — 91% of per-day recomputes
produced a byte-identical result.

**The first guard shipped and never fired once.** It compared `MAX(fetchedAt) <= updatedAt`, reasoning
that `fetchedAt` over-reports change and so "errs safe". It does — on essentially every row of every
sync, because `INSERT OR REPLACE` re-stamps rows a deep fetch already has. On a fully settled day,
**7,315 of 7,633 rows carried a `fetchedAt` more than an hour after their own `timestamp`**. *Errs
safe* and *never fires* are the same behaviour at a 100% error rate; the direction of the error was
checked and its frequency was not.

Replaced with a content signature (`COUNT`, `MAX(timestamp)`, integer sums of temperature, precip,
cloud, QC) in a process-local map. A temperature-only version then dropped **measured precip**, which
arrives by REPLACE with count, newest timestamp and temperature all unchanged —
`ObservationRepositoryDailyMergeTest` caught it on the first full run.

`PersonalStationThinning` floors personal Synoptic stations at 10 min on write, keyed on
`stationType` and never on observed interval: **KSJC reports every 4.6 min and is OFFICIAL**,
full-weight, sky-reporting and the anchor of the NWS blend. Drops 557 of 1,998 rows per batch.

Part 3 (screen-off deferral) was planned and then **deliberately not built**: at 273 ms there is no
longer enough to defer, and on a device never plugged in the periodic tick is the only thing
resampling location.

## Round 4 — rows nothing can read (`f5e7aeec`)

`ActualsAggregator`'s filter named METAR while its comment described the whole category, so SYNOPTIC
kept getting `daily_history` rows. Measured: **METAR 0 rows, SYNOPTIC 51**.

Nothing could read them — every reader selects by display source. The user chose the broader fix
(drop everything not in `ALL_CONFIGURABLE`), which required checking VISUAL_CROSSING rather than
waving it through: it *does* write observations, and `AccuracyCalculator` reads `daily_history` with
**no source filter at all**. What closes it is one level down — `resolveBaselineSource` filters on
`hasNativeActuals`, and VISUAL_CROSSING defaults to `historicalDataKind = NONE`.

The "duplicate row" framing that started this was a symptom: SYNOPTIC matches SILURIAN only because
of that stored preference. Keying on "duplicates another row" would have fixed nothing elsewhere.

## Round 5 — attributing the click (`3607c048`)

`SET_VIEW_E2E_TIMING` — the only true click-to-complete number in the app, and only one action had
it — averaged 1,224 ms over 57 real taps. Added `INTERACTION_E2E` at `launchForWidget`, the seam
every widget action funnels through, plus lock-wait and context-prepare timers.

| span | measured |
|---|---:|
| dispatcher queue | 2 ms |
| mutex wait | 0 ms, uncontended |
| `contextResolver.resolve` | 9–10 ms |
| `requestIfStale` | 2–5 ms |
| `restartHeartbeats` | 30–33 ms |
| **handler (the render)** | **594–1,371 ms** |

All four hypothesised culprits were wrong. **The click is the render.**

## A methodology error, and its correction

**`adb shell am broadcast` has never reached this app.** Both `WeatherWidgetProvider` and
`WidgetActionReceiver` are `android:exported="false"`, and `am broadcast` runs as the shell uid.
Every broadcast this session was enqueued and never delivered; `onReceive`'s unconditional `Log.d`
never fired once.

**This invalidated a test reported as evidence**: the round-2 burst test ("9 broadcasts,
`CLICK_WATCHDOG` fired 0 times") proved nothing — it fired zero times because no click happened, and
`WidgetInteractionDispatcher` is still untested under real burst load.

The failure is convincing because it is silent *and* the periodic worker syncs concurrently, so
paints and `OBS_RANGE_READ` rows appear right after each broadcast and read as its effects. The
timing measurements stand — they timed real worker activity — but attributing them to taps was wrong.

Use `adb shell input tap <x> <y>`, which routes through the launcher's PendingIntent. Recorded in
memory, and the stale advice in an older note recommending `am broadcast` as a dispatch diagnostic
was corrected.

## Conventions changed

- **`performance/` is now the home for performance plans** (user-created), a sibling of `plans/`,
  which has passed 650 files. Documented in `CLAUDE.md` along with the `YYMMDD-kebab-title.md` naming
  both directories share.

## Still open

- **Tier 2 — extend the deferred-actuals fast path to interactions** — *done 2026-09-06, see
  [260906-tier2-deferred-actuals-fast-path.md](260906-tier2-deferred-actuals-fast-path.md);
  warm Samsung numbers still pending an off-call window.*
- **Re-run the burst test with `input tap`** — the bounded dispatcher's behaviour under load is
  unmeasured.
- **Render work still outside `TEMP_PIPELINE_PERF`** — it covers 144–923 ms of a 594–1,371 ms
  handler. Likely one interaction repainting several widgets while the timer measures one paint.
- **Synoptic volume** — deferred by the user. The station limit is the wrong knob (10 + the
  `MIN_SKY_STATIONS` top-up yields 11; lowering it to 8 yields 9 and saves ~5%, and KPAO sits exactly
  on the boundary). The weight is that 12 personal stations hold 80.4% of Synoptic rows at weight
  0.05 with zero sky.
