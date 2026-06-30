# 2026-06-29 — Disabling Fallbacks for Hourly Graphs and Implementing Gap Detection API Triggers

## Summary

The user investigated a mismatch on next Monday's forecast (+7 days) between the Daily view (vertical bar covered with >50% gray) and the Cloud Cover graph (showing 0% cloud cover at noon). By capturing emulator screenshots, preference data, and querying the database, we discovered that NWS hourly data stopped early on July 6 (07:00 AM), causing:
1. The Daily view to strictly use the closest NWS hourly forecast (07:00 AM, reporting 69% cloud cover).
2. The Cloud Cover graph to fall back to the first available source (**Open-Meteo**, reporting 0% cloud cover) due to the priority fallback chain `preferred ?: gap ?: entry.value.firstOrNull()`.

The user requested the removal of all fallbacks for hourly graphs (Temperature, Cloud Cover, and Precipitation) when data is missing, and requested that we trigger a refresh from the API when gaps are encountered. We disabled fallbacks in the shared forecast selector, all three hourly graph hour-builders, and the text-mode layout binder. We also added gap detection that schedules an immediate API update with a 15-minute cooldown. All 1,377 unit tests passed successfully.

## User Prompts (in chronological order, verbatim)

1. "Daily forecast view: how does cloud cover percent work on vertical bar?"

2. "Clould cover for next monday, +7 days, on cloud cover graph is 0% for noon, yet, on daily forecast view, the bar is covered with grey more than 50%.  Why?  Use emulator."

3. "There should be no fallback for hourly cloud graph when data is missing, same with temperature and cloud cover.  Perhaps trigger a read from the api, to see if data is available, but no fallback."

4. "write detailed session log to session-logs/ dir"

## Session Timeline and Actions (numbered for clarity)

1. **Daily Cloud Cover Percent Explanation**:
   * Analyzed [DailyForecastGraphRenderer.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt) and [WeatherConditionColors.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt).
   * Explained that daily vertical bars represent the temperature range. If the day is mixed/cloudy, the bar splits. The top segment is Gold/Amber (`#F4C542`) representing the clear fraction, and the bottom is Steel Blue (`#5A8FBF`) for rain or Slate Gray (`#8E99A4`) for cloud. The split height fraction corresponds to the noon hourly cloud cover percentage (or fallback from the weather icon).

2. **Divergence Investigation**:
   * Backed up emulator database and preferences using `scripts/backup_databases.py`.
   * Inspected active widget preferences and found that NWS was the active display source.
   * Queried hourly forecast entries for next Monday (July 6, 2026) in the SQLite database. Found that:
     * NWS hourly data only existed up to 07:00 AM on July 6 (with 69% cloud cover).
     * Open-Meteo data existed up to 04:00 PM (reporting 0% at noon).
     * Silurian data existed up to 04:00 PM (reporting 66% at noon).
   * Captured and verified the emulator's visual layout via screen capture.
   * Traced the discrepancy:
     * In [DailyNoonCloudCover.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/util/DailyNoonCloudCover.kt), the daily bar resolves cloud cover strictly for the target display source (NWS). Since data stops at 07:00 AM, it uses the 07:00 AM NWS data point (69% cloud cover), rendering >50% gray.
     * In [CloudCoverViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt), `buildCloudHourDataList` used the fallback priority `preferred ?: gap ?: entry.value.firstOrNull()`. Because NWS was missing at noon, it fell back to Open-Meteo (0% cloud cover).
   * Created [cloud_cover_mismatch_analysis.md](file:///home/dcar/.gemini/antigravity-cli/brain/6aa9e87b-f961-462d-81c7-1b7d9eecf672/cloud_cover_mismatch_analysis.md) summarizing these details.

3. **Disabling Fallbacks**:
   * Removed cross-source fallback in the shared [HourlyForecastSelector.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/actuals/HourlyForecastSelector.kt) by changing the filter to strictly match `displaySourceId`.
   * Disabled fallbacks in [CloudCoverViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt) and [PrecipViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt) by replacing the fallback chain with `entry.value.find { it.source == displaySource.id }`.
   * Updated the text-mode layout binder in [HourlyGraphViewCommon.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/HourlyGraphViewCommon.kt) to match.

4. **Implementing Gap Detection & API Trigger**:
   * Added checks in all three hourly view handlers to detect when hours in the visible graph window lack data from the displayed source.
   * If gaps exist, they invoke `stateManager.shouldRefreshMissingData(...)` with a 15-minute cooldown and trigger a refresh request via `WeatherWidgetProvider.triggerImmediateUpdate(forceRefresh = true, reason = "hourly_gaps")`.
   * Handlers modified: [CloudCoverViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt) (cloud cover), [PrecipViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt) (precipitation), and [TemperatureStateResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt) (temperature).

5. **Updating and Verifying Tests**:
   * Updated unit tests in [HourlyForecastSelectorTest.kt](file:///home/dcar/projects/weather-widget/shared/src/test/kotlin/com/weatherwidget/shared/actuals/HourlyForecastSelectorTest.kt) and [PrecipViewHandlerTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/PrecipViewHandlerTest.kt) to assert that empty results are returned (instead of fallbacks) when target source data is missing.
   * Ran the full JVM test suite via Gradle. All 1,377 tests passed.
   * Committed all code changes to Git.

## Key Technical Details

### Files Modified:
* **[HourlyForecastSelector.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/actuals/HourlyForecastSelector.kt)**: Strictly filters hourly forecasts by display source.
* **[HourlyForecastSelectorTest.kt](file:///home/dcar/projects/weather-widget/shared/src/test/kotlin/com/weatherwidget/shared/actuals/HourlyForecastSelectorTest.kt)**: Updated assertions to verify no fallback to `GENERIC_GAP` when display source is absent.
* **[CloudCoverViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt)**: Removed hourly fallback logic; added hourly gap detection and API fetch requests.
* **[PrecipViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt)**: Removed hourly fallback logic; added hourly gap detection and API fetch requests.
* **[PrecipViewHandlerTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/PrecipViewHandlerTest.kt)**: Updated test case to verify empty list return instead of fallback when NWS data is missing.
* **[HourlyGraphViewCommon.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/HourlyGraphViewCommon.kt)**: Disabled fallback in text-mode hourly layouts.
* **[TemperatureStateResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt)**: Added hourly gap detection and API fetch requests in the temperature hourly loading pathway.

## Outcome and Current State

* The fallbacks for hourly weather graphs (temperature, precipitation, and cloud cover) are disabled. Gaps are now represented honestly as missing points.
* Active gap detection triggers an immediate background API fetch when missing data is rendered, respecting a 15-minute cooldown period to avoid API spam.
* All unit tests passed, and changes are successfully committed to Git.
