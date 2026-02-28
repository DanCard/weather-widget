# Weather Widget Feature Summary: Decimal Precision & Graph Label Optimization
**Date:** February 28, 2026

## 1. High-Precision Forecast Snapshots
Preserved full decimal precision for current and historical forecasts to improve the accuracy of reliability calculations and "triple line" graph rendering.

### Key Changes
- **`ForecastRepository.kt`**: Modified `saveForecastSnapshot` to retain raw `Float` precision for today's forecast entries (e.g., `72.4°F` instead of `72°F`).
- **`DailyViewLogic.kt` & `DailyForecastGraphRenderer.kt`**: Updated UI labeling to allow decimal display for today and historical days. 
- **Consistency**: Future forecast days (tomorrow and beyond) remain rounded to integers in both the database and UI to maintain a clean, visual look.
- **Verification**: 
    - `ForecastRoundingTest.kt`: Unit test for conditional rounding logic.
    - `OpenMeteoIntegrationTest.kt`: Integration test for the Network -> Database pipeline.
    - `DailyViewUiRoundingTest.kt`: Integration test for Database -> UI precision handling.
    - `TripleLinePrecisionTest.kt`: End-to-end verification of high-precision data in graph estimators.

## 2. Hourly Graph Day Label Optimization
Implemented an intelligent, priority-based placement system for day labels (e.g., "Mon", "Tue") on both the Temperature and Precipitation hourly graphs.

### Logic Strategy
1. **Top Tier**: Attempt placement at the very top of the graph zone. Success requires NO collision with:
    - The temperature/probability curve (including nearby curve segments).
    - Weather icons or value labels.
    - The "NOW" indicator marker.
2. **Middle Tier**: If the top is blocked, attempt placement in the middle area (above navigation arrows, roughly `heightPx/2 - 42dp`).
3. **Bottom Tier (Fallback)**: If both higher spots are blocked, place the label at the very bottom (original location), ignoring collisions to ensure the date remains visible.

### Improvements
- **`GraphRenderUtils.kt`**: Centralized the placement logic into a reusable `drawDayLabels` method for consistent behavior across all hourly views.
- **Leading Labels**: The same priority logic is applied to the leading day label that appears at the leftmost edge of the graph when it doesn't start at 8 AM.
- **Visual De-cluttering**: Collision detection ensures labels do not overlap with critical UI elements, improving readability during extreme weather events.

---
*Summarized by Gemini CLI*
