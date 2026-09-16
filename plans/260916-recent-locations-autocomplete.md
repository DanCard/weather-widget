# Implement Recent Locations Autocomplete Dropdown

**Date**: 2026-09-16  
**Goal**: Display recent locations in a Google Search–style dropdown when focusing or typing in the location search text box across both Android (`:app`) and Linux Desktop (`:desktop`), enabling one-tap toggling between frequent cities (e.g. Lviv and Kyiv).

---

## 1. Architectural Overview & Design

### 1.1 Shared Data Model & Helper (`:shared`)
- **`RecentLocation`**: Serializable data class holding coordinates, label, and timestamp.
  - `lat: Double`
  - `lon: Double`
  - `label: String`
  - `timestamp: Long`
- **`RecentLocationsHelper`**:
  - `addRecent(recents: List<RecentLocation>, newLoc: RecentLocation, maxEntries: Int = 8): List<RecentLocation>`:
    - Deduplicates by proximity (~1km) or exact label match (case-insensitive).
    - Bumps existing entry to index 0 (LRU).
    - Caps total list size at `maxEntries`.
  - `filterMatching(recents: List<RecentLocation>, query: String): List<RecentLocation>`:
    - If `query` is blank: returns all `recents`.
    - If `query` has text: filters entries where `label` contains query (case-insensitive).
  - `toResolvedLocation(recent: RecentLocation): ResolvedLocation`.

### 1.2 Android Platform (`:app`)
- **Persistence (`WidgetStateManager`)**:
  - Save / retrieve `List<RecentLocation>` in `SharedPreferences` serialized as JSON (`KEY_RECENT_LOCATIONS`).
  - Helper functions: `getRecentLocations()`, `addRecentLocation(RecentLocation)`, `clearRecentLocations()`.
- **UI Layout (`activity_config.xml` & `item_recent_location.xml`)**:
  - Change `location_search_input` from `EditText` to `AutoCompleteTextView` (retaining all existing styling and attributes).
  - Create `item_recent_location.xml` for custom dropdown row layout:
    - History icon (`R.drawable.ic_history`).
    - Primary title (location name).
    - Secondary text (coordinates or details).
- **Behavior (`ConfigActivity.kt`)**:
  - Initialize `RecentLocationAdapter` with `recentLocations`.
  - Show suggestions dropdown when `location_search_input` gains focus or is clicked.
  - Dynamic filtering as the user types.
  - Tapping a suggestion:
    - Immediately invokes `saveChosenLocation(chosen.lat, chosen.lon, chosen.label, LocationMode.FIXED)`.
  - When saving any location (search result, manual coords, or GPS), automatically record it via `widgetStateManager.addRecentLocation(...)`.

### 1.3 Linux Desktop Platform (`:desktop`)
- **Persistence (`DesktopConfig`)**:
  - Add `val recentLocations: List<RecentLocation> = emptyList()` to `DesktopConfig`.
  - On location select in `DesktopUiApplication` / `DesktopWindowHosts`, update `recentLocations` with `RecentLocationsHelper.addRecent(config.recentLocations, newLoc)`.
- **UI & Behavior (`LocationPicker.kt`)**:
  - Add suggestion dropdown (`DropdownMenu` / `ExposedDropdownMenuBox` or anchored popup) to the search `OutlinedTextField`.
  - When `query` text field is focused and recent locations exist, display matching recents with history icon.
  - Clicking any suggestion immediately selects it (`selectLocation(...)`).

---

## 2. Implementation Steps

1. **Step 1: Shared Models & Logic**
   - Create `shared/src/main/kotlin/com/weatherwidget/data/model/RecentLocation.kt`.
   - Create `shared/src/main/kotlin/com/weatherwidget/shared/util/RecentLocationsHelper.kt`.
   - Add unit tests in `shared/src/test/kotlin/com/weatherwidget/shared/util/RecentLocationsHelperTest.kt` (`@Category(ShortDuration::class)`).

2. **Step 2: Android Implementation**
   - Add recent locations persistence methods to `WidgetStateManager.kt`.
   - Create `app/src/main/res/layout/item_recent_location.xml`.
   - Update `app/src/main/res/layout/activity_config.xml` to use `AutoCompleteTextView`.
   - Update `ConfigActivity.kt` to wire the adapter, handle focus dropdown, handle selection, and record recents on save.
   - Add Robolectric test in `app/src/test/java/com/weatherwidget/ui/ConfigActivityRecentLocationsRoboTest.kt`.

3. **Step 3: Desktop Implementation**
   - Add `recentLocations` to `DesktopConfig.kt`.
   - Update `LocationPicker.kt` to show suggestions dropdown on search input focus.
   - Update `DesktopWindowHosts.kt` / `DesktopUiApplication.kt` to save recent locations on selection.
   - Add unit tests for desktop config and location picker recents.

4. **Step 4: Full Suite Gate & On-Device Verification**
   - Run `./scripts/unit-tests.sh` to verify all tests pass across `:shared`, `:app`, and `:desktop`.
   - Install debug build to emulator (`./gradlew installDebug`).
   - Visually verify on emulator by opening Set Location and tapping into the search box.
