# One forecast delta in every view

**User, 2026-10-30:** "Desktop: Meteo api: says +2.5 from forecast on daily forecast view. When I go
to temperature graph it has a bigger value, says +3.9 from fcst."

**User, 2026-10-30:** "Create plan, make sure there is an integrated test and maximum use of shared
code. When this happened before I asked for an integrated test, so suprise it keeps happening."

## Evidence (desktop, Open-Meteo, 2026-10-30 10:35–10:49 PDT)

Observation: 64.08 at 10:35 in both views (`CURR_TEMP_RESOLVE`, `PERDAY_RAW max t=10:35`).

Forecast rows from `hourly_forecast_history`:

| Hour | 05:03 fetch | 10:24 fetch | Stitched row (settled pick) |
|---|---|---|---|
| 10:00 | 58.2 | 59.3 (hindcast) | 58.2 |
| 11:00 | 59.3 | 61.6 | 61.6 |

| View | Forecast at 10:35 | Delta | Evidence |
|---|---|---|---|
| Daily overlay | 61.60, newest run held flat before 11:00 | +2.48 | `CURR_TEMP_RESULT delta=2.48 estAtObs=61.60 run=newest_only`; `todayOverlay ... delta:+2.5` |
| Hourly graph | 60.18, interpolated across 05:03 and 10:24 fetches | +3.90 | Derived: matches the reported +3.9; the value is not logged |

## Root cause

`CurrentTemperatureResolver` measures the delta on one forecast run, deliberately
(plans/261003-current-temp-delta-same-forecast-run.md). Desktop `TemperatureGraph.kt:325` skips it and
computes its own `actualTemp - forecastTemp` on the stitched series, so the slope between two runs
leaks into the delta. Android has no such calculation: `TemperatureStateResolver.kt:422` passes the
resolver's delta to the graph.

The local value also drives desktop's ghost line and ghost labels. 11:00 draws 65.5 (61.6 + 3.9);
the resolver's correction gives 64.08 (61.6 + 2.48). It also depends on the visible window, because
`lastActualPoint` is the last actual in the panned window, so the label can change while panning.

## Why the earlier tests missed it

The closest earlier case is plans/261013-desktop-today-column-uses-published-current-temp.md: same
family, two desktop views disagreeing. Its integration test renders the popup in daily mode only and
pins the current temperature, not the delta. Every delta test stops short of the real path:

| Test | Gap |
|---|---|
| `DesktopDailyForecastModelTest` | Injects a literal `appliedDelta` |
| `ForecastDeltaCaptionScaleTest` | Text spans only; never reaches `TemperatureGraph` |
| `TemperatureGraphRendererForecastDeltaTest` | Android; delta injected |
| `CurrentTempRunParityTest` | Resolver against resolver; no view involved |
| `CurrentTemperatureResolverMixedRunTest` | Resolver only |

No test compares two views, and none renders desktop's delta label. Desktop's graph has no way to
report what it drew. This plan fixes both, and adds a guard so a new call site fails the build.

## Shared-code audit

| Decision | Android | Desktop | After |
|---|---|---|---|
| Delta value | Resolver | Own `rawDelta` | Resolver only, in both |
| Null / NaN rule | `?: 0f` (`TemperatureGraphSeriesResolver:40`) | Own | Shared `ForecastDeltaLine` |
| Ghost curve `forecast + delta` | `TemperatureGraphSeriesResolver:85` | `TemperatureGraph:332`, `:1002` | Shared `ForecastDeltaLine` |
| Ghost minimum 0.1 | `MIN_GHOST_LINE_DELTA` | Literal, twice | Shared constant + `drawsGhost` |
| Label gate | Reason ladder in `placeForecastDeltaLabel` | Different inline conditions | Shared `ForecastDeltaLabel.decide` |
| Label temperature for color | `lastObservedTemp` | `fetchDotPoint.actualTemp` | `lastObservedTemp` equivalent, both |
| Label outcome for tests | `ForecastDeltaDebug` | None | Shared `ForecastDeltaOutcome` |

Only drawing, text measuring and wiring stay per platform.

## Fix

### `:shared`

1. New `shared/graph/ForecastDeltaLine`: takes the resolved `appliedDelta`, exposes `effectiveDelta`,
   `expectedTemps(forecastTemps)`, and `drawsGhost`, with the 0.1 constant.
2. `ForecastDeltaLabel.decide(...)`: the gate as one ordered function returning a reason
   (`no_delta`, `fetch_dot_offscreen`, `no_current_temp`, `too_few_hours`, `zero_delta`,
   `span_too_wide`, or proceed). Keep Android's reason strings.
3. `ForecastDeltaOutcome(reason, valueText, suffixText)`: the platform-free outcome. Android's
   `ForecastDeltaDebug` wraps it and keeps its `RectF` and text-size fields.
4. `ForecastDeltaCallSites`: scan on the `CurrentConditionsCallSites` pattern, flagging an
   actual-minus-forecast subtraction in platform code. Allowed count starts at zero; if real unrelated
   hits appear, list them with a count and say why in the plan's follow-up notes.
5. Correct `ForecastDeltaLabel`'s KDoc: the number is the resolver's delta, measured on one run.

### `:desktop`

6. `TemperatureGraph` takes `appliedDelta: Float?` from `snapshot.resolved.appliedDelta`, the field the
   daily overlay already reads. No default value, so a call site cannot forget it.
7. Delete `rawDelta` and the local `appliedDelta`. `lastActualPoint` stays for `transitionX`.
8. Route ghost curve, ghost gate, ghost labels and delta label through items 1 and 2.
9. Add `onForecastDeltaPlaced: ((ForecastDeltaOutcome) -> Unit)?`, Android's existing seam.

### `:app`

10. Delegate `TemperatureGraphSeriesResolver`, `TemperatureGraphSeriesRenderer` and
    `placeForecastDeltaLabel` to items 1–3. No behavior change.

## Tests

The shared fixture lives in `shared/src/testFixtures`: today's real rows, observation and timestamps.
Expected values are hand-derived literals from the Evidence tables, not produced by the code under
test: **+2.5** shown, `appliedDelta` 2.48.

| Test | Kind | Covers | Oracle |
|---|---|---|---|
| `DesktopForecastDeltaViewsIntegrationTest` | integration | Temp SQLite DB → `getHourlyWithHistory` → `DesktopWeatherRepository.loadCached` → `DesktopWidgetPopup` rendered in daily, then hourly → `DesktopDailyForecastModel` overlay and `TemperatureGraph` label | Literal `+2.5` in both views, not only equal to each other |
| `ForecastDeltaViewsIntegrationRoboTest` (`:app`) | integration | Fixture → `CurrentTempRun` → `TemperatureGraphRenderer` label plus daily overlay | Same literal `+2.5` |
| Desktop ghost case, in the integration test | integration | 11:00 ghost value is 64.08, not 65.5 | Literal |
| Desktop pan case, in the integration test | integration | Panning within range leaves the label value unchanged | Literal |
| `ForecastDeltaLineTest` | unit | Null, NaN, threshold, expected temps | Literals |
| `ForecastDeltaLabelTest` additions | unit | Every `decide` reason, in ladder order | Literals |
| `ForecastDeltaCallSiteGuardTest` (one each, `:app` and `:desktop`) | integration | Shared scan over each platform's real sources | Offender list is empty |

All carry the repository's test category annotations.

**Mutation checks, recorded in the commit body.** Each must fail for the right reason:

1. Restore `rawDelta` in `TemperatureGraph`: desktop integration test fails with `+3.9`; guard fails.
2. Remove the `run=newest_only` branch in the resolver: both integration tests fail.
3. Pass a stale or null delta from the popup: desktop integration test fails.

## Risk: visible step at the ghost line's start

Desktop's ghost line currently starts exactly on the last actual, only because its delta was measured
there. With the resolver's delta on a revision like today's, the ghost starts 1.4° below the actual at
10:35. Android already behaves this way, so this is parity, not a new rule. Check it on screen; if it
reads as a defect, bring it back to the user rather than reintroducing a second delta. The ghost's
position after the transition is the part that must stay correct.

## Verification

1. `./scripts/unit-tests.sh`, plus the three mutation checks.
2. `./scripts/emulator-tests.sh -c com.weatherwidget.widget.TemperatureGhostLineTest`.
3. `./gradlew :desktop:createDistributable`, restart desktop, compare daily and hourly on Open-Meteo
   and one other source in `autostart-*.log`, and screenshot both views, including the ghost start.
4. `./gradlew installDebug` and an Android render check, since the renderer's gate moved.

## Out of scope

- The resolver's run selection is unchanged. +2.5 is the intended number.
- The header's current temperature reads `current_status` while both delta views read
  `snapshot.resolved`. Both delta views stay on the same field, so they cannot disagree.

## Verification results (2026-10-30)

Implemented as planned, with these differences:

- `TemperatureGraph.nowMs` and `WidgetPopup.nowOverrideMs` were added so the integration test can fix
  now at 10:48. The fixture is dated today because the hourly read ages rows out by the wall clock.
- `ForecastDeltaLabel.hiddenReason` is the gate named `decide` above, and `ForecastDeltaLabel.Outcome`
  is `ForecastDeltaOutcome`. `Outcome.expectedTempsByHour` reports the ghost line for the test.
- The Android integration test is `MediumDuration`: Robolectric is not allowed in `ShortDuration`.
- The fixture's observations use the default actuals provider for an unknown location, not NWS.
- Desktop now logs `ForecastDeltaDiag`, Android's line. The missing log is why +3.9 had to be derived.
- The call-site scan needed no allowed entries.

Mutation checks:

| Mutation | Result |
|---|---|
| Restore the graph's local delta | Desktop integration: expected `+2.5`, was `+3.9`; desktop guard fails |
| Disable the newest-run branch in the resolver | Both integration tests: expected 2.48, was 3.8966675 |
| Popup passes a null delta | Desktop integration: label reason `no_delta` |

- `./scripts/unit-tests.sh`: 6420 tests passed.
- `./scripts/emulator-tests.sh -c com.weatherwidget.widget.TemperatureGhostLineTest`: 2 passed.
- Desktop restarted, Open-Meteo, 11:10: daily `+2.5 fcst`, graph `+2.5 from forecast`,
  `ForecastDeltaDiag reason=drawn value=+2.5 appliedDelta=2.53`. The ghost line starts cleanly at the
  dot, so the step in the Risk section did not show on today's data.
- `./gradlew installDebug`: installed on 3 devices.
