# Non-total actual cloud layers

**Date:** 2026-08-27
**Status:** Approved for implementation
**Scope:** Shared actual-cloud pipeline plus Android and desktop cloud-cover graphs

## Evidence

1. The existing pink actual line already draws the provider's observed total. NWS/METAR rows do
   not store an independent total; `VisibleCloudCover` resolves it as `max(low, mid, high)` and
   `MetarCloudBlender` spatially blends those station totals.
2. At the active Mountain View site, stored NWS observations contain 2,863 low values, 79 middle
   values, and no high values. The layer data is therefore useful, but it is currently excluded
   from `MetarCloudBlender.Result.bands` because it is tagged `CUMULATIVE_LAYERS`.
3. Repeated actual middle/high trails remain visually noisy when they duplicate the total line.

## Decision

1. The pink actual line remains the only total-cloud line.
2. Draw lowercase `l`, `m`, and `h` actual trails only where the corresponding layer differs
   exactly from the actual total.
3. Do not apply the forecast 5% visibility floor to actual layers. A reported 0-4% layer remains
   visible when it differs from total; missing stays missing.
4. Retain the small pink normal-weight actual style and its widened spacing.
5. Preserve forecast behavior, including its 5% floor and existing middle/high trails.
6. For station observations, spatially blend each layer from the same accepted station anchors
   used for the total. Do not compare a single station's layer against a blended total.
7. Continue rejecting `TOTAL_ENVELOPE`, which carries heights rather than layer-cover percentages.

## Implementation

1. Extend `CloudBands` and the renderer inputs with low actual coverage.
2. Populate provider low/mid/high bands and produce blended NWS/METAR cumulative layers.
3. Generalize the glyph placer's actual-only suppression from matching 0%/100% endpoints to any
   exact layer/total match, with `minCover = 0` for actual placement.
4. Add the actual `l` trail to Android and desktop with the same style/obstacle handling as `m/h`.
5. Update shared pipeline tests and both rendered-platform regression suites.

## Verification

1. Shared tests cover low=0 retained below a different total, any exact total match suppressed,
   NWS cumulative layers reaching the graph model, and independently blended station layers.
2. Android Robolectric and desktop Compose tests cover the `l` trail and total-match suppression.
3. Run focused tests, Android APK and desktop distributable builds, `git diff --check`, and runtime
   screenshots for NWS and Open-Meteo where the stored window provides useful layer variation.
