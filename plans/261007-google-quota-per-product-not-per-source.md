# Google quota: block and report per product (hourly vs daily), not per source

## Symptom (user, 2026-10-07)
"The google 429 errors has a mistake. There are different quotas for different API calls … On daily
forecast view it says quota used up. It shouldn't. Only quota for hourly forecast view used up."

## Evidence
Emulator `app_logs`. The 429 bodies name the quota:
- `ForecastHoursQueriesPerDay` (`weather.googleapis.com/forecast/hours`), limit 90/day: 03:16, 07:12,
  07:33, 07:45, 08:33 on 10-07.
- `Weather API - Forecast Days Usage per day` — once, 10-06 16:37.

`forecast/days`, `currentConditions` and `history/hours` each have their own per-project daily quota.

## Root cause
1. `GoogleWeatherApi` keeps one `forecastBlockedUntilMs`. A daily 429 on `forecast/hours` blocks the
   **whole** `getForecast` until midnight PT: `forecast/days`, which has quota left, is never asked.
   The daily rows stop updating even though they could.
2. The failure is recorded per **source** (`WidgetFetchStateStore` `source_fail_*`, `SourceQuotaBlocks`,
   desktop `DesktopFetchErrorPresentation`). Every view draws it. The daily view says "Daily quota used"
   about a quota that only feeds the hourly view, and the word "Daily" there means per-day, not the
   daily view.

## Fix
### Shared
- `GoogleQuota.productOf(detail)` maps the 429's `quota_metric` to a product:
  - `forecast/hours` → HOURLY
  - `forecast/days` → DAILY
  - `history/hours` → HISTORY (already best-effort)
  - `currentConditions` → CURRENT
- `GoogleWeatherApi` keeps a block per product (until the next PT midnight).
  - HOURLY refused: still fetch days + current and return them; `hourly` is empty, and the stored
    hours stay (upsert).
  - DAILY refused: still fetch hours + current; `daily` is empty.
  - Both refused: throw `GoogleDailyQuotaException` as today.
  - A blocked product is not requested again before its reset.
- `RawFetch.quotaRefused: Map<ForecastProduct, Long>` (product → reset time). `ForecastProduct
  { HOURLY, DAILY }` is provider-neutral, so views never name Google.
- `SourceQuotaBlocks` keyed by (source, product). A source counts as blocked for staleness/refresh
  triggers only when both forecast products are blocked; one product left means a fetch still has
  something to get.

### Android
- `ForecastFetchCoordinator`:
  - a Google fetch that returned with `quotaRefused` is a **success** for the source: no
    `source_fail_*`, daily rows saved;
  - it records per-product state in `WidgetFetchStateStore` (`source_quota_until_<SOURCE>_<PRODUCT>`,
    persisted);
  - a later success for that product clears it.
- Watermarks:
  - hourly views (temperature, precipitation, cloud) draw "Hourly forecast quota used · resets 12 AM"
    from the HOURLY block;
  - the daily view draws "Daily forecast quota used · resets 12 AM" only from a DAILY block;
  - genuine whole-source failures keep today's banner.
- `SourceErrorDetailsActivity` names the product and quota.

### Desktop
- `DesktopWeatherRepository.persistForecastResult` saves what came back and records per-product
  blocks.
- The popup's hourly and daily views each show only their own product's quota banner.
- `DesktopFetchErrorPresentation` titles it "GOOGLE HOURLY FORECAST QUOTA USED" /
  "… DAILY FORECAST …".

## Tests
- Shared:
  - `productOf` for the four metrics (the recorded 10-07 hours body and the 10-06 days body);
  - `GoogleWeatherApi` with hours 429: days + current returned, `quotaRefused[HOURLY]` set, no hour
    request on the next fetch, days still requested;
  - days 429 mirrors it;
  - both → exception.
- Android:
  - the coordinator saves daily rows and records no source failure on an hours-only 429;
  - the daily renderer gets no watermark from an HOURLY block, and the hourly renderer gets the
    hourly text.
- Desktop: presentation titles per product; the daily view has no banner on an hours-only block.

## Verify
The emulator is out of hours quota now (until 12 AM PT). After install, the daily view shows fresh
Google daily rows with no pill, and the hourly view shows "Hourly forecast quota used · resets 12 AM".
The desktop shows the same split.
