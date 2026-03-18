import re

path = 'app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt'
with open(path, 'r') as f:
    content = f.read()

# Replace the end of buildHourDataList
old_loop = """        var lastActual: Float? = null
        for (i in finalHours.indices) {
            if (finalHours[i].isActual && finalHours[i].actualTemperature != null) {
                lastActual = finalHours[i].actualTemperature
            } else if (finalHours[i].dateTime.isBefore(now)) {
                finalHours[i] = finalHours[i].copy(isActual = true, actualTemperature = lastActual)
            }
        }

        return finalHours"""

new_loop = """        var lastActual: Float? = null
        for (i in finalHours.indices) {
            if (finalHours[i].isActual && finalHours[i].actualTemperature != null) {
                lastActual = finalHours[i].actualTemperature
            } else if (finalHours[i].dateTime.isBefore(now)) {
                if (lastActual != null) {
                    finalHours[i] = finalHours[i].copy(isActual = true, actualTemperature = lastActual)
                } else {
                    finalHours[i] = finalHours[i].copy(isActual = false, actualTemperature = null)
                }
            }
        }

        return finalHours"""

content = content.replace(old_loop, new_loop)

# Replace the forecast loop step
old_step = """            }
            currentHour = currentHour.plusHours(1)
        }"""
new_step = """            }
            currentHour = currentHour.plusMinutes(30)
        }"""
content = content.replace(old_step, new_step)

with open(path, 'w') as f:
    f.write(content)
