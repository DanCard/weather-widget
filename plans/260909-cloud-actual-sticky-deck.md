# Above-ceiling cloud decks persist across their station's anchor window

Date: 2026-09-09
Modules: `:shared` (blend), consumed by `:desktop` and `:app`
Status: implemented, merged to `main`

## Problem

On the desktop cloud-cover view (`weatherSource = SILURIAN`, actuals from Synoptic) the solid
actual line drew single-point spikes — 75/44/19 % at each hourly `:53` METAR — on a 1–2 % baseline,
plus a 50-minute 44 % plateau. Full evidence and root cause:
`findings/260909-cloud-actual-spikes-from-synoptic-blend.md`.

The spike mechanism: KSJC's 13,000 ft deck is above every ASOS ceilometer's 12,000 ft ceiling, so
it appears only in the hourly METAR. Under the max-of-blended-bands rule the line follows that one
candidate's mid band, then snaps back. Cloud aloft does not actually vanish between the hourly
reports — the open question left in `plans/260827-observation-site-merge-for-actual-series.md:191`.

## Change

`MetarCloudBlender.blend`:

1. Precompute `aboveCeilingCarriersByStation`: per station, the readings whose highest reported
   base exceeds `CeilometerBlindSpot.ASOS_CEILING_M` (3,658 m) and whose cover is non-zero.
2. `blendedLayer` now takes, per station, the **max** band value over the anchored reading and any
   above-ceiling report that station made within `ANCHOR_TOLERANCE_MS` (30 min). The total blend
   and the per-candidate anchor are unchanged; only the bands carry the deck forward.
3. `Stats.stickyDeckPoints` counts candidates anchored by a carried-forward deck and is surfaced
   as `stickyDeck=N` in the `CLOUD_SERIES` / `BACKFILL_CLOUD` summary.

Deliberately limited to **above-ceiling** reports: a below-ceiling mid layer is inside every
ceilometer's range, so the near station can see it and the ordinary per-candidate behaviour
applies.

## Result

Reported window, `stickyDeck=90`:

```
04:00–05:50  0 %
05:53–06:47  1 %
06:50–07:20  44 %
07:25–09:20  75 %
09:25–10:20  44 %
10:25–14:15  19 %
```

No spikes; the line is a staircase at KSJC's reported sky. Screenshot:
`/tmp/ww-cloud-sticky-deck-20260909.png`.

Trade-off, accepted: the 16 km station's deck is trusted for its hour even though the 3.8 km
station reports `CLR`, because that ceilometer physically cannot see the layer.

## Tests

- New `shared/.../actuals/AboveCeilingDeckPersistenceTest.kt`:
  - the deck stays on the mid band at `:53 + 2 min` and `:53 + 22 min` and the line is the max of
    the blended bands;
  - it does **not** persist at `:53 + 32 min` (outside the anchor tolerance);
  - a below-ceiling mid layer does not persist;
  - the `stickyDeck=` counter is in the stats summary.
- Full JVM suite green (4076 tests: 1553 shared, 376 desktop, 2147 app).
- Instrumented suite green on `emulator-5554`.

## Alternatives tried and parked

- `cloud-actual-despike-median` (`4a8de628`) — median filter of the emitted series. Removed the
  spikes but left the 44 % plateau and rewrites measured values.
- `cloud-actual-idw-total` (`580dd8d6`) — line = IDW total. Removed the plateau but left the
  spikes and let glyphs sit above the line.
