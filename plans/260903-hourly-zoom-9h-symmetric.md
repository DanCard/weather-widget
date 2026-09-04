# Plan: Center Hourly View Around Now (9 Hours Back / 9 Hours Forward)

## 1. Overview & Goal
Currently, the default hourly zoom stage (`ZoomStage.WIDE`) is an 18-hour window configured asymmetrically:
- **12 hours back / 6 hours forward** around the center hour (`now` for Today).
- The "Now" indicator sits ~67% across the screen.
- Day click navigation for other days shifts the center hour to account for this asymmetry (`shiftHours = (12 - 6) / 2 = +3h` -> 15:00), framing 3:00 AM to 9:00 PM around noon.

The goal is to update this default view to be **symmetric**:
- **9 hours back / 9 hours forward** (18 hours total).
- The "Now" indicator sits at the **exact center (50%)** of the graph.
- Applied symmetrically across both **Android** (`:app`) and **Linux Desktop** (`:desktop`).

---

## 2. Technical Details & Required Changes

### A. Shared Module (`:shared`)
1. **`ZoomStage.kt` (`com.weatherwidget.shared.graph.ZoomStage`)**:
   - Update `ZoomStage.WIDE`:
     ```kotlin
     WIDE -> ZoomWindow(
         stage = WIDE,
         backHours = 9,
         forwardHours = 9,
         navJump = 3,
         labelInterval = 4,
         smoothIterations = 3,
     )
     ```
   - Update accompanying KDoc comments explaining the symmetric 9/9 split around the now-line.

2. **`DayClickResolver.kt` (`com.weatherwidget.shared.util.DayClickResolver`)**:
   - `calculateHourlyOffset`:
     ```kotlin
     val shiftHours = (window.backHours - window.forwardHours) / 2
     ```
     With `backHours = 9` and `forwardHours = 9`, `shiftHours` becomes `0`.
   - Past/future day clicks will target `targetDay.atTime(12, 0)` directly, framing 3:00 AM to 9:00 PM symmetrically centered on noon.
   - Today clicks remain `offset = 0`, framing `[now - 9h, now + 9h]` centered on the current hour.

### B. Linux Desktop Module (`:desktop`)
1. **`DesktopGraphUtils.kt` (`com.weatherwidget.desktop.DesktopGraphUtils`)**:
   - Update `DEFAULT_ZOOM_FACTOR`:
     - Formula: $z = \frac{\ln(9 / \text{MIN\_BACK\_HOURS})}{\ln(\text{MAX\_BACK\_HOURS} / \text{MIN\_BACK\_HOURS})} = \frac{\ln(9 / 2)}{\ln(720 / 2)} \approx 0.256f$
     - Change `DEFAULT_ZOOM_FACTOR` from `0.304f` to `0.256f`.
   - Update documentation comments reflecting 9h back / 9h forward at default zoom.
   - Note on `forwardAnchors`: The `ZoomStage.WIDE` anchor in `forwardAnchors` is dynamically mapped via `stage.window().backHours to stage.window().forwardHours.toFloat()`, so it automatically maps `(0.256f, 9.0f)`.

### C. Android & Desktop Touch Zones / Mappers
1. **`HourlyTouchZoneMapper.kt` (`com.weatherwidget.widget.HourlyTouchZoneMapper`)**:
   - Formula uses `asymmetryShift = (zoom.forwardHours - zoom.backHours) / 2f`.
   - For WIDE (9/9), `asymmetryShift = 0f`.
   - 13 zones map from $-9$ to $+9$ hours: `[-9, -8, -6, -4, -3, -2, 0, 2, 3, 4, 6, 8, 9]`.
   - Center zone (index 6) maps to exactly offset `0`.

---

## 3. Test Suite Updates

1. **`:shared` Tests**:
   - `DayClickResolverTest.kt`:
     - Update expected offsets for tomorrow/yesterday/past days (which now anchor directly to `targetDay.atTime(12, 0)` with `shiftHours = 0` instead of `+3h`).
     - Verify `offset_centersWideViewAroundNoonForFutureDay` and `offset_centersWideViewAroundNoonForPastDay` assert start is 3:00 AM, end is 9:00 PM, and center is 12:00 PM.
   - `BlendCentreExcludesFreshRowsTest.kt`:
     - Check any hardcoded `backHours = 12, forwardHours = 6` fixtures that specifically mock WIDE.

2. **`:desktop` Tests**:
   - `DesktopGraphZoomTest.kt`:
     - Update tests asserting `DEFAULT_ZOOM_FACTOR` (e.g. `zoomFactorFromLegacy("WIDE")`).
     - Update `the default factor renders the WIDE window` (asserts `wide.backHours == 9`, `wide.forwardHours == 9`).
   - `NarrowZoomSpanDisplayedHoursTest.kt`:
     - Update test sweep containing `DesktopGraphUtils.DEFAULT_ZOOM_FACTOR`.

3. **`:app` Tests**:
   - `WeatherWidgetProviderRobolectricTest.kt`:
     - Update `zoneIndexToOffset` assertions for WIDE default:
       - index 0 -> `-9` (was `-12`)
       - index 12 -> `9` (was `6`)
       - index 6 -> `0` (was `-3`)
       - index 5 -> `-2` (was `-4`)
   - `ZoomCycleRoboTest.kt`:
     - Update `expectedOffsets` array for WIDE: `[-9, -8, -6, -4, -3, -2, 0, 2, 3, 4, 6, 8, 9]`.
     - Update `withNonZeroBaseOffset`: index 0 with base offset 6 resolves to `6 - 9 = -3` (was `-6`).
   - `DailyViewHandlerTest.kt` & `DailyHistoryClickIntentRoboTest.kt`:
     - Update expected offsets for future and past day intent extras that test day click navigation.

---

## 4. Verification Steps
1. Run shared tests: `./gradlew :shared:test`
2. Run desktop tests: `./gradlew :desktop:test`
3. Run android tests: `./gradlew :app:testDebugUnitTest`
4. Rebuild desktop distribution (`./gradlew :desktop:createDistributable`) and relaunch desktop app to verify visual layout.
5. Install and verify on Android device/emulator (`./gradlew installDebug`).
