# Move evening-mode threshold from 17:00 to 08:00 (narrow widgets only)

## Context

Evening mode shifts the daily-view window forward: instead of `[yesterday | today | tomorrow | …]`, narrow widgets show `[today | tomorrow | day-after | …]`. Currently triggers at 5 PM. The user wants narrow widgets to be forecast-forward much earlier in the day so the widget is more useful at lunch / mid-morning.

Two side-effects of the current design need to be addressed:

1. **Today-comparison overlay is conflated with the window shift.** At 5 PM today's high has roughly happened, so showing a forecast-vs-actual overlay on today's column was reasonable. At 8 AM the actual high hasn't occurred yet, so the overlay would compare a forecast bar against a partial morning temperature range — misleading. Decision: **drop the today-overlay branch.** Past days keep their overlay; today never gets one. Once the calendar rolls over, "today" becomes "yesterday" and automatically picks up the past-day overlay — so the feature's value (a preview a few hours early) is small and gets smaller with the 8 AM shift.

2. **`UIUpdateScheduler` has stale references** (`UIUpdateScheduler.kt:154-173`): comments say "6 PM," code hardcodes `withHour(18)`, but it gates on `EVENING_MODE_START_HOUR` which is 24 (the wide-widget sentinel) — meaning that branch is currently dead code. Fix it to use the narrow-widget threshold (the only one that actually triggers evening mode in practice).

Wide widgets (>8 columns) are unchanged — they keep `EVENING_MODE_START_HOUR = 24`, never entering evening mode early.

## Changes

### 1. `app/src/main/java/com/weatherwidget/util/NavigationUtils.kt`
- Change `NARROW_EVENING_MODE_START_HOUR` from `17` to `8`.
- Update the KDoc on `isEveningMode` (line 35) to reflect the new threshold ("8 AM" instead of "5 PM").
- Leave `EVENING_MODE_START_HOUR = 24` and the column threshold (`8`) unchanged.

### 2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- Line 327: change `val showComparison = (isPastDate || (isToday && isEveningMode))` to `val showComparison = isPastDate`.
- Today's column will no longer render the yellow forecast-comparison overlay regardless of hour. Past days unchanged.
- Verify no other call sites depend on `(isToday && isEveningMode)` semantics — Phase 1 exploration didn't find any.

### 3. `app/src/main/java/com/weatherwidget/widget/UIUpdateScheduler.kt`
- Lines 154-173 (`getTimeUntilEveningMode`): replace the gate on `EVENING_MODE_START_HOUR` with the narrow constant `NARROW_EVENING_MODE_START_HOUR`, and replace the hardcoded `withHour(18)` with `withHour(NavigationUtils.NARROW_EVENING_MODE_START_HOUR)`.
- Update the KDoc to refer to the narrow threshold (now 8 AM) instead of "6 PM."
- Optional rename: `getTimeUntilEveningMode` → keep name (still semantically accurate), but update the comment block at line 155.

### 4. Tests — `app/src/test/java/com/weatherwidget/util/NavigationUtilsTest.kt`
- `isEveningMode uses 5pm threshold for narrow widgets` (line 65) → rename to `…uses 8am threshold for narrow widgets`; replace `LocalTime.of(17, 0)` with `LocalTime.of(8, 0)` and `LocalTime.of(16, 59)` with `LocalTime.of(7, 59)`.
- `isEveningMode uses 6pm threshold for wide widgets` (line 79): keep semantics — wide widgets still don't enter evening mode at 5 PM, 6 PM, or 11 PM. Test still passes as written, but rename to `…wide widgets never enter evening mode early` and add a 9 AM case to make the new asymmetry explicit (`9 cols at 9am should not be evening mode`).
- `getDisplayCenterDate shift for evening mode` (line 49): unchanged — pure offset arithmetic, doesn't depend on the hour value.

### 5. Search for any test that asserts today's overlay
- Run `grep -rn "showComparison\|isToday && isEveningMode" app/src/test` to confirm no test pins the current overlay-on-today behavior. If found, delete or update those assertions.

## Critical files (for executor reference)
- `app/src/main/java/com/weatherwidget/util/NavigationUtils.kt` (constants + `isEveningMode`)
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt:327` (overlay branch)
- `app/src/main/java/com/weatherwidget/widget/UIUpdateScheduler.kt:154-173` (stale 6 PM refs)
- `app/src/test/java/com/weatherwidget/util/NavigationUtilsTest.kt` (threshold tests)

## Verification

1. **Unit tests:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.util.NavigationUtilsTest"` — all four `NavigationUtils` tests should pass.
2. **Build:** `./gradlew installDebug` on a connected emulator.
3. **Manual on emulator (narrow widget):**
   - Set emulator clock to 07:59 → trigger widget update → confirm window is `[yesterday | today | tomorrow | …]` (yesterday visible).
   - Set emulator clock to 08:00 → trigger widget update → confirm window slides forward to `[today | tomorrow | day-after | …]` (yesterday gone, extra forecast day on right).
   - Confirm today's column has **no yellow overlay** at any time of day. Past days still show the overlay.
4. **Manual on emulator (wide widget, >8 cols):**
   - Resize widget to 9+ columns. At any hour, confirm yesterday remains visible (no shift).
5. **Logs:** `adb logcat | grep -E "DailyView|Navigation"` to verify no unexpected errors.
