# Limit user refreshes to max every 10 minutes

## Problem
Currently, a user tapping Refresh on the widget is evaluated against the 4-hour background cadence (`isDataStale`). If data is younger than 4 hours (e.g. 3h 55m), the tap is silently skipped as `fresh_data` / `UP_TO_DATE`. Conversely, if the check were bypassed unconditionally, repeated taps could spam weather APIs.

## Goal
1. Allow user refreshes to fetch from the network without being blocked by the 4-hour background cadence.
2. Limit user-initiated refreshes to a maximum of once every **10 minutes**.
3. If a refresh is requested within 10 minutes of the last user refresh:
   - Suppress the network refresh.
   - Display a banner or toast message:
     `"Not doing because last refresh was x minute(s) ago, and wait another y minute(s) before asking for a refresh"`
   - Where `x` is the elapsed minutes since the last refresh, and `y` is the remaining minutes to wait.

## Architecture & Implementation

### 1. Shared throttle rule (`:shared`)
Create `shared/src/main/kotlin/com/weatherwidget/shared/util/UserRefreshThrottle.kt`:
- Constant `THROTTLE_MINUTES = 10` (600,000 ms).
- Pure decision function `check(lastRefreshMs: Long?, nowMs: Long): Decision`:
  - Returns `Decision.Allowed` when `lastRefreshMs == null` or `nowMs - lastRefreshMs >= THROTTLE_MS`.
  - Returns `Decision.Throttled(elapsedMinutes, remainingMinutes)` otherwise.
  - `elapsedMinutes = elapsedMs / 60_000`.
  - `remainingMinutes = ceil((THROTTLE_MS - elapsedMs) / 60_000).coerceAtLeast(1)`.

### 2. Extend `RefreshNotice` (`:shared`)
In `shared/src/main/kotlin/com/weatherwidget/shared/util/RefreshNotice.kt`:
- Add `RATE_LIMITED` to `enum class Reason { UP_TO_DATE, RATE_LIMITED, ALREADY_QUEUED, IN_PROGRESS, QUOTA, NO_LOCATION }`.
- Add `elapsedMinutes: Int? = null` and `remainingMinutes: Int? = null` to `RefreshNotice.NotQueued`.
- Update `RefreshNotice.text`:
  Format: `"Not doing because last refresh was %s ago, and wait another %s before asking for a refresh"` with singular/plural minute handling.
- Update `RefreshNotice.line` and `RefreshNotice.logDetail` to log `elapsedMin` and `waitMin`.

### 3. Android string resource & `RefreshToast` (`:app`)
- In `app/src/main/res/values/strings.xml`:
  `<string name="refresh_rate_limited">Not doing because last refresh was %1$s ago, and wait another %2$s before asking for a refresh</string>`
- In `RefreshToast.text`:
  Resolve `R.string.refresh_rate_limited` when `notice.reason == Reason.RATE_LIMITED`.

### 4. Storage & Enforcement
- **Android (`FetchMetadata.kt`)**:
  - Store and retrieve `KEY_LAST_USER_REFRESH`.
- **Android Widget (`WidgetRefreshCoordinator.kt`)**:
  - When `!uiOnly` (user tap):
    - Check `UserRefreshThrottle.check(lastUserRefreshMs, nowMs)`.
    - If `Throttled`: do not enqueue sync, show `NotQueued(RATE_LIMITED)` on widget banner via `RefreshToast.show`.
    - If `Allowed`: record `lastUserRefreshMs = nowMs`, enqueue immediate forced sync (`forceRefresh = true`, `reason = "widget_tap_to_refresh"`), show `Queued(Kind.FORECAST)` banner.
- **Android App Screens (`SettingsActivity`, `ForecastHistoryActivity`, `WeatherObservationsActivity`)**:
  - Check `UserRefreshThrottle`. If throttled, show Toast with the message and do not start fetch. If allowed, record timestamp and run fetch.
- **Desktop (`DesktopUiApplication.kt`)**:
  - Track `lastUserRefreshMs`. Check `UserRefreshThrottle.check(lastUserRefreshMs, nowMs)`.
  - If throttled, display pill on current surface with rate limit message.
  - If allowed, record timestamp and invoke `repo.refresh(..., userRefresh = true)`.

## Verification
1. JVM unit tests for `UserRefreshThrottleTest` and `RefreshNoticeTest` in `:shared`.
2. Robolectric tests for `WidgetRefreshCoordinator` and `RefreshToast` in `:app`.
3. Desktop tests for `RefreshNoticeStateTest`.
4. Run `./scripts/unit-tests.sh`.
5. Verify on connected Pixel 7: tap refresh, verify immediate update; tap again within 10 minutes, verify banner shows:
   `"Not doing because last refresh was X minutes ago, and wait another Y minutes before asking for a refresh"`.
