# Fix Precipitation Graph Overlay Collisions And Rain Amount Scope

## Summary
Update `PrecipitationGraphRenderer` so overlay layout is computed from one consistent set of bounds, eliminating drift between layout-time and draw-time placement. Change precipitation-amount behavior to show a single amount for the entire visible graph window rather than per contiguous rain block.

## Key Changes
- Make `NOW` label placement deterministic across layout and render.
- Include probability labels, rain labels, `NOW`, and day labels in one overlay collision system before watermark placement.
- Change default rain-amount semantics to one total for the visible graph window.
- Keep `rainAmountWindowHours > 0` as an explicit override mode.

## Public/API/Type Changes
- Extend precipitation renderer layout/debug types with resolved `NOW` and day-label placement data.
- Add shared day-label placement computation in `GraphRenderUtils` so placement and drawing use the same logic.

## Test Plan
- Verify rain-label placement avoids the same `NOW` bounds that are rendered.
- Verify watermark placement avoids final day-label and `NOW` bounds.
- Update precipitation amount tests so default graph behavior expects one visible-window amount.
- Re-run the focused precipitation renderer test suite.

## Assumptions
- Default precipitation-graph amount scope is the entire visible graph window.
- `rainAmountWindowHours > 0` remains an override mode.
