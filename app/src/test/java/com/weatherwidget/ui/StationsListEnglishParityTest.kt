package com.weatherwidget.ui

import com.weatherwidget.shared.observations.StaleObservationFallback
import com.weatherwidget.shared.observations.StationsList
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Desktop's stations-list subtitle ([StationsList.englishSubtitle]) equals Android's `obs_subtitle_*`. Pure JVM. */
@Category(ShortDuration::class)
class StationsListEnglishParityTest {
    private val strings: Map<String, String> by lazy {
        val resDir = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "values/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\\"", "\"") }
    }

    private fun res(name: String, vararg args: String) =
        args.foldIndexed(strings.getValue(name)) { i, s, arg -> s.replace("%${i + 1}\$s", arg) }

    private fun result(subtitle: StationsList.Subtitle, ageMs: Long? = null) =
        StationsList.Result(emptyList<Any>(), subtitle, ageMs, "")

    @Test
    fun `every subtitle matches its resource`() {
        val src = "Open-Meteo"
        assertEquals(res("obs_subtitle_none_found", src), StationsList.englishSubtitle(result(StationsList.Subtitle.NONE_FOUND), src))
        assertEquals(
            res("obs_subtitle_stale", src, StaleObservationFallback.formatAge(7_200_000L)),
            StationsList.englishSubtitle(result(StationsList.Subtitle.STALE, 7_200_000L), src),
        )
        assertEquals(res("obs_subtitle_nearby_stations"), StationsList.englishSubtitle(result(StationsList.Subtitle.NEARBY_STATIONS), src))
        assertEquals(res("obs_subtitle_latest_reading", src), StationsList.englishSubtitle(result(StationsList.Subtitle.LATEST_READING), src))
    }
}
