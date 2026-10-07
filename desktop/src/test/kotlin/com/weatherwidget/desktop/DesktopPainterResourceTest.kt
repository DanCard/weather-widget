package com.weatherwidget.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Guards the classpath painter shim ([painterResource]) against the failure mode that motivated it:
 * Android VectorDrawable XMLs under `src/main/resources/drawable` must keep decoding after the
 * deprecated `androidx.compose.ui.res.painterResource(String)` was replaced.
 */
@Category(MediumDuration::class)
class DesktopPainterResourceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `every drawable resource decodes to a painter`() {
        val drawableDir = File("src/main/resources/drawable")
        assertTrue("drawable dir missing: ${drawableDir.absolutePath}", drawableDir.isDirectory)
        val resourcePaths = drawableDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "xml" }
            .map { "drawable/${it.name}" }
            .sorted()
        assertTrue("no drawables found in ${drawableDir.absolutePath}", resourcePaths.isNotEmpty())

        resourcePaths.forEach { path ->
            composeTestRule.setContent {
                Image(
                    painter = painterResource(path),
                    contentDescription = path,
                    modifier = Modifier.size(24.dp),
                )
            }
            composeTestRule.waitForIdle()
        }
    }
}
