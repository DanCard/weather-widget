# Prevent stale temperature refinements from overwriting daily mode

## Summary
- Stop deferred temperature-view header refinements from applying after the widget has already switched back to daily or another non-temperature mode.
- Add a regression test that reproduces the stale partial-update path and verifies it is ignored.

## Key Changes
- Add a mode check in `TemperatureViewHandler` before applying the deferred `partiallyUpdateAppWidget()` header refresh.
- Add an explicit cancel hook for pending current-temp refinement jobs and invoke it when view mode leaves `TEMPERATURE`.
- Keep current-temperature math and formatting unchanged.

## Test Plan
- Robolectric regression test for the stale refinement race:
  - render temperature mode with deferred refinement enabled,
  - switch to daily before the refinement finishes,
  - assert the late partial update does not overwrite the daily render.
- Sanity check that the refinement still applies when the widget remains in temperature mode.

## Assumptions
- A view switch should always win over any late temperature-mode partial update.
- The user-visible current temperature should stay unchanged when a view renders normally; this fix only blocks stale cross-view overwrites.
