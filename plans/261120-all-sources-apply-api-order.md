# Plan: Apply "All sources" table ordering to API order

## Goal

Add a button to the Forecast Comparison screen's **"All sources for <day>"** card on both Android and Desktop that sets the user's API source order (`enabledSources` / `settings.visibleSources`) to match the table's current sorted order.

## Evidence & existing behavior

- **Shared table (`DaySourceComparison`)**: `DaySourceComparison.sort(rows, sort)` orders all visible sources for the viewed day by whichever column header the user tapped (default: High descending).
- **Android (`ForecastComparisonActivity`, `activity_forecast_comparison.xml`)**: Renders the card via `renderSourceComparison`. API order is stored in `WidgetStateManager` (`WeatherSourcePreferences`), which distinguishes `enabledSources()` (persisted user order) from `visibleSources()` (filtered by `SourceCoverage` for the active location). `SettingsActivity.persistSourceOrder` saves via `widgetStateManager.setVisibleSourcesOrder(...)`, logs `SOURCE_ORDER` and `SourceListEdit`, and sends a UI-only `ACTION_REFRESH` broadcast to repaint all widgets (so home buttons reflect the new primary source) without fetching.
- **Desktop (`ForecastComparisonWindow`, `DaySourceComparisonTable`, `DesktopConfig`)**: Renders the card via `DaySourceComparisonTable`. `DesktopConfig.resolveDesktopConfigSave` merges non-settings writers through `mergeNonSettingsSave`, which currently rejects `visibleSources` changes unless the save comes from Settings (`settings` / `settings-close`). `DesktopConfig.rebaseSettingsDraft` already three-way merges `visibleSources`, and `DaemonProcess` already classifies `visibleSources` reorders as `source_list_edit` (`SourceListEdit`) so no forecast fetch is triggered.

## Proposed design

### 1. Shared ordering rule (`:shared`)
- Add `WeatherSourceOrdering.reorderEnabled(enabledIds: List<String>, orderedVisibleIds: List<String>): List<String>`:
  - Places sources from `orderedVisibleIds` that are currently enabled first (in table order, deduplicated), followed by any enabled sources not present in `orderedVisibleIds` (e.g., NWS while outside US coverage) in their existing relative order.
  - Passes through `sanitizeVisibleIds`. Never drops a location-unavailable enabled source and never enables a disabled source.
- Add helpers on `DaySourceComparison`:
  - `reorderedEnabledIds(enabledIds: List<String>, sortedRows: List<Row>): List<String>`
  - `canApplyOrder(enabledIds: List<String>, sortedRows: List<Row>): Boolean` — true when `sortedRows.size > 1` and `reorderedEnabledIds(enabledIds, sortedRows) != WeatherSourceOrdering.sanitizeVisibleIds(enabledIds)`.

### 2. UI & interaction (Android & Desktop)
- **Placement & label**: Place a compact **"Set API order"** button on the right side of the `"All sources for <day>"` header row on both platforms.
  - **Android**: `Button` (`R.id.source_comparison_apply_order_button`) styled with `DataScreenStyler.pillButton`, in a horizontal header row alongside `source_comparison_title` (`layout_weight="1"` so the title wraps cleanly on narrow screens).
  - **Desktop**: Compact `OutlinedButton` (matching `Mode: Evolution`, `ObsStyle.accent` text, `testTag("source_compare_apply_order")`) in a `Row` alongside `"All sources for <day>"`.
- **Enabled state**: Enabled only when `DaySourceComparison.canApplyOrder(enabledIds, sorted)` is true; dimmed/disabled when the APIs are already in the table's order (or with ≤ 1 source).
- **On click**:
  - Preserves the currently displayed source (consistent with Settings reordering).
  - Fetches nothing (`SourceListEdit` rule).
  - **Android**:
    - Persists via `widgetStateManager.setVisibleSourcesOrder(updatedIds.map(WeatherSource::fromId))`.
    - Logs `SOURCE_ORDER` and `SOURCE_LIST_EDIT` (`SourceListEdit.classify` / `logMessage`) to `Log.d` and `appLogDao`.
    - Sends UI-only `ACTION_REFRESH` broadcast to repaint all widgets.
    - Updates `sourceOrder` on the cached `sourceCompareRows` and re-renders the card so the button immediately disables and tie-breakers reflect the new order.
    - Shows a short `Toast` (`"API order updated"`).
  - **Desktop**:
    - Add `ConfigSaveSource.COMPARISON_SOURCE_ORDER = "history-source-order"` and `DataWindowSaves.sourceOrder: (DesktopConfig) -> Unit = {}`.
    - Wire `DataWindowSaves.forForecastComparison` to pass `sourceOrder = { save(it, ConfigSaveSource.COMPARISON_SOURCE_ORDER) }`.
    - Update `mergeNonSettingsSave` / `resolveDesktopConfigSave` with `allowVisibleSourcesChange = source == ConfigSaveSource.COMPARISON_SOURCE_ORDER` so `visibleSources` is saved while stale other settings fields remain protected.
    - Clicking the button saves `config.copy(settings = config.settings.copy(visibleSources = updatedIds))` via `saves.sourceOrder`. Compose recomposes with the new `config`, immediately disabling the button; the daemon handles `SOURCE_LIST_EDIT` without fetching.

### 3. Documentation
- Update `arch/ARCHITECTURE.md` (`DaySourceComparison` bullet) and `arch/forecast-and-observation-cadence.md` (`SourceListEdit` bullet) to note that reordering can also be triggered from Forecast Comparison's "All sources" table.

## Verification plan

1. **`:shared` unit tests (`DaySourceComparisonTest`)**:
   - `reorderEnabled` normal reorder, preserving location-unavailable enabled sources, ignoring non-enabled ids.
   - `canApplyOrder` false when already matching or single source, true when different.
2. **`:app` Robolectric integration tests (`ForecastComparisonSourcesRoboTest`)**:
   - Sort table by a column, tap "Set API order", assert `WidgetStateManager.getEnabledSourcesOrder()` matches table order, displayed source is preserved, button disables, and UI-only widget repaint broadcast is sent.
   - Verify an enabled source unavailable at the site is preserved at the end rather than dropped.
3. **`:desktop` tests (`DesktopConfigSavePolicyTest`, `DesktopForecastComparisonSourcesTest`)**:
   - `ConfigSaveSource.COMPARISON_SOURCE_ORDER` persists `visibleSources` while merging away stale other settings fields; `COMPARISON_WINDOW` still merges away stale `visibleSources`.
   - Clicking "Set API order" in `ForecastComparisonContent` saves the reordered `visibleSources` through `saves.sourceOrder`, preserves `weatherSource`, and disables the button once applied.
4. **End-of-task gate**: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` and visual/runtime verification on desktop & emulator.
