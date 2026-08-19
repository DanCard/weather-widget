# Widget repaint gate: data watermark + paint-owed flag (2026-08-18)

## Report

"On samsung hourly temperature screen it says `knuq 71.6 @ 4:35`, but when I click on current
observations screen it has a higher temperature for knuq station." Then, minutes later: "It just
updated to the higher temp."

## What the logs show

Samsung fold (`RFCT71FR9NT`), `app_logs`:

| time | event |
|---|---|
| 16:56:44 | last TEMPERATURE paint. Newest KNUQ row is **16:35 / 71.6°F** (`obs=72.18`) |
| 17:02:32 | `charging_loop` fetch inserts **KNUQ 16:50 / 73.4°F**, `interactive=false` |
| 17:02:40 | **`WIDGET_PAINT_SKIP reason=screen_off`** — rows stored, nothing painted |
| ~17:03–17:08 | user unlocks. Widget still reads `knuq 71.6 @ 4:35`; Observations reads 73.4 |
| 17:08:33 | `reason=ui_update_alarm` → `WIDGET_PAINT ... reason=temp_changed`, `obs=73.41` |
| 17:09:25 | next pass → `state=header_only_live` (73.10 → 73.09, same formatted string) |

The two surfaces never disagreed about *which* observation to use. They read the DB at different
times. The DB itself was correct throughout.

Incidental, and worth knowing because it bounds how fresh KNUQ can ever be:
`OBS_WEB_API_DELTA station=KNUQ tier=use apiNewestMs=…16:35 webNewestMs=…16:50 deltaMin=15` — the
NWS observations API trails the web page by ~15 min and the web value wins. The 73.4 reading could
not have existed on the device before the 17:02 fetch, regardless of repainting.

## Two independent defects

### A. No trigger on unlock — **out of scope for this plan**

`WidgetPaintCoordinator.updateAllWidgets` (`WidgetPaintCoordinator.kt:78-81`) returns early with
`WIDGET_PAINT_SKIP reason=screen_off` when `!isScreenInteractive()`. That is the right battery
call — painting a widget nobody can see is wasted work. The gap is that unlocking fires nothing:
`ACTION_USER_PRESENT` is manifest-registered and therefore never delivered at targetSdk 26+
(`ScreenOnReceiver.kt:38` documents this outright; see
`plans/260818-power-connected-broadcast-never-delivered.md`). So the widget holds its pre-fetch
pixels until the next UI-update alarm happens to fire with the screen on.

Closing this properly needs a real screen-on wake edge. The only untested candidate is a
*runtime*-registered `ACTION_SCREEN_ON` receiver — manifest restrictions do not apply to those —
but it only works while a process is alive, which for a widget app is precisely when it is least
likely. **Deliberately not attempted here.** Separate investigation.

### B. The repaint gate's change-detection is lossy — **this plan**

`GraphRepaintGate.shouldRebuildBitmap` (`GraphRepaintGate.kt:38`) decides whether to rebuild the
graph bitmap by comparing **the formatted current-temp string** against the last render's. That
string is a *proxy* for "the data changed", and it is lossy with respect to everything else drawn
on that bitmap — the dominant-station label, its `@ 4:35` timestamp, the observed dot.

When the gate returns false, `TemperatureViewHandler.kt:135-145` calls `updateHeaderCurrentTemp`
and **returns before `TemperatureStateResolver.resolve()`** — the layer that formats the station
label (`DominantStationLabel`, drawn by `TemperatureGraphAnnotationRenderer.kt:369`). So:

> A fresh observation lands → the blended display temp still formats to the same string → gate
> returns `header_only_live` → bitmap not rebuilt → **the station label stays stale for up to 15
> minutes** (`MAX_BITMAP_INTERVAL_MS`), with the screen on and repaint passes actively running.

The 17:09:25 log is that path being taken. And the 17:08:33 recovery was **partly luck**: it
rebuilt only because `temp_changed` fired (72.02 → 73.10). Had the temp been stable, the gate would
have returned `header_only_live` — 11.8 min elapsed, under the 15-min backstop — and the stale
label would have survived the unlock.

`MAX_BITMAP_INTERVAL_MS` is itself a confession: a blind 15-minute forced rebuild exists *because*
the gate cannot tell whether data changed.

## Changes

### 1. Observation watermark as a gate input

New pure helper (no Android deps, testable in isolation):

```kotlin
object ObservationWatermark {
    /** Max observation `timestamp` across the already-scoped current rows; 0L when empty. */
    fun of(rows: List<ObservationEntity>): Long
}
```

**`timestamp`, explicitly not `fetchedAt`.** The obvious choice is "when did the DB last change",
i.e. `MAX(fetchedAt)` — and it is wrong here. Per
[[observations-fetchedat-attempt-semantics]], `fetchedAt` carries *attempt* semantics: since
2026-07-13 `INSERT OR REPLACE` refreshes it whenever any storable ob comes back, **even a repeated
stale one**, and `touchLatestFetchedAt` bumps it on a definitively empty attempt too. So
`MAX(fetchedAt)` advances on essentially every successful fetch cycle (~10–16 min while charging)
regardless of whether anything drawn changed — reintroducing blind periodic rebuilds under a new
name.

`timestamp` moves exactly when a station publishes a genuinely newer reading, which is precisely
what the label renders (`knuq 71.6 @ 4:35` → `73.4 @ 4:50`). It is also what blend, extrema and
retention already key on, so the gate agrees with the pipeline it is gating.

Known gap, accepted: a *correction* to an existing row (same `timestamp`, revised temperature)
does not move the watermark. That is rare, and `MAX_BITMAP_INTERVAL_MS` remains as the backstop for
it — which is the main reason §4 below must not raise that constant casually.

(The rows reaching this point are already location/source scoped by the caller — this helper must
not re-filter, and its KDoc should say so.)

### 2. Thread it to the gate

- `WidgetStateManager.LastGraphRenderState` (`WidgetStateManager.kt:386`) gains
  `val dataWatermarkMs: Long?`.
- `WidgetPresentationStateStore.lastGraphRender` / `setLastGraphRender`
  (`WidgetPresentationStateStore.kt:253-268`) persist it; add the key to `clearWidget`.
- `GraphRepaintGate.shouldRebuildBitmap` takes `lastWatermarkMs: Long?` + `currentWatermarkMs: Long`
  and gains, immediately after the `no_prior_render` check:
  - `lastWatermarkMs == null` → `Decision(true, "watermark_absent")` — one forced rebuild on
    upgrade, so persisted pre-upgrade state is never mistaken for "unchanged".
  - `currentWatermarkMs > lastWatermarkMs` → `Decision(true, "data_changed")`.
  - A `currentWatermarkMs` of `0L` (no observations) must **not** force a rebuild every pass.
- `currentTemps: List<ObservationEntity>` already flows
  `WidgetPaintCoordinator.kt:68` → `WidgetRenderer.kt:386` → handlers, so the watermark is
  computed once at the `WidgetRenderer` seam where `observation` is resolved, and passed to all
  three gate call sites: `TemperatureViewHandler.kt:126`, `PrecipViewHandler.kt:86`,
  `CloudCoverViewHandler.kt:138`. All three write-back sites
  (`TemperatureViewHandler.kt:201`, `PrecipViewHandler.kt:474`, `CloudCoverViewHandler.kt:499`)
  record the watermark they rendered.

### 3. Paint-owed flag

- At the `screen_off` early return (`WidgetPaintCoordinator.kt:78-81`), set a **global** (not
  per-widget) `paint_owed` flag before returning. Global because the skip happens before widget ids
  are enumerated.
- On the next `updateAllWidgets` that proceeds with the screen on, force a rebuild (bypass the
  gate, `reason=paint_owed`) and clear the flag.

This does not shorten the unlock gap — defect A owns that — but it makes recovery **deterministic**
instead of dependent on the temp string happening to move.

### 4. Follow-on, only once 1–3 are in

With a real watermark, `MAX_BITMAP_INTERVAL_MS` stops being load-bearing and could be raised (a
battery win, the inverse of this bug). **Do not change it in the same commit** — it would confound
the fix with a regression risk on the one path that currently guarantees eventual freshness.

## Verification

### Unit — `GraphRepaintGateTest` (pure, extends the existing file; `@Category(ShortDuration::class)`)

1. watermark advanced, **same** formatted temp → rebuild, `reason=data_changed`
   *(this is the 17:09:25 scenario and the regression guard for the whole plan)*
2. watermark unchanged, same temp, under max interval, no drift → `header_only_live`
   *(guards against the watermark causing a rebuild on every pass — the battery regression)*
3. `lastWatermarkMs == null` → rebuild, `reason=watermark_absent` (upgrade path)
4. `currentWatermarkMs == 0L` with a non-null prior → does **not** rebuild (no-observations thrash)
5. watermark **regresses** (row deleted / retention cleanup) → does not rebuild, no crash
6. both temp string and watermark changed → deterministic reason (assert which wins)
7. `paint_owed` short-circuits ahead of every other check → `reason=paint_owed`
8. existing `no_prior_render` / `temp_changed` / `max_interval` / `now_drift` cases still pass
   unchanged with the new params defaulted

### Unit — `ObservationWatermarkTest` (new, pure)

9. max observation `timestamp` across mixed rows
10. empty list → `0L`
11. single row → that row's `timestamp`
12. does not re-filter by source/location (rows in, max out) — pins the scoping contract
13. **rows whose `fetchedAt` advanced while every `timestamp` stayed put → watermark unchanged.**
    This is the regression test for the `fetchedAt` trap in §1; without it a future refactor
    "simplifies" the helper back to `fetchedAt` and silently restores blind rebuilds.

### Robolectric — state persistence

14. `LastGraphRenderState` round-trips `dataWatermarkMs` through `WidgetPresentationStateStore`
15. a pre-upgrade prefs blob (renderMs + displayedTemp, **no** watermark key) reads back with
    `dataWatermarkMs == null` → drives case 3 rather than defaulting to `0L`
16. `clearWidget` removes the watermark key
17. `paint_owed` survives process death (write, re-instantiate the store, read)

Per [[testing-strategy]]: no mocking framework — assert on the extracted pure functions, use the
`_test_default` prefs suffix for the store tests, and assert dp/logic rather than rendered text
(Robolectric has no font engine).

### Instrumented / manual

18. Extend `WidgetRendererDailyUiOnlyRepaintTest` (or a temperature sibling) to assert a uiOnly
    cycle with an advanced watermark and a stable temp string reaches the full render path.
19. On-device replay of the reported case: note the station label, force a current fetch while the
    screen is off, unlock, confirm the label refreshes on the first pass that runs — and that
    `WIDGET_PAINT` carries `reason=data_changed` or `paint_owed` rather than `temp_changed`.
20. Idle check: over ~30 min charging with the screen on, confirm `header_only_live` still dominates
    the log. If `data_changed` fires every pass, the watermark is being recomputed wrong (this is
    the failure mode to watch for — it would roughly triple idle repaint cost).

## Risks

- **Over-firing.** The design already avoids the known trap (`fetchedAt` attempt semantics — see
  §1), but the empirical check still matters: if `data_changed` fires on every ~2-min pass rather
  than on new readings, the ~800 ms bitmap cost returns. Check 20 exists for exactly this. If it
  does over-fire, move to a content hash of station/temp pairs rather than reverting the design.
- **Under-firing.** Keying on `timestamp` means a same-timestamp correction is invisible to the
  gate. Accepted, with `MAX_BITMAP_INTERVAL_MS` as the backstop — and it is why §4 is deferred
  rather than bundled.
- Three gate call sites must stay in step; a handler that reads the watermark but forgets to write
  it back rebuilds forever.
- Defect A is untouched. The reported symptom is *reduced* (recovery becomes deterministic) but the
  unlock-latency window remains.

## Status

**Implemented 2026-08-18.** `:app:testDebugUnitTest` green (full suite); installed on the Samsung
fold and verified end to end.

Files: `ObservationWatermark.kt` (new), `GraphRepaintGate.kt`, `WidgetStateManager.kt`,
`WidgetPresentationStateStore.kt`, `WidgetPaintCoordinator.kt`, `WidgetRenderer.kt`, and the three
graph handlers. Tests: `ObservationWatermarkTest` (new), `WidgetRenderStateWatermarkRoboTest` (new),
`GraphRepaintGateTest` (extended).

### Two things the plan got wrong, both caught on device

1. **The watermark was computed from the wrong list.** `WidgetRenderer` was reading `currentTemps`,
   which the plan asserted "already flows" to the handlers. It does — but when a `repository` is
   present (every worker and UI path) it is *empty*, and the observations actually drawn come from
   `repository.getObservationsInRange(...)` inside the branch. The watermark therefore persisted as
   `0` and the whole change was inert. It now follows the same fork the render does.

2. **The interaction path clobbered it.** `GraphInteractionRenderer` (nav taps, refresh) calls the
   same handlers, never consults the gate, and was stamping the default over a good watermark. The
   parameter is now `Long?`, where null means "not measured — preserve what is stored". Deliberately
   *not* fixed by computing a watermark there too: that path queries a different observation set
   (`getMainObservationsWithComputedNwsBlend`, whole-day) than the gated path
   (`getObservationsInRange`, window-around-now), and two watermarks from two queries are not
   comparable — a lower one from the wrong query would silently suppress a needed rebuild.

Both were invisible to the unit tests, which had the pure logic right the whole time.

### Verified

- Unit: all cases in §Verification 1–17 pass. Falsification checked — disabling the `data_changed`
  clause fails exactly `new observation with unchanged temp string forces rebuild` and
  `data changed outranks temp changed`, and nothing else.
- Device: `widget_last_data_watermark_349 = 1787098800000` = 17:20:00, exactly the newest NWS
  observation (AW020) at the time. Idle UI-only ticks still log `header_only_live`, so check 20's
  over-fire regression did not materialise.

### Not yet observed on device

`data_changed` and `paint_owed` have not been seen in `app_logs` yet — they need a new reading to
land during a UI-only tick, and a fetch that completes while the screen is off, respectively. Both
paths are covered by unit tests; the on-device confirmation (checks 18–19) is still outstanding and
should be picked up from the next day's logs by grepping those two reasons.
