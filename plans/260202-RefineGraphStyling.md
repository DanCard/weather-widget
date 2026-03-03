# Refine Graph Styling and Proportions

## Problem
1.  **Proportions:** The graph became too small (aggressive width/height shrinking).
2.  **Gap:** Still too much space above the day labels.
3.  **Styling:**
    *   Bars have "knobs" (T-caps) which are unwanted.
    *   History bar (yellow) is too thick.
    *   Forecast bar (blue line) is too thin.

## Goal
Restore larger graph dimensions, remove all horizontal caps ("knobs"), and balance the bar weights.

## Implementation Plan

### 1. `TemperatureGraphRenderer.kt` (Daily)
*   **Dimensions:**
    *   `topPadding`: Reduce from `24f` to **`16f`**.
    *   `horizontalPadding`: Reduce from `12f` to **`4f`**.
*   **Spacing:**
    *   Reduce `attachedStackHeight` padding further.
*   **Bar Styling:**
    *   **REMOVE CAPS:** Delete all `canvas.drawLine(centerX - capHeight, ...)` calls for caps.
    *   **History Bar:** Change `strokeWidth` from `barWidth * 1.8f` to **`barWidth * 1.1f`**.
    *   **Forecast Bar:** Change `strokeWidth` from `barWidth * 0.5f` to **`barWidth * 0.8f`**.

### 2. `HourlyGraphRenderer.kt` (Hourly)
*   **Dimensions:**
    *   `topPadding`: Reduce from `24f` to **`16f`**.
    *   `horizontalPadding`: Reduce from `12f` to **`4f`**.

## Verification
*   Verify code compiles.
*   Visual check:
    *   Wider/taller graph.
    *   Simple vertical lines without "T" bars at top/bottom.
    *   Better balance between yellow (history) and blue (forecast) lines.
