package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.test.category.MediumDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * The 2026-11-02 stall: a Settings refresh started on Open-Meteo finished after the switch to Google and
 * put Open-Meteo's snapshot back, so the popup sat on "Loading Google Weather…" with every source's rows
 * stored. DesktopSnapshotPublish + DesktopWeatherRepository + DesktopSourceWait on a real database.
 * plans/261102-desktop-late-refresh-overwrites-selected-source.md
 */
@Category(MediumDuration::class)
class DesktopSnapshotPublishTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 37.42
    private val lon = -122.08

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("snapshot-publish-test", ".db")
        db = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(db)
        listOf("OPEN_METEO", "GOOGLE_WEATHER").forEach { source ->
            dao.upsertForecasts(
                lat, lon, source,
                listOf(DailyForecast(date = LocalDate.now().toString(), highTemp = 60f, lowTemp = 50f, condition = "Clear", source = source)),
            )
        }
    }

    @After
    fun tearDown() {
        db.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun repository(source: String, latitude: Double = lat) =
        DesktopWeatherRepository(DesktopWeatherService(latitude, lon, source), dao, latitude, lon, source)

    /** What the popup decides from a snapshot; the selected source's own rows draw, anything else waits. */
    private fun sourceWait(snapshot: ForecastSnapshot, selected: String) = DesktopSourceWait.resolve(
        snapshotIsSelectedSource = snapshot.sourceId == selected,
        loadIsSlow = true,
        snapshotIsEmpty = snapshot.raw.hourly.isEmpty() && snapshot.raw.daily.isEmpty(),
        fetchEndedEmpty = false,
    )

    @Test
    fun `only the repository still selected may publish`() {
        val owner = Any()
        assertTrue(DesktopSnapshotPublish.isCurrent(owner, owner))
        assertFalse(DesktopSnapshotPublish.isCurrent(owner, Any()))
        assertFalse(DesktopSnapshotPublish.isCurrent(null, owner))
        assertFalse(DesktopSnapshotPublish.isCurrent(owner, null))
        assertFalse(DesktopSnapshotPublish.isCurrent(null, null))
    }

    @Test
    fun `the same source at another location is a different owner`() {
        val before = repository("GOOGLE_WEATHER")
        val after = repository("GOOGLE_WEATHER", latitude = 52.23)
        assertEquals(before.sourceId, after.sourceId)
        assertFalse(DesktopSnapshotPublish.isCurrent(before, after))
    }

    @Test
    fun `a refresh finishing after a source switch leaves the selected source drawn`() = runBlocking {
        val refreshOwner = repository("OPEN_METEO")
        val selected = repository("GOOGLE_WEATHER")
        val shown = selected.loadCached()!!
        assertNull(sourceWait(shown, selected.sourceId))

        val late = refreshOwner.loadCached()!!
        assertEquals("OPEN_METEO", late.sourceId)
        // The stall, as the popup saw it when this snapshot was published unchecked.
        assertEquals(DesktopSourceWait.LOADING, sourceWait(late, selected.sourceId))

        val published = if (DesktopSnapshotPublish.isCurrent(refreshOwner, selected)) late else selected.loadCached()!!
        println("published source=${published.sourceId} wait=${sourceWait(published, selected.sourceId)}")
        assertEquals("GOOGLE_WEATHER", published.sourceId)
        assertNull(sourceWait(published, selected.sourceId))
    }

    /** Every assignment outside the gate is listed with the reason it cannot be stale. */
    @Test
    fun `the popup forecast is only assigned through the gate`() {
        val file = listOf(
            File("src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt"),
            File("desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt"),
        ).first { it.isFile }
        val allowed = setOf(
            "forecast = snapshot", // publishIfCurrent itself
            "forecast = cached", // LaunchedEffect(repository): its first load, and the location adoption
            "forecast = null", // location interstitial, in a repository-keyed effect
            "repo.loadCached()?.takeUnless { holdForLocationChange(it) }?.let { forecast = it }", // safety-net loop, same key
            "forecast = forecast,", // the popup's parameter
        )
        val assignment = Regex("""(?<![\w.])forecast\s*=[^=]""")
        val lines = file.readLines().map { it.trim() }.filter { !it.startsWith("//") && !it.startsWith("*") && assignment.containsMatchIn(it) }
        val offenders = lines.filterNot { it in allowed }
        println("forecast assignments=${lines.size} offenders=$offenders")
        assertTrue("expected the gate's own assignment", "forecast = snapshot" in lines)
        assertEquals("forecast assigned outside publishIfCurrent", emptyList<String>(), offenders)
    }
}
