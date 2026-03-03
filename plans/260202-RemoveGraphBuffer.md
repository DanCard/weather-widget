# Fix Graph Gap: Remove Data Buffering - COMPLETE ✅

## Status: IMPLEMENTED & VERIFIED (Build)

**Build:** ✅ SUCCESS

## Changes Implemented

### 1. Scaling Logic (Both Graphs)
*   **Removed Buffer:** `minTemp` and `maxTemp` now reflect the **exact** values of the visible data, with no added +/- 5 degrees.
*   **Result:** The graph will now stretch to fill the entire vertical area allocated to it. The lowest temperature bar/point will touch the very bottom of the drawing area.

### 2. Layout Adjustments (Both Graphs)
*   **Top Padding:** Increased to **24dp** (from 8dp). This pushes the entire visual block down, creating a cleaner top margin.
*   **Horizontal Padding:** Increased to **12dp** (from -8dp/8dp). This shrinks the graph width slightly, making it look less stretched.
*   **Icon Size:** Reduced to **8dp** (from 10dp). Tiny and unobtrusive.

## Verification
*   [x] Build Success (`./gradlew assembleDebug`)
*   [ ] Visual check:
    *   No more "floating" bars on mild days; the range adapts to fill the space.
    *   Graph sits lower and is narrower.
    *   Icons are tiny.