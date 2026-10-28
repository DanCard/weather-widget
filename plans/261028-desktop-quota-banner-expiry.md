# Desktop quota banner expiry and reset timing

User, 2026-10-28: "desktop: Has quota banner up, since yesterday. There is no quota issue today."

## Evidence
- Database inspection (`~/.local/share/weather-widget/weather.db`):
  - At **2026-10-27 23:47:49** (`id=355353`), Google hit an hourly quota limit with reset at midnight:
    `tag=PRODUCT_QUOTA`: `source=GOOGLE_WEATHER product=HOURLY until=1793170800000` (2026-10-28 00:00:00 Pacific).
  - At **2026-10-27 23:59:56** (`id=355580`), `DesktopUiApplication` evaluated status 4 seconds before midnight, saw the quota was active (`statusNow < untilMs`), and transitioned the banner to:
    `tag=CURR_TEMP_BANNER`: `state=error (was none)`.
  - At **00:00:00**, the quota reset.
  - The banner stayed up until **00:09:57** (`id=355599`), when the 10-minute fallback reload loop (`UI_FALLBACK_RELOAD_MS`) finally fired, bumped `dataUpdateCount`, and logged:
    `tag=CURR_TEMP_BANNER`: `state=none (was error)`.
  - During that 10-minute window past midnight (and potentially indefinitely if the fallback loop wasn't ticking), the UI showed a stale quota banner claiming the quota was exhausted.

## Root Causes
1. **No Quota Expiry Scheduler in Desktop UI**: In `DesktopUiApplication.kt`, short-window rate-limits have an expiry timer (`rateLimitExpiryJob` with `delay(remaining)`) and network warmup has `graceJob`, but **neither product quotas nor source-level quotas had an expiry timer**. When midnight arrived, nothing was scheduled to re-evaluate `updateStatus()` or take down the banner.
2. **Source Failure Quota doesn't check reset time**: In `DesktopUiApplication.kt:705`, `kind.quota` was evaluated without checking `statusNow < GoogleQuota.nextResetMs(it.timestamp)`. An old daily quota error from a prior day could be treated as an active failure.
3. **Popup Show doesn't re-evaluate status**: When the popup is opened (`requestShowPopup()`), `showRequestId` increments, but it was not in the keys of `LaunchedEffect(repository, config, forecast, dataUpdateCount)`, so bringing the popup to the front did not re-evaluate banner status against current wall-clock time.

## Plan
1. **Add Quota Expiry Job in `DesktopUiApplication.kt`**:
   - Introduce `quotaExpiryJob: Job? = null` inside `LaunchedEffect(repository, config, forecast, dataUpdateCount, showRequestId)`.
   - Add helper `expireQuotaBannerAfter(resetAtMs: Long)` that calculates `remaining = resetAtMs - statusNow` and delays until `resetAtMs` to call `updateStatus()` and `logBannerTransition()`.
   - When `productQuota != null`, schedule `expireQuotaBannerAfter(productQuota.second.untilMs)`.
   - For source failures with `kind.quota`, gate on `statusNow < GoogleQuota.nextResetMs(status.timestamp)` and schedule `expireQuotaBannerAfter(GoogleQuota.nextResetMs(status.timestamp))`.
2. **Re-evaluate on Popup Open**:
   - Add `showRequestId` to the keys of `LaunchedEffect(repository, config, forecast, dataUpdateCount, showRequestId)`.
3. **Automated Unit Tests**:
   - Add test cases in `DesktopProductQuotaTest` and/or `DesktopFetchErrorPresentationTest` validating that expired product quotas and source quotas are not treated as active, and that reset timestamps correctly determine banner visibility.

## Verification
- Added `DesktopStatusBannerIntegrationTest`:
  - `product quota block banners immediately and clears automatically at untilMs`
  - `source-wide daily quota from yesterday is not bannered after midnight Pacific`
  - `source-wide daily quota active today expires at midnight Pacific`
  - `rate limit banner appears and clears after RateLimitNotice SHOW_MS`
- Added unit tests in `DesktopProductQuotaTest` and `DesktopFetchErrorPresentationTest`.
- Ran `./gradlew :desktop:test --rerun --tests "com.weatherwidget.desktop.DesktopStatusBannerIntegrationTest"` (all passed).
- Ran `./scripts/unit-tests.sh` (6,231 tests passed).
- Built desktop distributable with `./gradlew :desktop:createDistributable`.
- Verified live running desktop application logs `CURR_TEMP_BANNER` `state=none`.
