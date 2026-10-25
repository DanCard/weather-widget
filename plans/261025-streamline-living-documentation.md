# Streamline and update living Markdown documentation

## Scope and findings

The repository has more than 1,500 tracked Markdown files, but the user explicitly asked to ignore
the dated plans, summaries, notes, performance investigations, and session logs. Preserve those
historical records and the two dated bitmap analyses unchanged. Leave the generated
`fastlane/README.md` alone.

The living docs duplicated one another and disagreed with the code. Confirmed examples included
obsolete fetch cadences, Kotlin/SDK versions, schema/entity details, desktop single-instance
behavior, broken script references, stale testing rules, and an inaccurate blanket 30-day privacy
retention claim.

## Implementation

1. **Establish one canonical agent guide in `AGENTS.md`.**
   - Reduce the 601-line working copy to a concise operational guide.
   - Retain evidence-first review, Android/desktop parity, shared runner + Host ownership, device and
     worker safety, runtime verification, logging, migration, test, and commit rules.
   - Remove volatile counts, schema numbers, inventories, device procedures, long code samples, and
     duplicated architecture narratives.

2. **Eliminate competing agent manuals.**
   - Replace `CLAUDE.md`, `GEMINI.md`, and `QWEN.md` with short pointers to `AGENTS.md`.
   - Move current operational details into the relevant living architecture/operations documents and
     leave historical decision narratives in the dated archive.

3. **Refresh the living architecture docs against source-of-truth code.**
   - Rewrite `arch/ARCHITECTURE.md` around module boundaries, shared runners and Host contracts,
     persistence, sync/render pipelines, the desktop daemon/UI split, and observability.
   - Replace `arch/testing-strategy.md` with current JVM/Robolectric/MockK/instrumented strategy, Host
     contract suites, category rules, commands, and staggered-runner behavior.
   - Update `arch/hourly-forecast-update-cadence.md` for the current LONG-only fetch policy, eight-day
     live hourly, observation cadence, worker ticks, and runtime screen-on limitations.
   - Update `arch/data-pruning.md` for fetch-time labels, current snapshot policy, the 30-day active
     window, and 18-month settled hourly history.
   - Streamline `arch/daily-history-extremes.md` to current shared runners, field meanings, provider
     provenance, and accuracy baselines.

4. **Add `arch/operations.md`.**
   - Consolidate device evidence, source toggles, worker diagnosis/recovery, desktop
     autostart/single-instance behavior, and releases.
   - Verify commands and paths, include `GOOGLE_WEATHER`, and document `.quit-<launchId>`.

5. **Correct user-facing and install/privacy docs.**
   - Tighten `README.md`: configurable providers, high-level battery behavior, keyless debug setup,
     current desktop behavior, and working script names.
   - Align `docs/APT_REPO.md` optional providers with public configurable behavior.
   - Update `PRIVACY_POLICY.md` from verified network/storage behavior, including Google, observation
     feeds, geocoding/IP lookup, radar services, and distinct local retention periods.

6. **Validate.**
   - Check repository-relative Markdown links in every edited living doc, inspect the final diff, and
     run `git diff --check`.
   - Do not run Gradle/emulator suites for documentation-only edits. Do not commit unless requested.

## Final shape

- `AGENTS.md`: canonical concise rules and commands.
- `CLAUDE.md`, `GEMINI.md`, `QWEN.md`: compatibility pointers.
- `arch/`: current architecture, testing, cadence, retention, actuals, and operations details.
- `README.md`, `docs/APT_REPO.md`: accurate user/install documentation.
- `PRIVACY_POLICY.md`: data handling aligned with current code.
- Dated history: untouched except for this new plan record.
