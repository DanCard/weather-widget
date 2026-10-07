# Hourly graph: observed-low label that the NOW dot already states (plateau into NOW)

## Symptom (desktop, 2026-10-07 07:15)
Hourly graph shows a pink "60.6°" under the curve near 6a, on the hour-label row, beside the NOW
dot's "60.9°". User: "60.6 temp label not helpful."

## Evidence
Desktop log `autostart-20261007-031748.log`:
`LabelAccepted: displayed="60.6" t=05:55 role=ACTUAL_LOW … val=60.647 idx=130` with the NOW dot at
idx 145 (~07:10). The NOW dot reads 60.9°.

## Root cause
4ac67c2d drops an ACTUAL_HIGH/LOW/END label within 1 °F of the dot reading only when it is **within
12 dp** of the dot. Here the low is 75 min (≈118 px) left of the dot, so it survives. It still says
nothing new: from 05:55 to now the observed line never leaves the 60.6–60.9 band, so the dot is
reporting the same low. A 0.3° difference is not worth a label.

## Fix (shared `LabelGeometryResolver`, both platforms)
Replace the distance condition for the actual-series same-reading case with a **plateau** condition:
drop ACTUAL_HIGH/LOW/END when `|value − dot reading| < FETCH_DOT_SAME_READING_DEGREES` (1 °F) **and**
every observed sample from the extremum's index to the dot's index stays within that same 1 °F of
the extremum. In that case the curve runs flat into NOW, and the dot already carries the value.
- A low at 02:00 that rose 3° and came back down to the dot's reading is a separate event and keeps
  its label (the plateau test fails).
- The exact-text + 12 dp rule for forecast labels is unchanged.
- Log `LabelSuppressed reason=FETCH_DOT_PLATEAU spanIdx=… maxDev=…`.

## Tests
Extend `FetchDotSameReadingSuppressionTest`:
- Report case: low 60.65 at idx 130, flat ≤ 61.0 through dot at idx 145 reading 60.9 → dropped (fails today).
- Dip-and-recover: low 60.6 at idx 100, 64° at idx 120, dot 60.9 at idx 145 → kept.
- Low 59.5 vs dot 60.9 (Δ1.4) → kept.

## Verify
Rebuild and restart the desktop (`scripts/buildStart-desktop.sh`); log shows
`reason=FETCH_DOT_PLATEAU` for the 60.6 label and the popup no longer draws it. Android widget
shares the rule; spot-check emulator.
