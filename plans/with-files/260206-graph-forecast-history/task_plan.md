# Task Plan: Forecast History Mode Toggle

## Goal
Add two graph modes in `ForecastHistoryActivity` for the active API only: `Evolution` and `Error`, with a button to toggle between them.

## Current Phase
Phase 5

## Phases
### Phase 1: Requirements & Discovery
- [x] Confirm requested UX: mode 1 + mode 2 with toggle button
- [x] Confirm API scope: active/requested API only
- [x] Inspect `ForecastHistoryActivity` and `ForecastEvolutionRenderer`
- **Status:** complete

### Phase 2: UI Wiring
- [x] Add mode toggle button to `activity_forecast_history.xml`
- [x] Add IDs needed for dynamic title/legend updates
- [x] Add button behavior and mode state in activity
- **Status:** complete

### Phase 3: Graph Rendering
- [x] Keep current evolution renderer path for mode 1
- [x] Add error-over-time renderer path for mode 2 (`forecast - actual`)
- [x] Show zero reference line for error mode
- **Status:** complete

### Phase 4: Verification
- [x] Compile targeted module/tests
- [x] Validate graph mode switch and active-source behavior
- **Status:** complete

### Phase 5: Delivery
- [ ] Summarize changes and any limitations
- **Status:** in_progress

## Key Questions
1. Should error mode be hidden/disabled when actual data is unavailable for the selected day?
2. Should legend text switch from `Actual` to `Zero Error` in error mode?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Keep graphs source-specific via existing `requestedSource` filtering | Matches user requirement for active API only |
| Use single toggle button to cycle/switch between two modes | Minimal UI change and clear interaction |
| In error mode, show guidance when actual temps are unavailable | Prevents misleading charts for future dates and keeps UX explicit |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| User interrupted turn after first layout patch | 1 | Recorded checkpoint; continue from current workspace state |

## Notes
- `app/src/main/res/layout/activity_forecast_history.xml` already has initial toggle-related UI additions.
