# Task Plan: Diagnose and fix graph visibility on emulator 5556 and Pixel 7 Pro (2 rows)

## Goal
Diagnose why graphs are not shown on emulator 5556 and Pixel 7 Pro when the widget is 2 rows high and implement a fix, ensuring verification with tests.

## Current Phase
Phase 3: Implementation

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Identify constraints and requirements
- [x] Investigate codebase for widget sizing and graph visibility logic
- [x] Document findings in findings.md
- **Status:** complete

### Phase 2: Planning & Structure
- [x] Define technical approach (P=25 for rows, P=15 for columns)
- [x] Refine approach for Pixel 7 Pro (Use raw threshold 1.4f instead of integer comparison)
- [x] Propose a fix based on discovery
- [x] Get explicit consent from the user before execution
- **Status:** complete

### Phase 3: Implementation
- [x] Update WeatherWidgetProvider.kt with 1.4f threshold
- [x] Update/Add unit tests for the 1.4f threshold logic
- [x] Verify fix with tests
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Run all relevant tests (unit)
- [x] Verify visual correctness via Pixel 7 Pro logs
- [x] Fix any issues found
- **Status:** complete

### Phase 5: Delivery
- [x] Review all output files
- [x] Ensure deliverables are complete
- [x] Deliver to user with explanation
- **Status:** complete

## Key Questions
1. How does the widget determine whether to show the graph based on row height?
2. Why does it specifically fail on emulator 5556 and Pixel 7 Pro?
3. How can we reliably test "2 rows high" programmatically?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use planning-with-files | Complex task requiring multiple steps and discovery |
| Use teach and explain mode | User request |
| Set row padding P=25 | Base value for rounding logic. |
| Use rawRows >= 1.4f | Pixel 7 Pro reports 107dp (1.46 rows). Rounding up requires P=28, which breaks Foldable (jumps to 3 rows). |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
|       | 1       |            |

## Notes
- Project uses Kotlin and Gradle.
- Widget uses RemoteViews.
- Pixel 7 Pro reports minHeight=107dp for 2 rows.
- Foldable reports minHeight=198dp for 2 rows.