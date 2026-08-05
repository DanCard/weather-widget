package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.test.category.LongDuration
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PackageReplacedReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `package replacement renders every widget from cache once`() = runTest {
        val receiver = receiverWithTestScope()
        var renderCount = 0
        receiver.renderAllWidgetsFromCache = { _, _ -> renderCount++ }

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        advanceUntilIdle()

        assertEquals(1, renderCount)
    }

    @Test
    fun `unexpected action is ignored`() = runTest {
        val receiver = receiverWithTestScope()
        var renderCount = 0
        receiver.renderAllWidgetsFromCache = { _, _ -> renderCount++ }

        receiver.onReceive(context, Intent("com.weatherwidget.UNEXPECTED"))
        advanceUntilIdle()

        assertEquals(0, renderCount)
    }

    private fun kotlinx.coroutines.test.TestScope.receiverWithTestScope(): PackageReplacedReceiver =
        PackageReplacedReceiver().apply {
            repository = mockk<WeatherRepository>(relaxed = true)
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        }
}
