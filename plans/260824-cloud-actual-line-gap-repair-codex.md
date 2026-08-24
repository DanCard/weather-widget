# Cloud Actual-Line Gap Repair

Date: 2026-08-24
Status: Implemented and verified

## User report

1. Desktop hourly cloud-cover percentage graph has disconnected lines while displaying Open-Meteo.
2. The emulator shows the same visual symptom while displaying NWS.

## Scope

Repair the two proven causes of disconnected solid cloud-actual curves without turning missing
measurements into invented values:

1. Correct shared segmentation of dense, mixed-cadence NWS/METAR actual points on Android and
   desktop.
2. Let desktop repair recent gaps in the METAR history borrowed by forecast-only sources such as
   Open-Meteo.

This plan builds on the approved, in-progress native-timestamp METAR work in
`plans/260824-subhourly-metar-cloud-blend.md`. Existing uncommitted source and test changes are
user-owned and must be preserved or deliberately extended, not overwritten.

## Evidence collected

### Desktop / Open-Meteo

1. The running desktop configuration was:
   - Location: Avery Drive, Mountain View, California.
   - View: cloud cover.
   - Forecast source: `OPEN_METEO`.
2. Open-Meteo is forecast-model data in this architecture and does not become a measured actual.
   Its solid actual curve correctly borrows the independently measured `METAR` feed through
   `ActualsProviderResolver`.
3. The active desktop database is
   `~/.local/share/weather-widget/weather.db`, not the older config-directory database.
4. The solid curve's persisted METAR timestamps contain a real hole from 05:35 through 06:47 local
   time. `CloudActualSeries` therefore splits it rather than drawing a straight line through an
   interval with no stored measurements.
5. A live AviationWeather request proved the reports existed upstream inside that interval:
   - KSQL and KNUQ at 05:55.
   - KSJC at 05:53.
   - KSQL and KNUQ at 06:15.
   - KNUQ and KSQL at 06:35.
6. Desktop's borrowed-observation refresh requests only two hours of METAR history. Once an older
   polling hole falls outside that window, later refreshes cannot repair it even while the upstream
   archive still contains the reports.
7. The checked 24-hour, five-station AviationWeather request returned 164 rows. The service
   documents a 400-entry limit for most endpoints, so this bounded recovery window is comfortably
   within the observed request size at this location.

### Emulator / NWS

1. The inspected device was `emulator-5554`, identified as Google's `sdk_gphone64_x86_64`.
2. The current widget displayed NWS and reproduced the disconnected solid curve.
3. Runtime logs showed the graph received a populated actual series:
   - `actual=138`, then `actual=139` after refresh.
   - Three cloud-reporting stations participated.
   - The graph renderer completed normally.
4. The copied Room database contained 139 distinct cloud-carrying timestamps in the visible window,
   from 03:00 through 14:45. The break is therefore not an empty-series, API-parser, database-write,
   or renderer-crash failure.
5. Gap distribution in that live series was:
   - 2 minutes: 11 intervals.
   - 3 minutes: 11 intervals.
   - 5 minutes: 108 intervals.
   - 10 minutes: 4 intervals.
   - 15 minutes: 2 intervals.
   - 20 minutes: 2 intervals.
6. The shared splitter currently infers cadence from the median interval. The live NWS median is
   five minutes, producing a ten-minute maximum bridge. Normal 15- and 20-minute METAR intervals
   are consequently misclassified as missing-data gaps.
7. At that threshold the 139 valid points are split into five segments, including two singleton
   segments which the renderer cannot draw as lines.

### Existing regression-test gap

The focused shared test run passed:

```text
./gradlew :shared:testShortShared \
  --tests com.weatherwidget.shared.graph.CloudActualSeriesTest \
  --tests com.weatherwidget.shared.actuals.MetarCloudBlenderTest
```

The current uncommitted `station-offset pairs do not shatter the line into dots` test uses a
20-minute median cadence. It covers the desktop-shaped timestamp mix but not the emulator's NWS
mix, where five-minute ASOS timestamps dominate the median. Passing that fixture therefore does not
prove the live NWS curve stays connected.

## Root causes

### A. Shared segmentation mistakes merged-source density for provider cadence

`MetarCloudBlender` emits native timestamps from several nearby stations. NWS contributes frequent
five-minute ASOS rows as candidate timestamps while still preferring official METAR sky reports as
the value anchors. The merged point spacing is therefore denser than the underlying METAR reporting
cadence. Inferring the allowed bridge solely from the median merged spacing makes legitimate
15-20-minute measurement intervals look like gaps.

### B. Desktop cannot heal older borrowed-METAR holes

The frequent observations-only path intentionally fetches two hours, which is suitable for normal
polling. It is insufficient as the only METAR history path for an application that may be stopped,
suspended, restarted, or unable to poll for longer than two hours. Open-Meteo's borrowed actual
curve consequently retains persistent holes even though AviationWeather still serves the missing
reports.

## Proposed implementation

### 1. Correct shared actual-series gap handling

Update `shared/.../graph/CloudActualSeries.kt` so the maximum bridge is:

```text
max(inferred cadence * 2, METAR anchor tolerance)
```

The METAR anchor tolerance is 30 minutes. This gives the live NWS series enough room to connect its
ordinary 15-20-minute intervals while preserving real gap splitting:

1. Five-minute mixed NWS median -> 30-minute maximum bridge, not 10 minutes.
2. Fifteen-minute uniform series -> 30-minute maximum bridge, unchanged.
3. Hourly synthetic/provider series -> two-hour maximum bridge, unchanged.
4. Desktop's real 72-minute METAR hole -> remains disconnected until storage is repaired.

Prefer exposing one shared constant rather than copying a literal if doing so does not create an
undesirable dependency direction. The graph utility must remain deterministic and source-agnostic.

### 2. Add a live-shaped shared regression test

Extend `CloudActualSeriesTest` with a timestamp distribution matching the emulator evidence:

1. A long run dominated by five-minute steps.
2. Ordinary 15- and 20-minute intervals embedded in that run.
3. A separate interval greater than 30 minutes representing a genuine missing-data gap.
4. Assert that the normal mixed-cadence run stays one drawable segment and only the genuine hole
   creates a split.
5. Retain the existing quarter-hour and station-offset tests.

This test belongs in `:shared` and keeps exactly one required duration category.

### 3. Add bounded desktop METAR history recovery

On the desktop full-refresh/startup path for a source borrowing `METAR` actuals:

1. Fetch 24 hours of METAR observations from AviationWeather.
2. Persist the returned rows through the existing desktop observation write path.
3. Keep the frequent observations-only loop at two hours; do not turn every poll into a historical
   download.
4. Preserve API/source identity as `METAR`. Never relabel these observations as Open-Meteo.
5. Continue using the configured site coordinates and existing nearest-station selection.
6. Log one sparse full-refresh summary containing requested hours, returned row count, stored row
   count, and station count.
7. Preserve failure behavior: a failed recovery fetch must leave cached data intact and render a
   real gap rather than fabricate a connection.

The initial implementation is deliberately bounded to 24 hours. Deeper graph-history recovery is
outside this incident's scope and would require chunking because AviationWeather documents a
maximum-result limit.

### 4. Desktop orchestration tests

Add or extend desktop tests to prove:

1. A full Open-Meteo refresh requests the 24-hour borrowed-METAR recovery window.
2. A frequent observations-only refresh still requests two hours.
3. NWS continues using its own observation path and is not silently routed through borrowed METAR.
4. Returned METAR rows remain source-isolated and reach persistence.
5. A failed historical recovery does not delete or replace cached observations.

## Explicitly unchanged

1. Open-Meteo Forecast API values remain forecast/model output; they will not be promoted to actual
   observations.
2. The dashed forecast line remains dashed and hourly.
3. Actual values remain unsmoothed and positioned at their native timestamps.
4. Genuine gaps remain gaps; the renderer will not interpolate across missing measurements.
5. Android's NWS fetching architecture is unchanged by the desktop recovery work.
6. No settings, layout, or default-value changes are required.
7. No new work-cancellation or `ExistingWorkPolicy.REPLACE` path is introduced.

## Database and migration impact

No schema change or migration is required on Android Room or desktop SQLite. The repair uses the
existing observation columns, keys, DAOs, and upsert behavior. Existing missing intervals remain
missing until a successful bounded recovery fetch returns and stores the upstream reports.

## Verification

### Automated

1. Run the focused shared tests:

   ```bash
   ./gradlew :shared:testShortShared \
     --tests com.weatherwidget.shared.graph.CloudActualSeriesTest \
     --tests com.weatherwidget.shared.actuals.MetarCloudBlenderTest
   ```

2. Run focused desktop repository/service tests covering borrowed METAR fetching and persistence.
3. Run all shared and desktop duration lanes affected by the changes.
4. Run the focused Android cloud renderer/handler tests affected by the shared segmentation change.
5. Run `git diff --check`.

### Desktop runtime

1. Build the current desktop distributable.
2. Restart the desktop application so the tested binary is the running binary.
3. Select Open-Meteo and open hourly cloud cover.
4. Confirm the full refresh stores the upstream reports previously absent between 05:35 and 06:47.
5. Query the active database and confirm those METAR timestamps now exist under `api='METAR'`.
6. Capture a screenshot confirming the solid curve is continuous wherever the recovered data
   exists.
7. Confirm a deliberately unsupported longer gap still renders as a gap.

### Android emulator runtime

1. Build and install debug on `emulator-5554` without shutting the emulator down.
2. Keep the widget on NWS hourly cloud-cover view.
3. Confirm logcat still reports a populated `CLOUD_SERIES` actual count.
4. Capture a screenshot confirming 15-20-minute intervals no longer shatter the solid line.
5. Confirm the dashed forecast line, labels, footer icons, NOW marker, navigation, and zoom remain
   intact.

## Acceptance criteria

1. Emulator/NWS renders one solid segment across the live 15-20-minute intervals that currently
   produce false breaks.
2. A gap greater than the 30-minute METAR tolerance still splits the actual curve.
3. Desktop/Open-Meteo repairs recent missed METAR observations during bounded full refresh and does
   not connect across data that remains absent.
4. Open-Meteo forecast values never enter the actual series.
5. Both platforms retain source/site filtering and native timestamps.
6. Focused and applicable duration-bucket tests pass.
7. Fresh desktop and emulator screenshots verify the runtime result.

## Implementation outcome

1. `CloudActualSeries` now applies a source-agnostic 30-minute minimum bridge alongside the
   inferred-cadence threshold. This connects ordinary mixed 15-20-minute METAR intervals while
   continuing to split genuine gaps longer than 30 minutes.
2. The desktop frequent borrowed-METAR poll remains two hours, while a full refresh now performs a
   bounded 24-hour recovery fetch for sources such as Open-Meteo that borrow METAR actuals.
3. Recovery rows retain `METAR` provenance and use the existing observation persistence path.
   Failed recovery requests leave cached observations intact.
4. Shared and desktop regression tests cover the live-shaped cadence, 2-hour versus 24-hour fetch
   selection, persistence, NWS isolation, and recovery failure behavior.
5. No Android Room or desktop SQLite schema change or migration was introduced.

## Verification outcome

1. Focused shared cloud-series and METAR-blending tests passed.
2. Focused desktop borrowed-METAR and backfill integration tests passed.
3. All shared and desktop duration suites passed, together with the affected Android cloud graph
   renderer, label, watermark, and handler tests.
4. `:desktop:createDistributable` and Android `assembleDebug` passed.
5. A fresh desktop full refresh logged a 24-hour borrowed-METAR recovery with 164 returned and
   stored rows from five stations. Database inspection confirmed the formerly missing 05:53,
   05:55, 06:15, and 06:35 reports were persisted with `api='METAR'`.
6. A fresh desktop screenshot showed a continuous solid actual curve through the repaired interval.
7. The debug APK was installed on `emulator-5554`. Runtime logs reported a populated NWS actual
   series, and a fresh screenshot showed the former 15-20-minute false breaks connected.
8. `git diff --check` passed.

## Delivery boundary

Implementation and verification are complete. Do not commit or push unless explicitly requested.
