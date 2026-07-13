---
name: desktop-resume-holdoff-warmup-banner
description: "Resume kick waits 15-25s (thundering herd); offline failures within 90s of a wake event show a blue \"waiting for network to warm up\" banner, not the red error"
metadata: 
  node_type: memory
  type: project
  originSessionId: dd5b68e2-2c0b-47ad-9cbe-7e3baa5cc2ea
---

Desktop resume-from-suspend behavior (added 2026-07-13, user request):

- `kickResumeRefresh` no longer fetches immediately: it pauses `RESUME_KICK_DELAY_MS` (15s) +
  jitter (0–10s) before `runLaunchRefresh`. Rationale: DNS is dead for ~10s post-wake and every
  client refetches at once (thundering herd). The RESUME_DETECT log row now reads
  "catch-up refresh in NNNNms" — a resume with no fetch for ~25s is WORKING AS INTENDED.
  If NM fires network:restored during the pause, `kickNetworkRestoredRefresh` cancels the job
  (last-wins) and takes over.
- UI banner: an offline-classified `CURRENT_TEMP_STATUS ok=false` within `NETWORK_WARMUP_GRACE_MS`
  (90s) of the newest RESUME_DETECT/NETWORK_DETECT INFO row (`getLatestWakeEventMs()`, cross-process
  via app_logs) renders as a calm blue "Waiting for network to warm up…" notice instead of the red
  error block. Anchored to the wake event, NOT the failure row — persistent offline writes fresh
  failures every cycle and must escalate to the real error once the grace passes.
- `isOfflineExceptionName(String)` in shared ForecastTypes.kt is the name-only classifier for log
  rows (Ktor CIO DNS failure = `UnresolvedAddressException`, message null).
- Pure-function tests in RefreshDelayTest.kt pin: pause < RESUME_DEBOUNCE_MS, and grace > worst-case
  recovery pipeline (hold-off + offline retries 5s/15s + NM kick pause).

Related: [[desktop-resume-refresh]] [[desktop-network-restore-monitor]]

**Why:** User doesn't want to contribute to post-wake thundering herd, and the transient DNS
failure banner ("NWS current temp not updating") was a false alarm on every resume.

**How to apply:** When reading resume logs, expect the pause. When adding new fetch paths that can
run near a wake, reuse the wake-event anchor rather than failure-row age for "is this transient".
