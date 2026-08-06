package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
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
class HeaderWidthCheckerTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `FULL at very wide width`() {
        val result = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 16f,
            currentTempText = "72.5°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        assertEquals(HeaderDisclosureLevel.FULL, result)
    }

    @Test
    fun `at narrow width disclosure level is below FULL`() {
        val result = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = 80,
            apiSourceText = "NWS",
            apiTextSizeDp = 16f,
            currentTempText = "72.5°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        assertTrue("Expected disclosure level below FULL, got $result", result != HeaderDisclosureLevel.FULL)
    }

    @Test
    fun `current temp always shown when any header is shown`() {
        val withAll = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context, widthDp = 80,
            apiSourceText = "NWS", apiTextSizeDp = 16f,
            currentTempText = "72.5°", deltaText = "+1.2", precipText = "30%", precipTextSizeDp = 26f,
        )
        val minimalOnly = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context, widthDp = 80,
            apiSourceText = "NWS", apiTextSizeDp = 16f,
            currentTempText = "72.5°", deltaText = null, precipText = null, precipTextSizeDp = null,
        )
        assertTrue(withAll != HeaderDisclosureLevel.NONE)
        assertTrue(minimalOnly != HeaderDisclosureLevel.NONE)
    }

    @Test
    fun `showsIcon helper for disclosure levels`() {
        assertEquals(true, HeaderDisclosureLevel.FULL.showsIcon())
        assertEquals(false, HeaderDisclosureLevel.NO_ICON.showsIcon())
        assertEquals(false, HeaderDisclosureLevel.NO_ICON_NO_DELTA.showsIcon())
        assertEquals(false, HeaderDisclosureLevel.MINIMAL.showsIcon())
        assertEquals(false, HeaderDisclosureLevel.NONE.showsIcon())
    }

    @Test
    fun `showsDelta helper for disclosure levels`() {
        assertEquals(true, HeaderDisclosureLevel.FULL.showsDelta())
        assertEquals(true, HeaderDisclosureLevel.NO_ICON.showsDelta())
        assertEquals(false, HeaderDisclosureLevel.NO_ICON_NO_DELTA.showsDelta())
        assertEquals(false, HeaderDisclosureLevel.MINIMAL.showsDelta())
        assertEquals(false, HeaderDisclosureLevel.NONE.showsDelta())
    }

    @Test
    fun `showsPrecip helper for disclosure levels`() {
        assertEquals(true, HeaderDisclosureLevel.FULL.showsPrecip())
        assertEquals(true, HeaderDisclosureLevel.NO_ICON.showsPrecip())
        assertEquals(true, HeaderDisclosureLevel.NO_ICON_NO_DELTA.showsPrecip())
        assertEquals(false, HeaderDisclosureLevel.MINIMAL.showsPrecip())
        assertEquals(false, HeaderDisclosureLevel.NONE.showsPrecip())
    }

    @Test
    fun `null delta and precip returns non-NONE at wide enough width`() {
        val result = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = 200,
            apiSourceText = "NWS",
            apiTextSizeDp = 16f,
            currentTempText = "72°",
            deltaText = null,
            precipText = null,
            precipTextSizeDp = null,
        )
        assertTrue("Expected non-NONE disclosure level, got $result", result != HeaderDisclosureLevel.NONE)
    }

    @Test
    fun `null currentTempText still returns some disclosure level`() {
        val result = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 16f,
            currentTempText = null,
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        assertTrue(result != HeaderDisclosureLevel.NONE)
        }

        @Test
        fun `computeHeaderScale returns 1-0 for Pixel 7 Pro standard width`() {
        // Pixel 7 Pro standard width is ~411dp. 
        // It should NOT scale up even if empty space is high, to avoid "Everything enlarged" bug.
        val scale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = 411,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        assertEquals(1.0f, scale, 0.01f)
        }

        @Test
        fun `computeHeaderScale returns 1-35 for very wide Samsung-like width with low occupancy`() {
        // Very wide widget (e.g. 500dp) should scale up if occupancy < 0.50
        val scale = HeaderWidthChecker.computeHeaderScale(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeDp = 26f,
        )
        // At 500dp width, occupied width is ~164dp (occupancy ~0.33), which is < 0.50.
        // It meets both the width requirement (>=450) and occupancy requirement.
        assertEquals(1.35f, scale, 0.01f)
        }

        @Test
        fun `deltaLabelFitsInHeader true at very wide width`() {
        val result = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = "+1.2",
            deltaLabelText = "from yest",
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
        )
        assertTrue(result)
        }

        @Test
        fun `deltaLabelFitsInHeader false at narrow width`() {
        val result = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 80,
            apiSourceText = "NWS",
            apiTextSizeDp = 16f,
            currentTempText = "72°",
            deltaText = "+1.2",
            deltaLabelText = "from yest",
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
        )
        assertTrue(!result)
        }

        @Test
        fun `inlineNavRowWidthDp is zero at and above the inline threshold`() {
        assertEquals(0f, HeaderWidthChecker.inlineNavRowWidthDp(420), 0.01f)
        assertEquals(0f, HeaderWidthChecker.inlineNavRowWidthDp(500), 0.01f)
        }

        @Test
        fun `inlineNavRowWidthDp mirrors positionCenterIcons zone widths`() {
        // Robolectric SDK 35 >= API 31: zones resize to 32/40/48dp by widget width.
        // 4 zones (selector|stations|home|history) + 1dp marginStart on the selector zone.
        assertEquals(4 * 48f + 1f, HeaderWidthChecker.inlineNavRowWidthDp(419), 0.01f)
        assertEquals(4 * 40f + 1f, HeaderWidthChecker.inlineNavRowWidthDp(373), 0.01f)
        assertEquals(4 * 32f + 1f, HeaderWidthChecker.inlineNavRowWidthDp(300), 0.01f)
        // Stations zone hidden for non-today graphs.
        assertEquals(3 * 40f + 1f, HeaderWidthChecker.inlineNavRowWidthDp(373, showStations = false), 0.01f)
        }

        @Test
        fun `deltaLabelFitsInHeader dropped when inline nav row crowds the header`() {
        // Narrow hourly header with the inline nav row shown (Pixel 7 Pro style): the
        // caption fits when the inline nav icons are ignored (the old bug) but must be
        // dropped once the inline row is counted, so the history icon no longer overlaps
        // the API label. widthDp=200 keeps the assertion meaningful under Robolectric's
        // coarse text metrics (measured left cluster without precip ~47px, API cluster ~61px).
        val inlineNavWidthDp = HeaderWidthChecker.inlineNavRowWidthDp(200, showStations = true)
        // precipText must be null here: a visible rain chance suppresses the caption outright
        // (see the precip-priority test below), which would mask the inline-nav effect.
        val withoutInline = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 200,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "67.7°",
            deltaText = "+0.5",
            deltaLabelText = "from yest",
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
            inlineNavWidthDp = 0f,
        )
        val withInline = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 200,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "67.7°",
            deltaText = "+0.5",
            deltaLabelText = "from yest",
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
            inlineNavWidthDp = inlineNavWidthDp,
        )
        assertTrue("expected caption to fit when inline nav row is ignored", withoutInline)
        assertTrue("expected caption to be dropped once the inline nav row is counted", !withInline)
        }

        @Test
        fun `deltaLabelFitsInHeader false when rain chance shown even at wide width`() {
        // Product rule: a visible precip % in the header has display priority and always
        // suppresses the "from yest" caption, no matter how much room there is.
        val withPrecip = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "67.7°",
            deltaText = "+0.5",
            deltaLabelText = "from yest",
            precipText = "1%",
            precipTextSizeDp = 18f,
            includeIcon = true,
        )
        val withoutPrecip = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "67.7°",
            deltaText = "+0.5",
            deltaLabelText = "from yest",
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
        )
        assertTrue("expected caption to be suppressed when a rain chance shows in the header", !withPrecip)
        assertTrue("expected caption to fit at wide width without precip", withoutPrecip)
        }

        @Test
        fun `deltaLabelFitsInHeader false without delta or label`() {
        val noDelta = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = null,
            deltaLabelText = "from yest",
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
        )
        val noLabel = HeaderWidthChecker.deltaLabelFitsInHeader(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeDp = 12.6f,
            currentTempText = "72°",
            deltaText = "+1.2",
            deltaLabelText = null,
            precipText = null,
            precipTextSizeDp = null,
            includeIcon = true,
        )
        assertTrue(!noDelta)
        assertTrue(!noLabel)
        }
        }