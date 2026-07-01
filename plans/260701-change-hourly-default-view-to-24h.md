# Plan - Change Hourly Default View to 24 Hours (Option B)

This plan outlines the steps required to change the hourly graph's view span from `backHours + forwardHours + 1` to exactly `backHours + forwardHours` (making the `WIDE` default view show 24 hours). This is done by modifying the loops and filters to be exclusive of the end hour rather than inclusive.

## Proposed Changes

### 1. Update Hourly Point Accumulation Loops (Exclusive of End Hour)

We will modify the hourly data loops to use `.isBefore(endHour)` instead of including `.isEqual(endHour)`. This changes the range from `[start, end]` (inclusive) to `[start, end)` (half-open/exclusive end).

* **[PrecipViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt)** (line 508):
  ```diff
  -        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
  +        while (currentHour.isBefore(endHour)) {
  ```
* **[TemperatureStateResolver.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt)** (line 433):
  ```diff
  -        while (current.isBefore(endHour) || current.isEqual(endHour)) {
  +        while (current.isBefore(endHour)) {
  ```
* **[CloudCoverViewHandler.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt)** (lines 92 and 542):
  ```diff
  -            while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
  +            while (currentHour.isBefore(endHour)) {
  ```
* **[ActualTemperatureSeriesBuilder.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt)** (line 129):
  ```diff
  -        while (currentHour.isBefore(endHour) || currentHour.isEqual(endHour)) {
  +        while (currentHour.isBefore(endHour)) {
  ```

---

### 2. Update Desktop App Filters and Fallbacks

In the desktop Compose app, we'll exclude the end hour (`cutoff`) from the filtered range.

* **[HourlyGraphInput.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/HourlyGraphInput.kt)** (line 154):
  ```diff
  -        hourly.filter { it.dateTime in (start - 3_600_000L)..cutoff }
  +        hourly.filter { it.dateTime >= (start - 3_600_000L) && it.dateTime < cutoff }
  ```
* **[TemperatureGraph.kt](file:///home/dcar/projects/weather-widget/desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt)** (lines 148-150):
  ```diff
  -        hourly.filter { it.dateTime in start..cutoff }
  -            .sortedBy { it.dateTime }
  -            .ifEmpty { hourly.sortedBy { it.dateTime }.take(backHours + forwardHours + 1) }
  +        hourly.filter { it.dateTime >= start && it.dateTime < cutoff }
  +            .sortedBy { it.dateTime }
  +            .ifEmpty { hourly.sortedBy { it.dateTime }.take(backHours + forwardHours) }
  ```

---

### 3. Update Android and Desktop Unit Tests

Adjust test assertions to expect `backHours + forwardHours` (e.g., 24 for `WIDE`, 4 for `NARROW`):

* **[PrecipGraphQueryWindowTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/PrecipGraphQueryWindowTest.kt)** (lines 97, 169):
  ```diff
  -        val expectedCount = ZoomLevel.WIDE.backHours + ZoomLevel.WIDE.forwardHours + 1 // 25
  +        val expectedCount = ZoomLevel.WIDE.backHours + ZoomLevel.WIDE.forwardHours // 24
  ```
* **[CloudCoverViewHandlerTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/CloudCoverViewHandlerTest.kt)** (line 33):
  ```diff
  -        val expectedSize = (ZoomLevel.WIDE.backHours + ZoomLevel.WIDE.forwardHours + 1L).toInt() // 25
  +        val expectedSize = (ZoomLevel.WIDE.backHours + ZoomLevel.WIDE.forwardHours).toInt() // 24
  ```
* **[TemperatureViewHandlerActualsTest.kt](file:///home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerActualsTest.kt)** (lines 252, 273):
  ```diff
  -    fun `WIDE zoom covers 25 hours`() {
  +    fun `WIDE zoom covers 24 hours`() {
  ...
  -        assertEquals("WIDE should cover exactly 25 hours (12h back + 12h forward + center)", 25, wideHours.size)
  +        assertEquals("WIDE should cover exactly 24 hours (12h back + 12h forward)", 24, wideHours.size)
  ```
* **[TemperatureGraphWindowTest.kt](file:///home/dcar/projects/weather-widget/desktop/src/test/kotlin/com/weatherwidget/desktop/TemperatureGraphWindowTest.kt)** (lines 81, 108):
  ```diff
  -        val points = hourly.filter { it.dateTime in window.startMs..window.endMs }.sortedBy { it.dateTime }
  +        val points = hourly.filter { it.dateTime >= window.startMs && it.dateTime < window.endMs }.sortedBy { it.dateTime }
  ...
  -        assertEquals(window.endMs, points.last().dateTime)
  +        assertEquals(window.endMs - 3_600_000L, points.last().dateTime)
  ```

---

## Verification Plan

1. **Run All Unit Tests**: Run `./gradlew test` to ensure all unit tests pass with the new exclusive bounds.
2. **Run Emulator Instrumented Tests**: Execute `./scripts/emulator-tests.sh` to ensure widget rendering and interaction behaviors work correctly on the Android emulator.