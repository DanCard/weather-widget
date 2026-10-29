package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.PreUnplugFetchHostContract
import com.weatherwidget.shared.util.PreUnplugFetchRunner
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** Desktop file half of [PreUnplugFetchHostContract]; the prefs half is `PrefsPreUnplugHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopPreUnplugHostContractTest : PreUnplugFetchHostContract() {
    private val paths = mutableListOf<Path>()
    private lateinit var dir: Path
    private lateinit var dao: DesktopWeatherDao

    override fun open(nowMs: Long): PreUnplugFetchRunner.Host {
        val db = Files.createTempFile("weather_contract", ".db").also { paths.add(it) }
        dir = Files.createTempDirectory("charge_contract").also { paths.add(it) }
        dao = DesktopWeatherDao(DesktopWeatherDatabase(db).apply { initialize() })
        return reopen(nowMs)
    }

    override fun reopen(nowMs: Long): PreUnplugFetchRunner.Host =
        DesktopPreUnplugHost(dao, DesktopChargeHistoryStore(dir.resolve("charge_history")), clock = { nowMs }, wake = {})

    @After
    fun deleteFiles() = paths.forEach { it.toFile().deleteRecursively() }
}
