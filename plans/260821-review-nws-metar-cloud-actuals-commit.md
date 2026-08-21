# Code Review: Commit 106e8fd — NWS cloud actuals from METAR sky condition, with self-healing repair

**Date:** 2026-08-21
**Commit:** `106e8fd9f39f21ed8831ae279715cd8f70758411`
**Scope:** 18 files, +1719/−36

## Verdict

Well-constructed commit. Pure logic isolated in `:shared`, honest data filing
(`cloudCoverLow` not `cloudCover`), determinism handled explicitly, desktop parity
maintained, and every test carries its `@Category`.

Tests run and passing:

- `./gradlew :shared:testShortShared`
- `./gradlew :app:testShortDebugUnitTest --tests com.weatherwidget.widget.handlers.HourlyObservationBackfillLocationTest`
- `./gradlew :app:testDebugUnitTest --tests com.weatherwidget.data.repository.NwsCloudActualsRoundTripTest`

## Findings

### 1. `parseCloudLayers` can abort the whole observation parse on JSON `null` (low severity, fix soon)

`shared/src/main/kotlin/com/weatherwidget/data/remote/NwsApi.kt:112`

```kotlin
val cloudLayers = parseCloudLayers(props["cloudLayers"]?.jsonArray)
```

If NWS ever emits `"cloudLayers": null` (rather than omitting the key),
`JsonNull.jsonArray` throws `IllegalArgumentException`, killing the parse before
temperature is extracted — the entire observation is dropped, not just cloud. Same
exposure at `layerObj.jsonObject` / `base?.jsonObject` (lines 143–145) for a malformed
layer entry.

The surrounding code shares this pattern for `temperature` etc., so it is
house-style-consistent, but `cloudLayers` is a new *optional* field where an explicit
null is more plausible than for required fields. A
`runCatching { ... } ?: emptyList()` around the call would make cloud strictly
non-fatal.

### 2. CLOUD-view repair probe reads 72h of observations on every render

`app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt:465-487`

The `maybeEnqueueHourlyObservationBackfill` enqueue is cooldown-guarded, but the
`repository.getObservationsInRange(...)` feed that precedes it runs unconditionally on
every CLOUD-view render, even when the decision will be "no". Acceptable cost, but it
is the one place the commit adds recurring work to a hot path.

### 3. Desktop logs run a full blend just for a log line

`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt:356-360`

`MetarCloudBlender.blend(result.rawObservations, ...)` executes per backfill solely to
produce `stats.summary()`. Cheap relative to the HTTP fetch that precedes it, but it is
dead computation when only counts are needed — a lighter `stationsWithLayers` count
would do.

### 4. High-only overcast renders as a gap in the actual curve (intended behavior awareness)

`shared/src/main/kotlin/com/weatherwidget/shared/observations/MetarSkyCover.kt` —
`lowPercent`

A report like `OVC040` alone (no sub-2000 m layer) yields `lowPercent = null`, i.e.
"not reported", so the hour drops out of the blended series while the sky was fully
measured above the low ceiling. This follows the documented §3 semantics (a
12,000-ft-limited measurement filed as low layer), but it means genuinely observed
clear-low conditions produce curve gaps. Flagging as intended-behavior awareness, not a
bug.

## Things checked and found correct

- **Bucketing**: round-to-nearest-hour with the KPAO :47 case tested;
  `filterKeys { it in startMs until endMs }` consistent between blend and DAO.
- **Determinism**: TOTAL-order sort (timestamp, stationId, lat, lon) with a dedicated
  test; tie-breaks in per-station picks rely on the pre-sorted input correctly.
- **Self-heal trigger safety**: OFFICIAL-only, QC-excluded, web-fallback-excluded basis
  prevents an unsatisfiable loop; threshold (`cloudBuckets * 2 < officialBuckets`) is
  sound given multiple reports/bucket vs ~25-30% partial METARs.
- **Synthetic-row hijack prevention**: blender excludes `NWS_BLEND` and
  synthetic-backfill stations; non-NWS branch keeps the pinned synthetic station.
- **Callers**: all `getCloudActuals` call sites updated to `.hours`;
  `maybeEnqueueHourlyObservationBackfill` signature matches the new call.
- **WorkManager safety**: no new cancel-by-name paths introduced; repair rides REPLACE
  on the existing 72h repair (delayed/not-running work — safe per AGENTS.md).

## Recommended follow-up

Item 1 is the only one worth fixing soon; items 2–3 are optional polish.

## Implementation status (2026-08-21, same day)

- **Finding 1 — fixed.** `NwsApi.parseCloudLayers` now takes `JsonElement?` and uses safe casts
  (`as? JsonArray/JsonObject/JsonPrimitive`, `JsonNull` excluded) exclusively — no unchecked
  `jsonObject`/`jsonPrimitive` conversions remain, so a JSON-null or malformed `cloudLayers`
  degrades to "not reported" instead of throwing out of `parseObservationProperties`. Regression
  tests added: `JSON-null cloudLayers degrades...` (temperature survives) and
  `malformed layer entries are skipped...` in `NwsApiCloudLayersParseTest`.
- **Finding 2 — fixed.** New pure-read pre-check `hourlyBackfillCoolingDown` +
  `hourlyBackfillSourceKey` in `HourlyObservationBackfill.kt`; `CloudCoverViewHandler` checks it
  BEFORE the 72h observation read, so a cooling-down render skips the expensive DB read entirely.
  Covered by new `HourlyObservationBackfillCooldownTest` (mockk, Short bucket).
- **Finding 3 — fixed (deleted).** The blend-for-stats block in
  `DesktopWeatherRepository.BACKFILL_CLOUD` logging was removed along with the now-unused
  `MetarCloudBlender` import; the log line reverted to its row-count form.
- **Finding 4 — no change** (intended §3 semantics).

Verified: `:shared:testShortShared`, `:desktop:testShortDesktop`,
`:app:testShortDebugUnitTest` (cooldown + location tests), and
`:app:compileDebugKotlin` / `:desktop:compileKotlin` all green.
