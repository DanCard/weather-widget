# Daily noon cloud: freshest row wins, not first (2026-08-27)

Samsung SM-F936U1, widget 345, source NWS. Daily today/tomorrow cloud bars disagree with the
hourly cloud graph, alternating between correct and too small.

## Evidence

Logcat, same date and same display source, minutes apart:

```
05:16:10  hourlyRows=226  resolveNoonCloudCoverRatio: date=2026-08-27 ratio=0.5   date=2026-08-28 ratio=0.91
05:17:15  hourlyRows=388  resolveNoonCloudCoverRatio: date=2026-08-27 ratio=0.26  date=2026-08-28 ratio=0.34
```

`hourly_forecasts` holds five NWS noon rows per date, at sub-precision coordinate fragments:

| local hour | lat/lon | fetched | cloudCover |
|---|---|---|---|
| 2026-08-27 12:00 | 37.416,-122.087 | 08-22 18:27 | 26 |
| | 37.424,-122.088 | 08-22 18:40 | 26 |
| | 37.422,-122.073 | 08-24 12:32 | 36 |
| | 37.419,-122.094 | 08-26 14:57 | 59 |
| | 37.417,-122.089 | 08-26 23:18 | **50** |
| 2026-08-28 12:00 | 37.416,-122.087 | 08-22 18:27 | 34 |
| | 37.417,-122.089 | 08-26 23:18 | **91** |

0.26 / 0.34 is the five-day-old row. 0.50 / 0.91 is the current forecast.

## Why the 2026-07-10 fix does not cover it

That fix (`GraphDataLoader.unifyToNearestSite`, plans/260710-daily-cloud-cover-flap-stale-fragment.md)
collapses a proximity-box read to the nearest physical site. It cannot separate these two rows:
`37.416,-122.087` is **inside** the same-site box of `37.417,-122.089` (dlat 0.001, dlon 0.002 vs
`LocationMatch.SAME_SITE_TOLERANCE_DEG` = 0.002, inclusive). They are the same site by definition,
so unification keeps both — deliberately, since dropping same-site fragments is what blanked the
forecast line once. Only `fetchedAt` tells a current forecast from a five-day-old one here.

Two compounding defects:

1. `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent` selects with `.firstOrNull()`. The
   canonical rule for duplicate rows in this table is `HourlyForecastSelector`'s freshest-wins
   (`maxByOrNull { it.fetchedAt }`). Row order follows `ORDER BY dateTime ASC` with arbitrary
   tie-breaking, so the winner changes with the query window — a flap, not a stable wrong value.
2. `DailyViewLogic.mapHourlyForecastsForNoonCloud` was a private hand-rolled copy of
   `HourlyForecastEntity.toHourlyForecast()` that dropped `fetchedAt`, `locationLat` and
   `locationLon`. Every row reached the resolver with `fetchedAt = 0`, so freshest-wins could not
   have worked even if the resolver had asked for it. `toHourlyForecast()`'s own KDoc warns about
   exactly this class of private copy.

## Changes

1. `shared/.../DailyNoonCloudCover.kt` — pick the freshest noon row that carries a cloud value
   (`maxByOrNull { it.fetchedAt }`; stable on ties, so `fetchedAt = 0` gap rows behave as before)
   instead of the first. Fixes Android and desktop, and the daily icon's partly-cloudy floor, which
   reads the same value.
2. `app/.../DailyViewLogic.kt` — delete the private mapping, use `toHourlyForecast()`.
3. Docs on both entry points: the base resolver now disambiguates by freshness; the `...AtSite`
   variant remains preferable where coordinates are available, because it *also* excludes a
   genuinely-different neighbouring marker.

## Testing

| # | Test | Kind | Classes exercised |
|---|---|---|---|
| 1 | `freshestNoonRowWinsAmongSameSiteDuplicates` | unit | DailyNoonCloudCover |
| 2 | `noonSelectionIsIndependentOfRowOrder` — the flap itself: both orderings must agree | unit | DailyNoonCloudCover |
| 3 | `tieOnFetchedAtKeepsFirstRow` — gap rows carry `fetchedAt = 0`; no behaviour change | unit | DailyNoonCloudCover |
| 4 | `olderRowWithCloudBeatsFresherRowWithoutOne` — freshest *with a value*, not freshest-then-null | unit | DailyNoonCloudCover |
| 5 | `prefersLowCloudOnTheFreshestRow` — low-cloud preference composes with freshest-wins | unit | DailyNoonCloudCover |
| 6 | Replace `firstNoonRowWinsWhenDuplicatesExist_callersMustUnifySitesFirst`, which asserted the hazard as intended behaviour | unit | DailyNoonCloudCover |
| 7 | `both render paths resolve fresh noon cloud from a SAME-SITE fragment` | **integration** | WeatherWidgetProvider, WidgetIntentRouter, WidgetRenderer, DailyViewHandler, DailyGraphRenderer, DailyViewLogic, HourlyForecastEntity.toHourlyForecast, GraphDataLoader.unifyToNearestSite, DailyNoonCloudCover |

Test 7 is the one that matters, and it is a new fixture rather than a new harness: the existing
`DailyCloudCoverSiteParityRoboTest` seeds its stale fragment at `37.39,-122.081` — 0.027 degrees
away, i.e. a *different* site, which `unifyToNearestSite` removes. That fixture cannot express this
bug. The added case moves the stale fragment to `37.416,-122.087`, inside the same-site box, and
keeps both legs (refresh cache-first, onUpdate) asserting the fresh value. It also covers change 2
implicitly: the mapping is private, and dropping `fetchedAt` again makes this test fail.

Oracle note: test 7 reads `DailyViewLogic`'s permanent `resolveNoonCloudCoverRatio` debug line via
ShadowLog rather than the bitmap — Robolectric has no font engine, and the ratio feeds a colour
split. It asserts the exact expected value, not just leg-equality, so a both-legs-wrong regression
still fails.

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

The fragments themselves keep accumulating; only the selection is fixed. These rows differ in the
3rd decimal, so `LocationMatch.quantize` (3 dp) considers them distinct keys ~100–200 m apart.
Whether stale same-site fragments should be pruned on write is a separate question.
