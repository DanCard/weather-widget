# Weather Widget agent guide

Weather Widget is an Android home-screen widget and Linux desktop app. The Gradle modules are
`:app` (Android), `:shared` (pure JVM weather logic and API clients), and `:desktop` (Compose for
Desktop). Keep user-visible behavior consistent across Android and desktop unless the user explicitly
limits a task to one platform.

## Working rules

1. **Verify before diagnosing or changing behavior.** Use logs, database queries, screenshots, API
   responses, or targeted instrumentation. Do not guess what ran or what a widget displays.
2. **Review significant changes before editing.** Present the evidence, root cause, and proposed fix,
   then wait for approval. Routine releases and explicit single-purpose instructions such as "run
   tests" or "commit" are exempt. After approval, continue through verification without unnecessary
   check-ins.
3. **Verify runtime changes on a device/emulator or in the desktop app.** A successful build alone
   does not prove rendering, scheduling, persistence, or interaction behavior.
4. **Do not commit unless explicitly requested.** Never make mid-task commits.
5. **Preserve user state.** Never uninstall the app or clear app data (`adb uninstall`,
   `pm uninstall`, `pm clear`) without explicit user instruction. This destroys widget bindings and
   customized placements.
6. **Do not cancel a running `WeatherWidgetWorker`.** Immediate unique work uses `KEEP` or
   `APPEND_OR_REPLACE`, never `REPLACE`; delayed work may use `REPLACE`, and periodic work uses
   `UPDATE`. Cancellation can cause a native ART crash that bypasses the JVM crash logger. See
   [operations](arch/operations.md) for diagnosis and safe recovery.
7. **Leave `leaveApksInstalledAfterRun` enabled.** Removing it can uninstall test APKs and remove
   home-screen widgets.

## Architecture rules

- Put shared weather behavior in `:shared`, not in parallel Android and desktop implementations.
  A shared runner owns the complete rule—windows, source scope, ordering, gates, and logging—while
  each platform supplies a `Host` for storage, system state, and I/O. A shared planner with substantial
  per-platform glue is not sufficient.
- `:shared` must remain free of Android `Context`, Room, RemoteViews, and widget preferences.
- `:app` owns Android lifecycle, Room, Hilt, scheduling, activities, and RemoteViews glue.
- `:desktop` owns Compose UI and Linux integration, using its desktop SQLite host implementations.
- Mirror feature, preference, default, and settings-layout changes on both platforms. In particular,
  keep `activity_settings.xml` and `SettingsWindow.kt` consistent.
- Preserve location identity: coordinate-keyed writes and reads must follow the shared `LocationMatch`
  quantization and site-matching rules.

See [architecture](arch/ARCHITECTURE.md) for the current structural map.

## Code and persistence conventions

- Use idiomatic Kotlin, data classes for values, constructor injection, structured coroutines, and
  `suspend` functions for asynchronous work.
- Use `goAsync()` for non-trivial `BroadcastReceiver` work and finish the pending result in `finally`.
- Do not silently swallow exceptions. Preserve cancellation and log other failures with context.
- Define `private const val TAG = "ClassName"` where appropriate and keep useful diagnostic logging.
- Use `Log.v` for high-frequency render/tick/poll traces; VERBOSE stays out of the persistent DB log.
  `Log.d` and above may persist to `app_logs`, so keep those events sparse and queryable. Use
  `Log.e(TAG, message, exception)` for errors.
- Log important state transitions and API calls through the existing logging facilities.
- Add a Room migration for every Android schema change and make the corresponding desktop SQLite
  schema/migration change. Keep exported Room schemas current and test migrations with real SQLite.
  The authoritative definitions are `WeatherDatabase.kt` and `DesktopWeatherDatabase.kt`; do not copy
  their version numbers into documentation.
- Add migrations rather than relying on destructive fallback for normal upgrades.

## Build and test

Use Java 21 and plain `./gradlew` commands:

```bash
./gradlew assembleDebug                 # Android debug APK
./gradlew installDebug                  # install on connected targets
./gradlew :desktop:createDistributable  # desktop app used by autostart
./gradlew :desktop:run                  # desktop development run

./scripts/unit-tests.sh                 # JVM tests in all modules
./scripts/emulator-tests.sh             # instrumented tests on connected emulators
./scripts/staggered-tests.sh            # full JVM + emulator gate
./scripts/staggered-tests.sh --install  # install only after the gate passes
```

Prefer pure JVM tests, Robolectric for Android dependencies, and instrumented tests only for real
rendering, view/RemoteViews behavior, or SQLite migrations. Every test must follow the repository's
category rules. Use `scripts/emulator-tests.sh -c <fully.qualified.TestClass>` for focused device
iterations. Do not run `installDebug` concurrently with instrumentation. See
[testing strategy](arch/testing-strategy.md) for categories, runner behavior, and focused commands.

## Runtime and release references

- [Operations](arch/operations.md): device evidence, source toggles, worker recovery, desktop
  autostart/single-instance behavior, and releases.
- [Forecast and observation cadence](arch/hourly-forecast-update-cadence.md): forecast cadence,
  worker ticks, observations, and repaint behavior.
- [Retention and pruning](arch/data-pruning.md): active data, settled history, and snapshot cleanup.
- [Daily-history extremes](arch/daily-history-extremes.md): actuals provenance and accuracy baselines.
- [APT repository](docs/APT_REPO.md): desktop installation and publishing.

When changing documented behavior, update the relevant living document in the same change.

## Documentation and commits

- Do not create plan or summary files for simple fixes or routine releases. Put plans for complex work
  directly in `plans/`, with a `YYMMDD-kebab-title.md` name; use `performance/` for performance
  investigations. Do not overwrite an existing plan.
- Dated plans, summaries, notes, and session logs are historical records, not current documentation.
  Link to them for evidence without treating their old behavior as authoritative.
- Session logs include user prompts and prefer numbered lists for long sequences.
- Commit messages use an imperative summary under 72 characters and a succinct bulleted or numbered
  body. Include related docs/tests and reference associated plan paths. Do not imitate older prose-heavy
  commit history.
