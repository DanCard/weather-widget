# Current temperature: one shared run (audit item E)

User, 2026-10-24: "yes" → continue the audit (`261023-duplicate-logic-audit-different-names.md`) with E
(from `261021-android-desktop-shared-code-audit.md`). Both platforms feed the header number from the
same shared pieces, but the glue that strings them together is written 1× on desktop and ~6× on
Android, and the copies disagree.

## The pipeline (shared pieces, per-platform glue)
1. Window: `CurrentTemperatureResolver.buildCurrentTempResolutionWindow` (now −12 h … +3 h).
2. Observed now: `ActualsAggregator.resolveCurrentObservation` (the blend at now).
3. Header number: `CurrentTemperatureResolver.resolve` — forecast at now + (observed − forecast at the
   observation's time), the "delta".
4. "From yesterday": `YesterdayDeltaCalculator.computeDelta`.

Desktop: one function, `DesktopWeatherRepository.resolveForForecastResult`. Android: window 6×,
`resolve` 5× (`TemperatureViewHandler` ×3, `TemperatureStateResolver`, `CurrentTempResolutionHelper`),
yesterday delta 3×, plus `ObservationResolver.resolveObservedCurrentTemp` as a fallback in 5 places.

## Divergences found
1. **Stored delta, Android only.** Android persists the last delta per widget + source
   (`widget_current_temp_delta_*` prefs) and passes it to `resolve`; desktop passes `null`. It matters
   only when no observation reaches the window: Android shows forecast + the last stored delta,
   desktop the bare forecast. `resolve` never ages it. Measured on the Pixel, 10-21 23:58 → 10-22
   01:45: header 60.1 °F = forecast 58.2 + a held delta of 1.91; desktop's rule gives ~58.2. The
   Pixel also holds deltas weeks old (OWM's from ~10-03) that would apply unchanged.
2. **Raw-observation fallback, Android only.** When the blend gives nothing,
   Android falls back to the newest raw row (NWS_BLEND preferred,
   `resolveObservedCurrentTemp`) as the observed temperature; desktop has no fallback.
3. **Android-internal:** `WidgetRenderer`'s no-repository branch hard-codes `lookbackHours = 12L,
   lookaheadHours = 2L`; the constant is 3 h (`RESOLUTION_LOOKAHEAD_HOURS`), which every other
   caller uses — a different blend window, and a cache key that never matches the others'.

## Decisions (user, 2026-10-24)
First answers were "keep the stored delta with a 3 h age limit" and "raw fallback on both" — on my
recommendation, which was wrong on both counts (a side-agent review caught the first):
- **Stored delta: dropped on both.** It is reached only when no reading reaches the 12 h window, so
  its source reading is > 12 h old: a 3 h limit never fires, and a longer one applies a correction
  from another time of day. The 10-21 Pixel case was not "no readings" — Synoptic had 692 in the
  window; the start-up paint passed none to `resolve` (Android glue).
- **Raw fallback: dropped on both.** It fires only when the blend accepted no reading, and it ignores
  the blend's QC and personal-weight filters, so it could only surface a rejected reading. It never
  ran on either phone (79/79 headers `graph_style`); on production paths its input list was empty.

## Done (phase 1)
- `:shared` `CurrentTempRun` (window → blend at now → `resolve` → yesterday delta). Desktop
  `resolveForForecastResult` and the Android Months screen run it (Months printed the newest raw row
  as its current temperature, not the widget header's number).
- `CurrentTemperatureResolver.resolve` takes no stored delta (no `storedDeltaState`, `currentLat/Lon`,
  `updatedDeltaState`, `shouldClearStoredDelta`); `CurrentTemperatureDeltaState` deleted. Android's
  delta prefs are no longer read or written; `CurrentTemperatureDeltaStore` keeps only the legacy
  un-suffixed coordinates (`WidgetLocationStore`) and per-widget key removal. Not purged: a purge
  could not tell a widget's only coordinates from a delta's.
- Android raw fallback removed (`ObservationResolver.resolveObservedCurrentTemp`, 5 call sites), and
  with it a now-dead observation query on every hourly-graph interaction (`GraphInteractionRenderer`).
- `WidgetRenderer`'s no-repository branch uses the shared window constants (was 12 h / 2 h).
- Tests: `CurrentTempRunTest` (blend anchors; a QC-failed reading alone → bare forecast; nothing in
  the window → bare forecast), `CurrentTemperatureResolverTest` (no observation → bare forecast),
  stored-delta tests removed or reduced to their fresh-observation assertions, a legacy-location test.

## Phase 2 — handoff (written 2026-10-24 at the end of phase 1, commit `42a10c30`)

**Goal.** Every Android header path gets its current temperature from `:shared` `CurrentTempRun.run`
(as desktop and the Months screen already do), so the rule has one copy. Phase 1 already made the
*rules* identical — no stored delta, no raw fallback, one window — so phase 2 is glue removal; the
header number should not change except where a path was feeding `resolve` different inputs (below).
Follow the CLAUDE.md shared-code rule; `AGENTS.md` asks for a plan + approval before code.

**What the run does** (`shared/.../widget/CurrentTempRun.kt`): cuts hourly + observations to
`buildCurrentTempResolutionWindow(now)` (−12 h…+3 h) → `ActualsAggregator.resolveCurrentObservation`
(blend at now, `RESOLUTION_LOOKBACK/LOOKAHEAD_HOURS`) → `CurrentTemperatureResolver.resolve` with
`computeSmoothedForecasts` → `YesterdayDeltaCalculator.computeDelta` on the *full* lists. Returns
display temp, applied delta, delta from yesterday, observed temp/at, window counts.

**Android call sites still doing it themselves** (line numbers at `42a10c30`):

| Piece | Where |
|---|---|
| Blend at now | `widget/handlers/CurrentTempResolver.kt:69` (`resolveGraphStyleCurrentTemp[FromInputs]`), `widget/WidgetRenderer.kt:476` (no-repository branch) |
| …called from | `DailyInteractionRenderer.kt:302`, `GraphInteractionRenderer.kt:184`, `WidgetRenderer.kt:465` |
| `resolve` | `CurrentTempResolutionHelper.kt:24` (`resolveTimed`, used by `DailyHeaderResolver.kt:130`, `CloudCoverViewHandler.kt:255`, `PrecipViewHandler.kt:187`), `TemperatureStateResolver.kt:548`, `TemperatureViewHandler.kt:122 / 319 / 384` |
| Yesterday delta | `TemperatureStateResolver.kt:595`, `DailyViewHandler.kt:363`, `TemperatureGraphHoursLoader.kt:277` |
| Window built | `WidgetRenderer.kt:441`, `GraphDataLoader.kt:242`, `DailyGraphRenderer.kt:470`, `CurrentTempResolver.kt:30`, `notify/DominantTempChangeNotifier.kt:71` |

**Traps (read before moving anything):**
1. **Observation read scope.** `CurrentTempResolver.resolveGraphStyleCurrentTemp` reads
   `ActualsReadScope.apisFor(displaySource)`, and its comment says the today-column overlay
   re-derives this observation and matches `observedAt` on **exact equality** — a different read
   drops the station rows from the overlay. `WidgetRenderer` reads `apis = null` on purpose (its
   rows also feed `ObservationWatermark`). The run takes rows; each Host must keep its own read.
2. **Two-step paint.** `TemperatureStateResolver` / `TemperatureViewHandler` paint a quick header
   first (`resolveQuick`, no observation) when `deferCurrentTempResolution` (startup,
   `WidgetViewModeDispatcher.kt:71`), then refine asynchronously (`scheduleCurrentTempRefinement`).
   Keep the quick path; the refinement is the run.
3. **The 10-21 start-up case.** At `startup_launch` (`WidgetStartupCoordinator.kt:445`) a header
   resolved with `obs=none` while Synoptic had 692 readings in the window — the start-up paint
   passed no observation. Find which path that was (logcat `CURR_TEMP_RESULT … obs=none` right after
   `HOURLY_PAINT_TRACE phase=startup_launch`) and make sure its Host reads observations.
4. **Yesterday delta inputs.** The run's yesterday delta reads the full lists; Android's three calls
   pass what each handler has loaded — check each gets ≥ 24 h of observations, or the delta goes
   null where it used to show.
5. **Per-widget sources.** Several widgets show different sources at once (`CURR_TEMP_RESULT`
   lines on the Samsung differ by widget) — the Host is per widget + source, not per app.

**Tests to add.** Parity: the same rows through an Android Host and desktop's give the same
`displayTemp` (a desktop-vs-Pixel spot check on 2026-10-24 07:44 gave 47.16 vs 47.07 °F — blend
inputs differ, Pixel still has Synoptic). One test per Host that a header with observations in the
window never resolves `obs=none` (trap 3). Keep `CurrentTempUnificationIntegrationTest` green.

**Verify.** Full suite; install on both phones; `adb logcat | grep CURR_TEMP_RESULT` per widget;
desktop `CURR_TEMP_RESULT` in `app_logs`; restart desktop.

## Phase 2 — plan (2026-10-24; approved, user chose to widen the existing read)

**What the code shows.** Android's glue is layered, unlike desktop's one function: the observation
is blended once per paint upstream (`WidgetRenderer`, or `CurrentTempResolver` for an interaction)
and passed down as `lastObservedTemp`/`observedAt`. Each handler then calls `resolve` itself. The six
`resolve` calls give the same inputs to a shared function. What really diverges is (a) the two
blend branches and (b) the yesterday delta, which reads three different observation windows:
- daily: now − 36 h … now (`OVERLAY_OBSERVATION_LOOKBACK_MS`), so pan-independent;
- hourly graph: centred on the **graph's** `centerTime` (`HOURLY_LOOKBACK/LOOKAHEAD`), so a panned
  view can lose now − 24 h and the delta goes null (or moves) as you pan;
- hourly text mode: `centerTime` − 30 h … + 2 h, the same problem.
The header comment says the delta is "pan-independent". Only the daily view keeps that promise.

**Trap 3 identified.** Every `obs=none` header on the Pixel (6 rows, 10-21 23:58 → 10-22 01:45) is
**widget 88, daily, SILURIAN** (actuals provider SYNOPTIC, explicit). It fires on RESIZE too, not
only at start-up. The window had 692 SYNOPTIC rows: none QC-failed, all at site 37.417/−122.089,
newest 23:55. That same paint's today-column overlay did find readings (`obsHigh=60.09`), so the
rows were reachable, yet the header received no observation. I cannot yet tell which layer drops them. Step 1
reproduces it offline from those rows.

**Steps.**
1. **Repro test first** (`:app` Robolectric): Pixel rows from 10-21 23:58 (SYNOPTIC observations
   + SILURIAN hourly, trimmed into a fixture) → `CurrentTempRun.run(SILURIAN)`. If it gives a
   temperature, the Android glue lost the rows; fix it in step 2. If it gives null, the cause is
   in the shared blend (both platforms), so report it and propose a fix separately.
2. **`CurrentTempRun` exposes its steps** so Android can use them at its two layers (`run`
   composes them, so desktop and Months stay as they are):
   `observe(hourly, observations, source, lat, lon, nowMs, weight)` → window + blend at now;
   `header(now, source, windowHourly, observed)` → `resolve` with the smoothed forecasts;
   `yesterday(...)` → the delta on now-anchored lists.
3. **Blend at now → `observe`**: `WidgetRenderer` (both branches become one call; each branch keeps
   its own rows, unscoped vs `currentTemps`, trap 1) and `CurrentTempResolver.resolveGraphStyleCurrentTemp`
   (keeps its scoped read for the overlay's exact `observedAt` match). Delete
   `resolveGraphStyleCurrentTempFromInputs`.
4. **`resolve` → `header`** at all six sites (`CurrentTempResolutionHelper.resolveTimed`,
   `TemperatureStateResolver`, `TemperatureViewHandler` ×3). Keep the quick paint (`resolveQuick`)
   and the async refinement as they are; the refinement becomes `header` (trap 2).
5. **Yesterday delta, one read**: compute it once upstream from a now-anchored read
   (now − 36 h … now, the daily view's window) and pass it down. That removes the three calls and the
   pan dependence. The cost is one extra observation read per hourly paint. I'll check whether
   `WidgetRenderer`'s −12 h read can be widened to −36 h instead; `ObservationWatermark` takes the
   newest row, so a wider read should leave it alone. I'll confirm before relying on it.
6. Remove `CurrentTempResolutionHelper` if all that's left is a timer.

**Tests.** The step-1 repro (fails before the fix if the cause is Android-side). Parity: the same rows
through the Android path (`observe` + `header` as `WidgetRenderer` calls them) and desktop
`resolveForForecastResult` give the same `displayTemp`. Hourly view panned ±2 days: the yesterday
delta matches the unpanned value (fails today). `CurrentTempUnificationIntegrationTest` and the
desktop twin stay green. Full suite.

**Verify.** Install on both phones. `CURR_TEMP_RESULT` per widget, with no `obs=none` for widget 88
on SILURIAN. Pan an hourly widget and watch the "from yest" value hold. Desktop `CURR_TEMP_RESULT`
in `app_logs`. Restart desktop.

## Done (phase 2)
- **Trap 3 was a false premise.** All 692 SYNOPTIC rows in widget 88's window were *fetched*
  10-22 02:30–06:43, after the 23:58 paint; at 23:58 Synoptic was in fetch backoff. `obs=none` was
  right; phase 1 counted rows by reading time, not arrival. The shared run on those rows resolves
  55.97 °F, so there is no blend bug either. The repro test was dropped (it tested nothing real).
- `:shared` `CurrentTempRun` gains `observe` (blend at now + yesterday delta) and `header`
  (`resolve` cut to the window around now); `run` composes them, so desktop and Months are
  unchanged. `inputWindow(now)` = now − 36 h … window end, the reads' range.
- Android: `CurrentTempResolver.observe` / `observeFromRows` (the Host's read; scoped for the
  interaction paths, unscoped in `WidgetRenderer` for the watermark) replace both blend branches and
  `resolveGraphStyleCurrentTemp[FromInputs]`. `WidgetRenderer`'s read was widened from −12 h to
  −36 h (user); `ObservationWatermark` takes the newest row, so it cannot move. The interaction
  paths load hourly over the same range (`GraphDataLoader.loadCurrentTempInputHourlyForecasts`,
  was `…Resolution…`) and cut it with `nowCentred` for the views.
- Every header `resolve` (6 sites) is `CurrentTempRun.header` via `CurrentTempResolutionHelper`;
  the startup quick paint (`resolveQuick`) is unchanged.
- The yesterday delta rides on `ObservedCurrentTemperature.deltaFromYesterday` into the views; the
  three handler computations are gone, and with them the text-mode observation read (its only use).
  It was pan-dependent on the hourly graph (read centred on the graph, centre − 72 h) and text mode
  (centre − 30 h); now it is the same on every view and every pan. It is also present in an
  interaction's phase-1 paint, which skips the graph read.
- **Precip / cloud headers** resolved from the graph's (possibly panned) hourly list; they now get
  the now-centred list like every other view.
- Not a divergence after all: the smoothed-vs-raw daily header. `HEADER_SMOOTH_ITERATIONS = 0`, so
  "smoothed" equals raw; `header` still passes the smoothed map, matching desktop if it is ever on.
- Left alone: `DailyGraphRenderer` and `DominantTempChangeNotifier` cut the same window for
  `resolveCurrentObservationDetails` (dominant station), a different product.
- Tests: `CurrentTempRunParityTest` (Android observe + header == desktop run on the same rows);
  `TemperatureDeltaVisibilityRoboTest` now feeds the delta through `CurrentTempResolver.observe`
  with a range-honouring repository, plus graph panned +72 h and text mode panned +24 h (both blank
  under the old graph-centred reads). Full suite green (`DesktopDailyParityTest` failed once under
  the parallel run and passed alone and on the rerun; unrelated to this change).
- Verified 2026-10-24 08:05: Pixel NWS `observe obs=534 windowObs=213 fromYesterday=-9.20`, header
  49.25 °F, matching the "-9.2 from yest" its background paint showed; Samsung GOOGLE_WEATHER
  `fromYesterday=-8.67`, 49.29 °F; desktop NWS 49.22 °F (blend inputs differ by 0.01 °F).
