---
name: label-redundancy-measured-where-drawn
description: "Duplicate right-edge 67°/67° labels: redundancy measured at the index timestamp, but run-centered roles are DRAWN at centerOfRun; same-flat-run pairs are redundant at ANY distance"
metadata: 
  node_type: memory
  type: project
  originSessionId: 6934c1e6-0251-44cd-bf16-6004539039bd
---

Symptom (2026-07-15, all surfaces — 2 emulators, Pixel, Samsung, desktop): the hourly graph showed
**two identical forecast labels stacked at the right edge** ("67°" and "67°"), both grey `#BBBBBB`.
The upper one was displaced up off the dashed line by curve-fit and landed on the pink actual line,
so it read as an actual-series label but was forecast. Sequel to [[end_label_redundancy_suppression]].

**Two distinct bugs, both in `TemperatureLabelResolver` (`:shared`, hence every surface):**

1. **Measured where the label is NOT drawn.** Run-centered roles (`LOW`/`HIGH`/`FORECAST_*`/
   `PAST_FORECAST_*`/`LOCAL`) are drawn at `centerOfRun` — the MIDPOINT of their flat equal-value run
   — not at their own index. The redundancy gate measured from the index's own timestamp. On a 67°
   plateau spanning idx 39→52 the LOW is *drawn* at the plateau center (58.75px from END) but was
   *measured* at idx 39 (117.5px) — so the 64px `REDUNDANT_PAIR_PX` budget kept both. Non-plateau
   labels are unaffected, which is why only flat runs broke.
2. **Distance is the wrong criterion.** Fixing (1) alone left it width-dependent: 58.75px suppressed
   on Pixel, 73px survived on emulator-5556 (widthPx=584). A `LOW` and `END` on the SAME flat run of
   the SAME series with the SAME value are **one plateau labeled twice** — redundant at ANY distance.
   A pixel budget cannot express that. Rule: `tIdx in runBounds(labelTemps, idx)` ⇒ redundant.

**Why:** the gate answers "do these read as a redundant pair?" — that's a question about DRAWN
positions and about series/run identity, not about index arithmetic.

**How to apply:**
- Any new redundancy/collision rule must use the anchor the renderer will actually draw at. `runBounds`
  is now shared by `centerOfRun` and `anchorMinutes` so the two can't drift apart again.
- Symptoms that flip on **window resize / pan by one step** (desktop "went away after resizing",
  `offset=-8` suppressed vs `offset=-9` accepted one second later) = a threshold being straddled, not
  a device quirk. Treat as a knife-edge rule, not a rendering glitch.
- The existing `REDUNDANT_PAIR_PX=64` budget still governs pairs on *different* runs; the same-run
  rule is index-based so it also holds for geometry-less unit-test callers.

**Testing:** fixtures must reproduce the upstream extrema signature, not just the shape — a first
attempt passed for the WRONG reason (`END` was dropped at `deduplicateAnchors`, never reaching the
gate) because `isActual` stopped mid-window and `transitionX` was null. Production had `isActual`
throughout and `transitionX=1312` (beyond widthPx: whole window is past). Verify a new test FAILS
against the old code before trusting it — see [[testing-strategy]].
