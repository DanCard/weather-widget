# Suppress the Dominant-Station Label for Synthetic Backfill Rows

**Date:** August 9, 2026
**Devices:** Samsung Galaxy Z Fold (SM-F936U1), Pixel 7 Pro, desktop
**Status:** Implemented, unit-tested, installed on both Android devices, desktop rebuilt and restarted (uncommitted)
**Follows:** [summaries/260809-hourly-dominant-station-label.md](260809-hourly-dominant-station-label.md) (commit `81c7dea8`)

---

## 1. Question that started it

> In commit `81c7dea8` — is the station code in the output string? `knuq` should equal the station code.

Yes. `DominantStationLabel.format` builds `stationId.lowercase() + " " + formattedTemp` (plus the
`@ 5:15 pm` clause added later by `e29d139b`), and that `stationId` comes straight from
`observations.stationId` via `BlendContribution` — no mapping table, no friendly-name substitution.
`knuq` is `KNUQ` (Moffett Field), and the temperature beside it is that station's **raw** reading,
not the blended value the pink line draws.

But answering it surfaced a case where the token is *not* a station code.

## 2. The defect

For the label's purposes there are two kinds of non-NWS station, and they need opposite treatment:

- **Real non-NWS stations** — Synoptic mesonet sites (`AW020`, `LOAC1`) and personal weather
  stations. These arrive under the NWS display source and are genuine thermometers. They are the
  whole reason the label exists.
- **Synthetic `<SOURCE>_MAIN` backfill rows** — `HistoricalActualsBackfill` re-files a
  forecast-only source's own hourly list as observation rows so the actual line still renders.

The second kind was being named. `open_meteo_main 71.2°` is three problems at once: an internal
identifier leaking into the UI, a "station" that is really the provider's own forecast, and
information the API indicator in the corner already shows.

**It was not an edge case — it was the guaranteed outcome under every source but NWS.**
`blendObservationSeries` filters candidates to `observation.api == displaySourceId`
(`ActualTemperatureSeriesBuilder.kt:846`), and forecast-only sources contribute exactly one station
family: the synthetic row, at `distanceKm = 0`. The synthetic deprioritisation at line 441 only
bites when a real station also competes, and under Open-Meteo/Silurian/WeatherAPI/Tomorrow.io there
never is one. So the synthetic row holds 100% of the weight and is always dominant.

This also contradicted the label's own stated contract. Its KDoc draws the line at `rawTemp` vs
`resolvedTemp` to avoid printing "a forecast in disguise" beside a callsign — but that guard sits one
level too shallow. It catches a real station whose value was extrapolated, and misses a row whose
`rawTemp` was a forecast before it ever reached the blend.

## 3. Why nothing on the row gave it away

The backfill has to look like a station to drive the actual line at all, so it carries
`stationType = "OFFICIAL"`, `distanceKm = 0f`, and resolves with `sourceKind = "observed"`. Every
field a caller might filter on says "real official station reading." The only reliable test is
`ObservationSourceMatcher.isSyntheticBackfillStation(stationId, sourceId)`, which needs the display
source id — something a text formatter has no business knowing.

`StationDailyExtremes.stationDailyExtreme` (`StationDailyExtremes.kt:67-68`) already refuses to name
`NWS_BLEND` or a synthetic backfill when it picks a station. The graph label was simply the surface
that hadn't adopted that policy.

## 4. The change

**Carry the answer on the data, not the caller.**

- `BlendContribution` gained `isSynthetic: Boolean = false`
  (`ActualTemperatureSeriesBuilder.kt:81`), populated from the `isSynthetic` the blend loop
  *already computes* at line 389 for its own weighting — no new work, no new source lookup. It
  threads through the private `ContributionMeta` into both capture paths, so the Blend tab's full
  breakdown carries it too (that tab previously could not distinguish the row either, since
  `sourceKind` reports `"observed"` for it).

- **A `BlendContribution` overload of `DominantStationLabel.format`**
  (`DominantStationLabel.kt:86`) is now the production entry point:

  ```kotlin
  if (contribution == null || contribution.isSynthetic) return null
  ```

  It then delegates to the existing string formatter, which keeps its own tests and its
  lowercase/`@ time`/Celsius rules untouched.

- **Both call sites collapsed to it.** Android (`TemperatureStateResolver.kt:345`) and desktop
  (`TemperatureGraph.kt:750`) each went from four repeated `?.contribution?.field` plucks to one
  argument.

### Why an overload rather than an `isSynthetic` parameter

A defaulted `isSynthetic: Boolean = false` on the existing `format` would mean a future call site
that forgets it silently gets the buggy behavior. The overload makes the gated path the *shorter*
one to write, so the safe call is also the convenient one. The primitive `format` stays public for
the string rules the tests exercise and for callers that already know the row is real.

## 5. Behavioural result

| Display source | Before | After |
|---|---|---|
| NWS, real station dominant | `knuq 62.6° @ 9:55 pm` | unchanged |
| NWS, mesonet/PWS dominant | `aw020 65° @ …` | unchanged (still named — this is the point of the label) |
| NWS, total station outage → `NWS_MAIN` | `nws_main 83.2° @ …` | no label |
| Open-Meteo / Silurian / WeatherAPI / Tomorrow.io | `open_meteo_main 71.2° @ …` (always) | no label |

The label is effectively an NWS-mode feature now. That is honest: NWS is the only mode where a blend
of distinct thermometers exists to explain.

## 6. Verification

- **`ActualsSyntheticBackfillPriorityTest`** (new, 2 tests): the dominant contribution under
  Open-Meteo is flagged synthetic, and a real station winning under NWS is not. The first also pins
  `stationType == "OFFICIAL"` and `sourceKind == "observed"` on that same row, documenting in the
  test why the flag is the only way to tell.
- **`DominantStationLabelTest`** (new, 3 tests): synthetic → null; the *same row with the flag off* →
  not null, so the suppression is provably the flag's doing and not some other field; null
  contribution → null; plus a field-passthrough test for the overload.
- **Both guards proven to fail when defeated.** Stripping the `isSynthetic` check from `format` and
  forcing `isSynthetic = false` at the capture site failed exactly those two assertions and nothing
  else in the 32-test run.
- Full `:shared` suite green on `--rerun-tasks`; `:app` and `:desktop` compile clean; `cpdCheck`
  report unchanged (does not mention the touched files).
- Desktop rebuilt via `scripts/buildStart-desktop.sh` and restarted; `./gradlew installDebug` on both
  devices. Live desktop screenshot under NWS still renders `knuq 62.6° @ 9:55 pm` — the case that
  must keep working does.
- **Not visually confirmed:** the suppressed case on a real screen. Seeing it requires switching the
  configured primary source to a non-NWS one, which rewrites the user's source ordering, so it was
  left alone.

## 7. Files touched

| File | Change |
|---|---|
| `shared/…/actuals/ActualTemperatureSeriesBuilder.kt` | `isSynthetic` on `BlendContribution` + `ContributionMeta`; populated at both capture sites |
| `shared/…/graph/DominantStationLabel.kt` | `BlendContribution` overload of `format` carrying the gate; KDoc |
| `app/…/widget/handlers/TemperatureStateResolver.kt` | call site → overload |
| `desktop/…/desktop/TemperatureGraph.kt` | call site → overload; comment |
| `shared/…/test/actuals/ActualsSyntheticBackfillPriorityTest.kt` | +2 tests, `blend()` helper takes the capture cutoff |
| `shared/…/test/graph/DominantStationLabelTest.kt` | +3 tests, `contribution()` helper |

## 8. Deliberately not done

- **A `weightShare` floor.** Nothing currently gates on it, so a station holding 22% of the blend is
  named exactly as confidently as one holding 95% — in the first case the label misattributes the
  line. A ~40% floor would make the label mean "this station *is* the line." `weightShare` is
  already computed; this is a small follow-up.
- **Marking personal stations.** A backyard PWS dominating the blend is the classic explanation for
  "the app says 78° and it's 71°", and `stationType == "PERSONAL"` is already on the contribution
  (`BlendTableFormatter.LEGEND` already teaches `O`/`P`). A trailing marker would cost width on a
  label that is already suppressed when space is tight.
- **Substituting `stationName` for the id.** More legible for cryptic mesonet ids, but far wider, and
  keeping the id lets you cross-reference the graph against the Blend tab's station column.
