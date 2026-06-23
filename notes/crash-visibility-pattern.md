# Crash-Visibility Pattern (project-agnostic hand-off note)

A reusable approach for answering "**will I know if users hit bugs in production?**"
before a Play Store launch. Distilled from the cal-date-widget implementation
(Jun 2026) so it can be adapted to other apps. **Adapt the conclusions; don't copy
them blindly — step 0 decides everything.**

Reference implementation (cal-date-widget):
- `app/src/main/java/ai/dcar/caldatewidget/CrashStore.kt` — testable read/write seam
- `app/src/main/java/ai/dcar/caldatewidget/CalApp.kt` — global uncaught-exception handler
- `app/src/test/.../CrashStoreTest.kt` — unit tests (plain JUnit)
- `app/src/test/.../CalAppCrashHandlerTest.kt` — integration test (handler→store wiring)
- `app/proguard-rules.pro` — `-dontobfuscate` rationale

---

## Step 0 — Decide the strategy from the app's network/privacy posture (DO THIS FIRST)

The right crash-tracking answer depends on a single question: **does the app already
transmit data off-device (i.e. declare `INTERNET`)?**

- **Offline app (no `INTERNET`, on-device only, "no tracking" privacy promise):**
  - Use **Google Play Android vitals** as the primary crash/ANR radar — it's free,
    automatic, requires no SDK/permission, and does NOT count as collection by you
    (Google gathers it from opted-in users). No Data Safety change, no policy change.
  - Do **not** add Firebase/Sentry/Bugsnag — it forces an `INTERNET` permission,
    flips the Data Safety form to "Yes, collects crash data + device IDs," and
    contradicts the privacy policy. Cost ≫ benefit at small scale.
  - Augment with an on-device, user-initiated bug report (below).

- **Already-networked app (has `INTERNET`, location, accounts, etc.):**
  - The offline reasoning above does NOT apply — the app already transmits data, so a
    network crash reporter (Crashlytics/Sentry) is legitimately on the table. Weigh
    real-time/breadcrumb value against vitals' zero-cost coverage.
  - Whatever you choose, update the **Data Safety form** and **privacy policy** to
    match (crash payloads, device identifiers, location precision).

> Pitfall observed: a weather/location app already has `INTERNET` + background
> location, so importing an offline app's "vitals-only, no SDK" conclusion would be
> wrong. Re-derive per app.

---

## Step 1 — Gap analysis BEFORE implementing

Audit what already exists so you don't rebuild it: search for an `Application`
subclass / `UncaughtExceptionHandler`, any `CrashReporter`/`BugReport` classes,
existing crash tests, and the release build config. Only close real gaps.

---

## Step 2 — The implementation pattern (transferable regardless of step 0)

1. **R8 / minification.** Keep `minifyEnabled` + resource shrinking (size wins). For
   obfuscation:
   - If the **source is public** (open/source-available): add `-dontobfuscate` +
     `-keepattributes SourceFile,LineNumberTable`. Obfuscation buys no IP protection
     when the code is public, and un-mangled traces help on-device bug reports.
   - If the **source is closed**: keep obfuscation and rely on the R8 `mapping.txt`
     bundled in the uploaded `.aab` — Play Console auto-deobfuscates vitals from it.
   - Either way: **upload an `.aab`** so vitals stay deobfuscated.

2. **Persist the last crash on-device.** A custom `Application` registers
   `Thread.setDefaultUncaughtExceptionHandler { ... }` that writes the trace
   (timestamp, thread, app version, device, stack) to app-private storage, then
   **delegates to the previously-installed handler** so the OS still terminates
   normally and vitals still records it. Writes only to private storage — no network,
   no privacy-posture change.

3. **Extract the read/write into a standalone object** (e.g. `CrashStore`) instead of
   burying it in the `Application`/`Activity`. One object owns the filename contract
   for both writer and reader, and its logic is unit-testable with plain `File` I/O —
   no Android, no Robolectric, no reflection. (Mirror the codebase's existing
   "testable logic object" convention.)

4. **Surface the persisted crash** in the user-initiated bug report, so a submitted
   report includes the real trace even after the process restarted (logcat is
   volatile). Keep it gated behind the user's send action — stays on-device until sent.

5. **Eliminate silent failures.** Empty `catch (e: SecurityException) {}` / generic
   swallow blocks hide real problems (e.g. revoked permission → silently empty UI).
   At minimum `Log.w(...)` so they appear in logcat, vitals, and bug reports.

---

## Step 3 — Test layering (and what to avoid)

- **Unit:** test the extracted logic object directly — fast, no framework
  (placeholder-when-absent, round-trip, overwrite, error path).
- **Integration:** ONE Robolectric test that triggers the real installed handler and
  asserts the crash reads back through the store. Wrap the trigger in `catch
  (Throwable)` — persistence runs before the delegate, so the assertion stays
  deterministic regardless of the delegate's terminate behavior.
- **Avoid:** reflecting into `private` methods, and asserting on output assembled by
  async `thread {}` work (flaky). If a method is hard to test, that's a signal to
  extract a seam (step 2.3) — **not** to widen visibility or reach in with reflection.

---

## Step 4 — Manual on-device checks (can't be automated)

- Force a real uncaught crash, relaunch, open the bug report, confirm a readable
  `File.kt:NN` trace appears.
- Revoke a sensitive permission, refresh, confirm the warning log fires instead of a
  silently empty UI.
- Post-launch: watch Play Console → Quality → Android vitals (Crashes & ANRs);
  confirm traces are deobfuscated.
