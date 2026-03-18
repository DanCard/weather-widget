import os
import re

def patch_file(path):
    with open(path, 'r') as f:
        content = f.read()

    # Replace TestData.hourlyActual(dateTime = "...", temperature = XXf) 
    # with TestData.observation(timestamp = java.time.LocalDateTime.parse("...").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = XXf)
    
    def replacer(match):
        dt = match.group(1)
        temp = match.group(2)
        return f'TestData.observation(timestamp = java.time.LocalDateTime.parse("{dt}").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = {temp})'

    content = re.sub(r'TestData\.hourlyActual\(\s*dateTime\s*=\s*"([^"]+)",\s*temperature\s*=\s*([^)]+)\)', replacer, content)

    # Some tests might just do `TestData.hourlyActual(dateTime = "2026-02-20T12:00")`
    def replacer2(match):
        dt = match.group(1)
        return f'TestData.observation(timestamp = java.time.LocalDateTime.parse("{dt}").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())'

    content = re.sub(r'TestData\.hourlyActual\(\s*dateTime\s*=\s*"([^"]+)"\)', replacer2, content)

    # Some might do `TestData.hourlyActual("2026-02-20T10:00", temperature = 68f)`
    def replacer3(match):
        dt = match.group(1)
        temp = match.group(2)
        return f'TestData.observation(timestamp = java.time.LocalDateTime.parse("{dt}").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = {temp})'

    content = re.sub(r'TestData\.hourlyActual\(\s*"([^"]+)",\s*temperature\s*=\s*([^)]+)\)', replacer3, content)

    with open(path, 'w') as f:
        f.write(content)


patch_file('app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerActualsTest.kt')
patch_file('app/src/test/java/com/weatherwidget/widget/handlers/DailyTapActualsRegressionTest.kt')
print("Tests patched")
