# User-Initiated Refresh 10-Minute Throttle and Notice

## User Prompts

1. "Pixel 7: Meteo data is from 6 am.  Suppose to update every 4 hours."
2. "I want tapping refresh to work.  I don't want an override."
3. "Limit user refreshes to max every 10 minutes.  If there is no refresh occuring , say that through toast or banner message, saying not doing because last refresh was x minutes ago, and to wait another y minutes before asking for a refresh."
4. "yes"
5. "write above summary to summaries/"

## Problem & Root Cause

1. **Symptom:** On the Pixel 7 Pro home-screen widget, Meteo data remained at 6 am despite the background 4-hour update cadence, and tapping the refresh button did not trigger an on-demand forecast fetch.
2. **Evidence & Root Cause:**
   - Background forecast updates follow `ForecastHorizonCadence.kt`, running on a 4-hour cadence when charging or battery ≥ 70% with the screen on, 8 hours screen off, or 24 hours on lower battery.
   - When the user tapped "Refresh" on the widget, `WidgetRefreshCoordinator.kt` called `WidgetRefreshPolicy.shouldTriggerNetworkFetchAfterRefresh(uiOnly, isDataStale)`. Because the existing data was < 4 hours old, `isDataStale` evaluated to `false`, causing `WidgetRefreshCoordinator` to treat the data as `fresh_data` / `Reason.UP_TO_DATE`. It skipped the forecast network sync entirely and only scheduled an isolated NWS daily history gap fill.
   - Tapping refresh failed to satisfy user expectations to manually pull fresh weather on demand.

## Solution

1. **Explicit User Refreshes Bypass Routine Freshness Checks**:
   - Tapping refresh on the widget or triggering manual refresh actions in Settings, History, Observations, and Desktop UI now queues an immediate forced forecast sync (`forceRefresh = true, reason = "widget_tap_to_refresh", userRefresh = true`) plus a daily history gap fill.
2. **Rate Limit to Max Every 10 Minutes**:
   - Manual user refreshes are throttled to at most once every 10 minutes across Android and Desktop.
   - When throttled within 10 minutes: no network fetch or gap fill occurs. A user-visible notice (home-screen widget message banner on the widget, `Toast.LENGTH_LONG` on Android in-app activities, or Compose `RefreshNoticePill` on Desktop) displays:
     `"Not doing because last refresh was X minute(s) ago, and wait another Y minute(s) before asking for a refresh"`.
   - When allowed (≥ 10 minutes or initial user refresh): records the timestamp, enqueues the sync/gap fill, and displays the standard queued notice.
   - Passive background updates (periodic workers, power connect/disconnect, screen unlock repaints with `EXTRA_PASSIVE = true`, UI-only repaints) do not check, update, or get blocked by the user refresh throttle.

## Changes

1. **Core Throttle Logic (`UserRefreshThrottle.kt`, `RefreshNotice.kt`)**:
   - Created pure JVM `UserRefreshThrottle` in `:shared` with `check(lastRefreshMs, nowMs)`, returning `Allowed` or `Throttled(elapsedMinutes, remainingMinutes)`. Added helper formatters `formatMinutes` and `formatMessage`.
   - Extended `RefreshNotice` with `Reason.RATE_LIMITED(elapsedMinutes, remainingMinutes)` and updated `text()` and `logDetail()`.
2. **Android Persistence & Localization (`FetchMetadata.kt`, `strings.xml`)**:
   - Added `KEY_LAST_USER_REFRESH`, `getLastUserRefreshTime(context)`, and `setLastUserRefreshTime(context, time)` in `FetchMetadata.kt`.
   - Added string resource `refresh_rate_limited` (`"Not doing because last refresh was %1$s ago, and wait another %2$s before asking for a refresh"`) with `%1$s` and `%2$s` format specifiers across English and all 19 localized `values-*/strings.xml` resource directories.
3. **Android Widget Action & Coordination (`WidgetRefreshCoordinator.kt`, `WidgetActionReceiver.kt`, `ScreenOnReceiver.kt`, `RefreshToast.kt`)**:
   - Added `EXTRA_PASSIVE = "com.weatherwidget.EXTRA_PASSIVE"` to `WidgetActions.kt`.
   - `ScreenOnReceiver.kt` passes `EXTRA_PASSIVE = true` for screen-on repaint debt and unlock broadcasts, ensuring background unlocks do not consume or get blocked by the user throttle.
   - `WidgetActionReceiver.kt` distinguishes user taps (`userInitiated = !uiOnly && !isPassive`).
   - `WidgetRefreshCoordinator.kt` evaluates `UserRefreshThrottle.check(...)` on user-initiated refreshes. If throttled, logs `USER_REFRESH_THROTTLED` and displays `RefreshNotice.NotQueued(Reason.RATE_LIMITED)` on `R.id.widget_message_banner`. If allowed, updates timestamp and enqueues immediate forced sync.
   - `RefreshToast.kt` maps `Reason.RATE_LIMITED` to `R.string.refresh_rate_limited`.
4. **Android In-App Screens (`SettingsActivity.kt`, `ForecastHistoryActivity.kt`, `WeatherObservationsActivity.kt`)**:
   - Wired `UserRefreshThrottle.check(...)` to Settings "Refresh data", Forecast History refresh button, and Observations refresh button. When throttled, shows `Toast.LENGTH_LONG` with the rate-limit message; when allowed, records the timestamp and proceeds.
5. **Desktop Compose App (`DesktopUiApplication.kt`)**:
   - Added `lastUserRefreshMs` state and integrated `UserRefreshThrottle.check(...)` for full refresh, observations refresh, and per-source refresh buttons. Displays `RefreshNoticePill` when throttled.
6. **Documentation (`arch/hourly-forecast-update-cadence.md`)**:
   - Added the "User-initiated refresh throttle (10-minute limit)" section detailing the 10-minute cooldown, notice behavior, and passive-event exemption.
7. **Automated Tests**:
   - Added `UserRefreshThrottleTest` in `:shared`.
   - Added rate-limit assertions to `RefreshNoticeTest` in `:shared`.
   - Added `rate limited refresh shows minute details` test to `RefreshNoticeStateTest` in `:desktop`.
   - Updated `WidgetRefreshToastIntegrationRoboTest` in `:app` for the new immediate queueing and rate-limit banner behavior.

## Verification

1. **JVM & Robolectric Unit Tests**:
   - Ran `./scripts/unit-tests.sh`: all 6,188 tests passed across short, medium, long, localization, shared, and desktop test suites.
2. **Device Verification (Google Pixel 7 Pro `2A191FDH300PPW`)**:
   - Installed debug build using `./gradlew installDebug`.
   - Triggered initial widget refresh broadcast: allowed, logged `USER_REFRESH_ALLOWED`, queued immediate forced sync (`SYNC_START force=true`), and displayed queued notice.
   - Triggered subsequent widget refresh within 2 minutes: throttled, logged `USER_REFRESH_THROTTLED elapsedMin=2 waitMin=8`, suppressed network sync, and displayed the widget message banner:
     `"Not doing because last refresh was 2 minutes ago, and wait another 8 minutes before asking for a refresh"`.
   - Screenshot verified on home screen: banner clearly displayed over the widget.
