package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.annotation.Config

@Category(LongDuration::class)
@Config(sdk = [34])
class HeaderWidthCheckerHourlyFitTest : RobolectricTest() {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun resolveHourly(
        widthDp: Int,
        apiSourceText: String = "NWS",
        precipText: String? = "72%",
        deltaText: String? = "-5.3",
    ): Pair<HeaderFit.Measured, HeaderFit.Plan> {
        val measured = HeaderWidthChecker.measureHeader(
            context = context,
            widthDp = widthDp,
            headerScale = 1f,
            apiSourceText = apiSourceText,
            apiTextSizeDp = HeaderConstants.apiTextSizeDp(numRows = 3),
            currentTempText = "53.9°",
            currentTempSizeDp = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP,
            deltaText = deltaText,
            deltaLabelText = "from yest",
            precipText = precipText,
            precipTextSizeDp = if (precipText != null) HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP else null,
            dateText = null,
            includeIcon = true,
            baseButtons = 3,
            wantHome = true,
            wantMonths = false,
        )
        return measured to HeaderFit.resolve(measured)
    }

    @Test
    fun `pixel 7 pro width keeps all 4 hourly buttons right-anchored before the API label`() {
        val (measured, fit) = resolveHourly(widthDp = 373)
        assertEquals(HeaderFit.ButtonPlacement.SHOWN, fit.placement)
        assertTrue("home fits on 373dp width: $fit", fit.homeShown)
        assertEquals(4, fit.buttons)

        val (bLeft, bRight) = HeaderFit.buttonsSpan(measured, fit)!!
        assertEquals(measured.width - measured.rightCluster - measured.gap, bRight, 0.5f)
        assertTrue("buttons sit right of center on 373dp", (bLeft + bRight) / 2f > measured.width / 2f)
    }

    @Test
    fun `buttons narrow evenly down to minButtonWidth before dropping home`() {
        val (measured, fit) = resolveHourly(widthDp = 320, apiSourceText = "Meteo")
        assertEquals(HeaderFit.ButtonPlacement.SHOWN, fit.placement)
        assertTrue("buttons remain visible at 320dp", fit.buttons >= 3)
        assertTrue(
            "narrowed zone stays within [minButtonWidth, buttonWidth]",
            fit.buttonZone in measured.minButtonWidth..measured.buttonWidth,
        )
    }
}
