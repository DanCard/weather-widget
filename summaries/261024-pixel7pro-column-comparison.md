# Pixel 7 Pro Daily View: 6 vs 7 Columns Implementation & Verification

## Summary of Changes

We implemented **Option A** across Android, Desktop, and Shared codebases:
1. **Single Slot Reservation**:
   - In [`LargeTodayOverlayPolicy.kt`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/graph/LargeTodayOverlayPolicy.kt), `displayColumns` now preserves all `availableColumns` (`displayColumns = availableColumns`). Today no longer reserves a double slot by dropping an entire day from the forecast.
   - In [`DailyLargeTodayOverlayPolicy.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyLargeTodayOverlayPolicy.kt), `TODAY_SLOT_SPAN = 1`. Each day maps directly to 1 launcher click zone.
   - In [`DailyViewHandler.kt`](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt) and [`DesktopDailyForecastModel.kt`](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt), candidate date ranges now inspect all available columns.
2. **Visual Parity**:
   - The canvas renderer continues to use [`WeightedColumnLayout`](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/graph/WeightedColumnLayout.kt) with a `1.25x` weighting for Today.
   - On Pixel 7 Pro (width 373 dp), Today receives ~64.4 dp width while the remaining 6 days receive ~51.5 dp each.
3. **Plan Reference**:
   - [plans/261024-today-overlay-single-slot-seven-columns.md](file:///home/dcar/projects/weather-widget/plans/261024-today-overlay-single-slot-seven-columns.md)

---

## On-Device Verification (Widget 88 Before & After)

````carousel
![Widget 88: Before (6 Columns with Detailed Today)](../screenshots/pixel7pro-7col-20261024/widget88_6columns.png)
<!-- slide -->
![Widget 88: After (7 Columns with Detailed Today)](../screenshots/pixel7pro-7col-20261024/widget88_7columns.png)
````

### Results:
- **Columns Displayed**: Increased from 6 days to **7 days** (Yesterday, Today + 5 forecast days: Sat, Sun, Mon, Tue, Wed, Thu).
- **Legibility**: Today's frosted focal pill, thermostat triple bar, and `+1.8 fcst / 60.8°` stack fit with ample clearance. The additional day column fits without label overlap or clipping.
- **Click Routing**: Each of the 7 columns aligns with its corresponding touch zone and opens the correct date details.

---

## Test Verification

- **JVM Unit Tests**: Ran `./scripts/unit-tests.sh` &mdash; **6,014 tests passed in 55 seconds** across `:shared` (2,693), `:desktop` (720), and `:app` (2,601).
- **Desktop Distribution**: Built via `./gradlew :desktop:createDistributable` &mdash; successful in 2 seconds.
- **Device Deployment**: Installed on Pixel 7 Pro (`2A191FDH300PPW`) via `./gradlew installDebug` and verified live with runtime captures.
