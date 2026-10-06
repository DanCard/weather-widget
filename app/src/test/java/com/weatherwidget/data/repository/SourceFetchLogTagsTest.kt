package com.weatherwidget.data.repository

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SourceFetchLogTagsTest {

    @Test
    fun `every configurable source has a distinct failure tag`() {
        val tags = WeatherSourceOrdering.ALL_CONFIGURABLE.map { source ->
            assertNotNull("no failure tag for ${source.id}", SourceFetchLogTags.failureTag(source))
            SourceFetchLogTags.failureTag(source)
        }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `existing tags are unchanged because triage queries search for them`() {
        assertEquals("FETCH_NWS_FAIL", SourceFetchLogTags.failureTag(WeatherSource.NWS))
        assertEquals("FETCH_METEO_FAIL", SourceFetchLogTags.failureTag(WeatherSource.OPEN_METEO))
        assertEquals("FETCH_OWM_FAIL", SourceFetchLogTags.failureTag(WeatherSource.OPEN_WEATHER_MAP))
        assertEquals("FETCH_WAPI_FAIL", SourceFetchLogTags.failureTag(WeatherSource.WEATHER_API))
        assertEquals("FETCH_SILURIAN_FAIL", SourceFetchLogTags.failureTag(WeatherSource.SILURIAN))
        assertEquals("FETCH_TMRW_FAIL", SourceFetchLogTags.failureTag(WeatherSource.TOMORROW_IO))
    }
}
