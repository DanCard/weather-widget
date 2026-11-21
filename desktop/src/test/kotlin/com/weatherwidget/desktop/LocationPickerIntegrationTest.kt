package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.remote.NominatimApi
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.test.category.LongDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class LocationPickerIntegrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `search matches are displayed and clickable within default window dimensions`() {
        val mockShared = mockk<SharedLocationResolver>()
        val mockPhone = mockk<PhoneLocator>()
        val mockTimezone = mockk<TimezoneLocator>()

        coEvery { mockShared.suggestPrefill(any()) } returns null
        coEvery { mockPhone.locate(any()) } returns null
        coEvery { mockTimezone.locate() } returns null
        every { mockPhone.isAvailable() } returns false

        val lvivMatches = listOf(
            ResolvedLocation(
                lat = 49.8419,
                lon = 24.0316,
                label = "Lviv, Lviv Urban Hromada, Lviv Raion, Lviv Oblast, Ukraine",
                source = "Nominatim",
            ),
            ResolvedLocation(
                lat = 49.6842,
                lon = 23.8965,
                label = "Lviv Oblast, Ukraine",
                source = "Nominatim",
            ),
        )
        coEvery { mockShared.searchText("lviv, ukraine") } returns lvivMatches

        val resolver = LocationResolver(mockPhone, mockTimezone, mockShared)

        var selectedLocation: ResolvedLocation? = null

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 560.dp, height = 680.dp)) {
                LocationPicker(
                    resolver = resolver,
                    allowAutoSelect = false,
                    onLocationSelected = { selectedLocation = it },
                )
            }
        }

        // Search for "lviv, ukraine"
        composeTestRule.onNodeWithText("Address, ZIP, or city").performTextInput("lviv, ukraine")
        composeTestRule.onNodeWithText("Search").performClick()

        // Verify search results are displayed
        composeTestRule.onNodeWithText("Choose one of these 2 matches:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lviv, Lviv Urban Hromada, Lviv Raion, Lviv Oblast, Ukraine").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lviv Oblast, Ukraine").assertIsDisplayed()

        // Tap on the first match
        composeTestRule.onNodeWithText("Lviv, Lviv Urban Hromada, Lviv Raion, Lviv Oblast, Ukraine").performClick()

        assertNotNull(selectedLocation)
        assertEquals(49.8419, selectedLocation?.lat ?: 0.0, 0.0001)
        assertEquals(24.0316, selectedLocation?.lon ?: 0.0, 0.0001)
    }

    @Test
    fun `recent locations dropdown appears on focus and selection chooses location`() {
        val mockShared = mockk<SharedLocationResolver>()
        val mockPhone = mockk<PhoneLocator>()
        val mockTimezone = mockk<TimezoneLocator>()

        coEvery { mockShared.suggestPrefill(any()) } returns null
        coEvery { mockPhone.locate(any()) } returns null
        coEvery { mockTimezone.locate() } returns null
        every { mockPhone.isAvailable() } returns false

        val recents = listOf(
            RecentLocation(lat = 50.4501, lon = 30.5234, label = "Kyiv, Ukraine"),
            RecentLocation(lat = 49.8419, lon = 24.0316, label = "Lviv, Ukraine"),
        )

        val resolver = LocationResolver(mockPhone, mockTimezone, mockShared)

        var selectedLocation: ResolvedLocation? = null

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 560.dp, height = 680.dp)) {
                LocationPicker(
                    resolver = resolver,
                    allowAutoSelect = false,
                    recentLocations = recents,
                    onLocationSelected = { selectedLocation = it },
                )
            }
        }

        // Tap the search input to focus it
        composeTestRule.onNodeWithText("Address, ZIP, or city").performClick()

        // Verify dropdown items appear
        composeTestRule.onNodeWithText("Kyiv, Ukraine").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lviv, Ukraine").assertIsDisplayed()

        // Select Kyiv
        composeTestRule.onNodeWithText("Kyiv, Ukraine").performClick()

        assertNotNull(selectedLocation)
        assertEquals(50.4501, selectedLocation?.lat ?: 0.0, 0.0001)
        assertEquals("Kyiv, Ukraine", selectedLocation?.label)
    }

    @Test
    fun `hitting enter in search text box triggers search and displays matches`() {
        val mockShared = mockk<SharedLocationResolver>()
        val mockPhone = mockk<PhoneLocator>()
        val mockTimezone = mockk<TimezoneLocator>()

        coEvery { mockShared.suggestPrefill(any()) } returns null
        coEvery { mockPhone.locate(any()) } returns null
        coEvery { mockTimezone.locate() } returns null
        every { mockPhone.isAvailable() } returns false

        val lvivMatches = listOf(
            ResolvedLocation(
                lat = 49.8419,
                lon = 24.0316,
                label = "Lviv, Ukraine",
                source = "Nominatim",
            ),
        )
        coEvery { mockShared.searchText("lviv") } returns lvivMatches

        val resolver = LocationResolver(mockPhone, mockTimezone, mockShared)

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 560.dp, height = 680.dp)) {
                LocationPicker(
                    resolver = resolver,
                    allowAutoSelect = false,
                    onLocationSelected = {},
                )
            }
        }

        // Type "lviv" and trigger search via Enter key
        val searchNode = composeTestRule.onNodeWithText("Address, ZIP, or city")
        searchNode.performTextInput("lviv")
        searchNode.performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.Enter)
        }

        // The search runs on Dispatchers.IO, which the compose clock does not track: wait for it.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("One match — confirm it:").fetchSemanticsNodes().isNotEmpty()
        }

        // Verify search results are displayed
        composeTestRule.onNodeWithText("One match — confirm it:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lviv, Ukraine").assertIsDisplayed()
    }

    @Test
    fun `pasting multiline address with unit number resolves through real SharedLocationResolver`() {
        val mockPhone = mockk<PhoneLocator>()
        val mockTimezone = mockk<TimezoneLocator>()
        coEvery { mockPhone.locate(any()) } returns null
        coEvery { mockTimezone.locate() } returns null
        every { mockPhone.isAvailable() } returns false

        val json = Json { ignoreUnknownKeys = true }
        val scottsdale = """
            [{"display_name":"7900, East Princess Drive, Scottsdale, Maricopa County, Arizona, 85255, United States",
              "lat":"33.6441650","lon":"-111.9096590",
              "address":{"house_number":"7900","road":"East Princess Drive","city":"Scottsdale","state":"Arizona",
                "ISO3166-2-lvl4":"US-AZ","postcode":"85255","country":"United States","country_code":"us"}}]
        """.trimIndent()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val q = request.url.parameters["q"].orEmpty()
                    val body = if (q == "7900 East Princess Drive, Scottsdale, 85255 AZ") scottsdale else "[]"
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val realShared = SharedLocationResolver(
            nominatimApi = NominatimApi(client, json),
            ipGeolocationApi = IpGeolocationApi(client, json),
        )
        val resolver = LocationResolver(mockPhone, mockTimezone, realShared)

        var selectedLocation: ResolvedLocation? = null
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 560.dp, height = 680.dp)) {
                LocationPicker(
                    resolver = resolver,
                    allowAutoSelect = false,
                    onLocationSelected = { selectedLocation = it },
                )
            }
        }

        val searchNode = composeTestRule.onNodeWithText("Address, ZIP, or city")
        searchNode.performTextInput("7900 East Princess Drive\nScottsdale, 85255 AZ\nUnit 1182.1")
        composeTestRule.onNodeWithText("Search").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("7900 East Princess Drive, Scottsdale, AZ 85255")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithText("7900 East Princess Drive, Scottsdale, AZ 85255").performClick()
        assertNotNull(selectedLocation)
        assertEquals(33.644165, selectedLocation?.lat ?: 0.0, 0.0001)
        assertEquals(-111.909659, selectedLocation?.lon ?: 0.0, 0.0001)
    }
}
