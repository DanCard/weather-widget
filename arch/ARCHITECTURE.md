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

- `ForecastPersistRun`: ordered live-hourly, snapshot, elapsed-backfill, and daily-row persistence.
- `HourlySnapshotRun`: which fetches receive history snapshots and their fetch-time labels.
- `CurrentConditionsRun` and `CurrentTempRun`: observation/current-temperature behavior.
- `ObservationWriteRun`: every `observations` write — own `fetchedAt`, station-type re-tag on every api,
  personal stations thinned to one row per 10 minutes over the stored window (plus each station's
  newest), the Tomorrow.io fetch line, and retired-product cleanup per api written.
- `DailyHistoryRun` and `NwsStationActualsRun`: history maintenance and station-derived actuals.
- `WeatherApiHistoryRun` and `GoogleHistoryRun`: provider-specific history workflows.
- `SourceFetchTable`: what each source's forecast fetch requests, and (`runFollowUps`) which of the
  history runners above ride that fetch, a failed one included.

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
- `hourly_forecasts`: current live hourly series used by graphs and interpolation.
- `hourly_forecast_history`: forecast snapshots for evolution, prior-day comparisons, and accuracy.
- `observations`: station/provider observations with provenance and quality metadata.
- `daily_history`: settled daily actuals, frozen forecast comparisons, and source/writer provenance.
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
3. Persist each source through `ForecastPersistRun`: live hourly first, then eligible history snapshots
   and elapsed backfill, then daily rows and whole-download daily summaries.
4. Refresh observations/current conditions and perform due history maintenance through shared runners.
5. Repaint from stored state. UI interactions read cached data immediately and request network work
   only when the relevant policy says it is due.

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
