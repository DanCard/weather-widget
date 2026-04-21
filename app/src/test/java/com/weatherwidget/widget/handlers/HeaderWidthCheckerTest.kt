package com.weatherwidget.widget.handlers

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
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
            apiTextSizeSp = 16f,
            currentTempText = "72.5°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeSp = 26f,
        )
        assertEquals(HeaderDisclosureLevel.FULL, result)
    }

    @Test
    fun `at narrow width disclosure level is below FULL`() {
        val result = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = 80,
            apiSourceText = "NWS",
            apiTextSizeSp = 16f,
            currentTempText = "72.5°",
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeSp = 26f,
        )
        assertTrue("Expected disclosure level below FULL, got $result", result != HeaderDisclosureLevel.FULL)
    }

    @Test
    fun `current temp always shown when any header is shown`() {
        val withAll = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context, widthDp = 80,
            apiSourceText = "NWS", apiTextSizeSp = 16f,
            currentTempText = "72.5°", deltaText = "+1.2", precipText = "30%", precipTextSizeSp = 26f,
        )
        val minimalOnly = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context, widthDp = 80,
            apiSourceText = "NWS", apiTextSizeSp = 16f,
            currentTempText = "72.5°", deltaText = null, precipText = null, precipTextSizeSp = null,
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
            apiTextSizeSp = 16f,
            currentTempText = "72°",
            deltaText = null,
            precipText = null,
            precipTextSizeSp = null,
        )
        assertTrue("Expected non-NONE disclosure level, got $result", result != HeaderDisclosureLevel.NONE)
    }

    @Test
    fun `null currentTempText still returns some disclosure level`() {
        val result = HeaderWidthChecker.resolveHeaderDisclosure(
            context = context,
            widthDp = 500,
            apiSourceText = "NWS",
            apiTextSizeSp = 16f,
            currentTempText = null,
            deltaText = "+1.2",
            precipText = "30%",
            precipTextSizeSp = 26f,
        )
        assertTrue(result != HeaderDisclosureLevel.NONE)
    }
}