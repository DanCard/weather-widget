# Add Google Weather API as a source

## Goal
Make Google Weather (`weather.googleapis.com/v1`) a user-selectable forecast source on Android and
desktop, keyed like the other premium sources (user key in Settings, else baked from
`local.properties` `GOOGLE_WEATHER_API_KEY`).

## What the API actually does (probed 2026-10-06, Mountain View + Kyiv)
| Endpoint | Returns | Limits |
|---|---|---|
| `currentConditions:lookup` | temp, condition `type` (enum, e.g. `CLEAR`, `CLOUDY`), cloudCover, precip, `currentTime` | global (Kyiv OK) |
| `forecast/days:lookup` | per local day: `displayDate`, `maxTemperature`/`minTemperature`, `daytimeForecast`/`nighttimeForecast` each with condition type, precip `probability.percent`, `qpf` mm, cloudCover | **max 10 days** (`days=16` → 400) |
| `forecast/hours:lookup` | hourly temp, condition, precip %, qpf, cloudCover | **24 per page**, `nextPageToken`, up to 240 h |
| `history/hours:lookup` | same shape, newest-first | **max 24 h** (`hours=48` → 400) |

- Units default to metric (`CELSIUS`, `MILLIMETERS`); request `unitsSystem=IMPERIAL` for °F to match
  the other parsers, keep qpf in mm (convert if imperial returns inches).
- Billing is per request, so hourly pagination costs one call per 24 h.

## Design decisions
1. **Forecast-only, borrows actuals (Silurian model).** `historicalDataKind = NONE`,
   `supportsTemperatureActuals = false`, `supportsCloudActuals = false`. Google documents
   `history/hours` as its own hourly weather history, not station observations; treating it as
   actuals would repeat the "NWS API actual IS the forecast" circularity. Actuals come from
   `ActualsProviderResolver.DEFAULT_PROVIDER` (METAR), user-changeable as for Silurian.
2. **History fills today's elapsed hours as forecast rows**, the same role as Tomorrow.io's
   `nowMinus23h` and Silurian `include_past`: a fresh site still gets a full-day hourly curve.
3. **Hourly horizon = 72 h (3 pages) + 24 h history + days + current = 6 calls per full fetch.**
   Daily rows (10 days) carry their own day/night precip and condition, so pages 4–10 add cost
   without changing what's drawn. One constant, easy to raise.
4. **Visibility and placement (user's calls 2026-10-06).** Added to
   `WeatherSourceOrdering.ALL_CONFIGURABLE`.
   - **Enabling Google makes it primary:** `WeatherSourceOrdering.toggle` inserts it at the front
     (`PRIMARY_ON_ENABLE`); every other source is appended (so OWM lands last on enable). It is a
     starting position, not a pin — the user can move either afterwards.
   - **Widgets follow:** when Google is newly enabled at the front, every Android widget's display
     source switches to it (`setVisibleSourcesPreservingSelections`, and the debug migration);
     desktop sets `weatherSource` to it.
   - **OWM is no longer forced last on every load/save** (desktop `DesktopConfig.load/save`
     normalisation removed). Android's one-time `migrateOpenWeatherMapPositionIfNeeded` already ran
     and stays as history. id `GOOGLE_WEATHER`, display "Google Weather", short
   "Google".
   - **Android debug:** default-visible (`WidgetStateManager.DEFAULT_VISIBLE_SOURCES` debug list,
     beside Tomorrow.io) plus a one-time, **debug-only** `WeatherSourcePreferences` migration that
     appends it to an existing stored list (flag-gated like `migrateSilurianIfNeeded`, so turning it
     off sticks). Release: not default-visible.
   - **Key baked into debug builds only.** Exception to the 2026-07-08 "release ships baked keys"
     policy: Google bills per request (6 per full fetch). `app/build.gradle.kts` sets the
     buildConfigField per build type — blank for release, so release users must enter their own key.
   - **Desktop:** not default-visible in code. Enabled on the user's own machine by editing their
     `config.json` `visibleSources` after the build — per-machine config, no code path. Public apt
     builds (`-PpublicBuild`) bake no key anyway.
5. Condition `type` enum mapped to the app's condition vocabulary in a shared
   `WeatherCodeMapper.googleConditionToCondition` (CLEAR/MOSTLY_CLEAR/PARTLY_CLOUDY/MOSTLY_CLOUDY/
   CLOUDY/…RAIN…/…SNOW…/THUNDERSTORM…/WINDY/FOG…), used by both the parser and Android's
   `DailyForecastIconResolver` (native token = the enum).

## Commit 1 — refactor: per-source lists become one list (no behaviour change)
`WeatherSource`'s contract is "adding a new entry is a single edit", but Android still keeps
hand-written per-source lists that each new source must be added to (and that already disagree on
order). Desktop does not use `ComparisonStatistics`, so this commit is Android + `:shared` only.
- `ComparisonStatistics`: the six named fields (`nwsStats`, `openWeatherMapStats`, `meteoStats`,
  `weatherApiStats`, `tomorrowIoStats`, `silurianStats`) → `bySource: Map<WeatherSource,
  AccuracyStatistics?>` + `fun statsFor(source)`.
- `AccuracyCalculator.calculateComparison`: iterate `WeatherSourceOrdering.ALL_CONFIGURABLE`.
- `StatisticsActivity` (both lists), `ForecastHistoryActivity`, `BugReportActivity`: iterate
  `ALL_CONFIGURABLE` / `statsFor` instead of their own lists. Display order becomes
  `ALL_CONFIGURABLE` order filtered to enabled sources (one order everywhere, where today they
  differ per screen).
- `ApiSourceWarningHelper` failure tag + `ForecastFetchCoordinator` `SourceFetchEntry` tags: one
  `failureLogTag(source)` (existing tags preserved verbatim — `FETCH_OWM_FAIL`, `FETCH_TMRW_FAIL`
  … are queried in bug-report triage).
- Tests: existing `AccuracyCalculatorIntegrationTest` updated to the map; new test that
  every `ALL_CONFIGURABLE` source gets a comparison entry and a failure tag, so a future source
  cannot be silently missing.

## Commit 2 — Google Weather
### `:shared`
- `WeatherSource.GOOGLE_WEATHER` (+ `fromDisplaySourceOrNull` arm).
- `data/remote/GoogleWeatherApi.kt`: `getForecast(lat, lon): RawFetch` — current, days, hourly
  pages, history in parallel; `require2xx(GOOGLE_WEATHER, …)`; key via `key=` param (already
  covered by `ApiKeyRedaction`? verify, add if not).
- `WeatherCodeMapper.googleConditionToCondition`.
- `WeatherSourceOrdering.ALL_CONFIGURABLE`, `ObservationSourceMatcher.sourcePrefixes`,
  `AccuracyModels.googleWeatherStats`.
### `:app`
- `app/build.gradle.kts` `GOOGLE_WEATHER_API_KEY` buildConfigField (debug = local.properties, release = blank); `BuiltInApiKeys.baked`.
- `WidgetStateManager` debug default list; debug-only one-time migration in `WeatherSourcePreferences`.
- `AppModule`: provider, host→source mapping (`googleapis.com` → `GOOGLE_WEATHER`) for
  `api_usage_stats`, inject into `ForecastRepository` / `CurrentTempRepository`.
- `ForecastFetchCoordinator`: `SourceFetchEntry` via `fetchAndSaveSharedForecast`
  (tag `FETCH_GOOGLE_FAIL` from `failureLogTag`).
- `CurrentTempRepository`: `GOOGLE_WEATHER` arm using the shared forecast-backed POI helper
  (as Silurian).
- `DailyForecastIconResolver` (calls the shared mapper), `SettingsActivity` description,
  `HourlyObservationBackfill` (borrowed, like Silurian). Stats/history/bug-report screens need no
  edit after commit 1.
- `strings.xml` `api_source_google_desc` (+ the 20 locale files, English text where untranslated
  follows existing practice — check).
### `:desktop`
- `desktop/build.gradle.kts` key spec; `DesktopWeatherService` fetch arm
  (`withHistoricalActuals` + borrowed observations-only arm, as Silurian); Settings description;
  `DesktopFetchErrorPresentation` if it lists sources.
### Local only
- `local.properties`: `GOOGLE_WEATHER_API_KEY=<test key>` (gitignored, never committed).

## Tests
Integration = 2+ real classes collaborating (project definition). Mocked only at the HTTP edge
(`MockEngine` serving fixtures recorded from the live API on 2026-10-06) and Room in-memory.

### Unit
- `GoogleWeatherApiTest` (`:shared`, MockEngine): parses current/days/hours/history fixtures, °F,
  pagination stops at 72 h (asserts exactly 3 hour-page requests), history + forecast hours merged
  without duplicate hours, one daily row per `displayDate`, missing key → no request, 4xx →
  `ApiAccessException` with the key redacted from the message.
- `WeatherCodeMapper` test for every documented condition enum (unknown → safe default).
- `WeatherSourceOrdering` / `ActualsProviderResolver` tests extended: Google is configurable,
  `borrows == true`, never offered as an actuals provider.

### Integration
- **`GoogleWeatherFetchIntegrationTest`** (Android, Robolectric, pattern of
  `OpenMeteoIntegrationTest`): real `ForecastRepository` → `ForecastFetchCoordinator` →
  `GoogleWeatherApi` (MockEngine) → Room. Asserts `forecasts` rows for 10 days under
  `GOOGLE_WEATHER`, `hourly_forecasts` covering today's elapsed hours (from history) through +72 h,
  and **no** `observations` rows filed under `GOOGLE_WEATHER` (forecast never re-filed as actuals).
- **`GoogleWeatherBorrowedActualsIntegrationTest`** (Android): Google display source + METAR
  observation rows in Room → the daily actuals read (`DailyActualsStore` / `ActualsReadScope`)
  returns METAR's high/low for Google's past day. Proves the Silurian-style borrowing works end to
  end, not just the resolver's boolean.
- **`GoogleWeatherDesktopServiceTest`** (desktop, pattern of `TomorrowIoDesktopServiceTest`): real
  `DesktopWeatherService` + `GoogleWeatherApi` on MockEngine → `RawFetch` with daily/hourly and no
  synthetic `GOOGLE_WEATHER_MAIN` observations; observations-only refresh routes to the borrowed
  provider and makes zero Google requests.
- **`GoogleWeatherDebugDefaultIntegrationTest`** (Android, Robolectric): real `WidgetStateManager`
  + `WeatherSourcePreferences` on real SharedPreferences — fresh debug install lists Google; an
  existing stored list gains it once; user removes it → it stays removed after the migration runs
  again.
- **Accuracy pipeline** (`AccuracyCalculatorIntegrationTest`, extended): Google forecast snapshots +
  METAR actuals in Room → `ComparisonStatistics.statsFor(GOOGLE_WEATHER)` non-null with the
  expected error.
- **Android release key**: a Gradle check (unit test reading `BuildConfig` per variant is not
  possible from one variant) — assert in `app/build.gradle.kts` that the release
  `GOOGLE_WEATHER_API_KEY` field is `""`, and verify with
  `unzip -p app-release.apk classes*.dex | grep -c AIza` = 0 on a release build.

### On-device / live
- `installDebug` on the emulator: Google appears enabled, widget draws daily + hourly graph,
  `api_usage_stats` shows `GOOGLE_WEATHER` with ≤ 6 requests per full fetch, no `FETCH_GOOGLE_FAIL`.
- `:desktop:createDistributable`, enable Google in this machine's `config.json`, restart via
  `scripts/buildStart-desktop.sh`, confirm the popup draws and `app_logs` show the fetch.

## Implementation notes (2026-10-06, deviations from the plan above)
- **Current temperature uses `GoogleWeatherApi.getCurrent` — 1 request, centre point only.** The
  forecast-backed POI helper (`CurrentTempRepository.fetchForecastCurrent`) runs a full forecast at
  5 points; for Google that is 30 billed requests per refresh, every 10–20 min while charging.
- **`ForecastFetchCoordinator.SOURCES_TO_CHECK` was a fifth hand-kept source list** (staleness
  gate). It is now `ALL_CONFIGURABLE`; without that, a stale Google forecast never triggered a fetch.
- **Accuracy:** Google (`HistoricalDataKind.NONE`) is graded against the best *measured* baseline
  (`ActualsBaselineResolver`), exactly like Silurian — never its own `daily_history` row.
- **Elapsed hours** from `history/hours` land in `hourly_forecast_history` (via the existing elapsed
  backfill), not `hourly_forecasts`, which keeps only future hours.
- **Placement rule is shared:** `WeatherSourceOrdering.withEnabled` / `selectionAfterChange` drive
  Android (`WeatherSourcePreferences`) and desktop (`SettingsWindow`).
- **Failure tags:** `SourceFetchLogTags` is the one table; it also fixes Tomorrow.io's missing tag
  in `ApiSourceWarningHelper` (its fetch error never surfaced on the widget).
- Test renamed: `GoogleWeatherDebugDefaultIntegrationTest` → `GoogleWeatherPrimaryIntegrationTest`
  (covers Settings enable + widget switch as well as the debug migration).
- Fixtures replayed in `:app`/`:desktop` via `GoogleWeatherFixtures`, which shifts the recording to
  the test's current hour so assertions do not depend on when the suite runs.

## Bugfix 2026-10-06: `history/hours` is 10 calls/day per project (raised to 20 by the user the same day)
Live 429 at 11:35/11:42/11:48: `HistoryHoursQueriesPerDay`, `quota_limit_value: 10`, unit
`1/d/{project}` — shared by every device, build and probe using the key. Because `getForecast`
awaited history alongside the forecast, the exhausted quota failed **every** Google fetch for the
rest of the day (widget: "GOOGLE WEATHER UPDATES FAILING 429"; desktop `REFRESH_FAIL`).
- History is **best-effort**: its failure drops only the elapsed hours, never the forecast.
- It is requested only when **needed** — `GoogleWeatherApi.needsHistory`: the site has no Google
  `hourly_forecast_history` hours in the last day (Android via the history DAO + `LocationMatch.sameSite`,
  desktop via `getHourlyHistoryCoveredHours`). Steady state: one history call per site per day.
- After a history **429** the client skips history until midnight Pacific (Google's quota reset).
- Tests: `GoogleWeatherApiTest` (skip, 429 keeps forecast + backoff + reset, `needsHistory`);
  `GoogleWeatherFetchIntegrationTest` (second fetch at a site makes no history call). The fixture
  recorders became synchronized lists — the four concurrent requests lost an update in a plain list.
