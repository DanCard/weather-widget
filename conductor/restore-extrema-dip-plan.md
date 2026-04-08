# Plan - Fix Temperature Label Collision Priority

Address the issue where colliding temperature labels on the hourly graph are placed in the wrong vertical order. Specifically, ensure that the more extreme temperature (higher for peaks, lower for valleys) occupies the more extreme position (top for peaks, bottom for valleys).

## Objective
Update the sorting logic for temperature label candidates to prioritize "more extreme" values. By placing the most extreme value first in its preferred direction (above for peaks, below for valleys), we ensure it gets the most prominent spot, while less extreme values that collide are displaced to the opposite side or further away.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**: Contains the label placement and sorting logic in `placeTemperatureLabels`.

## Proposed Changes

### 1. Update Candidate Sorting Logic
In `TemperatureGraphRenderer.kt`, modify the `sortWith` block in `placeTemperatureLabels` (around line 723).

**Current Logic:**
```kotlin
if (isPeak) it.rawTemperature else -it.rawTemperature
```
*Problem:* This sorts peaks ascending (lowest first) and valleys descending (highest first), which causes less extreme values to take the preferred spots first.

**New Logic:**
```kotlin
if (isPeak) -it.rawTemperature else it.rawTemperature
```
*Rationale:* 
- **Peaks:** Sorting by `-rawTemperature` (ascending) means higher temperatures come first. The highest peak gets step 0 `above`. Lower peaks that collide are displaced `below`.
- **Valleys:** Sorting by `rawTemperature` (ascending) means lower temperatures come first. The lowest valley gets step 0 `below`. Higher valleys that collide are displaced `above`.

## Verification & Testing

### 1. Unit Testing
- Create/Run a test case (like the one in `conductor/TemperatureLabelCollisionOrderTest.kt`) that simulates nearby peaks and valleys.
- Verify that for colliding peaks, the higher temperature has a smaller Y coordinate (higher on screen).
- Verify that for colliding valleys, the lower temperature has a larger Y coordinate (lower on screen).

### 2. On-Device Verification
- Deploy to the emulator.
- Observe the hourly graph in situations where forecast and actual extrema are close (e.g., a forecast low of 52 and an actual low of 50).
- Confirm that the 50 label is below the 52 label.
- Confirm that for peaks (e.g., 87 vs 85), the 87 label is above the 85 label.
