# Full Forecast Refresh: 8-Hour Clock Reset on Manual Refresh

*Date: 2026-10-18*

## Summary

**Yes, running a full refresh resets the 8-hour clock for the next scheduled full forecast refresh.**

Under the unified cadence architecture ([`ForecastHorizonCadence.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/data/remote/ForecastHorizonCadence.kt)), when a manual refresh runs:
1. It requests a **LONG** horizon fetch (full ~8-day daily forecast and full hourly reach) for the active/displayed source(s).
2. On completion, it records a new **LONG fetch marker** with the current timestamp.
3. The next routine full refresh calculation (`lastLongMs + intervalHours - 2min grace`) measures from this new timestamp, resetting the 8-hour countdown (when on AC/charging or battery $\ge$ 70% with screen on).
4. Because a LONG fetch also satisfies the SHORT horizon, it resets the **SHORT fetch marker** as well (restarting the 4-hour screen-on / 8-hour screen-off countdown).

---

## 1. Cadence Matrix (`ForecastHorizonCadence`)

Daily and hourly forecast cadences are unified across all weather APIs and platforms (Android `:app` and Linux `:desktop`):

| Battery Level / Power | Screen State | Long Horizon (≈ 8 days) | Short Horizon (3 days / 72 h) |
|---|---|---|---|
| **$\ge$ 70% or Charging** | **Interactive / Screen ON** | **Every 8 hours** | **Every 4 hours** |
| **$\ge$ 70% or Charging** | **Screen OFF** | **Every 24 hours** | **Every 8 hours** |
| **< 70% (Battery Saver)** | **ON or OFF** | **Every 24 hours** | *None* |

* **Due Calculation:**
  ```kotlin
  longDueAtMs = lastLongMs + (longIntervalHours * 3_600_000L) - DUE_GRACE_MS (120s)
  shortDueAtMs = lastShortMs + (shortIntervalHours * 3_600_000L) - DUE_GRACE_MS (120s)
  ```

---

## 2. Refresh Execution Flow

### Triggering Manual Refresh (`userRefresh = true`)
* **Android:** Triggered via Settings button, widget refresh, or history refresh. Schedules immediate worker sync with `userRefresh = true` (`ForecastFetchContext.userRefresh = true`).
* **Desktop:** Triggered via popup/tray Refresh button. Calls `DesktopWeatherRepository.refresh(reason = "user_refresh:...", userRefresh = true)`.

### Horizon Selection (`ForecastHorizonCadence.forced`)
```kotlin
fun forced(decision: Decision?, userRefresh: Boolean): Horizon = when {
    userRefresh -> Horizon.LONG
    else -> decision?.due ?: Horizon.SHORT
}
```
* For the active/displayed source, `userRefresh == true` forces `Horizon.LONG`.

### Marker Recording
When the fetch succeeds:
* **Android ([`ForecastFetchCoordinator.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/repository/ForecastFetchCoordinator.kt#L289-L301)):**
  ```kotlin
  widgetStateManager.markShortFetch(source.id, latitude, longitude, nowMs)
  if (horizon == ForecastHorizonCadence.Horizon.LONG) {
      widgetStateManager.markLongFetch(source.id, latitude, longitude, nowMs)
  }
  ```
  Persists to SharedPreferences under `forecast_long_fetch_<sourceId>` and `forecast_short_fetch_<sourceId>`.
* **Desktop ([`DesktopForecastHorizon.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopForecastHorizon.kt#L56-L61)):**
  ```kotlin
  dao.log(tag = SHORT_TAG, message = prefix, level = "INFO")
  if (horizon == ForecastHorizonCadence.Horizon.LONG) {
      dao.log(tag = LONG_TAG, message = prefix, level = "INFO")
  }
  ```
  Logs `FORECAST_LONG_FETCH` and `FORECAST_SHORT_FETCH` to `app_logs`.

Because `lastLongMs` is now `nowMs`, the next full forecast will not be due until 8 hours later (or 24 hours if screen turns off).

---

## 3. Scope: Active vs. Non-Active Sources (Android)

* **Displayed / Active Sources:** Guaranteed to execute a LONG fetch on manual refresh and reset both LONG and SHORT markers.
* **Non-Active Sources Riding Along:** If untargeted sync fetches other enabled background sources, `isUserRefreshOf(source)` evaluates to `false`. These sources only run a LONG fetch if their own background cadence was already due for LONG; otherwise, they execute a SHORT fetch and update only their SHORT marker.

---

## 4. Historical Context

* **Pre-October 16, 2026 (`HourlyWindowPolicy`):** Manual refreshes intentionally withheld writing the full/long marker so that a daytime manual refresh would not postpone the scheduled overnight charger full sync.
* **Post-October 16, 2026 (`ForecastHorizonCadence`):** The architecture unified daily and hourly schedules into LONG (8d) and SHORT (3d) horizons. Because a fresh 8-day forecast is persisted during a manual refresh, it explicitly writes the LONG marker, restarting the 8-hour clock cleanly.

Gemini 3.8 Flash (Medium)
agy --conversation=dbf48303-48ad-46e3-ab65-c100d96879cd
