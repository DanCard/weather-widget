import sys

file_path = "app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt"

with open(file_path, "r") as f:
    content = f.read()

old_string = """        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                .mapValues { (_, items) -> items.find { it.source == displaySource.id } ?: items.first() }"""

new_string = """        // Build weather map: prefer the selected display source, fallback to generic gap
        val weatherByDate =
            weatherList
                .filter { it.source == displaySource.id || it.source == WeatherSource.GENERIC_GAP.id }
                .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
                .mapValues { (_, items) -> 
                    val preferred = items.find { it.source == displaySource.id }
                    if (preferred != null && (preferred.highTemp == null || preferred.lowTemp == null)) {
                        items.find { it.source == WeatherSource.GENERIC_GAP.id && it.highTemp != null && it.lowTemp != null } ?: preferred
                    } else {
                        preferred ?: items.first()
                    }
                }"""

if old_string in content:
    content = content.replace(old_string, new_string)
    with open(file_path, "w") as f:
        f.write(content)
    print("Patched successfully!")
else:
    print("String not found!")

