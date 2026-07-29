# WeatherAPI history backfill parity

**Date:** 2026-07-28  
**Status:** Proposed  
**Scope:** Shared WeatherAPI client, Android forecast/history orchestration, and desktop
forecast/history orchestration.

## Requirement

When WeatherAPI is enabled, its historical weather should populate automatically and behave like
historical data from the other enabled APIs:

1. A normal WeatherAPI refresh stores any past hours included in the forecast response.
2. If the required recent history is still missing, the app calls WeatherAPI's
   `/v1/history.json` endpoint automatically.
3. The returned hours feed the same source-tagged observation, hourly graph, daily-history, and
   accuracy pipelines already used by the other APIs.
4. Android and desktop follow the same source behavior and storage semantics.
5. Existing complete history is not fetched again.
6. A history failure does not disable WeatherAPI and does not discard a successful current
   forecast.

“The same as other APIs” means the user does not have to select a separate history mode, press a
backfill button, or accept a WeatherAPI-specific empty-history state. Provider-specific endpoint
and plan limits may bound how far back the automatic fill can go, but the resulting data uses the
normal app paths.

## Important data semantics

WeatherAPI's History API is its historical-weather product, but WeatherAPI's pricing FAQ describes
that product as archived forecast data rather than station observations:

- History endpoint documentation:
  <https://www.weatherapi.com/docs/#intro-history>
- Pricing/FAQ description:
  <https://www.weatherapi.com/pricing.aspx>

This distinction must not prevent feature parity, because the app already uses source-specific
historical series for non-NWS providers. It does affect internal naming, diagnostics, and what the
implementation may claim:

| Data layer | WeatherAPI behavior |
|---|---|
| Source-specific historical temperature/precipitation | Populate from `/history.json` |
| Hourly graph's past WeatherAPI curve | Display through the normal observation pipeline |
| Daily historical high/low/rain | Derive through the normal daily-history pipeline |
| Accuracy comparison's WeatherAPI reference | Use the source-specific historical series, matching other non-NWS APIs |
| Station-observation claim | Do not make one |
| Historical forecast snapshots/vintages | Never synthesize from `/history.json` |

The implementation should use a precise term such as “provider history” or “archived history” in
new code and logs. Existing user-facing “actual” terminology is outside this plan unless a touched
screen would otherwise make a new, explicitly false station-observation claim.

## Current evidence

1. `WeatherApi.getForecast` calls only `https://api.weatherapi.com/v1/forecast.json`.
2. A WeatherAPI forecast response contains the current day plus future days. Saving the past hours
   embedded in that response gives partial current-day history, not prior days.
3. A direct `/v1/history.json` request using the configured key succeeded for the previous day and
   returned one daily record and 24 hourly records.
4. The configured key currently returns a three-day forecast, consistent with WeatherAPI's Free
   plan. The published Free-plan history allowance is one past day.
5. Live desktop storage after enabling WeatherAPI contained:
   1. three daily forecast rows;
   2. 72 hourly forecast rows;
   3. 22 WeatherAPI observation rows, all from the current day; and
   4. one daily-history row, also for the current day.
6. `HourlyForecastStore.saveHourlyEntitiesFromShared` already sends past source hours through
   `HistoricalActualsBackfill` into `ObservationEntity`.
7. `DailyHistorySnapshotter` can derive/repair daily history only from rows already in local
   storage. It does not fetch missing network history.
8. Android's `evaluateHourlyBackfillNeed` returns `organic_backfill_active` for every non-NWS
   source, although WeatherAPI currently has no organic prior-day history fetch.
9. Desktop's normal WeatherAPI path similarly converts only past hours already present in the
   forecast response.
10. Desktop `needsDeeperHistory` and `ensureHistory` are intentionally hard-coded to NWS station
    observations; `DesktopWeatherService.fetchHistory` is hard-coded to Open-Meteo.
11. `WeatherSource.providesHistoricalActuals` currently marks WeatherAPI as having “genuine
    actuals.” That boolean conflates data availability with observation provenance and its comment
    conflicts with the provider's FAQ.
12. The older `plans/260316-Hourly-Graph-Actual-Temperature-History.md` anticipated a
    `WeatherApi.getHistory` method, but its proposed persistence model predates the current
    `ObservationEntity`/shared actuals architecture. This plan supersedes only that plan's
    WeatherAPI-history portion.

## Goals

1. Backfill the previous WeatherAPI day automatically when its hourly history is absent.
2. Store WeatherAPI history through the same source-tagged observation path used by other
   non-NWS providers.
3. Make the resulting hours immediately available to hourly graphs, daily extrema/history, rain
   history, and source-relative accuracy calculations.
4. Keep Android and desktop behavior aligned.
5. Make repeated refreshes idempotent and quota-conscious.
6. Preserve a successful live forecast when the optional history request fails.
7. Preserve provider provenance in code and logs.
8. Avoid adding a Room or desktop database migration unless implementation inspection exposes an
   actual missing persistence field.
9. Never insert archived history into forecast-vintage tables.

## Non-goals

1. Do not change setup-time source selection or disable/enable any source.
2. Do not add runtime source fallback.
3. Do not claim WeatherAPI archive rows are NWS-style station observations.
4. Do not manufacture past `ForecastSnapshotEntity` or desktop forecast-vintage rows.
5. Do not promise more historical depth than the configured WeatherAPI subscription permits.
6. Do not use `end_dt` in the first implementation; WeatherAPI documents it as a paid-plan
   feature, while the bundled production key currently behaves like a Free-plan key.
7. Do not add a separate WeatherAPI history screen, table, or graph style.
8. Do not replace NWS's station-observation WorkManager backfill.
9. Do not introduce `ExistingWorkPolicy.REPLACE`, explicit cancellation, or a new independently
   scheduled worker.
10. Do not log an API key, a URL containing the key, or a raw response body.
11. Do not destructively clear user databases to verify the change.

## Behavioral contract

### Baseline history depth

The first implementation targets the previous local calendar day:

1. It is the useful gap left by `forecast.json`.
2. It is supported by the current bundled Free-plan credential.
3. It provides a complete 24-hour day for graph and daily-history derivation.
4. It avoids probing unavailable paid history dates and consuming calls.

The current day continues to come from `forecast.json`, which includes elapsed hourly records.
Paid multi-day history can be added later behind an explicit, tested capability/configuration;
the design below keeps the date-based client API extensible for that work.

### Trigger and result table

| State at the current location | Action | Current forecast result |
|---|---|---|
| WeatherAPI disabled | No WeatherAPI history call | Unchanged |
| WeatherAPI enabled; yesterday has complete source coverage | No history call | Save normally |
| WeatherAPI enabled; yesterday is absent | Fetch yesterday once | Save forecast, then merge history |
| WeatherAPI enabled; yesterday is partially populated | Fetch yesterday once and idempotently replace/merge matching hours | Save forecast, then repair history |
| History HTTP 200 with parseable hours | Store hours, derive affected daily row, refresh UI/cache | Successful |
| Missing/invalid key or HTTP 401/403 | Record sparse failure and long cooldown | Forecast still successful |
| Plan/date restriction or other permanent HTTP 4xx | Record unsupported result and long cooldown | Forecast still successful |
| HTTP 429 | Respect retry guidance when available and apply quota cooldown | Forecast still successful |
| Offline, timeout, DNS, HTTP 5xx, or malformed payload | Record transient failure and retry on a later normal refresh | Forecast still successful |
| Coroutine cancellation | Rethrow; do not convert to a normal failure | Structured cancellation preserved |
| Location changes while work is in flight | Reject mismatched result through existing location contract | New location remains authoritative |

“Complete” should be a pure, tested coverage rule rather than `rowCount > 0`. Use distinct hourly
buckets for the target local date and require a reasonable near-complete day. The recommended
threshold is 20 distinct hourly buckets, matching tolerance for daylight-saving transitions and
occasional provider gaps. A zero-to-small partial day must trigger repair.

## Design

### 1. Add a reusable WeatherAPI history client

Extend `shared/.../data/remote/WeatherApi.kt` with a date-based method:

```kotlin
suspend fun getHistory(
    lat: Double,
    lon: Double,
    date: LocalDate,
): ForecastResult
```

Implementation requirements:

1. Call `/v1/history.json` with `key`, `q`, and `dt=YYYY-MM-DD`.
2. Continue using latitude/longitude for an unambiguous location.
3. Do not use `end_dt` initially.
4. Share WeatherAPI forecast-day/hour parsing between `forecast.json` and `history.json`; do not
   create two parsers that can drift.
5. Return the existing shared `ForecastResult` shape so both platforms can reuse the normal
   conversion/storage code.
6. Treat `current` as optional when parsing history responses; history correctness must not depend
   on a current-conditions object being present.
7. Preserve WeatherAPI's returned local timestamps consistently with the existing forecast parser.
   Add DST-focused fixtures so a history day is not shifted or duplicated by host time zone.
8. Convert non-2xx responses into typed access/HTTP failures that allow callers to distinguish
   authentication, plan/date restriction, quota, and transient server errors without parsing
   exception strings.
9. Rethrow `CancellationException`.
10. Redact the key in all logging and exception text.

If refactoring the current parser is necessary, first add characterization tests for the existing
forecast response so history support cannot silently alter forecast behavior.

### 2. Separate history capability from provenance

Replace or refine `WeatherSource.providesHistoricalActuals`, because one boolean currently answers
two different questions:

1. Can this provider supply source-specific historical weather?
2. Is that history a station observation/reanalysis rather than archived model/forecast data?

Use an explicit shared capability/provenance value, for example:

```kotlin
enum class HistoricalDataKind {
    STATION_OBSERVATION,
    REANALYSIS,
    ARCHIVED_PROVIDER_HISTORY,
    RECENT_FORECAST_HOURS,
    NONE,
}
```

The exact names may change during implementation, but the decisions must remain explicit:

| Source | Historical behavior |
|---|---|
| NWS | Station observations through its dedicated path |
| Open-Meteo | Provider/reanalysis history through `past_days` or archive support |
| Silurian | Past hours supplied by `include_past` |
| WeatherAPI | Archived provider history through `/history.json` |
| Sources without a supported historical product | Do not retain forecast precipitation as measured history |

Update `HistoricalActualsBackfill` to ask a narrowly named question such as whether the provider's
historical precipitation may feed source-specific history. Temperature and precipitation behavior
must remain consistent with the established non-NWS API behavior. Add tests that lock the mapping
for every `WeatherSource` so future enum additions cannot silently inherit the wrong semantics.

This step is a terminology/capability correction, not a new UI distinction and not a reason to
withhold WeatherAPI history.

### 3. Add a shared, pure missing-history policy

Extract the date and coverage decision from Android/desktop infrastructure. Inputs should include:

1. source;
2. requested location;
3. current instant and target-location zone/date;
4. distinct stored WeatherAPI hourly buckets for yesterday;
5. last attempt outcome/time; and
6. supported lookback depth.

The policy returns one of:

```kotlin
sealed interface ProviderHistoryDecision {
    data object NotApplicable
    data object AlreadyCovered
    data class Fetch(val date: LocalDate)
    data class Cooldown(val reason: String, val retryAtMs: Long)
}
```

Rules:

1. Only WeatherAPI uses the new endpoint policy in this change.
2. A complete previous day is `AlreadyCovered`.
3. A missing/partial previous day is `Fetch`.
4. Success is naturally persistent because database coverage prevents another request.
5. Permanent credentials/plan failures receive a long persisted cooldown so every forecast cycle
   does not spend quota on a known failure.
6. Transient failures receive a shorter cooldown and become eligible on a later normal refresh.
7. Cooldown keys include source, normalized/quantized location identity, and target date. A result
   from one location must not suppress another location.
8. The production default maximum lookback is one day.

Prefer a small platform-neutral policy in `:shared` and thin platform persistence adapters. If
existing fetch-state persistence can express these keys without semantic abuse, reuse it;
otherwise add a compact preferences/config entry rather than a database migration.

Suggested default retry classes:

| Outcome | Suggested minimum delay |
|---|---|
| Success | No timer needed; DB coverage is the guard |
| 401/403 or plan/date restriction | 24 hours |
| 429 | Provider `Retry-After` if present, otherwise at least 6 hours |
| Offline/timeout/DNS/5xx | Normal next data-fetch interval, with a floor of 60 minutes |
| Malformed 2xx payload | 6 hours and sparse error logging |

Constants must be centralized and unit-tested rather than repeated in Android and desktop code.

### 4. Integrate Android backfill into the normal WeatherAPI refresh

Add a focused Android orchestrator such as `WeatherApiHistoryBackfiller`, injected with:

1. `WeatherApi`;
2. `ObservationDao`/the existing observation storage abstraction;
3. `DailyHistorySnapshotter` or the existing daily-extrema recomputation entry point;
4. history-attempt persistence;
5. clock/time-zone provider; and
6. sparse logger.

Call it from the WeatherAPI branch of `ForecastFetchCoordinator` after the normal forecast result
has been parsed and saved:

1. Save the successful live forecast through the current code.
2. Evaluate yesterday's stored WeatherAPI coverage for the same active location.
3. If missing and not cooling down, call `WeatherApi.getHistory`.
4. Convert the returned past hours using the existing `HistoricalActualsBackfill`/observation
   mapping path.
5. Upsert idempotently using the existing WeatherAPI source/station identity.
6. Recompute/freeze the affected `daily_history` row through existing daily-history logic.
7. Trigger the same cache/widget invalidation used when observations change.

The optional history step must not turn an otherwise successful forecast refresh into a failed
WeatherAPI source result. Report it separately in logs and fetch diagnostics.

Do not put `/history.json` daily rows through the ordinary future-forecast snapshot writer:

1. They are historical reference data, not a forecast captured at that earlier issuance time.
2. Inserting them into `forecasts` or `forecast_snapshots` would fabricate prediction vintages.
3. It would distort forecast-evolution and accuracy calculations.

### 5. Route Android graph-demand checks by source capability

Refine `HourlyObservationBackfill.evaluateHourlyBackfillNeed`, which currently treats every
non-NWS source as though an organic backfill were active:

| Display source | Missing-history action |
|---|---|
| NWS | Existing NWS station-observation backfill |
| WeatherAPI | Request a targeted WeatherAPI refresh/history check |
| Open-Meteo/Silurian | Existing normal source fetch behavior |
| Unsupported/deprecated source | No history request; return an explicit reason |

The WeatherAPI graph-demand path should enqueue or reuse the existing targeted source refresh with
the safe unique-work policy already used by the widget. It must not add another worker type or use
`ExistingWorkPolicy.REPLACE`.

Because the actual endpoint work lives in the normal WeatherAPI fetch path, both situations behave
the same:

1. WeatherAPI is newly enabled and receives its first fetch.
2. A graph discovers that local WeatherAPI history was deleted, partial, or never populated.

Keep rapid renders from generating duplicate requests by using the persisted coverage/cooldown
decision and the existing unique-work `KEEP`/safe coalescing behavior.

### 6. Integrate desktop through the same policy and persistence semantics

Extend `DesktopWeatherService` with a WeatherAPI-specific history call that delegates to the new
shared client. Do not repurpose its Open-Meteo `fetchHistory(historyDays)` method.

In `DesktopWeatherRepository`:

1. After a successful WeatherAPI refresh is persisted, evaluate the same previous-day coverage
   policy.
2. Fetch yesterday only when absent/partial and eligible.
3. Convert returned hours with the same shared history-to-observation mapping as Android.
4. Persist the source-tagged raw observations.
5. recompute the affected daily-history/extrema row.
6. reload/notify the UI through the existing repository state path.
7. preserve the successful current forecast if the history request fails.

Desktop's existing `ensureHistory` zoom path may route WeatherAPI to this one-day repair when that
day is missing, but it must not advertise or repeatedly request 7–30 days that the bundled
credential cannot access. The UI should simply render the available provider coverage beyond that
point, as it does for other bounded sources.

The startup/refresh path is the primary trigger so desktop WeatherAPI history appears without the
user first zooming or panning the graph.

### 7. Keep location and source identity exact

Every coverage query, upsert, cooldown, and recomputation must retain:

1. `source = WEATHER_API`;
2. the existing synthetic/provider station ID convention;
3. latitude and longitude under the repository's current location-match tolerance;
4. target-location local date; and
5. original hourly timestamps.

Do not infer coverage from WeatherAPI rows at an old location. Reuse the existing location-match
contract and add tests around a near/far coordinate change.

The parser should retain WeatherAPI location metadata only for validation/diagnostics; the user's
configured coordinates remain the storage identity so provider-rounded coordinates do not split
one logical site.

### 8. Add sparse, queryable diagnostics

Add one low-frequency result breadcrumb per evaluation/fetch, for example:

```text
WAPI_HISTORY_CHECK source=WEATHER_API date=2026-07-27 coverage=0/24 decision=fetch
WAPI_HISTORY_RESULT source=WEATHER_API date=2026-07-27 hours=24 daily=1 result=stored
WAPI_HISTORY_RESULT source=WEATHER_API date=2026-07-27 result=cooldown class=auth retryAt=...
```

Requirements:

1. Do not log per-hour rows at DEBUG.
2. Use `Log.v` for any high-frequency graph-demand rejection trace.
3. Persist only sparse check/result summaries at DEBUG or INFO.
4. Never log the key or full request URL.
5. Include location only as the project's safe normalized site identity, not unnecessary precise
   coordinates in a persistent diagnostic.
6. Distinguish forecast success from optional history failure.

## Expected files

The final implementation should remain narrow. Expected touch points include:

1. `shared/src/main/kotlin/com/weatherwidget/data/remote/WeatherApi.kt`
2. `shared/src/main/kotlin/com/weatherwidget/data/model/WeatherSource.kt`
3. `shared/src/main/kotlin/com/weatherwidget/shared/actuals/HistoricalActualsBackfill.kt`
4. a new shared pure WeatherAPI/provider-history decision file
5. `app/src/main/java/com/weatherwidget/data/repository/ForecastFetchCoordinator.kt`
6. a new Android `WeatherApiHistoryBackfiller.kt`
7. `app/src/main/java/com/weatherwidget/widget/handlers/HourlyObservationBackfill.kt`
8. Android DI wiring if the orchestrator is injected
9. `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`
10. `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt`
11. focused shared, Android, and desktop tests

Implementation should inspect the current dirty worktree first and preserve the existing
setup-source-selection changes. In particular, `WeatherApi.kt` already has uncommitted work related
to the effective key/setup validation and must be edited as a merge, not overwritten.

## Test plan

### Shared client and parsing

Add or move WeatherAPI tests into the module that owns the client, following the current test
layout migration rather than duplicating fixtures:

1. `getHistory` sends `q` and one `dt`, with no `end_dt`.
2. API key resolution remains unchanged.
3. History response parses all hourly temperature, precipitation, condition, humidity, wind, and
   daily fields currently represented by `ForecastResult`.
4. Missing `current` object is accepted.
5. A DST-short and DST-long local day retains unique ordered hours.
6. Existing forecast parsing remains byte-for-byte/field-for-field compatible.
7. 401, 403, 429, 5xx, malformed JSON, and cancellation have the correct typed outcomes.
8. Exception/log text does not contain the key.

### Shared policy and provenance

1. Disabled/non-WeatherAPI source returns `NotApplicable`.
2. Twenty or more distinct yesterday buckets returns `AlreadyCovered`.
3. Missing and partial coverage returns `Fetch(yesterday)`.
4. Duplicate rows do not falsely satisfy the threshold.
5. Old-location rows do not satisfy new-location coverage.
6. Successful coverage suppresses later calls without a success preference.
7. Auth, plan, quota, transient, and malformed-response cooldowns differ as designed.
8. A new local day produces a new target date.
9. Every `WeatherSource` has an explicit history/provenance mapping.
10. WeatherAPI archived precipitation remains usable in its source-specific historical curve,
    matching established non-NWS behavior.

### Android

1. Successful WeatherAPI forecast plus missing yesterday performs one history call.
2. Complete yesterday performs zero history calls.
3. Partial yesterday is repaired.
4. Repeating the refresh is idempotent and does not duplicate observations.
5. History failure still reports/saves the forecast as successful.
6. Cancellation propagates.
7. Returned history for a stale location is not applied to the new location.
8. History hours are source-tagged `WEATHER_API`.
9. A daily-history row is derived for yesterday after insertion.
10. Historical precipitation and temperature reach the existing graph data loader.
11. No past forecast or forecast-snapshot rows are inserted.
12. Graph-demand routing chooses NWS, WeatherAPI, or organic/no-op behavior correctly.
13. The targeted WorkManager request uses the existing safe unique-work policy, never `REPLACE`.
14. Locale/resource tests remain unchanged unless a user-visible message is actually added.

Prefer pure JVM tests for decisions and Robolectric only where Context, Room, preferences, or
WorkManager are required. Apply exactly one required duration category to every test class.

### Desktop

1. WeatherAPI startup refresh with missing yesterday fetches and persists history.
2. Complete yesterday suppresses the call.
3. Partial rows are repaired idempotently.
4. History failure preserves the current forecast and cached future hours.
5. The repository recomputes and exposes yesterday's daily high/low/rain.
6. Old-location history does not satisfy current-location coverage.
7. Zoom demand can repair the supported missing day but does not loop on unavailable deeper days.
8. Forecast-vintage tables remain unchanged by history import.

## Verification commands

Run the narrowest tests first, then the relevant module suites:

```bash
./gradlew :shared:testShortShared
./gradlew :app:testShortDebugUnitTest
./gradlew :app:testMediumDebugUnitTest
./gradlew :desktop:testShortDesktop
./gradlew :desktop:testMediumDesktop
./gradlew :shared:test
./gradlew :app:testByDurationDebugUnitTest
./gradlew :desktop:testByDurationDesktop
```

If Android runtime wiring needs an instrumented test, run it emulator-only:

```bash
./scripts/emulator-tests.sh -c com.weatherwidget.data.repository.WeatherApiHistoryBackfillIntegrationTest
```

Do not shut down the emulator afterward.

## Runtime evidence plan

### Android emulator

1. Identify the emulator with `adb devices` and `getprop`; do not infer identity from serial.
2. Back up relevant app preferences/database files before changing a live configured source or
   location.
3. Use WeatherAPI at the current location or a reversible test location.
4. Query source-specific observation and daily-history coverage before refresh.
5. Trigger the existing safe targeted WeatherAPI refresh.
6. Query `app_logs` for `WAPI_HISTORY_CHECK`/`WAPI_HISTORY_RESULT`.
7. Pull the live Room database together with WAL/SHM if needed and verify:
   1. yesterday now has approximately 24 WeatherAPI observation hours;
   2. yesterday has a WeatherAPI-derived daily-history row;
   3. a second refresh adds no duplicates and performs no history network call; and
   4. no historical forecast snapshot was fabricated.
8. Navigate the hourly and daily graphs into yesterday and capture a screenshot showing that
   WeatherAPI history renders through the normal UI.
9. Restore any temporarily changed source/location state and leave the emulator running.

### Desktop

1. Record current WeatherAPI counts/ranges in the desktop database.
2. Start the rebuilt desktop app with WeatherAPI selected.
3. Trigger one normal refresh.
4. Verify one successful history result and a complete prior-day source range.
5. Verify the hourly graph and daily history expose those rows.
6. Refresh again and verify the endpoint is not called again.
7. Confirm the current/future forecast remains visible while simulating a history 4xx or transient
   failure in an automated test; do not invalidate the real key to perform this check.

### Credential/production safety

1. Verify no key appears in Gradle test reports, logs, exception messages, or captured request
   diagnostics.
2. Keep the existing release-key presence guard from the setup-source-selection work.
3. Do not infer the subscription tier from a local debug build alone. The implemented one-day
   policy is deliberately compatible with the currently observed bundled-key capability.

## Acceptance criteria

1. With WeatherAPI enabled and yesterday absent, one normal Android refresh automatically fetches
   and stores yesterday's WeatherAPI history.
2. The same is true on desktop.
3. WeatherAPI past hours appear through the existing hourly graph and daily-history behavior,
   without a WeatherAPI-only UI path.
4. Temperature and precipitation use the same source-specific historical pipeline as other
   non-NWS APIs.
5. Complete history causes no repeat network request.
6. Partial history is repaired idempotently.
7. A history failure never disables WeatherAPI and never discards a valid current forecast.
8. Auth/plan/quota failures do not retry on every scheduled fetch.
9. Location changes cannot mix or suppress history across sites.
10. No `/history.json` row is written as a historical forecast snapshot/vintage.
11. Android uses no new cancel/replace work path.
12. Logs provide one sparse, key-free result that can be verified from `app_logs`.
13. Focused tests and the affected module duration suites pass.
14. Emulator and desktop database plus graph evidence confirm the behavior end to end.

## Implementation sequence

1. Characterize existing `forecast.json` parsing and storage with tests.
2. Add the shared history/provenance model and pure coverage/cooldown policy.
3. Add `WeatherApi.getHistory` with fixtures and typed failures.
4. Add Android backfill orchestration and source-aware graph-demand routing.
5. Add desktop refresh/backfill parity.
6. Run focused tests and fix regressions before broad module suites.
7. Verify Android on the emulator using logs, database rows, and screenshots.
8. Verify desktop using logs, database rows, and graph behavior.
9. Update this plan's status and evidence with the exact test/runtime results.

