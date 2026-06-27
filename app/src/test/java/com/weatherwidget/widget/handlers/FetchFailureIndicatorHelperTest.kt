package com.weatherwidget.widget.handlers

import android.content.Context
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.test.category.LongDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class FetchFailureIndicatorHelperTest {

    private lateinit var context: Context
    private lateinit var appLogDao: AppLogDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appLogDao = mockk()
    }

    @Test
    fun `resolveFetchError returns null when no log exists`() = runBlocking {
        coEvery { appLogDao.getLatestCurrentTempStatus("NWS") } returns null

        val result = FetchFailureIndicatorHelper.resolveFetchError("NWS", appLogDao)
        assertNull(result)
    }

    @Test
    fun `resolveFetchError returns null when latest log status is ok`() = runBlocking {
        coEvery { appLogDao.getLatestCurrentTempStatus("NWS") } returns AppLogEntity(
            timestamp = System.currentTimeMillis(),
            tag = "CURRENT_TEMP_STATUS",
            message = "source=NWS ok=true",
            level = "INFO"
        )

        val result = FetchFailureIndicatorHelper.resolveFetchError("NWS", appLogDao)
        assertNull(result)
    }

    @Test
    fun `resolveFetchError returns formatted error when latest log status is failed`() = runBlocking {
        val lastAttemptTime = System.currentTimeMillis()
        coEvery { appLogDao.getLatestCurrentTempStatus("NWS") } returns AppLogEntity(
            timestamp = lastAttemptTime,
            tag = "CURRENT_TEMP_STATUS",
            message = "source=NWS ok=false class=ConnectTimeoutException detail=Timeout connecting to api.weather.gov",
            level = "WARN"
        )

        val lastGoodObsMs = System.currentTimeMillis() - 3600_000L // 1 hour ago
        val result = FetchFailureIndicatorHelper.resolveFetchError("NWS", appLogDao, lastGoodObsMs)
        
        assertNotNull(result)
        assertTrue(result!!.contains("NWS current temp not updating"))
        assertTrue(result.contains("Connect timeout (10s)"))
        assertTrue(result.contains("Last good obs:"))
        assertTrue(result.contains("Last attempt:"))
    }

    @Test
    fun `bind sets visibility to VISIBLE and configures PendingIntent when message is non-null`() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val errorMessage = "NWS current temp not updating\nTimeout connecting\nLast good obs: None\nLast attempt: 12:00:00"

        FetchFailureIndicatorHelper.bind(context, views, 42, errorMessage)

        val layout = views.apply(context, null)
        val warningView = layout.findViewById<android.view.View>(R.id.current_temp_warning)
        assertNotNull(warningView)
        assertEquals(android.view.View.VISIBLE, warningView.visibility)
        assertTrue(warningView.isClickable)
    }

    @Test
    fun `bind sets visibility to GONE when message is null`() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)

        FetchFailureIndicatorHelper.bind(context, views, 42, null)

        val layout = views.apply(context, null)
        val warningView = layout.findViewById<android.view.View>(R.id.current_temp_warning)
        assertNotNull(warningView)
        assertEquals(android.view.View.GONE, warningView.visibility)
    }
}
