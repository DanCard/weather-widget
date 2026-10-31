package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.SourceDragHostContract
import com.weatherwidget.shared.sourceview.SourceDragRun
import com.weatherwidget.test.category.MediumDuration
import org.junit.After
import org.junit.Before
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** The Settings host against real SQLite, through the process's app-log queue. */
@Category(MediumDuration::class)
class DesktopSourceDragHostContractTest : SourceDragHostContract() {
    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("source-drag-host", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        DesktopAppLogQueue.install(dao)
    }

    @After
    fun teardown() {
        DesktopAppLogQueue.install(null)
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    override fun host(onSave: (List<String>) -> Unit): SourceDragRun.Host = DesktopSourceDragHost(onSave)

    override fun storedLogs(tag: String, count: Int): List<String> {
        DesktopAppLogQueue.drain()
        return dao.getRecentLogsByTags(listOf(tag)).map { it.message }
    }
}
