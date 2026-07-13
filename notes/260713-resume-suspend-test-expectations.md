# Desktop resume-from-suspend test: conditions & expected timeline

Context: verifying the 2026-07-13 resume hold-off + network warm-up changes
(summaries/260713-resume-holdoff-warmup-banner.md) with a real suspend. All signals below are
persisted in `weather.db` → `app_logs`, so the timeline can be reconstructed after the fact.

## Test conditions

**Suspend for at least 10 minutes.** The launch-refresh staleness gate only fetches when
observations are >10 min old (forecast >60 min). The observation loop runs every ~10 minutes
while awake, so a 2-minute suspend right after a fetch resumes into `action=NONE` — the
hold-off pipeline still logs, but there is no network fetch and therefore no DNS-race to
observe. Ten-plus minutes guarantees the interesting path (a ~65+ minute suspend additionally
exercises `FULL_FORECAST`).

## Expected timeline on a successful pass

1. `RESUME_DETECT … catch-up refresh in ~15000–25000ms` + `WAKE_EVENT reason=resume:logind`
   at the moment of wake.
2. ~5–15s later: `NETWORK_DETECT connectivity restored` + `superseding pending catch-up job
   (last-wins)` — the breadcrumb showing the NM kick cancelled the still-sleeping resume kick —
   + `WAKE_EVENT reason=network:restored`.
3. `LAUNCH_REFRESH_CHECK reason=network:restored` → `CURRENT_TEMP_STATUS ok=true`.
4. **No** `SOURCE_FALLBACK` / `UnresolvedAddressException`, and **no** `CURR_TEMP_BANNER` rows —
   the banner log only writes on state *transitions*, so silence there is the pass condition.

Variant: if the network is up before logind fires (wired, or a very fast Wi-Fi reconnect),
NetworkManager may never emit a restored signal — then the resume kick itself runs after its
full hold-off (`LAUNCH_REFRESH_CHECK reason=resume:logind`). Also correct; just the other branch.

## Caveat

The old failure needed the fetch to land in the DNS-dead window, so a single clean pass is
strong evidence but not proof the race is gone — a few suspend cycles over the following days
settle it. The logging is permanent, so every future resume is a recorded test.

## Timeline query

```sql
SELECT datetime(timestamp/1000,'unixepoch','localtime'), level, tag, message
FROM app_logs
WHERE tag IN ('RESUME_DETECT','NETWORK_DETECT','WAKE_EVENT','LAUNCH_REFRESH_CHECK',
              'CURRENT_TEMP_STATUS','SOURCE_FALLBACK','CURR_TEMP_BANNER','REFRESH_RETRY')
  AND timestamp > (strftime('%s','now') - 3600) * 1000
ORDER BY timestamp;
```
