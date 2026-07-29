# Disable NWS and enable WeatherAPI during unsupported-location setup

**Date:** 2026-07-28  
**Status:** Implemented and verified  
**Scope:** Android launcher widget setup (`APPWIDGET_CONFIGURE`) only.

## Revised requirement

When the user confirms a location while adding the Android widget:

1. Check whether the NWS `/points` endpoint supports that location.
2. If NWS definitively reports that the point is unsupported, remove NWS from the enabled source
   list and, when WeatherAPI is not already enabled, validate WeatherAPI at that location before
   automatically enabling it.
3. If NWS works, leave the source settings unchanged.
4. If the check is inconclusive because of timeout, no network, DNS, HTTP 5xx, rate limiting,
   malformed data, or cancellation, leave NWS enabled.
5. A production release that promises the bundled WeatherAPI fallback must fail its release build
   before upload when the build-time WeatherAPI key is blank.

This is a one-time setup decision, not a continuously evaluated runtime policy.

“Setup” means `ConfigActivity` launched by the widget host with an
`AppWidgetManager.EXTRA_APPWIDGET_ID` and `isGlobalMode == false`. The same activity's Settings
entry (`EXTRA_GLOBAL_CONFIG`) is outside this rule.

## Resulting behavior

| Setup NWS check | Source-setting result | Setup result |
|---|---|---|
| Valid NWS gridpoint | No source changes | Save location and finish |
| Definitive NWS `InvalidPoint`; WeatherAPI already enabled | Disable NWS; preserve WeatherAPI | Save location and finish |
| Definitive NWS `InvalidPoint`; WeatherAPI validation succeeds | Disable NWS; enable WeatherAPI | Save location and finish |
| Definitive NWS `InvalidPoint`; WeatherAPI key missing or validation fails | Disable NWS; do not enable WeatherAPI | Save location and finish with another provider |
| Timeout/offline/DNS/HTTP 5xx/429 | No source changes | Save location and finish |
| Unexpected 404 or malformed response | No source changes | Save location and finish |
| Lifecycle cancellation/destruction | No source changes | Do not let the cancelled coroutine write or finish stale work |

Additional rules:

1. A successful setup check does not auto-enable NWS if the user already disabled it.
2. A later Settings location change does not automatically enable or disable NWS.
3. Passive GPS resampling and candidate-location promotion do not automatically alter sources.
4. Periodic/current-temperature/forecast failures do not automatically alter sources.
5. If another source is already enabled, disabling NWS preserves the remaining order.
6. If NWS is the only enabled source, replace it with Open-Meteo so the invariant “at least one
   source enabled” remains true and the newly configured widget has a usable provider.
7. If WeatherAPI is already enabled, preserve it without probing or moving it.
8. If WeatherAPI is not enabled, append it after the surviving enabled sources only when its
   effective key is nonblank and a bounded live validation returns a parseable WeatherAPI forecast
   for the selected location.
9. If no effective WeatherAPI key exists, or validation is unsuccessful or inconclusive, do not
   enable WeatherAPI; record why automatic enable was skipped rather than creating a source likely
   to fail.

## Current evidence

1. Fresh Android release installs enable `NWS,OPEN_METEO,SILURIAN`, so the normal unsupported-point
   result becomes `OPEN_METEO,SILURIAN`.
2. `ConfigActivity.saveChosenLocation` is the shared sink for confirmed GPS, search, and manual
   coordinate choices.
3. `ConfigActivity` has two modes:
   1. launcher widget setup, which saves the location and returns `RESULT_OK`; and
   2. Settings/global location editing, which updates all widgets without a widget-host result.
4. `WidgetStateManager.setVisibleSourcesOrder` owns the enabled source preference and resets widget
   source-toggle positions after the order changes.
5. WeatherAPI is a configurable global source and participates in forecast/current-temperature
   fetching when enabled.
6. Android's effective WeatherAPI key uses the user-entered per-source key first and
   `BuildConfig.WEATHER_API_KEY` second. The current checkout has a nonblank build-time key, while
   `WeatherApi` explicitly fails when no key is configured.
7. The locally generated release AAB contains the configured WeatherAPI key. A live London request
   with that key succeeded on 2026-07-28 and returned three forecast days. This proves the local
   artifact and current credential, but not an independently installed Play-delivered artifact.
8. Connected Android installations are local builds (`installer=null`), so none provides
   post-publication Play Store APK evidence.
9. `NwsCoverageCache.kt` is unused legacy code from a removed runtime-filtering feature. It must not
   be restored for this setup-only change because it:
   1. treats every exception as uncovered;
   2. persists runtime availability;
   3. shares neighboring location buckets; and
   4. could cause later background behavior to change source settings.
10. NWS documents `/points/{latitude},{longitude}` as the coordinate-to-grid lookup:
   <https://www.weather.gov/documentation/services-web-api>.
11. Live checks on 2026-07-28 showed:
   1. New York City returned HTTP 200;
   2. London returned HTTP 404 with problem type `InvalidPoint` and title
      `Data Unavailable For Requested Point`;
   3. Guam, the U.S. Virgin Islands, and American Samoa returned HTTP 200.

The territory results rule out device-country checks and simple
CONUS/Alaska/Hawaii/Puerto-Rico bounding boxes. The NWS response itself is the coverage authority.

## Goals

1. Disable NWS before the first widget fetch/render when the setup location is definitively outside
   NWS coverage.
2. Apply the rule to every user-confirmed location option in launcher setup:
   1. confirmed precise-device location;
   2. selected search result;
   3. manually entered coordinates; and
   4. the best-effort location used when leaving setup.
3. Bound setup latency and never strand the pending widget if the network is unavailable.
4. Preserve the user's source configuration on every inconclusive result.
5. Preserve at least one enabled source.
6. Enable WeatherAPI as an additional global source only when NWS is definitively unsupported and
   WeatherAPI successfully validates at the selected location.
7. Make release AAB creation/upload fail before publication when the bundled production
   WeatherAPI key is blank.
8. Log one sparse setup decision that can be verified from `app_logs`.

## Non-goals

1. Do not add runtime automatic source selection.
2. Do not add location-keyed NWS coverage preferences or a Room table.
3. Do not filter NWS out of an “effective” source list while leaving it stored as enabled.
4. Do not revisit the decision during fetches, current-temperature updates, screen unlock, passive
   GPS movement, or location handoff.
5. Do not change the desktop app.
6. Do not auto-enable NWS after a later move into coverage.
7. Do not disable NWS on generic fetch failure.
8. Do not change WorkManager names/policies or add any cancel/replace path.
9. Do not enable WeatherAPI when its effective key is blank.
10. Do not disable or reorder an already-enabled WeatherAPI source because a setup-time validation
    is unavailable or unsuccessful.
11. Do not treat an API key embedded in an Android AAB/APK as a secret; it is extractable by
    clients. This change does not add ineffective key obfuscation or replace provider-side quota,
    rotation, and restriction controls.

## Design

### 1. Add a setup-only three-way coverage checker

Introduce a narrow Android setup component, for example:

```kotlin
enum class SetupNwsCoverage {
    SUPPORTED,
    UNSUPPORTED,
    INCONCLUSIVE,
}

class SetupNwsCoverageChecker {
    suspend fun check(latitude: Double, longitude: Double): SetupNwsCoverage
}
```

The checker uses `NwsApi.getGridPoint` under a short, explicit timeout.

Classification:

1. A valid, parseable gridpoint is `SUPPORTED`.
2. HTTP 404 is `UNSUPPORTED` only when the NWS problem body identifies
   `InvalidPoint`/`Data Unavailable For Requested Point`.
3. All other exceptions and responses are `INCONCLUSIVE`.
4. `CancellationException` is rethrown rather than converted to `INCONCLUSIVE`.

Prefer a shared typed `NwsPointUnavailableException` from `NwsApi.getGridPoint` so setup,
forecast, desktop fallback, and future callers do not each parse NWS problem documents
differently. This type does not itself change runtime source behavior.

Do not use or write `NwsCoverageCache`. No setup result needs to persist by location after the
source preference has been updated.

### 2. Put the check in the launcher setup save pipeline

Refactor the existing save sink into a guarded asynchronous pipeline:

1. The user chooses/confirms coordinates.
2. If `isGlobalMode == false`, show a bounded “checking weather coverage” state and call the setup
   checker.
3. Apply the source decision.
4. Persist the chosen active location using the existing `LocationUpdater` path.
5. Enqueue the existing force refresh.
6. return `RESULT_OK` with the widget ID and finish.

For `isGlobalMode == true`, skip the NWS check and retain the current Settings location-save
behavior.

Guard against double taps while the check is running. Disable the relevant confirmation controls
or use one activity-level `saveInProgress` flag. Any new progress text must be added to all locale
resource directories and covered by `LocaleResourceParityTest`.

Use a timeout short enough that setup cannot feel stuck. The exact constant should be fixed by a
test and recorded in the code; the plan recommends approximately five seconds. Timeout means
`INCONCLUSIVE`, followed by an ordinary successful location save.

### 3. Validate WeatherAPI before automatically enabling it

Only after the NWS result is `UNSUPPORTED`, NWS is currently enabled, WeatherAPI is currently
disabled, and the effective WeatherAPI key is nonblank:

1. Make a bounded WeatherAPI forecast request for the selected location.
2. Use the same credential resolution and forecast endpoint as the production `WeatherApi` client.
3. Request the smallest forecast payload that still proves forecast access, rather than validating
   only an unrelated current-conditions endpoint.
4. Classify a successful 2xx response with a parseable, nonempty forecast as `AVAILABLE`.
5. Classify missing/invalid credentials, HTTP 401/403, quota/rate responses, other non-2xx
   responses, timeout, DNS/offline errors, and malformed/empty data as not available for automatic
   enablement.
6. Rethrow `CancellationException` so cancelled setup cannot mutate sources or finish stale work.
7. Never log the credential, request URL containing the credential, or response body.

The validation result decides only whether to add a currently-disabled WeatherAPI source. It does
not remove WeatherAPI when the user already enabled it. Apply the final source mutation only after
the validation completes so activity cancellation cannot leave a partial source change.

Keep the combined NWS and WeatherAPI setup delay explicitly bounded. Tests should fix the timeout
constants and cover the serial worst case; setup must still finish with Open-Meteo or another
surviving provider when WeatherAPI validation cannot complete.

### 4. Apply a one-way setup source mutation

Extract the decision as pure logic:

```kotlin
fun sourcesAfterSetupCheck(
    current: List<WeatherSource>,
    result: SetupNwsCoverage,
    weatherApiAvailable: Boolean,
): List<WeatherSource>
```

Rules:

1. `SUPPORTED` and `INCONCLUSIVE` return the input unchanged.
2. `UNSUPPORTED` also returns the input unchanged when NWS is already disabled; the setup check
   must not use NWS unavailability as a reason to rewrite unrelated user source choices.
3. Otherwise, `UNSUPPORTED` removes NWS while preserving every other source and its order.
4. If removing NWS would leave the list empty, start the replacement list with `OPEN_METEO`.
5. Do not add Open-Meteo when another enabled source remains.
6. When `weatherApiAvailable` is true and WeatherAPI is not already present, append WeatherAPI
   after the surviving list.
7. When `weatherApiAvailable` is false, leave WeatherAPI disabled.
8. Do not reorder the surviving sources or duplicate WeatherAPI.

Resolve `weatherApiConfigured` using the same precedence as `AppModule`:

1. a nonblank `WidgetStateManager.getApiKey(WeatherSource.WEATHER_API)`; otherwise
2. a nonblank `BuildConfig.WEATHER_API_KEY`.

Keep that effective-key check in one small Android helper so setup and dependency wiring cannot
silently drift.

Only write preferences when the resulting list differs. Use a dedicated
`WidgetStateManager.disableSourceForSetup` (or similarly explicit API) rather than putting coverage
behavior into general source getters.

`setVisibleSourcesOrder` currently resets all widget display-source steps. That is acceptable for
a first widget but can disturb existing widgets when the user adds another one. The setup-specific
mutation should therefore:

1. capture each existing widget's selected source before removing NWS;
2. remove NWS from the global enabled list;
3. restore surviving selections at their new indexes; and
4. reset only widgets that were displaying NWS to the first remaining source.

The pending new widget has no meaningful explicit source selection and starts with the first
remaining source—normally Open-Meteo.

### 5. Add a production release-key gate

Add a release-only Gradle verification task and wire the release AAB build/upload path through it:

1. `bundleRelease`/the Fastlane `build` lane must fail before upload when the resolved build-time
   `WEATHER_API_KEY` is blank.
2. Debug builds remain allowed without a bundled key because a developer can enter a per-source
   key and because debug must not depend on release credentials.
3. The check reports only configured/missing state; it never prints the key.
4. Fastlane continues uploading the exact AAB produced by the guarded `bundleRelease` invocation.
5. Add a focused Gradle verification fixture or task-level test proving blank release
   configuration fails and nonblank configuration reaches bundle creation.

This gate proves the release artifact was built from a nonblank configured value. The live setup
request separately proves that the embedded or user-entered effective credential is currently
accepted and can retrieve forecast data. Both are needed because a present key can later be
revoked, expired, restricted, or quota-limited.

### 6. Keep setup completion lifecycle-safe

`ConfigActivity` currently finishes synchronously after a location choice. Making setup wait for a
network check changes that contract, so cover:

1. activity destruction/rotation while the check is pending;
2. system or in-app Back during the check—cancel the probe, leave NWS unchanged, and use the
   existing best-effort widget-add completion path;
3. the existing best-effort Back path that completes the widget-host handshake;
4. duplicate completion attempts;
5. cancellation without writing a stale source/location result.

Prefer a lifecycle-owned coroutine and a single finalization method. Do not launch an unowned
network coroutine. The existing unowned scope remains appropriate only for final diagnostic
logging.

### 7. Add sparse setup diagnostics

Write one persistent `NWS_SETUP_CHECK` row per completed setup decision:

```text
widget=<id> lat=<lat> lon=<lon> result=supported sourceChange=none elapsedMs=<n>
widget=<id> lat=<lat> lon=<lon> result=unsupported weatherapi=available sourceChange=disabled_nws,enabled_weatherapi elapsedMs=<n>
widget=<id> lat=<lat> lon=<lon> result=unsupported sourceChange=disabled_nws,weatherapi_skipped_missing_key elapsedMs=<n>
widget=<id> lat=<lat> lon=<lon> result=unsupported weatherapi=unavailable reason=http_401 sourceChange=disabled_nws elapsedMs=<n>
widget=<id> lat=<lat> lon=<lon> result=inconclusive reason=timeout sourceChange=none elapsedMs=<n>
```

Do not log full response bodies, and do not add any render/fetch-loop coverage logs for this
feature.

## Implementation phases

### Phase 1: tests for classification and source mutation

1. Add mock-engine `NwsApi`/checker tests:
   1. valid gridpoint → `SUPPORTED`;
   2. NWS `InvalidPoint` problem → `UNSUPPORTED`;
   3. unrelated 404 → `INCONCLUSIVE`;
   4. HTTP 429 and 5xx → `INCONCLUSIVE`;
   5. DNS/timeout → `INCONCLUSIVE`;
   6. cancellation propagates.
2. Add WeatherAPI availability-check tests:
   1. parseable nonempty forecast → `AVAILABLE`;
   2. missing key skips the request and is unavailable;
   3. HTTP 401/403, 429, and 5xx do not enable WeatherAPI;
   4. timeout/DNS, malformed data, and an empty forecast do not enable WeatherAPI;
   5. cancellation propagates;
   6. request and diagnostics never expose the key.
3. Add pure source-policy tests:
   1. available WeatherAPI with the default list becomes
      `OPEN_METEO,SILURIAN,WEATHER_API`;
   2. available WeatherAPI with `NWS,SILURIAN` becomes `SILURIAN,WEATHER_API`;
   3. available WeatherAPI with `NWS` becomes `OPEN_METEO,WEATHER_API`;
   4. an already-enabled WeatherAPI is not duplicated or moved;
   5. unavailable WeatherAPI removes NWS without enabling WeatherAPI;
   6. an already-disabled NWS list is unchanged;
   7. supported and inconclusive results are exact no-ops.
4. Prove the unsupported/default assertion fails before implementation.

### Phase 2: integrate with `ConfigActivity`

1. Inject the checker through Hilt and provide a focused test seam/fake.
2. Route all launcher setup save choices through the asynchronous check.
3. Bypass the check in Settings/global mode.
4. Add the in-progress/double-tap guard.
5. Resolve effective WeatherAPI key availability without logging or exposing the key.
6. If eligible, run the bounded WeatherAPI availability check before applying source changes.
7. Skip WeatherAPI validation when NWS is supported/inconclusive/already disabled, WeatherAPI is
   already enabled, or no effective key exists.
8. Apply the setup-specific source mutation before the refresh worker is enqueued.
9. Keep `RESULT_OK` and widget-ID echo semantics unchanged after finalization.

### Phase 3: preserve existing widget selections

1. Implement the setup-specific disable operation.
2. Restore existing widgets that selected surviving sources.
3. Move existing NWS-displaying widgets to the first remaining source.
4. Confirm no source-toggle key points at a different source merely because the list shrank.

### Phase 4: add the release-key publication gate

1. Add the release-only nonblank-key validation task in `app/build.gradle.kts`.
2. Make `bundleRelease` and the Fastlane build/upload lanes unable to bypass the validation.
3. Add focused verification for blank and nonblank release-key configurations without printing the
   credential.
4. Build a release AAB with the configured local credential and verify the guarded task ran.

### Phase 5: focused and emulator verification

1. Extend `ConfigActivityAddFlowRoboTest`:
   1. London confirmation waits for both fake checks, enables WeatherAPI only on the successful
      WeatherAPI result, saves the location, and returns `RESULT_OK`;
   2. a supported point leaves sources untouched;
   3. timeout/inconclusive leaves sources untouched and still completes setup;
   4. a second tap cannot launch a second check or completion;
   5. Settings/global mode never invokes either checker;
   6. missing, invalid, quota-limited, and inconclusive WeatherAPI validation removes unsupported
      NWS but does not add WeatherAPI;
   7. an already-enabled WeatherAPI is preserved without a validation call.
2. Add/extend `WidgetStateManager` tests for preservation of existing widget selections.
3. Run `LocaleResourceParityTest` if a string is added.
4. On an emulator:
   1. start from the fresh enabled-source list;
   2. configure a widget for London;
   3. verify `NWS_SETUP_CHECK result=unsupported` records both `disabled_nws` and
      `enabled_weatherapi`;
   4. verify Settings shows NWS unchecked and Open-Meteo, Silurian, and WeatherAPI enabled in that
      order;
   5. verify the setup log records successful WeatherAPI validation without exposing the key;
   6. verify WeatherAPI completes the subsequent real forecast fetch;
   7. verify the first populated widget body uses Open-Meteo;
   8. repeat with an NWS-supported point and verify NWS stays enabled without auto-enabling
      WeatherAPI;
   9. test an offline/timeout NWS setup and verify the widget completes while NWS and WeatherAPI
      settings remain unchanged.
   10. use a fake or deliberately invalid test credential in controlled coverage to verify failed
       WeatherAPI validation does not add the source; do not overwrite or expose the real key.
5. Restore emulator network/location state and leave the emulator running.

## Expected files

Likely production changes:

1. `shared/src/main/kotlin/com/weatherwidget/data/remote/NwsApi.kt`
2. A small typed NWS unsupported-point exception near `NwsApi`
3. New Android setup coverage checker/policy files
4. `app/src/main/java/com/weatherwidget/ui/ConfigActivity.kt`
5. `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
6. A small effective-source-credential helper, or the existing key wiring refactored to expose the
   same nonblank-key decision safely
7. A bounded WeatherAPI setup availability checker using the production client/endpoint semantics
8. `app/build.gradle.kts` for the release-only nonblank-key gate
9. `fastlane/Fastfile` only if required to make the guarded release build explicit in the upload
   lane
10. Android string resources only if a new progress message is needed

Likely tests:

1. `app/src/test/java/com/weatherwidget/data/remote/NwsApiTest.kt`
2. New NWS/WeatherAPI setup checker and pure source-policy tests
3. `app/src/test/java/com/weatherwidget/ui/ConfigActivityAddFlowRoboTest.kt`
4. `app/src/test/java/com/weatherwidget/widget/WidgetStateManagerTest.kt`
5. `LocaleResourceParityTest` when resources change
6. A focused release-key Gradle task test or fixture

The following should not need production changes:

1. `ForecastFetchCoordinator`
2. `CurrentTempRepository`
3. `WeatherWidgetWorker`
4. `LocationHandoffStore`/`LocationHandoffPolicy`
5. `SettingsActivity`
6. `NwsCoverageCache`

## Verification commands

Use each test class's measured duration category:

```bash
./gradlew :app:testShortDebugUnitTest --tests com.weatherwidget.data.remote.NwsApiTest
./gradlew :app:testShortDebugUnitTest --tests '*SetupNwsCoverageCheckerTest*'
./gradlew :app:testShortDebugUnitTest --tests '*SetupWeatherApiAvailabilityCheckerTest*'
./gradlew :app:testShortDebugUnitTest --tests '*SetupSourcePolicyTest*'
./gradlew :app:testLongDebugUnitTest --tests com.weatherwidget.ui.ConfigActivityAddFlowRoboTest
./gradlew :app:testLongDebugUnitTest --tests com.weatherwidget.widget.WidgetStateManagerTest
./gradlew :app:testByDurationDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease
git diff --check
```

If real `RemoteViews` behavior requires a new device test, add matching Robolectric coverage and
run the emulator-only class:

```bash
./scripts/emulator-tests.sh -c <fully.qualified.TestClass>
```

## Acceptance criteria

1. A launcher widget setup at a definitively unsupported NWS point completes with NWS removed from
   the enabled source list and WeatherAPI enabled only after a successful live validation.
2. Under the fresh-install defaults with a valid configured key, the resulting order is
   `OPEN_METEO,SILURIAN,WEATHER_API`.
3. NWS remains unchanged for supported and inconclusive checks.
4. No timeout, outage, rate limit, malformed response, or cancellation disables NWS.
5. Settings/global location changes and passive/background location changes never invoke this
   policy.
6. The source list never becomes empty.
7. WeatherAPI is not automatically enabled without an effective key or after an unsuccessful or
   inconclusive validation, and it is never duplicated.
8. Existing widgets retain surviving explicit source selections when another widget's setup
   disables NWS.
9. Setup still returns `RESULT_OK` with the widget ID and cannot complete twice.
10. A production release AAB cannot be built/uploaded through the supported Gradle/Fastlane path
    with a blank bundled WeatherAPI key, and no verification output exposes the credential.
11. No runtime coverage cache, source filtering, repository fetch-policy change, Room migration, or
    new background work is introduced.

## Implementation result

Implemented on 2026-07-28:

1. `NwsApi.getGridPoint` now distinguishes the definitive NWS `InvalidPoint` problem response
   with `NwsPointUnavailableException`; unrelated 404s and other failures remain ordinary API
   failures.
2. `SetupSourceSelector` performs the setup-only tri-state NWS check, validates WeatherAPI with a
   bounded one-day forecast request only when eligible, and applies the pure one-way source policy.
3. `ConfigActivity` runs the decision only for launcher widget setup, blocks duplicate saves,
   preserves the widget-host result handshake, and cancels a pending probe without changing
   sources when Back is pressed.
4. `WidgetStateManager.setVisibleSourcesOrderForSetup` translates existing widget selections by
   source identity instead of resetting every widget's source toggle.
5. `WeatherApiCredentialProvider` gives user-entered nonblank credentials precedence over the
   bundled build credential and is shared by dependency wiring and setup eligibility.
6. Release `assembleRelease` and `bundleRelease` now depend on
   `validateReleaseWeatherApiKey`; a blank `-PWEATHER_API_KEY=` fails before the release artifact is
   produced.
7. No runtime coverage cache, repository filtering, Room migration, resource-string addition,
   WorkManager policy change, desktop change, or Fastlane edit was needed. Existing Fastlane
   publication lanes invoke guarded `bundleRelease`.

## Verification result

1. Categorized Android unit/Robolectric suite:
   `./gradlew :app:testByDurationDebugUnitTest` — passed.
2. Focused selector tests:
   `./gradlew :app:testMediumDebugUnitTest --tests SetupSourceSelectorTest` — passed.
3. Focused API tests:
   `./gradlew :app:testShortDebugUnitTest --tests NwsApiTest --tests WeatherApiTest` — passed.
4. Focused activity/state tests:
   `./gradlew :app:testLongDebugUnitTest --tests ConfigActivityAddFlowRoboTest --tests
   ConfigActivityRobolectricTest --tests WidgetStateManagerTest` — passed.
5. Instrumented APK compilation:
   `./gradlew :app:assembleDebugAndroidTest` — passed.
6. Opt-in live emulator test using the production Hilt selector and real endpoints for London:
   one test passed, with NWS `UNSUPPORTED`, WeatherAPI `AVAILABLE`, and final order
   `OPEN_METEO,SILURIAN,WEATHER_API`.
7. Release negative check:
   `./gradlew :app:validateReleaseWeatherApiKey -PWEATHER_API_KEY=` — failed as required without
   printing a credential.
8. Release positive check:
   `./gradlew :app:bundleRelease` — passed; the key gate reported only `configured`, and the rebuilt
   AAB contains the configured value.
9. `git diff --check` — passed.
