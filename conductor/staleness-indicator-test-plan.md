# Test Plan: Staleness Indicator (Age of Last Measurement)

This test plan outlines the verification process for the staleness indicator on Temperature and Precipitation hourly graphs.

## 1. Manual Verification (Emulator/Device)

### Scenario A: Zoomed-in (NARROW) View
1.  **Preparation**: Ensure the widget is in NARROW zoom mode on the Temperature view.
2.  **Action**: Verify there is a recent observation (within the last few hours).
3.  **Expectation**: A colored dot appears on the solid actual line, and a small text label (e.g., "25m" or "1h 10m") is drawn next to it.
4.  **Collision Test**: Navigate the graph so that a temperature label or day label is near the dot. Verify the age text is still drawn (it should draw on top or shift to avoid clipping the right edge).

### Scenario B: Zoomed-out (WIDE) View
1.  **Preparation**: Toggle the widget to WIDE zoom mode (24h view).
2.  **Action**: Check for the "Last Fetch Dot".
3.  **Expectation**: The staleness indicator (age text) should NOT be visible. Only the dot should be present to keep the dense view clean.

### Scenario C: High-Frequency Data in NARROW View
1.  **Preparation**: Ensure the database contains high-frequency (e.g., every 5 minutes) sub-hourly measurements for the last 4 hours.
2.  **Action**: Open the NARROW zoom view.
3.  **Expectation**: The age text SHOULD be visible. (Previously, it would disappear because `hours.size` exceeded 8).

---

## 2. Automated Unit Tests

### TemperatureGraphRendererTest
Add a new test file `app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererStalenessTest.kt`:

1.  **`draws age text in narrow view with few points`**:
    *   Input: 5 hours of data (5 points).
    *   Verify: `Canvas.drawText` is called with the age string (e.g., "30m").
2.  **`draws age text in narrow view with many points`**:
    *   Input: 4 hours of data with 5-minute increments (48 points).
    *   Verify: `Canvas.drawText` is called with the age string.
3.  **`does not draw age text in wide view`**:
    *   Input: 24 hours of data (24 points).
    *   Verify: `Canvas.drawText` is NOT called for the age string.
4.  **`clamped age text at right edge`**:
    *   Input: Fetch dot positioned near the right edge of the bitmap.
    *   Verify: The `finalX` calculation correctly shifts the text to the left of the dot.

### PrecipitationGraphRendererTest
Add equivalent tests in a new test file or within existing precipitation tests:

1.  **`draws age text in narrow duration`**: Verify duration-based logic works for precipitation.

---

## 3. Regression Testing
1.  **Now Indicator**: Verify the "NOW" vertical line and label still appear correctly and don't interfere with the fetch dot.
2.  **Ghost Line**: Verify the dashed ghost line correctly starts from the fetch dot when a delta is applied.
