# Google daily quota: stop calling until reset, say so, and quiet the failure banner

## Evidence (Fold, 2026-10-06 14:40)

- Widget shows "⚠ GOOGLE WEATHER UPDATES FAILING · 429 Rate Limited · 2:37 PM".
- `FETCH_GOOGLE_FAIL code=HTTP_429` at 13:34, 13:54, 14:36, 14:37. Body:
  `quota_limit: ForecastHoursQueriesPerDay`, `quota_limit_value: 60`, `quota_unit: 1/d/{project}`,
  `quota_metric: weather.googleapis.com/forecast/hours`, `window_start_time` = 00:00 PDT.
- `source_fail_count_GOOGLE_WEATHER=4` (watermark threshold 3), so the banner stays up.

## Root cause

- `forecast/hours` has a per-project daily quota (60) shared by every device, emulator and desktop.
  `getForecast` awaits it, so after a 429 every Google fetch fails until midnight Pacific. Each
  retry is a wasted round trip that can never succeed (we already skip `history/hours` this way).
- The watermark has one meaning: "N consecutive failures". It shows the HTTP code and the last
  attempt time, so a quota with a known reset time reads like an outage that might clear any moment.
- The banner is the same full-size red pill however long it has been up.

## Plan

### 1. Skip `forecast/hours` after a daily-quota 429 (`:shared` `GoogleWeatherApi`)
- New `GoogleQuota.isDailyQuotaExhausted(detail)`: a 429 whose body's ErrorInfo says
  `quota_unit` `1/d/...`. A per-minute 429 (`1/min/...`) stays a normal 429 and is retried.
- Hours 429 + daily → `forecastBlockedUntilMs = nextQuotaResetMs(now)` (in-process, like
  `historyBlockedUntilMs`; a 429 costs no quota, so a new process making one more call is fine).
- While blocked, `getForecast` throws `GoogleDailyQuotaException` (an `ApiAccessException`, status
  429, carrying `resetAtMs`) **before any request**: no current, days or hours calls, since the fetch
  cannot succeed without hours. `getCurrent` is untouched (separate quota, still works).
- Android and desktop share this class, so both get the change.

### 2. Banner says "paused until reset" instead of "failing"
- `ForecastFetchCoordinator.extractErrorCode` / `CurrentTempRepository.extractCurrentErrorCode`:
  any daily-quota 429 → new code `QUOTA_DAILY` (not `HTTP_429`).
- Watermark for `QUOTA_DAILY`: main line "⚠ GOOGLE WEATHER UPDATES PAUSED", detail
  "Daily quota used · resets 12 AM". Reset time = next midnight Pacific after the failure, shown in
  local time (so it reads "3 AM" in New York). New strings in all 19 `values-*` locales.
- Desktop `desktopFetchErrorPresentation`: same detection on the 429 body → title
  "GOOGLE WEATHER DAILY QUOTA USED", body "Resets at 12 AM", retry line
  "Updates resume automatically after the reset."

### 3. Banner shrinks after 8 s and fades after 16 s
Stage depends on age = now − `bannerSince`:
| Age | Look |
|---|---|
| < 8 s | today's full pill |
| 8–24 s | **tiny**: one line, ~9 dp, e.g. "⚠ Google · resets 12 AM" / "⚠ Google · 429" |
| ≥ 24 s | tiny pill at 50 % opacity (user, 2026-10-06; was 35 % at 16 s) |

- **Android anchor**: new pref `source_fail_banner_since_<id>`. Set when the failure count first
  reaches the threshold, and reset when the error code changes (new information gets the full pill
  again). Cleared on success. A repeat of the same failure does **not** re-expand it, otherwise
  every hourly retry would pop it full-size again.
- **Android repaints**: a widget is a static bitmap, so the paint that draws the full pill enqueues
  `WidgetWorkScheduler.enqueueDelayedUiRepaint` at +8 s and +16 s (the existing `FetchBanner`
  mechanism). Best-effort: if a repaint is deferred, the next paint computes the right stage from the
  timestamp anyway.
- Pure stage logic in `:shared` (`FailureBannerStage.at(ageMs)`) so both platforms use the same
  thresholds.
- **Desktop**: the anchor is when the popup shows the banner (real-time Compose, `LaunchedEffect`).
  Tiny = title line only; faded = alpha 0.35. Clicking the tiny chip expands it again; the × still
  dismisses it.

## Tests
- `GoogleWeatherApiTest` (mock engine): a daily-quota 429 on hours → the next `getForecast` makes zero
  requests and throws `GoogleDailyQuotaException`; it calls again after the reset; a per-minute 429
  does not block; `getCurrent` is unaffected.
- `GoogleQuota` parse: daily, per-minute, non-JSON body.
- `extractErrorCode` → `QUOTA_DAILY`; `GraphFailureWatermarkRenderer.calculateLayout` for the
  `QUOTA_DAILY` text and for the tiny stage (width, one line).
- `FailureBannerStage` boundaries (7.999 s / 8 s / 16 s); anchor reset on code change, not on repeat.
- `desktopFetchErrorPresentation` daily-quota branch.
- On device: Fold, Google selected. Confirm the paused text, then tiny at about 8 s and faded at
  about 16 s (screenshots), and that `FETCH_GOOGLE_FAIL` stops appearing until midnight.

## Added during implementation
- **Pill moved below the header** (user: the pill covered the home icon). Header touch zones reach
  ~37 dp into the graph and `error_pill_touch_zone` sat on top of them from 4 dp. Pill and touch zone
  now start at 40 dp (`GraphFailureWatermarkRenderer.PILL_TOP_DP`); on API 31+ the zone's height
  follows the stage (50 dp full, 22 dp tiny).
- **Daily view swallowed the stage repaints**: `WidgetViewModeDispatcher` skips UI-only daily paints
  once today is painted (`state=skipped_ui_only`). It now paints through the stage window
  (`FailureBannerRepaint.stageChangeMayBePending`, 24 s + 60 s slack).
- Not fixed here: tapping the pill crashes the app (`BackgroundDataResolutionActivity` is an
  `AppCompatActivity` under `@android:style/Theme.Translucent.NoTitleBar`), and repeated crashes get
  the process blocked as bad, so the widget stops painting. Follow-up: replace it with an error-details
  page.
