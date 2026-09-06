# Click latency: where the remaining time goes, and eight ways at it

**Date:** 2026-09-06
**Status:** brainstorm — nothing implemented; recommendation at the end

Fifth in the Samsung tap-latency thread, after
[the read-cost investigation](260906-samsung-tap-latency-observation-read-cost.md),
[the api-scoped read](260906-scope-observation-read-by-api-and-bound-paint-concurrency.md),
[the settled-day skip](260906-thin-personal-stations-and-skip-settled-day-recomputes.md) and
[dropping non-displayable history rows](260906-drop-provider-only-daily-history-rows.md).

Those took the paint from 7,975 ms to ~400 ms and the full sync from 52,162 ms to ~5,365 ms. This is
an options list for what is left, written from measurement rather than intuition — the pattern that
has held all session is that the guesses which survived were the measured ones.

All figures from SM-F936U1, 3 widgets, ~80k `observations` across 71 device locations.

## Where a click goes today

| stage | warm | cold / contended |
|---|---:|---:|
| **E2E receipt → complete** (`SET_VIEW_E2E_TIMING`) | **~1,224 ms mean** (57 samples, 222–3,214) | — |
| paint (`TEMP_PIPELINE_PERF totalMs`) | 363–473 ms | 2,197–2,275 ms |
| → `obsQueryMs` | 103–138 | ~605 |
| → `buildHourDataMs` | 97–124 | **739–871** |
| → `renderMs` | 15–46 | 106–118 |

Recent post-fix E2E samples: 1,519 / 1,612 / 1,645 / 1,695 ms. `REFRESH_SLOW` still fires **13 times
an hour**, worst 3,395 ms; `RESIZE_SLOW` 9 times.

## The headline

**Roughly 800 ms of every click happens before the paint timer starts, and nothing measures it.**

`WidgetActionReceiver` → `launchForWidget` → `WidgetIntentRouter.runInteraction` (per-widget mutex) →
`WidgetIntentActionHandler.setView` → `renderAfterTransition` → **`prepareContext`** →
`WidgetRefreshContextResolver.resolve()`, which runs `ActiveLocationResolver`,
`forecastDao.getLatestForecastBySource` and more. Only *after* all of that does
`InteractionRenderDispatcher.render` start `handlerStartMs`, which is what `TEMP_PIPELINE_PERF`
reports.

This is the session's opening trap one layer out. Then, `totalMs=7819` with `resolveMs=4` read like a
fast render because the timer did not cover the cost. Now `TEMP_PIPELINE_PERF` says 400 ms while the
user waits 1,224 ms, because the timer starts after the data load. **Every large unaccounted span
found this session has turned out to be the answer.**

## The options

### Tier 1 — measure

**1. Time `prepareContext` / `contextResolver.resolve`.**
~800 ms of untimed work directly on the click path. Highest information for the lowest cost, and it
discriminates between two fixes that point in opposite directions: if the time is the data load, the
answer is caching and projection (5, 6, 7); if it is dispatcher scheduling or mutex wait, the answer
is concurrency shape and `WidgetInteractionDispatcher.MAX_PARALLELISM`. Guessing picks the wrong one
half the time.

**2. Extend E2E timing to every action.**
`SET_VIEW_E2E_TIMING` is the only true click-to-complete number in the app. `TOGGLE_API`,
`CYCLE_ZOOM`, `DAILY_NAV` and `RESIZE` — the taps actually reported as slow — emit nothing end to
end, so their `*_SLOW` rows measure a sub-span and the 13 `REFRESH_SLOW` per hour have no
user-perceived figure attached at all. One shared helper around `launchForWidget` would cover them.

### Tier 2 — change what is perceived, not what is computed

**3. Two-phase paint.**
Push the state change immediately from cached state — view mode, nav arrows, header, API label — then
the graph when data resolves. Response under 100 ms without making anything faster. At ~400 ms of
genuine paint work, this is the largest remaining win on "slow on taps", because the complaint is
about *feedback latency*, not throughput. Precedent exists: `WidgetPushDispatcher` already
distinguishes partial from full pushes.

**4. Optimistic toggle feedback.**
The API indicator and zoom label are pure state and can update before any data resolves. A narrow
special case of 3, worth listing separately because it is much cheaper and could ship alone.

### Tier 3 — cut remaining real work

**5. Memoize the blend.**
`buildHourDataMs` is now co-equal with `obsQueryMs` and is the largest single stage when cold
(739–871 ms). Desktop already solved exactly this: the blend window is 30-minute quantized, hence
memoizable — cache the series, not the resolve. Android has `WidgetInteractionCache` at a 2 s TTL; a
longer TTL keyed on the quantized window would cover repeated taps and the multi-widget fan-out.

**6. Column projection.**
Option A from the first plan, never done. The observation read still pulls **28 columns** including
`rawMetar`, `stationName` and `condition`; the blend uses about nine and none of the large text. One
132 h window carried 1.9 MB of text. Attacks bytes-off-disk, which the cold/warm split
(`sql=2438 ms` vs `353 ms` at identical row counts) identified as the real constraint.

**7. Share the resolved context across the fan-out.**
One tap becomes one broadcast per widget, and each independently resolves the same location and the
same latest forecast. Three widgets, three identical resolves. A short-lived cache keyed on
(location, source) would collapse them.

### Tier 4 — cheap structural

**8. VACUUM the database.**
Tested on a pulled copy: **70.2 MB → 61.8 MB, 12% smaller** — and note `freelist_count = 0`, so this
is page fragmentation rather than free pages, which Room never reclaims. 8.4 MB fewer pages to fault
in on the cold reads that still cost roughly 5× warm. Needs a trigger point and a one-shot flag, for
which `ForecastRepository.cleanOldData` and the existing `*_cleanup_v1` preference keys are the
precedent.

## Recommendation

**1 → 3 → 5.**

- **1 first** because ~800 ms is unattributed and the two plausible causes have opposite fixes.
- **3 next** because at ~400 ms of genuine paint work the remaining wins are perceptual rather than
  computational, and the original complaint was about feedback.
- **5 third** because it is now the largest single measured stage.

Hold **8** until after 1: it needs machinery, and it is only worth it if cold reads are still on the
critical path once the 800 ms is attributed.

## Not recommended yet

- **Raising `WidgetInteractionDispatcher.MAX_PARALLELISM`.** It was set to 2 hours ago and the burst
  test showed `CLICK_WATCHDOG` at zero. Changing it before item 1 attributes the 800 ms would be
  guessing, and if the wait is a mutex rather than the dispatcher it would do nothing.
- **Anything about Synoptic volume.** Deferred by the user and separately analysed; the station limit
  is the wrong knob (see the thinning plan's "Not doing" section).
