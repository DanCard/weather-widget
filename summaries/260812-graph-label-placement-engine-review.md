# Session summary — graph label-placement engine review & architecture refresh

**Date:** 2026-08-12
**Plans:** [plans/260812-code-review-graph-label-placement-engine.md](../plans/260812-code-review-graph-label-placement-engine.md) ·
[plans/260812-architecture-assessment.md](../plans/260812-architecture-assessment.md)

Full code review of the temperature label-placement subsystem (`shared/graph/`) followed by a
four-phase, behaviour-preserving restructure of its two god files, plus a flaky-test hardening and an
`ARCHITECTURE.md` refresh.

---

## Session flow

1. **Architecture assessment** — surveyed the three modules (`:app` ~46k, `:shared` ~18k,
   `:desktop` ~14k LOC) and rewrote `arch/ARCHITECTURE.md` (was 3 months stale; still described the
   old "NWS + Open-Meteo dual API" model). Ranked complexity hotspots.
2. **Code review** — read all 38 `shared/graph/` files + both platform call sites; findings written to
   `plans/260812-code-review-graph-label-placement-engine.md` (2 HIGH, 7 MEDIUM, 3 LOW).
3. **Phase 1–4** — executed the review's recommendations in phased, individually-committed steps.
4. **Flaky test** — diagnosed and hardened `DailyCloudCoverSiteParityRoboTest`.

---

## What shipped

| Phase | Finding(s) addressed | Change |
|---|---|---|
| 1 | H1 (collision rule drifted across 3 copies; forced placers ignored hard/icon bounds) + H2 | Extracted `CurveIntrusion.kt` + `CollisionTester.kt`; all three passes now share one `obstacles()`/`curve()` rule; forced placers take `drawnIconBounds` + `reservedHardBounds` |
| 2 | M1 (`TemperatureLabelEngine` 1202-LOC god file) | Split into `ActualExtremePlacers` (unified high/low), `CurveFitPlacer`, `ValleyCascade`; engine → 494 LOC |
| 3 | M2/M3 (`TemperatureLabelResolver` 1031-LOC god file) | Split into `LabelCandidateCollector` (530), `LabelSuppression` (206), `LabelGeometryResolver` (224); resolver → 171-LOC facade |
| 4 | M4/M5/M6/L2/L3 | `ValueSlot` data class; `DECLUTTER_THRESHOLD_MAX` constant; comparator precompute (removes O(n² log n)); doc comments |

Also hardened `DailyCloudCoverSiteParityRoboTest` against a real-IO race (flaky in
`:app:testLongDebugUnitTestFresh`).

### Net restructure

`TemperatureLabelEngine` 1202→494 LOC, `TemperatureLabelResolver` 1031→171 LOC, plus 8 new
single-purpose files: `CurveIntrusion`, `CollisionTester`, `ActualExtremePlacers`, `CurveFitPlacer`,
`ValleyCascade`, `LabelCandidateCollector`, `LabelSuppression`, `LabelGeometryResolver`. Public API
(`computePlacements`, `PlacedLabel`, `formatTemp`, `computeExtremaIndices`,
`collectLabelCandidates`, `sortLabelCandidates`, `resolveCandidateGeometry`,
`ESSENTIAL_LABEL_ROLES`) unchanged.

---

## Verification

- `:shared:test` — green throughout (806 tests).
- `:desktop:test` — green (same two pre-existing `TemperatureGraph` warnings).
- `:app:compileDebugKotlin` — clean.
- Full `:app:testLongDebugUnitTestFresh` — green after the hardening (was flaky before).

One real catch: `LogTest.resolver placement breadcrumbs reach the installed sink` caught that
Phase 3 renamed the logcat tag `"TempLabelResolver"` → `"LabelCandidateCollector"`; reverted the tag
to preserve the on-device `grep TempLabelResolver` workflow.

---

## Flaky test root cause

`DailyCloudCoverSiteParityRoboTest` leg B called `advanceUntilIdle()` then asserted on render output.
`advanceUntilIdle()` drains only the *virtual* test scheduler, not Room's real executor threads, so
under full-suite load the render was still suspended on a DAO read when the asserts ran. Fixed by
polling the `startup_done` / `startup_ERROR` / `startup_CANCELLED` breadcrumb `WidgetStartupCoordinator`
writes after `WidgetRenderer.updateWidgetWithData` returns, with a bounded real-time deadline.

---

## Still open (deliberately deferred)

- **M7** — unify `findLocalExtremaIndices` (Int in `GraphLabelPlacementUtils` vs Float in
  `TemperatureExtrema`); the two have subtly different plateau handling, so unifying risks behaviour.
- **L1** — demote the engine's per-render `Log.d` breadcrumbs to `Log.v`; the DEBUG breadcrumbs are
  already gated to `LOGGED_ROLES`, and demoting them is a debuggability tradeoff left to the user.
- **Unused `widthPx`/`heightPx`** threaded through `CurveFitPlacer.checkBlockers` — mechanical but
  touches three signatures for marginal value.
- `plans/260812-architecture-assessment.md` and this summary were the final uncommitted docs.

## Commits

```
f7d096cb Clean up label engine: naming, comparator precompute, and docs
bb91b9c9 Harden DailyCloudCoverSiteParityRoboTest against a real-IO race
97eeca5e Split TemperatureLabelResolver into candidate/suppression/geometry modules
320760bf Split TemperatureLabelEngine into single-purpose placer modules
8e4c236e Extract a single collision rule and fix forced-placer hard-bound drift
054d4425 Refresh architecture docs to reflect current codebase
```
