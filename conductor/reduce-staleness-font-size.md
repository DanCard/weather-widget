# Plan - Reduce Font Size for Staleness Indicator in Fetch Dot Labels

Halve the font size of the staleness indicator (e.g., "(12m)") within the "Last Fetch Dot" label while keeping the value part (e.g., "72.1°") at standard size.

## Objective
The current fetch dot label (e.g., "72.1° (12m)") uses a uniform font size. This plan reduces the size of the age component to 50% of the value component for better visual hierarchy and a more refined look.

## Key Files & Context
- **`TemperatureGraphRenderer.kt`**
- **`PrecipitationGraphRenderer.kt`**
- **`CloudCoverGraphRenderer.kt`**

## Implementation Steps

### 1. Refactor Rendering in TemperatureGraphRenderer
- Modify the fetch dot drawing logic in `renderGraph`.
- Create two `Paint` objects:
  - `valuePaint`: Uses standard size (e.g., `19.5f * labelScale`).
  - `stalenessPaint`: Uses 50% size (e.g., `9.75f * labelScale`) and slightly higher transparency (e.g., `#88FFFFFF`).
- Calculate individual text widths: `valueWidth` and `stalenessWidth`.
- Compute `totalWidth = valueWidth + gap + stalenessWidth`.
- Adjust `finalX` calculation to use `totalWidth`.
- Draw both parts sequentially:
  - `canvas.drawText(valueText, finalX, textY, valuePaint)`
  - `canvas.drawText(stalenessText, finalX + valueWidth + gap, textY, stalenessPaint)`
- Handle right-alignment case (when dot is near the right edge) by shifting both starting points accordingly.

### 2. Update PrecipitationGraphRenderer
- Apply the same dual-paint drawing logic.
- Standard size: `11.0f`. Staleness size: `5.5f`.

### 3. Update CloudCoverGraphRenderer
- Apply the same dual-paint drawing logic.
- Standard size: `11.0f`. Staleness size: `5.5f`.

## Verification & Testing

### Manual Verification
- Deploy to an emulator/device.
- Observe the fetch dot label on all three graph types.
- Confirm the age text is noticeably smaller than the value text.
- Verify horizontal alignment remains correct when the label is on either side of the dot.

### Automated Testing
- Note: MockK `verify` calls that expect a specific full string will likely fail.
- Update `TemperatureGraphRendererStalenessTest` and `TruthCurveLinearRenderingTest` to verify the individual text components instead of the unified string.
