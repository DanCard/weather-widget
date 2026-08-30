# Plan: Noon-Centered Day Click & Dead Zone API Toggle

## 1. Overview & Objectives
This change addresses two user requests across both Android (`:app`) and Desktop (`:desktop`):
1. **Noon-Centered Hourly View on Day Click**: When clicking on a past or future day in daily forecast view, the hourly view should be centered around noon (12:00 PM).
2. **Dead Zone Tap to API Toggle**: When clicking on a dead zone (unclaimed/background touch area on widget or desktop popup header), map that action to toggle the API source.

---

## 2. Root Cause Analysis

### Feature 1: Day Click Hourly Centering
- **Current Behavior**:
  `DayClickResolver.calculateHourlyOffset` (in `:shared`, used by both Android and Desktop) computes the hourly offset from `now` to `targetDay.atTime(12, 0)`.
  When `ZoomStage.WIDE` was symmetric (12h back / 12h forward = 24h total), setting `centerTime` to 12:00 resulted in the window `[12:00 - 12h, 12:00 + 12h] = [00:00, 24:00]`, where noon was at 50% width.
  However, `ZoomStage.WIDE` was updated to be asymmetric (12h back / 6h forward = 18h total) to bias toward history when centered on `now`. As a result, setting `centerTime` to 12:00 produces the window `[12:00 - 12h, 12:00 + 6h] = [00:00, 18:00]`. In this window, noon (12:00) sits at `12/18 = 66.7%` (2/3 of the graph width, off-center), and the evening (6:00 PM – midnight) is omitted.
- **Root Cause**:
  `DayClickResolver.calculateHourlyOffset` assumed symmetric 12/12 back/forward hours. For asymmetric zoom (12 back / 6 forward), the visual center of the window is `centerTime - (backHours - forwardHours) / 2`. To place noon at the visual center, `centerTime` needs to be `12:00 + (backHours - forwardHours) / 2` (i.e. `12:00 + 3h = 15:00`).

### Feature 2: Dead Zone Clicks Mapped to API Toggle
- **Current Behavior**:
  - On **Android**: `setupDeadZoneCatchAll` in `TemperatureTouchTargets.kt` attached a toast broadcast (`ACTION_SHOW_TOAST` with "Dead zone tapped" in debug, empty in release) to `R.id.widget_root` to prevent Samsung launcher fallback to `MainActivity`. Furthermore, `PrecipViewHandler` and `CloudCoverViewHandler` did not call `setupDeadZoneCatchAll`.
  - On **Desktop**: Empty header space between clusters was non-interactive dead space.
- **Root Cause**:
  `setupDeadZoneCatchAll` was configured to show a debug toast rather than executing an app interaction.

---

## 3. Proposed Fix & Implementation Plan

### Step 1: Update `DayClickResolver.kt` (`:shared`)
- In `shared/src/main/kotlin/com/weatherwidget/shared/util/DayClickResolver.kt`:
  - Update `calculateHourlyOffset(now: LocalDateTime, targetDay: LocalDate, zoomStage: ZoomStage = ZoomStage.WIDE)`:
    - If `targetDay == now.toLocalDate()`, return 0 (centers on `now` with the NOW indicator).
    - Otherwise, compute the shift `shiftHours = (zoomStage.window().backHours - zoomStage.window().forwardHours) / 2` (which is `(12 - 6) / 2 = 3` hours for WIDE).
    - Set `targetCenter = targetDay.atTime(12, 0).plusHours(shiftHours)`.
    - Compute `Duration.between(alignedNow, targetCenter).toHours().toInt()`.
  - Result: For WIDE (18h window), window runs from `03:00` (3:00 AM) to `21:00` (9:00 PM). Noon (12:00 PM) is at `(12 - 3) / 18 = 50%` of the width — centered in the hourly view.

### Step 2: Update Android Widget Touch Handlers (`:app`)
- In `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`:
  - In `setupDeadZoneCatchAll`:
    - Create Intent with `action = WidgetActions.ACTION_TOGGLE_API` and extra `EXTRA_APPWIDGET_ID`.
    - Set pending intent on `R.id.widget_root`.
- In `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`:
  - Call `setupDeadZoneCatchAll(context, views, appWidgetId)` during widget update.
- In `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`:
  - Call `setupDeadZoneCatchAll(context, views, appWidgetId)` during widget update.

### Step 3: Update Desktop App (`:desktop`)
- In `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt`:
  - In `WidgetHeader`: add click handler on empty header background / spacers to invoke API source toggle (`onUpdateConfig` with next visible source).

### Step 4: Tests & Verification
- Update and add unit tests in `:shared`, `:app`, and `:desktop`:
  - `DayClickResolverTest`: verify `calculateHourlyOffset` returns the offset for noon centering on past/future days and 0 for today.
  - `DayClickHelperTest` and `DailyViewHandlerIntentContractTest`: update expected intent offsets.
  - Test `setupDeadZoneCatchAll` on Android.
  - Run `./gradlew test` across all modules.
  - Run `./scripts/emulator-tests.sh` for Android instrumented tests.
  - Verify visually on Android emulator and Desktop application.
