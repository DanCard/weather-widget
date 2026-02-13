# Task Plan - Surface Rain Tomorrow Information

## Goal
Improve the weather widget to clearly indicate when rain is expected tomorrow (or the next significant rain event within 48 hours).

## Proposed Ideas
1.  **"Next Rain" Alert (Recommended):** Dynamically update the `precip_probability` field to show the next rain event if today is dry. Example: "Rain tomorrow 2p" or "Rain 10p".
2.  **Enhanced Daily Forecast:** Add precipitation percentage under the tomorrow/future day icons in the daily view.
3.  **Rain Banner:** Add a small text indicator if rain is expected in the next 48 hours.

## Phases
- [x] Phase 1: Understand current architecture and data handling (Complete)
- [ ] Phase 2: Propose ideas to the user (In Progress)
- [ ] Phase 3: Implement "Next Rain" utility and UI updates
- [ ] Phase 4: Verify with tests/emulator

## Progress Tracking
- [2026-02-12] Analyzed `DailyViewHandler`, `HourlyViewHandler`, and `PrecipViewHandler`.
- [2026-02-12] Identified `precip_probability` TextView as a good candidate for surfacing this info.
- [2026-02-12] Initialized planning files.

## Errors Encountered
None so far.
