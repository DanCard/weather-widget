# Lowering hourly days by view probability — assessment (2026-10-10)

Question (user): what do you think of lowering the number of hourly days fetched based on the
probability a source is viewed (`SourceViewProbability`)?

Context: one Open-Meteo fetch asks for `forecast_days = 16` (`ForecastHorizon.MAX_DAYS`) plus
`past_days = 7` — about 23 days × 24 h × 8 hourly variables. Stored reach on the desktop DB:
Open-Meteo 364 h, Silurian 352 h, Google 183 h (on-demand deep fetch; routine 72 h), NWS 155 h. The
hourly views read at most 240 h (`HourlyOnDemand.REACH_HOURS`).

## Short answer

Worth doing, but not first, and not as a standalone probability rule. It saves storage/parsing more
than downloads, and it collides with the reason shortening hourly was withdrawn twice before.

## Why probability-based days are a weaker lever than they look

- **For free sources the download barely changes.** One Open-Meteo call returns whatever was asked;
  3 days instead of 16 is a smaller body, but still one call. The saving is parsing, rows written and
  snapshot copies, not requests.
- **The daily view reads hourly for every day it shows.** The daily icon's noon cloud
  (`DailyNoonCloudCover`) is read from hourly rows at render time. Trim a source to 3 days and days
  4+ draw grey "no data" clouds the moment the user switches to it — exactly why both earlier
  attempts were withdrawn ("google looses cloud shading"). Day/night rain % is also derived from
  hourly, though at fetch time.
- **A switch would not fix it.** Switching only refreshes a source older than 4 h
  (`SourceToggleRefreshPolicy`), so a short-horizon source fetched an hour ago is shown as is — grey
  icons included.
- **The 8-day gate already covers the big case.** A source not viewed in 8 days isn't fetched at all
  (`performance/261010-fetch-only-sources-likely-to-be-viewed.md`), so a horizon rule would only
  matter for sources viewed in the last week but rarely.

## Recommended order

1. **Stop storing hours nothing reads.** No probability needed. Open-Meteo/Silurian store ~364 h
   while the views read 240 h — ~5 days per fetch written and later discarded. Trim to
   `HourlyOnDemand.REACH_HOURS` at save time; one place, every source, viewed or not.
2. **Store noon cloud on the daily row at fetch time**, as day/night rain % already is. The daily
   view then no longer depends on how far hourly reaches. (The idea from
   `notes/261010-hourly-space-saving-brainstorm.md`; it is what makes a short horizon safe.)
3. **Then, if still worth it, shorten by probability.** With step 2 in place, a rarely viewed source
   could keep 72 h of hourly and rely on `HourlyOnDemand` for a tapped day further out — cheap and
   low-risk at that point.

**Measure first.** If the concern is storage, the bigger cost is probably `hourly_forecast_history`
— each fetch copies the far-out hours — not the live table. Count rows by lead time (live vs.
snapshot) on the desktop DB and the Pixel before choosing between step 1 and step 3.

## Insights

- The daily view consumes hourly data as an input. Any horizon rule is really a rule about what the
  daily view can draw; storing the summary on the daily row (step 2) removes that coupling.
- "Fetch fewer days" saves different things per source: for Google every 24 h is a billed page; for
  free sources it is one call regardless. Only Google gains in requests, and it is already at 72 h.

## Next step offered

Measurement only (pull the Pixel DB, query the desktop DB, no code change): where the space goes —
live vs. snapshot, and how much of it lies past 240 h.
