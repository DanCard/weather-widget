import os
import re

files_to_update = [
    "app/src/test/java/com/weatherwidget/widget/HourlyGraphDayLabelRobolectricTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureFetchDotColorTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphJunctionTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphLabelPlacementRobolectricTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererActualsTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererContinuityTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererFetchDotTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererLabelPlacementTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererStalenessTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphRendererWapiTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureLabelCollisionOrderTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureLabelSuppressionTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TruthCurveLinearRenderingTest.kt",
    "app/src/test/java/com/weatherwidget/widget/handlers/HourlyZoomCenteringRoboTest.kt",
    "app/src/test/java/com/weatherwidget/widget/handlers/TemperatureFetchDotUpdateRoboTest.kt",
    "app/src/test/java/com/weatherwidget/widget/TemperatureGraphPlateauOverlapTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/HourlyGraphDayLabelTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/TemperatureFetchDotIntegrationTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/TemperatureGhostLabelIntegrationTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/TemperatureGhostLineTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/TemperatureGraphClutterTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/TemperatureGraphLabelGeneralTest.kt",
    "app/src/androidTest/java/com/weatherwidget/widget/TemperatureGraphLabelTest.kt",
]

replacements = {
    "TemperatureGraphRenderer.HourData": "HourData",
    "TemperatureGraphRenderer.TemperatureRole": "TemperatureRole",
    "TemperatureGraphRenderer.FetchDotDebug": "FetchDotDebug",
    "TemperatureGraphRenderer.LabelPlacementDebug": "LabelPlacementDebug",
    "TemperatureGraphRenderer.GhostLineDebug": "GhostLineDebug",
    "TemperatureGraphRenderer.ActualLineDebug": "ActualLineDebug",
    "TemperatureGraphRenderer.DayLabelPlacementDebug": "DayLabelPlacementDebug",
    "TemperatureGraphRenderer.PointsDebug": "PointsDebug",
}

for file_path in files_to_update:
    full_path = os.path.join(os.getcwd(), file_path)
    if not os.path.exists(full_path):
        print(f"Skipping missing file: {file_path}")
        continue
        
    with open(full_path, 'r') as f:
        content = f.read()
    
    new_content = content
    replaced_any = False
    used_classes = set()
    
    # Sort keys by length descending to avoid partial matches
    sorted_replacements = sorted(replacements.items(), key=lambda x: len(x[0]), reverse=True)
    
    for old, new in sorted_replacements:
        if old in new_content:
            new_content = new_content.replace(old, new)
            replaced_any = True
            used_classes.add(new)
            
    if replaced_any:
        # Check if we need to add imports (if in a different package)
        package_match = re.search(r"package (com\.weatherwidget\.widget\.[\w.]+)", content)
        # If it's a subpackage, it needs imports
        is_subpackage = package_match and package_match.group(1) != "com.weatherwidget.widget"
        
        if is_subpackage:
            for cls in sorted(list(used_classes)):
                import_stmt = f"import com.weatherwidget.widget.{cls}"
                if import_stmt not in new_content:
                    import_matches = list(re.finditer(r"import .*\n", new_content))
                    if import_matches:
                        inserted = False
                        for i, match in enumerate(import_matches):
                            if import_stmt < match.group(0):
                                new_content = new_content[:match.start()] + import_stmt + "\n" + new_content[match.start():]
                                import_matches = list(re.finditer(r"import .*\n", new_content))
                                inserted = True
                                break
                        if not inserted:
                            last_import_end = import_matches[-1].end()
                            new_content = new_content[:last_import_end] + import_stmt + "\n" + new_content[last_import_end:]
                            import_matches = list(re.finditer(r"import .*\n", new_content))
                    else:
                        package_line = re.search(r"package .*\n", new_content)
                        new_content = new_content[:package_line.end()] + "\n" + import_stmt + "\n" + new_content[package_line.end():]

        with open(full_path, 'w') as f:
            f.write(new_content)
        print(f"Updated {file_path}")
    else:
        print(f"No changes needed for {file_path}")
