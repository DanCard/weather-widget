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
- `SourceSwitchRunner`: a user's switch of the displayed source (API button, daily home button).
  Persist then paint is the whole critical path; the view tally, the staleness check / refresh request
  and the `app_logs` row follow, and `SOURCE_SWITCH_E2E` times each phase. Hosts: Android
  `WidgetIntentActionHandler` (toggle and home reset; the deferred phase joins the receiver's job),
  desktop `DesktopSourceSwitch` (refresh is the daemon's `source_change` check).
- `DaySourceComparison`: the Forecast History screen's "All sources for <day>" table. Each visible
  source's newest forecast for the day at the nearest site, run through `DailyColumnBuilder` with that
  source as the display source (so the high, rain %, peak hour and noon cloud match the widget's daily
  view), sorted by high by default; a source with no row is listed last. Columns: short name, high, low, day rain %,
  peak hour, night rain, amount (8am–8pm hourly sum as on the widget; dropped below 380 dp or when no
  row has one), noon cloud, 30-day
  high bias, and age since `lastConfirmedAt` (dimmed past 24 h). Hosts: `RoomDaySourceComparisonHost`,
  `DesktopDaySourceComparisonHost`; `DAY_SOURCE_COMPARE` logs each build.
  Clicking a column header sorts by it (`Sort.tapped`: same column flips, a new column starts
  descending except Source A–Z, Peak earliest-first and Age freshest-first); blanks stay last and ties keep the source
  order. The sort is remembered (`source_compare_sort` pref / `DesktopConfig.sourceCompareSort`);
  `DAY_SOURCE_SORT` logs each tap. A legend under the table gives one line per ambiguous column
  (`legendColumns`; wording per platform: desktop `LEGEND_TEXT`, Android `source_compare_legend_*`).

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
days, climate normals and History of Forecasts (graphs and the all-sources table) print the tenth.

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
[forecast and observation cadence](hourly-forecast-update-cadence.md).

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

Hourly-graph labels share one top limit, `GraphLabelArea.TOP` (the canvas edge, not the plot top), so
no placer carries its own exception. Value and ghost labels do not check for collision with the header
row. The context annotations — the dominant station (`knuq 71.6° @ 2:35 pm`) and "Actual temperature
data from X" — search only below Android's header band (`TemperatureHeaderFit.headerBandBottomPx`;
desktop's header sits above the canvas). Both shrink (`1.0/0.8/0.6`) and are omitted when nothing
fits; the actuals-source annotation also tries two lines before it is omitted
(`DominantStationLabel.placeWrapped`).

The daily view's morning fog is a see-through ellipse over each bar's cold end, wider than the bar and
under the labels (`plans/261111-daily-bar-morning-fog.md`). `DailyColumnBuilder` resolves it through
`DailyMorningFog`, one rule for every column:

- **Observed fog** (`daily_history.observedFog*`) combines with the provider's whole-morning forecast:
  the forecast row's `fogLevel`/`fogTopTemp`/`fogVisibilityMi`/`fogPredawn` (`ForecastMorningFog`,
  computed at persist time from the fetch's hourly visibility and words over 00:00–12:00, carried
  forward like noon cloud), else the stored hours. A fetch made after the fog keeps it on today and
  past days (`plans/261111-today-fog-whole-morning.md`).
- **Observed no fog** (level 0) **or only a saturation hint** (level 1, no fog or mist reported): the
  stations win for the hours already gone. Only forecast fog from the current hour to noon counts,
  combined with the hint, so after noon and on past days at most the hint's faint haze draws
  (`plans/261112-observed-no-fog-vetoes-forecast.md`).
- **No observation** (future days): the forecast alone.

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
