# Desktop: now-dot hover shows the nearest official station's card

*2026-09-12* — plan: `plans/260912-desktop-now-dot-hover-station-cards.md`

## Outcome

Hovering the hourly graph's "now" dot on the desktop app now shows the Stations window's
Observations-tab card for the **nearest official station** — name, condition, temperature
(right-justified), `ID • distance • OFFICIAL (API|Web)`, Reported / Fetched times — instead of the
Blend tab's six-column weight table. The overlay is laid out inside the graph, always beside the
dot, never clipped and never covering it.

## What changed

- **`ObservationsWindow.kt`** — `visibleStationRows` now takes `List<ObservationReading>` (the tab
  maps `.toReading()` first); the card body is extracted to
  `ObservationCard(obs, useCelsius, nowMs, fontScale, showRawMetar, clickable, compact)`. The
  overlay renders the *same* composable over the *same* row selection as the tab, so the two cannot
  drift. `clickable` is attached only when there is a history URL, so overlay cards are not
  hit-testable.
- **`NowDotStationsPopup.kt`** — `nowDotStationsTable()` → `nowDotStationCards()`: tab selection →
  OFFICIAL only (fallback to all rows when none is official) → nearest one (`MAX_POPUP_ROWS = 1`).
  `NowDotPopupPositioner` (pure): `fitWidth` caps the overlay to the roomier side of the dot;
  `calculate` places right → left → roomier side, then clamps vertically. Overlay is a `Layout`
  sibling of the Canvas; column is `width(IntrinsicSize.Max)` so it wraps to content.
  `NowDotTarget.canvasWidth` dropped.
- **`TemperatureGraph.kt`** — passes `observations`, `WeatherSource.fromId(displaySourceId)`,
  `now` to the popup.
- **Tests** — `NowDotStationsPopupTest` (13) rewritten for cards + positioner;
  `ObservationsWindowRowsTest` (7) fixtures switched to `ObservationReading`. All pass.

## Insights

- **Compose `Popup` is the wrong tool for a hover overlay.** It is a separate pointer layer;
  opening it sent the Canvas a pointer *Exit* even with the popup nowhere near the pointer —
  hover false → popup gone → hover true → flicker loop. Debug prints showed `hovered=true` →
  positioner ran → `hovered=false` with no Move event between. A plain `Layout` sibling that
  measures its child and knows the container size is all the old `offset()` Box was missing.
- **`fitWidth` is what guarantees the overlay can sit beside the dot.** At default zoom the dot is
  near the centre, so anything wider than ~half the graph has nowhere to go. Capping to the roomier
  side means it never covers the dot, which would end its own hover. The "never covers the dot"
  test runs a grid of positions through `fitWidth` + `calculate`.
- **`width(IntrinsicSize.Max)` + name `weight(1f)` right-justifies the temperature without a slab
  of white space**: the column is exactly as wide as its widest line, and the weight only absorbs
  the difference between a card and the widest one.
- **xdotool probing gotcha:** Canvas-local coordinates are offset from the window's by the header
  and left arrow (~(6, 31) px here), and the graph re-centres on `.show`. Locate the dot from a
  fresh screenshot (white ring pixels), not a previous capture — two "hover doesn't work" scares
  were both mis-aimed pointers.

## Live iteration (user watching the running app)

| Request | Change |
|---|---|
| fonts −40 %, no horizontal white space, narrower | card font factor 0.5 → 0.3 × uiScale; compact padding 4dp; preferred width 340 → 220dp |
| only the first two official stations | cap 6 → 2; `+N more` footer dropped |
| delete white space between temperature and the rest; fonts +20 % | column wraps to content; 0.3 → 0.36 |
| right-justify the temperature | name keeps `weight(1f)` inside the wrapped column |
| delete the header line | `59.60° blended · 3 official of 5 stations` removed; `blendedLabel`/`blendStationCount` dropped from `NowDotStationCards` |
| just the first official station | cap → 1 |

## Verification

- Unit: 13 + 7 pass.
- Live: `scripts/buildStart-desktop.sh`, hover via `xdotool`; two captures 1.5 s apart identical
  (hover stable). Intermediate states (3 cards + header; 2 cards) screenshotted; the final one-card
  state was checked by the user on the running app.
