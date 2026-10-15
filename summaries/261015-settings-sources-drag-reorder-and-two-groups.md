# Settings sources: drag to reorder, two groups — summary

Plan: `plans/261015-settings-sources-drag-reorder-and-two-groups.md`.

## What changed

- **Section order** (both platforms, `SettingsSection`): Weather Data Sources moved up two spots,
  above Daily View — Today Column.
- **Sources list** (both platforms): checkbox + ▲/▼ rows replaced by two groups in one card:
  - *In rotation*: ≡ handle drags (saved once on drop), ✕ removes (last one refused).
  - *Available*: + or a row tap adds (API-key gate, Google-first and its fetch unchanged).
  - Move up / Move down accessibility actions on each In-rotation row.
- `:shared` `WeatherSourceOrdering.move(from, to)` and `available()`; `ordered()` now built from
  `available()`.
- Android: `RecyclerView` + `ItemTouchHelper` (drag only from the handle), `item_source_rotation.xml`,
  `item_source_available.xml`, three vector icons; `item_api_source.xml` deleted, with it the
  shared-checkbox-id restore workaround. Six strings × 20 locales.
- Desktop: hand-rolled drag (`detectDragGestures` + `graphicsLayer`), rows keyed by source id.

## Verification

See the plan's Verification section: shared, Android Robolectric and desktop Compose suites green;
real drag and scroll-vs-drag checked on the emulator; desktop restarted.
