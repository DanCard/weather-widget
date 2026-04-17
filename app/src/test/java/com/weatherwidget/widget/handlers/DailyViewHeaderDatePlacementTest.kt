package com.weatherwidget.widget.handlers

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class DailyViewHeaderDatePlacementTest {

    @Test
    fun `resolveHeaderDatePlacement hides date when fewer than six columns are shown`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyViewHandler.resolveHeaderDatePlacement(
                context = context,
                widthDp = 300,
                numColumns = 5,
                currentTempText = "1234567890°",
                deltaText = null,
                precipText = null,
                precipTextSizeSp = null,
                apiSourceText = "NWS",
                apiTextSizeSp = 16f,
                dateText = "Sun 19",
            )

        assertNull(placement)
    }

    @Test
    fun `resolveHeaderDatePlacement prefers center when header text leaves room`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyViewHandler.resolveHeaderDatePlacement(
                context = context,
                widthDp = 360,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = null,
                precipText = null,
                precipTextSizeSp = null,
                apiSourceText = "NWS",
                apiTextSizeSp = 16f,
                dateText = "Sun 19",
            )

        assertEquals(DailyViewHandler.HeaderDatePlacement.CENTER, placement)
    }

    @Test
    fun `resolveHeaderDatePlacement keeps center when header text still leaves room`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val placement =
            DailyViewHandler.resolveHeaderDatePlacement(
                context = context,
                widthDp = 220,
                numColumns = 8,
                currentTempText = "60.0°",
                deltaText = "+2.6",
                precipText = "54%",
                precipTextSizeSp = 26f,
                apiSourceText = "NWS",
                apiTextSizeSp = 16f,
                dateText = "Sun 19",
            )

        assertEquals(DailyViewHandler.HeaderDatePlacement.CENTER, placement)
    }
}
