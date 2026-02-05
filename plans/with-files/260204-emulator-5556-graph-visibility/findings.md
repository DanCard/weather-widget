# Findings & Decisions: Emulator 5556 Graph Visibility

## Requirements
- Diagnose and fix graph visibility issue on emulator 5556 at 2 rows high.
- Use teach and explain mode.
- Get explicit consent after plan is derived.
- Include tests for the issue.

## Research Findings
- **Current Row Calculation Logic**:
  ```kotlin
  val rows = ((minHeight + 15).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)
  ```
  with `CELL_HEIGHT_DP = 90`.
- **Row Thresholds (Current)**:
  - 1 row: `minHeight` < 120dp (e.g., 110dp + 15 = 125. 125/90 = 1.38 -> rounds to 1)
  - 2 rows: `minHeight` >= 120dp (e.g., 120dp + 15 = 135. 135/90 = 1.5 -> rounds to 2)
- **Historical Context**:
  - `plans/Fix-Sizing-Detection-For-2-Rows.md`: Mentioned `minHeight=137dp` for a 2-row widget.
  - `plans/Fix-row-Sizing-Detection-260202.md`: Mentioned Foldable (emulator 5556) reports `minHeight=198dp` for 2 rows.
  - The padding was reduced from 30 to 15 to prevent 198dp from rounding up to 3 (`(198+30)/90 = 2.53` -> 3).
- **The Gap**:
  - Many Android launchers use `(70 * N) - 30` for `minHeight`. For 2 rows, this is `110dp`.
  - At `110dp`, the current formula (`+15`) results in 1 row.
  - **Pixel 7 Pro Discovery**: The Pixel 7 Pro (2A191FDH300PPW) reports `minHeight=107dp` for a 2-row widget.
  - At `107dp`, even `+25` padding results in `1.46` rows, which rounds down to 1.
  - **Conflict**: Pixel 7 Pro needs `P >= 28` to round up to 2. However, Foldable (198dp) needs `P < 27` to stay at 2 rows (otherwise jumps to 3).

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Use raw floating-point threshold | Instead of integer rounding for visibility, use `rawRows >= 1.4f`. This allows the Pixel 7 Pro (1.46) to show the graph while keeping the Foldable at 2 rows. |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| | |

## Resources
- `WeatherWidgetProvider.kt` (lines 630-645)
- `plans/Fix-row-Sizing-Detection-260202.md`
- `plans/Fix-Sizing-Detection-For-2-Rows.md`

## Visual/Browser Findings
-
-

---
*Update this file after every 2 view/browser/search operations*
*This prevents visual information from being lost*
