# Hourly Temperature Graph Series Colors

## Summary
- Color the actual solid line with a soft yellow-gold.
- Color the forecast dashed line with a muted cool blue.
- Tint key temperature labels by series so actual-backed labels read warm and forecast-backed labels read cool.
- Leave the fill, hour labels, day labels, and NOW indicator unchanged.

## Implementation Changes
- In `TemperatureGraphRenderer`, replace the shared line gradient styling with separate solid paints:
  - actual line: warm yellow-gold solid stroke
  - forecast line: cool blue dashed stroke
- Split key temperature label paints into actual and forecast variants.
  - Actual-series labels (`HIGH`, `LOW`, `START`, `END`, actual locals) use the warm label paint.
  - Forecast-series labels (`FORECAST_HIGH`, future-only labels) use the cool label paint.
- Keep line widths, dash pattern, fill shading, and placement math unchanged.
- Extend label debug output so tests and log review can confirm the chosen label color family.

## Test Plan
- Add/update Robolectric coverage to verify:
  - actual and forecast highs both exist when peaks differ
  - the actual high uses the actual color family
  - the forecast high uses the forecast color family
- Re-run targeted temperature graph unit/Robolectric tests.
- Manually verify on emulator that the full render shows:
  - yellow-gold actual line
  - blue dashed forecast line
  - yellowish actual labels and bluish forecast labels

## Assumptions
- Use a subtle warm/cool split rather than saturated colors.
- Keep the existing graph fill and header coloring so the widget does not become visually noisy.
