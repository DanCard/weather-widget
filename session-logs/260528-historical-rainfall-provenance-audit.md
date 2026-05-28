# Session Log — Historical rainfall provenance audit (all APIs)

**Date:** 2026-05-28
**Branch:** main
**Follow-up to:** the NWS day/night rain fix (`9b3d55e`) and NWS dead-field cleanup (`bef4620`)
**Area:** how each weather source obtains/stores *actual* (historical) rainfall

---

## Why this audit

After fixing NWS (past-day rain was showing a forecast as a measured actual), the user asked whether the other APIs had the same class of problem — and noted a general dislike of fallbacks that hide bugs. This session audited every source's historical-rainfall path and fixed the provenance issues.

---

## Audit findings

**Structural root:** for all non-NWS sources, "actuals" come from `ForecastRepository.saveHistoricalActuals` (`:801`), which takes the source's hourly data, keeps the past hours (`dateTime <= now`), and stores them as `${id}_MAIN` pseudo-observations stamped `stationType="OFFICIAL"`, name `"History Backfill"`, comment *"ground truth."* Whether that's truly measured depends on whether the source has a real history product — and several don't.

**Critical interaction:** the NWS measured-only fix (`resolveDailyPrecip`'s `allowForecastFallback` gate) does NOT protect these sources, because their `_MAIN` rows always carry a precip value → `resolveDailyPrecip` always takes BRANCH 1 (measured). The gating had to happen at the *write* (backfill) side instead.

**Per-source provenance:**

| Source | Real history? | Past "actual" precip is really… | Units |
|--------|:--:|---|---|
| NWS | ✅ station obs | measured gauge; null when none | mm (precipitationLastHour) |
| WeatherAPI | ✅ `/history.json` (3d) | history endpoint, native `precip_mm` | mm |
| Silurian | ✅ `/history/hourly` (3d) | history endpoint | inches→mm (`precipitation_accumulation` ×25.4) |
| Open-Meteo | ⚠️ `past_days` | reanalysis/measured blend (decent) | mm native |
| Tomorrow.io | ❌ ≤24h limit | nowcast, intensity-derived | in/hr ×25.4 |
| OpenWeatherMap | ❌ none | **pure past forecast** | inches→mm |
| Visual Crossing | ❌ forecast-only | **pure past forecast** | inches→mm (unitGroup=us) |

Units checked out everywhere (all sources request `units=imperial`/`us` → inches → ×25.4 is correct). The real issues were provenance, not arithmetic.

Findings ranked: (1) OWM & Visual Crossing present forecast as measured actual — the NWS bug, worse (no real data); (2) Tomorrow.io can't show real history beyond ~24h, and uses an *intensity* as an hourly amount; (3) the `"ground truth"`/`"OFFICIAL"` labeling overclaims; (4) minor — Silurian's 3-field precip fallback was fragile.

---

## Decisions (via user)

- **Forecast-only sources (OWM, Visual Crossing):** suppress past-day actuals — don't persist their backfilled precip as measured. Mirror the NWS measured-only contract.
- **Cleanups:** fix the "ground truth" labeling/comment; pin down Silurian's precip field; ensure Tomorrow.io past labels don't imply a measurement beyond its 24h window.

---

## Changes (uncommitted, on top of `bef4620`)

1. **`WeatherSource.kt`** — added `providesHistoricalActuals: Boolean = false`. `true` for NWS, Open-Meteo, WeatherAPI, Silurian; `false` (default) for Visual Crossing, OpenWeatherMap, Tomorrow.io, Generic.
2. **`ForecastRepository.saveHistoricalActuals`** — `precipAmountMm = if (source.providesHistoricalActuals) hour.precipAmountMm else null`. Forecast-only sources no longer store past-day precip as actuals (temperature backfill kept). Fixed the misleading "ground truth" comment. This also resolves the Tomorrow.io concern (no real history → no measured precip persisted).
3. **`SilurianApi.parsePrecipAmountMm`** — collapsed the speculative 3-field fallback to the only field the API emits (`precipitation_accumulation`, confirmed by test fixtures), inches→mm, with a clarifying comment.
4. **New `WeatherSourceHistoricalActualsTest`** (`ShortDuration`) — locks the provenance policy so any newly-added source must make an explicit true/false decision instead of silently defaulting to forecast-as-actual.

**Verification:** full `./gradlew testDebugUnitTest` — BUILD SUCCESSFUL. No existing test relied on VC/OWM/Tomorrow.io backfill precip, confirming nothing downstream depended on the dishonest data.

---

## Deliberate non-changes (flagged for review)

- **Left `stationType = "OFFICIAL"`** — it drives a badge in `WeatherObservationsActivity` (obs inspector); changing it is a separate UI decision. Only the inaccurate comment was fixed.
- **Scoped to precip only.** Temperature actuals for forecast-only sources are still forecast-derived (same provenance question, but outside the rainfall-focused ask). Candidate follow-up for full consistency.

---

## Key lesson

The same principle — *never render a forecast as a measurement* — had to be enforced at two different layers depending on where the substitution happens: at **read** time for NWS (`resolveDailyPrecip` fallback gate, sparse station obs) and at **write** time for the other sources (`saveHistoricalActuals` flag, forecast backfilled as actual). A single gate wouldn't have covered both. (Saved to memory: `historical_actuals_provenance.md`.)
