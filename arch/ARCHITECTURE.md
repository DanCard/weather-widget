# Weather Widget architecture

Weather Widget renders the same weather content through an Android home-screen widget and a Linux
desktop app. Its main architectural constraint is parity: shared behavior belongs in one pure-JVM
implementation, with platforms supplying only the capabilities that differ.

## Modules

| Module | Owns | Must not own |
|---|---|---|
| `:shared` | Weather models, API clients, fetch/persistence policy, actuals, history rules, graph geometry, shared workflows | Android framework types, Room, RemoteViews, Compose, widget preferences |
| `:app` | Android lifecycle, Room entities/DAOs/migrations, Hilt, WorkManager/AlarmManager/JobScheduler, activities, RemoteViews and Canvas glue | A second implementation of shared weather rules |
| `:desktop` | Compose UI, Linux power/presence/session integration, desktop process lifecycle, SQLite host implementations | Android dependencies or duplicate shared policy |

The authoritative dependency versions are in `gradle/libs.versions.toml` and the module build files.
SDK levels are in `app/build.gradle.kts`.

## Shared runners and Host contracts

Cross-platform workflows are implemented as runners in `:shared`. A runner owns the whole behavioral
rule: source and site scope, time windows, operation order, retention/gating decisions, and log events.
Each platform implements the runner's `Host` interface for database access, system state, and other
platform capabilities.

Examples include:

- `ForecastPersistRun`: ordered hourly, elapsed-backfill, and daily-row persistence.
- `CurrentConditionsRun` and `CurrentTempRun`: observation/current-temperature behavior.
- `ObservationWriteRun`: every `observations` write — own `fetchedAt`, station-type re-tag on every api,
  personal stations thinned to one row per 10 minutes over the stored window (plus each station's
  newest), the Tomorrow.io fetch line, and retired-product cleanup per api written.
- `DailyHistoryRun` and `NwsStationActualsRun`: history maintenance and station-derived actuals.
- `NwsGapFillRun`: the need-gated 3-day NWS station-history fill (which days, which stations, when to stop).
- `DailyBlendRecomputeRun`: the stored daily blend recompute — one whole local day at a time, 9 days back,
  unchanged days skipped by observation signature, existing rows read by their exact date key.
- `WeatherApiHistoryRun` and `GoogleHistoryRun`: provider-specific history workflows.
- `SourceFetchTable`: what each source's forecast fetch requests, and (`runFollowUps`) which of the
  history runners above ride that fetch, a failed one included.
- `TargetedForecastSyncRun`: a forecast the user forced for one source — rain-chance freeze, paint, then a
  debounced post-sync tail for observations and the actuals recompute. Hosts: Android
  `AndroidTargetedSyncHost` (`weather_widget_post_sync_actuals` work), desktop `DesktopTargetedSyncHost`
  (`DesktopPostSyncTail`); see [cadence](forecast-and-observation-cadence.md#targeted-forecast-syncs).
- `SourceSwitchRunner`: a user's switch of the displayed source (API button, daily home button, and
  the Forecast Comparison and Current Observations screens' source pills). Persist then paint is the whole
  critical path; the view tally, the staleness check / refresh request and the `app_logs` row follow,
  and `SOURCE_SWITCH_E2E` times each phase. Hosts: Android `WidgetIntentActionHandler` (toggle and
  home reset, the deferred phase joining the receiver's job; screen pills via `switchFromScreen` on
  the application scope), desktop `DesktopSourceSwitch` (refresh is the daemon's `source_change`
  check). The data screens hold no source of their own: they show the main view's, so a switch in
  either place shows in both. Pill switches count as `COMPARISON` / `OBSERVATIONS` and, like the API
  button, as a choice away from the primary (`SourceViewProbability`).
  plans/261119-sync-data-screen-sources-with-main.md
- `DaySourceComparison`: the Forecast Comparison screen's "All sources for <day>" table. Each visible
  source's newest forecast for the day at the nearest site, run through `DailyColumnBuilder` with that
  source as the display source (so the high, rain %, peak hour and noon cloud match the widget's daily
  view), sorted by high by default; a source with no row is listed last. Columns: short name, high, low,
  Now (today only: the widget header's rain % over `headerRainLookaheadHours`, by the header's own
  `HeaderContent.headerRainProbability`, 0 % printed), Day rain %,
  peak hour (every day chance's peak, with no threshold; the daily view's
  `PEAK_HOUR_MIN_EXCLUSIVE_PERCENT` floor does not apply here), night rain, Total (8am–8pm hourly sum as on the widget; only when some row has one),
  noon cloud, 30-day
  high bias, and age since `lastConfirmedAt` (dimmed past 24 h). Hosts return every stored fetch and
  the runner stitches them (`HourlyForecastStitcher.stitchBySource`), the header's read, so a started
  hour keeps its pre-hour rain forecast in both (`DesktopHeaderRainParityTest`,
  `HeaderRainParityRoboTest`). Hosts: `RoomDaySourceComparisonHost`,
  `DesktopDaySourceComparisonHost`; `DAY_SOURCE_COMPARE` logs each build.
  Columns are sized to their widest text (`DaySourceComparison.layout`; each platform only measures
  text), spare width shared as even gaps. Now is offered first, on top of the widest step, and kept
  only with `HEADER_RAIN_MIN_GAP_DP` gaps ("plenty of width"); otherwise it goes before anything else.
  A table too narrow for `MIN_COLUMN_GAP_DP` gaps then steps
  down: whole-degree lows, then no Total, then both (`FitStep`; `DAY_SOURCE_LAYOUT` at VERBOSE).
  Desktop lays out on every width change and Android on each render (rotation recreates the
  activity), so Now comes and goes with the width. A two-level header puts "Rain" over the shown
  rain columns, Now through Total (`Layout.rainGroupBounds`).
  plans/261120-all-sources-header-rain-column.md
  Clicking a column header sorts by it (`Sort.tapped`: same column flips, a new column starts
  descending except Source A–Z, Peak earliest-first and Age freshest-first); blanks stay last and ties keep the source
  order. The sort is remembered (`source_compare_sort` pref / `DesktopConfig.sourceCompareSort`);
  `DAY_SOURCE_SORT` logs each tap. A "Set API order" button on the header row applies the table's
  sorted order to the user's enabled sources (`reorderedEnabledIds` / `canApplyOrder`, preserving any
  location-unavailable enabled sources) without fetching (`SourceListEdit`). A legend under the table gives one line per ambiguous column
  (`legendColumns`; wording per platform: desktop `LEGEND_TEXT`, Android `source_compare_legend_*`).
  Today only, a source whose later fetch succeeded but left today out (Silurian's UTC days after 4 PM
  Pacific: its tomorrow row confirmed more than a minute after its today row) gets `Row.noLongerSent`:
  Age reads `5h*` and the legend adds a `*` note (`showStarNote`; desktop `STAR_NOTE`, Android
  `source_compare_legend_star`). A failed refresh leaves tomorrow just as old, so it is never starred.
  The legend always ends with a line explaining the "Set API order" button (desktop `APPLY_ORDER_NOTE`,
  Android `source_compare_legend_apply_order`).

A shared planner that leaves ordering or policy in platform glue is not equivalent; that arrangement
previously allowed Android and desktop to drift. Host contract tests exercise the same runner against
both persistence implementations. `CrossPlatformDuplicateNamesTest` also guards against reintroducing
parallel app/desktop implementations of logic that belongs in `:shared`.

## Data and location identity

Android uses Room (`WeatherDatabase.kt`); desktop uses SQLite (`DesktopWeatherDatabase.kt` and
`DesktopWeatherDao.kt`). They are separate storage implementations of shared rules, not a shared ORM.
Schema changes require paired migrations and real-SQLite tests. Exported Room schemas are under
`app/schemas/`; schema version numbers belong in code, not in this overview.

Important persisted concepts are:

- `forecasts`: daily forecasts from every retained fetch, including far-day summaries and confirmation
  timestamps used to distinguish stable forecasts from newly observed rows.
- `hourly_forecasts`: the one hourly table. Every fetch appends its hours under its fetch time and never
  changes an earlier fetch's row. `SettledForecastPick` decides which row stands for an hour.
- `observations`: station/provider observations with provenance and quality metadata, kept until archived.
- `hourly_actuals`: the frozen blended result per hour, display source, and site, kept 18 months.
- `daily_history`: settled daily actuals, frozen forecast comparisons, and source/writer provenance.
  Keyed on the rounded site (`DailyHistoryKey`, `LocationMatch.quantize`) on both platforms.
- `app_logs` and API/network usage tables: diagnostics and quota visibility.

Location is part of data identity. `LocationMatch` defines coordinate quantization and the site-match
proximity rule. Writers and readers must use it consistently: a slight coordinate change must not
strand rows at an unreachable key. Site matching is distinct from the user's fixed/follow-device
location preference.

There is no fallback coordinate when no location is available: the UI asks the user to set one and
weather fetches do not use a stand-in site. Background/automatic Android paths use passive last-known
location only; an active precise fix is reserved for the explicit foreground location action. Fixed
locations remain pinned, while follow-device mode applies a genuinely different weather site without
a candidate/readiness holding period.

Retention is not a flat 30-day policy. See [data pruning](data-pruning.md) for active snapshots,
settled history, observations, logs, and usage data.

## Fetch and persistence pipeline

Android entry points include `WeatherWidgetWorker`, schedulers, widget actions, and activities. Desktop
entry points are its daemon loops and UI requests. Both eventually use shared source, cadence, and
persistence rules.

At a high level:

1. Resolve the active site and determine which sources/products are due or explicitly targeted.
2. Fetch provider data, preserving partial products where policy allows and recording quota failures.
3. Persist each source through `ForecastPersistRun`: hourly rows first, then
   elapsed backfill, then daily rows and whole-download daily summaries.
4. Refresh observations/current conditions and perform due history maintenance through shared runners.
5. Repaint from stored state. UI interactions read cached data immediately and request network work
   only when the relevant policy says it is due.

Temperatures are stored at the provider's precision on every day; nothing rounds before storage, and
accuracy scores the raw difference. Display rounds: a daily column prints whole degrees only for a
future provider forecast (`DailyColumn.printsWholeDegrees`, `TempUtils.formatDayTemp`); today, past
days, climate normals and Forecast Comparison (graphs and the all-sources table) print the tenth.

A daily column's left slot is the forecast from a day earlier (`:shared` `PriorDayForecast`,
`PriorBarSlot`). Today and past columns always draw it in their triple bar. The left bar's anchors:
- today: the newest fetch before 06:00 (low) and 16:00 (high) on D−1;
- past days: the values frozen into `daily_history`;
- future columns: only with Settings → "Show forecast from 24 h ago on future days" (default off,
  both platforms). They use the newest row first seen before now − 24 h, judged per side, with no
  fallback (`PriorDayForecast.selectAsOf`). The bar is drawn at the past-day width and touches the
  unmoved forecast bar. It is dashed when that row was last confirmed over 24 h before the cutoff.

The y-axis (`DailyAxisTemps`) and the rain-label cap read the same `PriorBarSlot.drawn` rule.

A failed fetch, quota block, or rate limit shows a failure pill over the graph on both platforms. The pill
stays until the failure or block ends: there is no dismissal. Clicking it opens the details, worded from
`:shared` `SourceErrorDetails`, which describes the pill that opened it: headline, explanation, the
provider's request, status, message and quota details, failures in a row, last attempt and success, and
the full response. Android shows them on `SourceErrorDetailsActivity` with localized text; desktop shows
them on the popup's details card with the shared English, kept equal to `values/strings.xml` by
`SourceErrorDetailsEnglishParityTest`.

Forecast cadence (every fetch LONG), observation cadence, worker wakeups, and user-triggered refreshes are
separate concerns. The current tables are in
[forecast and observation cadence](forecast-and-observation-cadence.md).

Provider metadata and source ordering come from `WeatherSource`, `WeatherSourceOrdering`, and coverage
policy. A user's enabled-source list is not rewritten merely because a source cannot serve the current
location. Actuals-only feeds are not display providers.

## Actuals and accuracy

`daily_history` distinguishes provider/station API actuals from the location blend and from frozen
forecasts. These values have different provenance and must not overwrite one another accidentally.
Shared observation, station-extremes, history, and baseline code determines which value a graph or
accuracy calculation may use; forecast-only sources must not be graded against their own forecast.

See [daily-history extremes](daily-history-extremes.md) for field meanings and writer/reader rules.

## Rendering and interaction

Shared graph code computes series, geometry, extrema, label placement, overlays, and header fit. Android
Canvas/RemoteViews and desktop Compose execute those plans with platform text measurement and drawing.
This keeps label and layout decisions testable on the JVM and minimizes platform-specific drift.

The Android widget's graph fills the widget edge to edge: `widget_weather.xml` has no root padding
and no `graph_view` side margins (`plans/261118-remove-widget-edge-padding.md`). The bitmap size,
the pill touch zones and the nav-arrow overlap read that inset from `WidgetInsets`, and
`WidgetEdgeInsetRoboTest` lays out the real XML to keep the two in step.

Hourly-graph labels share one top limit, `GraphLabelArea.TOP` (the canvas edge, not the plot top), so
no placer carries its own exception. Value and ghost labels do not check for collision with the header
row. The context annotations — the dominant station (`knuq 71.6° @ 2:35 pm`) and "Actual temperature
data from X" — search only below Android's header band (`TemperatureHeaderFit.headerBandBottomPx`;
desktop's header sits above the canvas). Both shrink (`1.0/0.8/0.6`) and are omitted when nothing
fits; the actuals-source annotation also tries two lines before it is omitted
(`DominantStationLabel.placeWrapped`).

The daily view's rain chance for a day (8am–8pm) or night (8pm–8am) window is the window's max
hourly chance (`DailyRainLabels.periodMaxima`, stored on the forecast row by `DailyHourlySummaries` so
days past the hourly store keep it). One exception lives in `:shared` `CombinedRainChance`: sources whose
hourly chances stop short of certainty show the chance of rain in at least one hour,
`1 − ∏(1 − pₕ)`, once the window's max reaches a per-source cutoff just under that ceiling — Google
85 % (its hours stop at 85 % a day out), WeatherAPI 89 % (its hours stop at ~92 %), Tomorrow.io 85 %. Below the cutoff
the max stands, since Google's 10 % drizzle floor would otherwise combine to ~72 % over a dry day. The combined value is stored beside
the max (`forecasts.hourlyDayPrecipCombined` / `hourlyNightPrecipCombined`) and moves with it in
`carryForward`; the peak hour stays the first hour at the max. Label, icon, bar colour, the
source-comparison popup and the frozen `daily_history` chance all read the resolved value; the hourly
view is unchanged (`plans/261117-google-combined-daily-rain-chance.md`).

A daily column's icon comes from `:shared` `DailyIconName.resolve` inside `DailyColumnBuilder`: the
provider's native token (else its condition text), turned into a cloud icon when a rain icon's day and
night chances both fall below the thresholds, then the partly-cloudy floor from noon cloud %. Today's
column then lets the hourly forecast win a clear conflict (`DailyIconName.todayCurrentHourOverride`):
a daily icon without precipitation turns rainy when the header's rain % — the same
`PrecipProbabilityCalculator` call over the same header rain lookahead setting — is at least 70 %,
taking the wettest hour's rain-type icon in that window (rain / storm / snow at ≥ 80 %, "chance rain"
at 70–79 %). Otherwise the current hour (the newest site row at or before now, within 3 h) decides
cloud: a clear or mostly-clear icon becomes cloudy in a ≥ 91 % hour, and a cloudy or mostly-cloudy
one becomes mostly clear (clear night after dark) in a ≤ 25 % hour; neighbouring cloud steps never
switch. The icon therefore changes with the clock at the next repaint (about every 3 minutes), and
bar colour follows the icon. Providers' daily codes can contradict their own hours (Tomorrow.io sent
code 1000 "Clear" beside a 94 % day whose rain fell 5–9am). Trace: `TODAY_ICON_HOURLY` at VERBOSE
(`plans/261120-today-icon-hourly-rain-override.md`).

Daily night rain labels (the chance or amount between two columns) are placed by one scored search,
`:shared` `NightRainLabelPlacer`, run from `DailyRainLabelLayout.layoutNights`
(`plans/261116-night-rain-label-scored-placement-redesign.md`). The hosts measure ink, not line
boxes: low labels, icons, day names and the label itself become 1 px row bands
(`InkScan.rowBands`; Android rasterizes in `TextInk`, desktop in `DailyForecastGraph`), so open glyph
space (under a °, a 4's crossbar, between a sun's rays) is free. Every candidate on a 0.5 dp grid is
scored: centring between the columns (per dp²), distance below the preferred row (~6 dp under the
higher low's ink; linear plus squared, so big drops cost more), crowding inside 2 dp, and intrusion
inside 1 dp of other ink (0.5 dp corner to corner), which is large enough that any clear spot wins.
It never rises above the preferred row except toward a 1 dp tuck when room is short, and may pass
the day names' ink top by 1.5 dp only between names. The 85 % size is used only when full size has
no clear spot. The last column's gap is the canvas edge, so its label targets that gap clamped inside
the edge margin (`NIGHT_AT_EDGE`), not the centre of its own low. The edge margin is 4 screen dp:
Android draws a downscaled bitmap and stretches it to the widget, so `NightInput.bitmapScale` scales it
(the device density alone made it ~10 dp on the Fold 4). Labels are placed in column order,
each avoiding those already placed. Weights live
in `NightRainLabelPlacer.Weights`; `nightRainLabel position` (tag `DailyRainLabelLayout`) logs the
chosen spot, each score term, the runner-up, and every obstacle box.

The header icon is the displayed source's forecast hour (`HeaderConditionIcon`), except while rain is
falling now: then it is blue dots only, no cloud, sized by intensity (`ic_weather_rain_now_light` /
`ic_weather_rain_now` / `ic_weather_rain_now_heavy`; `plans/261115-header-rain-now-dots.md`). The shared
`RainNow` rule takes the strongest of three inputs:

- the nearest official station's newest report (NWS or METAR rows only, ≤ 25 km, ≤ 75 min;
  raw METAR present weather, `VC*` ignored);
- the NOAA radar mosaic pixel at the site (`RadarNowSampler`, NWS coverage only, ≤ 25 min old), read
  by colour family: blue → sprinkling, green → raining, red ≥ 150 → heavy;
- Tomorrow.io's 5-minute history (≤ 30 min), only while Tomorrow.io is the displayed source.

`HeaderContent.build` takes the level as a required `rainNow`; Android resolves it per render in
`AndroidRainNow` (Room rows plus the radar sample in prefs), desktop in `desktopRainNow` (the snapshot's
rows plus the daemon's `~/.cache/weather-widget/radar-now.txt`). `RAIN_NOW` logs each change of level.
Left-cluster header tap routing is shared in `HeaderTapRouting`: temperature/delta toggles `DAILY ↔ TEMPERATURE`,
rain `%` toggles `PRECIPITATION ↔ DAILY`, and the weather icon follows rain `%` while `HeaderContent.Content.isRainingNow`
is true or temperature/delta otherwise.

The header's rain percentage scans forward by `DisplaySettings.headerRainLookaheadHours` (1–8 h,
default 1 h, 6 h before 2026-11-20; installs that never moved the slider follow the default; Settings → **Header** on both platforms) from `now` in every view: panning or switching
views never changes it, and its daily fallback is always today's row
(`plans/261119-header-rain-constant-across-views.md`). Android reads it from the same now-centred rows as
the current temp (`HeaderContent.nowRowsWindowMs`: the current-temp window stretched to the longest
lookahead; the temp cuts them back to its own window), never from a view's panned graph rows. Desktop
reads its full forecast. `DayClickResolver.TODAY_LOOKAHEAD_HOURS` stays fixed at 6 h to match
`ZoomStage.WIDE`. Shared display defaults, integer slider bounds (`IntSliderSpec`), clamping, and diffs
live in `DisplaySettings` (`:shared`) and are mirrored into Android's `WeatherDisplayPreferences` and
Desktop's `DesktopSettings` (`plans/261118-header-rain-lookahead-slider.md`).

The daily view's morning fog is a see-through ellipse over each bar's cold end, wider than the bar and
under the labels (`plans/261111-daily-bar-morning-fog.md`). `DailyColumnBuilder` resolves it through
`DailyMorningFog`, one rule for every column:

- **Observed fog** (`daily_history.observedFog*`) combines with the provider's whole-morning forecast:
  the forecast row's `fogLevel`/`fogTopTemp`/`fogVisibilityMi`/`fogPredawn` (`ForecastMorningFog`,
  computed at persist time from the fetch's hourly visibility and words over 00:00–12:00, carried
  forward like noon cloud), else the stored hours. A download that starts after midnight is judged on
  the stored newest-per-hour rows, which the persist reads from the fog window's 00:00, so a later
  all-clear fetch clears an earlier fetch's fog
  (`plans/261117-today-fog-stale-forecast-and-nws-metar-history-fill.md`). A fetch made after the fog keeps it on today and
  past days (`plans/261111-today-fog-whole-morning.md`).
- **Observed no fog** (level 0) **or only a saturation hint** (level 1, no fog or mist reported): the
  stations win for the hours already gone. Only forecast fog from the current hour to noon counts,
  combined with the hint, so after noon and on past days at most the hint's faint haze draws
  (`plans/261112-observed-no-fog-vetoes-forecast.md`).
- **No observation** (future days): the forecast alone.

`ObservedMorningFog` counts a foggy station report (above the hint) only while it is **active**, meaning
it is its station's latest report and less than 45 minutes old, or once the same station's previous or
next report **confirms** it by being foggy too. A lone report between clear ones (KNUQ 2026-11-16
`M1/4SM HZ` between 8 SM and 10 SM CLR) shows while it is current and then drops out of the level,
visibility and top. Today's fog is recomputed live, so no stored state is involved; `FOG_SPIKE_DROPPED`
(VERBOSE) logs each one the daily recompute drops
(`plans/261116-isolated-fog-report-counts-only-while-active.md`).

`FogPatch` plans the nested ovals (height from the low to the burn-off temperature of the fog proper,
layers gathered toward the low so the top fades, opacity from the lowest visibility on a log scale, 5 %
for a saturation-only hint). Fog whose every hour or report came before that day's sunrise at the site
(`MorningFog.predawn`) draws petite: ⅓ the height and ½ the width, opacity unchanged. Combined fog is
pre-dawn only when both sides are. `DailyBarRenderer` and `DailyForecastGraph` only fill the ovals.

Android's `WeatherWidgetProvider` and interaction coordinators route widget actions, serialize
interactions per widget, load cached state, and paint through the widget render pipeline. RemoteViews
cannot describe arbitrary touch regions, so renderers and click routing must agree on the same geometry.
Bitmap or label changes therefore require runtime visual and interaction verification, not only unit
tests.

The desktop popup mirrors the shared views and state but uses Compose and desktop navigation/window
hosts. Settings and behavior changes normally require both platform UIs.

## Desktop process model

The packaged desktop app uses a long-lived daemon and an ephemeral UI process:

- The daemon owns weather storage, fetch loops, configuration, power/network/presence monitoring, tray
  integration, and the genmon/panel socket.
- The UI process hosts Compose windows and exits when its last window closes; the daemon can remain
  alive to serve background work and panel requests.
- The UI process fetches nothing that stores data. Every refresh, viewing refresh, location change,
  on-demand hourly day and deep-history pull is a `FetchRequest` line over `fetch-request.sock`; the
  daemon runs it on its own scope (`FETCH_REQUEST start|done|reply_dropped`), so closing the last window
  mid-fetch costs nothing. The UI reloads from the database when the reply comes. Display-only network
  (radar, rain loop, location lookups) stays in the UI. `UserRefreshScopeGuardTest` pins this.
- Last-launch-wins uses `.quit-<launchId>` trigger files, with file watching and a polling fallback.
- Autostart launches the repo-local `createDistributable` output through
  `scripts/desktop-app-launcher-and-autostart.sh`.

Operational commands and IPC details are in [operations](operations.md).

## Observability

The shared `Log` router separates ephemeral traces from persistent diagnostics. VERBOSE is for frequent
render/poll events and is not persisted; DEBUG and above can reach `app_logs`, so those events must be
sparse and useful in queries.

Android's `ProcessExitLogger` records `ApplicationExitInfo`, including native/process deaths that a JVM
crash handler cannot explain. Sync stages, widget lifecycle/interactions, fetch plans, source gates,
and history maintenance have structured log tags. Use these plus database state and screenshots as the
first evidence when investigating runtime behavior.

## Verification

The test pyramid is pure JVM logic first, Robolectric for Android dependencies, MockK only where
coupling is unavoidable, and instrumented tests for real rendering/view/SQLite behavior. Shared Host
contract suites pin cross-platform persistence parity. See [testing strategy](testing-strategy.md) for
categories and commands, and [operations](operations.md) for safe device inspection.
