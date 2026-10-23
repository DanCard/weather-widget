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
  the same span. Awaiting the user's OK.
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
