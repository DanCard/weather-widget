package com.weatherwidget.util

import android.graphics.LinearGradient
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherConditionColorsRobolectricTest {

    @Test
    fun `forecastBarGradient uses the passed x coordinate for both start and end points`() {
        val testX = 123.45f
        val topY = 10f
        val bottomY = 200f
        val icon = R.drawable.ic_weather_partly_cloudy_chance_rain
        
        val gradient = WeatherConditionColors.forecastBarGradient(icon, testX, topY, bottomY)
        
        // Use reflection to verify internal fields of LinearGradient
        assertEquals("x0 must match the passed x coordinate", testX, getField(gradient!!, "mX0") as Float, 0.001f)
        assertEquals("x1 must match the passed x coordinate", testX, getField(gradient, "mX1") as Float, 0.001f)
        assertEquals("y0 must match topY", topY, getField(gradient, "mY0") as Float, 0.001f)
        assertEquals("y1 must match bottomY", bottomY, getField(gradient, "mY1") as Float, 0.001f)
    }

    private fun getField(obj: Any, fieldName: String): Any? {
        val field: Field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }
}
