# 6 PM Evening Mode Battery Analysis

## What happens at 6 PM

At 6 PM (`NavigationUtils.EVENING_MODE_START_HOUR = 18`), the app enters **evening mode**, which shifts the daily forecast view forward by one day:

1. **Before 6 PM**: A typical widget (3+ cols) shows *yesterday -> today -> tomorrow+forecast* (starting from offset -1).
2. **At/after 6 PM**: It switches to *today -> tomorrow -> ...* (starting from offset 0), dropping yesterday and adding an extra future forecast day.

Implemented in:
- `NavigationUtils.kt:21-23` — `isEveningMode` checks `hour >= 18`
- `DailyViewHandler.kt:184-189` — applies evening mode to rendering

## Battery impact: negligible

The 6 PM transition is **not** a separate alarm. It piggybacks on the existing periodic UI update mechanism:

1. `UIUpdateIntervalStrategy.computeDelayMillis()` (line 30-32) checks whether the next periodic UI update would land *after* 6 PM. If so, it shortens the delay to fire exactly at 6 PM instead.

2. The alarm uses `AlarmManager.RTC` (not `_WAKEUP`) via `setAndAllowWhileIdle()` (`UIUpdateScheduler.kt:118-123`), so it **will not wake a sleeping device**.

3. If the phone is asleep at 6 PM, the alarm defers until the device wakes for another reason. At that point, `ScreenOnReceiver` triggers a UI refresh anyway, applying evening mode naturally.

## Would deferring to screen-on-only be lighter?

No. The current design is already effectively screen-on gated. The `setAndAllowWhileIdle` + `RTC` (non-wakeup) combination means the 6 PM hint only fires when the device is already awake. Removing it would save approximately zero battery while losing the rare case where the device happens to be awake at 6 PM but no screen-on event follows.
