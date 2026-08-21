# Plan: Add Meteo Actual Cloud Cover to the Hourly Cloud Graph

## Objective

Show Open-Meteo's recorded hourly cloud-cover percentage as the **actual** portion of the hourly Cloud Cover graph. It should use the same visible contract as the hourly temperature graph:

1. forecast cloud cover remains the normal forecast curve;
2. past hours with persisted, provenance-approved values draw as a continuous solid actual curve;
3. future hours retain the forecast curve; and
4. a missing actual value never becomes `0%`, never crosses a source/location boundary, and never erases an otherwise usable forecast curve.

The work applies to Android and desktop. The initial functional scope is `OPEN_METEO` (`Meteo`); NWS station observations are not cloud-cover observations today and must not be presented as such.

## Verified Starting Point

- `OpenMeteoApi.getForecast()` already requests `hourly=...,cloud_cover` and includes `past_days`, so the Meteo fetch result contains hourly cloud-cover values for past hours as well as forecasts.
- The temperature actual line does not read those values directly. `HistoricalActualsBackfill` persists the past hourly slice as source-scoped observations, and `ActualTemperatureSeriesBuilder` turns those persisted records into the graph's actual series.
- That observation channel currently contains temperature and (conditionally) precipitation, but no cloud-cover field. Consequently both `CloudCoverViewHandler` and desktop `CloudCoverGraph` only render the forecast `HourlyForecast.cloudCover` curve.
- The forecast and observation stores are location-keyed. Existing nearest-site/source filtering must remain the single authority; raw cross-fragment rows must not be introduced into this feature.

## Design Decisions and Invariants

1. **Meaning of actual.** An actual cloud-cover point is an hourly value that came from a provider history product and was persisted through the actuals channel. For this change Open-Meteo's reanalysis/history-backed past hourly values qualify; generic past forecast values do not.
2. **No fabricated values.** Do not derive cloud cover from weather icons, conditions, or temperature observations. Do not carry a missing actual cloud value forward merely to make the actual line continuous; leave that portion absent and preserve the forecast beneath it.
3. **Source and site isolation.** Select actual cloud points only when their stored API equals the displayed source and their persisted coordinate resolves to the same nearest site as the forecast window. `NWS_BLEND` and rows from another source are ineligible.
4. **Time boundary.** A point can be actual only when its timestamp is before the render's `now`; the current/future point remains forecast until a later refresh persists it as historical data. Use one end-inclusive visible-window definition on both renderers.
5. **Presentation.** Reuse the temperature graph's semantic distinction: solid, visually distinct actual stroke over the past segment and existing cloud forecast stroke for the remaining segment. Keep the cloud fill, percentage labels, icons, footer, watermark, and missing-forecast diagnostic behavior unchanged.
6. **Compatibility.** Existing databases migrate with nullable `cloudCover`; all sources that cannot supply eligible historical cloud cover continue rendering forecast-only with no schema/data crash.

## Implementation Steps

### 1. Extend the persisted actuals contract without changing its temperature semantics

1. Add nullable `cloudCover: Int?` (validated/clamped to `0..100` at the input boundary) to shared `ObservationReading` in `shared/src/main/kotlin/com/weatherwidget/data/model/ForecastTypes.kt`.
2. Add the corresponding nullable field to Android `ObservationEntity`, its `toReading()` conversion, every Android `ObservationReading -> ObservationEntity` mapper, and the desktop observation entity/conversions owned by `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/`.
3. Add additive migrations for both databases:
   - bump and migrate Android Room's `observations` table in `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`;
   - bump and migrate desktop SQLite in `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`.
   Preserve existing rows with `NULL`, and update the schema/migration tests rather than rebuilding or clearing either database.
4. Audit every explicit `ObservationReading(...)` and observation-entity constructor. Non-cloud providers and NWS station readings must explicitly or safely default to `null`; only a true historical-cloud input should populate the field.

### 2. Persist only provenance-approved historical cloud cover

1. Extend `HistoricalDataKind` in `shared/src/main/kotlin/com/weatherwidget/data/model/WeatherSource.kt` with an explicit historical-cloud eligibility property (parallel to the existing precipitation rule), rather than inferring eligibility from a source ID at render time.
2. Mark `OPEN_METEO` eligible because its hourly past slice is supplied by the Meteo historical/reanalysis path. Leave forecast-only, generic, and NWS station-observation paths ineligible until their APIs expose a separately verified cloud-cover observation product.
3. Update `HistoricalActualsBackfill.build()` to copy `HourlyForecast.cloudCover` only when that source is eligible; keep its existing timestamp cutoff, API label, synthetic station identity, and precipitation behavior unchanged.
4. Thread the field through Android `HourlyForecastStore.saveHistoricalActuals()` and desktop `DesktopWeatherService.withHistoricalActuals()`/DAO upsert paths. A refresh must replace a previously stored historical cloud value for the same synthetic source/time/site, just as it does the backed-up temperature value.
5. Add one sparse `DEBUG` summary at the persistence boundary (source, persisted historical-cloud count, null count, and time span). Do not log each hourly point; normal render sampling remains `VERBOSE` if diagnostics are needed.

### 3. Create a shared actual-cloud series resolver

1. Add a focused pure helper in `:shared` (for example `shared/.../actuals/ActualCloudCoverSeriesBuilder.kt`) instead of overloading `ActualTemperatureSeriesBuilder` with unrelated spatial-temperature logic.
2. Its input is the already source/site-filtered hourly forecast window, source-filtered `ObservationReading` records, displayed source, visible start/end, and render `now`. Its output is one point per graph timestamp containing forecast cloud cover, optional actual cloud cover, and `isActual`.
3. For top-of-hour values, choose an observation at that exact timestamp; for any retained non-hourly actual timestamp, insert a timestamped point only if it has a cloud value and can be mapped without changing the x-axis contract. Do not interpolate meteorological cloud cover and do not use the temperature builder's carry-forward behavior.
4. Reject `null`, out-of-range, future, wrong-source, and wrong-site records before assembling the result. If there are no eligible actuals, return a forecast-only result equivalent to today's cloud graph.
5. Put the resolver's matching, cutoff, null-gap, and invalid-value cases under plain `:shared` unit tests, each with the required `ShortDuration` category.

### 4. Wire Android data loading and rendering

1. Extend the hourly graph render request/data-loader path used by both `WidgetRenderer` and `GraphInteractionRenderer` to fetch the same nearest-site, displayed-source observation window for Cloud Cover that the temperature graph receives. Do not add a second raw DAO query in `CloudCoverViewHandler`.
2. Update `CloudCoverViewHandler` to call the shared actual-cloud resolver before `buildCloudHourDataList`; preserve its current forecast missing-hours calculation so it reports missing forecast coverage, not missing actual coverage.
3. Expand `CloudCoverGraphRenderer.CloudHourData` (or introduce a small sibling resolved-point type) with optional `actualCloudCover` and `isActual`. Pass this through the renderer without changing the existing label/footer input fields.
4. In `CloudCoverGraphRenderer`, calculate forecast coordinates as today, calculate actual coordinates from only contiguous eligible past points, and draw the actual path after the fill/forecast curve but before labels/icons. Split paths at gaps so no line bridges unknown values. Reuse the temperature graph's actual-stroke color/width language where it remains legible against the gray cloud curve; add cloud-specific paints in `CloudCoverGraphStyle` rather than creating renderer-local `Paint`s.
5. Keep the existing dynamic 85–100% cloud scale and derive both paths from the same scale and timeline geometry. Ensure `now` line, labels, icon bounds, percentage-label placement, and watermark collision calculations retain their current behavior.
6. Add low-volume `CLOUD_ACTUAL_SERIES` debug output only for each render summary (displayed source, forecast point count, actual point count, gap count, latest actual timestamp); make any per-point rejection trace `VERBOSE`.

### 5. Wire desktop parity

1. Pass `snapshot.raw.rawObservations` to `CloudCoverGraph(...)` from `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`, mirroring the existing temperature and precipitation calls.
2. Update `desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt` to call the same shared actual-cloud resolver with the identical source, site, visible window, and `now` inputs used for Android semantics.
3. Build forecast and actual coordinate paths from the resolver output, split actual segments at missing values, and draw the same solid actual treatment over the existing cloud forecast curve. Preserve Compose pan/zoom, gestures, labels, fill, footer, and missing-data text.
4. Ensure the desktop repository's cached observation load includes the new field and does not broaden source or site selection. Existing snapshots without the column should simply produce a forecast-only graph after migration.

## Tests and Verification

### Automated coverage

1. Update `shared/src/test/kotlin/com/weatherwidget/shared/actuals/HistoricalActualsBackfillTest.kt` to prove that Open-Meteo retains valid historical cloud cover and an ineligible provider leaves it `null`.
2. Add shared tests for the actual-cloud resolver: matching Meteo past hours become actual; future rows remain forecast; null and out-of-range values make gaps; wrong source/site rows are excluded; no actuals preserves forecast-only output.
3. Extend Android Room migration/DAO coverage for nullable observation cloud cover, plus a repository integration test that verifies `HourlyForecastStore` persists the backfilled Meteo field at the quantized site.
4. Extend `app/src/test/java/com/weatherwidget/widget/handlers/CloudCoverViewHandlerTest.kt` with an Android wiring case that asserts source/site-filtered actual values reach graph data without changing the forecast missing-data diagnostic.
5. Add a Robolectric rendering regression adjacent to `CloudCoverGraphLabelPlacementRobolectricTest.kt`: inspect renderer debug callbacks or controlled pixels/path metadata to prove a solid actual segment appears for past Meteo points, stops at a null gap, shares the forecast scale, and leaves future points forecast-styled. Categorize it by measured duration.
6. Add/update desktop graph tests to pass `ObservationReading.cloudCover` through `CloudCoverGraph`, asserting the same actual/future/gap behavior as the shared resolver. Keep all test classes in their module's required duration category.
7. Run focused tests first, then `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` once the targeted lanes pass. Resolve only failures caused by this feature.

### Runtime evidence

1. Build and install the Android debug app, then select Meteo and Cloud Cover on a running emulator. Capture a screenshot, `CLOUD_ACTUAL_SERIES`/cloud-renderer logcat, and the `observations` plus `hourly_forecasts` rows for the selected coordinate and time window.
2. Verify the screenshot shows a solid actual cloud-cover segment for eligible past Meteo hours, forecast styling for future hours, a visible break (not 0% or a bridge) for a deliberately missing historical value, and an unchanged forecast missing-data diagnostic when forecast cloud cover is absent.
3. Build/run desktop, select Meteo Cloud Cover, and capture the same past/future/gap cases. Confirm panning and zooming do not change the actual-series source or site.
4. Exercise a non-Meteo source and a migrated existing database; both must remain stable and forecast-only unless a later source is explicitly marked historical-cloud eligible.

## Files Expected to Change

| Area | Primary files |
| --- | --- |
| Shared model/provenance/series | `shared/src/main/kotlin/com/weatherwidget/data/model/ForecastTypes.kt`, `shared/src/main/kotlin/com/weatherwidget/data/model/WeatherSource.kt`, `shared/src/main/kotlin/com/weatherwidget/shared/actuals/HistoricalActualsBackfill.kt`, new actual-cloud resolver and tests |
| Android storage | `app/src/main/java/com/weatherwidget/data/local/ObservationEntity.kt`, `app/src/main/java/com/weatherwidget/data/local/WeatherDatabase.kt`, observation mappers, `app/src/main/java/com/weatherwidget/data/repository/HourlyForecastStore.kt` |
| Android graph | `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`, shared graph render request/data loader, `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`, `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphStyle.kt` |
| Desktop storage/rendering | `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`, `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDatabase.kt`, `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt`, `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`, `desktop/src/main/kotlin/com/weatherwidget/desktop/CloudCoverGraph.kt` |

## Out of Scope

- Adding NWS cloud-cover station observation ingestion or treating station conditions/icons as cloud-cover measurements.
- Reworking forecast-history snapshots or changing the existing cloud percentage label/zoom interaction design.
- Changing temperature actual-series blending, current-temperature resolution, provider selection, or refresh cadence.
