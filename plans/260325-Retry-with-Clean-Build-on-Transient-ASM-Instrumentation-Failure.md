# Fix: Retry with Clean Build on Transient ASM Instrumentation Failure

## Context

Running `scripts/emulator-tests.sh` occasionally hits a transient build failure:
```
Execution failed for task ':app:transformDebugClassesWithAsm'
> Error occurred while instrumenting class com.weatherwidget.widget.WeatherWidgetProvider
```

This is caused by stale incremental build cache corrupting the ASM bytecode instrumentation (Hilt's `@AndroidEntryPoint` transform). Rather than always running `clean` (which adds ~30-60s), we should detect this specific error and retry with `clean` only when it occurs.

## Implementation

### File: `scripts/emulator-tests.sh`

**Path 1: Multi-emulator pre-build (line ~372)**

After the existing build failure at line 372-376, before printing "Build failed" and exiting, check if the log contains `transformDebugClassesWithAsm`. If so, print a message and retry with `clean` prepended:

```bash
if ! "$PROJECT_DIR/gradlew" assembleDebug assembleDebugAndroidTest --console=plain > "$TEST_RESULTS_LOG" 2>&1; then
    if grep -q "transformDebugClassesWithAsm" "$TEST_RESULTS_LOG" 2>/dev/null; then
        echo -e "${YELLOW}ASM instrumentation error detected — retrying with clean build...${NC}"
        if ! "$PROJECT_DIR/gradlew" clean assembleDebug assembleDebugAndroidTest --console=plain > "$TEST_RESULTS_LOG" 2>&1; then
            echo -e "${RED}Build failed${NC}"
            cat "$TEST_RESULTS_LOG"
            exit 1
        fi
    else
        echo -e "${RED}Build failed${NC}"
        cat "$TEST_RESULTS_LOG"
        exit 1
    fi
fi
```

**Path 2: Main single-emulator path (line ~712-715)**

After the build completion poll loop, the script checks `grep -q "BUILD SUCCESSFUL"` at line 713. If the build failed, we need to check if it was an ASM error before proceeding to the test results section. If it was, clean and re-run the full Gradle command:

After line 715 (`fi` closing the BUILD SUCCESSFUL check), add:

```bash
# Retry on transient ASM instrumentation failure (stale incremental cache)
if [ "$TEST_SUCCESS" = false ] && grep -q "transformDebugClassesWithAsm" "$TEST_RESULTS_LOG" 2>/dev/null; then
    echo -e "${YELLOW}ASM instrumentation error detected — retrying with clean build...${NC}"
    : > "$TEST_RESULTS_LOG"

    show_progress "$TEST_RESULTS_LOG" &
    PROGRESS_PID=$!

    script -qfc "./gradlew clean $GRADLE_CMD $GRADLE_APK_PRESERVE_ARG --console=plain --info" \
        "$TEST_RESULTS_LOG" > /dev/null 2>&1 &
    GRADLE_PID=$!

    # Same poll loop as before (re-poll for build completion)
    WAIT_ELAPSED=0
    while kill -0 $GRADLE_PID 2>/dev/null; do
        if [ $WAIT_ELAPSED -ge $TEST_TIMEOUT ]; then
            echo -e "${RED}Test timeout after ${TEST_TIMEOUT}s${NC}"
            break
        fi
        if grep -q "BUILD SUCCESSFUL\|BUILD FAILED" "$TEST_RESULTS_LOG" 2>/dev/null; then
            sleep 2
            break
        fi
        sleep 1
        WAIT_ELAPSED=$((WAIT_ELAPSED + 1))
    done

    if kill -0 $GRADLE_PID 2>/dev/null; then
        kill $GRADLE_PID 2>/dev/null || true
        sleep 1
        kill -9 $GRADLE_PID 2>/dev/null || true
    fi
    wait $GRADLE_PID 2>/dev/null || true

    kill $PROGRESS_PID 2>/dev/null || true
    wait "$PROGRESS_PID" 2>/dev/null || true

    if grep -q "BUILD SUCCESSFUL" "$TEST_RESULTS_LOG" 2>/dev/null; then
        TEST_SUCCESS=true
    fi

    TEST_END=$(date +%s)
    TEST_DURATION=$((TEST_END - TEST_START))
fi
```

### Reducing duplication

The main poll loop (lines 669-701) and the retry poll loop are identical. To avoid copy-pasting ~30 lines, extract a function like `run_gradle_and_poll()` that encapsulates:
1. Truncate log file
2. Start progress monitor
3. Launch Gradle via `script -qfc`
4. Poll for completion/timeout
5. Kill Gradle if hung
6. Kill progress monitor
7. Set `TEST_SUCCESS` based on output

Both the initial run and the retry call this function. The retry simply prepends `clean` to `$GRADLE_CMD`.

## Files to Modify

1. **`scripts/emulator-tests.sh`**
   - Extract `run_gradle_and_poll()` function (from existing lines ~648-720)
   - Add ASM retry logic after initial run returns failure
   - Same pattern for multi-emulator pre-build path (line ~372)

## Verification

1. Manually corrupt the build cache to trigger the error, verify the retry succeeds:
   ```bash
   # Corrupt a class file to simulate stale cache
   touch app/build/intermediates/classes/debug/com/weatherwidget/widget/WeatherWidgetProvider.class
   ./scripts/emulator-tests.sh
   # Should see "ASM instrumentation error detected — retrying with clean build..."
   ```
2. Normal run (no error): verify no retry occurs and timing is unchanged
3. Real build failure (e.g., syntax error): verify it exits with failure, no retry
