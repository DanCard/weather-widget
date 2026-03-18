import re

path = 'app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt'
with open(path, 'r') as f:
    content = f.read()

# 1. Update hourWidth calculation
old_hourWidth = "val hourWidth = widthPx / hours.size.toFloat()"
new_hourWidth = """val minTimeEpoch = hours.firstOrNull()?.dateTime?.toEpochSecond(java.time.ZoneOffset.UTC) ?: 0L
        val maxTimeEpoch = hours.lastOrNull()?.dateTime?.toEpochSecond(java.time.ZoneOffset.UTC) ?: 0L
        val timeRangeHours = if (maxTimeEpoch > minTimeEpoch) (maxTimeEpoch - minTimeEpoch) / 3600f else hours.size.toFloat() - 1f
        val hourWidth = widthPx / (timeRangeHours + 1f).coerceAtLeast(1f)"""

content = content.replace(old_hourWidth, new_hourWidth)

# 2. Update x calculation
old_x = "val x = hourWidth * index + hourWidth / 2"
new_x = """val pointEpoch = hours[index].dateTime.toEpochSecond(java.time.ZoneOffset.UTC)
            val x = hourWidth / 2f + ((pointEpoch - minTimeEpoch) / 3600f) * hourWidth"""

content = content.replace(old_x, new_x)

# 3. Tenth of digit formatting
content = content.replace('String.format("%.0f",', 'String.format("%.1f",')
content = content.replace('String.format("%.0f°",', 'String.format("%.1f°",')

# 4. Remove spline smoothing
content = content.replace(
    'val smoothedLabelTemps = GraphRenderUtils.smoothValues(rawLabelTemps, iterations = 1)',
    'val smoothedLabelTemps = rawLabelTemps'
)
content = content.replace(
    'val smoothedTruthTemps = GraphRenderUtils.smoothValues(rawTruthTemps, iterations = 3)',
    'val smoothedTruthTemps = rawTruthTemps'
)

with open(path, 'w') as f:
    f.write(content)

print("Updated TemperatureGraphRenderer.kt")
