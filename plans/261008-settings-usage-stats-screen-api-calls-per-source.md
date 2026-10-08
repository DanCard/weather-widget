# Settings → "Usage stats": API calls per source on this device

## Request (user, 2026-10-08)

"At the bottom of settings add a button for usage stats and show usage stats for device, including
api calls per API."

## What exists

- Both Settings screens already end with **Data Usage** (`SettingsSection.DATA_USAGE`, last in
  `:shared`): network bytes, cellular / Wi-Fi, for 24 h / 7 d / 30 d / 90 d.
- `api_usage_stats` (Android Room, desktop SQLite, same shape) counts every request by
  (day, source, endpoint) with errors and 429s. Kept 30 days until this change (`RetentionPolicy`). Nothing in the
  UI reads it today; only `scripts/google_usage_today.py` does.
- Rows written before 2026-10-08 have `endpoint = ''` (no per-endpoint breakdown).

## Design

**Button:** "Usage stats…" at the bottom of the Data Usage card on both platforms. Placing it inside
the existing last section keeps it at the bottom of Settings without a new `SettingsSection`.

**Screen** (Android `UsageStatsActivity`, desktop `UsageStatsWindowHost`), titled "Usage stats":

1. **API calls**, one block per source that has any rows, busiest first:
   - Columns: **Today · This month · Last month · 90 days** (user, 2026-10-08: calendar months
     match a provider's monthly bill; a rolling 30 days never covers last month whole).
   - Second line, only when non-zero: `errors 3 · quota refusals (429) 1` (90 days)
   - Per-endpoint rows under it, same columns (`forecast/hours`, `forecast/days`,
     `currentConditions`, …). Pre-endpoint rows are shown as "earlier (not broken down)".
   - Google's "today" is the **Pacific** day (a note under the table says so), matching Cloud Console;
     other sources' "today" is the local day; Google's months are Pacific months too. This is the
     `usageDayMs` rule already in `:shared`.
   - Footer: "Counts every request this device sent, including retries and failures. Kept 90 days."
2. **Network data** (user: include it): the existing data-usage report (bytes per window) is
   repeated at the top, so the screen is the device's complete usage summary. The Settings card
   keeps showing it as today.

**Retention** (user, 2026-10-08): `api_usage_stats` keeps **90 days** (was 30), the same as
`network_usage`: `RetentionPolicy.USAGE_DAYS`. ~16 rows/day/device ⇒ ~1.5k rows. No migration.

**Shared logic** (`:shared`, `ApiUsageSummary`): a pure function from the rows plus `now` and the
local zone to a list of `SourceUsage(sourceId, displayName,
counts, errors, quotaRefused, endpoints: List<EndpointUsage>)`, where `counts` is
`UsageCounts(today, thisMonth, lastMonth, last90Days)`. It works out each source's "today" key through
`ApiUsageClassifier.usageDayMs`, sorts sources and endpoints, and names sources from `WeatherSource`
(`SYNOPTIC` and any unknown id are shown as-is). Both screens only lay the list out.

**Data access:** Android `ApiUsageDao.getSince(cutoffMs)`; desktop
`DesktopWeatherDao.apiUsageSince(cutoffMs)`. Both are read off the main thread.

## Tests

- `:shared` `ApiUsageSummaryTest`: today / this month / last month / 90 d windows (incl. a month
  boundary and Google's Pacific month); a Google row on the Pacific day is
  "today" for a device in Kyiv; endpoint and pre-endpoint rows add up to the source total; sorting;
  errors and 429s.
- Desktop integration: DAO + summary on a temp database (`logApiCall` → `apiUsageSince` →
  `ApiUsageSummary`); a Compose test that the Settings button opens the window and shows a source row.
- Android: Robolectric DAO `getSince` test, and a Settings test that the button launches
  `UsageStatsActivity`, which renders a source row from seeded data.
- On device: open the screen on the Pixel and Samsung and compare against
  `scripts/google_usage_today.py`.

## Not in scope

- Totals beyond 90 days.
- Showing provider quota limits (no hard-coded limits; the console is authoritative).

## Follow-up (user, 2026-10-08): network data moves off Settings

"Bottom of settings screen: Move data usage to the usage stats screen." The Data Usage card in
Settings keeps its heading, one line ("Network data and API calls used by this device/computer.")
and the **Usage stats…** button; the 24 h / 7 d / 30 d / 90 d network numbers are shown only on
the Usage stats screen. Android `SettingsActivity.loadDataUsageStats` and desktop
`SettingsWindow`'s `dataUsageProvider` are gone; `DataUsageSectionContent` lives in
`UsageStatsWindow.kt`.
