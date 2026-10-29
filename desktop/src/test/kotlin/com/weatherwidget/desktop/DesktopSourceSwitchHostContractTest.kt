package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.contract.SourceSwitchHostContract
import com.weatherwidget.shared.sourceview.SourceSwitchRunner
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop SQLite half of [SourceSwitchHostContract]; the Android half is `WidgetSourceSwitchHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopSourceSwitchHostContractTest : SourceSwitchHostContract() {
    private val dirs = mutableListOf<Path>()
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private var config: DesktopConfig? = null

    override fun open(from: WeatherSource, to: WeatherSource, trigger: SourceViewTrigger): SourceSwitchRunner.Host {
        val dir = Files.createTempDirectory("source-switch-contract").also { dirs.add(it) }
        db = DesktopWeatherDatabase(dir.resolve("weather.db")).apply { initialize() }
        dao = DesktopWeatherDao(db)
        DesktopSourceViews.install(dao, executor = { it.run() })
        DesktopSourceSwitch.install(dao, CoroutineScope(Dispatchers.Unconfined))
        val start = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "Mountain View",
            settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN"), weatherSource = from.id),
        )
        config = start
        return DesktopSourceSwitch.Host(start, to.id, trigger) { config = it }
    }

    override fun displayedSourceId(): String = WeatherSource.fromDisplaySource(config!!.displaySource).id

    override suspend fun appLogs(): List<Pair<String, String>> = db.getConnection().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT tag, message FROM app_logs ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }
    }

    override suspend fun viewSwitches(sourceId: String, trigger: SourceViewTrigger): Int =
        dao.sourceViewDaysSince(0L).filter { it.sourceId == sourceId && it.trigger == trigger.name }.sumOf { it.switches }

    @After
    fun cleanup() {
        DesktopSourceSwitch.install(null)
        DesktopSourceViews.install(null)
        dirs.forEach { it.toFile().deleteRecursively() }
    }
}
