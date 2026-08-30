# Summary: Noon-Centered Day Click & Dead Zone API Toggle

## 1. Overview
Implemented two dual-platform user interactions across Android (`:app`) and Desktop (`:desktop`):
1. **Noon-Centered Hourly View on Day Click**: Clicking on a past or future day in daily forecast view centers the hourly view around noon (12:00 PM) rather than cutting off the evening.
2. **Dead Zone Clicks Mapped to API Toggle**: Clicking on dead zones / unclaimed touch areas (widget root background on Android, popup header dead space on Desktop) triggers the weather API source toggle.

---

## 2. Changes Made

### A. Noon-Centered Hourly View on Day Click (`:shared`, `:app`, `:desktop`)
- **Shared Logic**: Updated `DayClickResolver.calculateHourlyOffset` in `:shared` (`shared/src/main/kotlin/com/weatherwidget/shared/util/DayClickResolver.kt`):
  - When `targetDay != today`, the target center time is shifted by `(window.backHours - window.forwardHours) / 2` (`+3h` for `ZoomStage.WIDE`'s 12-back / 6-forward window, targeting `15:00`).
  - Framing: The 18-hour WIDE window spans `03:00` (3 AM) to `21:00` (9 PM), placing noon (`12:00 PM`) at exactly 50% width (`(12 - 3) / 18 = 50%`).
  - When `targetDay == today`, offset returns `0` (centered on `now`).

### B. Dead Zone Clicks Mapped to API Toggle (`:app`, `:desktop`)
- **Android (`:app`)**:
  - Updated `setupDeadZoneCatchAll` in `TemperatureTouchTargets.kt` (`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`) to attach `WidgetActions.ACTION_TOGGLE_API` to `R.id.widget_root`.
  - Added `setupDeadZoneCatchAll` calls to `PrecipViewHandler.kt` and `CloudCoverViewHandler.kt` to ensure complete coverage alongside `DailyViewHandler` and `TemperatureViewHandler`.
- **Desktop (`:desktop`)**:
  - Updated `WidgetHeader` in `Main.kt` (`desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`) so that clicking unclaimed header space triggers `toggleWeatherSource`.

---

## 3. Verification

- **Unit Tests**:
  - `DayClickResolverTest`: Verified `calculateHourlyOffset` returns the noon-centered offsets for past/future days and 0 for today.
  - `DayClickHelperTest`, `DailyViewHandlerTest`, `DailyHistoryClickIntentRoboTest`, `DesktopUiTest`: Updated expected offsets and assertions to reflect noon-centered window geometry.
  - Executed `./gradlew test` across all modules (`:shared`, `:desktop`, `:app`) — **Passed (2,120+ tests)**.
- **Instrumented Tests**:
  - Executed `./scripts/emulator-tests.sh` on `Generic_Foldable_API36` — **Passed (92 passed, 2 skipped, 0 failed)**.
- **Associated Plan**:
  - `plans/260830-noon-centered-day-click-and-dead-zone-api-toggle.md`
