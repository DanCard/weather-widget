package com.weatherwidget.data.repository

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class AddressQueryCleanerTest {
    @Test
    fun `flattenMultilineInput joins lines with commas without double commas`() {
        val pasted = "7900 East Princess Drive\nScottsdale, 85255 AZ\nUnit 1182.1"
        assertEquals(
            "7900 East Princess Drive, Scottsdale, 85255 AZ, Unit 1182.1",
            AddressQueryCleaner.flattenMultilineInput(pasted),
        )

        val crlfWithTrailingCommas = "7900 East Princess Drive,\r\n\r\nScottsdale, 85255 AZ,\r\nUnit 1182.1\r\n"
        assertEquals(
            "7900 East Princess Drive, Scottsdale, 85255 AZ, Unit 1182.1",
            AddressQueryCleaner.flattenMultilineInput(crlfWithTrailingCommas),
        )
    }

    @Test
    fun `flattenMultilineInput leaves single-line typing untouched`() {
        assertEquals("7900 East ", AddressQueryCleaner.flattenMultilineInput("7900 East "))
        assertEquals("Scottsdale, AZ", AddressQueryCleaner.flattenMultilineInput("Scottsdale, AZ"))
    }

    @Test
    fun `candidateQueries builds normalized, unit-stripped, and city fallback for multiline paste`() {
        val pasted = "7900 East Princess Drive\nScottsdale, 85255 AZ\nUnit 1182.1"

        assertEquals(
            listOf(
                "7900 East Princess Drive, Scottsdale, 85255 AZ, Unit 1182.1",
                "7900 East Princess Drive, Scottsdale, 85255 AZ",
                "Scottsdale, 85255 AZ",
            ),
            AddressQueryCleaner.candidateQueries(pasted),
        )
    }

    @Test
    fun `candidateQueries strips common secondary unit designators`() {
        assertEquals(
            "123 Main St, Phoenix, AZ 85001",
            AddressQueryCleaner.stripUnitDesignators("123 Main St Apt. 4B, Phoenix, AZ 85001"),
        )
        assertEquals(
            "123 Main St, Phoenix, AZ 85001",
            AddressQueryCleaner.stripUnitDesignators("123 Main St, Suite 200-A, Phoenix, AZ 85001"),
        )
        assertEquals(
            "123 Main St, Phoenix, AZ 85001",
            AddressQueryCleaner.stripUnitDesignators("123 Main St #12, Phoenix, AZ 85001"),
        )
        assertEquals(
            "7900 East Princess Drive, Scottsdale, AZ 85255",
            AddressQueryCleaner.stripUnitDesignators("Unit 1182.1, 7900 East Princess Drive, Scottsdale, AZ 85255"),
        )
    }

    @Test
    fun `candidateQueries returns empty list for blank input`() {
        assertEquals(emptyList<String>(), AddressQueryCleaner.candidateQueries("  \n \r\n "))
    }

    @Test
    fun `candidateQueries does not duplicate when no unit or comma is present`() {
        assertEquals(listOf("85255"), AddressQueryCleaner.candidateQueries("85255"))
    }
}
