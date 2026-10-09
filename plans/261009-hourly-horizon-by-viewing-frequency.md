# Hourly horizon by how often each source is viewed

Status: **proposed — awaiting approval** (2026-10-09). Builds on
`plans/261009-google-hourly-on-demand-past-72h.md` (on-demand day fetch, 12 h freshness, daily
prune), which must land first.

## User's proposal (2026-10-09)

> Keep track if user commonly views other APIs. If yes, then fetch 7 days once every 24 hours while
> charging. Otherwise don't. Put at bottom of setup how often the user fetched other APIs and if there
> is more than a 20% chance then fetch 7 days of data once every 24 hours while charging. Metered APIs
> like google, only fetch 24 hours in advance on normal cadence. Non metered APIs fetch only 3 days on
> normal cadence. All APIs that are commonly viewed, daily forecast view, fetch 7 days of hourly data
> at most once every 24 hours.

Why the earlier attempts were withdrawn: hourly rows feed the daily view's day/night rain %
(`DailyPrecipPeriods`, for sources without their own day/night values) and the daily icon's noon cloud
shading (`DailyNoonCloudCover`, read at render time). This proposal keeps a full 7 days for every
source the user actually looks at, refreshed at most daily, so both stay correct where they are seen.

## Rules (with my defaults for the open points — confirm or change)

| Source | Routine fetch keeps | 7-day hourly fetch |
|---|---|---|
| Metered (Google) | 24 h (1 billed page) | if commonly viewed: at most once per 24 h, while charging (7 pages) |
| Non-metered (all others) | 72 h | if commonly viewed: at most once per 24 h, while charging (the API's normal single call, kept to 7 days) |

1. **"Commonly viewed"** = in the last 30 days, ≥ 20 % of *views* had that source displayed. A view
   is a screen-on paint of a widget (Android) or the popup opening (desktop), counted per source per
   day. *(default — confirm)*
2. **Desktop "while charging"**: on AC (or always, on a desktop without a battery). *(default)*
3. **Switching to a rarely viewed source** in the daily view: if its 7-day hourly is missing or > 24 h
   old, the toggle fetches 7 days once (extends `SourceToggleRefreshPolicy`), so days 4+ keep their
   day/night split and cloud shading. *(default)*
4. **Unplugged for days**: no 7-day fetch off-charger. Days 4–7 hourly may age past 24 h, while the
   daily rows keep refreshing on cadence. *(accept, or allow above some battery level)*
5. **Tap / pan past stored hours**: the existing on-demand day fetch (any source now, not just Google).
6. **Settings → Data Usage** (Android and desktop): one line per source: "Viewed 64 % · hourly 7 days
   daily" or "Viewed 3 % · hourly 3 days".
7. **Prune**: the daily prune deletes future hourly past each source's routine window once it is
   > 24 h old (7-day rows) / > 12 h old (on-demand rows), so space actually drops.

## Design sketch

- `:shared` `HourlyHorizonPolicy`:
  - `routineHours(source)`: Google 24, others 72;
  - `isCommonlyViewed(viewShare)`: share ≥ 0.20;
  - `sevenDayDue(lastSevenDayFetchMs, isCharging, now)`;
  - `trim(payload, hours, now)`.
- View counting: table `source_views(date, source, count)` on both platforms (Room migration + desktop
  schema). Written from the screen-on paint / popup open; pruned to 30 days (`RetentionPolicy`).
- Saves trim to the routine window, except a 7-day or on-demand fetch.
- Day/night precip is resolved from the full payload before trimming, on both platforms (Android
  `withStoredPrecipPeriods`, desktop the same) — the payload, not the trimmed store.
- The 7-day fetch rides the charging loop (Android) / the desktop refresh loop: one per commonly
  viewed source per 24 h, logged `HOURLY_7DAY_FETCH source=… reason=…`.

## Costs / numbers

- Space: on the desktop DB, about 45 % of hourly rows lie past 72 h, roughly 2–3 MB of 24 MB.
  `observations` is the largest table. Android not measured yet.
- Google: routine 1 page per fetch (was 1–3) + 7 pages once a day if commonly viewed — about today's
  cost.

## Tests (planned)

- `:shared` policy unit tests: routine hours, view-share threshold, 7-day due rules, trim.
- Integration (Android repo + Room; desktop repo + SQLite):
  - a routine fetch stores 72 h / 24 h;
  - a 7-day fetch stores 7 days;
  - days 4+ keep day/night precip after a trimmed fetch;
  - the prune drops aged rows.
- UI: the Settings usage line on both platforms.
- On device: daily view days 4–7 (rain %, icons) before and after, on a commonly viewed source and a
  rarely viewed one.
