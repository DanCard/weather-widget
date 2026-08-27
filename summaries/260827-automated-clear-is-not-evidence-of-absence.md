# An automated station's CLR is not evidence of absence above 12,000 ft

**Date:** 2026-08-27
**Plan:** [plans/260827-automated-clear-is-not-evidence-of-absence.md](../plans/260827-automated-clear-is-not-evidence-of-absence.md)

## What happened

The user reported the sky was near-overcast while the widget showed 2%, then asked: "knuq 0 is
obviously wrong. Can we add a quality check or something. if other stations are reporting 75% then
say it is wrong?"

The instinct was right, the proposed rule was not, and the difference mattered.

## Why "if others disagree, drop the outlier" would have been wrong

It also fires when KNUQ reports `CLR` and KPAO reports `BKN008` — a deck at 800 ft, comfortably
inside KNUQ's range. There the disagreement is real spatial variation, KNUQ's 0 is a true
measurement, and discarding it would bias the curve upward on exactly the patchy-marine-layer
mornings the graph most needs to get right. Same symptom, opposite correct response.

## What is actually wrong

KNUQ's 0 is not a wrong measurement. It is not a measurement. Our own stored METARs reproduce the US
convention exactly:

| station | `AUTO` tag | clear code used |
|---|---|---|
| KNUQ | 787 / 787 | **CLR** ×553, SKC ×0 |
| KPAO | 0 / 140 | **SKC** ×82, CLR ×0 |
| KRHV | 0 / 54 | **SKC** ×20, CLR ×0 |

`CLR` is emitted by an automated station and means "no cloud detected **below 12,000 ft**" — an ASOS
ceilometer is a vertical laser with a hard ceiling. `SKC` is emitted by a human observer and means
"sky clear", a whole-sky assessment. KPAO's deck was at **18,000 ft**; KNUQ physically cannot see
it. The blend was counting that silence as a vote worth **69% of the IDW weight**, because
inverse-square weighting rewards it for being nearest.

Over 130 hours with 2+ stations reporting, this configuration occurs on **21 (16.2%)**.

## The rule

A clear reading from an automated station is excluded from a bucket's blend when another station in
that bucket reports a layer above 12,000 ft — the layer it could not have seen. Narrow by
construction: never fires on `SKC`, never fires against a low deck, needs no threshold or outlier
statistic, and never empties a bucket.

**No schema change was needed.** The classification is fully derivable from stored columns, and the
key was recognising a third class: `isMetar = false` marks the `/observations` endpoint's 5-minute
feed, which is an instantaneous ceilometer sample and therefore automated by construction (3,799
rows). Parsing `rawMetar` alone would have classified only 239 of 498 clear rows; adding that class
closes it to 100%.

## What changed

- **`CeilometerBlindSpot`** (shared, pure): `ASOS_CEILING_M = 3658`, `isAutomatedClear`,
  `highestReportedBase`, `filterBlindClears`.
- **`MetarCloudBlender.blend`** applies it between `contributions` and `valueByDistance` — the one
  place the station blend turns readings into a number.
- The **dominant-station label** now names a station that actually fed the value. Labelling the
  curve with a station whose own reading was excluded would name a source that contradicts what is
  drawn.
- **`Stats.ceilometerBlindBuckets`**, surfaced in `summary()` beside `shadowed` and
  `metarPreferred`. This decision changes a drawn value, so it must be visible in `app_logs` without
  a rebuild: a curve reading far above the nearest station's own report is correct exactly when this
  counter is non-zero.

## Verification

All eight rows of the plan's table pass: `:shared` 2598, `:desktop` 887, `:app` 3159, zero failures.
A mutation check (disabling the above-ceiling predicate) fails three of the new tests.

**Live on device.** After the restart, with KNUQ `CLR` at 3.8 km, KPAO `BKN180` (5,490 m) at 6.1 km
and KSJC (3,050 m) at 15.9 km, the renderer's own label diagnostics show the actual curve ending at
`75%`. The progression across today's three changes on this one scene:

| | blend |
|---|---|
| low-layer preference (this morning) | **2%** |
| total-cloud reversal | **~28%** |
| + ceilometer blind spot | **75%** |

75% is what the two stations that can physically see the deck were reporting. KSJC's own mid base
(3,050 m) is *below* the ceiling, so it alone would not have triggered the rule — KPAO's 18,000 ft
deck did, which is the intended narrowness working.

## Risk accepted

On a genuinely clear day where one distant station reports thin high cirrus, the nearest station's 0
is dropped and the blend reads higher than the sky looks. That is the same trade the total-cloud
reversal made deliberately, and the `h` glyph trail says which layer is responsible. The rule cannot
fire at all unless some station actually reported a layer above 12,000 ft.
