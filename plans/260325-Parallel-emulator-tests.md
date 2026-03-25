# Parallel emulator tests when multiple emulators are connected

## Context
`scripts/emulator-tests.sh` detects multiple connected emulators and re-invokes itself once per emulator (lines 316-330). Currently this runs sequentially. With two emulators, tests take 2x as long as necessary.

Simply backgrounding the sub-invocations won't work — both run `./gradlew connectedDebugAndroidTest`, and Gradle uses a project-level lock. The second process would block until the first finishes entirely.

## Approach: Build once, then parallel `am instrument`

Replace the sequential multi-emulator block (lines 316-330) with:

1. **Build both APKs** with a single Gradle command:
   ```bash
   ./gradlew assembleDebug assembleDebugAndroidTest
   ```

2. **Install APKs** on each emulator (can be parallel):
   ```bash
   adb -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s $SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```

3. **Run tests** on each emulator in parallel via `am instrument`:
   ```bash
   adb -s $SERIAL shell am instrument -w \
       [-e class $TEST_CLASS] \
       com.weatherwidget.test/androidx.test.runner.AndroidJUnitRunner
   ```

4. **Collect results** from each background process, prefix output with serial for readability.

### Key details
- The pre-test cleanup (force-stop, cancel jobs) at lines 370-378 must run per-emulator before test execution
- The `leaveApksInstalledAfterRun` flag only applies to Gradle — with manual `adb install`, APKs are always left installed (which is the desired default)
- Test class filtering (`-c CLASS`) maps to `am instrument -e class CLASS`
- The post-test widget refresh (line 738) must run per-emulator after tests complete
- Need a `prefix_output` helper (copy from `parallel-tests.sh` lines 87-93)
- Test result XML parsing (lines 541-598) won't work since Gradle didn't run `connectedDebugAndroidTest` — fall back to parsing `am instrument` output for pass/fail counts

## File to Modify
- `scripts/emulator-tests.sh` — lines 316-330 (multi-emulator block)

## Also: Display test results log path on failure

**File**: `scripts/emulator-tests.sh`

When `TEST_SUCCESS = false` or `FAILED > 0` or `ERRORS > 0`, print `$TEST_RESULTS_LOG` so the user can pass it to an AI agent:

```bash
echo -e "${RED}Full test log: $TEST_RESULTS_LOG${NC}"
```

Add this just after the "Debug log:" line (line 635), but only when there's a failure. In parallel mode, print each emulator's log path with its serial prefix.

## Verification
1. Start two emulators: `Generic_Foldable_API36` and `Medium_Phone_API_36`
2. `./scripts/emulator-tests.sh` — should build once, then run tests on both in parallel with prefixed output
3. Verify both emulator results are reported and exit code reflects any failures
4. Introduce a failing test — verify the test results log path is printed on failure
5. Test with `-c` flag to verify class filtering works in parallel mode
