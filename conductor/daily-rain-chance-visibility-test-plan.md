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

```kotlin
@Test
fun `renderGraph shows rain label when high temp leaves room below 50 percent header limit`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val today = LocalDate.now()
    val day = DailyForecastGraphRenderer.DayData(
        date = today,
        label = "Mon",
        high = 70f,
        low = 50f,
        rainData = DailyForecastGraphRenderer.RainData(dailyRainLabelText = "65%")
    )

    val labels = mutableListOf<DailyForecastGraphRenderer.RainLabelDrawnDebug>()
    runBlocking {
        DailyForecastGraphRenderer.renderGraph(
            context = context,
            days = listOf(day),
            widthPx = 800,
            heightPx = 500, // Sufficient height
            onRainLabelDrawn = labels::add
        )
    }

    assertEquals("Rain label should be shown on emulator-like dimensions", 1, labels.size)
    assertEquals("ABOVE_HIGH", labels.first().placement)
}

@Test
fun `renderGraph hides rain label when it would cross the 50 percent header forbidden zone`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val today = LocalDate.now()
    // By using a very small height, we trigger the tight layout logic
    // Or we can verify the resolveRainAboveHighPlacement logic directly
}
```

## Manual Verification
1. Open the **Emulator** (Generic Foldable API 36).
2. Set a day with 60%+ rain chance.
3. Observe that the "60%" text appears closely above the high temperature.
4. Verify it does not overlap with the "Tue 21" date text at the very top.
