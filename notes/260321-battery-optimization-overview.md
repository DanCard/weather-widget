# Battery Optimization — Architecture & Verification

Created: 2026-03-21

## Core Design Principle

The widget uses a **two-tier update architecture** that separates cheap UI refreshes (reading cached DB data) from expensive network fetches. This is the most impactful battery optimization — most widget drain comes from unnecessary network calls and device wake locks, and this design avoids both.

---

## Tier 1: UI-Only Updates (No Network)

- Triggered by `UIUpdateScheduler` via `AlarmManager.RTC` (NOT `RTC_WAKEUP` — no device wakeup)
- `UIUpdateReceiver` checks `PowerManager.isInteractive` — skips work if screen is off, preserves schedule
- Reads cached hourly forecasts, interpolates current temperature via `TemperatureInterpolator`
- Frequency scales with how fast temperature is changing between hours:

| Temp change (current → next hour) | UI Update Frequency |
|------------------------------------|---------------------|
| ≥ 6°F | Every 15 min |
| ≥ 4°F | Every 20 min |
| ≥ 2°F | Every 30 min |
| < 2°F | Every 60 min |

- When charging: capped at every 2 min max (`UIUpdateIntervalStrategy`, `PLUGGED_IN_MAX_DELAY_MS`)
- Key files: `UIUpdateScheduler.kt`, `UIUpdateReceiver.kt`, `UIUpdateIntervalStrategy.kt`, `TemperatureInterpolator.kt`

---

## Tier 2: Network Data Fetches

### Background Scheduled Fetches

Battery-aware intervals from `BatteryFetchStrategy.kt`:

| Condition | Interval |
|-----------|----------|
| Charging | 30 min |
| Battery > 70% | 4 hours |
| Battery > 50% | 8 hours |
| Battery ≤ 50% | No scheduled fetch |

Below 30% battery: opportunistic fetches are also blocked (`MIN_BATTERY_FOR_OPPORTUNISTIC_FETCH = 30`).

WorkManager runs a 1-hour periodic baseline (`schedulePeriodicUpdate()` in `WeatherWidgetProvider`), but the actual fetch is gated by the above strategy.

### Cooldowns and Rate Limits

- **5-minute cooldown** between full data fetches (`WeatherWidgetWorker`)
- **30-minute staleness threshold** — no fetch if data is fresh (`DataFreshness.kt`, `STALENESS_THRESHOLD_MINUTES = 30`)
- **10-minute global rate limit** across all fetch paths (`WeatherRepository`, `MIN_NETWORK_INTERVAL_MS`)
- **2-minute debounce** on power-connected events (`PowerConnectedRefreshPolicy`)

### User Interaction Fetches

Most widget taps render from cached DB with **no network call**. A background fetch is triggered only if data is over 4 hours old (`STALE_DATA_THRESHOLD_MS` in `WidgetIntentRouter`).

---

## Opportunistic Update Sources

### Screen Unlock (`ScreenOnReceiver`)
- Always triggers a UI-only refresh
- If charging: also enqueues a current-temp network fetch
- If battery < 30% and not charging: forces UI-only mode

### Opportunistic Job Service (`OpportunisticUpdateJobService`)
- Android 8+ only; uses `JobScheduler` with 30-min period
- **Piggybacks on existing system wakeups** — no independent wakeups
- 15-second startup grace period to avoid churn on boot
- Checks `DataFreshness.hasRecentHourlyData()` before triggering anything

### Charging Loop (`CurrentTempUpdateScheduler`)
- Active only when charging AND screen is on
- Fetches fresh current-temp data every 10 minutes
- Cancelled immediately on screen-off or unplug (`ScreenOnReceiver`)

---

## Wake Lock Policy

- **No explicit wake locks** (`PowerManager.newWakeLock()` is never called)
- `AlarmManager.RTC` (not `RTC_WAKEUP`) — device is never woken up to update display
- `goAsync()` in BroadcastReceivers provides system-managed partial wake lock during execution only
- Screen-off check in `UIUpdateReceiver` prevents doing work when display is inactive

---

## Verifying Battery Usage

### Step 1: Battery Historian
```bash
adb shell dumpsys batterystats --reset
adb shell dumpsys battery unplug   # simulate unplugged
# Use device for 2-4 hours, then:
adb bugreport > bugreport.zip
# Upload bugreport.zip to https://bathist.ef.lc/
```

### Step 2: Real-time Monitoring
```bash
# Active alarms for the widget
adb shell dumpsys alarm | grep -A5 weatherwidget

# JobScheduler state
adb shell dumpsys jobscheduler | grep -A10 weatherwidget

# WorkManager execution
adb shell dumpsys jobscheduler | grep -A5 WeatherWidgetWorker
```

### Step 3: Logcat — Battery-Relevant Tags
```bash
adb logcat -s "BatteryFetchStrategy" "UIUpdateScheduler" "ScreenOnReceiver" \
  "OpportunisticUpdate" "CurrentTempUpdate" "WidgetRefreshPolicy" "WeatherWidgetWorker"
```
Shows every scheduling decision in real time — fetch skips, UI-only mode selection, cooldown hits.

### Step 4: Simulate Battery States
```bash
adb shell dumpsys battery set level 20   # low battery — should suppress fetches
adb shell dumpsys battery unplug
adb shell dumpsys battery set ac 1       # charging — should enable aggressive updates
adb shell dumpsys battery reset          # restore real state
```

---

## Key Files Reference

| File | Role |
|------|------|
| `BatteryFetchStrategy.kt` | Battery-aware fetch interval logic |
| `UIUpdateScheduler.kt` | AlarmManager-based opportunistic UI scheduling |
| `UIUpdateReceiver.kt` | Handles alarm-triggered UI updates (screen-on check) |
| `UIUpdateIntervalStrategy.kt` | Charging/evening adjustments for UI delay |
| `ScreenOnReceiver.kt` | Screen unlock, screen off, power connected events |
| `OpportunisticUpdateJobService.kt` | 30-min piggyback job (Android 8+) |
| `CurrentTempUpdateScheduler.kt` | 10-min charging loop for current temp |
| `CurrentTempFetchPolicy.kt` | Policy: when to allow current-temp network fetch |
| `WidgetRefreshPolicy.kt` | UI-only vs network-capable decision on unlock/refresh |
| `PowerConnectedRefreshPolicy.kt` | 2-min debounce for power-connected refresh |
| `DataFreshness.kt` | Staleness checks (30-min threshold, hourly data availability) |
| `WeatherWidgetWorker.kt` | Main worker: full fetches, UI-only, current-temp, backfill |
| `TemperatureInterpolator.kt` | Temp-change-rate-based update frequency calculation |

---

## Things to Watch For (Regression Risk)

- Switching `AlarmManager.RTC` → `RTC_WAKEUP` anywhere in `UIUpdateScheduler` would cause device wakeups
- Removing the `isInteractive` guard in `UIUpdateReceiver` would do work on screen-off
- Adding a `PowerManager.newWakeLock()` without a paired release would drain battery
- Adding network calls inside `AlarmManager` receivers instead of dispatching to WorkManager

---

## Note on CLAUDE.md vs Code

`CLAUDE.md` documents fetch intervals as 60/120/240/480 min, but `BatteryFetchStrategy.kt` actually uses **30/240/480/disabled**. The code is the source of truth.
