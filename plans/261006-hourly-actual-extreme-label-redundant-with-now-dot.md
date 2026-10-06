# Hourly graph: observed high/low label redundant with the NOW-dot reading

## Report (user, 2026-10-06, emulator)

Hourly temperature view, 4:33 pm: "84.5 actual temp label not helpful." The pink `84.5°`
(`ACTUAL_HIGH`, 15:55 observation, `val=84.52346 idx=50`) sits directly above the NOW dot, which
prints its own `84.4°`. One observed plateau, labeled twice, 0.1° apart.

## Cause

`LabelGeometryResolver.resolve` already drops a label that duplicates the fetch-dot value label —
but only on an **exact printed-text match** (`label == fetchDotLabel`, within 12 dp of the dot).
`"84.5°" != "84.4°"`, so a 0.12° difference slipped through. The resolver-level redundancy gate
(`checkRedundantPairSuppression`) never sees the dot: its label is not a candidate, only a hard bound.

`ACTUAL_HIGH` is deliberately never redundant against a *forecast* high (two series compared side by
side). The NOW dot is different: the **same observed series**, minutes later, at the same x.

## Fix

In `LabelGeometryResolver.resolve`, for actual-series roles (`ACTUAL_HIGH`, `ACTUAL_LOW`,
`ACTUAL_END`) within the existing 12 dp of the dot: redundant when
`|value − lastObservedTemp| < FETCH_DOT_SAME_READING_DEGREES` (1 °F). Other roles keep the exact-text
rule (forecast labels are a different series; only an identical printed number is a duplicate).
The 12 dp horizontal gate is unchanged, so a peak an hour earlier that the line has since fallen from
keeps its label. Log `LabelSuppressed: ... reason=FETCH_DOT_SAME_READING` (VERBOSE).

## Tests

Shared unit test on `LabelGeometryResolver.resolve`:
- ACTUAL_HIGH 84.52 beside dot 84.4 → dropped (the report)
- ACTUAL_HIGH 86.0 beside dot 84.4 → kept (peak meaningfully above now)
- ACTUAL_HIGH 84.52 far from the dot → kept
- FORECAST_HIGH 84.52 beside dot 84.4 → kept (different series, text differs)

## Verification

`:shared:test`, `testDebugUnitTest`, `:desktop:test`; emulator screenshot of the hourly view.
