# Task Plan

## Goal
Fix the UI update scheduling regression in `UIUpdateReceiver` and add test coverage for the observation timestamp/dot pipeline (unit + Robolectric-focused coverage).

## Phases
1. `completed` Patch `UIUpdateReceiver` so screen-off path still preserves scheduling continuity.
2. `completed` Add/adjust tests for receiver scheduling behavior.
3. `completed` Add unit tests for `ObservationResolver` source/time selection.
4. `completed` Add renderer test coverage for observation-dot draw gating (non-null timestamp).
5. `completed` Run targeted tests and summarize outcomes.

## Notes
- Edits were kept scoped to receiver + tests only.
