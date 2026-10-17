# Fold 4 Cover Display 6-Column Layout

## User Prompt

> Fold 4: The cover display has 5 columns. I think it would look better with 6 columns.

---

## Evidence & Root-Cause Analysis

### 1. Device Inspection (Samsung Galaxy Z Fold 4, `SM_F936U1` / `RFCT70G352Y`)
- **AppWidget Options** (`dumpsys appwidget` for widget 383 on cover display):
  - `appWidgetMinWidth = 337`, `appWidgetMaxWidth = 337`
  - `appWidgetMinHeight = 313`, `appWidgetMaxHeight = 313`
  - `semAppWidgetColumnSpan = 5`, `semAppWidgetRowSpan = 3` (One UI cover screen grid)
- **Current Render State**:
  - `DAILY_RENDER`: `widget=383 mode=GRAPH offset=-1 cols=5 rows=4 skipYesterday=true center=2026-10-16`
  - Screenshot verification (`/tmp/fold4_cover.png`): renders **5 day columns**: `Today` (wide overlay spanning 2 slots), `Sat`, `Sun`, `Mon`, `Tue`.

### 2. Column Derivation in Code
- **`WidgetSizeCalculator.kt`**:
  ```kotlin
  private const val CELL_WIDTH_DP = 60
  private const val COLUMN_FIT_BIAS_DP = 30

  fun columnsForWidthDp(widthDp: Int): Int =
      ((widthDp + COLUMN_FIT_BIAS_DP).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)
  ```
  - For width 337dp:
    `((337 + 30) / 60).roundToInt() = (367 / 60).roundToInt() = 6.12` &rarr; **6 available launcher slots**.
- **`LargeTodayOverlayPolicy.kt`**:
  ```kotlin
  val enabled = useGraph && todayVisible && availableColumns >= profile.minColumns && rows >= MIN_ROWS
  return Decision(
      enabled = enabled,
      displayColumns = if (enabled) availableColumns - 1 else availableColumns,
  )
  ```
  - For rows = 4 (`>= MIN_ROWS = 4`) and availableColumns = 6 (`>= minColumns = 3`):
    - Detailed Today overlay is enabled (`TODAY_SLOT_SPAN = 2`).
    - Today consumes 2 grid slots, leaving 4 slots for other days.
    - Result: `displayColumns = 6 - 1 = 5` days.

---

## Proposed Changes

### 1. Increase Column Density
- In `app/src/main/java/com/weatherwidget/widget/handlers/WidgetSizeCalculator.kt`:
  - Lower `CELL_WIDTH_DP` from `60` to `55` (with `COLUMN_FIT_BIAS_DP = 30`).
  - **Fold 4 Cover (337dp)**: `(337 + 30) / 55 = 367 / 55 = 6.67` &rarr; rounds to **7 slots**.
    - With large Today overlay (`TODAY_SLOT_SPAN = 2`), `displayColumns = 7 - 1 = 6` days (`Today` + 5 forecast days).
    - Per-slot width: ~48.1dp (well within existing `fitScaleForWidth` shrink-to-fit limits; Pixel 7 Pro is 53.3dp).
  - **Pixel 7 Pro (373dp)**: `(373 + 30) / 55 = 403 / 55 = 7.33` &rarr; rounds to **7 slots** (remains 6 days with Today overlay; unchanged).
  - **Fold 4 Unfolded (574dp)**: `(574 + 30) / 55 = 604 / 55 = 10.98` &rarr; 11 slots (was 10).

### 2. Dual-Platform Parity
- Create `shared/src/main/kotlin/com/weatherwidget/shared/graph/DailyLayoutConstants.kt` holding:
  - `CELL_WIDTH_DP = 55`
  - `CELL_HEIGHT_DP = 90`
  - `ICON_WIDTH_THRESHOLD_DP = 130`
- Update `app/src/main/java/com/weatherwidget/widget/handlers/WidgetSizeCalculator.kt` and `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt` to reference `DailyLayoutConstants`.

### 3. Unit Tests & Bug Fixes
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/DailyLayoutConstantsTest.kt`: verify constant definitions.
- `app/src/test/java/com/weatherwidget/widget/handlers/WidgetSizeCalculatorColumnsTest.kt`: updated 55dp boundaries.
- `app/src/test/java/com/weatherwidget/data/repository/ForecastHorizonStorageIntegrationTest.kt`: pinned `clockMs` to 08:10 so evening runs don't roll past midnight.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt`: passed `nowMs = now` to `weatherDao.upsertForecasts(...)` so evening runs don't trigger `SameDayExtremeCutoff` on historical data.
- `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopForecastHorizonStorageTest.kt`: pinned `now` to 10:00 AM.
- `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopNwsWebLegTest.kt`: used `runBlocking` (matching Android) to prevent `TestCoroutineScheduler` virtual-time timeout on Ktor `MockEngine`.

---

## Verification Plan

1. **Unit Tests**:
   - Run `./scripts/unit-tests.sh Short` to verify all formula and boundary tests pass.
2. **On-Device Verification**:
   - Deploy debug build to Fold 4 (`RFCT70G352Y`): `./gradlew installDebug`.
   - Broadcast update or touch navigation to re-render widget 383.
   - Capture device screenshot via adb and inspect `/tmp/fold4_cover_verified.png` to confirm 6 columns (`Today` + 5 days) are displayed legibly without crowding or label truncation.
   - Inspect `app_logs` to verify `cols=6` (or `displayCols=6`) logged in `DAILY_RENDER`.
