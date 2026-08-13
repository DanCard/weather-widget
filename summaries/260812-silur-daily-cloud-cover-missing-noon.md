# Silur daily view: cloud cover missing / 0% at noon — investigation + logging

Date: 2026-08-12
Device: Samsung SM-F936U1 (`RFCT71FR9NT`)
Source: Silurian (Silur)

## What the logs say right now

1. **The bug is not currently reproducible from stored data.** The on-device DB has 6,250
   `SILURIAN` hourly rows, and all of them have `cloudCover` in the 1–99 range — no nulls, no
   zeros. The current cache is fine; this matches "sometimes".
2. **The live render log shows the mechanism.** The daily bar's cloud segment comes from
   `DailyNoonCloudCover.resolveNoonCloudCoverRatio`, which reads the hourly `cloud_cover` at exactly
   **12:00 local**. When that noon hour is absent or its `cloud_cover` is null, the function returns
   **0 (clear)** by design — so the bar renders no cloud segment, i.e. "0% at noon" / "no cloud
   cover percentage". The icon, in contrast, still resolves from the worded condition, which is how
   you can get a "Cloudy" icon with a 0% bar.
3. **The existing `cloudMissing` diagnostic was dead code.** `cloudCoverRatioOverride` is never null
   (it is `0f` when missing), so `DAILY_RENDER … cloudMissing=…` could never fire — the "missing"
   case was indistinguishable from a genuinely clear noon.
4. **Silur's daily response has a `cloud_cover` field the app ignores.** The daily endpoint returns
   `cloud_cover` (e.g. 76, 63, 50…), but `SilurianApi` only maps high/low/condition/precip from it.
   Only the hourly noon reading drives the bar.
5. **Silur's hourly endpoint has been timing out.** `FETCH_SILURIAN_FAIL` shows a 30s request
   timeout on `/forecast/hourly`, and a current-temp Silur fetch failed at 02:28:31. A partial /
   failed fetch is another way the noon hour can end up missing.

## Logging added (to catch the next occurrence)

1. `shared/src/main/kotlin/com/weatherwidget/data/remote/SilurianApi.kt` — one DEBUG row per
   successful forecast fetch summarizing the raw `cloud_cover` payload:

   ```
   cloudCoverSummary kind=hourly total=361 present=361 missing=0 zero=0 nonInt=0 noonIssues=-
   ```

   Records, for both hourly and daily arrays: how many `cloud_cover` values are present / missing /
   zero / non-integer (e.g. a float, which `intOrNull` silently drops), plus any noon-hour issues.
   This tells us whether the API is ever returning null / 0 / float `cloud_cover`.

2. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — added
   `measuredCloudCover=` to the existing per-day `cloudDecision` line, so the next repro shows
   `measuredCloudCover=null` (noon missing) vs `measuredCloudCover=0` (genuinely clear) vs a value.

## Verification

- `:shared:compileKotlin` and `:app:compileDebugKotlin` pass.
- `:app:testShortDebugUnitTest --tests com.weatherwidget.data.remote.SilurianApiTest` passes.
- Installed on the Samsung and confirmed live logcat now emits the augmented line, e.g.:

  ```
  cloudDecision: … cloudCoverRatioOverride=0.49 measuredCloudCover=49 storedNoonCloud=null …
  ```

No commit made (per project convention).

## Next step

Once the new `cloudCoverSummary` / `measuredCloudCover=null` logs capture a repro, the likely fix is
to stop hard-defaulting missing Silur noon cloud to 0 — e.g. fall back to Silur's ignored daily
`cloud_cover` field (or a weather_code-derived default). Held off implementing that until the logging
confirms which trigger is actually happening.
