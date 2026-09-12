# Desktop: now-dot hover shows station cards, not the blend table

*2026-09-12*

## Request

Hovering the graph's "now" dot currently pops up the **Blend** table (station / km / age / raw /
fed-to-blend / weight). Change it to:

1. show the Stations window's **default view** — the Observations-tab station cards — instead of
   the blend table;
2. make the overlay larger;
3. (maybe) show only OFFICIAL stations.

## What is actually wrong today

Screenshot of the live app, hovering the dot at the default zoom:

- The table is drawn as an `offset()` Box **inside the graph's `Box`**, so it is clipped to the
  Canvas bounds. In the capture it is cut off on the left (flipped left because the dot sits past
  mid-window, but `x` is never clamped to ≥ 0) and cut off at the bottom (5 rows already run past
  the graph's lower edge; the popup has no idea how tall it is).
- It is the wrong table for a glance: six monospace columns at 10.5sp·scale answer "how was the
  blend weighted", which is the Blend tab's question. The user's question at the dot is "what are the
  nearby stations reading right now" — the Observations tab's question. `DesktopConfig.obsSelectedTab`
  defaults to `TAB_OBSERVATIONS`, so that tab *is* the window's default view.

## Design

### 1. Content: the Observations tab's card, reused verbatim

Every field the tab's card shows — `stationName`, `condition`, `temperature`, `stationId`,
`distanceKm`, `stationType`, `api`, `timestamp`, `fetchedAt`, `qcFailed`, `isWebFallback` — exists on
`ObservationReading`, and the graph already receives the same rows the blend consumed
(`observations: List<ObservationReading>`, from `snapshot.raw.rawObservations`). No new data path.

Two extractions in `ObservationsWindow.kt`, both so the popup and the tab **cannot drift**:

| Today | After |
|---|---|
| `visibleStationRows(List<DesktopObservationEntity>, WeatherSource)` | `visibleStationRows(List<ObservationReading>, WeatherSource)` — the tab maps `.toReading()` first (the mapper already exists in `DesktopEntities.kt`). Same matcher, same newest-per-station, same nearest-first. |
| Card body inlined in `ObservationList`'s `items {}` | `ObservationCard(obs: ObservationReading, useCelsius, nowMs, fontScale, clickable)` — tab calls it at `fontScale = 1f`, popup at its own. |

The popup body becomes `visibleStationRows(observations, WeatherSource.fromId(displaySourceId))`
rendered as `ObservationCard`s. That is the single-source rule the current popup follows for the
formatter, moved one level up to the card itself.

Two deliberate omissions in the popup only (the tab is unchanged):
- **raw METAR line** — ~80 monospace chars, ~1300 px at graph scale; it is inspection detail, not a
  glance;
- **click-to-history** — the overlay closes when the pointer leaves the dot, so nothing on it can be
  clicked.

A header line (`59.60° blended · 3 official of 5 stations`, from `BlendTableFormatter`) was in the
approved plan and shipped in the first build; the user cut it during the live iteration (§2b).

### 2. Size: laid out inside the graph by a pure positioner (Popup tried and rejected)

**First attempt — `androidx.compose.ui.window.Popup` — did not survive contact.** A Popup is a
separate pointer layer; the moment it opened, the Canvas received a pointer *Exit* even though the
overlay was nowhere near the pointer → hover false → popup gone → Move → hover true → … a flicker
loop, observed live via debug prints (`hovered=true` → positioner ran → `hovered=false` with no Move
in between).

**What shipped:** the overlay is a `Layout` filling the graph's `Box`, a sibling of the Canvas as
before, but one that measures its child and knows the container size — the two things the old
`offset()` Box lacked. The layout node carries no pointer-input modifier, and `ObservationCard`
attaches `clickable` only when there is a history URL to open (none in the overlay), so nothing in
the overlay is hit-testable and the Canvas keeps the hover.

`NowDotPopupPositioner` (pure, tested):
- `fitWidth(anchor, containerWidth, preferred)` — the overlay's max width is the roomier side of
  the dot. At default zoom the dot sits near the centre, so a preferred width over ~half the graph
  can never sit beside it; the cards narrow (names ellipsize, as in a narrow Stations window)
  rather than the overlay covering the dot.
- `calculate(anchor, container, popup)` — right of the dot with a 12 px gap; flip left on
  overflow; else the roomier side clamped; then clamp vertically. Never covers the dot: the pointer
  landing on it would end the hover that opened it.

The column is `width(IntrinsicSize.Max)`: as wide as its widest line, no wider, every card filling
that width so the (weight-pushed) temperatures right-align without a slab of empty space.

### 2b. Iterated live with the user (same session)

| Request | Change |
|---|---|
| fonts −40 %, no horizontal white space, narrower | `CARD_FONT_SCALE_PER_UI_SCALE` 0.5 → 0.3; `compact` card padding 4dp; preferred width 340 → 220dp |
| only the first two official stations | `MAX_POPUP_ROWS` 6 → 2; `+N more` footer dropped |
| delete the white space between temperature and the rest; fonts +20 % | column wraps to content (`IntrinsicSize.Max`, `Constraints(maxWidth = …)` not fixed); 0.3 → 0.36 |
| right-justify the temperature | name keeps `weight(1f)`; with the wrapped column the slack is only the difference to the widest line |
| delete the header line | header removed; `NowDotStationCards` loses `blendedLabel`/`blendStationCount`; the breakdown still gates the popup (no blend point → no dot to explain → nothing shown) |
| just the first official station | `MAX_POPUP_ROWS` → 1 |

### 3. Official stations only — recommended **yes**, with a fallback

Filter `stationType == "OFFICIAL"`; if that leaves nothing (Synoptic-borrowing setups can be PWS-only
in places), fall back to all rows. The header always reports the full blend count, so the filter
never hides that PWS took part.

Why yes: the local DB has 9 PERSONAL vs 4 OFFICIAL stations within 20 km; under NWS the list is
`AW020 (PERSONAL) · KNUQ · KPAO · LOAC1 (PERSONAL) · KSJC` → official-only is `KNUQ · KPAO · KSJC`.
Three cards is a glance; five is a list. The Blend tab remains the place to see PWS weights.

Counter-argument to weigh: with `personalStationDiscount = 0` the PWS contribute real weight (AW020
carried 9% in the screenshot), and the overlay would not show that thermometer. The header's
"3 official of 5" is the hint that more exist.

## Files

| File | Change |
|---|---|
| `desktop/.../ObservationsWindow.kt` | `visibleStationRows` takes `ObservationReading`; extract `ObservationCard(fontScale, showRawMetar, clickable, compact)`; tab maps `.toReading()` |
| `desktop/.../NowDotStationsPopup.kt` | `nowDotStationsTable` → `nowDotStationCards(...)`; `NowDotPopupPositioner` (`fitWidth` + `calculate`); `Layout`-based overlay rendering `ObservationCard`s; `NowDotTarget.canvasWidth` dropped |
| `desktop/.../TemperatureGraph.kt` | pass `observations`, `displaySourceId`, `now` to the popup |
| `desktop/src/test/.../ObservationsWindowRowsTest.kt` | fixtures build `ObservationReading` (or map via `toReading()`) |
| `desktop/src/test/.../NowDotStationsPopupTest.kt` | drop the blend-table tests; add cards + positioner tests |

## Tests

| # | Test | Asserts |
|---|---|---|
| 1 | cards = `visibleStationRows` output | for the same input, popup rows == tab rows — pins the single-source rule |
| 2 | official filter | PERSONAL rows dropped, `officialOnly = true` |
| 3 | official fallback | all-PERSONAL input shows all rows rather than nothing |
| 4 | cap + remainder | 10 stations (shuffled) → the nearest 1, `remaining = 9` |
| 5 | empty-safe | no rows / no breakdown → null, popup draws nothing |
| 6 | positioner: right of dot | `x = dot.right + GAP`, y unchanged when it fits |
| 7 | positioner: flips left | popup that would overflow lands at `dot.left - GAP - width` |
| 8 | positioner: vertical clamp | tall popup near the bottom is pulled up to `graph.h - popup.h`, never negative |
| 9 | `fitWidth` | capped to the roomier side for a centred dot; full preferred width off-centre |
| 10 | positioner: never covers the dot | for a grid of dot positions, measured at `fitWidth`, the rect excludes the anchor and stays inside the graph |
| 11 | existing hit-test tests | unchanged |

## Verification

- `./gradlew :desktop:test --tests "*NowDotStationsPopupTest" --tests "*ObservationsWindowRowsTest"`
  — 13 + 7 pass.
- `scripts/buildStart-desktop.sh`, hover the dot with `xdotool`, two captures 1.5 s apart identical
  (hover stable, no flicker): card to the right of the dot, fully inside the graph. Intermediate
  states (3 cards with header; 2 cards) screenshotted during the live iteration; the final one-card
  build was verified by the user watching the running app.

**Gotcha for future xdotool probing:** Canvas-local coordinates are offset from the window's by the
header and left arrow (~(6, 31) px at this window size), and the graph re-centres on `.show`, so
locate the dot in a fresh screenshot (white ring pixels) rather than from a previous capture.
