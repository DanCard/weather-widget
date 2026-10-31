package com.weatherwidget.ui

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.SourceDragHostContract
import com.weatherwidget.shared.sourceview.SourceDragRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.AppLogWidgetStateEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings host against real Room, through the event logger Settings injects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class AndroidSourceDragHostContractTest : SourceDragHostContract() {
    private lateinit var db: WeatherDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    override fun host(onSave: (List<String>) -> Unit): SourceDragRun.Host =
        SettingsSourceDragHost(AppLogWidgetStateEventLogger(db.appLogDao(), scope), onSave)

    override fun storedLogs(tag: String, count: Int): List<String> = runBlocking {
        repeat(100) {
            val rows = db.appLogDao().getLogsByTag(tag, 50).map { it.message }
            if (rows.size >= count) return@runBlocking rows
            Thread.sleep(20)
        }
        db.appLogDao().getLogsByTag(tag, 50).map { it.message }
    }
}
