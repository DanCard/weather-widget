# Test Plan: Daily Rain Chance Appearance and Disappearance

## Goal
Verify that the daily forecast rain probability label correctly appears when there is sufficient vertical space (Emulator scenario) and disappears when it would overlap the widget's header area (Samsung/Pixel scenario).

## Background
The rain probability label is drawn above the high temperature text. To avoid cluttering the widget's header (which contains the Date, Current Temp, and API source), we implement a "forbidden zone" at the top of the widget.
- **Forbidden Zone:** The top 50% of the header area (approx. 27dp).
- **Available Space:** The lower 50% of the header area (between 27dp and 54dp from the top) is available for the rain label.
- **Anchor:** The high temperature baseline sits approx. 6dp above the high temperature point on the graph.

## Test Scenarios

### 1. Emulator Appearance (Sufficient Room)
**Scenario:** A standard widget height where the high temperature is the maximum value, leaving exactly 54dp of header space.
- **Inputs:**
    - `widthPx = 800`, `heightPx = 500` (Simulating a medium-sized widget).
    - `high = 70f`, `low = 50f`.
    - `rainData = RainData(dailyRainLabelText = "65%")`.
- **Expected Behavior:**
    - The rain label sits above the high temp.
    - Since 27dp is available for drawing, and the rain label + gap + high temp offset totals ~20dp, the label **MUST** be drawn.
    - Placement: `ABOVE_HIGH`.

### 2. Header Avoidance (Insufficient Room)
**Scenario:** A very short widget or a layout where the top margin is pushed down, making the 27dp limit too restrictive.
- **Inputs:**
    - `widthPx = 800`, `heightPx = 200` (Very short widget).
    - `high = 70f`, `low = 50f`.
- **Expected Behavior:**
    - The `graphTop` (54dp) remains the same DP-wise, but because the total height is small, the vertical scaling might compress things.
    - If the rain label's top edge (`baseline + ascent`) is less than 27dp from the top, it **MUST** be skipped.
    - Expected log/debug: `rainLabel skipped: above-high insufficient space`.

### 3. Tight Spacing (Visual Polish)
**Scenario:** Verify the gap between rain chance and high temp is minimal.
- **Verification:**
    - Use `RainLabelDrawnDebug` to check `placement.baseline` vs `highBaseline`.
    - The difference should be exactly `gap + rainMetrics.descent`.
    - With `gap = 0.5dp`, the visual distance should be very small.

## Automated Test Case (Kotlin)

These tests are implemented in `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`:

```kotlin
@Test
fun renderGraph_placesRainLabelAboveHighWhenRoomExists() {
    // Use a dummy day with high=100 to push the graph scale up,
    // so the 70f day has more headroom below the header.
    val labels = renderRainLabels(
        days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 2, 2),
                label = "Sun",
                high = 100f,
                low = 80f,
            ),
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 2, 3),
                label = "Mon",
                high = 70f,
                low = 50f,
                rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
            ),
        ),
        widthPx = 800,
        heightPx = 500,
        numColumns = 4
    )

    assertEquals("Rain label should be shown when room exists", 1, labels.size)
    assertEquals("ABOVE_HIGH", labels.first().placement)
}

@Test
fun renderGraph_hidesRainLabelWhenItCrossesTopFiftyPercentOfHeader() {
    // At 100px height, high temp at 100f is forced to graphTop (54px).
    // The rain label would sit around 20-30px from top, which is < 27px (50% of 54px).
    val labels = renderRainLabels(
        days = listOf(
            DailyForecastGraphRenderer.DayData(
                date = LocalDate.of(2026, 2, 3),
                label = "Mon",
                high = 100f,
                low = 50f,
                rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%"),
            ),
        ),
        widthPx = 800,
        heightPx = 100,
    )

    assertTrue("Rain label should be hidden when it crosses into the top forbidden zone", labels.isEmpty())
}
```

## Manual Verification
1. Open the **Emulator** (Generic Foldable API 36).
2. Set a day with 60%+ rain chance.
3. Observe that the "60%" text appears closely above the high temperature.
4. Verify it does not overlap with the "Tue 21" date text at the very top.
