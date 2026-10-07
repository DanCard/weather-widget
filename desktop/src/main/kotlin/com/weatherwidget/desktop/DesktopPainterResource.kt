package com.weatherwidget.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.decodeToImageVector
import org.jetbrains.compose.resources.decodeToSvgPainter

/**
 * Classpath painter loader, ported from JetBrains' migration snippet for the deprecated
 * `androidx.compose.ui.res.painterResource(String)`:
 * https://github.com/JetBrains/compose-multiplatform-core/pull/1457
 *
 * Keeps loading the Android-style vector XMLs under `desktop/src/main/resources/drawable`
 * so the existing `drawable/<iconName>.xml` path protocol (see [WeatherIcon]) stays intact.
 */
@Composable
internal fun painterResource(resourcePath: String): Painter = when (resourcePath.substringAfterLast('.')) {
    "svg" -> rememberSvgResource(resourcePath)
    "xml" -> rememberVectorXmlResource(resourcePath)
    else -> rememberBitmapResource(resourcePath)
}

@Composable
private fun rememberBitmapResource(path: String): Painter {
    return remember(path) { BitmapPainter(readResourceBytes(path).decodeToImageBitmap()) }
}

@Composable
private fun rememberVectorXmlResource(path: String): Painter {
    val density = LocalDensity.current
    val imageVector = remember(density, path) { readResourceBytes(path).decodeToImageVector(density) }
    return rememberVectorPainter(imageVector)
}

@Composable
private fun rememberSvgResource(path: String): Painter {
    val density = LocalDensity.current
    return remember(density, path) { readResourceBytes(path).decodeToSvgPainter(density) }
}

private object ResourceLoader

private fun readResourceBytes(resourcePath: String): ByteArray =
    ResourceLoader.javaClass.classLoader.getResourceAsStream(resourcePath)?.readAllBytes()
        ?: error("Missing desktop resource: $resourcePath")
