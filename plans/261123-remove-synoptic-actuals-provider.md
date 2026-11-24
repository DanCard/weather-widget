# Remove Synoptic as an Optional Actuals Provider

## Goal & Scope

Remove `WeatherSource.SYNOPTIC` as a selectable optional actuals provider across `:shared`, `:app`, and `:desktop`, along with its dedicated radius-fetch pipeline, backoff/gate machinery, schedulers, and picker options.

**Explicitly kept:** Synoptic's **NWS stale-station web fallback** (`SynopticStaleStationFallback`, `SynopticApi.fetchSynopticObservations`, `MesoTokenApi`, and `ApiUsageClassifier` tagging `synopticdata.com` as `"SYNOPTIC"`). That path fetches a single stale NWS station and files rows under `api = "NWS"` with `isWebFallback = true`.

## Motivation & Evidence

- **Unused default, high maintenance:** Borrowing sources default to NWS inside US coverage and METAR elsewhere (`ActualsProviderResolver.borrowerDefault`). Supporting Synoptic as an opt-in actuals provider adds a full parallel pipeline across all three modules (`SynopticObservationFetcher`, `SkyReportingStationSlots`, `SynopticFetchPolicy`, `SynopticFetchGate`, `SynopticFetchWindow`, `SynopticBackoff`, `SynopticObservationRefresher`, `SynopticObservationSource`, `DesktopSynopticBackoffStore`).
- **Expensive API:** Synoptic's `radius` endpoint does not sort `limit=` nearest-first, requiring full 25-mile radius pulls (~100–200 stations) just to discard ~95% of rows (`performance/261003-synoptic-fetch-cost-and-resilience.md`).
- **Feature gaps:** US-only, relies on scraping weather.gov's browser token (`MesoTokenApi`), and `SynopticApi.parseStationObservations` hardcodes `precipLastHourMm = null` (so Synoptic actuals carry no observed rain while `actualsCarryRain` defaulted to `true`).

## Detailed Changes

### 1. `:shared`

- **Delete files:**
  - `shared/src/main/kotlin/com/weatherwidget/shared/observations/SynopticObservationFetcher.kt`
  - `shared/src/main/kotlin/com/weatherwidget/shared/observations/SkyReportingStationSlots.kt`
  - `shared/src/main/kotlin/com/weatherwidget/shared/util/SynopticFetchPolicy.kt`
  - `shared/src/main/kotlin/com/weatherwidget/shared/util/SynopticFetchGate.kt`
  - `shared/src/main/kotlin/com/weatherwidget/shared/util/SynopticFetchWindow.kt`
  - `shared/src/main/kotlin/com/weatherwidget/shared/util/SynopticBackoff.kt`
- **Edit `WeatherSource.kt`:**
  - Remove `WeatherSource.SYNOPTIC` enum entry and its `"SYNOPTIC"` arm in `fromDisplayNameOrId`.
- **Edit `SynopticApi.kt`:**
  - Remove `fetchRadiusTimeseries`, `executeFetchRadiusTimeseries`, `parseRadiusTimeseries`, `RadiusStation`, `PARSED_VARS`, `MILES_TO_KM`, and `FEET_TO_METERS`.
  - Keep `fetchSynopticObservations`, `executeFetchSynopticObservations`, `parseSynopticTimeseries`, `parseStationObservations`, `mapSkyConditionToAmount`, `parseTimestampToIsoString`, and `MesoTokenApi` integration.
- **Edit shared policy/actuals/observation consumers:**
  - `ActualsProviderResolver.kt`: remove `WeatherSource.SYNOPTIC` exclusion in `allowsAlternativeProvider`, remove `synopticConfigured` parameter from `candidateOptions` (no longer needed once `SYNOPTIC` is gone).
  - `ActualsFeedPolicy.kt`: `STATION_NETWORK_FEEDS` becomes `setOf(WeatherSource.METAR)` (update KDoc).
  - `HourlyActualsArchiveRun.kt`: remove `WeatherSource.SYNOPTIC` from non-displayable exclusion set.
  - `DailyIconName.kt`: remove `WeatherSource.SYNOPTIC` branch.
  - `RainNow.kt`, `ObservedPrecip.kt`, `ActualsAggregator.kt`, `ObservationOrigin.kt`, `DominantStationLabel.kt`, `CloudLayerGlyphPlacer.kt`, `PersonalStationThinning.kt`, `NwsQualityControl.kt`, `CurrentConditionsFetcher.kt`, `StoredBlendGuard.kt`, `PastDayCoverage.kt`, `DailyBlendRecomputeRun.kt`, `DailyActualsAssembler.kt`, `ActualTemperatureSeriesBuilder.kt`, `MetarFetchWindow.kt`, `TargetedForecastSyncRun.kt`: remove `WeatherSource.SYNOPTIC` references and update comments where they mention Synoptic as an optional feed.
  - Keep `FailureClass.kt` (still used by `WeatherApiHistoryRun`, `ProviderHistoryPolicy`, `DesktopStatusBannerController`, `DesktopWeatherRepository`, `DesktopFetchErrorPresentation`).
  - Keep `ApiUsageClassifier.kt` mapping `synopticdata.com` → `"SYNOPTIC"` for the NWS stale-station fallback.

### 2. `:app` (Android)

- **Delete files:**
  - `app/src/main/java/com/weatherwidget/widget/SynopticObservationRefresher.kt`
  - `app/src/main/java/com/weatherwidget/data/repository/SynopticObservationSource.kt`
- **Edit wiring & callers:**
  - `AppModule.kt`: keep `SynopticApi` + `MesoTokenApi` (for `NwsObservationSource` stale-station fallback); remove `SynopticObservationSource` binding if provided there.
  - `WeatherWidgetWorker.kt`, `FullSyncPipeline.kt`, `AndroidTargetedSyncHost.kt`: remove `synopticRefresher` / `SynopticObservationRefresher` / `SynopticFetchPolicy` calls.
  - `CurrentObservationsActivity.kt`: remove `WeatherSource.SYNOPTIC` branch in refresh and drop `synopticConfigured = true` from `ActualsProviderResolver.candidateOptions(...)`.
  - `AndroidRainNow.kt`: remove `WeatherSource.SYNOPTIC.id` from `APIS`.
  - `SourceFetchLogTags.kt`: remove `WeatherSource.SYNOPTIC` arm.
  - `ActualsReadScope.kt`, `HourlyObservationBackfill.kt`, `TemperatureStateResolver.kt`, `ObservationDao.kt`, `ObservationEntity.kt`: remove `WeatherSource.SYNOPTIC` references / update stale comments.

### 3. `:desktop`

- **Delete files:**
  - `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopSynopticBackoffStore.kt`
- **Edit wiring & callers:**
  - `DesktopWeatherService.kt`: keep `synopticApi` + `sharedMesoTokenCache` for `NwsStationBundles` stale-station fallback; remove `synopticBackoffStore` param, `synopticFetcher`, `synopticGate`, and `WeatherSource.SYNOPTIC` branches in `fetchObservationsOnly` / `fetchStationNetworkObservations`.
  - `DesktopUiApplication.kt`, `DaemonRuntime.kt`, `DesktopWeatherRepository.kt`, `LaunchRefreshJobs.kt`, `WeatherApiClient.kt`: remove `DesktopSynopticBackoffStore` and `WeatherSource.SYNOPTIC` feed wiring.
  - `CurrentObservationsWindow.kt`: remove `synopticConfigured = true` from `ActualsProviderResolver.candidateOptions(...)` and any `WeatherSource.SYNOPTIC` references.
  - `DesktopActualsPreference.kt`, `NowDotStationsPopup.kt`: update comments/references.
  - `desktop/build.gradle.kts`: update comment mentioning `"SYNOPTIC"` if no longer relevant.

### 4. Backward Compatibility & Existing User State

- **Saved preferences:** Both Android (`WeatherSourcePreferences.actualsProvider`) and Desktop (`DesktopActualsPreference.lookup`) look up stored IDs via `WeatherSource.entries.firstOrNull { it.id == stored }`. Any existing user who selected `"SYNOPTIC"` will automatically resolve to `null` and fall back cleanly to the default (`NWS` inside US coverage, `METAR` outside). No preference migration or data clear is needed.
- **Stored DB rows:** Existing `observations` rows with `api = "SYNOPTIC"` will no longer be queried as an active provider and will age out naturally under normal retention. No Room/SQLite schema change is required.
- **Stale backoff files/prefs:** Desktop's `~/.local/share/weather-widget/synoptic_fetch_backoff` and Android's `synoptic_fetch_backoff` SharedPreferences become inert and harmless (no crash or side effects).

### 5. Tests

- **Delete tests & fixtures solely covering the removed radius/provider pipeline:**
  - `shared/src/test/kotlin/com/weatherwidget/data/remote/SynopticApiRadiusTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/data/remote/SynopticApiVarsEquivalenceTest.kt`
  - `shared/src/test/resources/synoptic/radius-full-recent120.json`
  - `shared/src/test/resources/synoptic/radius-vars-recent120.json`
  - `shared/src/test/kotlin/com/weatherwidget/shared/observations/SkyReportingStationSlotsTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/shared/observations/SkyStationSlotsGapIntegrationTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/shared/observations/SynopticObservationFetcherTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/shared/util/SynopticBackoffTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/shared/util/SynopticFetchGateTest.kt`
  - `shared/src/test/kotlin/com/weatherwidget/shared/util/SynopticFetchWindowTest.kt`
  - `app/src/test/java/com/weatherwidget/widget/SynopticObservationRefresherSingleFlightTest.kt`
  - `app/src/test/java/com/weatherwidget/widget/SynopticRefresherThinningTest.kt`
  - `app/src/test/java/com/weatherwidget/widget/SynopticStationTypeRetagTest.kt`
  - `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopSynopticBackoffStoreTest.kt`
- **Keep & verify tests for the NWS stale-station fallback:**
  - `MesoTokenApiTest`, `SynopticApiTokenTest`, `SynopticApiRulesTest` (trimmed of radius-only cases if any), `SynopticStaleStationFallbackTest`, `NwsCurrentWebLegIntegrationTest`.
- **Update remaining tests** that referenced `WeatherSource.SYNOPTIC` as a test fixture (e.g., `ActualsProviderResolverTest`, `ActualsFeedPolicyTest`, `ActualsNonDisplayableSourceTest`, `ActualsProviderChangeCoordinatorTest`, `TargetedSyncPostActualsIntegrationTest`, etc.) — use `METAR` or another valid provider where testing borrowing/non-displayable behavior, and add explicit regression tests verifying a stored `"SYNOPTIC"` actuals preference gracefully falls back to the default on both Android and Desktop.

### 6. Living Documentation (`arch/`)

Update all four living docs to reflect that METAR is the sole standalone station-network actuals feed while Synoptic remains only as NWS's stale-station web fallback:
- `arch/ARCHITECTURE.md` (RainNow station APIs)
- `arch/daily-history-extremes.md` (observation table list, METAR as actuals-only feed, RainAccuracyRunner measured rows)
- `arch/forecast-and-observation-cadence.md` (station-network gap window, targeted sync tail)
- `arch/operations.md` (actuals-only feeds list, targeted sync note)

## Verification

1. Run `./scripts/unit-tests.sh` across `:shared`, `:app`, and `:desktop`.
2. Run the full cross-platform end-of-task gate:
   ```bash
   ./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh
   ```
3. Verify on both Android and Desktop that:
   - The Actuals source picker in Current Observations no longer lists Synoptic.
   - Sources previously set to Synoptic (or borrowing sources) fall back to NWS (in US) / METAR (abroad) without errors.
   - NWS stale-station fallback (`SynopticStaleStationFallback`) remains wired and tested.
