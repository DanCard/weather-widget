package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.SourceRetryHostContract
import com.weatherwidget.shared.util.SourceRetry
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.io.File

/** Desktop SQLite half of [SourceRetryHostContract]; the Room half is `AndroidSourceRetryHostContractTest`. */
@Category(ShortDuration::class)
class DesktopSourceRetryHostContractTest : SourceRetryHostContract() {
    private val dbFile: File = File.createTempFile("source-retry-contract", ".db")
    private lateinit var dao: DesktopWeatherDao
    private val wakes = mutableListOf<Pair<String, Long>>()
    private var lastArmed: Pair<String, Long>? = null

    override fun open(displayed: String): SourceRetry.Host {
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbFile.toPath()).apply { initialize() })
        val host = DesktopSourceRetryHost(dao, { displayed }, { true }, { true }) { lastArmed?.let { wakes += it } }
        // Record what was armed so the wake reports it, as Android's enqueue does.
        return object : SourceRetry.Host by host {
            override suspend fun arm(sourceId: String, retry: SourceRetry.Pending) {
                lastArmed = sourceId to retry.delayMs
                host.arm(sourceId, retry)
            }
        }
    }

    override suspend fun recordSuccess(sourceId: String) {
        Thread.sleep(2) // after the armed row, and before whatever is armed next (rows compare by ms)
        dao.log("REFRESH", "source=$sourceId hourly=1 daily=1", "INFO")
        Thread.sleep(2)
    }

    override fun wakes() = wakes.toList()

    @After
    fun tearDown() {
        dbFile.delete()
    }
}
