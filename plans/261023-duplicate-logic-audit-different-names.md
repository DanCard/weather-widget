# Android ↔ desktop: duplicate logic under different names — audit 2026-10-23

User, 2026-10-23: "We found some duplicate code in android and desktop. Wondering if you want to
conduct an audit and try to find more duplicate code that should be in shared code."

Audit only — no code changed. Follows `261021-android-desktop-shared-code-audit.md` (A, C, D, part of
B and G done; E, F, G-rest, H–K open). Today's duplicate — `drawWeatherAdaptiveBar` / `drawAdaptiveBar`,
the whole daily-bar split written twice — was invisible to `CrossPlatformDuplicateNamesTest` because
the names differed. This round looks for **same logic, different names**.

## Method
1. **Twin files**: every app/desktop main file pair, scored by the `:shared` functions they both call
   (rarer names weigh more). A high score = two files writing the same glue around shared pieces.
2. **Duplicate log tags / constants**: string tags (`"FOO_BAR"`) and `const val`s written in both
   `app/src/main` and `desktop/src/main` but not in `:shared`. A tag logged on both sides means the
   code that logs it exists on both sides.
3. **Hand-sync comments**: "matches / mirrors / like Android" in desktop — 70 of them (15 in
   `DailyForecastGraph`, 9 `TemperatureGraph`, 6 `DesktopWidgetHeader`, 5 `DesktopWeatherService`).
   Each is a promise kept by hand.
4. Spot-read the top pairs for divergences.

## Findings

### 1. Daily column + bar inputs (audit item F) — divergences found
| | Android | Desktop |
|---|---|---|
| Noon cloud from the row | `DailyViewLogic`: only if `weather.source == displaySource` | `DesktopDailyForecastModel.buildDay`: only if `!isClimateNormal`; no source check |
| Bar base colour | shared `forecastColor(isSunny, isBarRainy, isMixed)` | own `forecastColor(day)` + extra "cloud < 60 % → gold" rule |
| Snapshot / prior bar colour | `snapshotBarOverrideArgb ?: defaultColor` in `DailyBarRenderer` | same call + `Color.Yellow`, twice, in `DailyForecastGraph` |
| Stand-in colour in the bar's clear share | `isSourceGapFallback` | `isClimateNormal` |
| Thermostat bulb | `BULB_RADIUS_SCALE 1.2`, `BULB_VERTICAL_CENTER_FRACTION 0.5` | same numbers, own constants ("Mirrors Android") |

Twin pair score 15.4 (`DailyViewLogic` ↔ `DesktopDailyForecastModel`) and 10.4 (`DailyTodayResolver` ↔
`DesktopDailyForecastModel`). Recommendation unchanged from 10-21 F, now with evidence: one shared
`DailyColumnBuilder` producing each column's values **and bar inputs** (noon cloud, rain %, bar /
snapshot colours, stand-in flag), so renderers take numbers, not rules.

### 2. Post-fetch side jobs (audit item B, itemized) — same planner, glue twice
Each pair below calls a shared planner but writes the throttle, window, store, gate and log on both
platforms (tags logged on both sides in brackets):

| Job | Android | Desktop | Drift seen |
|---|---|---|---|
| Prior-day cloud (`PRIOR_CLOUD*`) | `ForecastFetchCoordinator.fetchPriorDayCloudForecast` | `DesktopWeatherRepository.maybeFetchPriorDayCloudForecast` | past days 31 vs `MAX_BACK_HOURS/24+1`; Android throttles before the call, desktop after; failure tag `PRIOR_CLOUD_FAIL` vs `PRIOR_CLOUD`. Allowlisted as ADAPTER, but it is a twin |
| NWS station actuals (`NWS_STATION_ACTUALS*`) | `NwsApiDailyActualsFetcher` (139) | `fillNwsStationActualsIfNeeded` (~125) | station list source / `MAX_STATIONS`; log fields differ (`dates=` desktop only) |
| WeatherAPI history (`WAPI_HISTORY_*`) | `WeatherApiHistoryBackfiller` (222) | repository `:1020–1140` | — (not diffed) |
| Nowcast refresh (`NOWCAST_*`) | `NowcastRefresher` (88) | repository `:1150–1180` | skip logged `Log.d` on desktop only |
| Hourly on demand / trim (`HOURLY_ON_DEMAND`, `HOURLY_TRIM`) | `HourlyOnDemandWorker`, `HourlyForecastStore` | repository `:440–470`, `:939` | — |
| Tomorrow.io 5-min (`TMRW_5M_FETCH`) | `CurrentTempRepository`, `ForecastFetchCoordinator` | repository `:1350` | — |
| Uncovered lows (`TODAY_LOW_UNCOVERED`, `DAILY_HISTORY_KEPT_UNCOVERED`) | `DailyActualsStore` | repository `:1413`, `:1648` | — |
| Climate fetch (`CLIMATE_FETCH_EMPTY`) | `ClimateNormalsRepository` | repository | — |
| Current observation (`OBS_ATTEMPT_TOUCH`, `OBS_CLOUD_CARRIER`, `NWS_STATION_FAIL`) | `NwsCurrentObservationUpdater` (216) | `DesktopWeatherService` | — |

Each is a small Runner + Host (the CLAUDE.md rule), independent of the rest of B.

### 3. UI flows written twice
Tags on both sides: `SOURCE_SWITCH_FETCH`, `LOCATION_FETCH_PENDING`, `CLICK_DAILY`, `REFRESH_TOAST`,
`HISTORY_REFRESH_DONE`, `SIM_LOOP_ERROR` / `SIM_LOOP_FALLBACK`, `HEADER_FIT` (measuring glue around
shared `HeaderFit`), `TIMEZONE_CHANGE`, `FETCH_PLAN`, `SOURCE_FETCH_GATE`, `SOURCE_VIEW_PROBABILITY`,
`DOMINANT_TEMP_WATCH`, `GOOGLE_REQUEST`, `GOOGLE_HOURS_PAGES`. Some are thin adapters (the gate loaders,
`GOOGLE_REQUEST` interceptor); the source-switch, location-pending and day-click flows are decisions
and belong with item J (tap zones) as shared coordinators.

### 4. Hourly window and zoom (audit item G)
- Rounding the window centre to the nearest hour (`minute >= 30 → +1 h`) is written 3×: desktop
  `temperatureGraphHourWindow`, Android `PrecipViewHandler:610`, `TemperatureGraphHoursLoader:92`.
- `MIN/MAX_HOURLY_OFFSET = ±720` in `WidgetStateManager` and `DesktopWidgetPopup`.
- Still needs the zoom-model decision from 10-21.

### 5. Small items
- Observations tabs `TAB_BLEND/OBSERVATIONS/FETCH_LOGS`, `BLEND_TABLE_POINTS` in both screens.
- `HISTORY_VACUUM_MIN_FREE_BYTES` **differs**: Android 8 MB, desktop 16 MB. It is the DB's
  reclaimable (freelist) space that makes a VACUUM worth running, not free disk. **Decided (user,
  2026-10-23, on recommendation): 8 MB on both**, one constant in `HistoryPruneSchedule` — the VACUUM
  only runs on a charger with the screen off and took 0.16 s for 100 MB on desktop, so there is no
  reason to leave 8–16 MB of dead pages in the file.
- Still pending from 10-21 K: `calculateRainAmountPlacements`, `columnsForWidthDp`,
  `formatRelativeTime`, `rainLoopErrorText`, `tempToColor`.

## Guardrail gap and fix
`CrossPlatformDuplicateNamesTest` only sees equal names. Add a **duplicate log-tag test** beside it:
fail when a tag literal is logged in both `app/src/main` and `desktop/src/main` but not defined in
`:shared`; seed the allowlist with today's 34 (each with a reason / pending item) and shrink it as
jobs move. Log tags are already the user's grep contract, so defining each next to its shared rule
is right on its own terms — and a twin can no longer hide behind a new name as long as it logs.
Also re-label `fetchPriorDayCloudForecast` in the name allowlist from ADAPTER to PENDING.

## Suggested order
1. Log-tag guardrail + allowlist re-label (small, stops new twins).
2. F: shared `DailyColumnBuilder` incl. bar inputs — the area with live divergences and today's bug.
3. Section 2 jobs one at a time as Runner + Host, smallest first: prior-day cloud, nowcast refresh,
   NWS station actuals, WeatherAPI history, current observation.
4. G after the zoom-model decision; E (current temp) after a closer read.

## Decisions
- G: Android cannot zoom smoothly — a widget's only input is a tap (RemoteViews: no pinch, scroll or
  long-press; `ZoomStage.next`), and each step is a new bitmap. Proposed: **the span in hours is the
  shared input**; label interval, smoothing, date mode and footer labels are functions of it
  (`HourlyAxisPlan`). Android's stages become preset spans, desktop's wheel any span — same rule at
  the same span. **Approved by the user 2026-10-25** ("ok on G").
- Vacuum threshold: 8 MB on both (decided).

## Progress
- 2026-10-23: §1 daily columns → `:shared` `DailyColumnBuilder` (graph and text, both platforms;
  `plans/261023-shared-daily-column-builder.md`).
- 2026-10-23: §2 prior-day cloud → `:shared` `PriorDayCloudRun` + Host. Same 31 days on both
  (desktop's `720/24+1`); the Open-Meteo gate moved into the rule (desktop asked every source and got
  an hourly empty result); desktop now logs `PRIOR_CLOUD*` to app_logs like Android; one failure tag
  `PRIOR_CLOUD_FAIL`. Tag allowlist −2.
- 2026-10-23: §2 nowcast refresh → `:shared` `NowcastRefreshRun` + Host (gate inputs, policy call,
  `NOWCAST_*` logs). Desktop now skips the hourly read when Open-Meteo isn't displayed, as Android
  did. Each platform keeps its source scope for the "rain plausible?" read (Android every source in
  the box, desktop its usable sources). Tag allowlist −3.
- 2026-10-23: §2 NWS station actuals → `:shared` `NwsStationActualsRun` + Host: missing-date rule,
  5-station cap, stored-observation fallback (`StationDailyExtremes`), row updates
  (`applyPulled` / `applyCached`, also used by Android's `NwsStationActualsStore` for its second
  caller) and every `NWS_STATION_ACTUALS*` log. Desktop now skips unchanged rows (rewrote them every
  run), logs a failed station-day to app_logs (console only before) and writes
  `NWS_STATION_ACTUALS_CACHED`; one `NWS_STATION_ACTUALS_OUTCOME` format. The run's date range and
  the planner now read "today" from the same `nowMs`. Tag allowlist −3.
- 2026-10-23: §2 WeatherAPI history → `:shared` `WeatherApiHistoryRun` + Host (coverage read, policy
  decision — also desktop's "fetching" check —, partial-day retry, `WAPI_HISTORY_*` logs). Both store
  the day as observations (Android via `saveHistoricalActuals`, desktop via `HistoricalActualsBackfill`);
  Android also forces the day's extremes recompute, desktop's post-fetch pass recomputes. Logs now one
  format (desktop's covered / cooldown checks reach app_logs at VERBOSE; `daily=` on desktop). Tag
  allowlist −2 (34 → 24).
- 2026-10-23: §2 current observation — partial. The per-station log lines (`OBS_CLOUD_CARRIER`,
  `OBS_ATTEMPT_TOUCH`, `NWS_STATION_FAIL`) come from `:shared` `NwsStationAttemptLog` (one format;
  `secondary=` → `web=`), and desktop's fetchedAt touch is site-scoped like Android's (it stamped the
  station's newest row at any site — a previous one after a move). Not shared: the flow itself —
  Android fetches the latest reading here and the history window elsewhere, desktop both per station
  in one pass, so the "silent vs failed" verdict reads different outcomes. One runner needs the two
  fetch shapes aligned first: **follow-up decision**. Tag allowlist −3 (→ 21).
- 2026-10-23: current observation, completed (user chose option A): both platforms' cycle is
  `:shared` `NwsStationBundles` — latest + last 90 min per station; Android stored latest only and
  repaired gaps later (`plans/261023-nws-observation-cycle-latest-plus-recent-window-both-platforms.md`).
  One station throwing no longer cancels the pass (desktop had no guard). Header blend still per
  platform (`interpolateIDW` vs `NwsBlend.build`) — item E.
- 2026-10-23: §2 daily blend write → `:shared` `DailyBlendWrite` (Android's `persistExtremes`, moved).
  Desktop bugs fixed: it compared against the first `daily_history` row anywhere in the ~7-mile box
  (a neighbouring site's row got this site's blend — test: 66.5 → 63.0 on the old code) and rewrote
  every row in full; now this site's rows only, unchanged rows skipped, and a conditional
  field-limited UPDATE (`DesktopWeatherDao.updateBlendIfUnchanged`, as Android's). The
  `TODAY_LOW_UNCOVERED` line is `DailyActualsAssembler.suppressedLowLines` (desktop gains `span=`).
  Tag allowlist −2 (→ 19).
- 2026-10-23: §2 climate normals → `:shared` `ClimateNormalsRun` + Host. Desktop now honours the
  Open-Meteo gate (it fetched the archive with Open-Meteo disabled; `savedConfigHasOpenMeteo`, read
  when asked) and no longer swallows cancellation (`catch (e: Exception)`); one log set
  (`CLIMATE_SKIP_DISABLED`, `CLIMATE_FETCH_EMPTY` WARN, `CLIMATE_CACHED`, `CLIMATE_WARM_FAIL`) on both.
  Tag allowlist −1 (→ 18).
- 2026-10-23: climate normals no longer gated on Open-Meteo being among the user's sources (user:
  "Climate normals should be independent of open-meteo api disabled") — the archive is free and
  keyless, normals are site data. Gate, `CLIMATE_SKIP_DISABLED`, Android's `isSourceVisible` read
  and desktop's `savedConfigHasOpenMeteo` removed; both platforms fetch on a cache miss.
- 2026-10-23: §2 hourly trim → `HourlyOnDemand.trim` + `TRIM_TAG` (one split, one line). Same rule
  before; only the code and the line's `horizonH` (desktop printed the configured hours, Android the
  hours from now) differed. Tag allowlist −1 (→ 17).
- 2026-10-23: §2 on-demand hourly → `:shared` `HourlyOnDemandRun` + Host (re-check, fetch, one
  `HOURLY_ON_DEMAND` line: `outcome=covered_no_fetch|stored|empty|failed hours storedRows storedLast
  fetchMs`, Android appends widget/announce/startDelayMs/paintMs). Android now re-checks coverage at
  run time like desktop (a gap-fill or earlier tap that stored the day costs no second fetch);
  desktop now reports a failed fetch as `outcome=failed` WARN in app_logs instead of throwing to the
  popup. Tag allowlist −1 (→ 16).
- 2026-10-23: §2 last fetch-job tags. `GOOGLE_HOURS_PAGES` → `GoogleWeatherApi.HOURS_PAGES_TAG` +
  `hoursPagesLine(onDemandDay)` (Android's on-demand page log lacked `trigger=on_demand_day`).
  `FETCH_PLAN` → `ForecastParts.PLAN_TAG` — **label only; the planning is still a twin (open
  item below)**.
  `TMRW_5M_FETCH` → `TomorrowIoActuals.FETCH_TAG` + `fetchLine` + `replacements` (same-site key
  count, written twice). Fetch-job allowlist group empty; tag allowlist → 13 (5 adapters, 8 UI flows).
- **OPEN — fetch planning is still written twice** (side-agent review, 2026-10-23). Sharing the
  `FETCH_PLAN` label took it off `CrossPlatformDuplicateLogTagsTest`, but the code that picks which
  sources to judge and when to log is per platform: Android `ForecastRepository.logFetchPlan` judges
  every *visible* source and logs on every sync (`parts=none` included); desktop `DaemonRuntime` +
  `DesktopForecastPlanner` judges the displayed source + `SourceFetchGate` survivors (with
  `ForecastSchedule.heldPast`) and logs only when something is due. Target: a `:shared` plan runner
  (sources in → per-source decision, due parts, the line) both platforms call. Lesson for this audit:
  a shorter allowlist is not proof — a tag made a shared constant hides from the guard test, so each
  removal must say whether the surrounding logic moved too.
- 2026-10-23: **closed** the open fetch-planning item → `:shared` `ForecastFetchPlan` (candidates +
  per-source decision → parts, horizon, next due held past a full quota block, `FETCH_PLAN` line).
  Android `ForecastFetchCoordinator.planSync` (planFetch / requiresNetworkFetch read it) and desktop
  `DesktopForecastPlanner` are its hosts; Android's `logFetchPlan` and `routineParts` are gone.
  Settled differences: the line judges only the candidates (Android listed gated-off sources too), and
  desktop logs every plan, `parts=none` included (it logged only when something was due).
- 2026-10-23: §3 day tap → `:shared` `DayTap` (outcome Fetch/NoData/Open from `hoursToCover` +
  `hasHourly`; the `CLICK_DAILY` line with platform extras; the `CLICK_DAILY_NO_HOURLY` line). Desktop
  now writes `CLICK_DAILY_NO_HOURLY` too. Still per platform, by design: the effects (Android's
  transient message + worker + delayed repaint; desktop's popup banner state) and how each reads the
  day's stored hourly (Android DB, desktop the loaded snapshot). Tag allowlist → 12.
- 2026-10-23: §3 source becomes primary → `:shared` `SourceSwitchFetchRule` (drawable-cache test with
  the 24 h age bar, banner/forced decision, start and finish `SOURCE_SWITCH_FETCH` lines). Desktop
  bug fixed: its cache test had no age limit, so a week-old cache with rows dated today counted as
  drawable — no banner and no fetch. Still per platform: Android's widget banner + expedited worker,
  desktop's popup banner (and its 4 s failure message); with a fresh cache Android queues an unforced
  sync, desktop leaves it to the forecast loop. Tag allowlist → 11.
- 2026-10-23: §3 location change → `:shared` `LocationChangePaintPolicy` already held the decision;
  it now also owns the `LOCATION_FETCH_PENDING` tag, the action names (`Action`, with levels) and the
  line (`action=… place=… [extra]`; desktop led with `place=`), and the hourly age test
  (`hasFreshHourlyForToday`, each row's own `fetchedAt`). Desktop bug fixed: its cached read stitches
  today's elapsed hours in from history snapshots past the 24 h live-row limit, so a two-day-old site
  was adopted with no banner. Android's interstitial-mode `cached_rows_adopted` is now `cache_adopted`,
  as desktop. Still per platform: where the cache is read (Android DAO + `HourlyForecastLoader`,
  desktop `loadCached`) and the effects (widget banner/interstitial + forced worker; popup banner,
  interstitial and in-process refresh). Tag allowlist → 10.
- 2026-10-23: §3 refresh notice → the text and log detail were already `:shared` `RefreshNotice` /
  `RefreshOutcome`; now also the one-per-4 s throttle (`RefreshNotice.Gate`, `admit` / `restart`), the
  `REFRESH_TOAST` lines (`line`, `outcomeLine`) and the `HISTORY_REFRESH_DONE` line (`RefreshOutcome.doneLine`
  / `doneLevel`). Android bug fixed: its History refresh ending was a bare Toast — it bypassed the
  throttle (a queued toast could show over it) and wrote no `REFRESH_TOAST` row; it now goes through
  `RefreshToast.showOutcome`, as desktop. Still per platform: the surface (Toast / widget banner vs the
  Compose pill) and the strings (Android resources, desktop `RefreshNotice.text`). `showOutcome` is on
  the names allowlist as ADAPTER. Tag allowlist → 8.
- 2026-10-23: §5 small items / 10-21 K — the five `PENDING` names, all resolved (names allowlist has
  no PENDING group left):
  - `columnsForWidthDp` → `:shared` `DailyLayoutConstants.columnsForWidthDp(widthDp, maxColumns)` /
    `rowsForHeightDp`, with `COLUMN_FIT_BIAS_DP` / `ROW_FIT_BIAS_DP`. **Desktop drift fixed**: its
    `+15` was Android's value before `457027da` (2026-06-20, 15 → 30); popups 163–177, 218–232, 273–287,
    328–342, 383–397 and 480–494 dp now show one more column, as the widget does. Desktop keeps only
    its 9-column cap. Width tests moved to `:shared` `DailyLayoutConstantsTest`.
  - `tempToColor` wrappers gone; the twin underneath was the gradient stop list (Android
    `buildTempGradient`, desktop `buildColorStops`; deduped on `"%.4f".format` vs `(pos*1000).toInt()`)
    → `TemperatureColorModel.gradientStops`.
  - `rainLoopErrorText` → `RainLoopLoadError.message()` (line + args) and `Line.english`; Android maps
    the line to its resource, desktop prints `englishText()`. **Desktop bug fixed**: a GONE/HTTP error
    with no status read "HTTP null". `RainLoopErrorLineParityTest` (app) pins each English template to
    `values/strings.xml`.
  - `formatRelativeTime` was a name coincidence (panel tooltip "2h ago" vs History's duration); the
    real twin was Android History's own "1h 20min" vs desktop History's `AgeFormatter.formatDuration`
    ("1h 20m"). Android History now calls the shared one (translated "just now" passed in) — Android's
    History freshness line now reads "m", not "min".
  - `calculateRainAmountPlacements`: the rule inside (4 dp padding) → `RainAmountLabelPlacer.PADDING_DP`;
    the wrapper stays as ADAPTER (rect types, Paint vs TextMeasurer bounds).
- 2026-10-24: §4 hour rounding + §5 small items (no behaviour change):
  - The hourly window — centre rounded to the nearest hour half up, then `−back … +forward` — was
    written 8× (Android `TemperatureHourDataBuilder`, `CloudCoverViewHandler` ×2, `PrecipViewHandler`,
    `TemperatureGraphHoursLoader`, `GraphDataLoader`; desktop `temperatureGraphHourWindow`; `:shared`
    `ActualTemperatureSeriesBuilder`, `CurrentTemperatureResolver`) beside an unused shared
    `alignToNearestHourHalfUp`. Now `:shared` `WeatherTimeUtils.hourWindow` → `HourSpan` (or the
    rounding alone).
  - `ActualsAggregator.alignedCenterMs` (the current-observation blend's centre) rounded epoch millis
    on **UTC** hours — :30 local in half-hour zones (India, Newfoundland). Now `alignedCenterMs(nowMs,
    zoneId)` on the shared local rule (user, 2026-10-24); no change in whole-hour zones. Its doc said
    "nearest 30-minute boundary" — it was always the nearest hour (window changes once per hour).
    Test: Kolkata 14:50 → 15:00 local (old code: 14:30).
  - `MIN/MAX_HOURLY_OFFSET` (±720 h) → `NavigationUtils`, beside the daily bounds.
  - Observations `TAB_BLEND/OBSERVATIONS/FETCH_LOGS` + `BLEND_TABLE_POINTS` → `:shared`
    `ObservationsTabs`; the ids are persisted (Android `last_selected_obs_tab`, desktop
    `obsSelectedTab`), pinned by `HourWindowTest`.
  - Tests: `:shared` `HourWindowTest` (half-up boundary, span across midnight, tab ids); full unit
    suite 5,863 passed.
- 2026-10-24: 10-21 H, WeatherNext loop player → `:shared` `WeatherNextLoop` (manifest load with the
  day-long gone memory behind a `GoneStore`, `framesFor`, `prefetch` with `stillWanted`, `advance` /
  `step` / `SPEEDS`, the `SIM_LOOP_ERROR` / `SIM_LOOP_FALLBACK` lines). Desktop bugs fixed: a layer
  switch didn't stop the old layer's prefetch (window scope), which kept downloading and wrote into the
  new layer's slots (`RainForecastLayerSwitchTest`: 4 old-layer requests on the old wiring, 1 now);
  "host gone" was in memory and lost on every restart (`DesktopWeatherNextGoneStore`, file
  `weathernext_gone`). Tag allowlist → 6 (5 adapters + `HEADER_FIT`); no `UI_FLOW` entry besides it.
  Plan: `plans/261024-shared-weathernext-loop-player.md`.
- 2026-10-24: 10-21 I, hourly precip/cloud inputs. Most was shared already (`ObservedPrecip.rowsFor` /
  `byHour`, `CloudActualSeries.points`, `RainPeriodSelection`, `RainAmountLabelPlacer`;
  `actualPrecipRowsForSource` was gone). What was still written per platform, now `:shared`:
  - `ObservedPrecip.elapsedHourAmount` — an hour not begun has no rain actual (desktop
    `buildRainHours`, Android `PrecipViewHandler`).
  - `CloudActualSeries.pointsUntil` — the drawn cloud curve capped at now (desktop
    `cloudActualPlotRange`, removed; Android read a second clock, `System.currentTimeMillis()`,
    instead of the render's `now`).
  - `ActualsProviderResolver.showsBorrowedLabel` — "Actuals: X" only for a borrowing source with
    actuals drawn; 4 copies (cloud + temperature, both platforms). Android's cloud graph keyed on the
    hourly vertices' snapped `actualCloudCover`; now on the drawn curve, as desktop.
  - Tests: `:shared` `HourlyActualsInputsTest` (took over `CloudCoverActualPlotRangeTest`'s pre-roll
    case); suite 5,878.
- 2026-10-24: 10-21 J, tap zones — **closed; mostly not a twin.** Where a tap lands is platform input
  by nature (Android widget: one view per zone, no coordinates; desktop: x/y against computed
  geometry); what a zone opens (`DayClickResolver.resolveView`), the icon's home view
  (`WeatherConditionResolver.resolveIconHome`, desktop `WeatherIcon` adapts it) and the header buttons
  (`HeaderFit` / `HeaderButtons`) were shared already. The one twin: the hour a tap zooms on →
  `HourlyZoomRules.tapOffsetHours(fraction, startOffsetHours, spanHours)`; Android
  `HourlyTouchZoneMapper` passes zone / 12 (same results for every stage, span, offset and zone —
  `TapOffsetHoursTest`), desktop the pointer's x for tap and wheel. **Desktop bug fixed:** both
  computed the instant as `Long + Float` (Float, 131 s steps at epoch size), so a tap or wheel a couple
  of minutes past a half hour centred the hour before (`TapZoomOffsetTest`: −5 on the old math, −6).
- 2026-10-24: 10-21 E, current temperature — **phase 1 done**, phase 2 open
  (`plans/261024-shared-current-temp-run.md`). `:shared` `CurrentTempRun` is desktop's pipeline and
  the Android Months screen's; the stored delta and the raw-row fallback are gone on both platforms
  (user decisions after review); Android's no-repository window fixed (2 h → 3 h). Android's widget
  header paths still wire the shared pieces themselves — phase 2.
- 2026-10-24: E, phase 2 done: every Android header path runs `CurrentTempRun` (`observe` + `header`);
  the yesterday delta is pan-independent on every view (`plans/261024-shared-current-temp-run.md`).
- 2026-10-24: E, NWS fetch-time reading → `:shared` `NwsBlend.fetchReading` on both platforms; the
  displayed `NWS_BLEND` was already shared (`NwsBlend.current`). Desktop's no-blend fallbacks dropped
  (user): no forecast or stale station as "current"
  (`plans/261024-nws-fetch-time-current-reading-shared.md`). **E closed.**
- 2026-10-24: G, the rest. The span-keyed tables were already shared (`HourlyZoomRules`, user
  2026-10-21; the "awaiting decision" above was stale). The footer-label selection →
  `:shared` `HourlyFooterLabels` (`plans/261024-shared-hourly-footer-labels.md`): Android labelled
  from the window's left edge (moved on every pan) and its precip/cloud graphs always labelled now;
  both platforms now label tidy clock hours, no "now" exception (user). **G closed.**
- 2026-10-24: `HEADER_FIT` → `:shared` `HeaderFit.LOG_TAG` / `logLine` (one line format, both
  platforms); measuring stays per platform (Paint vs TextMeasurer). Tag allowlist → 5 adapters; the
  `UI_FLOW` category is empty and removed. The duplicate-name allowlist holds only ADAPTER / DRAWING /
  GENERIC entries.

- 2026-10-24: Tier 3 started — Host contract tests (`plans/261024-host-contract-tests-room-and-desktop-sqlite.md`).
  The "known differing read" above was a misreading: Android `getForecastsInRange` and desktop
  `getForecastsInRangeBySource` are different reads; the same-named pair returns the same rows. First
  suite: `:shared` testFixtures `DailyHistoryHostContract`, run on Room (`RoomDailyHistoryHost`) and
  desktop SQLite (`DesktopDailyHistoryHost`). Found and fixed: desktop's `hourlyHistory` returned its
  display read (one merged row per hour across the box's sites, end hour included, future `Generic`
  rows); now raw snapshots `[start, end)` like Android (user: option A) — the stitcher already merges.
  No visible change (the readers are one-time past-day backfills). Guard `HostContractCoverageTest`:
  10 ports pending.
- 2026-10-24: `ClimateNormalsRun.Host` contract (Room / desktop SQLite, 4 cases) — no divergence. 9 pending.
- 2026-10-24: `PriorDayCloudRun.Host` contract (5 cases, store → read-back). Desktop's prior-cloud read
  merged a neighbouring town's rows and included the end hour; now `:shared`
  `PriorDayCloudForecast.coverByHour` on both (user: Android's rule). 8 pending. The band curve's read
  (`getPriorDayBandForecast`) had the same gap on desktop → `PriorDayBandForecast.selectAtSite` on both.
- 2026-10-24: `NwsStationActualsRun.Host` contract (6 cases). The past-day blend's NWS hourly: Android
  live only, desktop live + history; now raw reads stitched in the shared run (user: fill on both).
  7 pending. Open: the live-day blend's hourly input differs by platform (not a Host read).
- 2026-10-24: `HourlyOnDemandRun.Host` contract — no divergence. 6 pending. Open (both platforms):
  `hoursToCover` counts a neighbouring town's rows in the box as coverage.
- 2026-10-24: `WeatherApiHistoryRun.Host` contract — desktop's coverage read narrowed to WeatherAPI rows
  (no behaviour change). 5 pending. Desktop stored observations at raw coordinates,
  Android quantized → desktop quantizes too, existing rows moved at startup (user: desktop may be a laptop).
- 2026-10-24: `ElapsedForecastBackfill.Store` contract — no divergence. 4 pending. Open: snapshot bucket
  width (Android 4 h displayed / 12 h background, desktop 4 h for every source).
- 2026-10-24: snapshot rule → `:shared` `HourlySnapshotRun` (user: displayed every fetch; others ≥ 11 h /
  22 h by battery; not viewed in 8 days never; fetch-time labels) —
  `performance/261024-hourly-snapshot-windows-by-view-and-battery.md`.
- 2026-10-24: `NwsStationBundles.Host` contract — no divergence. 3 pending.
- 2026-10-24: `NowcastRefreshRun.Host` contract; "rain plausible?" counts usable sources only on both
  (Android counted every row in the box; user). 2 pending.
- 2026-10-24: `GoogleHistoryRun.Host` contract; live hours read every same-site fragment on both
  (Android read the exact key only; user). 1 pending.
- 2026-10-24: `CurrentConditionsRun.Host` contract (stores only); the low-priority throttle moved into
  the run, Android's rule — a clock set back counts as due (desktop waited; user). **Tier 3 done:
  `HostContractCoverageTest.PENDING` is empty.**
- 2026-10-24: B rest started — `plans/261024-shared-forecast-persist-rules.md`. Phase 1 `:shared`
  `DailyForecastWrite` (prior = this site's row, unchanged rows re-stamped, NULL/NULL dropped, ended
  NWS period dropped; Android's rule, user), phase 2 `HourlyForecastWrite` (nullable fields kept,
  `fetchedAt` = last confirmed on both). Today's "yesterday's forecast" pick now also sees the shown row.
- 2026-10-24: B rest phase 3 — `:shared` `ForecastPersistRun` + Host owns the whole fetch→store order
  (live hours, snapshot + elapsed backfill under one label, SHORT daily filter, `DAILY_HOURLY_MISMATCH`,
  `DAILY_SUMMARY`, daily summaries, `DailyForecastWrite`); `ForecastPersistHostContract` on Room and
  desktop SQLite. Android's NWS now a `RawFetch` like every source (`NwsDailyMapper.dailyForecasts`); the
  per-source save paths (`fetchAndSaveSharedForecast`, `fetchFromSilurian`, `saveDailyBatch`, desktop
  `withHourlySummaries` / `backfillElapsedHistory`) are gone.
- 2026-10-25: B rest — observations phase 1 (`plans/261024-shared-observation-persist-rules.md`):
  `:shared` `ObservationWriteRun` + Host owns every `observations` write's rules (own `fetchedAt`,
  re-tag every api, personal thinning over the stored window, Tomorrow.io line per Tomorrow.io fetch,
  retired-product cleanup per api); `ObservationWriteHostContract` on Room and desktop SQLite. Desktop
  `persistObservations` and Android's NWS, current-conditions, provider-history and METAR writers go
  through it. Phase 2 (O3, Synoptic refresher, Observations screen) left.
- 2026-10-25: B rest — observations phase 2: O3 input rule shared; Synoptic refresher and Observations
  screen through `ObservationWriteRun`. Every Android/desktop observation write now goes through it except
  the WeatherAPI history Host's store. Left in B: the fetch registry / `refresh()` orchestration.
- 2026-10-25: B rest — fetch registry (`plans/261025-shared-source-fetch-table.md`). Phase 1: `:shared`
  `SourceFetchTable` owns each source's request (F1 current reading not filed from a forecast reply, F2
  Google stored hours on the exact key). Phase 2: `SourceFetchTable.runFollowUps` owns what rides a fetch
  (prior-day cloud, WeatherAPI history, NWS station actuals — incl. after a failed NWS fetch) on both.
  F6: NWS's forecast fetch is `:shared` `NwsForecastReply` on both, forecast only; desktop's NWS refresh
  takes its observations through the shared `fullRefreshFetch` pull. Next twin: the week's NWS gap-fill
  (Android `NwsObservationBackfiller` vs desktop `refreshObservationWindow`).

- 2026-10-25: NWS observation gap-fill (`plans/261025-shared-nws-gap-fill.md`). Phase 1: `:shared`
  `NwsGapFillRun.daily` — need-gated 3-day pass on both (desktop's 7-day pull on every wake removed).
  Phase 2: `NwsGapFillRun.recent` + shared `ObservationBackfillRetryPolicy`; desktop retries unreachable
  on the shared schedule. Device check on both phones and desktop recorded in the plan. **B closed.**

- 2026-10-25: hourly input of the stored recompute shared (`ActualsAggregator.aggregateStored`,
  `c06b7d3a`). Then the recompute loop itself (`plans/261025-shared-daily-blend-recompute.md`,
  `4ff650c7`): `:shared` `DailyBlendRecomputeRun` replaces Android's per-day body and desktop's window
  loop (which replaced rows at its edge); `daily_history` keyed on the rounded site on both
  (`DailyHistoryKey`, Room v83 + desktop merge); station-actuals today-row noise fixed.

## Next (handoff, 2026-10-25)
Every lettered item (A–K), B and the tag guardrail are done. Left:
1. ~~**Host-contract open items**~~ — all done 2026-10-25 (blend input, station actuals, on-demand
   neighbour coverage).
2. **Second audit round** — the guard tests see only equal names and logged tags. 30 hand-sync
   comments ("matches / mirrors Android") remain in `desktop/src/main` (2026-10-25: `DailyForecastGraph`
   8, `TemperatureGraph` 5, `SettingsWindow` 5, `DesktopWeatherRepository` 5); re-run the twin-file score. Cleanup to ride along: `ObservationResolver.computeDailyExtremes` has only
   test callers (19) since the shared recompute.
Process that worked: find the twin → write a plan with the divergences and any user
decision → approval → shared code + a test that fails on the old code → full suite incl.
`:app:compileDebugAndroidTestKotlin` → install both phones, restart desktop, check the log line on a
device → append Progress here.
