# Refine Visuals: Daily & Hourly Refinements

## Problem
1.  **Daily Layout:** Floating icons/text are too close to the bars; bars are too thick overall; historical bars lack emphasis.
2.  **Hourly Layout:** "Crappy" appearance; missing labels; text too large; too much empty space at the top.

## Goal
1.  **Daily:** Thinner bars, bolder history, more padding for floating elements.
2.  **Hourly:** Reclaim top space, fix label visibility, smaller fonts.

## Implementation Plan

### 1. `TemperatureGraphRenderer.kt` (Daily)
*   **Top Padding:** Reduce `16f` -> **`8f`**.
*   **Bar Width:** Reduce base `barWidth` from `3f` to **`2.2f`**.
*   **History Emphasis:**
    *   Set `historyBarPaint` and `historyCapPaint` stroke width to `barWidth * 1.8f`.
*   **Floating Spacing:**
    *   Increase gap between bar and icon: **4dp**.
    *   Increase gap between icon and low temp: **4dp**.

### 2. `HourlyGraphRenderer.kt` (Hourly)
*   **Top Padding:** Reduce `16f` -> **`8f`**.
*   **Font Size:** Reduce temp label base size from `10f` to **`9f`**.
*   **Label Visibility:**
    *   Reduce `minHourLabelSpacing` from `28f` to **`22f`** to ensure more labels fit on narrow widgets.
*   **Curve Thickness:** Keep at `2f`.

### 3. `WeatherWidgetProvider.kt`
*   **Hourly Interval:** Set `labelInterval` to **2** for all widgets with 3+ columns to maximize information.

## Verification
*   Verify code compiles.
*   Visual verification on 2x4 and 3x4 widgets.
