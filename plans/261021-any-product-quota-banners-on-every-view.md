# Any product quota block banners on every view

User, 2026-10-21: "There were recent attempts to get updated google forecast data and quota exceeded
error occurred. I didn't see an error message. There should be a glaring error message when quota
is exceeded."

## Evidence

Desktop `app_logs`, 2026-10-21:

```
20:07:39 REFRESH_ENTER        reason=user_refresh:history parts=BOTH horizon=LONG source=GOOGLE_WEATHER
20:07:39 GOOGLE_REQUEST       endpoint=forecast/hours status=429
20:07:39 GOOGLE_REQUEST       endpoint=forecast/days  status=200
20:07:41 PRODUCT_QUOTA        source=GOOGLE_WEATHER product=HOURLY until=1792652400000 ("…Forecast Hours Usage per day")
20:07:46 REFRESH              source=GOOGLE_WEATHER hourly=0 daily=10
20:07:46 CURRENT_TEMP_STATUS  source=GOOGLE_WEATHER ok=true
20:07:46 REFRESH_TOAST        origin=history done=Refused refused=HOURLY_FORECAST
```

Samsung (RFCT70G352Y) logcat: `FETCH_PRODUCT_QUOTA source=GOOGLE_WEATHER product=HOURLY` at 19:43 and
20:08 too.

## Root cause

The refusal was recorded correctly. It was hidden **by rule**: a product quota block shows only
on the view that draws that product (user, 2026-10-07):

- `:shared` `QuotaNotice.productOf(view)` → desktop `productQuotaBanner` reads only the viewed
  product's block.
- Android `WidgetStateManager.viewWatermark(source, product)`: "An hourly-only quota block leaves
  the daily view clean."

`forecast/days` still answered, so the source never errored (`CURRENT_TEMP_STATUS ok=true`) and the
daily view had nothing to show. The only notice was the Forecast History toast
("Refreshed Google · Hourly forecast quota used · resets …"), which lasts a few seconds.

## Fix

Overturn the 2026-10-07 rule: **every view shows any active quota block for the source.**

1. `:shared` `QuotaNotice`: replace `productOf(view)` with `bannerProduct(view, blocked)`, the
   single rule both platforms call. The viewed product's own block wins; failing that, the other
   product's block; failing that, null. Its wording already names the product ("Hourly forecast
   quota used · resets 12:00 AM"), so a daily view that banners an hourly block reads correctly.
2. Desktop `productQuotaBanner` and Android `viewWatermark` both ask it, and the error code is
   the chosen product's (`GoogleQuota.errorCodeFor`).
3. Order is unchanged: a source-wide failure first, then the product quota, then a per-minute
   rate limit.
4. Update CLAUDE.md's "Every quota refusal is bannered at once, on every view" paragraph and the
   doc comments that cite the 2026-10-07 decision.
5. **Same staging as every failure banner**: full, then one tiny line at 8 s, then 50 %
   transparent at 24 s (shared `FailureBannerStage` / `FailureBannerLayout`). The user guessed 16 s,
   then said either is fine, so 24 s stays and no other banner changes.

## Tests

- `QuotaNoticeTest`: covers every combination of view × {none, hourly, daily, both} blocked. With
  only hourly blocked, the daily view gets HOURLY. This fails on the old code.
- Android Robolectric (`RateLimitBannerRoboTest` neighbourhood): an hourly-only block paints the
  failure pill on the daily view.
- Desktop: a `productQuotaBanner`-level test if reachable; otherwise the shared test covers the rule.

## Verify

Google's hourly quota is still blocked until 00:00 PT tonight, so the live state reproduces it:
restart the desktop app, then open the daily view on Google, which should show the
"GOOGLE UPDATES PAUSED" banner. Install on the Samsung and screenshot the daily widget.

## Outcome (2026-10-21)

- `:shared` `QuotaNotice.bannerBlock(viewed, blockOf)`. Desktop `productQuotaBanner` and Android
  `WidgetStateManager.viewWatermark` both call it.
- Tests pass: `QuotaNoticeTest`, `ProductQuotaWatermarkRoboTest` (the daily view now banners an
  hourly-only block, which fails on the old code), `DesktopProductQuotaTest` (every `ViewMode` over
  `DesktopQuotaState`), `RateLimitBannerRoboTest`, `GoogleWeatherFetchIntegrationTest`,
  `DesktopFetchErrorPresentationTest`, `CrossPlatformDuplicateNamesTest`.
- Live, 20:24 PDT, while Google's hourly block was active until 00:00 PT: the Samsung's daily widget
  and the desktop popup's daily view both show "Hourly forecast quota used · resets 12 AM". The
  desktop logged `CURR_TEMP_BANNER state=error`. Because the refusal was 17 minutes old, the pill
  was already at the faded stage.
