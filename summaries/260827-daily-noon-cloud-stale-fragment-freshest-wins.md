# Daily noon cloud flaps between a fresh and a 5-day-old fragment (2026-08-27)

## Problem

Samsung Fold (SM-F936U1, widget 345, source NWS): the daily view's today/tomorrow cloud bars
disagree with the hourly cloud graph, alternating between a correct value and a too-small one.

Logcat shows the flap directly, same date and same display source:

```
05:16:10  hourlyRows=226  resolveNoonCloudCoverRatio: date=2026-08-27 ratio=0.5   date=2026-08-28 ratio=0.91
05:17:15  hourlyRows=388  resolveNoonCloudCoverRatio: date=2026-08-27 ratio=0.26  date=2026-08-28 ratio=0.34
```

The database explains both values. `hourly_forecasts` holds FIVE NWS noon rows per date, at
sub-precision coordinate fragments of the same physical site:

| local hour | lat/lon | fetched | cloudCover |
|---|---|---|---|
| 2026-08-27 12:00 | 37.416,-122.087 | 08-22 18:27 | 26 |
| | 37.424,-122.088 | 08-22 18:40 | 26 |
| | 37.422,-122.073 | 08-24 12:32 | 36 |
| | 37.419,-122.094 | 08-26 14:57 | 59 |
| | 37.417,-122.089 | 08-26 23:18 | **50** |
| 2026-08-28 12:00 | 37.416,-122.087 | 08-22 18:27 | 34 |
| | 37.417,-122.089 | 08-26 23:18 | **91** |

0.26/0.34 is the FIVE-DAY-OLD row; 0.50/0.91 is the current forecast.

## Root cause

This is the [[daily-noon-cloud-refresh-path-unmerged]] family (2026-07-10), but the previous fix's
premise no longer holds. That fix routed raw proximity-box reads through
`GraphDataLoader.unifyToNearestSite`, on the assumption that collapsing to the nearest physical
site removes the stale row. It cannot here: `37.416,-122.087` is INSIDE the same-site box of
`37.417,-122.089` (dlat 0.001, dlon 0.002, tolerance `LocationMatch.SAME_SITE_TOLERANCE_DEG` =
0.002, inclusive). Both rows are legitimately the same site, so unification keeps both by design —
dropping same-site fragments is what blanked the forecast line once. Only `fetchedAt` separates
them.

Two compounding defects:

1. `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent` selects with `.firstOrNull()`. The
   canonical rule for duplicate hourly rows is `HourlyForecastSelector`'s freshest-wins
   (`maxByOrNull { it.fetchedAt }`). Row order comes from `ORDER BY dateTime ASC` with arbitrary
   tie-breaking, so which duplicate wins depends on the query window — hence a per-render-path flap
   rather than a stable wrong value.
2. `DailyViewLogic.mapHourlyForecastsForNoonCloud` is a private hand-rolled copy of
   `HourlyForecastEntity.toHourlyForecast()` that DROPS `fetchedAt`, `locationLat` and
   `locationLon`. Even a freshest-wins resolver would have seen `fetchedAt = 0` on every row.
   `toHourlyForecast()`'s own doc warns about exactly this: "a private per-loader copy of this
   conversion is exactly the kind of drift that let a 13-day-old coordinate fragment win in one
   loader and lose in the other".

## What changed

- `shared/.../DailyNoonCloudCover.kt`: the noon row is now the FRESHEST noon row carrying a cloud
  value (`maxByOrNull { it.fetchedAt }`, stable on ties), not the first. Applies to both platforms
  and to the icon's partly-cloudy floor, which reads the same value.
- `app/.../DailyViewLogic.kt`: `mapHourlyForecastsForNoonCloud` deleted; the call sites use the
  canonical `toHourlyForecast()`, which carries `fetchedAt` and the coordinates.
- `DailyNoonCloudCoverTest.firstNoonRowWinsWhenDuplicatesExist_callersMustUnifySitesFirst` asserted
  the old hazard as intended behaviour. Replaced with freshest-wins coverage plus a tie case.
- New `DailyNoonCloudStaleFragmentRegressionTest` (app, Robolectric) replays the device rows
  through the real `DailyViewLogic` mapping.

## Verification

Shared unit tests: `DailyNoonCloudCoverTest` 15 green, including the five new freshest-wins cases.
Full `:shared:testByDurationShared` green. App: `*Cloud*`/`*DailyView*`/`*DailyForecast*` unit and
Robolectric tests green, including `OpenMeteoLowCloudViewParityIntegrationTest`.

The new integration case was proven to fail against the pre-fix sources, with the device's own
number:

```
DailyCloudCoverSiteParityRoboTest > both paths resolve the fresh noon cloud when the stale
fragment is the same site FAILED
  java.lang.AssertionError: refresh path must use the fresh site's noon cloud expected:<0.65> but was:<0.26>
```

The pre-existing far-fragment test still PASSED against those same pre-fix sources, which is the
point: its fixture cannot reach this bug.

On the Fold after `installDebug`, across both render paths (`onUpdate` and the
`refresh_action_cache_first` leg that used to produce the wrong value), every date now resolves a
single ratio:

```
2 2026-08-27 NWS 0.5     2 2026-08-29 NWS 0.23    2 2026-08-31 NWS 0.3
2 2026-08-28 NWS 0.91    2 2026-08-30 NWS 0.27    2 2026-09-01 NWS 0.29
```

Before the fix the same query returned `16x 0.26` against `3x 0.5` for 2026-08-27 and
`16x 0.34` against `3x 0.91` for 2026-08-28. Home-screen screenshot confirms Friday's bar now
renders mostly grey, matching 91% cloud.

Harness note: adding a second test method to `DailyCloudCoverSiteParityRoboTest` exposed two
process-wide statics keyed by widget id that outlive a test — `WidgetRenderer`'s
`fullyPaintedDailyWidgetIds` (a repeat id makes the refresh leg return `state=skipped_ui_only`
without pushing RemoteViews) and `WeatherWidgetProvider`'s `lastUpdateByWidgetId` startup debounce
(Robolectric restarts `elapsedRealtime` per test, so a carried-over timestamp reads as "500ms ago").
The class now allocates a fresh widget id per test method and calls
`WidgetRenderer.resetPaintTrackingForTest()` in `@Before`.

## Follow-ups

- The five fragments themselves are still accumulating; only the selection is fixed. Write-side
  quantization already exists (`LocationMatch.quantize`, 3 dp) yet these rows differ in the 3rd
  decimal, i.e. they are genuinely distinct keys ~100-200 m apart. Worth a separate look at whether
  stale same-site fragments should be pruned on write.
