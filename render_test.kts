import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

val width = 100
val height = 400

val hY = 50f
val lY = 350f
val effectiveLowY = lY

val topColor = Color(0xF4, 0xC5, 0x42) // FORECAST_SUNNY
val bottomColor = Color(0x8E, 0x99, 0xA4) // FORECAST_CLOUDY

val ratio = 0.72f
val normalizedRatio = Math.max(0f, Math.min(ratio, 1f))
val goldEnd = Math.max(0f, Math.min(1f - normalizedRatio, 1f))
val transitionLength = Math.min(0.12f, normalizedRatio * 0.5f)
val greyStart = Math.max(goldEnd, Math.min(goldEnd + transitionLength, 1f))

println("goldEnd: $goldEnd, greyStart: $greyStart")

// Java2D doesn't have multi-stop gradients easily without LinearGradientPaint, so let's use LinearGradientPaint
val stops = floatArrayOf(0f, goldEnd, greyStart, 1f)
val colors = arrayOf(topColor, topColor, bottomColor, bottomColor)

val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
val g2d = img.createGraphics()
g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
g2d.color = Color.WHITE
g2d.fillRect(0, 0, width, height)

val gradient = java.awt.LinearGradientPaint(
    0f, hY, 0f, effectiveLowY,
    stops, colors
)

g2d.paint = gradient
g2d.stroke = BasicStroke(20f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
g2d.draw(Line2D.Float(50f, hY, 50f, effectiveLowY))

g2d.dispose()
ImageIO.write(img, "png", File("test_bar.png"))
