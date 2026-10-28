# Desktop Quota Banner Expiry & Reset Lifecycle

## User Prompts

1. "desktop: Has quota banner up, since yesterday. There is no quota issue today."
2. "write above to plans/ dir"
3. "approved"
4. "Should there be an integrated test for this?"
5. "yes"
6. "Should this code live in shared and be shared with android?"
7. "write a session summary to summaries/ dir"

## Problem & Root Cause

1. **Symptom:**
   The desktop UI displayed the error banner (`GOOGLE WEATHER UPDATES PAUSED / Hourly forecast quota used · resets 12 AM`) well past the midnight reset time even though the quota was valid and active for the new day.

2. **Evidence & Root Cause:**
   - In `~/.local/share/weather-widget/weather.db` on 2026-10-27 at 23:47:49, a Google Weather hourly quota refusal was recorded with `until=1793170800000` (midnight Pacific, 2026-10-28 00:00:00).
   - At 23:59:56, `DesktopUiApplication` evaluated status, saw the quota was active (4 seconds before midnight), and raised the banner (`CURR_TEMP_BANNER` `state=error`).
   - At 00:00:00, the quota reset. However, because no foreground refresh occurred at midnight, no IPC event updated the UI.
   - Unlike per-minute rate limits (`rateLimitExpiryJob` with `delay(remaining)`) and network warmup (`graceJob`), **neither product quotas nor source-level quotas had an expiry timer scheduled**. The UI stayed in its error state until the fallback reload loop (`UI_FALLBACK_RELOAD_MS = 10 * 60 * 1000L`) fired at 00:09:57.
   - In addition, source-level daily failure checks did not verify `statusNow < GoogleQuota.nextResetMs(it.timestamp)`, and window show requests did not re-evaluate banner state against current wall-clock time.

## Changes

1. **Desktop Status Banner Controller (`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopStatusBannerController.kt`)**:
   - Extracted banner state management and transitions into `DesktopStatusBannerController`.
   - Introduced `quotaExpiryJob` with `expireQuotaBannerAfter(resetAtMs: Long)` scheduling a coroutine delay until `untilMs` or midnight Pacific, automatically calling `updateStatus()` to clear the banner without user intervention.
   - Gated source-level daily quota failures on `statusNow < GoogleQuota.nextResetMs(it.timestamp)`.
2. **Desktop UI Integration (`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt`)**:
   - Delegated status evaluation and transitions to `DesktopStatusBannerController`.
   - Added `showRequestId` to the `LaunchedEffect` trigger keys so bringing the window to the foreground immediately re-evaluates the banner against current wall-clock time.
3. **Database Tie-Breaker (`shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt`)**:
   - Added `id DESC` secondary tie-breaker to `getLatestCurrentTempStatus` (`ORDER BY timestamp DESC, id DESC LIMIT 1`) to eliminate SQLite order non-determinism when entries share a timestamp.
4. **Desktop Unit & Integrated Tests**:
   - Added `DesktopStatusBannerIntegrationTest.kt` verifying four end-to-end scenarios over real SQLite and coroutines:
     - Product quota block banners immediately and clears automatically at `untilMs`.
     - Source-wide daily quota from yesterday is not bannered after midnight Pacific.
     - Source-wide daily quota active today expires automatically at midnight Pacific.
     - Rate limit banner appears and clears after `RateLimitNotice.SHOW_MS` (60s).
   - Added `an expired product quota block returns null on all views` to `DesktopProductQuotaTest.kt`.
   - Added `a daily quota failure expires once current time passes resetAtMs` to `DesktopFetchErrorPresentationTest.kt`.
5. **Time-of-Day Flake Fixes**:
   - Fixed midnight boundary assumption in `DesktopDailyBlendSiteTest.kt`.
   - Fixed `GoogleWeatherFetchIntegrationTest.kt` where today's morning minimum is stored unrounded (`isToday = true`) rather than integer-rounded (`isToday = false`).
6. **Plan Documentation (`plans/261028-desktop-quota-banner-expiry.md`)**:
   - Recorded evidence, root cause analysis, architecture decisions, and test verification.

## Verification

- **Integrated Tests**: `./gradlew :desktop:test --rerun --tests "com.weatherwidget.desktop.DesktopStatusBannerIntegrationTest"` passed all 4 test cases.
- **Unit Tests**: `./scripts/unit-tests.sh` passed all 6,231 tests across `:shared`, `:desktop`, and `:app`.
- **Distributable Build**: `./gradlew :desktop:createDistributable` passed.
- **Live Desktop Runtime**: Verified running desktop application logs `CURR_TEMP_BANNER` `state=none`.
