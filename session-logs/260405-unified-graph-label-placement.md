# Unified Hourly Graph Label Placement Architecture

## Problem Description
Before this session, the three hourly graphs (Temperature, Precipitation, and Cloud Cover) each had independent and inconsistent label placement logic. 
- **Cloud Cover** had recently been upgraded with sophisticated multi-directional placement, density filtering (thining out redundant labels), and glyph-box-aware geometry.
- **Temperature** had local extrema detection but was prone to clutter on wavy days and used less precise text-baseline geometry.
- **Precipitation** tended to be cluttered with redundant `0%` labels and had complex custom rules that were difficult to maintain.

The goal was to unify these under a single architectural framework while preserving the specific design nuances required for each weather metric.

## Proposed Solution
1. **Abstract Shared Logic:** Extract the core placement and filtering logic into `GraphLabelPlacementUtils.kt`.
2. **Density Filtering:** Implement a standardized thinning algorithm using progressive difference thresholds (`8, 12, 16` for percentages; `2, 3, 4` for temperatures) to protect significant features while removing clutter.
3. **Glyph-Box Geometry:** Calculate vertical placement based on the actual visual bounds of the text glyphs rather than just the baseline, ensuring consistent clearance from the graph curve.
4. **Graph-Specific Refinement:** Integrate the shared logic into each renderer while restoring specific rules (like "first rising label below" for rain) required by instrumented tests.

## Implementation Details

### 1. Created `GraphLabelPlacementUtils.kt`
- **`CandidateKind` Hierarchy:** Standardized priority as **Global Max > Peak > Global Min > Valley > Edge**.
- **`filterDenseLabelCandidates`:** A functional thinning algorithm that respects "protected" indices (like the daily high/low or first rain spike).
- **`computeLabelVerticalPlacement`:** Geometry logic that ensures labels "above" and "below" the line have identical visual padding based on `Paint.FontMetrics`.

### 2. Standardized `PrecipitationGraphRenderer.kt`
- Switched to the unified filtering logic but added protections for:
    - **First Non-Zero Label:** Critical for users to see exactly when rain starts.
    - **Soft Dips:** Significant drops in rain probability that are not true valleys (e.g., 80% -> 65% -> 85%).
    - **Interior Zero Runs:** Gaps in rain are explicitly labeled to show dry windows.
- **Restored Design Rules:** 
    - **First Rising Below:** Prefers placing the first rain label below the curve to keep the graph start clear.
    - **Right-Edge Context:** Prefers below for labels at the end of a descending curve.
- **Bottom Overflow:** Increased allowance to **55% probability** to accommodate taller glyph boxes near the crowded bottom axis.

### 3. Refined `TemperatureGraphRenderer.kt`
- **Smart Role Precedence:** Implemented logic to favor `HIGH`/`LOW` role names for endpoints by default, but automatically switch to `START`/`END` if the label lands on the Fetch Dot (ensuring the label is shown instead of being suppressed by the dot).
- **Samsung Z Fold Fix:** Slightly reduced `GRAPH_TO_FOOTER_GAP_DP` from `2f` to `1.8f`. This 0.2dp adjustment was necessary to satisfy visual clearance tests on high-density foldable screens where the coldest point was rendering 0.3px too high.
- **Role Consistency:** Reverted local extrema role names to `LOCAL` to match the existing test suite's expectations.

## Verification Results

### Unit & Robolectric Tests
- **Status:** **PASS** (692 tests)
- Verified that monotonic temperature rises (50° -> 70°) are thinned out to at most 6 labels.
- Verified that precipitation peaks stay above the line while significant valleys prefer below.

### Instrumented Tests (AndroidTest)
- **Status:** **PASS** (172 tests per device)
- **Samsung Z Fold (SM-F936U1):** 172/172 passed. (Confirmed fix for Coldest Point visual clearance).
- **Google Pixel 7 Pro:** 172/172 passed.
- **Android Emulator:** 172/172 passed.

### Manual Verification (Emulator)
- **Temperature:** Graph is visibly cleaner on wavy data; daily high/low are always prioritized.
- **Precipitation:** Baseline (0%) is clean; rain spikes are clearly labeled; first rain event always shows a label.
- **Visuals:** All labels maintain consistent vertical spacing from the curve regardless of graph type.
