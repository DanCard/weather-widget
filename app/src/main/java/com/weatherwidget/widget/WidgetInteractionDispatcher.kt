package com.weatherwidget.widget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The bounded dispatcher every widget interaction runs on.
 *
 * One tap fans out to one broadcast **per widget**, and each `onReceive` launches its own coroutine.
 * On unbounded `Dispatchers.IO` a three-widget home screen therefore ran three full paints at once,
 * each issuing its own multi-thousand-row observation read. Measured 2026-09-06 on the Samsung: 18
 * reads over 300 ms in a single refresh cycle summing 50.4 s, several with identical
 * `candidates`/`merged` counts — the same read, run concurrently, each copy paying full cost and
 * evicting the others' pages from a cache they were all about to need.
 *
 * This bounds the *interaction* path only. The worker's own fan-out
 * (`WidgetPaintCoordinator.updateAllWidgets`) is already a sequential `for` loop over widget ids, so
 * it needs no bound and deliberately keeps its own dispatcher: sharing one pool would let a 30-second
 * background sync occupy every slot and starve the taps this exists to protect.
 *
 * **Two, not one.** Serializing outright would make a second widget's paint wait out the first in
 * full, and `goAsync()` allows roughly ten seconds before the broadcast is killed (`CLICK_WATCHDOG`
 * fires at 8 s). Two keeps one slot free for a second tap while still overlapping I/O with CPU
 * rather than having N cold reads thrash together.
 *
 * Safe against the obvious deadlock: `WidgetInteractionCoordinator` locks per widget and no path
 * takes a second widget's lock while holding one — `forEachWidgetIsolated` is a sequential loop, and
 * every `withWidgetLock` call site acquires exactly one id.
 */
internal object WidgetInteractionDispatcher {

    /** Concurrent widget interactions permitted at once. */
    const val MAX_PARALLELISM = 2

    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
}
