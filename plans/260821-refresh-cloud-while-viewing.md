# Refresh cloud data while the widget is being viewed

**Date:** 2026-08-21
**Root cause:** `summaries/260821-...` + live Samsung evidence (below). Cloud refreshes only on the
full forecast fetch; the frequent interactive fetch is current-only and skips cloud; the
interaction-triggered stale gate is effectively dead.

## Problem (evidence from RFCT71FR9NT, 2026-08-21 ~10:17)

Cloud data is stale because it refreshes only on the full forecast fetch, which is slow and
per-source-thresholded, while temperature refreshes every 10 min:

- The 10-min `charging_loop` fetch is `currentOnly=true` — it refreshes temperature + NWS latest
  observations (which happen to carry NWS cloud) but **skips** the hourly cloud forecast and the
  Open-Meteo cloud actuals.
- Open-Meteo (secondary source) has a **90-min** staleness threshold, so 76-min-old data reads
  "fresh" and is not refetched. The 60-min periodic tick also misaligns with the source's own fetch
  time, so Open-Meteo's forecast refreshes every ~1.5–2 h.
- Open-Meteo cloud **actuals** are only filed during the full fetch (`HistoricalActualsBackfill`),
  and only for hours that had ended by then — so the "actual" curve trails "now" by ~3 h
  (last filed 08:54, covering up to 07:00).
- Interactions don't help: `RefreshScheduler.refreshIfStale` uses a **4-hour** threshold
  (`BatteryFetchStrategy.STALE_DATA_THRESHOLD_MS`) and the 10-min current fetch keeps that timestamp
  fresh, so every tap/nav/resize logs `STALE_REFRESH_SKIP skip=fresh_data`.

Observed: `visibleSources=NWS:4m/60m:fresh, OPEN_METEO:76m/90m:fr… isDataStale=false`.

## Goal

**Auto-refresh cloud data while the user is looking at the widget**, so the cloud graph tracks
reality on the same "I'm looking at it" cadence temperature already gets — without turning every
screen-on repaint into a network call.

## Approach

Add a **cloud-freshness watchdog** that rides the existing interactive heartbeat, gated so it only
fires when cloud is genuinely stale:

1. **Freshness signal** — a new `DataFreshness.isCloudStaleForActiveSource(context, thresholdMs)`
   that reads the active source's latest hourly `fetchedAt` (and, for Open-Meteo, the newest filed
   cloud actual) and compares to `now`.
2. **Trigger** — run that check on the existing interactive paths, not on every repaint:
   - the `charging_loop` current-only work (already every 10 min while charging + screen-on), and
   - `ScreenOnReceiver` / interaction handlers (tap, nav, resize, view toggle).
   Each path is already debounced; reuse those debounces so a "looking" burst can't stampede.
3. **Action** — when the cloud view is the active view and cloud is older than the threshold, enqueue
   a **targeted cloud refresh** (existing `enqueueForcedRefresh(targetSourceId = activeSource)` with a
   `cloud_watchdog` reason, KEEP policy — never REPLACE). This reuses the full-fetch path but scoped
   to the source the user is viewing, so it refreshes that source's hourly cloud (Open-Meteo: cloud
   forecast + re-files cloud actuals; NWS: hourly cloud forecast — NWS actuals already ride the
   current fetch).

## Key parameter (needs a decision)

Cloud staleness threshold while viewing. Recommended **15 min** (cloud is hourly-resolution data;
  15 min keeps the actual curve ≤ ~1 h behind "now" and bounds the refresh rate to at most ~4×/h
  while actively viewing). Alternatives: 10 min (more calls, barely fresher) or 30 min (still
  visibly stale at the right edge).

## Out of scope / explicitly not done

- Not making `ui_update_alarm` (2–5 min) do network work — that's the cheap repaint path; the
  watchdog rides the 10-min interactive loop + interaction/screen-on events instead.
- Not lowering the global per-source staleness thresholds (60/90/120 min) — that would fetch even
  when nobody is looking, at battery/quota cost.

## Verification

- Unit: `CloudViewingRefreshPolicyTest` (shared threshold cases) and `CloudViewingRefreshRoboTest`
  (Android watchdog decision + cooldown).
- On-device (Samsung): park the widget in CLOUD view, confirm `CLOUD_SERIES`/`SYNC_START` shows a
  cloud refresh firing while interactive and the Open-Meteo actual curve advances past the previous
  3-h gap, then confirm it idles when the screen is off.

---

## Implementation (260821) — done, dual-platform

**Shared** — `shared/util/CloudViewingRefreshPolicy.kt`: the 15-min `CLOUD_STALE_WHILE_VIEWING_MS`
threshold + pure `isStale(latestDataAtMs, nowMs)` (absent ≠ stale). One number, both platforms.

**Android** — `CloudCoverViewHandler.maybeRefreshCloudWhileViewing(...)`, called from the CLOUD-view
render path (after the `CLOUD_SERIES` log). It reads the active source's latest hourly `fetchedAt`
from the rows already in hand, and when stale **and** the per-widget/source cooldown is clear,
enqueues `RefreshScheduler.enqueueForcedRefresh(reason="cloud_while_viewing", targetSourceId=active)`.
Reuses the backfill probe's `shouldRefreshMissingData`/`markMissingDataRefreshRequested` store.

**Desktop** — `DaemonProcess` observation loop (10 min screen-on): before the observation fetch, if
the screen is on and `getLastSuccessfulFetch(activeSource)` is stale, run the active source's full
forecast refresh (`newRepo.refresh()`) instead of waiting for the 60-min forecast loop.

**Tests** — `CloudViewingRefreshPolicyTest` (4 cases) and `CloudViewingRefreshRoboTest` (5 cases:
stale→enqueue, fresh→noop, null repo→noop, other-source rows→noop, cooldown marked). Verified:
`:shared:testShortShared`, `:desktop:compileKotlin`, `:app:testDebugUnitTest --tests
CloudViewingRefreshRoboTest --tests CloudCoverViewHandlerTest` — all green.

**Still pending** — on-device verification (the `CLOUD_VIEWING_STALE` log + actual curve advancing on
the Samsung), and the Open-Meteo actual-curve gap at "now" is now bounded by the 15-min threshold
rather than the old 60–90-min full-fetch staleness.
