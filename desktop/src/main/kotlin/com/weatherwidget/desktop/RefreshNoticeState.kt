package com.weatherwidget.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weatherwidget.shared.util.RefreshNotice
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where a refresh notice shows: the window whose button (or the popup whose source cycle) asked. */
enum class RefreshNoticeSurface { POPUP, SETTINGS, HISTORY, OBSERVATIONS }

/**
 * The desktop's two-second refresh toast ([RefreshNotice]; plans/261015-refresh-queued-toast-all-surfaces.md):
 * what a user's refresh queued, or why nothing. Android's `Toast.LENGTH_SHORT` is the same 2 s.
 *
 * One per process at a time, at most one per [RefreshNotice.DURATION_MS] ([RefreshNotice.shouldShow]).
 * Each [show] writes `REFRESH_TOAST` through [log], shown or suppressed.
 */
class RefreshNoticeState(
    private val log: (message: String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val formatTime: (Long) -> String = ::formatClockTime,
) {
    data class Shown(val text: String, val surface: RefreshNoticeSurface, val serial: Int)

    var current by mutableStateOf<Shown?>(null)
        private set

    private var lastShownMs: Long? = null
    private var serial = 0

    fun show(notice: RefreshNotice, surface: RefreshNoticeSurface, origin: String) {
        val now = clock()
        val shown = synchronized(this) {
            RefreshNotice.shouldShow(lastShownMs, now).also { if (it) lastShownMs = now }
        }
        if (shown) current = Shown(RefreshNotice.text(notice, formatTime), surface, ++serial)
        log("origin=$origin ${RefreshNotice.logDetail(notice)} ${if (shown) "shown" else "suppressed"}")
    }

    /** Clears [shown] after [RefreshNotice.DURATION_MS], unless a newer notice replaced it meanwhile. */
    suspend fun expire(shown: Shown) {
        delay(RefreshNotice.DURATION_MS)
        if (current?.serial == shown.serial) current = null
    }

    /** The text for [surface] now, or null. */
    fun textFor(surface: RefreshNoticeSurface): String? = current?.takeIf { it.surface == surface }?.text
}

/** Provided by the application root, so every window reads the one state without threading it through. */
val LocalRefreshNotice = staticCompositionLocalOf<RefreshNoticeState?> { null }

internal fun formatClockTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Runs the expiry for whatever notice is current; place once, at the application root. */
@Composable
fun RefreshNoticeExpiry(state: RefreshNoticeState) {
    val shown = state.current
    LaunchedEffect(shown?.serial) { shown?.let { state.expire(it) } }
}

/** The pill a window shows over its content (align it bottom-centre in the window's root Box). */
@Composable
fun RefreshNoticePill(surface: RefreshNoticeSurface, modifier: Modifier = Modifier) {
    val text = LocalRefreshNotice.current?.textFor(surface) ?: return
    Box(
        modifier
            .padding(16.dp)
            .background(Color(0xE6202124), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("refresh_notice_${surface.name.lowercase()}"),
    ) {
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}
