# Findings for CR #6: TemperatureGraphRenderer Extraction

## Requirements from notes/260408-code-review-temperature-graph-renderer.md
- The 1573-line (now ~1604) singleton object mixes: scaling, layout, path computation, gradient building, label placement, day labels, fetch dot rendering, icon drawing, extrema detection.
- Extract self-contained sections into separate files.
- **Mechanical approach only**: Move code blocks, no optimizations, no logic changes, no refactoring beyond what's needed for the move (e.g. delegation, imports, visibility).
- Implement **in steps**, run tests after **each step**.
- Afterwards, make recommendations for optimization/cleanup (do not implement yet).

## Research Findings
- File has 1 main `object TemperatureGraphRenderer` with ~34 private functions, several data classes (HourData, RenderContext, Layout, PaintSet, TemperatureRole, LabelCandidate, etc.).
- Many functions are self-contained but share state via RenderContext (34 fields - see #7).
- Tests exist: TemperatureGraphRendererLabelPlacementTest.kt, FetchDotTest, ActualsTest, WapiTest, StalenessTest, ContinuityTest.
- Previous fixes applied (from catchup): #1, #2, #8, #10, #11 already done in prior session.
- Main entry: `fun renderGraph(...)` at line ~1505 orchestrates everything.
- Label placement is the largest method (~460 lines, lines 959+), also targeted by #5.
- tempToY and other helpers duplicated/repeated (but #10 already addressed per review).

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Extract to new objects in same package (e.g. TemperatureUtils, GraphLayoutCalculator, GraphPathBuilder, TemperatureLabelPlacer, FetchDotRenderer) | Mechanical move: functions become members of new objects; main renderer calls `TemperatureUtils.tempToColor(...)`; minimal signature changes |
| Keep all original private consts with their functions | No optimization, just relocation |
| Use `internal` visibility where needed for test access | Matches existing internal funs like isMinorOverlapEligible |
| Delegate via static-like object calls | Avoids changing RenderContext or adding instances unless necessary; keeps mechanical |
| Run `./gradlew test --tests "*TemperatureGraphRenderer*Test"` after each extraction | Verifies no breakage per user request; focus on unit tests first (avoids emulator) |
| Update main object to remove moved code and add imports/delegations | Required for compilation after move |
| Do not touch performance items (#12-14) or dead code (#3-4) yet | Stick strictly to #6 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Large file makes full reads expensive | Used targeted grep/read with offsets; planning files for memory |
| Shared RenderContext couples everything | Will extract context-aware methods to take ctx as param where possible (mechanical) |
| Test dependencies on internal functions | Keep some functions internal and in same package |

## Resources
- notes/260408-code-review-temperature-graph-renderer.md
- app/src/test/java/com/weatherwidget/widget/TemperatureGraphRenderer*.kt (6 test files)
- AGENTS.md for testing conventions (use emulator-tests.sh only if needed; prefer unit tests)

**Next:** Proceed to Phase 2 - extract utilities first as it's lowest risk.
