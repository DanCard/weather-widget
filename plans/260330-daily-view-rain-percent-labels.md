# Daily View Rain Label Placement

## Summary

Add per-day rain labels to the daily graph for rainy forecast days, using the same blue text treatment as the header rain indicator. For each non-today rainy day, show either chance of rain or rain amount, preferring placement above the high label, then below the low label, and omitting it if neither fits.

## Key Changes

- Replace the current single-rain-label behavior in daily graph prep/rendering with per-day rain annotations.
- Define a new per-day display payload for the graph instead of reusing `rainSummary` alone:
  - `dailyRainLabelText: String?`
  - `dailyRainLabelKind: CHANCE | AMOUNT`
  - `shouldShowDailyRainLabel: Boolean`
- Compute `shouldShowDailyRainLabel` only when all of these are true:
  - the day is not `today`
  - the day’s icon resolves to a rain indicator icon
  - the day is forecast/current-future, not past history
  - there is label content to show
- Compute label content in `DailyViewLogic.prepareGraphDays` with this precedence:
  - if daily precip probability is `100` and a derived precip amount is available, show amount
  - otherwise show chance as percent
  - if neither is available, omit
- Keep text mode unchanged unless explicitly requested later. The request is about the daily forecast graph layout.
- Remove the current graph-only single-label gate and the below-low-only placement rule.

## Data / Interface Changes

- Extend `ForecastEntity` and `HourlyForecastEntity` with nullable precipitation amount fields.
- Bump Room DB version and add a migration adding nullable amount columns to `forecasts` and `hourly_forecasts`.
- Extend provider DTOs and repository mapping so providers populate amount when available and leave it null otherwise.
- For NWS daily aggregation, derive daily amount from hourly/period data during forecast construction if the source exposes enough precipitation quantity data; otherwise leave null.
- For providers that only expose probability, keep amount null and fall back to percentage even at `100%`.

## Rendering Rules

- Match header rain styling:
  - same forecast blue color
  - same general compact typography treatment
  - same droplet-prefixed text style unless the current header style has changed by implementation time, in which case reuse the live header formatter/colors rather than duplicating constants
- Placement for each eligible day:
  - try above the high temp first
  - if the label would collide with top bounds or nearby graph content, try below the low temp
  - if that also collides with bottom bounds, icon stack, or day label area, omit
- Placement checks should use measured text width/height from the graph paint, not hard-coded character assumptions.
- Amount formatting:
  - locale-aware units: inches for US/UK-style locales, millimeters otherwise
  - compact form for tight space
  - omit the leading zero for sub-1 values, e.g. `.002in`
  - trim unnecessary trailing zeros
- Percent formatting:
  - compact integer percent, e.g. `35%`, `100%`

## Test Plan

- Unit tests for graph-day preparation:
  - rainy non-today day with `35%` shows `35%`
  - rainy non-today day with `100%` and amount shows amount
  - rainy non-today day with `100%` and no amount falls back to `100%`
  - non-rain icon omits label even if precip probability exists
  - today omits day-level rain label
- Renderer tests:
  - label renders above high when space allows
  - label falls back below low when top placement does not fit
  - label is omitted when neither placement fits
  - multiple rainy days can each render their own label
- Data tests:
  - Room migration preserves existing rows and initializes amount columns to null
  - repository/provider mapping populates amount when source data exists and leaves null otherwise

## Assumptions

- This plan applies to the daily graph renderer, not the 1-row/text daily layout.
- “Rainy day” is determined by the resolved rain weather icon, matching your request.
- When amount data is unavailable from a provider, the UI still shows percentage at `100%` rather than hiding the label.
- Locale-based unit selection is the default: inches for US/UK-oriented locales, millimeters elsewhere.
