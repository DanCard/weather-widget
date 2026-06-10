# Context

The "⚠ SILURIAN UPDATES FAILING" watermark pill on the widget graphs is opaque — it names the source but gives no reason. The user wants to know the HTTP error code and what it means. The error code IS captured inside `ApiAccessException.statusCode` at catch time; it's just never stored for later rendering. `recordSourceFetchFailure()` only increments a counter, dropping the code on the floor.

---

## What changes

### 1. `WidgetStateManager.kt`

Add error code storage alongside the existing failure counter.

```kotlin
private const val KEY_SOURCE_FAILURE_CODE_PREFIX = "source_fail_code_"

fun recordSourceFetchFailure(source: WeatherSource, errorCode: String? = null) {
    val next = getSourceFailureCount(source) + 1
    prefs.edit()
        .putInt("$KEY_SOURCE_FAILURE_COUNT_PREFIX${source.id}", next)
        .apply { errorCode?.let { putString("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}", it) } }
        .apply()
}

fun getSourceLastErrorCode(source: WeatherSource): String? =
    prefs.getString("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}", null)

// In recordSourceFetchSuccess — also clear the stored code:
prefs.edit()
    .putInt("$KEY_SOURCE_FAILURE_COUNT_PREFIX${source.id}", 0)
    .remove("$KEY_SOURCE_FAILURE_CODE_PREFIX${source.id}")
    .apply()
```

### 2. `ForecastRepository.kt` — `safeFetch` catch block

Extract the error code from the exception and pass it to `recordSourceFetchFailure`.

```kotlin
} catch (exception: Exception) {
    val errorCode = extractErrorCode(exception)
    widgetStateManager.recordSourceFetchFailure(source, errorCode)
    logFetchFailure(tag, source, exception)
    null
}
```

Add a private helper (mirrors the existing `logFetchFailure` extraction logic):

```kotlin
private fun extractErrorCode(exception: Exception): String? = when (exception) {
    is ApiAccessException -> exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
    is ClientRequestException -> "HTTP_${exception.response.status.value}"
    else -> null
}
```

### 3. `CurrentTempRepository.kt` — both catch blocks (lines 184–193)

Same pattern — extract code and pass to `recordSourceFetchFailure`:

```kotlin
} catch (exception: ApiAccessException) {
    val errorCode = exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
    widgetStateManager.recordSourceFetchFailure(targetSource, errorCode)
    ...
} catch (exception: Exception) {
    widgetStateManager.recordSourceFetchFailure(targetSource, null)
    ...
}
```

(Or extract a shared `extractErrorCode` helper into a shared location — either repo is fine since both live in the same module.)

### 4. Four view handlers — thread `errorCode` to renderers

In each handler that calls `renderGraph()` with `showErrorWatermark`/`errorSourceLabel`, add:

```kotlin
errorCode = stateManager.getSourceLastErrorCode(displaySource),
```

Files:
- `handlers/TemperatureStateResolver.kt` (line ~251)
- `handlers/DailyViewHandler.kt` (line ~1175)
- `handlers/CloudCoverViewHandler.kt` (line ~371)
- `handlers/PrecipViewHandler.kt` (line ~333)

### 5. Four graph renderers — add `errorCode` parameter

In each `renderGraph()` signature, add `errorCode: String? = null` alongside the existing params, and pass it through to `drawErrorWatermark`:

```kotlin
GraphRenderUtils.drawErrorWatermark(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode)
```

Files: `TemperatureGraphRenderer.kt`, `DailyForecastGraphRenderer.kt`, `CloudCoverGraphRenderer.kt`, `PrecipitationGraphRenderer.kt`

### 6. `GraphRenderUtils.drawErrorWatermark` — display error code

Add `errorCode: String? = null` parameter. If present, render a second smaller line inside the pill showing a human-readable translation:

```kotlin
fun humanReadableCode(code: String): String = when {
    code == "HTTP_401" -> "401 Unauthorized"
    code == "HTTP_403" -> "403 Forbidden"
    code == "HTTP_429" -> "429 Rate Limited"
    code.startsWith("HTTP_5") -> "${code.removePrefix("HTTP_")} Server Error"
    code.startsWith("HTTP_") -> code.removePrefix("HTTP_").let { "HTTP $it" }
    code == "ACCESS_ERROR" -> "Access Error"
    else -> code
}
```

Layout: main text line "⚠ SILURIAN UPDATES FAILING", then a second line (10sp, same coral-red, 70% opacity) with just the code reason, e.g. "403 Forbidden". Pill height grows to accommodate both lines. Pill width uses the wider of the two text measurements.

---

## Files modified (summary)

| File | Change |
|------|--------|
| `WidgetStateManager.kt` | Store/retrieve last error code per source |
| `ForecastRepository.kt` | Extract code at catch, pass to `recordSourceFetchFailure` |
| `CurrentTempRepository.kt` | Same |
| `TemperatureStateResolver.kt` | Pass `errorCode` to renderer |
| `DailyViewHandler.kt` | Pass `errorCode` to renderer |
| `CloudCoverViewHandler.kt` | Pass `errorCode` to renderer |
| `PrecipViewHandler.kt` | Pass `errorCode` to renderer |
| `TemperatureGraphRenderer.kt` | Accept + forward `errorCode` |
| `DailyForecastGraphRenderer.kt` | Accept + forward `errorCode` |
| `CloudCoverGraphRenderer.kt` | Accept + forward `errorCode` |
| `PrecipitationGraphRenderer.kt` | Accept + forward `errorCode` |
| `GraphRenderUtils.kt` | Render second line with translated error code |

---

## Not in scope

- `ApiSourceWarningHelper.classifyBlockingSourceWarning`: currently only shows the blocking warning screen for 401/missing-key. Could add a non-401 fallback, but that's a separate issue and the watermark already covers the common case.
- Desktop port: desktop graph renderer is a separate reimplementation; out of scope here.

---

## Verification

1. Build and install: `./gradlew installDebug`
2. In Settings, set source to Silurian with an **invalid API key** (forces non-401 or 403).
3. Wait for 3 fetch cycles (or trigger with ACTION_REFRESH) — watermark should appear on all graph types.
4. Verify watermark now shows e.g. "403 Forbidden" below "SILURIAN UPDATES FAILING".
5. Fix the API key → watermark disappears after next successful fetch.
6. Check logcat for `FETCH_SILURIAN_FAIL` tag to confirm code is being logged correctly.
