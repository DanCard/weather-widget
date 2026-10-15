# Settings → Weather Data Sources: drag to reorder, two groups

User, 2026-10-15: "The interface for moving priority of data sources seems klunky." Chose options 1
(drag handles) and 2 (two groups) from the suggestions. Both platforms (AGENTS.md dual-platform rule).

## Problem

Each source row (`item_api_source.xml`; desktop `ApiSourcesList`) has a checkbox, the name and
description, and ▲/▼ buttons.

- Every ▲/▼ tap writes prefs and rebuilds every row (`rebuildSourceRows`), so the list jumps.
  Moving a source from 6th to 1st takes 5 taps.
- Enabled and disabled sources share one list. Disabled ones are only dimmed (alpha 0.5), and the
  arrows are `INVISIBLE` on them. So "the order" and "on/off" are mixed in the same list, and the
  dimmed rows look broken rather than optional.

## Design

```
Weather Data Sources
 In rotation                                   ← ordered; first = primary (displayed)
  ≡  Google Weather    Forecast-only…      ✕
  ≡  NWS               US official…        ✕
  ≡  Open-Meteo        Global, free…       ✕
 Available
  +  Silurian          …
  +  Tomorrow.io       …
```

- **In rotation**: the enabled list, in stored order. A **≡ handle** drags the row; the order
  is saved **once, on drop** (not on every slot it passes), so a drag is one prefs write and one
  `SOURCE_ORDER` log line. **✕** removes the row, which drops it into Available. Removing the last
  source shows the existing "At least one source must remain enabled" message.
- **Available**: disabled sources in `ALL_CONFIGURABLE` order, at full opacity (not dimmed).
  **+** (or tapping the row) adds the source through the existing path: the API-key gate, Google
  going first (`withEnabled`), and on Android `SourceSwitchFetch` + `RefreshToast`. All unchanged.
- The "Not available at this location" note stays on the In-rotation row, as now.
- **Accessibility:** a handle you can only drag can't be used with TalkBack or a keyboard. Each
  In-rotation row gets **Move up / Move down** accessibility actions (Android
  `ViewCompat.addAccessibilityAction`; Compose `semantics { customActions }`). They reuse the
  existing `moveUp`/`moveDown`, so nothing new is needed in `:shared` for them. Nothing extra shows
  on screen.
- Description text changes to: "Drag to reorder. The first source is the one the widget shows;
  tapping the source label cycles through the rest. Removed sources are not fetched."

### Shared (`WeatherSourceOrdering`)

- `move(visibleIds, from, to): List<String>`: moves one row from position `from` to position `to`
  (what a drag produces); sanitized; out-of-range indices are a no-op.
- `available(visibleIds): List<WeatherSource>`: the hidden half of `ordered()`. `ordered()` is
  built from it, so the two can't disagree.

### Android

- `api_sources_container` (LinearLayout) becomes two group headers, plus a **RecyclerView**
  (`api_sources_rotation`, `isNestedScrollingEnabled = false`, wrap_content inside the existing
  ScrollView) for In rotation, plus a LinearLayout (`api_sources_available`) for Available. RecyclerView
  is already a dependency (`libs.androidx.recyclerview`).
- `ItemTouchHelper` with `isLongPressDragEnabled = false`: a drag starts only from a touch-down on
  the ≡ handle (`startDrag`). Starting it from the handle only means scrolling the Settings page
  over the list never picks up a row. `onMove` → `notifyItemMoved` (animated, no rebuild);
  `clearView` → persist with `WeatherSourceOrdering.move` and log `SOURCE_ORDER Drag: …`.
- New `item_source_rotation.xml` (handle, name/description, ✕) and `item_source_available.xml`
  (+, name/description). `item_api_source.xml` is deleted. The checkbox goes too, and with it the
  shared-id state-restore bug from 2026-09-18 (`isSaveEnabled = false` workaround): no checkboxes,
  no restored state.
- Strings: `sources_in_rotation`, `sources_available`, `source_add`, `source_remove`,
  `source_drag_handle`, new `api_sources_description`. `move_up`/`move_down` stay (they become the
  accessibility action labels). **All 19 locales** (locale-parity test).

### Desktop

- `ApiSourcesList` gets two sub-headed groups. The drag is written by hand (no new
  dependency): `Modifier.pointerInput { detectDragGestures }` on the handle tracks the dragged
  row's offset, the row is drawn lifted with `graphicsLayer { translationY }` and moved in the
  local list each time it crosses half a row height, and `onChanged` is called once on drag end.
  For about 9 rows a library (`sh.calvin.reorderable`) isn't worth adding.
- Test tags: `source_drag_<id>`, `source_remove_<id>`, `source_add_<id>`, `source_row_<id>`
  (replacing `source_checkbox_<id>`, `move_up_<id>`, `move_down_<id>`).

## Tests

| # | Kind | Test | Asserts |
|---|---|---|---|
| 1 | Unit | `WeatherSourceOrderingTest.move` | down, up, to first, to last, same index, out of range = no-op, unknown ids sanitized |
| 2 | Unit | `WeatherSourceOrderingTest.available` | complement of enabled, `ALL_CONFIGURABLE` order; `ordered() == enabled + available()` |
| 3 | Robolectric (integration) | Settings renders two groups | seeded [OM, SIL, TIO] → rotation rows OM, SIL, TIO; Available holds the rest; no dimming |
| 4 | Robolectric (integration) | drag persists once on drop | drive the ItemTouchHelper callback `onMove(0→2)` + `clearView` → prefs = [SIL, TIO, OM]; one `SOURCE_ORDER` row |
| 5 | Robolectric (integration) | ✕ removes; last one refused | ✕ on SIL → Available; ✕ on the only source → list unchanged + toast |
| 6 | Robolectric (integration) | + adds; Google goes first; key gate | + Google → first + `SourceSwitchFetch` path; + keyless source with no key → refused + scroll to keys |
| 7 | Robolectric (integration) | accessibility actions | Move up/down actions present on rotation rows (not first/last) and reorder prefs |
| 8 | Robolectric | recreate keeps sources (rewritten existing test) | `recreate()` → prefs unchanged, rotation rows unchanged |
| 9 | Compose desktop (integration) | two groups + add/remove | tags as above; `source_add_TOMORROW_IO` replaces the old checkbox clicks in Phase5 / BaselineChange / DesktopUiTest |
| 10 | Compose desktop (integration) | drag reorders | `performTouchInput { down; moveBy(2 rows); up }` on `source_drag_OPEN_METEO` → `visibleSources` reordered, `onChanged` once |
| 11 | Compose desktop | custom actions | Move up/down semantics actions reorder |
| 12 | Existing | Section order, locale parity, HardcodedUserFacingString | unchanged / still green |

Manual: drag on the Pixel and the desktop window; check scrolling the Settings page over the list
doesn't start a drag; TalkBack actions on the phone.

## Out of scope

- "Make primary" / star (option 3). The first row is the primary, and dragging there is one
  gesture.
- Any change to what the order means (cycle order, primary, fetch gating).

## Verification

- Unit (shared): `WeatherSourceOrderingTest` 27/27 — `move` (4 directions, no-ops, sanitized indices), `available`.
- Android Robolectric: new `SettingsSourceListRoboTest` (tests 3–7: groups, drag saves once on drop,
  no-op drag saves nothing, remove / last-one refused, add / Google first, a11y actions) and the
  rewritten `SettingsActivityRobolectricTest` recreate + NWS-coverage tests; whole `com.weatherwidget.ui`
  package, `LocaleResourceParityTest`, `HardcodedUserFacingStringTest` green. The key-gate test is
  skipped on this machine (every key-based source has a key baked from `local.properties`).
- Desktop: new `SettingsWindowSourceListTest` (tests 9–11) + full `:desktop:test` and `:shared:test` green.
  Test 10 caught a real bug: rows were not wrapped in `key(id)`, so the first swap re-keyed the
  handle's `pointerInput` and cancelled the drag. Fixed.
- Emulator (Medium Phone API 36): groups render in a card above Today Column; a real `input swipe`
  on Silurian's handle moved it to the top (`visible_sources_order=SILURIAN,NWS,GOOGLE_WEATHER,OPEN_METEO`),
  a swipe over row text scrolled the page with the order unchanged; order restored after.
- Pixel 7 Pro: installed; not driven (locked screen).
- Desktop distributable rebuilt and restarted (`scripts/buildStart-desktop.sh`).
