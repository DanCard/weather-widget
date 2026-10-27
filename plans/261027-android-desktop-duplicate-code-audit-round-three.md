# Android ↔ desktop duplicate code — audit round three, 2026-10-27

User, 2026-10-27: "duplicate code audit. Desktop and android should work the same. there isn't a
reason for different code from a user perspective."

Audit only — no code changed. Follows `261021-android-desktop-shared-code-audit.md` and
`261023-duplicate-logic-audit-different-names.md` (A–D, F, most of B, K and §2–§3 done there).

## Method
1. Re-read both earlier audits' open items (E, G, H, I, J, Tier 3, the UI-flow tag allowlist).
2. **New scan: literals and constants.** Every `const val` and every string literal ≥ 12 chars written
   in both `app/src/main` and `desktop/src/main` but not in `:shared`. The name test and the log-tag
   test cannot see a twin that shares only a reason code (`"no_empty_band"`), a pref key
   (`"synoptic_fetch_backoff"`), a label (`"Historical Avg"`) or a format (`mm / 25.4f`). This scan
   found most of the items below.
3. Function lists of the large desktop files (`DesktopWeatherRepository`, `DesktopWeatherService`,
   `DaemonRuntime`, the graphs and windows), each spot-read against its Android counterpart.

## Findings, ranked by what a user would see

### 1. Observations screen, stations list — desktop is not location-scoped (bug)
| | Android `WeatherObservationsActivity.loadObservations` | Desktop `ObservationsWindow` |
|---|---|---|
| Read | the proximity box at the active location, collapsed to the nearest site that has rows for the displayed source (`LocationMatch.selectNearestSiteWith`) | `getRecentObservations(sinceMs)` — **no location at all** |
| Nothing in 24 h | `StaleObservationFallback` (older readings, with an age) | empty list |
| Latest per station, sort | yes | yes (`visibleStationRows`) |

So after a move (the notebook at the café), desktop lists stations from every place visited in the
last 24 h, and a site with no recent readings shows nothing where Android shows the stale ones.
**Fix:** a `:shared` stations-list rule (site choice, recent → stale fallback, latest per station,
order, the diagnostic line) with a Host for the two reads; both screens call it.

### 2. Current temperature (old item E) — one pipeline written ~6 times on Android, once on desktop
The header number goes window → observation (`ActualsAggregator.resolveCurrentObservation`) →
`CurrentTemperatureResolver.resolve` → `YesterdayDeltaCalculator.computeDelta`. Desktop does it in
`DesktopWeatherRepository.resolveForForecastResult`. Android assembles it separately in
`CurrentTempResolver`, `WidgetRenderer:441`, `TemperatureStateResolver`, `TemperatureViewHandler`
(3 sites), `DailyGraphRenderer`, `GraphDataLoader`, `DailyViewHandler`, `TemperatureGraphHoursLoader`.
- **Divergence:** Android passes a per-widget, per-source stored delta (`getCurrentTempDeltaState`)
  and keeps applying it after the observation leaves the 12 h window. Desktop always passes `null`,
  so in that case its header drops to the raw forecast while Android's stays corrected.
- **Fix:** `:shared` `CurrentTempRun` (window, the narrow filters, resolve, delta, the yesterday
  delta, log levels) with a Host for the delta-state store (Android prefs per widget; desktop a
  small store per source + site). Every Android caller and desktop's one function call it.

### 3. Saving a fetch (rest of old item B) — the per-source table and the save step, twice
- **Per-source fetch table:** Android `ForecastFetchCoordinator.buildFetchRegistry` (one lambda per
  source) vs desktop `DesktopWeatherService.fetchForecast` `when`. They agree today only because
  literals agree (Open-Meteo `historyDays = 7` vs `ACTUALS_HISTORY_DAYS = 7`; nowcast saved from
  the Open-Meteo response on both; prior-day cloud riding Open-Meteo).
- **Save step:** Android `fetchAndSaveSharedForecast` → `withPayloadSummaries` →
  `saveDailyBatch` → `withStoredHourlySummaries` (+ `ForecastSnapshotStore` carry-forward) vs
  desktop `persistForecastResult` → `withHourlySummaries` (two-input `DailyHourlySummaries.forDate`).
  Same rules called in different shapes. Differences to settle while moving: desktop drops daily rows
  dated before today at this step, Android doesn't; Tomorrow.io's historical rows (Android:
  `subHourly` only, never `hourly`; desktop: its own Tomorrow path) — verify each with a test.
- **Fix:** `:shared` `ForecastSaveRun` + Host (store hourly, store daily rows, store observations,
  record quotas): trim, horizon filter, summaries + carry-forward, `DAILY_SUMMARY` /
  `DAILY_HOURLY_MISMATCH` / `BACKFILL_CLOUD` lines. Then a shared source → request table (history
  days, parts, which side jobs ride which source). The largest item; do it after 1 and 2.

### 4. Climate-normal "Historical Avg" days — "covered" means different things
Android `ClimateGapFiller.coveredDates`: every date up to the *shortest* source's last day, across
all sources in the rows. Desktop `appendClimateNormalGaps`: dates the *displayed* source has (after
`PartialForecastDays.fillPartialFutureDays`), then `DailyColumnSource.allowsClimateNormal`. The
column builder hides most of the difference, but they can disagree on where filler days start.
**Fix:** `:shared` `ClimateGapRows` (covered rule, filler rows, the label) on both; pick
desktop's rule (the displayed source's own coverage), since that is what the column shows.

### 5. Hourly temperature graph annotations — the decision chain twice
Placement is already shared (`DominantStationLabel`, `GhostLineLabel`, `ForecastDeltaLabel`), but
each platform writes the gate order, the span check and the reason codes (`too_few_hours`,
`span_too_wide`, `no_empty_band`, `zero_delta`) and the "Actual temperature data from X" text around
it: Android `TemperatureGraphAnnotationRenderer` (890 lines), desktop `TemperatureGraph` (~200
lines of it; 9 "mirrors Android" comments). Android's cloud graph repeats the reason codes
(`CloudCoverGraphAnnotations`). **Fix:** a shared plan per annotation — inputs in, `Placed(rect)` or
`Suppressed(reason)` out, plus the diagnostic line; renderers only measure and draw.

### 6. Small items
- Radar window clock: desktop hard-codes `h:mm a` in US locale; Android follows the 24-hour setting
  (`DateFormat.is24HourFormat`). Desktop should use the locale rule that its other clocks use.
- Rain-map rate text (`mm / 25.4f` → "0.12 in/h", "1.5 mm/h") written in both
  `OpenMeteoRainLoopActivity` and `OpenMeteoRainLoopWindow` → `:shared` (units passed in).
- Player tick constants (`TICK_MS` 35 / 100 ms) in both radar and rain-loop screens →
  `RadarPlayback` / `RainLoopSpeed`.
- Observations tabs `TAB_BLEND/OBSERVATIONS/FETCH_LOGS`, `BLEND_TABLE_POINTS` (open since 10-23).
- Pref/marker keys written on both sides: `synoptic_fetch_backoff`, `charging_loop`,
  `viewing_forecast_due`, `settings_enable` → constants next to their shared rules.

### 7. Decided (user, 2026-10-27): remove desktop dismissal — done, see `261027-error-and-quota-banner-click-shows-details-both-platforms.md`
- **Quota banner dismissal.** Desktop lets the user dismiss a quota banner
  (`dismissedErrorTimestamp`, which hides blocks that began before the dismissal). Android has no
  dismissal; the banner stays until the block ends. CLAUDE.md says every quota refusal is bannered
  until reset. Recommendation: **remove desktop's dismissal for quota banners** (keep it for
  transient fetch errors), so both follow the documented rule.

## Still open from the earlier audits
- **G, hourly axis / zoom** — in progress on branch `worktree-hourly-axis-plan`
  (`261023-shared-hourly-axis-plan-and-two-and-a-half-day-view.md`). The 30-min centre rounding
  written 3× is part of it.
- **H, rain-loop player** (`RainForecastActivity` 518 / `RainForecastWindow` 605, one shared type
  each; `SIM_LOOP_ERROR` / `SIM_LOOP_FALLBACK` tags allowlisted for it).
- **Tier 3, the two DAOs**: shared contract tests per intent-named read (recommended over SQLDelight).
- `HEADER_FIT` tag: measuring glue around shared `HeaderFit`, still per platform.

## Checked and left platform-specific
- **I, precip/cloud series:** both build from `ObservedPrecip.rowsFor` + `byHour` and
  `RainPeriodSelection`; desktop filters in the caller. Nothing left that's worth moving.
- **J, tap zones:** zone enum and resolver are shared (`DayClickResolver`); Android's input is a
  RemoteViews zone and desktop's an x,y. The hit-test stays per platform.
- Curves: every `computeTangents` / `buildCurve` wrapper delegates to `:shared` `CurveMath`.
- Banner layout and stages: `FailureBannerStage` / `FailureBannerLayout` used on both.

## Guardrail
Add `CrossPlatformDuplicateLiteralsTest` beside the name and log-tag tests: fail on a string literal
≥ 12 chars, or a `const val` name, present in both `app/src/main` and `desktop/src/main` but not
in `:shared`. Allowlist today's set, each with a reason or one of the items above, and shrink it as
they move. Today's scan: 10 constants, 33 literals; many are user strings that legitimately live in
Android resources (allowlist those as `STRING_RESOURCE`).

## Suggested order
1. §1 stations list (a real bug; small; Runner + Host).
2. Guardrail (stops new literal twins).
3. §2 current-temp run (removes ~6 Android assemblies; fixes the delta-state divergence).
4. §4 climate gap rows, §6 small items, §7 once decided.
5. §5 annotation plans.
6. §3 save run, then the source → request table.
Each step: tests that fail on the old code for each divergence fixed, `scripts/staggered-tests.sh`,
desktop restart, and a device check of the screen it touches.
