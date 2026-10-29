package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SourceSwitchRunner.Host] means on Android's widget Host (Room, widget prefs) and desktop's
 * (SQLite, config) (plans/261029-shared-source-switch-runner.md). The persist step makes the source
 * the displayed one without an app_logs row, since that insert is what stalled a desktop switch for 5 s;
 * the view tally counts one switch per call under its trigger; the log row reads back; and a whole run
 * through the shared runner leaves all three. Refresh is platform glue (a WorkManager request on
 * Android, the daemon's check on desktop), so it is not part of this contract.
 */
abstract class SourceSwitchHostContract {
    /** A fresh database with [from] displayed and NWS, OPEN_METEO, SILURIAN visible; a Host switching to [to]. */
    protected abstract fun open(from: WeatherSource, to: WeatherSource, trigger: SourceViewTrigger): SourceSwitchRunner.Host

    /** The source the platform now displays, by [WeatherSource.id]. */
    protected abstract fun displayedSourceId(): String

    /** Every app_logs message, oldest first. */
    protected abstract suspend fun appLogs(): List<Pair<String, String>>

    /** `source_view_days` switches counted for [sourceId] under [trigger]. */
    protected abstract suspend fun viewSwitches(sourceId: String, trigger: SourceViewTrigger): Int

    @Test
    fun `persist makes the target the displayed source and writes no app_logs row`() = runBlocking {
        val host = open(WeatherSource.NWS, WeatherSource.OPEN_METEO, SourceViewTrigger.TOGGLE)
        val before = appLogs()

        assertEquals(WeatherSource.OPEN_METEO, host.persistSelection())
        assertEquals("OPEN_METEO", displayedSourceId())
        assertEquals(before, appLogs())
    }

    @Test
    fun `each recorded view counts one switch under its trigger`() = runBlocking {
        val host = open(WeatherSource.OPEN_METEO, WeatherSource.NWS, SourceViewTrigger.HOME)
        host.recordView(WeatherSource.NWS)
        host.recordView(WeatherSource.NWS)

        assertEquals(2, viewSwitches("NWS", SourceViewTrigger.HOME))
        assertEquals(0, viewSwitches("NWS", SourceViewTrigger.TOGGLE))
    }

    @Test
    fun `a persisted log row reads back with its tag`() = runBlocking {
        val host = open(WeatherSource.NWS, WeatherSource.SILURIAN, SourceViewTrigger.TOGGLE)
        host.persistLog(SourceSwitchRunner.E2E_TAG, "platform=contract phase=done", "INFO")

        assertTrue(appLogs().contains(SourceSwitchRunner.E2E_TAG to "platform=contract phase=done"))
    }

    @Test
    fun `a run through the shared runner switches, counts and logs`() = runBlocking {
        val host = open(WeatherSource.NWS, WeatherSource.SILURIAN, SourceViewTrigger.TOGGLE)
        SourceSwitchRunner(host, platform = "contract").run(WeatherSource.NWS, SourceViewTrigger.TOGGLE, this)!!.join()

        assertEquals("SILURIAN", displayedSourceId())
        assertEquals(1, viewSwitches("SILURIAN", SourceViewTrigger.TOGGLE))
        val row = appLogs().single { it.first == SourceSwitchRunner.E2E_TAG }.second
        assertTrue(row, row.startsWith("platform=contract from=NWS to=SILURIAN trigger=TOGGLE phase=done"))
    }
}
