# Plan - Fix Missing Start Label and Day Label Collision

Ensure the hourly temperature graph always displays its boundary labels (START, END) and that day labels do not overlap them.

## Objective
The forecast start label is missing, and the day label is appearing in its place. This plan makes boundary labels "essential" so they always draw first, and ensures day labels only draw in available, non-overlapping positions.

## Key Files & Context
- **`app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`**

## Implementation Steps

### 1. Update Label Essentiality in TemperatureGraphRenderer
- Update `isEssential` logic to include boundary and transition roles:
  ```kotlin
  val isEssential = candidate.role == "LOW" || candidate.role == "HIGH" || 
                    candidate.role == "FORECAST_LOW" || candidate.role == "FORECAST_HIGH" ||
                    candidate.role == "START" || candidate.role == "END" || 
                    candidate.role == "ACTUAL_END"
  ```
- This ensures these labels draw even if they have a collision on their fallback side. Since they draw *before* day labels, they will reserve their space in `drawnLabelBounds`.

### 2. Verify Day Label Logic
- Ensure the day label placement loop correctly tries TOP -> MID -> BOTTOM.
- Confirm that if all three positions collide with existing labels (including the now-essential START/END labels) or icons, the day label is skipped.
- (The current implementation already follows this "skip if no space" logic, so we will primarily verify this during review).

### 3. Maintain Gaps
- Do NOT change `aboveGap` or `belowGap` (keep them at `dpToPx(context, -0.1f)`).

## Verification & Testing

### Manual Verification
- Deploy to emulator.
- Observe the hourly temperature graph.
- Verify that the start of the forecast line has a temperature label.
- Verify that day labels do not overlap any temperature labels or icons.
- Check that if space is extremely tight, a day label may be omitted rather than drawn in a colliding position.

### Automated Testing
- Run all unit tests.
- Update `TemperatureGraphLabelPlacementRobolectricTest` if needed to reflect that START/END are now forced labels.
