# Findings + Plan — Graph label placement: Android ↔ desktop parity

Date: 2026-08-14
Parent: deep review of the dominant-station-label / empty-space-finder placement stack
(commit `aea4810b` "Move dominant-station label to the left edge").

Scope reviewed: `shared/.../graph/{GraphEmptySpaceFinder, ForecastDeltaLabel,
DominantStationLabel, GhostLineLabel, GhostLineGate, FetchDotLabel}.kt`, Android
`TemperatureGraph{AnnotationRenderer, SeriesRenderer, SeriesResolver, ObstacleRegistry}.kt`, and
the desktop `TemperatureGraph.kt` placement block.

## 1. Findings (by severity)

### P1 — Ghost line and observed line end at different x on desktop vs Android (medium, parity)

**Android** (`TemperatureGraphSeriesResolver`):
- `fetchDotX` = x of the observation timestamp (e.g. 5:35 pm).
- The observed line is anchored to `transitionX = min(nowX, fetchDotX, …)` and gets a terminal
  point at the fetch dot carrying the last observed temp (`buildAnchoredActualPoints`).
- The ghost line clips from `fetchDotX` rightward (`TemperatureGraphSeriesRenderer.draw`).

**Desktop** (`TemperatureGraph.kt`):
- `transitionX = lastActualPoint?.let { xAtTime(it.timeMs) }` — the last actual **hourly** point
  (5:00 pm), not the observation time.
- The observed line is drawn from `actualLinePoints` (hourly, `timeMs <= transitionMs`) with **no
  terminal anchor** at the observation time.
- The ghost line clips from `transitionX` (5:00 pm) rightward.
- The fetch-dot **circle** is drawn separately at `xAtTime(transitionMs)` (5:35 pm).

Consequence: on desktop the pink line ends at 5:00 and the ghost line starts at 5:00, with the
fetch dot floating at 5:35; on Android the pink line reaches 5:35 and the ghost line starts at
5:35. `visibleCurveYsAt` sampling and the ghost-label candidate gate (`x > startX`) inherit the
same offset. Each platform is internally consistent (sampler matches its own clip), but they do
not match each other by the sub-hour gap between the last hourly actual and the observation time.

Desktop's `transitionX` is used *only* for: ghost clip, ghost gate `fetchDotX`, ghost-label
`ghostLineStartX`, ghost candidate gate, and `visibleCurveYsAt` (both the observed-line boundary
and the ghost boundary). The observed-line boundary and the ghost boundary are therefore currently
the same variable, which is what Phase 2/3 below separate.

### P2 — Ghost labels reserve the widest candidate's width (minor)

Both platforms pass `width = candidates.maxOf { measureText(format(temp)) }` as a single metric
for every ghost-label placement. A short label (`64.3°`) is placed in a box sized for the widest
candidate, so it can be rejected where it would actually fit, and spacing is looser than necessary.
Conservative and parity-preserving; cosmetic only.

### P3 — Curve clearance samples 5 x-positions (minor, accepted)

`GraphEmptySpaceFinder.CURVE_SAMPLES = 5` (and `GhostLineLabel` likewise). A narrow curve spike
between samples could be missed. Fine for smooth bezier curves; a documented fidelity limit, not a
bug. **No action.**

### P4 — Desktop `DominantStationDiag` dedup key excludes box coords (note)

`lastDominantDiagKey` is built from `reason/text/span/station/rawTemp/w/h/drawnLabels.size`, so a
pure coordinate change with an otherwise-identical key will not re-emit the log line (the box
coords added in `aea4810b` are in the *message*, not the *key*). Low impact — coords are
deterministic from `w` + `drawnLabels` — but a one-line fix.

### P5 — `0.08f` lead anchor vs `0f` (note, accepted)

`0f` would express "flush-left" more literally and always clamp; `0.08f` is indirect but produces
identical output for current label widths (verified: `boxLeft=5px`, i.e. the 2dp pad). **No
action** — left as-is deliberately.

## 2. Goal

Bring the desktop observed-line and ghost-line geometry into parity with Android around the
observation anchor, and (optionally) tidy the two low-priority findings. Each phase is independently
shippable and has a review gate; **pause after each phase.**

## 3. Phases

### Phase 1 — Evidence: confirm and quantify P1 on a live desktop render

Do not change behavior yet. Capture, from one desktop render at the same narrow zoom as Android:

- `transitionMs` (observation time), `lastActualPoint.timeMs`, `transitionX`,
  `fetchDotXVal`/`xAtTime(transitionMs)`, and the derived sub-hour offset.
- Confirm whether the pink line ends before the fetch dot on desktop (vs reaching it on Android).

Existing desktop diags (`ActualLineDiag`, `GapLabelDiag`, `GhostLabelDiag`, `DominantStationDiag`)
may already suffice; if not, add one targeted `Log.i` breadcrumb gated by a dedup key (mirroring
the `GhostLabelDiag` pattern).

**Gate:** proceed to Phase 2 only if the offset is confirmed non-zero. If desktop's actual series
already includes a point at the observation time, P1 shrinks to just the ghost clip/sample start.

**Verify:** logcat/console shows the offset; screenshot of desktop hourly narrow view (pink line end
vs fetch dot).

### Phase 2 — CANCELED (P1 disproven in Phase 1)

Desktop: introduce a distinct ghost-start x from the observation time

Split the overloaded `transitionX`:

```kotlin
val ghostStartX = transitionMs?.let { xAtTime(it) } ?: transitionX
```

Use `ghostStartX` (leave `transitionX` as the observed-line end until Phase 3) for:
- ghost line clip (`clipRect(left = ghostStartX, …)`),
- ghost gate `fetchDotX = ghostStartX`,
- ghost-label `ghostLineStartX` and the candidate gate (`coord.x > ghostStartX`),
- `visibleCurveYsAt` ghost sampling (`x >= ghostStartX`) — **but keep** the observed-line sampling
  boundary at `x <= transitionX` so the sampler still matches the (not-yet-extended) pink line.

**Verify:** `:desktop:compileKotlin`, `:desktop:test`; runtime log shows ghost start == fetch-dot x;
Android untouched.

### Phase 3 — CANCELED (P1 disproven in Phase 1)

Desktop: anchor the observed-line terminal to the observation time

Replicate Android's `buildAnchoredActualPoints` terminal-point behavior on the desktop observed
line: append a terminal point at the anchor x carrying the last observed temp, so the pink line
reaches the fetch dot (matching Android). Update the observed-line sampling boundary in
`visibleCurveYsAt` to the new end. Exact temp source (last actual point temp vs fetch-dot temp)
pinned against Android's `anchoredToFetchDot`/`terminalY` logic during the phase.

**Verify:** `:desktop:test`; side-by-side with Android render — pink line end and ghost line start
now coincide at the fetch dot.

### Phase 4 — DONE (decisions + changes below)

Minor cleanup (independent, low risk)

- **4a (P2):** pass per-candidate width to `GhostLineLabel` instead of one max width, *or* document
  the conservative behavior and leave it. Decide here.
- **4b (P4):** add the dominant box coords to the desktop dedup key so coordinate-only changes
  re-emit `DominantStationDiag`.

**Verify:** `:shared:test`, `:desktop:test`.

### Phase 5 — DONE (see section 6)

Cross-platform re-verification

- Full `:shared:test`, `:desktop:test`, `:app:testDebugUnitTest`.
- Side-by-side Android vs desktop screenshots (hourly narrow view): fetch-dot x, pink-line end,
  ghost-line start, dominant-label box all agree.
- Confirm no regression to the dominant-label left-hug behavior from `aea4810b`.

## 4. Phase 1 result (evidence)

Captured with a gated `AnchorDiag` breadcrumb on a live desktop render (narrow view, spanH=4):

```
AnchorDiag: transitionMs=1786755600000 lastActualMs=1786755600000 offsetMin=0
  transitionX=962 anchorX=962 pixelOffset=0 fetchDotX=962 ghostLineDrawn=true w=1282
```

- `transitionMs` (observation time) == `lastActualMs` (last actual point) → `offsetMin=0`.
- `transitionX` == `anchorX` == `fetchDotX` (962) → `pixelOffset=0`.

**P1 is disproven.** There is no sub-hour x divergence between the desktop observed/ghost anchor
and the fetch dot. Root cause of the wrong hypothesis: `ActualTemperatureSeriesBuilder.build`
merges hourly anchors (`topHoursByMs`) with raw observation timestamps (`actualByTime`) into
`allTimes`, so the newest observation becomes its own sub-hourly point in `actualSeries.points`;
`lastActualPoint.timeMs` therefore equals `currentObservedAt`, making desktop's `transitionX`
already the observation-time x (matching Android's `fetchDotX`).

Phases 2–3 (the P1 fix) are canceled. Remaining actionable work is Phase 4 (P2 + P4, minor).

## 5. Notes / risks

- **Evidence-first paid off here:** Phase 1 disproved the hypothesis before any Phase 2/3 code was
  written. The wrong assumption was that `actualSeries.points` are hourly-only.
- **The `AnchorDiag` breadcrumb is worth keeping** (mirrors the other `*Diag` keys): it pins
  `transitionMs`/`lastActualMs`/`offsetMin`/`transitionX`/`anchorX`/`pixelOffset` in one line, so a
  future anchor drift on either platform is a one-log lookup instead of a re-derivation.
- **Phase 4a touches shared code** (`GhostLineLabel.placeAll` metrics), so it needs the shared test
  suite as the safety net; Phase 4b is desktop-only.

## 6. Phase 4–5 result (complete)

**4a (P2) — decided: document and leave.** The conservative max-width ghost-label metric is
deliberate (uniform box width keeps ghost labels on a shared spacing rhythm and avoids per-candidate
measurement). Added a KDoc note to `GhostLineLabel.placeAll` recording that trade-off. No behavior
change.

**4b (P4) — fixed.** Added the dominant box's rounded left/right/top/bottom to the desktop
`lastDominantDiagKey`, so a coordinate-only change now re-emits `DominantStationDiag`.

**Phase 5 — verified.**
- `:shared:test` ✓
- `:desktop:test` ✓
- `:app:testDebugUnitTest` ✓

No placement behavior changed in Phase 4 (doc + logging only), so the dominant-label left-hug
behavior from `aea4810b` is unaffected and remains verified on the emulator (`boxLeft=5px`).
