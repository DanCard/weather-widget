# Fix: hourly graph shows a stale 6-day-old forecast for past hours (desktop 78° vs Samsung 74°)

## Context

**Symptom:** The hourly temperature graph forecasts today's high as **78°** on desktop but **74°** on
the Samsung widget, for the same location/source. Desktop is wrong; 74° is correct.

**Root cause (verified against the live desktop `weather.db` + a panel screenshot):**
- Desktop is on **NWS** (confirmed via source indicator and every `REFRESH`/`CURR_TEMP` log). The live
  NWS forecast for today peaks at **74°** — identical to Samsung.
- For the **elapsed (past) hours of today**, the graph does not draw the live forecast. It draws the
  "as-predicted" line from `hourly_forecast_history`, and `HourlyForecastStitcher` selects
  `minByOrNull { fetchedAt }` — the **earliest snapshot ever stored** for each hour. Desktop has
  snapshots back to **06-17 (6.2 days old)** that predicted today's afternoon at **78–79°** → the 78° peak.
- Samsung shows 74° only because its history table doesn't reach that far back, so it falls through to
  the live forecast. **Same shared code, different data depth** — that is the entire cross-device divergence.
- Even the genuine **1-day-ahead** snapshot (≥24h lead) was 78° here (NWS truly busted this forecast;
  actual ≈69°), so "1-day-ahead selection" would NOT yield 74. Only the **latest** forecast does.

**How it got there:** commit `72e5a033` ("Fix hourly graph showing hindsight revisions for past hours")
tried to avoid NWS's REPLACE-overwritten "hindsight revision" by showing the "original prediction," but
implemented that as the *earliest* snapshot — which is the 6–7-day-out, least-accurate long-range
forecast, not a meaningful day-ahead prediction.

**Intended outcome:** The hourly graph's forecast line/high-label uses the **latest** forecast for every
hour (past included). Desktop and Samsung both read 74° today, and the line is robust to history depth.
The dedicated **Forecast History** view (separate snapshot-query path, not the stitcher) still provides
the as-predicted accuracy comparison.

## Change

### 1. `shared/src/main/kotlin/com/weatherwidget/data/model/HourlyForecastStitcher.kt` (core)
Make the **latest forecast win for all hours**; history only backfills hours/fields the live data lacks.
- `originalByTime`: change `minByOrNull { it.fetchedAt }` → `maxByOrNull { it.fetchedAt }` (freshest
  snapshot, so when live rows have aged out for fully-past days the *latest* revision is used).
- Remove the past-hours special case (`time < nowMs && original != null -> original.copy(...)`). The
  `live != null -> live.copy(backfill nullable from original)` branch then applies uniformly to past and
  future; `else -> original` still supplies hours that live lacks. (Past hours of today retain live rows
  fetched today, so live wins → 74°.)
- Rewrite the class KDoc: drop the "original prediction / earliest snapshot for past hours" rule; state
  that the latest forecast wins everywhere and the freshest history snapshot backfills missing
  hours/nullable fields. The same-site collapse behavior is unchanged.

This one file is shared by both callers — Android `GraphDataLoader.kt:121` and desktop
`DesktopWeatherDao.getHourlyWithHistory` (`DesktopWeatherDao.kt:590`) — so the fix lands on both platforms.

### 2. Tests (invert the now-obsolete "earliest wins" assertions)
- `shared/src/test/kotlin/com/weatherwidget/data/model/HourlyForecastStitcherTest.kt`:
  - `past hour - original prediction from earliest history wins` (≈L57): assert **live wins** (76f /
    "Sunny"), history no longer overrides temp/condition; rename accordingly.
  - `past hour - earliest snapshot supplies temp while a later bucket backfills cloud cover` (≈L73):
    live supplies temp/condition; history still **backfills `cloudCover`** the live row is missing.
    Reframe to verify nullable-field backfill, not earliest-temp.
- `desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopWeatherRepositoryTest.kt`
  (`loadCached fills missing cloud cover from hourly history`, ≈L100–149): past hour now expects live
  **temp=70f / condition="Clear"** with **cloudCover=82 backfilled** from history (live had null).
  Update the comment at L140–141.

### 3. Comments referencing the old behavior
Update the inline notes that describe "original prediction / hindsight revision for past hours" in
`app/.../widget/handlers/GraphDataLoader.kt` and `shared/.../desktop/DesktopWeatherDao.kt` so they match
the new "latest wins" rule.

## Verification

1. **Unit tests:**
   - `./gradlew :shared:testDebugUnitTest --tests "*HourlyForecastStitcherTest*"`
   - `./gradlew :desktop:test --tests "*DesktopWeatherRepositoryTest*"`
2. **Desktop (real app):** rebuild + restart via `scripts/buildStart.sh`, then `touch
   ~/.local/share/weather-widget/.show`, capture the panel
   (`DISPLAY=:0 import -window root /tmp/.../panel.png && convert ... panel.jpg`) and confirm the hourly
   graph's past-hours peak now reads **74°**, not 78°.
3. **Android (emulator/Samsung):** `./gradlew installDebug`; confirm the widget hourly graph still reads
   74° (unchanged) — proves cross-device convergence.
4. Cross-check the DB: the freshest NWS snapshot per hour today already equals the live values (max 74°),
   so post-fix the stitched series max for today = 74°.

## Trade-off to note
Navigating to a fully-past **day** on the main hourly graph will now show that day's *latest* (near-final)
forecast rather than the day-ahead prediction. This is consistent with "latest forecast" and keeps the
headline number device-stable; the day-ahead/as-predicted accuracy comparison remains in the dedicated
Forecast History view.

## Memory follow-up (post-implementation)
- Update/replace `hourly_forecast_line_is_hindcast.md` — its "earliest snapshot for past hours" rule is
  now reversed.
- Add a memory: stitcher uses **latest** forecast for all hours; `earliest` was a 6–7-day-out long-range
  pick (origin commit `72e5a033`); cross-device divergence was history-depth, not source.
