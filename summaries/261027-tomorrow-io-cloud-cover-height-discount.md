# Tomorrow.io Cloud Cover Height-Based Discount

## User Prompts

1. "desktop: Does tomorrow break down their cloud cover % ?  Their cloud cover % doesn't match reality, high % cloud cover doesn't match   reality."
2. "Can we use height fields to adjust cloud cover % ?"
3. "Maybe discount by 25% if there are no low clouds?  Discount another 25% if there are no medium clouds?"
4. "It was mostly sunny today at lunch, can we look at what tomorrow said?  I'd like the cloud cover for today to be less than 50% for today at lunch?  Maybe 33% would be accurate."
5. "Can we do 33% and 33% for a total of 66% discount?"
6. "yes"
7. "Should I expect to see a difference for today or tomorrow, or the next day?  I'm not seeing a change."
8. "I used the refresh button and saw a change for the next two day, but not today."
9. "yes"
10. "that worked well, thank you!  Write a session summary to summaries/ dir"

## Problem & Root Cause

1. **Symptom:** Tomorrow.io was reporting 70%–100% cloud cover on days and hours that were visually mostly sunny or clear with thin high-altitude cirrus (e.g. today at lunch 11:00 AM – 1:00 PM, Tomorrow.io forecasted 99%–100% overcast and recorded 76%–78% cloudy observations). In contrast, NWS reported 25%–34% (Sunny / Mostly Sunny) and human observation was ~33%.
2. **Evidence & Provider Limitation:**
   - Tomorrow.io's API (v4 timelines) does not provide separate vertical layer percentages (`cloudCoverLow`, `cloudCoverMid`, `cloudCoverHigh`). It only provides a single aggregate `cloudCover` (0–100%) alongside geometric height fields (`cloudBase` and `cloudCeiling`).
   - Because Tomorrow.io supplies no layer breakdown, the app's weighted perceptual resolver (`VisibleCloudCover`, which weights low 50%, mid 25%, high 25%) had no bands to calculate with and was forced to fall back directly to Tomorrow.io's raw `cloudCover` total.
   - Tomorrow.io's raw `cloudCover` treats high-altitude, semi-transparent cirrus clouds at 44,000–52,000 ft as 70%–100% coverage, making bright sunny skies appear as solid dark overcast on the widget and graph.

## Solution & Mathematical Formulation

Because `cloudBase` represents the altitude of the *lowest visible cloud layer*, the absence of lower decks can be inferred with high certainty:
- **Low clouds present** (`cloudBase < 3,000 m` / ~10,000 ft): **0% discount** ($\times 1.0$) — dense low stratus and cumulus decks retain full reported weight.
- **No low clouds** (`3,000 m <= cloudBase < 8,000 m` / ~10,000–26,000 ft): **33% discount** ($\times 0.67$, or $\times \frac{2}{3}$) — mid-altitude altocumulus and altostratus decks allow significantly more light through.
- **No low AND no medium clouds** (`cloudBase >= 8,000 m` / > 26,000 ft): **66% discount** ($\times 0.34$, or $\times \frac{1}{3}$) — high cirrus and cirrostratus decks near the top of the troposphere / stratosphere boundary are discounted to match human visual perception.
- **Missing or null cloudBase**: Retains raw reported `cloudCover` as a safe fallback.

### Real Impact at Lunch (2026-10-27 12:00 PM)
- Tomorrow.io measured `cloudBase = 15,933 m (~52,000 ft)`.
- Forecast of 99% $\to$ **34%** ($\approx \frac{1}{3}$, matching the user's ~33% target and < 50% limit).
- 5-minute observation of 76% $\to$ **26%** (matching NWS's 25% Sunny observation).

## Implementation Changes

1. **Shared Tomorrow.io API Client (`TomorrowIoApi.kt`)**:
   - Added `const val MID_CLOUD_BASE_METERS = 3_000` and `const val HIGH_CLOUD_BASE_METERS = 8_000`.
   - Added `discountCloudCoverByHeight(rawPercent: Int?, baseMeters: Int?): Int?` in the companion object.
   - Updated `getForecast` (hourly forecasts) to discount `cloudCover` using the parsed `cloudBase` distance in meters.
   - Updated `getFiveMinuteHistory` (observations) to discount `cloudCover` using the parsed `cloudBase` distance in meters.
2. **Unit & Integration Tests**:
   - `TomorrowIoCloudDistanceTest.kt` (`:shared`): Added tests covering high decks ($\ge 8{,}000\text{ m}$ applying 66% discount), mid decks ($3{,}000\text{ m} - 7{,}999\text{ m}$ applying 33% discount), low decks ($< 3{,}000\text{ m}$ retaining full weight), zero percent, and null base altitude fallbacks.
   - `TomorrowIoApiTest.kt` (`:app`): Added `getForecast discounts high-altitude and mid-altitude cloud base decks` verifying end-to-end parsing of 9.94 mi cirrus (99% $\to$ 34%), 3.0 mi mid deck (78% $\to$ 52%), and 1.0 mi low deck (90% $\to$ 90%).
3. **Elapsed Hours Persistence & Retroactive Local DB Update**:
   - Under `HourlyForecastWrite.kt`, hours that have already elapsed (`dateTime < nowMs - 1h`) are protected from being overwritten during live forecast fetches.
   - To make today's earlier daytime hours (00:00 to 20:00, including lunch) match the new discount immediately on the desktop graph, a retroactive update was applied to stored `hourly_forecasts` and `hourly_forecast_history` rows in `weather.db` based on recorded `observations.cloudEnvelopeBaseMeters`, followed by signaling `.data-updated` to reload the desktop UI cache.

## Verification

1. **JVM Unit Tests:** Ran `./scripts/unit-tests.sh`; all **6,225 tests** passed across `:shared`, `:desktop`, and `:app` with zero failures.
2. **Desktop Build:** Ran `./gradlew :desktop:createDistributable`; completed successfully.
3. **Database & UI Verification:** Verified that tomorrow's forecast (11:00 AM – 12:00 PM) updated into the 46%–47% range on manual refresh, and today's lunch peak dropped from 99% to 34% on the desktop curve.
