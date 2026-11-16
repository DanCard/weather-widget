# Slow first paint on the Pixel: recap and recommendations

Date: 2026-11-15. Report: "Widget very slow on first draw on Pixel 7. Widget startup very slow
after device startup."

Plans: `performance/261115-boot-first-paint-frozen-behind-startup-syncs.md` (phases 1 and 2) and
`performance/261115-phase3-targeted-refresh-and-daily-blend-cost.md` (phase 3).
Commits: `0e5b182d7` (phases 1 and 2), `709d96940` (phase 3 B1 and B2). Neither is pushed.

## What was wrong

On the boot at 11:19 the Pixel 7 Pro took 102 s from process start to first paint.

- **About 45 s of contention.** Three syncs started in the same second as the cache-first
  startup paint. A read that takes 0.4-0.7 s warm was still unfinished 39 s later.
- **48.8 s frozen.** The last broadcast had been released by the 8 s watchdog, so Android counted
  the process as cached and froze it mid-paint (`ActivityManager: freezing 6870
  com.weatherwidget`, then `sync unfroze` 49 s later).
- **About 4 s** to finish the read and render three widgets.

Root cause: the startup cooldown held syncs back for a fixed 6 s from process start. The paint
needed longer than that, so the syncs started beside it.

## What was done

| Phase | Change | State |
|---|---|---|
| 1 | Background syncs wait until the startup paint has landed (30 s cap, 3 s recheck); `SYNC_DEFERRED_STARTUP` logs `cause=` | Committed |
| 2 | Sync runs on a two-thread background-priority pool; the repaint a sync ends with stays at normal priority | Committed |
| 3 B1 | Stage timing inside the daily blend, on `DAILY_RECOMPUTE_PERF` and Android's `DAILY_ACTUALS_PERF` | Committed |
| 3 B2 | The blend builds only the rows a caller keeps; a one-day recompute no longer blends every date in its window | Committed |
| 3 A | A targeted refresh fetches its target only | Not started |
| 3 B3 | Blend only displayed sources; rain totals once per provider | Not started |

The JVM suite passes on the committed state: 7,063 tests.

## What was measured

| Check | Result |
|---|---|
| Phase 1, emulator: forced sync sent during the startup paint | Deferred with `cause=paint_pending`; paint at 1.0 s; sync started 11 s later |
| Phase 1, Pixel reinstall | First paint 13.8 s, no freeze; no sync was requested, so the hold had nothing to hold |
| Phase 2, Pixel: 7 taps during a forced sync, warm process | About 1 s per tap with or without the pool; one tap near 2 s in each run |
| Phase 2, Pixel: sync stages | Ran on `sync-pool-1/2` |
| Phase 3 B2, Pixel: today's recompute | 14 blends to 7; `extremes` 10.1 s to 5.5 s and 4.8 s |
| Phase 3 B2, Pixel: cold startup paint | `extremesMs` 8.3 s, was 8.2 s: no improvement |

## What is not verified

- **A boot with stale data**, the case phase 1 exists for. It needs the phone's data to be a few
  hours old and the PIN after the reboot.
- **Whether the pool helps a cold process.** The one comparison was warm, one run per build, and
  the two syncs did unequal work.
- **Past-day recompute gain from B2.** There is no like-for-like "before": 12-18 s off the pool
  earlier in the day, 8-11 s on the pool after B2.

## Findings along the way

1. **The rain-total step costs as much as the series blend.** Today's recompute before B2: 4.3 s
   blend, 5.0 s rain totals, on a dry day. It runs once per source per date over largely the same
   provider rows.
2. **The sync pool slows the recompute.** Today's recompute took 2.4-5.3 s before phase 2 and
   10.1 s on the pool, before B2.
3. **The cold paint's largest step is the daily-actuals read**: 8.2 s of a 13.8 s first paint with
   no sync running. Its split after B2: 1.9 s reading, 6.4 s assembling, of which 3.2 s is blend
   and 2.5 s rain totals, across three source groups when the paint displayed one.
4. **Every cold start on this phone is slow.** 13 of the last 15 took 10-18 s to first paint with
   no boot and no freeze. The phone runs a debuggable build, which Android does not precompile.
5. **Android killed the app for background CPU** on 2026-11-14 at 22:01, which caused a 305 s
   first paint afterwards. The recompute is the likely cause; that link is inferred from timing.
6. **Two earlier decisions already limit background fetching**: the 8-day source gate
   (2026-10-10) and the desktop away state (2026-10-19). The original phase 3 design, an idle
   housekeeping job, was dropped because of them and because the cost is in the recompute.
7. **Fold 4 blank hourly graph, unrelated.** A day tap on Sun Nov 22 opened a day where NWS
   hourly data ends at midnight. No "data ends" message appeared, because
   `NoHourlyChecker.hasHourlyForDay` counts a day as covered from two points. None of this work
   touches that rule.

## Recommendations

In the order I would do them.

1. **Run the stale-data reboot on the Pixel.** It is the only test of phase 1's purpose and the
   first look at the pool on a cold process. Read `COLD_START_PERF`, `SYNC_DEFERRED_STARTUP
   cause=`, and the freezer lines.
2. **Fix the Fold message.** Show "No hourly forecast for {day} — data ends …" when the hours the
   view shows hold no data for the source. Small change to a shared rule.
3. **B3: rain totals once per provider's rows.** Lower risk, and it should remove roughly the rain
   half of each recompute and of the cold read. Needs an equality test like B2's.
4. **B3: blend only the sources the caller displays in the live read.** This is what should
   shorten the cold paint (three groups to one on this phone). It changes what that read returns
   for sources nobody asked for, so the callers that reuse a loaded bundle after a source switch
   must be checked first.
5. **Decide the recompute's place on the pool after the reboot.** If taps and paints look the
   same with and without the pool, the pool is costing sync time for no shown benefit and should
   be dropped or narrowed to exclude the recompute.
6. **Part A: a targeted refresh fetches its target only.** Smallest effect of the remaining
   items; first confirm the scheduler picks up sources already past due.
7. **Leave user-started fetches on the pool for now.** Revisit only if a user-started refresh with
   a full recompute measures clearly slower.

Not recommended:

- **The idle housekeeping job (Part C).** Build it only if B3 leaves a past-day recompute
  expensive enough to matter beside a paint.
- **Cooperative yielding** (a running sync pausing while the user taps). Set aside 2026-11-15.

## Device state left behind

- The committed build is installed on the Pixel 7 Pro, the Fold 4 and two emulators. One emulator
  (`Medium_Phone_API_36`) was started for this work and left running.
- The phase 2 benchmark ran two forced all-source syncs on the Pixel, which count against the
  provider quotas, and cycled widget 79 through all seven sources three times. It ended on NWS,
  where it started. Those taps count as views, so all seven sources are inside the 8-day source
  gate again from 2026-11-15; all seven were inside it before.
- The Fold's hourly widget was left on Sun Nov 22, where the user's tap put it.
