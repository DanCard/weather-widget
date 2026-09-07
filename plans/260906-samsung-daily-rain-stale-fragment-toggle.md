# Samsung daily rain chance changes after API toggle

**Date:** 2026-09-06

**Status:** PLANNED — evidence collected; implementation not started

**Device:** Samsung SM-F936U1 (`RFCT71FR9NT`), Android 16

**Widget:** 345, daily graph, NWS, location `37.416805,-122.088951`

## Outcome

Make the daily daytime/nighttime rain labels use the freshest NWS hourly row for each hour at the
display site on both Android and desktop. Toggling away from and back to NWS must not resurrect a
stale probability from another cached coordinate fragment.

This change must preserve the existing rain-window semantics:

1. Daytime is 8 AM–8 PM.
2. Nighttime is 8 PM–8 AM the following morning.
3. The maximum probability inside each window is displayed when it clears the existing
   distance-based threshold.
4. NWS period probabilities remain fallbacks when no usable hourly probability exists.
5. Past days continue to replay the values frozen in `daily_history`.

For the reproduced forecast, the correct output is no Tuesday daytime label (1% is below the
threshold) and an **11%** interstitial label for Tuesday night into Wednesday morning.

## User-visible symptom

The Samsung daily forecast initially showed 21% for Tuesday and 21% for Tuesday night. It later
settled to no Tuesday daytime label and 11% between Tuesday and Wednesday. Toggling the displayed API
away from NWS and back to NWS reproducibly restored the incorrect 21%/21% labels.

The temperatures did not become stale at the same time: Tuesday remained 84 degrees and Wednesday
remained 92 degrees. This is a rain-label row-selection problem, not an entirely stale widget image.

## Runtime evidence

### Official NWS values

The active endpoint is:

```text
https://api.weather.gov/gridpoints/MTR/93,87/forecast
https://api.weather.gov/gridpoints/MTR/93,87/forecast/hourly
```

The live NWS daily periods returned:

| Period | Probability | Temperature | Forecast |
|---|---:|---:|---|
| Tuesday, Sep 8 | 1% | 84 F | Mostly Sunny |
| Tuesday Night | 11% | 68 F | Mostly Cloudy |
| Wednesday, Sep 9 | 11% | 92 F | Mostly Sunny |

The hourly endpoint explains the Tuesday-night label. From Tuesday 8 PM through Wednesday 4 AM,
the probability is 1–4%; it rises to 11% at 5–8 AM Wednesday. Therefore 11% is the correct maximum
for the app's 8 PM–8 AM night window.

### Samsung database

The freshest hourly series at `37.417,-122.089` agrees with NWS:

| Cached coordinate | Tuesday day max | Tuesday night max | Wednesday day max | Freshest fetch (UTC) |
|---|---:|---:|---:|---|
| `37.417,-122.089` | **1%** | **11%** | **11%** | 2026-09-07 03:54:56 |

Older same-site coordinate fragments still contain the superseded forecast:

| Cached coordinate | Tuesday day max | Tuesday night max | Freshest fetch (UTC) |
|---|---:|---:|---|
| `37.419,-122.087` | 21% | 21% | 2026-09-06 01:32:22 |
| `37.415,-122.087` | 21% | 21% | 2026-09-06 01:25:55 |
| `37.421,-122.087` | 21% | 21% | 2026-09-06 02:14:16 |
| `37.422,-122.086` | 21% | 21% | 2026-09-06 01:38:26 |

Those stale rows include 14% on Tuesday morning, 21% Tuesday evening/night, and 15% on Wednesday
night. The pre-toggle screenshot contained exactly those 14%/21%/15% values, establishing that the
labels came from stale fragments rather than the current NWS series.

### Toggle render path

The reproduction produced this log fingerprint:

```text
DailyGraphRenderer: ... widget=345 source=NWS ... hourlyRows=1134
DailyInteractionRenderer: widget=345 action=TOGGLE_API metadata=source=NWS ... hourlyRows=1134
TOGGLE_API_RENDER_OK: widget=345 from=TOMORROW_IO
```

The post-toggle Samsung screenshot showed 21% above Tuesday, 21% between Tuesday and Wednesday,
14% before Tuesday, and 15% after Wednesday. A preceding render from the current rows showed only
the correct 11% Tuesday-night label.

## Root cause

`hourly_forecasts` is keyed by timestamp, source, and coordinates. Small changes in phone location
therefore leave multiple legitimate cached NWS forecast series near the current location. Older
series stop receiving updates but remain inside the DAO proximity box until retention removes them.

`DailyInteractionRenderer` calls `GraphDataLoader.unifyToNearestSite`, but that helper deliberately
keeps coordinate fragments inside `LocationMatch.sameSite`. It also preserves duplicate timestamps;
this is necessary for other consumers that select the freshest row later. Consequently, the API
toggle passes 1,134 NWS/Generic rows to `DailyViewLogic`, not one resolved NWS row per hour.

The live rain-label chain is:

```text
DailyInteractionRenderer (TOGGLE_API)
  -> DailyViewHandler / DailyGraphRenderer
  -> DailyViewLogic.prepareGraphDayInputs
  -> DailyForecastIconResolver.resolveDailyLabelPrecip
  -> DailyRainLabels.resolveDailyLabelPrecip
  -> DailyRainLabels.calculateDayNightPrecipProbabilities
  -> maxOrNull() over every matching NWS row in the window
```

That chain never invokes `HourlyForecastSelector.selectForecastsByTime`. Because the final reducer
is a maximum, a single old 21% row wins over the current 1%/11% rows.

The shared module already contains the intended defense:

1. `DailyRainLabels.selectSiteHourly` calls `HourlyForecastSelector.selectForecastsByTime`, selecting
   one freshest display-source row per timestamp around a supplied center.
2. `DailyRainLabels.resolveLiveDayNightChanceAtSite` applies that selection before taking window
   maxima.
3. `DailyRainChanceSiteSelectionTest` explicitly demonstrates that raw proximity rows poison a
   maximum and that site-resolved rows do not.

The freeze/repair path uses the site-aware method, but the live Android and desktop daily-label path
still calls the unsited method. The code comment claiming every display path has already selected a
site is therefore too strong: nearest-site unification and freshest-per-hour selection are separate
operations.

## Proposed implementation

### 1. Make the shared live-label entry point site-aware

In `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`:

1. Add a site-aware daily-label entry point (preferred name:
   `resolveDailyLabelPrecipAtSite`) accepting `centerLat` and `centerLon`.
2. Preserve the current past-day branch exactly: use stored frozen chances first, then period fields.
3. For today/future dates, delegate to the existing `resolveLiveDayNightChanceAtSite` so
   `HourlyForecastSelector` chooses the freshest row per timestamp before window maxima are taken.
4. Keep the existing unsited primitives for already-normalized callers and focused calculation tests;
   document that raw DAO/proximity lists must use the site-aware entry point.
5. Keep Generic-gap fallback behavior unchanged.

Do not change `LocationMatch.sameSite`, delete cached fragments, change the DAO proximity window, or
change `GraphDataLoader.unifyToNearestSite`. Those are broader policies and are not required to fix
this selection defect.

### 2. Android: carry the configured render center to rain resolution

Relevant files:

- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyTextRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`

Implementation:

1. Put the already-resolved widget latitude/longitude into `DailyRenderContext`, or thread the values
   explicitly through both graph and text preparation entry points.
2. Extend `DailyViewLogic.prepareGraphDayInputs` and `prepareTextDays` with the render center.
3. Extend `DailyForecastIconResolver.resolveDailyLabelPrecip` with that center and delegate to the
   shared site-aware entry point.
4. Use the same resolved values for label text, daily icon rain gating, click routing, and night-rain
   touch zones, since all are derived from the returned `ResolvedDailyPrecip`.
5. Do not select coordinates independently from an arbitrary hourly row. The configured render
   center already used by the widget is the stable selection center.

This fixes `TOGGLE_API`, ordinary `onUpdate`, cache-first refresh, graph mode, and text mode through
one downstream resolver rather than adding a toggle-specific special case.

### 3. Desktop parity

In `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt`:

1. Pass `config.lat` and `config.lon` into `buildDay`.
2. Replace the unsited `DailyRainLabels.resolveDailyLabelPrecip` call with the shared site-aware entry
   point.
3. Preserve existing label formatting, thresholds, icons, and past-day replay.

No desktop persistence migration or UI/layout change is needed.

### 4. Diagnostics

Add one sparse DEBUG breadcrumb per daily render only if existing logs cannot prove the resolved
values. It should include widget/source/date, raw candidate count, selected count, center, and final
day/night probability. Do not log per-row or per-hour details at DEBUG; those are high-frequency and
must remain VERBOSE to avoid growing `app_logs`.

## Regression tests

Every new test class must declare exactly one duration category.

### Shared unit tests

Extend `DailyRainChanceSiteSelectionTest` with the exact Samsung scenario:

1. Current center/site rows: Tuesday day 1%, Tuesday night 11%, newest `fetchedAt`.
2. Same-site stale fragments: Tuesday day/night 21%, older `fetchedAt`.
3. Assert the raw unsited reducer demonstrates 21%/21%.
4. Assert `resolveDailyLabelPrecipAtSite` returns 1%/11% regardless of input row order.
5. Assert same-coordinate revisions still use newest `fetchedAt`.
6. Assert past-day stored chance behavior is unchanged.
7. Assert Generic-gap fallback remains available when the display source has no rows.

Run the focused shared Short bucket/test and category validation.

### Android tests

1. Extend `DailyForecastIconResolverTest` for site-aware delegation and the 1%/11% versus 21%/21%
   fixture.
2. Add or extend a Robolectric integration test that seeds current and stale same-site NWS fragments,
   renders widget 345 through the ordinary daily path and the `TOGGLE_API` interaction path, and
   asserts both resolve no Tuesday day label and an 11% Tuesday-night label.
3. Assert the exact expected value in each path, not only equality between paths, so both paths being
   wrong cannot pass.
4. Exercise both graph and text daily preparation if the plumbing is separate.
5. Use fresh widget IDs/reset process-wide render tracking between test cases, following
   `DailyCloudCoverSiteParityRoboTest`.

Prefer Robolectric because this test crosses real state, DAO, selector, interaction, and renderer
components but does not require a real Canvas pixel oracle. Instrumentation remains for final device
proof.

### Desktop tests

Extend the desktop daily-model test with the same mixed-fragment fixture and assert the resulting
`DesktopDailyDay` uses 1%/11%, independent of row order.

## Verification

### Automated

Run focused tests first, then the relevant duration buckets and build:

```bash
./gradlew :shared:testShortShared
./gradlew :app:testShortDebugUnitTest
./gradlew :app:testLongDebugUnitTest
./gradlew :desktop:testShortDesktop
./gradlew assembleDebug
./gradlew :desktop:createDistributable
```

If the touched test classes belong to different measured duration buckets, use their actual category
tasks instead of forcing the commands above. Run the repository's duration-category validation and
`git diff --check` before handoff.

### Samsung runtime proof

1. Record widget 345's current source/view/date offset/zoom before installation.
2. Install the debug APK on `RFCT71FR9NT` without removing widget instances.
3. Restore daily graph mode and NWS if installation changes presentation state.
4. Capture the baseline screenshot and relevant daily-render logs.
5. Toggle NWS -> another visible provider -> NWS several times.
6. After every NWS render, verify:
   - Tuesday daytime 1% remains suppressed;
   - Tuesday night/Wednesday morning remains 11%;
   - 21%, 14%, and 15% stale-fragment labels do not return;
   - Tuesday/Wednesday temperatures and icons remain unchanged except for legitimate fresh data.
7. Query the device database after the test to show the stale 21% rows still exist. Success must come
   from deterministic selection, not incidental cleanup or a new fetch overwriting evidence.
8. Capture a final Samsung screenshot and narrow log excerpt showing `TOGGLE_API`, NWS, and the
   resolved 1%/11% inputs.
9. Restore the user's original widget presentation state.

### Desktop runtime check

Open the desktop daily view at the same configured location/source if practical and confirm it agrees
with the site-resolved values. If live desktop proof is not performed, report that boundary plainly;
the desktop model regression test and build do not substitute for a screenshot.

## Acceptance criteria

1. Android and desktop resolve live daily rain from one freshest hourly row per timestamp at the
   configured display site.
2. The Samsung NWS daily view shows no Tuesday daytime label and 11% for Tuesday night with the
   captured forecast dataset.
3. Toggling the API cannot restore 21%/21% from stale coordinate fragments.
4. Graph mode, text mode, icon selection, click routing, and night-rain touch zones use the same
   resolved probabilities.
5. Past-day frozen chances, Generic-gap fallback, thresholds, and 8 AM/8 PM boundaries remain
   unchanged.
6. Focused shared, Android, desktop, and Robolectric integration tests pass; Android and desktop
   builds succeed.
7. Samsung verification includes screenshot, logs, database evidence, and repeated API toggles.

## Out of scope

1. Deleting or migrating existing coordinate fragments.
2. Changing forecast-cache keys, retention, or location quantization.
3. Changing the daily/night rain windows or the probability threshold/font scaling.
4. Treating an 11% probability as a rain-condition icon when existing icon thresholds suppress it.
5. Refactoring unrelated hourly graph or current-temperature selection paths.
6. Committing or pushing without a separate explicit request.

## Implementation stop conditions

Stop and revise this plan before continuing if:

1. The API-toggle path does not reproduce 21%/21% with the captured stale rows.
2. The proposed site-aware selector chooses anything other than the freshest current NWS values.
3. Passing the configured center changes unrelated hourly graph continuity or Generic-gap behavior.
4. Android and desktop require different probability-selection rules.
5. Runtime verification cannot distinguish a selector repair from a coincidental network refresh or
   database cleanup.

## Implementation status — 2026-09-06

Implemented the site-aware resolver and threaded the configured render center through Android graph
and text preparation and the desktop daily model. Android now maps hourly entities through the full
shared conversion so `fetchedAt` and storage coordinates reach the selector.

Verified:

1. The exact current 1%/11% versus stale 21%/21% Samsung fixture passes in shared logic, the Android
   adapter, and the desktop model.
2. A Robolectric database/renderer regression passes through both the ordinary graph render and the
   real `TOGGLE_API` interaction in text mode; both resolve 1%/11%, not 21%/21%.
3. `:shared:testShortShared`, `:app:testShortDebugUnitTest`, `:app:testLongDebugUnitTest`,
   `:desktop:testShortDesktop`, and `assembleDebug` pass.
4. `:desktop:createDistributable` passes.
5. `git diff --check` passes.

Runtime evidence completed after the Samsung reconnected:

1. Verified `RFCT71FR9NT` is Samsung `SM-F936U1`, installed the debug APK with `-r`, and captured the
   active inner 1812x2176 display.
2. Cycled widget 345 through NWS -> Open-Meteo -> Silurian -> Tomorrow.io -> NWS via the real header
   interaction. The final NWS log resolved Tuesday as `day=1 night=11`; the screenshot shows no
   Tuesday daytime label and an 11% overnight label.
3. The post-toggle database copy still contains the old 21% rows at the stale 37.415/-122.087,
   37.419/-122.087, 37.421/-122.087, and 37.422/-122.086 fragments. The repaired display therefore
   comes from deterministic selection, not cleanup or overwrite.
4. Restored widget 345 to its original DAILY/NWS/date-offset-0/WIDE presentation.
5. Desktop live screenshot proof was not performed; model regression and distributable build are
   complete.
