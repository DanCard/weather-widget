package com.weatherwidget.data.repository

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.contract.CurrentConditionsHostContract
import com.weatherwidget.shared.observations.CurrentConditionsRun
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android half of [CurrentConditionsHostContract]; the desktop half is `DesktopCurrentConditionsHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class AndroidCurrentConditionsHostContractTest : CurrentConditionsHostContract() {
    private val databases = mutableListOf<WeatherDatabase>()

    override fun open(): CurrentConditionsRun.Host {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = TestDatabase.create().also { databases.add(it) }
        return AndroidCurrentConditionsHost(
            context, WidgetStateManager(context), db.appLogDao(),
            fetch = { _, _, _ -> error("no network") }, errorCode = { "TEST" },
        )
    }

    @After
    fun closeDatabases() = databases.forEach { it.close() }
}
