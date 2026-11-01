# Daily header rain label tap opens the rain graph

Date: 2026-11-01. Status: fixed and installed on all four devices; not committed.

## User prompts

1. "clicking on rain chance label in header row should take us to rain chance hourly graph. Make
   sure there is an android integrated test for this."
2. "Works on desktop , fails on fold 4. Takes me to temperature graph instead."
3. "go ahead"
4. "write above recap to summaries/"

## Result

Tapping the rain % on the daily header now opens the hourly rain graph, confirmed by real taps on
the Fold 4.

## Root cause

The daily header is painted into graph_view's bitmap, and the tap zones are invisible RemoteViews
laid over it. `DailyGraphRenderer` marked the delta and "from yest" views `INVISIBLE` even when no
delta was painted. `INVISIBLE` keeps a view's width, so the rain tap zone was pushed off the label.
The line dates from `ef9ae85f`.

The rain route itself was fine: the failed tap logged `ACTION_TOGGLE_VIEW`, not
`ACTION_TOGGLE_PRECIP`. Desktop was unaffected because Compose attaches the click to the text.

Daily-view positions before the fix:

| Device | Painted rain label (x) | Temperature zone (x) | Rain zone (x) |
|---|---|---|---|
| Fold 4 | 445–535 | 188–550 | 550–664 |
| Emulator | 313–392 | 77–456 | 456–564 |

The bug was not Fold-only: a tap on the painted label on the emulator also fired
`ACTION_TOGGLE_VIEW`.

## Fix

A hidden header view now holds space only if the bitmap painted that piece.

- `DailyForecastHeaderRenderer.resolvePaintedCluster` reports the painted pieces, using the same
  conditions as `drawHeader`.
- `DailyGraphRenderResult.paintedHeader` carries that report.
- `DailyGraphRenderer` sets each view to `INVISIBLE` if painted and `GONE` if not.

## Tests

- **New:** `DailyHeaderRainLabelTapIntegrationTest` binds a real widget in a real `AppWidgetHost`,
  at Fold and phone widths, with and without a delta. It finds the % by its pixels in the rendered
  bitmap, resolves taps the way touch dispatch does, and waits for the real receiver to switch to
  `PRECIPITATION`.
- **Replaced:** `PrecipTouchZoneInstrumentedTest` now makes real assertions on real views; it
  previously checked only an intent it built itself.

MockK could not run on the device (`libmockkjvmtiagent.so` missing), which is why the test uses a
real host.

## Verification

| Check | Result |
|---|---|
| Fold: tap left and right edges of the label | Both fired `ACTION_TOGGLE_PRECIP`; rain graph opened |
| Fold: tap the temperature | Still opens the temperature graph |
| New test on the old code | Failed in all 4 cases |
| New test on the fixed code | Passed in all 4 cases on both emulators |
| Full gate (`staggered-tests.sh --install`) | Exited 0; 113/113 on each emulator |

The Fold check ran with Open-Meteo at 47% and a delta showing. The original failure had no delta;
that case is covered by the emulator test at Fold dimensions, not by a Fold tap.

## Open observations

- On the Fold the rain zone ended about 1 px short of the label's right edge (607 vs 608). The tap
  at x=595 worked.
- A tap at x=570 on the rain graph returned to daily through `ACTION_SET_VIEW` the first time and
  `ACTION_TOGGLE_PRECIP` the second. Not investigated; both reach daily.
- One tap at x=400 on the temperature graph left no log entry, though it was inside the temp zone.
  It came about 2 seconds after the previous tap. Not investigated; it could be an adb artifact.
- A second tap on the rain label while on the rain graph toggles back to daily on Android, while
  desktop stays on the rain graph. Left unchanged.

The Fold widget was returned to the daily view afterwards, confirmed in the log.
