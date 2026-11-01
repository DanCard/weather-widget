package com.weatherwidget.architecture

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Android's SQLite ships with the OS, so migrations are limited to what minSdk's SQLite supports.
 * `RENAME COLUMN` needs 3.25 (API 28 has 3.22) and `DROP COLUMN` needs 3.35 (API 30 has 3.28); both
 * are syntax errors on older devices and crash database open on upgrade. This catches them on every
 * unit run, whether or not an old emulator is connected.
 * plans/261101-migrations-sqlite-compat-below-api-34.md
 */
@Category(ShortDuration::class)
class MigrationSqliteCompatGuardTest {
    private val unsupportedAlter = Regex("""ALTER\s+TABLE\s+\S+\s+(RENAME|DROP)\s+COLUMN""", RegexOption.IGNORE_CASE)

    @Test
    fun migrationsDoNotUseAlterTableFeaturesNewerThanMinSdkSqlite() {
        val dir =
            listOf(File("src/main/java"), File("app/src/main/java"))
                .map { File(it, "com/weatherwidget/data/local") }
                .firstOrNull { it.isDirectory }
        assertTrue("could not locate data/local sources from ${File(".").absolutePath}", dir != null)
        val sources = dir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected WeatherDatabase.kt among the scanned sources", sources.any { it.name == "WeatherDatabase.kt" })

        val offenders =
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (unsupportedAlter.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
                }
            }
        assertTrue(
            "Recreate the table instead, as MIGRATION_57_58 does (minSdk's SQLite rejects this):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
