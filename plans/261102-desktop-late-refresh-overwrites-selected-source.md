# Desktop: late refresh overwrites the selected source

Status: implemented; live mid-refresh reproduction outstanding. Desktop only (`:desktop`); no `:shared`, Android, or schema change.

## Symptom

The popup header is live, but the graph area shows "Loading Google Weather…" and no forecast. The state
persists, although every source's fetch succeeded and `weather.db` holds current rows for all seven.

## Evidence (2026-11-02, `app_logs` and `autostart-20261102-072506.log`)

| Time | Event |
|---|---|
| 09:00:58 | Settings → Refresh data starts with `src=OPEN_METEO` |
| 09:01:07 | `SETTINGS_REFRESH start displayed=OPEN_METEO` |
| 09:01:16 | Switch OPEN_METEO → NWS |
| 09:01:36 | Switch NWS → OPEN_METEO |
| 09:01:43.1 | Switch OPEN_METEO → GOOGLE_WEATHER |
| 09:01:43.7 | `SOURCE_SWITCH_E2E phase=data_ready`; header shows `source=GOOGLE_WEATHER` |
| 09:01:46 | `SETTINGS_REFRESH done`; `HISTORY_REFRESH_DONE ok=true source=OPEN_METEO ms=48907` |
| 09:01:46.96 | `HEADER_CONTENT source=OPEN_METEO ... label=Google`: the old source is back in the snapshot |
| 09:01:49.99 | `SOURCE_SWITCH_WAIT state=LOADING source=GOOGLE_WEATHER snapshot=OPEN_METEO` |
| 09:03:13 | Still stuck: no newer `SOURCE_SWITCH_WAIT`, reload, or data-ready line |

A window screenshot at 09:02:33 confirmed the blank graph. This is the only `SOURCE_SWITCH_WAIT` row in
`app_logs`.

## Root cause

`requestFullRefresh` captures `repo` when it starts. When the Settings refresh finishes,
`DesktopUiApplication.kt:899` calls `repo.loadCached()` on that captured repository, and `:901` assigns
the result to `forecast` without checking that it is still the selected repository.

Nothing repairs it, because the reload for the new source already ran. The state ends only at the next
daemon data notification, popup show, source change, or the `UI_FALLBACK_RELOAD_MS` (10 min) safety net.
Self-recovery was not observed.

`UserRefreshRunner` intentionally lets a fetch outlive its screen. Storing the rows late is correct;
publishing them to the display without checking is the defect. Before `6bb680c3` (2026-11-01, "Blank
desktop graphs when a source switch has no data") the same race would have left the old source's graph
under the new label. The blanking made it visible.

## Fix

Add one gate in `DesktopUiApplication.kt`, built on a small pure rule that tests can reach:

```kotlin
internal fun isCurrentRepository(owner: Any?, current: Any?): Boolean = owner != null && owner === current
```

`publishFrom(repo, snapshot)` assigns `forecast` only if `repo` is still `currentRepository.value`.
Otherwise it logs `STALE_PUBLISH_DROPPED owner=<source> current=<source> origin=<origin>` and reloads
through the current repository where needed.

The comparison is by repository identity, not `sourceId`. `repository` is rebuilt on any change to
location, source, API keys, or personal-station settings, so identity also covers a location change
during a refresh, which a source comparison would miss.

### Call sites

| Line | Path | Stale-owner handling |
|---|---|---|
| `:901` | Settings all-sources finish (the observed bug) | drop and reload |
| `:857` | Full refresh, displayed source | drop and reload |
| `:770` | Viewing-forecast refresh | drop and reload |
| `:987` | Observations refresh | drop and reload |
| `:428` | On-demand history | drop and reload |
| `:464` | On-demand day hourly | drop and reload |
| `:607` | Location-change fetch | drop; the newer change owns the display |
| `:288` | `reloadCachedForecast`: `repo` is read before a 0.4 s `loadCached()`, so a switch can land during it | drop only; the new repository's own load is already running, and reloading here could loop |

The `LaunchedEffect(repository)` loads at `:518` and `:538` need no gate: changing the key cancels them.
`adoptEmptySource` (`:275`) needs the same check because it writes `repo.emptySnapshot()`. Audit `:586`
during implementation.

Keep the existing follow-ups after a dropped publish: `dataUpdateCount++`, `notifyRefreshRequested()`,
and `announceRefreshDone`. The rows were stored and the refresh did succeed, so the user still sees the
"refreshed" notice. `refreshedSourceId` keeps naming the source that was refreshed.

## Tests

Pure JVM, in `desktop/src/test/kotlin/com/weatherwidget/desktop/`:

1. Gate unit test: same instance publishes; a different instance, a null owner, and a null current are
   dropped; two repositories with the same `sourceId` are still dropped (location change).
2. Guard test, in the style of `UserRefreshScopeGuardTest`: scans `DesktopUiApplication.kt` and fails on
   any direct `forecast =` assignment outside an allowlist (the gate, `:518`, `:538`, and the audited
   sites). It must fail on the current code.
3. Race regression, extending `DesktopSourceSwitchLockIntegrationTest` or a new test beside it: real
   `DesktopWeatherRepository` instances for two sources; a refresh on A finishes after the switch to B; B
   stays published. It must fail if the gate is bypassed.

Keep diagnostic logging in tests.

## Verification

1. `./scripts/unit-tests.sh`.
2. `./gradlew :desktop:createDistributable`, then restart the desktop app.
3. Live reproduction: Settings → Refresh data, then switch source several times before it finishes.
   Confirm from a screenshot that the graph stays on the selected source after `SETTINGS_REFRESH done`.
4. Query `app_logs`: `STALE_PUBLISH_DROPPED` appears, with no new `SOURCE_SWITCH_WAIT` for that run.
5. Control: refresh with no switch during it; the graph updates and no drop is logged.

## Documentation

Update `arch/operations.md` near the `SOURCE_SWITCH_WAIT` section with the `STALE_PUBLISH_DROPPED` tag
and the rule that a late result publishes only through the gate.

## Out of scope

- Disabling source switching during a refresh. The switch must stay instant.
- Cancelling the in-flight refresh. Its rows are valid and the runner guarantees they are kept.
- Changing `SLOW_LOAD_MS` or the `DesktopSourceWait` rules. They reported the wrong state correctly.
