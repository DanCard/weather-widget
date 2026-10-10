# Hourly data space saving — brainstorm (2026-10-10)

## User's ideas

1. Don't fetch lots of hourly data for other sources if they have a low probability of being viewed.
   Track how often the user toggles the API button to view other sources, and come up with a scheme
   for determining probability.
2. We currently fetch 7 days of hourly data, which is rarely viewed. People mostly look at the
   current day, sometimes tomorrow, and on rainy days sometimes check when the rain will start.
   7-day hourly is nice for knowing how cloudy it will be at noon each day in the daily forecast
   view, but the hourly details themselves aren't viewed.
3. Fetching days out to 7 at most once every 24 hours seems better than the current cadence.
   Fetching out to 3 days more often is OK if it is going to be viewed.

## Prior context

`plans/261009-hourly-horizon-by-viewing-frequency.md` already holds nearly this exact proposal
(20% view share, 7-day fetch at most once per 24 h, 72 h on the normal cadence). It is marked
"awaiting answers; may be shelved". Shortening the hourly horizon was approved and then withdrawn
twice on 2026-10-09, for two reasons the user gave:

- "hourly data is used for day / night chance" (`DailyPrecipPeriods`)
- "google looses cloud shading" (`DailyNoonCloudCover` reads hourly rows when the daily view is
  drawn)

## Thoughts

1. **Idea #2 is right, but it points to a different fix.** The daily view doesn't need days 4–7 of
   hourly data. It needs two numbers per day: the noon cloud cover and the day/night rain %. The
   rain % is already worked out from the full download before saving (`withStoredPrecipPeriods`).
   The noon cloud is the only thing still read from hourly rows when the screen is drawn. If we also
   save **noon cloud on the daily `forecasts` row when we fetch**, hourly rows past 72 h stop
   mattering to the daily view. Both reasons for the earlier withdrawals go away. And then:
   - Every source keeps 72 h of hourly. Rows beyond that are thrown away before saving, or not
     fetched for Google.
   - Tapping a day past 72 h uses the existing on-demand fetch (`HourlyOnDemand`), which already
     covers the "when will the rain start" case.
   - **No view tracking is needed.** The view-probability scheme (#1) was only there to decide who
     gets 7 days of hourly. Once the daily view doesn't need it, nobody does.
2. **View tracking (#1) is the most expensive part and buys the least.** It needs a new table on
   both platforms, migrations, counting rules ("what is a view?"), a threshold, a Settings line, and
   tests. Its only output is a yes/no per source. If still wanted later, it fits better as a
   **fetch-cadence** control (rarely viewed sources refresh less often) than as a control on how
   many days we fetch.
3. **On cadence (#3):** for free sources, one call returns 7–16 days anyway. "Fetch days 4–7 every
   24 h" really means "save days 4–7 at most once a day." The storage cost isn't one copy of the
   rows. It's the **snapshots**: every fetch copies the far-out hours into
   `hourly_forecast_history`. So the bigger space win may be not snapshotting hours past 72 h, or
   snapshotting them at most daily. Check this before deciding.
4. **Measure first.** The old plan estimates ~2–3 MB of a 24 MB desktop database, and says
   `observations` is the largest table. Android hasn't been measured. If "space" means database
   size, hourly may not be where the bytes are. If it means Google quota, that changes the
   priorities.

## Insights

- The two earlier attempts failed because the daily view reads a summary (noon cloud, rain %) from
  raw hourly rows each time it draws. Saving the summary when we fetch separates "how far ahead the
  daily view looks" from "how much hourly we keep."
- The project already does this for past days: `DailyHistorySnapshotter` copies cloud and rain
  values onto `daily_history`. Doing the same for the forecast row reuses that pattern rather than
  adding a new one.
- Retention vs. horizon: `hourly_forecast_history` grows with the number of fetches × hours per
  fetch. Cutting the hours *archived per snapshot* may save more than cutting the hours *kept live*.

## Open questions (before writing the plan)

1. **What does "space" mean?** Database size on the phone or desktop, Google billed requests, or
   network data?
2. **OK to save noon cloud on the daily row** so the daily view no longer needs 7 days of hourly?
   If yes, view tracking can go.
3. **Measure first?** Table sizes on the phone and desktop, broken down by live vs. snapshot hourly
   rows and by how far ahead they are.

Next step: the actionable plan goes in a new file in `performance/` (storage cost), replacing
`plans/261009-hourly-horizon-by-viewing-frequency.md`, which gets marked superseded rather than
overwritten.
