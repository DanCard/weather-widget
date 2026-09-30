# Settings "Location" card shows the address the user chose

## Report (Pixel 7 Pro, 2026-09-30 11:28)
User searched "860 Avery dr. 94043" in Settings → Set Location…, confirmed the Nominatim match.
1. The Settings card reads `Widget Location: Mountain View, California (37.4166, -122.0889) • Pinned`
   — the chosen address is nowhere on screen.
2. The card is titled "Default Location", a concept deleted 2026-08-12 (there is no default location).

## Evidence
- `app_logs` 11:28:34 `CONFIG Global location set … label=860, Avery Drive, Mountain View, Santa Clara
  County, California, 94043, United States` — the save had the full address.
- `weather_prefs` afterwards:
  - `historical_pois = Mountain View, California|37.416603088378906|-122.08887481689453;34A, Skierniewicka…;Kyiv, Ukraine|…`
    — the address entry is **gone**, replaced by a city name at Float-widened coordinates.
  - `geo_name_37.417_-122.089 = Mountain View, California`

## Root cause (two defects)
**A. The display never looks at the user's choice first.** `LocationUpdater.describe` →
`FriendlyLocationName.cached`: alias → `geo_name_*` reverse-geocode cache → `historical_pois`. The
reverse-geocode cache for the site ("Mountain View, California") outranks the label the user just picked,
so even an intact POI would not be shown. Desktop has the same shape: `SettingsWindow` replaces
`config.label` with `resolver.friendlyName(...)` whenever the lookup succeeds.

**B. The worker evicts the user's newest POI.** Two writers share `historical_pois` with opposite
conventions:
- `LocationUpdater.recordHistoricalPoi` appends newest at the **end**, `takeLast(5)`.
- `CurrentTempRepository.recordHistoricalPoi` (every current-temp fetch) **prepends**, `take(3)`,
  dedupes by exact `"|$lat|$lon"` string — which never matches, because the worker's coordinates come
  back from Float prefs (`37.416603088378906` ≠ `37.4166014`; see memory "Float prefs break coordinate ==").
  Its prepend + `take(3)` cuts the tail — exactly where the user's save was just appended.
  And what it writes is `FriendlyLocationName.cached(...)` or "lat, lon": names read *from* the name
  stores, written back as if new. It adds no information.

## Fix
1. **Title**: "Default Location" → "Location". Rename the key `default_location_title` →
   `location_title` (values + 19 locales; the translated strings get their language's plain
   "Location"), the layout comment, and desktop `SettingsSection.DEFAULT_LOCATION` → `LOCATION`
   with title "Location".
2. **Remember the chosen label with the active location.** `ActiveLocationResolver` gets an
   `active_location_label` beside the active coordinates: written by the setup-screen save
   (`applyActiveLocationToAllWidgets` with a non-null, non-coordinate label), cleared by any write
   without one (follow-device move, device-fix save, clear). `LocationUpdater.describe` prefers it
   when it is `LocationMatch.sameSite` with the effective location; otherwise the current
   `FriendlyLocationName` chain. Scoped to Settings/ConfigActivity summary — the widget header and
   other `FriendlyLocationName` callers keep their short city names (a full Nominatim address there
   would not fit).
   Display (user's call 2026-09-30: shorter): a postal-style label built in `:shared` from
   Nominatim's structured address (`addressdetails=1` added to search) — US
   `860 Avery Drive, Mountain View, CA 94043` (state code from `ISO3166-2-lvl4`), elsewhere
   `34A Skierniewicka, Warsaw, Poland`; falls back to `display_name` when there is no address.
   It is `ResolvedLocation.label` itself, so both pickers, the stored label and Settings agree.
   Device-fix saves resolve through the same reverse lookup, so they store a short address too
   (the plan originally dropped the label there; keeping it is strictly more informative, and a
   later follow-device move clears it).
3. **Desktop parity**: `SettingsWindow` shows `config.label` when it is a real name
   (`!isCoordinateLabel`), falling back to `friendlyName` only for coordinate-shaped labels.
4. **Delete the worker's POI write** — and the `locationName` parameter that existed only to
   feed it (the Observations screen passed "Manual Refresh" as a place name) (`CurrentTempRepository.recordHistoricalPoi` call in
   `refreshCurrentTemperature`, plus `appendHistoricalPoi` if nothing else uses it). The list's only
   writer is then `LocationUpdater`.

## Tests
- `LocationUpdaterTest`: search save with a label → `describeCurrentLocation` contains it even when
  a `geo_name_*` cache for the site says something else; a follow-device move to another site drops
  it; a device-fix save at the same site drops it.
- Robolectric: a current-temp refresh no longer modifies `historical_pois` (regression for B).
- Desktop: settings label prefers `config.label` over the resolver name (existing SettingsWindow test
  harness if present; else pure-function extraction of the label choice).
- On device: re-run the search for 860 Avery Dr on the Pixel 7 Pro, screenshot Settings.

## Result
- Unit: shared 1664, desktop 427, app 2259 — 0 failures.
- Pixel 7 Pro: search dialog lists "860 Avery Drive, Mountain View, CA 94043"; after Use + the forced
  sync, Settings reads `Widget Location: 860 Avery Drive, Mountain View, CA 94043 (37.4166, -122.0889)
  • Pinned` under "Location"; `historical_pois` keeps the entry.
