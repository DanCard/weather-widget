# Multi-line address paste & Nominatim fallback search

## Problem & evidence

Pasting a multi-line address with a unit number such as:

```text
7900 East Princess Drive
Scottsdale, 85255 AZ
Unit 1182.1
```

into the location search box returns no results:

1. **Single-line UI inputs** (`OutlinedTextField(..., singleLine = true)` in `LocationPicker.kt` and `AutoCompleteTextView` with `android:singleLine="true"` in `activity_config.xml`) do not normalize pasted newlines into comma-separated segments.
2. **Nominatim** (`https://nominatim.openstreetmap.org/search`) does not index secondary unit designators (`Unit`, `Apt`, `Suite`, `#...`). Verified directly against Nominatim:
   - `7900 East Princess Drive Scottsdale, 85255 AZ Unit 1182.1` -> `[]` (0 results)
   - `7900 East Princess Drive, Scottsdale, 85255 AZ` -> 2 results (`33.6442, -111.9097`)

## Plan

1. **Shared query cleaner (`:shared`)**:
   - Add `AddressQueryCleaner` with:
     - `flattenMultilineInput(input)` for UI paste handling (replaces `\r?\n` with `", "` without disturbing normal single-line typing).
     - `candidateQueries(rawQuery)` producing an ordered, deduplicated fallback list:
       1. Normalized query (newlines flattened, spaces/commas cleaned).
       2. Secondary unit designators stripped (`Unit`, `Apt`, `Apartment`, `Suite`/`Ste`, `Room`/`Rm`, `Floor`/`Fl`, `Bldg`/`Building`, `Lot`, `Space`/`Spc`, `Trlr`/`Trailer`, `#...`).
       3. Leading comma-separated segment dropped (city/state/ZIP fallback when a street number isn't in OSM).
   - Update `SharedLocationResolver.searchText` to iterate through `candidateQueries(query)` and return the first non-empty Nominatim match, logging when a fallback matches.
2. **Desktop & Android paste normalization**:
   - `LocationPicker.kt`: apply `AddressQueryCleaner.flattenMultilineInput` in `onValueChange`.
   - `ConfigActivity.kt`: attach an `InputFilter` to `location_search_input` that flattens pasted multi-line text with `AddressQueryCleaner.flattenMultilineInput`.
3. **Keep diagnostic logging** in `NominatimApi.search` and `LocationPicker.kt`.
4. **Tests**:
   - Unit tests for `AddressQueryCleaner`.
   - Integration tests in `SharedLocationResolverTest` (real `NominatimApi` + Ktor `MockEngine`), `LocationPickerIntegrationTest` (Compose UI + real `SharedLocationResolver` + `NominatimApi` + `MockEngine`), and `ConfigActivityRobolectricTest` (Android `AutoCompleteTextView` paste + real `SharedLocationResolver` + `NominatimApi` + `MockEngine`).
5. **Verification**:
   - Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
