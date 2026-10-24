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
