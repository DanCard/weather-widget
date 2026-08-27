# An automated station's CLR is not evidence of absence above 12,000 ft

**Date:** 2026-08-27
**Status:** Proposed — implementing on request

## The report

Observed 2026-08-27 ~14:00: the sky over Mountain View was close to overcast, the widget said 2%,
and after the total-cloud reversal it said ~28%. NWS's nearest station, KNUQ at 3.8 km, was
reporting **0**.

## What is actually wrong

KNUQ's 0 is not a wrong measurement. It is not a measurement.

| station | `AUTO` tag | clear code used |
|---|---|---|
| KNUQ | 787 / 787 | **CLR** ×553, SKC ×0 |
| KPAO | 0 / 140 | **SKC** ×82, CLR ×0 |
| KRHV | 0 / 54 | **SKC** ×20, CLR ×0 |

Our own stored METARs reproduce the US convention exactly:

- **`CLR`** is emitted by an *automated* station and means "no cloud detected **below 12,000 ft**".
  An ASOS ceilometer is a vertical laser with a hard ceiling; it is physically incapable of
  reporting anything higher.
- **`SKC`** is emitted by a *human observer* and means "sky clear" — a whole-sky assessment.

At the time of the report KPAO was reporting `BKN180` — a broken deck at **18,000 ft**. KNUQ cannot
see 18,000 ft. Its `CLR` is silence, and the blend has been counting that silence as a vote worth
**69% of the IDW weight**, because inverse-square weighting rewards it for being nearest.

## Why the obvious rule is the wrong rule

"If other stations report 75%, mark the 0 wrong" also fires when KNUQ reports `CLR` and KPAO
reports `BKN008` — a deck at 800 ft, comfortably inside KNUQ's range. There the disagreement is real
spatial variation, KNUQ's 0 is a genuine measurement, and discarding it would bias the curve upward
on exactly the patchy-marine-layer mornings the graph most needs to get right. Same symptom,
opposite correct response.

The rule must be grounded in the instrument's limitation, not in disagreement.

## The rule

**A clear reading from an automated station carries no information about layers above the
ceilometer ceiling.** So it is excluded from the blend for a bucket — and only for that bucket —
when another station in the same bucket reports a layer above that ceiling.

Narrow by construction:

1. Fires only on an *automated* clear reading, never on `SKC`.
2. Fires only against a layer **above 12,000 ft**. Any disagreement about low cloud leaves the 0
   fully weighted.
3. Needs no threshold and no outlier statistics. It is not a judgement about how much disagreement
   is too much; it is a fact about what the instrument can see.
4. Never empties a bucket: if every contribution would be dropped, the bucket keeps its original
   readings. A degraded answer beats no answer.

## Classifying a reading, with no schema change

The distinction is already fully derivable from stored columns. Measured over 7 days of NWS rows:

| reading | how it is recognised | rows |
|---|---|---|
| 5-minute ASOS sample | `isMetar = false` — the instantaneous ceilometer feed, automated by construction | 3,799 |
| automated METAR | `rawMetar` contains `CLR` / `NCD` | — |
| human METAR | `rawMetar` contains `SKC` / `CAVOK` — **trusted, never dropped** | 113 |
| `isMetar = true`, no `rawMetar` stored | cannot tell; **trusted** — dropping a real measurement on a guess is worse than keeping a possibly-blind one | 28 |

Parsing `rawMetar` alone would have covered only 239 of 498 clear rows; adding the `isMetar = false`
class closes it to 100%.

## Proposed implementation

1. **Shared** `CeilometerBlindSpot` — pure, no platform types:
   - `ASOS_CEILING_M = 3658` (12,000 ft).
   - `isAutomatedClear(reading)` per the table above.
   - `highestReportedBase(reading)` from the stored band base columns.
   - `filterBlindClears(contributions)` — drops automated clears when a sibling reports a base above
     the ceiling; returns the input unchanged when that would empty the list.
2. **`MetarCloudBlender.blend`** applies it between `contributions` and `valueByDistance`. That is
   the one place the station blend turns readings into a number, so it is the one place this
   belongs.
3. **Diagnostics.** A `ceilometerBlindBuckets` counter on the existing `Stats`, surfaced in
   `summary()` beside `shadowed` and `metarPreferred`. This decision changes a drawn value and must
   be visible in `app_logs` without a rebuild.

## Verification

| # | Kind | What it pins | Result |
|---|---|---|---|
| 1 | Unit (shared) | `CeilometerBlindSpotTest` classification — 5-min ASOS row; `CLR` METAR; `SKC` and `CAVOK` trusted; unclassifiable METAR trusted; a non-clear automated reading is not "clear" | 13/13 pass |
| 2 | Unit (shared) | Same class, the filter — drops the blind clear against an 18,000 ft layer; keeps it against an 800 ft deck; keeps it when no base is reported; a high base with no cover does not trigger it; never empties the list; `SKC` kept even against a high layer | included above |
| 3 | Unit (shared) | Ceiling boundary is exclusive: a layer exactly at 3,658 m leaves both readings, one metre above drops the blind clear | included above |
| 4 | **Integration** (shared) | `CeilometerBlindSpotBlendIntegrationTest` — the measured scene through `MetarCloudBlender.blend`: KNUQ `CLR` 3.8 km / KPAO `BKN180` 6.1 km / KSJC 15.9 km blends to >= 70 and reports `ceilometerBlind=1` in `summary()` | 3/3 pass |
| 5 | Regression (shared) | Same test class — the patchy marine layer (`BKN008`) keeps the near clear reading dominant (< 30) with the counter at 0, and an `SKC` station keeps its weight against a high deck | included above |
| 6 | Mutation check | Disabling the above-ceiling predicate fails #1, #3 and #4. Restored | caught |
| 7 | Full suites | `:shared` 2598, `:desktop` 887, `:app` 3159 | 0 failures |
| 8 | On-device | Desktop rebuilt and restarted at 14:17 | pass — see below |

### Live confirmation

Station state in the bucket at the time of the restart:

| station | dist | reading | base | classification |
|---|---|---|---|---|
| KNUQ | 3.8 km | `CLR` (AUTO) | — | automated clear, **dropped** |
| KPAO | 6.1 km | `BKN180` | 5,490 m | above the 3,658 m ceiling — triggers the rule |
| KSJC | 15.9 km | `FEW065 SCT080 BKN100` | 3,050 m | keeps its weight |

The renderer's own label diagnostics show the actual curve ending at **75%**:

```
PLACED "75%" idx=140 ... reason=peak
PLACED "75%" idx=150 ... reason=end
```

The progression on this one scene, all three changes of the day:

| | blend |
|---|---|
| low-layer preference (this morning) | **2%** |
| total-cloud reversal | **~28%** |
| + ceilometer blind spot | **75%** |

75% is what the two stations that can physically see the deck were reporting. Note that KSJC's own
mid base (3,050 m) sits *below* the ceiling, so it alone would not have triggered the rule — it was
KPAO's 18,000 ft deck that did, which is exactly the intended narrowness.

## Risk

On a genuinely clear day where one distant station reports thin high cirrus, the nearest station's
0 is dropped and the blend reads higher than the sky looks. That is the same trade the total-cloud
reversal already made deliberately — high cloud counts as cloud — and the `h` glyph trail says which
layer is responsible. The rule cannot fire at all unless some station actually reported a layer
above 12,000 ft.
